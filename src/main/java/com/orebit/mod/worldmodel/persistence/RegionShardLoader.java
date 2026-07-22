package com.orebit.mod.worldmodel.persistence;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.hpa.RegionShardResidency;
import com.orebit.mod.worldmodel.hpa.StraddleSet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The Stage-2 bounded-region-RAM <b>on-demand shard loader</b> (DESIGN-worldmodel-persistence.md — coarse-only
 * startup + atomic lazy-load). Under {@code hpa.lazyLoad}, {@link RegionPersistence#loadCoarseOnly} loads only the
 * per-dimension coarse levels at start and indexes which shards exist on disk; this class pages those shards in
 * <b>lazily</b> as the planner touches their leaves.
 *
 * <h2>Request → drain (the hpa↔persistence layering)</h2>
 * The consumers that discover a needed shard live in {@code hpa} (the clobber-guard in
 * {@link com.orebit.mod.worldmodel.hpa.PyramidMerger} / {@code ResourceMerger}, and
 * {@code RegionGrid.ensureLeaf}). They must not depend on {@code persistence}, so they REQUEST a load by adding a
 * shard key to {@link RegionShardResidency#enqueueLoad} — never by calling this loader. This class (one package
 * up) DRAINS that queue on the tick thread, so the dependency arrow only ever points {@code persistence → hpa}.
 *
 * <h2>Atomic, budgeted drain</h2>
 * {@link #drain} is wired off {@code onWorldTickEnd} beside {@link RegionPersistence#tick} and
 * {@code HpaMaintenance.flush}. Each requested shard is loaded <b>atomically</b> ({@link #loadShard} pages the
 * WHOLE shard — cost + resource levels 0..5 — in one call; measured ~11–34 ms now that the on-disk format is
 * un-gzipped, so it is tick-safe) under a per-tick wall-clock budget ({@code pathing.regionShardLoadBudgetMs})
 * with a ≥1-per-tick count backstop so progress is always guaranteed. Elapsed is checked AFTER each shard, so the
 * worst-case overshoot is one shard.
 *
 * <h2>Live-wins + reconcile</h2>
 * {@link #loadShard} decodes with a {@link StraddleSet} (so a row already built live this session is skipped, and
 * a skipped coarse cell is recorded), then runs {@link RegionReconciler#reconcile} to re-derive exactly those
 * straddle cells now that the shard's persisted children are interned beside them — composing with the
 * clobber-guard (a straddle whose sibling is still in another non-resident shard defers again). Finally it marks
 * the shard resident. All on the tick thread (the same thread {@code HpaMaintenance.flush} mutates the pyramid on),
 * so it never races a pyramid grow with the maintenance path.
 *
 * <p><b>Never throws onto the tick thread.</b> A missing/corrupt shard file is treated as absent (the cache
 * semantic — the live world rebuilds it), exactly like {@link RegionPersistence}'s load failures.
 *
 * <p>Static utility mirroring {@link RegionPersistence}'s shape; all I/O is COLD (a handful of shard loads as the
 * bot explores), so normal allocation is fine.
 */
public final class RegionShardLoader {

    private RegionShardLoader() {}

    /** Count backstop / progress guarantee: always page in at least this many shards per tick, even if the first
     *  already blew the wall-clock budget (so a starved budget can never wedge the lazy-load pipeline). */
    private static final int MIN_LOADS_PER_TICK = 1;

    /** Load-failure log throttle. */
    private static volatile long loadFailures = 0;

    /**
     * Drain this dimension's shard-load requests until the per-tick {@code pathing.regionShardLoadBudgetMs}
     * wall-clock budget is spent (after at least {@link #MIN_LOADS_PER_TICK} shard). No-op when no grid exists or
     * nothing is requested — under eager-load-all the residency's persisted-shard index is empty, so no request is
     * ever enqueued and this is a cheap per-tick queue-empty test. Runs on the tick thread.
     */
    public static void drain(ServerLevel level) {
        final MinecraftServer server = level.getServer();
        if (server == null) return;
        final RegionGrid grid = RegionGrid.peek(level);
        if (grid == null) return;
        final RegionShardResidency residency = grid.residency();
        if (residency == null || !residency.hasLoadRequests()) return;

        final long budgetNanos = (long) (ConfigLoader.config().regionShardLoadBudgetMs() * 1_000_000.0);
        final long start = System.nanoTime();

        int processed = 0;
        Long key;
        // pollLoadRequest() atomically claims the least-keyed requested shard (ascending, deterministic drain).
        while ((key = residency.pollLoadRequest()) != null) {
            loadShard(server, level, grid, RegionShardResidency.shardX(key), RegionShardResidency.shardZ(key));
            processed++;
            if (processed >= MIN_LOADS_PER_TICK && System.nanoTime() - start >= budgetNanos) break;
        }
    }

    /**
     * Page shard {@code (sx, sz)} into RAM atomically (Stage-2 lazy-load): decode its cost + resource leaf files
     * ({@code hpa.<sx>.<sz>.bin} / {@code res.<sx>.<sz>.bin}) with a {@link StraddleSet} (live-wins: rows already
     * built this session are skipped, their coarse skips recorded), {@link RegionReconciler#reconcile} the reported
     * straddle cells, then mark the shard resident. Idempotent — a no-op if the shard is already resident. A
     * missing/unreadable file is treated as absent (its half simply contributes nothing). Never throws onto the
     * tick thread.
     */
    public static void loadShard(MinecraftServer server, ServerLevel level, RegionGrid grid, int sx, int sz) {
        final RegionShardResidency residency = grid.residency();
        if (residency != null && residency.isResident(sx, sz)) return; // already paged in

        final Path dir = RegionPersistence.dimDir(server, level);
        final StraddleSet costStraddle = new StraddleSet();
        final StraddleSet resStraddle = new StraddleSet();

        // Cost leaves (levels 0..5). A missing file → no cost data for this shard (absent, like the cache semantic).
        // The trailing invalidation section merges into the dimension's crossing memory (dominance supplied here,
        // per the load-site rule — BotCaps never leaks into hpa); record()'s antichain makes the merge idempotent
        // beside rows already learned live this session.
        final Path costFile = dir.resolve("hpa." + sx + "." + sz + ".bin");
        if (Files.isRegularFile(costFile)) {
            try (InputStream in = Files.newInputStream(costFile)) {
                CostPyramidCodec.decode(in, grid.pyramid(), costStraddle,
                        grid.crossingMemory(), BotCaps::sigDominates);
            } catch (Throwable t) {
                onLoadFailure(costFile, t);
            }
        }
        // Resource leaves (levels 0..5).
        final Path resFile = dir.resolve("res." + sx + "." + sz + ".bin");
        if (Files.isRegularFile(resFile)) {
            try (InputStream in = Files.newInputStream(resFile)) {
                ResourcePyramidCodec.decode(in, grid.resourcePyramid(), resStraddle);
            } catch (Throwable t) {
                onLoadFailure(resFile, t);
            }
        }

        // Re-derive exactly the coarse cells the decode skipped by live-wins, now that this shard's persisted
        // children are interned (composes with the clobber-guard for a still-non-resident sibling). No-op on an
        // empty straddle set (the common fully-unloaded-shard load). On the tick thread — no planner race with the
        // pyramid grow beyond what HpaMaintenance.flush already incurs.
        RegionReconciler.reconcile(grid.pyramid(), costStraddle, grid.resourcePyramid(), resStraddle);

        if (residency != null) residency.markResident(sx, sz);
    }

    private static void onLoadFailure(Path file, Throwable t) {
        long n = ++loadFailures;
        if (n == 1 || n % 64 == 0) {
            OrebitCommon.LOGGER.warn("[Orebit] region shard lazy-load: could not load {} [{} total] — "
                    + "treating as absent; the live world rebuilds it", file, n, t);
        }
    }
}
