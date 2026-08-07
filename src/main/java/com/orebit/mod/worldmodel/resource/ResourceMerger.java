package com.orebit.mod.worldmodel.resource;

import java.util.Arrays;

import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionShardResidency;

/**
 * Roll-up driver for the {@link ResourcePyramid} — the resource-tally analog of
 * {@link com.orebit.mod.worldmodel.hpa.PyramidMerger PyramidMerger}{@code .mergeUpFragments}
 * (find-mine-resources design §5). After a level-0 tally row changes (chunk load / block change, phase 4), the
 * ancestors on the same fixed-grid octree ({@link RegionAddress}) must be recomputed so a coarse drill-down
 * query sees the correct aggregate count of each resource under a region.
 *
 * <h2>The merge operator</h2>
 * A parent's column vector is the <b>per-column log₂-sum of its {@link RegionAddress#childCount 8/4}
 * children</b> — column {@code c} of the parent is {@code Log2Codec.merge} folded over column {@code c} of every
 * child that exists ({@link ResourcePyramid#rowIfPresent} ≥ 0). Each of the 23 columns is combined
 * independently. A child that is not interned contributes the <b>zero vector</b> (no resource), so it simply
 * does not add to the parent — this is exactly why the phase-3 {@link Log2Codec#merge} fix matters: {@code 0}
 * is the additive identity, so a column that is empty in every child stays {@code 0} at the parent (no phantom
 * counts).
 *
 * <h2>Damping (early-out)</h2>
 * Line-for-line the {@code PyramidMerger} fragment damping: walk parent→root, recompute each parent, and stop
 * the moment a parent's recomputed vector equals what was already stored — nothing above it can change. This
 * keeps a per-column chunk-load (many level-0 leaves sharing coarse ancestors) from recomputing the upper
 * pyramid once per leaf. A real change always differs from the stored vector, so it is never dropped.
 *
 * <h2>House style</h2>
 * Pure static; no per-call allocation past warmup — the child gather, accumulator and compare buffers are
 * thread-local reused {@code byte[COLUMNS]} scratch (the merge runs single-threaded on the tick/maintenance
 * thread, like the nav roll-up). Unlike the nav {@code PyramidMerger.mergeUpFragments} (which stops at
 * {@link RegionAddress#MAX_COARSE_LEVEL} because the region A* never plans above it), the resource layer rolls
 * up to {@link ResourcePyramid#RESOURCE_TOP_LEVEL} (= {@link RegionAddress#MAX_LEVEL}) — <b>true-global</b>, so
 * the compass ({@code /bot report}) can surface resources the bot saw arbitrarily far away. The extra levels are
 * cheap: damping stops each single-leaf walk the instant an ancestor's vector is unchanged, and only ancestors
 * of populated leaves are ever interned.
 */
public final class ResourceMerger {

    private ResourceMerger() {}

    private static final int COLUMNS = ResourcePyramid.COLUMNS;

    // Reusable per-merge scratch (single-threaded per merge; sized to the column count).
    private static final ThreadLocal<byte[]> ACC   = ThreadLocal.withInitial(() -> new byte[COLUMNS]);
    private static final ThreadLocal<byte[]> CHILD = ThreadLocal.withInitial(() -> new byte[COLUMNS]);
    private static final ThreadLocal<byte[]> PREV  = ThreadLocal.withInitial(() -> new byte[COLUMNS]);

    /**
     * After the level-0 tally row at {@code (level0Rx, level0Ry, level0Rz)} changed, walk parent→root
     * recomputing each ancestor's column vector from its (now-current) children, stopping the moment a parent's
     * recompute leaves it unchanged. O(levels) per leaf change. Mirrors
     * {@link com.orebit.mod.worldmodel.hpa.PyramidMerger#mergeUpFragments}.
     */
    public static void mergeUpTallies(ResourcePyramid p, int level0Rx, int level0Ry, int level0Rz) {
        mergeUpFrom(p, 0, level0Rx, level0Ry, level0Rz);
    }

    /**
     * Generalized upward walk: recompute every ancestor of the row {@code (childLevel, crx, cry, crz)} as the
     * per-column log₂-sum of its children, ascending to {@link ResourcePyramid#RESOURCE_TOP_LEVEL}, stopping the
     * moment a parent's vector is unchanged (damping). {@link #mergeUpTallies} is the {@code childLevel == 0} case;
     * the reconciler (NOTES-perf-and-persistence.md §3) calls it with {@code childLevel == }
     * {@link RegionAddress#SHARD_LEVEL} to propagate a reconciled shard-top tally up to the true-global top.
     * Recomputes only PARENTS ({@code childLevel+1} and up); the row itself is assumed current (a real leaf/merge
     * or a {@link #reconcileNode} the caller already ran).
     */
    public static void mergeUpFrom(ResourcePyramid p, int childLevel, int crx, int cry, int crz) {
        // Roll up to RESOURCE_TOP_LEVEL (true-global) — higher than the region A*'s MAX_COARSE_LEVEL so the
        // compass can surface resources seen anywhere; damping keeps the extra levels cheap (§5; RegionAddress).
        while (childLevel < ResourcePyramid.RESOURCE_TOP_LEVEL) {
            int parentLevel = childLevel + 1;
            int prx = RegionAddress.parentRX(crx);
            int prz = RegionAddress.parentRZ(crz);
            int pry = RegionAddress.parentRY(cry, childLevel);

            int parentRow = p.rowFor(parentLevel, prx, pry, prz);
            boolean changed = recomputeParent(p, parentLevel, parentRow, prx, pry, prz);
            // Damping: the moment a parent's OUTPUT is unchanged, nothing above it can change either.
            if (!changed) break;

            crx = prx; cry = pry; crz = prz;
            childLevel = parentLevel;
        }
    }

