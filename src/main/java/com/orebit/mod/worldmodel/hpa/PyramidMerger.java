package com.orebit.mod.worldmodel.hpa;

/**
 * Square-pyramid roll-up of node face costs for the HPA* region tier
 * (PRD §6.3–6.5, §7.1; HPA-IMPLEMENTATION.md §7, "3c merge").
 *
 * <p>The {@link CostPyramid} stores, per node, six face→center costs (PRD §6.5: each face holds the
 * half-traversal cost from that face to the node center; the boundary between two siblings is the implicit
 * sum of the two facing halves — we store the half, never an edge). The leaf level (level 0) is filled
 * directly by {@link LeafCostComputer}; every ancestor's faces are <b>derived from its children</b> by this
 * merger. This file owns that derivation and two drivers:
 * <ul>
 *   <li>{@link #mergeUp} — after one leaf changes, walk parent→root re-computing each ancestor's six faces
 *       from its (now-current) children. O(levels) per leaf change (incremental maintenance, §12).</li>
 *   <li>{@link #mergeLevel} — bulk: build every {@code level+1} parent that has at least one child at
 *       {@code level}, by enumerating the level's interned rows and re-combining each parent.</li>
 * </ul>
 *
 * <h2>Octree → quadtree child enumeration (§2)</h2>
 * A node at {@code parentLevel} has {@link RegionAddress#childCount(int)} children at {@code parentLevel-1}:
 * <b>8</b> below the octree→quadtree transition ({@code parentLevel <= OCTREE_TOP}) and <b>4</b> at/above it
 * ({@code parentLevel > OCTREE_TOP}, where the cell already spans the full padded vertical slab so {@code ry}
 * is pinned to 0). The child index {@code i} bit layout is {@code bit0 = X, bit1 = Z, bit2 = Y} (Y bit only
 * meaningful in the octree); see {@link RegionAddress#childRX}/{@link RegionAddress#childRZ}/
 * {@link RegionAddress#childRY}. A child <i>borders</i> parent face {@code F} when its subcell touches that
 * face — i.e. the child's bit for that axis matches the face's side:
 * <pre>
 *   face 0 (-X): X-bit 0    face 1 (+X): X-bit 1
 *   face 2 (-Y): Y-bit 0    face 3 (+Y): Y-bit 1
 *   face 4 (-Z): Z-bit 0    face 5 (+Z): Z-bit 1
 * </pre>
 * In the quadtree all 4 children have Y-bit 0 and each spans the full slab, so for the ±Y faces every child
 * borders the face (the parent's ±Y aggregate is the children's own ±Y aggregate).
 *
 * <h2>Chosen operator — approximate square-pyramid (§7)</h2>
 * For each parent face {@code F}: take the <b>cheapest crossing of that face</b> among the children that
 * border it ({@code min} of each bordering child's own face-{@code F} cost), then add a half-span
 * center-ward traversal estimate, and re-quantize:
 * <pre>
 *   crossing  = min over bordering, BUILT children of child.faceCost(F)
 *   halfSpan  = AIR_TRANSIT_TICKS * (parentSide / LEAF_SIZE) / 2
 *   parent.face[F] = quantize(crossing + halfSpan)
 *   if NO bordering child is built → leave parent.face[F] at its default (do not fabricate)
 *   parent.built = (any child built)
 * </pre>
 * This is monotone (solid children → expensive parent, open children → cheap parent), cheap, and admissible
 * enough for coarse skeleton selection.
 *
 * <p><b>TODO refine:</b> replace the approximate operator with the <i>exact tiny-shortest-path among child
 * face-centers</i>: solve the ≤50-node shortest path over the children's center nodes + their shared inner
 * face-centers (boundary-shared face-centers joined at cost 0, child face→center legs weighted by the stored
 * half-costs), giving each parent face the true min cost from that outer face-center to the parent center.
 * The approximate {@code min-crossing + halfSpan} form here is a deliberate, documented placeholder.
 *
 * <h2>House style (§14)</h2>
 * Pure static, no per-call allocation: the combine reads/writes the {@link CostPyramid} SoA in place and
 * walks integers only. {@code AIR_TRANSIT_TICKS}/{@code LEAF_SIZE} mirror {@link LeafCostComputer} /
 * {@link RegionAddress}. No MC API (the pyramid is the only state touched).
 */
