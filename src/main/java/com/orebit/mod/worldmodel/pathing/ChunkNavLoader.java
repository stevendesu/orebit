package com.orebit.mod.worldmodel.pathing;

import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.orebit.mod.AllyBotEntity;
import com.orebit.mod.BotManager;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.ChunkCoords;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.HpaMaintenance;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Wires chunk lifecycle to the nav-grid pipeline: on load, queue the chunk; at the end of that
 * level's tick, build its {@link NavSection}[] and store it in {@link NavStore}; on unload, drop and
 * recycle it.
 *
 * <p><b>Why defer to the tick:</b> some loaders fire chunk-load on an async worker, and the build
 * touches a non-thread-safe section pool — so building on the tick thread keeps it single-threaded
 * for now. (The classify kernel is already thread-safe for a future background build.)
 *
 * <p><b>Per-level queue:</b> a chunk must be built against the level it loaded in; the old single
 * global queue drained against whichever level happened to tick, mixing dimensions.
 *
 * <p><b>Per-tick budget:</b> a teleport can load hundreds of chunks at once; draining them all in
 * one tick would spike. We drain columns until a per-tick wall-clock budget ({@code pathing.chunkBuildBudgetMs},
 * default 2 ms) is spent — checking elapsed after each column, so the worst-case overshoot is one column —
 * bounded above by a max-count backstop ({@code pathing.chunkBuildsPerTick}, default 64) that keeps a burst of
 * cheap all-air columns from draining unbounded. The rest stay queued and fill in over a few ticks. A fixed
 * COUNT couldn't adapt: per-column build cost swings ~15× with terrain (surface ~1 ms, cave ~5 ms), so 8 cave
 * columns already cost ~41 ms of a 50 ms tick; a time budget drains as many columns as safely fit each tick.
 *
 * <p><b>Bot-vicinity priority:</b> when a level holds one or more bots, the per-tick budget is spent on
 * the pending chunks NEAREST a bot first (a cheap bounded selection over the pending set), so a bot's
 * surrounding NavGrid — the ring its readiness gate waits on ({@code pathing.navReadyRadiusChunks}) —
 * fills in within a tick or two instead of behind an arbitrary FIFO backlog. With no bots in the level
 * the drain is plain FIFO (the historical behaviour).
 */
public final class ChunkNavLoader {

    private ChunkNavLoader() {}

    private static final Map<ServerLevel, Queue<Long>> pending = new ConcurrentHashMap<>();

    // Diagnostics until a consumer exists: confirm the pipeline is alive without log spam.
    private static int totalBuilt = 0;
    private static int nextLogAt = 1;

    // Tick-thread-confined scratch for the vicinity-priority drain (onWorldTickEnd runs on the server
    // thread; levels tick sequentially, so a single reused buffer per call is race-free). Avoids
    // per-tick allocation on the (cold, only-when-pending) drain path.
    private static final ArrayList<Long> DRAIN_SCRATCH = new ArrayList<>();
    private static final ArrayList<AllyBotEntity> BOT_SCRATCH = new ArrayList<>();

    // Chunk-key packing lives on NavStore now (the owner of the key space), so the consumer
    // (NavGridView) reads entries back with the exact same packing this loader writes them with.

