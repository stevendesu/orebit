package com.orebit.mod.worldmodel.hpa;

/**
 * The per-dimension, long-lived memory of forbidden directed region→region crossings — the persistent
 * backing that turns {@code RegionEdgeBlacklist} from a per-navigation scratchpad into world knowledge
 * (#5 invalidation memory).
 *
 * <p><b>Why this exists.</b> A {@code RegionEdgeBlacklist} lives and dies with one {@code
 * HierarchicalRegionPlan}: when the bot gets a new goal region (or the arrival tolerance changes), a fresh
 * plan is built with empty blacklists, so every dead crossing the region tier already proved unrealizable is
 * DISCARDED and re-discovered from scratch — the region tier re-proposes proven-dead hops as if new, an
 * infinite supply of "new" routes, and the driver never runs out of options to give up on. This store
 * survives that plan boundary: a fresh plan is SEEDED from it, so learned dead-ends accumulate across goals
 * until the region tier genuinely runs dry and the bot gives up gracefully.
 *
 * <p><b>Deliberately a dumb, pure long store</b> (no {@code RegionEdgeBlacklist} / {@code BotCaps} imports)
 * so it stays in {@code worldmodel/hpa} beside {@link RegionShardResidency} without introducing an
 * {@code hpa → pathfinding} dependency edge. The caps-dominance decision and the actual blacklist seeding
 * live on the consumer side ({@code HierarchicalRegionPlan}, which already owns both). A crossing is a
 * directed {@code (fromKey, toKey)} pair of {@code RegionPathfinder.fragmentNodeKey}s, tagged with the
 * {@code BotCaps.realizabilitySig()} of the caps that failed it (so a stronger bot can ignore a weaker
 * bot's negative). Kept per LEVEL (0..{@link RegionAddress#MAX_COARSE_LEVEL}) because the plan's blacklists
 * are per level and the physical keys don't encode the level.
 *
 * <p><b>Threading.</b> The region tier is tick-confined (all region reads/writes run on the tick thread
 * within {@code PathPlan.tick}); both {@link #record} (from the repair path) and the consumer's seed read
 * happen there, so plain growable arrays with no synchronisation suffice — same as {@code
 * RegionEdgeBlacklist}. In-memory only for now; disk persistence + block-change expiry + eviction are a
 * later increment (this store is the thing that will be serialised into the reserved shard invalidation
 * section).
 */
public final class RegionCrossingMemory {

    private final long[][] from = new long[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final long[][] to   = new long[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final long[][] sig  = new long[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final int[] size    = new int[RegionAddress.MAX_COARSE_LEVEL + 1];

    public RegionCrossingMemory() {
        for (int L = 0; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            from[L] = new long[8];
            to[L]   = new long[8];
            sig[L]  = new long[8];
        }
    }

    /**
     * Remember that the crossing {@code fromKey → toKey} at {@code level} could not be realized by the caps
     * whose signature is {@code capsSig}. No-op if the exact {@code (from, to, caps)} triple is already
     * present — a DIFFERENT caps failing the same crossing is a distinct fact and is kept separately.
     */
    public void record(int level, long fromKey, long toKey, long capsSig) {
        for (int i = 0; i < size[level]; i++) {
            if (from[level][i] == fromKey && to[level][i] == toKey && sig[level][i] == capsSig) return;
        }
        if (size[level] == from[level].length) {
            int n = size[level] * 2;
            from[level] = java.util.Arrays.copyOf(from[level], n);
            to[level]   = java.util.Arrays.copyOf(to[level], n);
            sig[level]  = java.util.Arrays.copyOf(sig[level], n);
        }
        int s = size[level];
        from[level][s] = fromKey;
        to[level][s]   = toKey;
        sig[level][s]  = capsSig;
        size[level]    = s + 1;
    }

    /** Remembered crossings at {@code level} (the consumer iterates {@code 0..count-1} to seed a plan). */
    public int count(int level) {
        return size[level];
    }

    /** The {@code fromKey} of the {@code i}-th crossing at {@code level}. */
    public long fromAt(int level, int i) {
        return from[level][i];
    }

    /** The {@code toKey} of the {@code i}-th crossing at {@code level}. */
    public long toAt(int level, int i) {
        return to[level][i];
    }

    /** The failing-caps signature of the {@code i}-th crossing at {@code level}. */
    public long sigAt(int level, int i) {
        return sig[level][i];
    }

    /** Total remembered crossings across all levels (telemetry / tests). */
    public int total() {
        int n = 0;
        for (int s : size) n += s;
        return n;
    }
}