public final class PyramidMerger {

    /**
     * Free-walk tick stand-in per leaf side (mirrors {@link LeafCostComputer}'s {@code AIR_TRANSIT_TICKS});
     * the half-span estimate scales this by the node's side in leaves.
     */
    public static final float AIR_TRANSIT_TICKS = RegionAddress.LEAF_SIZE;

    private PyramidMerger() {}

    // ---------------------------------------------------------------------------------------------------
    // Drivers
    // ---------------------------------------------------------------------------------------------------

    /**
     * Re-merge the chain of ancestors above the level-0 leaf {@code (level0Rx, level0Ry, level0Rz)} after the
     * leaf's faces changed. Walks parent→root: at each step computes the parent's region coords
     * ({@link RegionAddress#parentRX}/{@link RegionAddress#parentRY}/{@link RegionAddress#parentRZ}), interns
     * the parent row, and recombines it from its children via {@link #combineNode}. Stops at
     * {@link RegionAddress#MAX_LEVEL}. O(levels) — the incremental-maintenance hot path (§12).
     *
     * @param p         the dimension's cost pyramid
     * @param level0Rx  the changed leaf's region X (level 0)
     * @param level0Ry  the changed leaf's region Y (level 0)
     * @param level0Rz  the changed leaf's region Z (level 0)
     */
    public static void mergeUp(CostPyramid p, int level0Rx, int level0Ry, int level0Rz) {
        int childLevel = 0;
        int crx = level0Rx, cry = level0Ry, crz = level0Rz;
        while (childLevel < RegionAddress.MAX_LEVEL) {
            int parentLevel = childLevel + 1;
            int prx = RegionAddress.parentRX(crx);
            int prz = RegionAddress.parentRZ(crz);
            int pry = RegionAddress.parentRY(cry, childLevel);

            int parentRow = p.rowFor(parentLevel, prx, pry, prz);
            combineNode(p, parentLevel, parentRow, prx, pry, prz);

            // ascend
            crx = prx; cry = pry; crz = prz;
            childLevel = parentLevel;
        }
    }