    public static void register(PlatformEvents events) {
        events.onChunkLoad((level, chunk) ->
                pending.computeIfAbsent(level, l -> new ConcurrentLinkedQueue<>())
                        .add(NavStore.key(ChunkCoords.x(chunk.getPos()), ChunkCoords.z(chunk.getPos()))));

        events.onWorldTickEnd(level -> {
            Queue<Long> queue = pending.get(level);
            if (queue == null || queue.isEmpty()) return;
            // Time-budgeted drain: build columns until the per-tick wall-clock budget is spent, with a max
            // COUNT backstop so a burst of cheap all-air columns can't drain unbounded. Per-column build cost
            // swings ~15× with terrain (surface ~1 ms, cave ~5 ms — ChunkBuildBenchmark), so a fixed count
            // can't adapt; the ms budget drains as many columns as safely fit. Elapsed is checked AFTER each
            // column (both drain paths), so the worst-case overshoot is one column.
            final Config cfg = ConfigLoader.config();
            final long budgetNanos = (long) (cfg.chunkBuildBudgetMs() * 1_000_000.0);
            final int maxCount = cfg.chunkBuildsPerTick();
            final long start = System.nanoTime();

            // Gather the bots resident in THIS level (usually one). With none, keep the cheap FIFO poll —
            // no drain, no selection (the historical path). With bots present, spend the budget on the
            // pending chunks nearest a bot first so their readiness ring builds within a tick or two.
            BOT_SCRATCH.clear();
            for (AllyBotEntity bot : BotManager.bots()) {
                if (bot != null && bot.isAlive() && Worlds.of(bot) == level) BOT_SCRATCH.add(bot);
            }

            int built = 0;
            if (BOT_SCRATCH.isEmpty()) {
                Long k;
                while (built < maxCount && System.nanoTime() - start < budgetNanos
                        && (k = queue.poll()) != null) {
                    buildChunk(level, k);
                    built++;
                }
            } else {
                built = drainNearestBots(level, queue, maxCount, start, budgetNanos);
            }

            if (built > 0) {
                totalBuilt += built;
                if (totalBuilt >= nextLogAt) {
                    OrebitCommon.LOGGER.info("[Orebit] nav grid active: {} chunks built ({} stored in this level)",
                            totalBuilt, NavStore.size(level));
                    nextLogAt += 1024;
                }
            }
        });

        events.onChunkUnload((level, chunk) -> {
            long key = NavStore.key(ChunkCoords.x(chunk.getPos()), ChunkCoords.z(chunk.getPos()));
            NavStore.remove(level, key);
            NavGridUpdater.bumpEpoch(level); // dropped nav data changes the grid the same as building it
            NetherPortalIndex.remove(level, key); // the portal index lives exactly as long as the nav data
        });
    }

    /**
     * Build ONE queued chunk's nav data + all its riders (portal index, edit-epoch bump, HPA leaf) — the
     * per-chunk work factored out of the tick drain so the FIFO and the vicinity-priority paths run it
     * identically. Server-tick thread only.
     */
    private static void buildChunk(ServerLevel level, long k) {
        ChunkAccess chunk = level.getChunk(NavStore.keyX(k), NavStore.keyZ(k));
        // Nether-portal discovery rides the build: the classifier collects portal cells (palette-
        // gated — free for the no-portal chunk) and the chunk's index entry is replaced wholesale
        // beside its nav sections, so a rebuild is idempotent and a portal-less rebuild self-cleans.
        NetherPortalIndex.CellBuffer portals = new NetherPortalIndex.CellBuffer();
        // SLOWTICK navBuild phase: the NavGrid section build + its portal/epoch riders (diagnosis only).
        final long navStart = System.nanoTime();
        NavStore.put(level, k, ChunkNavBuilder.buildAllSections(level, chunk, portals));
        // Newly BUILT nav data is as plan-relevant as an edit: advance the edit epoch so the
        // follower's terrain-recheck debounce re-searches over the fresh area (without this a
        // bot whose first search ran before its chunks built waited on an unrelated block change
        // — the s52b cold-open false START-DEAD). One bump per chunk build, cold.
        NavGridUpdater.bumpEpoch(level);
        NetherPortalIndex.record(level, k, portals.toArray());
        com.orebit.mod.SlowTickMonitor.navBuild(navStart);
        // Eager HPA* region build (HPA-IMPLEMENTATION.md §12): build the region leaves as the chunk's
        // nav data is built, so the cost pyramid accumulates explored terrain (and survives chunk
        // unload in RAM — the travel-then-path fix). Bounded by this loader's per-tick chunk budget.
        // SLOWTICK mergeUp phase: the region-fragment leaf build + roll-up (diagnosis only).
        final long mergeStart = System.nanoTime();
        HpaMaintenance.onChunkNavBuilt(level, NavStore.keyX(k), NavStore.keyZ(k));
        com.orebit.mod.SlowTickMonitor.mergeUp(mergeStart);
    }

