package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;

/**
 * Fragment-pyramid roll-up for the HPA* region tier (HPA-FRAGMENTS.md §3.1, §5, §7 — the S5 coarse merge).
 *
 * <p>The {@link CostPyramid} stores, per node, a {@link RegionFragments} connectivity record. The leaf level
 * (level 0) is filled directly by {@link FragmentLeafComputer}; every ancestor's record is <b>derived from its
 * children</b> by this merger as a pure function of the 8 (octree) / 4 (quadtree) children's records. This file
 * owns that derivation ({@link #combineFragments}) and two drivers:
 * <ul>
 *   <li>{@link #mergeUpFragments} — after one leaf changes, walk parent→root recomputing each ancestor from its
 *       (now-current) children, stopping the moment a level's output is unchanged. O(levels) per leaf change
 *       (incremental maintenance, HPA-FRAGMENTS.md §6.5).</li>
 *   <li>{@link #mergeLevelFragments} — bulk: build every {@code level+1} parent that has at least one child at
 *       {@code level}, by enumerating the level's interned rows and recombining each parent.</li>
 * </ul>
 *
 * <h2>Octree → quadtree child enumeration (§2)</h2>
 * A node at {@code parentLevel} has {@link RegionAddress#childCount(int)} children at {@code parentLevel-1}:
 * <b>8</b> below the octree→quadtree transition ({@code parentLevel <= OCTREE_TOP}) and <b>4</b> at/above it
 * ({@code parentLevel > OCTREE_TOP}, where the cell already spans the full padded vertical slab so {@code ry}
 * is pinned to 0). The child index {@code i} bit layout is {@code bit0 = X, bit1 = Z, bit2 = Y} (Y bit only
 * meaningful in the octree); see {@link RegionAddress#childRX}/{@link RegionAddress#childRZ}/
 * {@link RegionAddress#childRY}.
 *
 * <h2>The merge operator (§7)</h2>
 * Union-find the children's fragments across the parent's shared internal split faces (two child fragments
 * connect when both touch the shared face and their footprints overlap), project each resulting component's
 * outer-flush child footprints onto the parent's six outer faces in face-relative 16-bucket units, and carry
 * the uniform-kind / {@code avgSolidHardness} / {@code passFrac} aggregates + the cap-collapse rule (§3.1). An
 * unbuilt child is treated optimistically (so unloaded terrain never walls off the merge); {@code parent.built
 * = any child built}. Identical {@link RegionFragments} schema to a leaf, so the region A* reads every level
 * through one set of accessors — only the producer differs.
 *
 * <h2>House style (§14)</h2>
 * Pure static, no per-call allocation past warmup: the combine reads/writes the {@link CostPyramid} SoA in
 * place and uses thread-local reused scratch for the union-find/footprint accumulators. No edge costs stored
 * (derived at query, §2.2). No MC API (the pyramid is the only state touched).
 */
public final class PyramidMerger {

    private PyramidMerger() {}

    // ===================================================================================================
    // Fragment-model roll-up (HPA-FRAGMENTS.md §3.1, §5, §7 — the S5 coarse pyramid merge)
    //
    // Recompute a parent's RegionFragments record as a PURE FUNCTION of its 8 (octree) / 4 (quadtree)
    // children's records: union-find the children's fragments across the shared internal faces (two child
    // fragments connect when their footprints overlap on the face between their two children), and project each
    // resulting component's outer-flush child footprints onto the parent's six outer faces in face-relative
    // 16-bucket units. Identical schema to a leaf, so the region A* reads every level through one set of
    // accessors; the only difference is the producer.
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
    // Fragment drivers (the fragment model is the only model — the center mergeUp/mergeLevel and the
    // RegionGrid.HPA_FRAGMENTS dispatch flag were deleted in the s36 cleanup)
    // ---------------------------------------------------------------------------------------------------

    /**
     * After a leaf's {@link RegionFragments} record changed, walk parent→root recomputing each ancestor from
     * its children via {@link #combineFragments}. Early-out: stop
     * ascending the moment a parent's recompute leaves it unbuilt (no built child below it) — there is nothing
     * above to change. O(levels) per leaf change (HPA-FRAGMENTS.md §6.5).
     */
    public static void mergeUpFragments(CostPyramid p, int level0Rx, int level0Ry, int level0Rz) {
        int childLevel = 0;
        int crx = level0Rx, cry = level0Ry, crz = level0Rz;
        // Roll up only to MAX_COARSE_LEVEL — no world-root node (HPA-FRAGMENTS.md §S5; RegionAddress).
        while (childLevel < RegionAddress.MAX_COARSE_LEVEL) {
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
     * Bulk-build level {@code level+1} from every node interned at
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
     * built}; an all-unbuilt parent is left unbuilt so the planner reads the §6 optimistic default rather than a
     * fabricated "known air".
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
