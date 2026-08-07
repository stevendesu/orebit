package com.orebit.mod.worldmodel.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.hpa.RegionShardResidency;
import com.orebit.mod.worldmodel.pathing.NavStore;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The Stage-2 bounded-region-RAM <b>cold-shard evictor</b> (NOTES-perf-and-persistence.md §4,
 * increment 4). Increment 3 built the lazy-load half (coarse-only startup + on-demand atomic shard page-in); this
 * is the half that actually BOUNDS live region RAM — it pages the coldest resident shards back to disk once the
 * count of resident built level-0 cost leaves (the dominant per-shard RAM, ~1.6–5.8 KB each) exceeds a configured
 * cap ({@code hpa.residentLeafCap}). The two together = bounded RAM.
 *
 * <h2>Off by default</h2>
 * {@code hpa.residentLeafCap == 0} means <b>UNBOUNDED / eviction OFF</b> (NOT "evict everything") — {@link #sweep}
 * is a no-op, so bounded RAM stays opt-in until in-game-verified. A positive value is the target max resident
 * built-leaf count.
 *
 * <h2>What an eviction does (per shard)</h2>
 * <ol>
 *   <li><b>Evictable gate (correctness, no timer).</b> A shard is evictable only when EVERY chunk column backing
 *       its resident level-0 leaves is currently UNLOADED in {@link NavStore} — an unloaded chunk fires no
 *       block-changes, so no pending dirty/maintenance work is lost by dropping the region-tier leaf. (This is a
 *       state gate, not a timeout — the no-arbitrary-timers rule.)</li>
 *   <li><b>LRU pick.</b> Among evictable shards, the COLDEST by {@link RegionShardResidency#lastTouched} (a
 *       monotonic touch ordering stamped on load / leaf-build, not a wall clock).</li>
 *   <li><b>Flush-if-dirty first.</b> If the shard has unflushed changes ({@link RegionPersistence#isShardDirty}),
 *       flush it ({@link RegionPersistence#flushShard}) so the on-disk copy is current before its RAM is freed —
 *       budgeted to {@link #MAX_FLUSHES_PER_SWEEP} per sweep (a flush ≈ a load in cost; a dirty shard being evicted
 *       is rare — a hot shard isn't cold). A dirty shard past the budget is left resident for a later sweep.</li>
 *   <li><b>Free RAM.</b> {@link #freeShard} nulls/zeroes the shard's level-0..5 cost + resource rows (leaving them
 *       interned — the maps are never compacted, since a planner-thread search may be reading them) and
 *       {@code markEvicted}s the shard. The coarse (level-6) rows stay resident (they hold the correct-full value).
 *       The shard is {@code markPersisted} so a later touch re-enqueues a reload and the clobber-guard defers on its
 *       now-{@code built=false} children instead of clobbering the coarse.</li>
 * </ol>
 *
 * <h2>Round-trip safety</h2>
 * An eviction returns the shard's subtree to exactly the "coarse-only, shard-not-resident" state a coarse-only
 * startup produces — so a later {@link RegionShardLoader} reload (decode-with-straddle + reconcile + markResident)
 * re-fills it byte-identically to before eviction (proven by {@code EvictionTest}). The clobber-guard composes for
 * free: an evicted child is interned-but-{@code built=false} with a persisted-non-resident shard, exactly the case
 * the guard now defers on.
 *
 * <h2>Concurrency / house style</h2>
 * {@link #sweep} runs on the tick thread ({@code onWorldTickEnd}), the same thread the maintenance flush + shard
 * drain run on, so it never races a pyramid grow. Freeing a row only degrades a concurrent planner read to the §6
 * optimistic default (never a torn/moved row). Cold path (at most a handful of shards per tick, and only when over
 * cap); the per-node search hot path is untouched. Never throws onto the tick thread. The pure core
 * ({@link #evictDownTo} / {@link #freeShard} / {@link #residentLeafCount}) is MC-free (the NavStore gate + flush are
 * injected via {@link EvictionEnv}), so it is unit-testable with no live level.
 */
public final class RegionEvictor {

    private RegionEvictor() {}

    /** Max dirty-shard flushes performed in one sweep (a flush ≈ a shard load in cost). A cold shard being dirty is
     *  rare, so this budget almost never binds; it exists so a bulk-dirty pathological case can't blow one tick. */
    static final int MAX_FLUSHES_PER_SWEEP = 4;

    /**
     * The injected environment the pure eviction core reads (so {@link #evictDownTo} stays MC-free + testable): the
     * NavStore evictable gate + the flush-before-evict. Production backs it with the live {@link NavStore} /
     * {@link RegionPersistence}; the headless test injects trivial answers.
     */
    public interface EvictionEnv {
        /** Whether chunk column {@code (chunkX, chunkZ)} is currently nav-loaded (⇒ its shard is NOT evictable). */
        boolean columnLoaded(int chunkX, int chunkZ);

        /**
         * Ensure the shard {@code (sx, sz)} is safely on disk before its RAM is freed: flush it if dirty (within the
         * per-sweep flush budget). Returns {@code true} if the shard is now current on disk (already clean, or just
         * flushed) so eviction may proceed; {@code false} if it is dirty AND the flush budget is spent this tick —
         * the caller then leaves it resident for a later sweep.
         */
        boolean ensureFlushed(int sx, int sz);
    }

    // ---------------------------------------------------------------------------------------------------
    // Tick entry (production) — resolve the cap + grid + a NavStore/RegionPersistence-backed env, then evict.
    // ---------------------------------------------------------------------------------------------------

    /**
     * The per-level eviction driver, wired off {@code onWorldTickEnd} beside {@link RegionPersistence#tick} /
     * {@link RegionShardLoader#drain}. No-op when {@code hpa.residentLeafCap <= 0} (eviction off — the default), no
     * grid exists, or nothing is over cap. Never throws onto the tick thread.
     */
    public static void sweep(ServerLevel level) {
        final int cap = ConfigLoader.config().residentLeafCap();
        if (cap <= 0) return; // 0 = unbounded / eviction off (default)
        final RegionGrid grid = RegionGrid.peek(level);
        if (grid == null) return;
        final MinecraftServer server = level.getServer();
        if (server == null) return;
        final RegionShardResidency residency = grid.residency();
        if (residency == null) return;

        final EvictionEnv env = new EvictionEnv() {
            private int flushes = 0;

            @Override
            public boolean columnLoaded(int chunkX, int chunkZ) {
                return NavStore.isBuilt(level, chunkX, chunkZ);
            }

            @Override
            public boolean ensureFlushed(int sx, int sz) {
                final long k = RegionShardResidency.shardKey(sx, sz);
                if (!RegionPersistence.isShardDirty(level, k)) return true; // already current on disk
                if (flushes >= MAX_FLUSHES_PER_SWEEP) return false;         // dirty + budget spent → defer this shard
                RegionPersistence.flushShard(server, level, grid, sx, sz);
                flushes++;
                return true;
            }
        };

        try {
            evictDownTo(grid.pyramid(), grid.resourcePyramid(), residency, cap, env);
        } catch (Throwable t) {
            OrebitCommon.LOGGER.warn("[Orebit] region eviction sweep failed — bounded-RAM cap not enforced this "
                    + "tick (retried next tick)", t);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Pure eviction core (MC-free; the NavStore gate + flush are injected) — unit-testable.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Evict the coldest evictable shards until the resident built level-0 cost-leaf count is {@code <= cap}, or no
     * evictable shard remains. Returns the number of level-0 cost leaves freed. A {@code cap <= 0} is a strict
     * no-op (unbounded / eviction off — NOT "evict everything"). Pure: the NavStore gate + flush-before-evict come
     * through {@code env}, so no {@link ServerLevel} is needed.
     */
    public static int evictDownTo(CostPyramid cost, ResourcePyramid res, RegionShardResidency residency,
                                  int cap, EvictionEnv env) {
        if (cap <= 0) return 0; // unbounded / eviction off — never "evict everything"

        // Census: per-shard resident built L0 cost-leaf count + which shards still have a loaded backing chunk.
        final Map<Long, Integer> perShard = new HashMap<>();
        final Set<Long> hasLoadedColumn = new HashSet<>();
        int total = 0;
        final int rows = cost.rowCount(0);
        for (int r = 0; r < rows; r++) {
            if (!cost.isBuilt(0, r) || cost.fragmentRecord(0, r) == null) continue;
            final int rx = cost.rowRX(0, r), rz = cost.rowRZ(0, r);
            final long k = RegionShardResidency.shardKey(RegionAddress.shardOf(rx, 0), RegionAddress.shardOf(rz, 0));
            perShard.merge(k, 1, Integer::sum);
            total++;
            if (env.columnLoaded(rx, rz)) hasLoadedColumn.add(k); // a backing chunk loaded ⇒ shard NOT evictable
        }
        if (total <= cap) return 0;

        int freedTotal = 0;
        while (total > cap && !perShard.isEmpty()) {
            // Coldest EVICTABLE shard (all backing columns unloaded), by LRU last-touch (lowest == coldest).
            long best = 0L;
            boolean found = false;
            long bestTouch = Long.MAX_VALUE;
            for (final Map.Entry<Long, Integer> e : perShard.entrySet()) {
                final long k = e.getKey();
                if (hasLoadedColumn.contains(k)) continue; // a backing chunk is still loaded — not evictable
                final long t = residency.lastTouched(RegionShardResidency.shardX(k), RegionShardResidency.shardZ(k));
                if (!found || t < bestTouch) { bestTouch = t; best = k; found = true; }
            }
            if (!found) break; // nothing evictable — stop (RAM stays above cap until a shard's chunks unload)

            final int sx = RegionShardResidency.shardX(best), sz = RegionShardResidency.shardZ(best);
            if (!env.ensureFlushed(sx, sz)) {
                // Dirty + this tick's flush budget spent: leave it resident, drop from candidates (progress), retry.
                perShard.remove(best);
                continue;
            }
            // Safely on disk now → record persisted (so the guard defers on its children + the loader can page it
            // back in), free its RAM, mark evicted.
            residency.markPersisted(sx, sz);
            final int freed = freeShard(cost, res, residency, sx, sz);
            total -= freed;
            freedTotal += freed;
            perShard.remove(best);
        }
        return freedTotal;
    }

    /**
     * Free shard {@code (sx, sz)}'s level-0..5 cost + resource rows (the RAM drop) and {@code markEvicted} it,
     * KEEPING the coarse (level-6) rows resident. Returns the number of level-0 cost leaves freed. Rows are left
     * interned but {@code built=false} ({@link CostPyramid#dropRow} / {@link ResourcePyramid#dropRow}) — the maps
     * are never compacted (a concurrent planner search may be reading them), and an interned-but-unbuilt child is
     * exactly what the clobber-guard defers on when its shard is persisted-non-resident. MC-free: the pure per-shard
     * evict used by both {@link #evictDownTo} and {@code EvictionTest} (bypassing the NavStore gate). Does NOT
     * flush or {@code markPersisted} — the caller establishes "safely on disk" first.
     */
    public static int freeShard(CostPyramid cost, ResourcePyramid res, RegionShardResidency residency,
                                int sx, int sz) {
        int freedLeaves = 0;
        // Cost levels 0..5 (== the encodeShard scope, so freeing == exactly what a reload re-fills).
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            final int rows = cost.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!cost.isBuilt(level, r)) continue;
                if (RegionAddress.shardOf(cost.rowRX(level, r), level) != sx
                        || RegionAddress.shardOf(cost.rowRZ(level, r), level) != sz) continue;
                if (level == 0 && cost.fragmentRecord(level, r) != null) freedLeaves++;
                cost.dropRow(level, r);
            }
        }
        // Resource levels 0..5.
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            final int rows = res.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!res.isBuilt(level, r)) continue;
                if (RegionAddress.shardOf(res.rowRX(level, r), level) != sx
                        || RegionAddress.shardOf(res.rowRZ(level, r), level) != sz) continue;
                res.dropRow(level, r);
            }
        }
        residency.markEvicted(sx, sz);
        return freedLeaves;
    }

    /** The current count of resident built level-0 cost leaves (the bounded-RAM metric) — a scan of ground truth,
     *  so it is accurate however the leaves became built (live build, eager load, lazy page-in). Cold. */
    public static int residentLeafCount(CostPyramid cost) {
        int n = 0;
        final int rows = cost.rowCount(0);
        for (int r = 0; r < rows; r++) {
            if (cost.isBuilt(0, r) && cost.fragmentRecord(0, r) != null) n++;
        }
        return n;
    }
}
