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
 * live on the consumer side ({@code HierarchicalRegionPlan}, which already owns both) — including the
 * dominance ORDER itself, which {@link #record} takes as a caller-supplied {@link Dominance} predicate
 * (in practice {@code BotCaps::sigDominates}) so the antichain compaction below never needs a pathfinding
 * import. A crossing is a directed {@code (fromKey, toKey)} pair of {@code RegionPathfinder.fragmentNodeKey}s,
 * tagged with the effective {@code BotCaps.realizabilitySig(caps, inv)} of the conditions that failed it (so
 * a stronger bot can ignore a weaker bot's negative). Kept per LEVEL
 * (0..{@link RegionAddress#MAX_COARSE_LEVEL}) because the plan's blacklists are per level and the physical
 * keys don't encode the level.
 *
 * <p><b>Antichain invariant</b> (DESIGN-persisted-invalidation-memory.md §3.5): the rows stored for one
 * {@code (from, to)} crossing form a dominance ANTICHAIN — no stored sig dominates another. {@link #record}
 * maintains it: a new fact already covered by a stored row (its sig dominates the new one — every bot the
 * new row would bind is already bound) is skipped; a new fact that strictly dominates stored rows replaces
 * them (one write + compaction of any further dominated rows); incomparable proofs coexist. This bounds
 * per-crossing rows at the set of incomparable proofs (typically 1).
 *
 * <p><b>Provenance</b> (DESIGN-persisted-invalidation-memory.md §4/§4b): each row remembers HOW it was
 * proven — {@link #PROV_PROOF} (a block-tier BLOCKED proof at level 0, the {@code onBlocked} record site),
 * {@link #PROV_ESCALATION} (a parent-window hop blamed by the cascade's tube-drain escalation,
 * {@code HierarchicalRegionPlan.blameTubeConfined} — session-only, filtered out of every encode), or
 * {@link #PROV_ROLLED_UP} (derived by the Phase-2b roll-up fold, {@link InvalidationRollup} — recorded from
 * the same {@code onBlocked} record path when a parent crossing's kill-set completes). Carried as a
 * parallel byte array in the same growable-parallel-array style; persistable provenances travel in the 2 free
 * high bits of the row's on-disk {@code toKey} ({@code CostPyramidCodec}).
 *
 * <p><b>Threading.</b> The region tier is tick-confined (all region reads/writes run on the tick thread
 * within {@code PathPlan.tick}); {@link #record} (from the repair path + the persistence load-merge), the
 * consumer's seed read, and {@link #evictLeafTouching} (from the {@code HpaMaintenance} block-change flush)
 * all happen there, so plain growable arrays with no synchronisation suffice — same as {@code
 * RegionEdgeBlacklist}. Persisted into each cost shard's invalidation section ({@code CostPyramidCodec} v4,
 * rows bucketed by their FROM region's shard; level-{@link RegionAddress#MAX_COARSE_LEVEL} rows ride the
 * per-dimension coarse file) via the normal dirty-shard flush.
 */
public final class RegionCrossingMemory {

    /** Provenance: proven dead by a block-tier BLOCKED result (the level-0 {@code onBlocked} record site). */
    public static final int PROV_PROOF = 0;
    /** Provenance: a parent-window hop blamed by the cascade's tube-drain escalation
     *  ({@code HierarchicalRegionPlan.blameTubeConfined} at level ≥ 1) — a realized-blame INFERENCE that only
     *  needs to converge within a session, not a proof. SESSION-ONLY: never persisted ({@code CostPyramidCodec}
     *  filters these rows at encode), and never a constituent of a Phase-2b roll-up fold. */
    public static final int PROV_ESCALATION = 1;
    /** Provenance: derived by the Phase-2b roll-up fold ({@link InvalidationRollup}, §4b) — a parent-level
     *  crossing recorded when ALL its constituent child-level crossings were proven dead ({@code PROOF} or
     *  {@code ROLLED_UP} rows only, at a dominating-or-equal sig). Persists like a proof; revived by
     *  {@link #evictLeafTouching}'s containing-coarse eviction when any leaf under it changes. */
    public static final int PROV_ROLLED_UP = 2;

    /** The region part of a {@code RegionPathfinder.fragmentNodeKey} — {@code packLevelKey} bits 0..49 (the
     *  6-bit fragment id sits at bits 50..55). Masking with this compares REGION identity across fragments. */
    private static final long REGION_MASK = (1L << 50) - 1;

    private final long[][] from = new long[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final long[][] to   = new long[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final long[][] sig  = new long[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final byte[][] prov = new byte[RegionAddress.MAX_COARSE_LEVEL + 1][];
    private final int[] size    = new int[RegionAddress.MAX_COARSE_LEVEL + 1];

    public RegionCrossingMemory() {
        for (int L = 0; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            from[L] = new long[8];
            to[L]   = new long[8];
            sig[L]  = new long[8];
            prov[L] = new byte[8];
        }
    }

    /**
     * The caller-supplied sig-dominance order ({@code dominates(a, b)} = a is at least as capable as b on
     * every realizability axis — in practice {@code BotCaps::sigDominates}). Taken as a parameter so this
     * store stays a pure long store with no pathfinding import (see the class note); a static method ref is
     * allocation-free at the call sites, and everything here is cold (BLOCKED-record / load-merge only).
     */
    @FunctionalInterface
    public interface Dominance {
        boolean dominates(long a, long b);
    }

    /**
     * Remember that the crossing {@code fromKey → toKey} at {@code level} could not be realized under the
     * conditions whose signature is {@code capsSig} (proven per {@code provenance} — one of the
     * {@link #PROV_PROOF}/{@link #PROV_ESCALATION}/{@link #PROV_ROLLED_UP} constants), maintaining the
     * per-crossing dominance ANTICHAIN (class note): skipped when a stored row for the same crossing already
     * dominates the new sig (that row binds every bot this one would — an exact duplicate is the
     * equal-dominates case of this rule, which is also what makes the persistence load-merge idempotent);
     * replaces the stored rows it strictly dominates (the replacing row's provenance wins); coexists with
     * incomparable rows (a genuinely different proof).
     */
    public void record(int level, long fromKey, long toKey, long capsSig, int provenance, Dominance dominance) {
        int replaced = -1; // index the new sig was written into (strict-dominance replace), or -1
        for (int i = 0; i < size[level]; i++) {
            if (from[level][i] != fromKey || to[level][i] != toKey) continue;
            long existing = sig[level][i];
            if (dominance.dominates(existing, capsSig)) return; // covered (equal sigs dominate ⇒ dedup too)
            if (dominance.dominates(capsSig, existing)) {
                if (replaced < 0) {
                    sig[level][i]  = capsSig;  // strict dominance → replace in place
                    prov[level][i] = (byte) provenance;
                    replaced = i;
                } else {
                    removeAt(level, i);        // a further dominated row → compact it away
                    i--;                       // re-examine the row swapped into this slot
                }
            }
            // incomparable → keep scanning; it may coexist
        }
        if (replaced >= 0) return;
        if (size[level] == from[level].length) {
            int n = size[level] * 2;
            from[level] = java.util.Arrays.copyOf(from[level], n);
            to[level]   = java.util.Arrays.copyOf(to[level], n);
            sig[level]  = java.util.Arrays.copyOf(sig[level], n);
            prov[level] = java.util.Arrays.copyOf(prov[level], n);
        }
        int s = size[level];
        from[level][s] = fromKey;
        to[level][s]   = toKey;
        sig[level][s]  = capsSig;
        prov[level][s] = (byte) provenance;
        size[level]    = s + 1;
    }

    /** Drop row {@code i} at {@code level} (swap-with-last; the store is unordered — seeding reads all rows). */
    private void removeAt(int level, int i) {
        int last = size[level] - 1;
        from[level][i] = from[level][last];
        to[level][i]   = to[level][last];
        sig[level][i]  = sig[level][last];
        prov[level][i] = prov[level][last];
        size[level]    = last;
    }

    /** Receives each row dropped by {@link #evictLeafTouching} — {@code level} + the row's {@code fromKey},
     *  enough for the caller to re-dirty the row's FROM shard so the deletion persists (rows are keyed /
     *  sharded by FROM, so a TO-side match still re-flushes correctly through its FROM shard). */
    @FunctionalInterface
    public interface EvictSink {
        void evicted(int level, long fromKey);
    }

    /**
     * Block-change expiry (DESIGN-persisted-invalidation-memory.md §4): the level-0 leaf {@code (leafRx,
     * leafRy, leafRz)} was rebuilt (its nav content changed), so every remembered crossing whose FROM <b>or</b>
     * TO region is that leaf — or, at coarser levels, whose FROM/TO cell CONTAINS that leaf — is no longer a
     * proven theorem and is dropped. Conservative over-eviction is correct: a re-learn is cheap, a stale
     * negative (refusing a crossing the change just opened) is the poison. Fragment ids are ignored in the
     * match (the rebuild may have renumbered them — every fragment row of the touched region goes). Each
     * dropped row is reported to {@code sink} so its FROM shard can be re-flushed. Linear over the per-level
     * stores — cold (the block-change flush path, never per-tick steady-state). Returns the rows dropped.
     */
    public int evictLeafTouching(int leafRx, int leafRy, int leafRz, EvictSink sink) {
        int evicted = 0;
        for (int L = 0; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            if (size[L] == 0) continue;
            // The containing cell at level L: horizontal >> L; vertical halves per level inside the octree and
            // pins to 0 at/above the octree→quadtree transition (RegionAddress.regionY semantics).
            final int cellRx = leafRx >> L;
            final int cellRz = leafRz >> L;
            final int cellRy = (L >= RegionAddress.OCTREE_TOP) ? 0 : (leafRy >> L);
            final long cell = RegionAddress.packLevelKey(cellRx, cellRy, cellRz);
            for (int i = 0; i < size[L]; i++) {
                if ((from[L][i] & REGION_MASK) != cell && (to[L][i] & REGION_MASK) != cell) continue;
                final long fromKey = from[L][i];
                removeAt(L, i);
                i--; // re-examine the row swapped into this slot
                evicted++;
                if (sink != null) sink.evicted(L, fromKey);
            }
        }
        return evicted;
    }

    /**
     * Whether this store holds a <b>proof-grade</b> row ({@link #PROV_PROOF} or {@link #PROV_ROLLED_UP} —
     * never {@link #PROV_ESCALATION}, which is cascade inference, not proof) for the exact crossing
     * {@code fromKey → toKey} at {@code level} whose sig dominates-or-equals {@code capsSig}. The Phase-2b
     * roll-up fold's dead-constituent test ({@link InvalidationRollup}): such a row binds every bot the sig
     * {@code capsSig} dominates (dominance is transitive), so the constituent is dead FOR the fold's sig.
     * Linear over the level's rows — cold (record-path only).
     */
    public boolean holdsProofDominating(int level, long fromKey, long toKey, long capsSig, Dominance dominance) {
        for (int i = 0; i < size[level]; i++) {
            if (from[level][i] != fromKey || to[level][i] != toKey) continue;
            if (prov[level][i] == PROV_ESCALATION) continue;
            if (dominance.dominates(sig[level][i], capsSig)) return true;
        }
        return false;
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

    /** The provenance ({@link #PROV_PROOF}/{@link #PROV_ESCALATION}/{@link #PROV_ROLLED_UP}) of the
     *  {@code i}-th crossing at {@code level}. */
    public int provAt(int level, int i) {
        return prov[level][i];
    }

    /** Total remembered crossings across all levels (telemetry / tests). */
    public int total() {
        int n = 0;
        for (int s : size) n += s;
        return n;
    }
}
