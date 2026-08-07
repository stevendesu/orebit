package com.orebit.mod.worldmodel.persistence;

import java.util.Set;

import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.StraddleSet;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * The Stage-2 <b>§2b reconciliation</b> pass (NOTES-perf-and-persistence.md §3/§4). After a lazy
 * shard-load has interned a shard's persisted leaves ({@code decode}) beside a coarse cell that was already
 * live-partial, that coarse cell now STRADDLES a complete-but-not-yet-recomputed child set: some children live,
 * the rest freshly interned from disk. Its stored value is still the partial live rollup. Rather than replay the
 * whole {@code mergeUp} for the shard (the expensive path this Stage exists to avoid — 34–880 ms/shard), this pass
 * re-derives <b>exactly</b> the straddle cells the {@code decode} reported (via {@link StraddleSet}) and then
 * propagates the change above the shard.
 *
 * <h2>The two phases (per pyramid)</h2>
 * <ol>
 *   <li><b>In-shard reconcile — ascending by level.</b> Recompute each straddle cell (levels {@code 1..}
 *       {@link RegionAddress#SHARD_LEVEL}) from its children via the per-cell primitive
 *       ({@link PyramidMerger#reconcileNode} / {@link ResourceMerger#reconcileNode}). Because those read children
 *       through {@code rowIfPresent}, they now UNION the live children with the freshly-interned persisted children
 *       → correct-full. <b>Ascending</b> order guarantees a cell's straddle children are reconciled before the cell
 *       itself; a cell's non-straddle children were loaded correct-full by {@code decode}, so every child is
 *       correct before its parent is recomputed.</li>
 *   <li><b>Above-shard propagation.</b> Each reconciled shard-top cell (level {@link RegionAddress#SHARD_LEVEL})
 *       rolls up to the pyramid top with damping via {@code mergeUpFrom(SHARD_LEVEL, …)} — cost to
 *       {@link RegionAddress#MAX_COARSE_LEVEL} (one recompute of the affected L6 parent), resource to
 *       {@link ResourcePyramid#RESOURCE_TOP_LEVEL} (the L6→L21 chain). These upper cells are the per-dimension
 *       coarse levels loaded at startup; recomputing them folds the reconciled shard in. (A straddle chain whose
 *       highest cell is below {@link RegionAddress#SHARD_LEVEL} needs no above-shard work — its next ancestor was
 *       loaded correct-full and is already consistent with the reconciled cell, so persisted-coarse ==
 *       recomputed-coarse holds without touching it.)</li>
 * </ol>
 *
 * <h2>Composes with the clobber-guard</h2>
 * Every recompute here routes through the SAME guarded drivers ({@code combineFragments} / {@code recomputeParent}).
 * So the increment-1 clobber-guard composes for free: if a straddle cell still has a child in a non-resident OTHER
 * shard (a boundary case), the guard DEFERS that cell (leaves it intact, re-marks it reconcile-pending) instead of
 * clobbering it with a still-partial rollup — it reconciles later, when that shard pages in. This pass never fights
 * the guard; it accepts the deferral.
 *
 * <h2>No-op on an empty straddle set</h2>
 * The common case — a fully-unloaded shard paging in — skips every coarse row (all its persisted rows are new, so
 * none are live-partial), reports an empty {@link StraddleSet}, and this pass returns immediately: zero cost.
 *
 * <p>Pure Java, no Minecraft API; cold path (shard load). Lives in {@code persistence} (above {@code hpa} /
 * {@code resource} in the dependency order) so it can drive both pyramids without a package cycle — it is the seam
 * the future lazy-loader calls right after a {@code decode}.
 */
public final class RegionReconciler {

    private RegionReconciler() {}

    /** Reconcile both pyramids from their respective decode-reported straddle sets (either may be empty/null). */
    public static void reconcile(CostPyramid cost, StraddleSet costStraddle,
                                 ResourcePyramid resource, StraddleSet resStraddle) {
        reconcileCost(cost, costStraddle);
        reconcileResource(resource, resStraddle);
    }

    /** Reconcile the cost pyramid's straddle cells (§2b) — ascending in-shard recompute, then propagate to L6. */
    public static void reconcileCost(CostPyramid p, StraddleSet straddle) {
        if (straddle == null || straddle.isEmpty()) return;

        // Phase 1 — in-shard, ascending (children before parents).
        for (int level = 1; level <= RegionAddress.SHARD_LEVEL; level++) {
            for (long key : straddle.at(level)) {
                PyramidMerger.reconcileNode(p, level,
                        RegionAddress.unpackRX(key), RegionAddress.unpackRY(key), RegionAddress.unpackRZ(key));
            }
        }
        // Phase 2 — propagate each reconciled shard-top (L5) cell above the shard to MAX_COARSE_LEVEL.
        for (long key : straddle.at(RegionAddress.SHARD_LEVEL)) {
            PyramidMerger.mergeUpFrom(p, RegionAddress.SHARD_LEVEL,
                    RegionAddress.unpackRX(key), RegionAddress.unpackRY(key), RegionAddress.unpackRZ(key));
        }
    }

    /** Reconcile the resource pyramid's straddle cells (§2b) — ascending in-shard re-sum, then propagate to L21. */
    public static void reconcileResource(ResourcePyramid p, StraddleSet straddle) {
        if (straddle == null || straddle.isEmpty()) return;

        // Phase 1 — in-shard, ascending.
        for (int level = 1; level <= RegionAddress.SHARD_LEVEL; level++) {
            for (long key : straddle.at(level)) {
                ResourceMerger.reconcileNode(p, level,
                        RegionAddress.unpackRX(key), RegionAddress.unpackRY(key), RegionAddress.unpackRZ(key));
            }
        }
        // Phase 2 — propagate each reconciled shard-top (L5) cell above the shard to RESOURCE_TOP_LEVEL.
        final Set<Long> tops = straddle.at(RegionAddress.SHARD_LEVEL);
        for (long key : tops) {
            ResourceMerger.mergeUpFrom(p, RegionAddress.SHARD_LEVEL,
                    RegionAddress.unpackRX(key), RegionAddress.unpackRY(key), RegionAddress.unpackRZ(key));
        }
    }
}
