package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;

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

    // ===================================================================================================
    // Fragment-model roll-up (HPA-FRAGMENTS.md §3.1, §5, §7 — the S5 coarse pyramid merge)
    //
    // The center-model {@link #combineNode} above is lossy in carved terrain (it assumes every face connects
    // through the node center). The fragment merge instead recomputes a parent's {@link RegionFragments}
    // record as a PURE FUNCTION of its 8 (octree) / 4 (quadtree) children's records: union-find the children's
    // fragments across the shared internal faces (two child fragments connect when their footprints overlap on
    // the face between their two children), and project each resulting component's outer-flush child footprints
    // onto the parent's six outer faces in face-relative 16-bucket units. Identical schema to a leaf, so the
    // region A* reads every level through one set of accessors; the only difference is the producer.
    //
    // House style (§14): pure, no MC; per-merge scratch is thread-local + reused (the merge runs single-threaded
    // on the tick/maintenance thread, like the leaf computer). No edge costs stored — derived at query (§2.2).
    // ===================================================================================================

    /**
     * Per-level flood/quantization resolution {@code G} (HPA-FRAGMENTS.md §3.1): 16 at the leaf (exact voxels),
     * a constant-moderate {@code 4} at coarse levels, dropping to {@code 2} very high up. Stored on the record
     * (bookkeeping); footprints are always 16-bucket face-relative regardless of {@code G}. Never splurge at low
     * levels — storage is dominated by the lowest stored level. Final values are S5 tuning.
     */
    public static int coarseG(int level) {
        if (level <= 0) return RegionAddress.LEAF_SIZE; // 16
        if (level <= 8) return 4;
        return 2;
    }

    /** Max fragment items in one merge = childCount × cap (8 × 63), rounded up — the union-find universe. */
    private static final int MAX_ITEMS = 8 * RegionFragments.MAX_FRAGMENTS + 8; // 512

    // Reusable per-merge scratch (one merge is single-threaded; reset at the top of combineFragments).
    private static final ThreadLocal<int[]> ITEM_SLOT = ThreadLocal.withInitial(() -> new int[MAX_ITEMS]);
    private static final ThreadLocal<int[]> ITEM_MASK = ThreadLocal.withInitial(() -> new int[MAX_ITEMS]);
    private static final ThreadLocal<int[]> ITEM_FP   = ThreadLocal.withInitial(() -> new int[MAX_ITEMS * 6]);
    private static final ThreadLocal<int[]> UF        = ThreadLocal.withInitial(() -> new int[MAX_ITEMS]);
    private static final ThreadLocal<int[]> COMP_OF   = ThreadLocal.withInitial(() -> new int[MAX_ITEMS]);
    // Per-component × face bbox accumulators (componentCount ≤ cap; faces 6). Reset per merge.
    private static final ThreadLocal<int[]> ACC_MINU = ThreadLocal.withInitial(() -> new int[RegionFragments.MAX_FRAGMENTS * 6]);
    private static final ThreadLocal<int[]> ACC_MAXU = ThreadLocal.withInitial(() -> new int[RegionFragments.MAX_FRAGMENTS * 6]);
    private static final ThreadLocal<int[]> ACC_MINV = ThreadLocal.withInitial(() -> new int[RegionFragments.MAX_FRAGMENTS * 6]);
    private static final ThreadLocal<int[]> ACC_MAXV = ThreadLocal.withInitial(() -> new int[RegionFragments.MAX_FRAGMENTS * 6]);
    private static final ThreadLocal<int[]> PACKED    = ThreadLocal.withInitial(() -> new int[6]);

    /** The +face for an internal split axis: X(0)→+X(1), Z(1)→+Z(5), Y(2)→+Y(3). */
    private static int plusFace(int axis) { return axis == 0 ? 1 : (axis == 1 ? 5 : 3); }
    /** The −face for an internal split axis: X(0)→−X(0), Z(1)→−Z(4), Y(2)→−Y(2). */
    private static int minusFace(int axis) { return axis == 0 ? 0 : (axis == 1 ? 4 : 2); }

    // ---------------------------------------------------------------------------------------------------
    // Fragment drivers (parallel to the center mergeUp / mergeLevel, dispatched by RegionGrid.HPA_FRAGMENTS)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Fragment analog of {@link #mergeUp}: after a leaf's {@link RegionFragments} record changed, walk
     * parent→root recomputing each ancestor from its children via {@link #combineFragments}. Early-out: stop
     * ascending the moment a parent's recompute leaves it unbuilt (no built child below it) — there is nothing
     * above to change. O(levels) per leaf change (HPA-FRAGMENTS.md §6.5).
     */
    public static void mergeUpFragments(CostPyramid p, int level0Rx, int level0Ry, int level0Rz) {
        int childLevel = 0;
        int crx = level0Rx, cry = level0Ry, crz = level0Rz;
        while (childLevel < RegionAddress.MAX_LEVEL) {
            int parentLevel = childLevel + 1;
            int prx = RegionAddress.parentRX(crx);
            int prz = RegionAddress.parentRZ(crz);
            int pry = RegionAddress.parentRY(cry, childLevel);

            int parentRow = p.rowFor(parentLevel, prx, pry, prz);
            long before = nodeSignature(p, parentLevel, parentRow);
            combineFragments(p, parentLevel, parentRow, prx, pry, prz);
            long after = nodeSignature(p, parentLevel, parentRow);
            // Design damping (HPA-FRAGMENTS.md §6.5): stop the moment a level's OUTPUT is unchanged — nothing
            // above it can change. This is also what keeps a per-column chunk-load (≈24 leaves sharing the same
            // coarse ancestors) from recomputing the upper pyramid 24×: leaves 2..N hit unchanged ancestors and
            // stop. (A built→unbuilt leaf still propagates: its parent's content changes, so the walk continues.)
            if (after == before) break;

            crx = prx; cry = pry; crz = prz;
            childLevel = parentLevel;
        }
    }

    /** Built-state + content signature of a node (a distinct sentinel for unbuilt/absent), for the merge damping. */
    private static long nodeSignature(CostPyramid p, int level, int row) {
        if (!p.isBuilt(level, row)) return 0L; // unbuilt / absent
        RegionFragments rf = p.fragmentRecord(level, row);
        return rf == null ? 1L : (rf.contentSignature() | 0x4000000000000000L); // set a high bit ⇒ never == 0/1
    }

    /**
     * Fragment analog of {@link #mergeLevel}: bulk-build level {@code level+1} from every node interned at
     * {@code level}, recombining each touched parent from its children via {@link #combineFragments}. Call
     * ascending ({@code mergeLevelFragments(p,0)}, then {@code 1}, …) to fill the whole fragment pyramid (the
     * load-time / test path; the incremental path is {@link #mergeUpFragments}).
     */
    public static void mergeLevelFragments(CostPyramid p, int level) {
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
            combineFragments(p, parentLevel, parentRow, prx, pry, prz);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The per-node fragment combine (union-find over child fragments → parent fragments)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Recompute the parent node {@code (parentLevel, parentRow)} (region coords {@code prx,pry,prz}) as a pure
     * function of its children's {@link RegionFragments} records (HPA-FRAGMENTS.md §5):
     * <ol>
     *   <li>Gather each child's fragments as <i>items</i> — a real MIXED fragment per record fragment; one
     *       synthetic full-face item for a uniform AIR/WATER, a collapsed-but-passable mass, or an
     *       <b>unbuilt/unloaded</b> child (optimistic open, so unexplored terrain never walls off the merge);
     *       a uniform SOLID child contributes none (a wall).</li>
     *   <li>Union-find the items across the parent's internal split faces — two items in adjacent children
     *       connect when both touch the shared face and their footprints overlap there.</li>
     *   <li>Each union component becomes one parent fragment; project its outer-flush child footprints onto the
     *       parent's six outer faces (per-axis downsample: a split axis maps 16 child buckets → 8 parent
     *       buckets at the child's half-offset; an unsplit axis — the quadtree's Y — maps 1:1).</li>
     * </ol>
     * Header: {@code passFrac}/{@code avgSolidHardness} are means over built children; the parent stays a
     * uniform kind only when <b>every</b> child is built and the same SOLID/AIR/WATER. {@code built = any child
     * built} (mirrors {@link #combineNode}); an all-unbuilt parent is left unbuilt so the planner reads the §6
     * optimistic default rather than a fabricated "known air".
     */
    public static void combineFragments(CostPyramid p, int parentLevel, int parentRow, int prx, int pry, int prz) {
        final int childLevel = parentLevel - 1;
        final int children = RegionAddress.childCount(parentLevel);

        final int[] itemSlot = ITEM_SLOT.get();
        final int[] itemMask = ITEM_MASK.get();
        final int[] itemFp = ITEM_FP.get();
        final int[] packed = PACKED.get();

        int nItems = 0;
        boolean anyChildBuilt = false;
        int builtChildren = 0, solidBuilt = 0, airBuilt = 0, waterBuilt = 0;
        long passSum = 0, hardSum = 0;
        int passN = 0, hardN = 0;

        for (int i = 0; i < children; i++) {
            final int crx = RegionAddress.childRX(prx, i);
            final int crz = RegionAddress.childRZ(prz, i);
            final int cry = RegionAddress.childRY(pry, i, parentLevel);
            final int childRow = p.rowIfPresent(childLevel, crx, cry, crz);
            final RegionFragments rf =
                    (childRow >= 0 && p.isBuilt(childLevel, childRow)) ? p.fragmentRecord(childLevel, childRow) : null;

            if (rf == null) {
                // Unbuilt/unloaded child: optimistic open (one synthetic full-face item) so unexplored terrain
                // never blocks a merge. NOT counted as a built uniform kind (keeps the parent MIXED, and the
                // anyChildBuilt gate below may leave the whole parent unbuilt = the §6 default).
                nItems = addSynthetic(itemSlot, itemMask, itemFp, nItems, i);
                continue;
            }
            anyChildBuilt = true;
            builtChildren++;
            passSum += rf.passFrac(); passN++;
            // avgSolidHardness is the MINE-EDGE cost scale (how hard the rock is to dig). Average it over the
            // children that actually CONTAIN solid (hardness > 0), NOT over all built children — an all-air child
            // should not dilute "this rock is deepslate" toward "soft". (passFrac, the crossing-cost scale,
            // correctly averages over every child.) Ordinal only; the block tier refines the real dig.
            if (rf.avgSolidHardness() > 0) { hardSum += rf.avgSolidHardness(); hardN++; }

            switch (rf.kind()) {
                case RegionFragments.KIND_SOLID:
                    solidBuilt++; // no item (a wall)
                    break;
                case RegionFragments.KIND_AIR:
                    airBuilt++;
                    nItems = addSynthetic(itemSlot, itemMask, itemFp, nItems, i);
                    break;
                case RegionFragments.KIND_WATER:
                    waterBuilt++;
                    nItems = addSynthetic(itemSlot, itemMask, itemFp, nItems, i);
                    break;
                default: // MIXED
                    final int fc = rf.fragmentCount();
                    if (fc == 0) {
                        // Collapsed/uniform mass: passable-enough → one synthetic open item; else a wall.
                        if (rf.passFrac() > 0) {
                            nItems = addSynthetic(itemSlot, itemMask, itemFp, nItems, i);
                        }
                    } else {
                        for (int f = 0; f < fc && nItems < MAX_ITEMS; f++) {
                            final int base = nItems * 6;
                            itemSlot[nItems] = i;
                            itemMask[nItems] = rf.faceMask(f);
                            for (int face = 0; face < 6; face++) {
                                itemFp[base + face] = rf.footprint(f, face); // NO_FACE where untouched
                            }
                            nItems++;
                        }
                    }
                    break;
            }
        }

        // No built descendant: leave the parent unbuilt (planner reads the §6 optimistic default). Mirrors
        // combineNode's "parent.built = any child built".
        if (!anyChildBuilt) {
            p.setBuilt(parentLevel, parentRow, false);
            return;
        }

        final RegionFragments out = p.ensureFragments(parentLevel, parentRow);
        out.reset(coarseG(parentLevel));
        out.setPassFrac(passN == 0 ? 0 : (int) Math.round((double) passSum / passN));
        out.setAvgSolidHardness(hardN == 0 ? 0 : (int) Math.round((double) hardSum / hardN));

        // Uniform-kind preservation: only when EVERY child is built and the same uniform kind (so the coarse
        // node keeps the air-chute / swim / mine semantics). A child unbuilt or of a different kind ⇒ MIXED.
        if (builtChildren == children && solidBuilt == children) {
            out.setKind(RegionFragments.KIND_SOLID);
            out.setFragmentCount(0);
            p.setBuilt(parentLevel, parentRow, true);
            return;
        }
        if (builtChildren == children && airBuilt == children) {
            out.setKind(RegionFragments.KIND_AIR);
            out.setFragmentCount(0);
            p.setBuilt(parentLevel, parentRow, true);
            return;
        }
        if (builtChildren == children && waterBuilt == children) {
            out.setKind(RegionFragments.KIND_WATER);
            out.setFragmentCount(0);
            p.setBuilt(parentLevel, parentRow, true);
            return;
        }

        out.setKind(RegionFragments.KIND_MIXED);

        if (nItems == 0) {
            // MIXED but no passable item (e.g. solid + collapsed-solid children): a uniform mine-through mass.
            out.setFragmentCount(0);
            p.setBuilt(parentLevel, parentRow, true);
            return;
        }

        // --- union-find the items across internal split faces -------------------------------------------
        final int[] uf = UF.get();
        for (int k = 0; k < nItems; k++) uf[k] = k;
        for (int a = 0; a < nItems; a++) {
            for (int b = a + 1; b < nItems; b++) {
                final int sa = itemSlot[a], sb = itemSlot[b];
                final int xor = sa ^ sb;
                if (xor != 1 && xor != 2 && xor != 4) continue;        // not adjacent (differ on >1 axis or same slot)
                final int axis = xor == 1 ? 0 : (xor == 2 ? 1 : 2);    // X / Z / Y
                if (axis == 2 && children == 4) continue;              // quadtree: Y is not split
                final int lo = (sa & xor) == 0 ? a : b;                // the child on the low (bit 0) side
                final int hi = lo == a ? b : a;
                final int fLo = plusFace(axis);                        // low child meets neighbour through +axis
                final int fHi = minusFace(axis);                       // high child through −axis
                if (((itemMask[lo] >> fLo) & 1) == 0) continue;
                if (((itemMask[hi] >> fHi) & 1) == 0) continue;
                if (overlap(itemFp[lo * 6 + fLo], itemFp[hi * 6 + fHi])) union(uf, a, b);
            }
        }

        // --- components → parent fragments --------------------------------------------------------------
        final int[] compOf = COMP_OF.get();
        final int[] accMinU = ACC_MINU.get(), accMaxU = ACC_MAXU.get();
        final int[] accMinV = ACC_MINV.get(), accMaxV = ACC_MAXV.get();
        Arrays.fill(compOf, 0, nItems, -1);
        int compCount = 0;
        boolean collapsed = false;

        for (int k = 0; k < nItems; k++) {
            final int root = find(uf, k);
            int comp = compOf[root];
            if (comp == -1) {
                if (compCount >= RegionFragments.MAX_FRAGMENTS) { collapsed = true; continue; }
                comp = compCount++;
                compOf[root] = comp;
                final int cb = comp * 6;
                for (int f = 0; f < 6; f++) { accMinU[cb + f] = Integer.MAX_VALUE; accMaxU[cb + f] = -1;
                                              accMinV[cb + f] = Integer.MAX_VALUE; accMaxV[cb + f] = -1; }
            }
            compOf[k] = comp;
        }

        if (collapsed) {
            out.setCollapsed(true);
            out.setFragmentCount(0);
            p.setBuilt(parentLevel, parentRow, true);
            return;
        }

        // Project each item's outer-flush footprints onto the parent faces of its component.
        for (int k = 0; k < nItems; k++) {
            final int comp = compOf[k];
            if (comp < 0) continue;
            final int slot = itemSlot[k];
            final int bitX = slot & 1, bitZ = (slot >> 1) & 1, bitY = (slot >> 2) & 1;
            final int cb = comp * 6;
            for (int face = 0; face < 6; face++) {
                if (((itemMask[k] >> face) & 1) == 0) continue;
                if (!childFlushWithParentFace(face, bitX, bitY, bitZ, children)) continue;
                projectFootprintOntoParentFace(itemFp[k * 6 + face], face, bitX, bitY, bitZ, children,
                        accMinU, accMaxU, accMinV, accMaxV, cb);
            }
        }

        // Emit the fragment records.
        for (int comp = 0; comp < compCount; comp++) {
            final int cb = comp * 6;
            int faceMaskBits = 0;
            for (int f = 0; f < 6; f++) {
                if (accMaxU[cb + f] >= 0) {
                    faceMaskBits |= (1 << f);
                    packed[f] = RegionFragments.packFootprint(accMinU[cb + f], accMaxU[cb + f],
                            accMinV[cb + f], accMaxV[cb + f]);
                } else {
                    packed[f] = RegionFragments.NO_FACE;
                }
            }
            out.setFragment(comp, faceMaskBits, packed);
        }
        out.setFragmentCount(compCount);
        p.setBuilt(parentLevel, parentRow, true);
    }

    /** Append one synthetic full-face item (uniform-open / unbuilt child) for child slot {@code i}. */
    private static int addSynthetic(int[] slot, int[] mask, int[] fp, int n, int i) {
        if (n >= MAX_ITEMS) return n;
        slot[n] = i;
        mask[n] = 0x3F; // all six faces
        final int base = n * 6;
        for (int f = 0; f < 6; f++) fp[base + f] = RegionFragments.NO_FACE; // full face
        return n + 1;
    }

    /** Whether child slot (bitX,bitY,bitZ) is flush with parent outer {@code face} (its quadrant on that face). */
    private static boolean childFlushWithParentFace(int face, int bitX, int bitY, int bitZ, int children) {
        switch (face) {
            case 0: return bitX == 0;                       // -X
            case 1: return bitX == 1;                       // +X
            case 4: return bitZ == 0;                       // -Z
            case 5: return bitZ == 1;                       // +Z
            case 2: return children == 4 || bitY == 0;      // -Y (quadtree: every child spans the slab)
            case 3: return children == 4 || bitY == 1;      // +Y
            default: return false;
        }
    }

    /**
     * Project a child's packed face footprint (child 16-bucket units) onto the parent's {@code face}, in the
     * parent's 16-bucket frame, and union it into the component's per-face bbox accumulator. Per in-face axis:
     * a split axis maps the child's 16 buckets into 8 parent buckets at the child's half-offset ({@code bit*8 +
     * c/2}); an unsplit axis (the quadtree's Y, where the child spans the full parent extent) maps 1:1.
     */
    private static void projectFootprintOntoParentFace(int childPacked, int face, int bitX, int bitY, int bitZ,
                                                       int children, int[] accMinU, int[] accMaxU,
                                                       int[] accMinV, int[] accMaxV, int cb) {
        int minU, maxU, minV, maxV;
        if (childPacked == RegionFragments.NO_FACE) {
            minU = 0; maxU = 15; minV = 0; maxV = 15;
        } else {
            minU = RegionFragments.footprintMinU(childPacked); maxU = RegionFragments.footprintMaxU(childPacked);
            minV = RegionFragments.footprintMinV(childPacked); maxV = RegionFragments.footprintMaxV(childPacked);
        }
        // Per-face in-face axes (RegionFragments): ±X→(Y,Z); ±Y→(X,Z); ±Z→(X,Y).
        final int uAxis, vAxis;
        switch (face) {
            case 0: case 1: uAxis = 2; vAxis = 1; break; // Y, Z
            case 2: case 3: uAxis = 0; vAxis = 1; break; // X, Z
            default:        uAxis = 0; vAxis = 2; break; // X, Y  (faces 4,5)
        }
        final boolean octree = children == 8;
        final int pMinU = projAxis(minU, uAxis, bitX, bitY, bitZ, octree);
        final int pMaxU = projAxis(maxU, uAxis, bitX, bitY, bitZ, octree);
        final int pMinV = projAxis(minV, vAxis, bitX, bitY, bitZ, octree);
        final int pMaxV = projAxis(maxV, vAxis, bitX, bitY, bitZ, octree);
        if (pMinU < accMinU[cb + face]) accMinU[cb + face] = pMinU;
        if (pMaxU > accMaxU[cb + face]) accMaxU[cb + face] = pMaxU;
        if (pMinV < accMinV[cb + face]) accMinV[cb + face] = pMinV;
        if (pMaxV > accMaxV[cb + face]) accMaxV[cb + face] = pMaxV;
    }

    /** Map a child in-face bucket (0..15) to the parent's 16-bucket frame for the given in-face axis. */
    private static int projAxis(int childBucket, int axis, int bitX, int bitY, int bitZ, boolean octree) {
        final boolean split = (axis == 2) ? octree : true; // X/Z always split; Y only in the octree
        if (!split) return childBucket;                    // child spans the full parent extent (quadtree Y)
        final int bit = axis == 0 ? bitX : (axis == 1 ? bitZ : bitY);
        return bit * 8 + (childBucket >> 1);               // 16 child buckets → 8 parent buckets at the offset
    }

    /** Footprint overlap on both in-face axes (a {@link RegionFragments#NO_FACE} ⇒ a full 0..15 face). */
    private static boolean overlap(int a, int b) {
        int minUA, maxUA, minVA, maxVA, minUB, maxUB, minVB, maxVB;
        if (a == RegionFragments.NO_FACE) { minUA = 0; maxUA = 15; minVA = 0; maxVA = 15; }
        else { minUA = RegionFragments.footprintMinU(a); maxUA = RegionFragments.footprintMaxU(a);
               minVA = RegionFragments.footprintMinV(a); maxVA = RegionFragments.footprintMaxV(a); }
        if (b == RegionFragments.NO_FACE) { minUB = 0; maxUB = 15; minVB = 0; maxVB = 15; }
        else { minUB = RegionFragments.footprintMinU(b); maxUB = RegionFragments.footprintMaxU(b);
               minVB = RegionFragments.footprintMinV(b); maxVB = RegionFragments.footprintMaxV(b); }
        return Math.max(minUA, minUB) <= Math.min(maxUA, maxUB)
                && Math.max(minVA, minVB) <= Math.min(maxVA, maxVB);
    }

    private static int find(int[] uf, int x) {
        while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x]; }
        return x;
    }

    private static void union(int[] uf, int a, int b) {
        int ra = find(uf, a), rb = find(uf, b);
        if (ra != rb) uf[ra] = rb;
    }
}
