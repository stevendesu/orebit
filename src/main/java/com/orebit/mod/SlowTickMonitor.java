package com.orebit.mod;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * SLOWTICK telemetry — a <b>diagnosis instrument</b> that attributes a slow server tick to one of three
 * buckets so a non-deterministic multi-second stall ("Can't keep up! Running 3376ms behind") can be blamed
 * on the right actor instead of guessed at. It changes <b>no</b> pathing/build behaviour; it only observes.
 *
 * <h2>The question it answers</h2>
 * When the bot paths long distances into fresh/unloaded terrain the server occasionally spikes to ~2.5–3.4 s
 * on a single tick. That is far larger than any single Orebit operation, so a per-op timer alone would show
 * nothing — the prime suspect is a <b>GC pause</b> (the region-fragment build allocates heavily, ~46 MB per
 * shard), or <b>vanilla</b> chunk-gen/lighting. This monitor decomposes the whole slow tick into:
 * <ul>
 *   <li><b>ourOps</b> — the sum of the instrumented Orebit per-tick phases (see the accumulate methods),</li>
 *   <li><b>gc</b> — total JVM stop-the-world collection time attributable to this tick (summed
 *       {@link GarbageCollectorMXBean#getCollectionTime()} delta), and</li>
 *   <li><b>other</b> — the residual {@code max(0, tick − ourOps − gc)} (vanilla chunk gen / lighting / IO).</li>
 * </ul>
 * and logs ONE line per slow tick:
 * <pre>
 * [Orebit][SLOWTICK] tick=3376ms ourOps=42ms gc=3301ms other=33ms (top: gc) | navBuild=30 mergeUp=8 hpaFlush=1 shardLoad=3 evict=0 persist=0 botTick=0
 * </pre>
 *
 * <h2>Tick identity &amp; once-per-server-tick emission (the tricky part)</h2>
 * The loader gives us only a per-<i>level</i> {@code onWorldTickEnd} hook (fired once per loaded dimension per
 * server tick), never a single end-of-server-tick callback. Worse, the per-bot search we want to attribute
 * runs during the bot's <b>entity tick</b> — <i>before</i> that level's {@code onWorldTickEnd}. So we key every
 * measurement on the server's monotonically-increasing tick number ({@link MinecraftServer#getTickCount()}) and
 * funnel every instrumented entry point through the idempotent {@link #rollIfNew(int)}:
 * <ol>
 *   <li>The FIRST instrumented entry of a new server tick — the bot's {@link #beginTick(MinecraftServer)} at
 *       entity-tick time when a bot is present, otherwise the {@link #onLevelTickEnd(ServerLevel) heartbeat}
 *       (registered as the FIRST {@code onWorldTickEnd} hook so it precedes every phase hook) — detects the
 *       tick-number change, <b>emits the just-closed tick</b>, snapshots the wall clock + GC baseline, and
 *       <b>resets</b> the phase accumulators.</li>
 *   <li>Every later entry the same tick sees an unchanged tick number and is a no-op roll, so the whole tick
 *       accumulates into one bucket set and is emitted exactly once (at the very start of the NEXT tick — a
 *       one-tick reporting latency, harmless for a diagnostic).</li>
 * </ol>
 * The heartbeat runs unconditionally every tick (even a tick with no bot and no queued work), so an idle tick
 * still advances the boundary — without it a stretch of empty ticks would collapse into one giant false
 * "slow" tick. Multi-level double-counting is thus impossible: the second/third dimension's {@code
 * onWorldTickEnd} in the same server tick share the tick number and never re-roll; phases from every dimension
 * accumulate into the single in-progress tick and are emitted together once per server tick.
 *
 * <h2>Cost &amp; gating</h2>
 * Accumulation is always on (a handful of {@code nanoTime()} reads + one MX-bean GC-time read per tick — all on
 * the tick thread, so no synchronization), and only the LOG LINE is gated: it fires when the tick exceeds
 * {@link #THRESHOLD_MS} AND {@link Debug#VERBOSE} is set (flip with {@code /bot debug verbose on}). A normal
 * ~50 ms tick never logs. All state is tick-thread-confined (entity tick + {@code onWorldTickEnd} both run on
 * the server thread), so the plain {@code long} fields need no locking; the off-thread worldgen block-change
 * path is deliberately NOT instrumented.
 */
public final class SlowTickMonitor {

    private SlowTickMonitor() {}

    /** A tick slower than this (wall-clock, ms) is logged (when {@link Debug#VERBOSE}). A normal 50 ms tick
     *  stays silent; a "Can't keep up" stall is thousands of ms. */
    public static final double THRESHOLD_MS = 100.0;

    // ---- tick-boundary bookkeeping (tick-thread only) ------------------------------------------------
    private static final int NO_TICK = Integer.MIN_VALUE;
    private static int currentTick = NO_TICK;
    private static long lastRollNanos = 0L; // wall clock at the start of the in-progress tick
    private static long lastGcMillis = 0L;  // total GC collection time sampled at the start of the in-progress tick

    // ---- per-phase accumulators for the in-progress tick (nanos) --------------------------------------
    private static long navBuildNanos;  // ChunkNavLoader per-chunk NavGrid section build (+ portal/epoch riders)
    private static long mergeUpNanos;   // HpaMaintenance.onChunkNavBuilt: region-fragment leaf build + mergeUp roll-up
    private static long hpaFlushNanos;  // HpaMaintenance.flush: dirty-leaf rebuild drain
    private static long shardLoadNanos; // RegionShardLoader.drain: lazy region-shard page-in
    private static long evictNanos;     // RegionEvictor.sweep: cold-shard eviction
    private static long persistNanos;   // RegionPersistence.tick: periodic dirty flush
    private static long botTickNanos;   // per-bot AllyBotEntity nav dispatch (replan/search + steer) — see botTick()

    // ---- roll / emit ---------------------------------------------------------------------------------

    /**
     * Called by the bot's entity tick (the earliest instrumented entry of a server tick when a bot exists) to
     * close out the previous tick before this tick's search accumulates. Idempotent within a tick.
     */
    public static void beginTick(MinecraftServer server) {
        if (server != null) rollIfNew(server.getTickCount());
    }

    /**
     * The per-level heartbeat — register as the FIRST {@code onWorldTickEnd} hook so it rolls the tick boundary
     * before any phase hook accumulates, and so an idle tick (no bot, no queued work) still advances the
     * boundary. A no-op roll for the 2nd/3rd dimension of the same server tick (shared tick number).
     */
    public static void onLevelTickEnd(ServerLevel level) {
        final MinecraftServer server = level.getServer();
        if (server != null) rollIfNew(server.getTickCount());
    }

    /**
     * If {@code tick} differs from the in-progress tick, emit the just-closed tick (wall + GC deltas), snapshot
     * the new baselines, and reset the phase accumulators. Idempotent when {@code tick} is unchanged.
     */
    private static void rollIfNew(int tick) {
        if (tick == currentTick) return;
        final long now = System.nanoTime();
        final long gcNow = totalGcMillis();
        if (currentTick != NO_TICK) {
            maybeLog(now - lastRollNanos, gcNow - lastGcMillis);
        }
        lastRollNanos = now;
        lastGcMillis = gcNow;
        currentTick = tick;
        navBuildNanos = mergeUpNanos = hpaFlushNanos = shardLoadNanos = evictNanos = persistNanos = botTickNanos = 0L;
    }

    private static void maybeLog(long tickNanos, long gcMillis) {
        final double tickMs = tickNanos / 1_000_000.0;
        if (tickMs < THRESHOLD_MS) return;   // a normal tick — stay silent (always, cheap)
        if (!Debug.VERBOSE) return;          // the LOG LINE is verbose-gated; accumulation ran regardless

        final long ourOpsNanos = navBuildNanos + mergeUpNanos + hpaFlushNanos + shardLoadNanos
                + evictNanos + persistNanos + botTickNanos;
        final double ourOpsMs = ourOpsNanos / 1_000_000.0;
        final double gcMs = gcMillis; // getCollectionTime() is already milliseconds
        final double otherMs = Math.max(0.0, tickMs - ourOpsMs - gcMs);

        final String top = (gcMs >= ourOpsMs && gcMs >= otherMs) ? "gc"
                : (ourOpsMs >= otherMs) ? "ourOps" : "other";

        OrebitCommon.LOGGER.warn(
                "[Orebit][SLOWTICK] tick={}ms ourOps={}ms gc={}ms other={}ms (top: {}) | "
                        + "navBuild={} mergeUp={} hpaFlush={} shardLoad={} evict={} persist={} botTick={}",
                Math.round(tickMs), Math.round(ourOpsMs), Math.round(gcMs), Math.round(otherMs), top,
                ms(navBuildNanos), ms(mergeUpNanos), ms(hpaFlushNanos), ms(shardLoadNanos),
                ms(evictNanos), ms(persistNanos), ms(botTickNanos));
    }

    /** Sum of every GC collector's total collection time (ms) since JVM start. Cheap: 1–2 beans, once per tick. */
    private static long totalGcMillis() {
        long sum = 0L;
        final List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0, n = beans.size(); i < n; i++) {
            final long t = beans.get(i).getCollectionTime();
            if (t > 0) sum += t;
        }
        return sum;
    }

    private static long ms(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    // ---- phase accumulate helpers (each takes the phase's start nanoTime) ----------------------------

    /** ChunkNavLoader per-chunk NavGrid section build (+ portal index / edit-epoch riders). */
    public static void navBuild(long startNanos)  { navBuildNanos  += System.nanoTime() - startNanos; }

    /** HpaMaintenance.onChunkNavBuilt — region-fragment leaf build + PyramidMerger/resource roll-up. */
    public static void mergeUp(long startNanos)   { mergeUpNanos   += System.nanoTime() - startNanos; }

    /** HpaMaintenance.flush — the debounced dirty-leaf rebuild drain. */
    public static void hpaFlush(long startNanos)  { hpaFlushNanos  += System.nanoTime() - startNanos; }

    /** RegionShardLoader.drain — lazy region-shard page-in. */
    public static void shardLoad(long startNanos) { shardLoadNanos += System.nanoTime() - startNanos; }

    /** RegionEvictor.sweep — cold-shard eviction back to disk. */
    public static void evict(long startNanos)     { evictNanos     += System.nanoTime() - startNanos; }

    /** RegionPersistence.tick — the budgeted periodic dirty flush. */
    public static void persist(long startNanos)   { persistNanos   += System.nanoTime() - startNanos; }

    /** Per-bot AllyBotEntity nav dispatch (replan/search + steer). Labelled botTick, not "search", because the
     *  block/region search is not cleanly separable from the surrounding follow/steer work on the tick thread. */
    public static void botTick(long startNanos)   { botTickNanos   += System.nanoTime() - startNanos; }
}