    /**
     * Bulk-build level {@code level+1} from every node currently interned at {@code level}. Enumerates the
     * level's interned rows, derives each row's parent coords, and recombines that parent (deduplicated by
     * the pyramid's intern — a parent touched by several children is recombined once per child but the
     * combine is idempotent, recomputing from the full child set each time). A coarse-build / load-time pass;
     * call ascending ({@code mergeLevel(p,0)}, then {@code mergeLevel(p,1)}, …) to fill the whole pyramid.
     *
     * @param p     the dimension's cost pyramid
     * @param level the child level to roll up; builds {@code level+1}. No-op if {@code level >= MAX_LEVEL}.
     */
    public static void mergeLevel(CostPyramid p, int level) {
        if (level >= RegionAddress.MAX_LEVEL) return;
        int parentLevel = level + 1;
        int rows = p.rowCount(level);
        for (int row = 0; row < rows; row++) {
            int crx = p.rowRX(level, row);
            int cry = p.rowRY(level, row);
            int crz = p.rowRZ(level, row);
            int prx = RegionAddress.parentRX(crx);
            int prz = RegionAddress.parentRZ(crz);
            int pry = RegionAddress.parentRY(cry, level);
            int parentRow = p.rowFor(parentLevel, prx, pry, prz);
            combineNode(p, parentLevel, parentRow, prx, pry, prz);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The per-node combine (the chosen approximate square-pyramid operator)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Recompute the six face buckets of the parent node {@code (parentLevel, parentRow)} (region coords
     * {@code prx,pry,prz}) from its children at {@code parentLevel-1}, using the approximate operator (§7):
     * each parent face = {@code quantize(min crossing among bordering BUILT children + halfSpan)}, left at
     * default when no bordering child is built; {@code parent.built = any child built}.
     */
    public static void combineNode(CostPyramid p, int parentLevel, int parentRow, int prx, int pry, int prz) {
        int childLevel = parentLevel - 1;
        int children = RegionAddress.childCount(parentLevel);

        // half-span: half of a free traversal across the parent's full side (in leaves).
        int parentSideLeaves = RegionAddress.sideOf(parentLevel) / RegionAddress.LEAF_SIZE;
        float halfSpan = AIR_TRANSIT_TICKS * parentSideLeaves / 2.0f;

        boolean anyChildBuilt = false;

        for (int f = 0; f < 6; f++) {
            // Roll up BOTH directions (PRD §6.5 / directional faces): the cheapest ENTER and the cheapest EXIT
            // through this face among bordering children. The asymmetry propagates up — e.g. if every child
            // bordering the +Y face is air (expensive EXIT, cheap ENTER), so is the parent.
            float bestEnter = Float.POSITIVE_INFINITY;
            float bestExit = Float.POSITIVE_INFINITY;
            boolean borderingBuilt = false;

            for (int i = 0; i < children; i++) {
                if (!childBordersFace(i, f, children)) continue;

                int crx = RegionAddress.childRX(prx, i);
                int crz = RegionAddress.childRZ(prz, i);
                int cry = RegionAddress.childRY(pry, i, parentLevel);

                int childRow = p.rowIfPresent(childLevel, crx, cry, crz);
                if (childRow < 0) continue;
                if (p.isBuilt(childLevel, childRow)) {
                    anyChildBuilt = true;
                    borderingBuilt = true;
                    float ce = p.faceCost(childLevel, childRow, f, CostPyramid.ENTER);
                    if (ce < bestEnter) bestEnter = ce;
                    float cx = p.faceCost(childLevel, childRow, f, CostPyramid.EXIT);
                    if (cx < bestExit) bestExit = cx;
                }
            }

            if (borderingBuilt) {
                p.setFaceBucket(parentLevel, parentRow, f, CostPyramid.ENTER, CostCodec.quantize(bestEnter + halfSpan));
                p.setFaceBucket(parentLevel, parentRow, f, CostPyramid.EXIT, CostCodec.quantize(bestExit + halfSpan));
            }
            // else: leave this face at its current value (default BUCKET_INF placeholder) — do not fabricate.
        }

        // Any child built (even one not bordering a particular face) makes the parent a real node.
        if (!anyChildBuilt) {
            for (int i = 0; i < children; i++) {
                int crx = RegionAddress.childRX(prx, i);
                int crz = RegionAddress.childRZ(prz, i);
                int cry = RegionAddress.childRY(pry, i, parentLevel);
                int childRow = p.rowIfPresent(childLevel, crx, cry, crz);
                if (childRow >= 0 && p.isBuilt(childLevel, childRow)) { anyChildBuilt = true; break; }
            }
        }
        p.setBuilt(parentLevel, parentRow, anyChildBuilt);
    }

    // ---------------------------------------------------------------------------------------------------
    // Child ↔ face geometry
    // ---------------------------------------------------------------------------------------------------

    /**
     * Whether child index {@code i} (bit0=X, bit1=Z, bit2=Y) borders parent face {@code f} given the parent's
     * {@code childCount} (8 octree / 4 quadtree). A child borders a face when its axis bit matches the face's
     * side. In the quadtree (4 children, no Y split) every child spans the full vertical slab, so all four
     * border both ±Y faces.
     */
    private static boolean childBordersFace(int i, int f, int childCount) {
        switch (f) {
            case 0: return (i & 1) == 0;          // -X : X-bit 0
            case 1: return (i & 1) == 1;          // +X : X-bit 1
            case 4: return ((i >> 1) & 1) == 0;   // -Z : Z-bit 0
            case 5: return ((i >> 1) & 1) == 1;   // +Z : Z-bit 1
            case 2:                               // -Y
                if (childCount == 4) return true; // quadtree: every child spans the slab
                return ((i >> 2) & 1) == 0;       // octree: Y-bit 0
            case 3:                               // +Y
                if (childCount == 4) return true; // quadtree: every child spans the slab
                return ((i >> 2) & 1) == 1;       // octree: Y-bit 1
            default:
                return false;
        }
    }
}
