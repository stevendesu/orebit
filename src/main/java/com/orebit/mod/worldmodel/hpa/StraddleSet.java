package com.orebit.mod.worldmodel.hpa;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The decode-time <b>straddle set</b> — the per-level set of <i>coarse</i> region cells a persistence
 * {@code decode} SKIPPED because the live world already had them built ("live world wins"), reported so a later
 * {@code RegionReconciler} pass can re-derive exactly those cells once a lazy shard-load has interned the missing
 * persisted children beside them (DESIGN-worldmodel-persistence.md — bounded region RAM, §2b reconciliation).
 *
 * <h2>Why a "straddle" set</h2>
 * Under a coarse-only / lazy startup, a coarse cell can end up STRADDLING two child sources: some children were
 * built live this session while the rest sit on disk in a not-yet-resident shard. When that shard finally pages
 * in, {@code decode} interns its persisted leaves — but the coarse cell itself is skipped (its live-partial value
 * wins over the persisted one). The skipped coarse cell is now a straddle: its child set is finally complete
 * (live + freshly-interned persisted), but its stored value is still the partial live rollup. This set records
 * exactly those cells so the reconciler can recompute them cheaply (NOT a full {@code mergeUp} of the shard).
 *
 * <h2>What is (and is not) recorded</h2>
 * Only <b>coarse</b> skips (level {@code >= 1}) are recorded. A level-0 (leaf) skip needs no reconcile: the live
 * leaf always wins and a leaf has no children to re-derive. Cells are keyed per level by
 * {@link RegionAddress#packLevelKey} (rx, ry, rz), so the reconciler iterates a level's cells and recovers coords
 * with {@link RegionAddress#unpackRX}/{@link RegionAddress#unpackRY}/{@link RegionAddress#unpackRZ}.
 *
 * <h2>House style</h2>
 * Pure data, <b>no Minecraft API</b> (the codecs and the reconciler stay headless-testable). Cold path (shard
 * load), so ordinary allocation is fine. Lives in {@code hpa} beside {@link RegionAddress} so both the persistence
 * codecs (which write it) and the reconciler (which drains it) can share it without a package cycle.
 */
public final class StraddleSet {

    /** Per-level sets of packed {@link RegionAddress#packLevelKey}s; index = level, lazily allocated. */
    private final Set<Long>[] byLevel;

    @SuppressWarnings("unchecked")
    public StraddleSet() {
        byLevel = (Set<Long>[]) new Set[RegionAddress.MAX_LEVEL + 1];
    }

    /** Record the coarse cell {@code (level, rx, ry, rz)} as skipped-by-live-wins (a straddle to reconcile). */
    public void add(int level, int rx, int ry, int rz) {
        Set<Long> s = byLevel[level];
        if (s == null) {
            s = new HashSet<>();
            byLevel[level] = s;
        }
        s.add(RegionAddress.packLevelKey(rx, ry, rz));
    }

    /** The packed keys recorded at {@code level} (empty, never {@code null}, if none). */
    public Set<Long> at(int level) {
        final Set<Long> s = (level >= 0 && level < byLevel.length) ? byLevel[level] : null;
        return s == null ? Collections.emptySet() : s;
    }

    /** {@code true} iff nothing was recorded at any level (the common fully-unloaded-shard load — reconcile is a no-op). */
    public boolean isEmpty() {
        for (Set<Long> s : byLevel) {
            if (s != null && !s.isEmpty()) return false;
        }
        return true;
    }

    /** Total number of recorded straddle cells across all levels. */
    public int size() {
        int n = 0;
        for (Set<Long> s : byLevel) {
            if (s != null) n += s.size();
        }
        return n;
    }
}