    /**
     * Bot-vicinity-priority drain (STEP 3): with at least one bot in {@code level}, spend the per-tick time
     * budget on the pending chunks NEAREST any bot first, capped by the {@code maxCount} backstop. Drains the
     * whole queue into {@link #DRAIN_SCRATCH}, builds the nearest (by squared chunk distance to the closest
     * bot) until either {@code System.nanoTime() - start >= budgetNanos} or {@code maxCount} columns are
     * built, and re-queues the rest. Cheap (no full sort): each nearest is found by a bounded linear pass —
     * at most {@code maxCount} passes over {@code n} entries. Cold — only runs on a tick with pending chunks
     * AND a bot present. Elapsed is checked at the top of each build (before selecting/building that column),
     * so worst-case overshoot is one column. Returns the number built. Preserves every per-chunk side effect
     * via {@link #buildChunk} (incl. the epoch bump the cold-open re-search relies on).
     */
    private static int drainNearestBots(ServerLevel level, Queue<Long> queue, int maxCount, long start,
            long budgetNanos) {
        DRAIN_SCRATCH.clear();
        for (Long k = queue.poll(); k != null; k = queue.poll()) DRAIN_SCRATCH.add(k);
        final int n = DRAIN_SCRATCH.size();
        if (n == 0) return 0;

        // Fast path: everything fits the COUNT backstop → build in FIFO order, still honoring the time budget
        // (re-queue whatever the budget runs out on). Elapsed checked before each column.
        if (n <= maxCount) {
            int i = 0;
            for (; i < n && System.nanoTime() - start < budgetNanos; i++) buildChunk(level, DRAIN_SCRATCH.get(i));
            final int built = i;
            for (; i < n; i++) queue.add(DRAIN_SCRATCH.get(i)); // survivors go back for a later tick
            DRAIN_SCRATCH.clear();
            return built;
        }

        // Bounded selection: pick the nearest chunk each pass, building it and marking it consumed (null) so
        // the next pass skips it, until the time budget or the `maxCount` backstop stops us. Then re-queue the
        // survivors. At most `maxCount` passes over `n` entries — no allocation, no full sort.
        int built = 0;
        for (int slot = 0; slot < maxCount && System.nanoTime() - start < budgetNanos; slot++) {
            int bestIdx = -1;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                final Long k = DRAIN_SCRATCH.get(i);
                if (k == null) continue;
                final long d = nearestBotChunkDistSq(NavStore.keyX(k), NavStore.keyZ(k));
                if (d < bestD) { bestD = d; bestIdx = i; }
            }
            if (bestIdx < 0) break;
            buildChunk(level, DRAIN_SCRATCH.get(bestIdx));
            DRAIN_SCRATCH.set(bestIdx, null);
            built++;
        }
        for (int i = 0; i < n; i++) {
            final Long k = DRAIN_SCRATCH.get(i);
            if (k != null) queue.add(k); // survivors go back for a later tick (re-prioritised as the bot moves)
        }
        DRAIN_SCRATCH.clear();
        return built;
    }

    /** Squared chunk-space distance from chunk {@code (cx,cz)} to the NEAREST bot in {@link #BOT_SCRATCH}
     *  (already filtered to the current level). {@link #BOT_SCRATCH} is non-empty when this is called. */
    private static long nearestBotChunkDistSq(int cx, int cz) {
        long best = Long.MAX_VALUE;
        for (int i = 0, m = BOT_SCRATCH.size(); i < m; i++) {
            final AllyBotEntity bot = BOT_SCRATCH.get(i);
            final long dx = cx - (bot.blockPosition().getX() >> 4);
            final long dz = cz - (bot.blockPosition().getZ() >> 4);
            final long d = dx * dx + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }
}