    /**
     * Recompute the single tally row {@code (level, rx, ry, rz)} in place as the per-column log₂-sum of its
     * children — the per-cell reconcile primitive (NOTES-perf-and-persistence.md §3). Interns the row if
     * needed; because the sum reads children through {@code rowIfPresent} it now folds in any freshly-interned
     * persisted children, so a straddle coarse tally becomes correct-full. Runs through the guarded
     * {@link #recomputeParent}, so it composes with the Stage-2 clobber-guard: a child still in a non-resident
     * OTHER shard DEFERS the recompute (the stored tally is kept and re-marked reconcile-pending) rather than
     * being summed down toward a partial child set.
     */
    public static void reconcileNode(ResourcePyramid p, int level, int rx, int ry, int rz) {
        final int row = p.rowFor(level, rx, ry, rz);
        recomputeParent(p, level, row, rx, ry, rz);
    }

    /**
     * Recompute the interned parent row {@code (parentLevel, parentRow)} (region coords {@code prx,pry,prz}) as
     * the per-column log₂-sum of its children, writing it only if it differs from the stored vector. Returns
     * whether the parent's vector changed (drives the walk's early-out).
     */
    private static boolean recomputeParent(ResourcePyramid p, int parentLevel, int parentRow,
                                           int prx, int pry, int prz) {
        final int childLevel = parentLevel - 1;
        final int children = RegionAddress.childCount(parentLevel);

        // Stage-2 clobber-guard (NOTES-perf-and-persistence.md §4): the resource-tier analog
        // of PyramidMerger's guard. If ANY absent child ({@code rowIfPresent < 0}) belongs to a
        // persisted-on-disk-but-not-resident shard, this child set is only partially loaded, so summing it would
        // ZERO-OUT the correct, fully-explored persisted coarse tally. DEFER: return false (no change) WITHOUT
        // reading or writing the parent row — the stored (persisted) vector is kept — and record the node
        // reconcile-pending. Returning false makes mergeUpTallies' unchanged early-out stop the upward walk for
        // this chain this pass, exactly as an unchanged recompute would (never clobbers, never a torn state).
        //   Byte-identical no-op: residency() is null until the startup-flip increment wires it, and empty under
        // eager-load-all, so isPersistedNonResident is always false and this block is skipped.
        //   Increment 4 (cold-shard eviction): the deferral key is "absent OR interned-but-UNBUILT" so an EVICTED
        // child — a row {@link ResourcePyramid#dropRow} zeroed + marked !built but left interned — defers exactly
        // like a never-loaded one when its shard is persisted-non-resident, keeping the resident coarse tally intact
        // instead of summing it down toward the partial (evicted-to-zero) child set. Byte-identical under
        // eager-load-all (isPersistedNonResident always false) and for a genuinely-empty resident leaf.
        final RegionShardResidency residency = p.residency();
        if (residency != null) {
            for (int i = 0; i < children; i++) {
                final int crx = RegionAddress.childRX(prx, i);
                final int crz = RegionAddress.childRZ(prz, i);
                final int cry = RegionAddress.childRY(pry, i, parentLevel);
                final int childRow = p.rowIfPresent(childLevel, crx, cry, crz);
                if (childRow >= 0 && p.isBuilt(childLevel, childRow)) continue; // real, resident+built child
                final int csx = RegionAddress.shardOf(crx, childLevel);
                final int csz = RegionAddress.shardOf(crz, childLevel);
                if (residency.isPersistedNonResident(csx, csz)) {
                    // Request the missing shard be paged in (Stage-2 atomic lazy-load), then keep the persisted tally.
                    residency.enqueueLoad(csx, csz);
                    residency.markReconcilePending(parentLevel, prx, pry, prz);
                    return false; // DEFER — keep the stored (persisted) tally; stop the walk this pass
                }
            }
        }

        final byte[] acc = ACC.get();
        final byte[] child = CHILD.get();
        Arrays.fill(acc, (byte) 0);

        for (int i = 0; i < children; i++) {
            final int crx = RegionAddress.childRX(prx, i);
            final int crz = RegionAddress.childRZ(prz, i);
            final int cry = RegionAddress.childRY(pry, i, parentLevel);
            final int childRow = p.rowIfPresent(childLevel, crx, cry, crz);
            if (childRow < 0) continue; // absent ⇒ zero vector ⇒ contributes nothing (0 is the identity)
            p.readRow(childLevel, childRow, child);
            for (int c = 0; c < COLUMNS; c++) {
                acc[c] = Log2Codec.merge(acc[c], child[c]);
            }
        }

        // Compare against the stored parent vector; write + mark built only on a real change (damping).
        final byte[] prev = PREV.get();
        p.readRow(parentLevel, parentRow, prev);
        boolean changed = false;
        for (int c = 0; c < COLUMNS; c++) {
            if (prev[c] != acc[c]) { changed = true; break; }
        }
        if (changed) {
            p.setRow(parentLevel, parentRow, acc);
            p.setBuilt(parentLevel, parentRow, true);
        }
        return changed;
    }
}
