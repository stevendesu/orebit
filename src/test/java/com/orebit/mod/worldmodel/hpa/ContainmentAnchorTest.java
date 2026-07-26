package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the coarse containment anchor query ({@link InvalidationRollup#containedParentFragment} —
 * the walk step behind {@link RegionGrid#containedFragment} / the region A*'s {@code anchorFragment}): the
 * parent fragment a child fragment actually merged into, re-derived through the merge's own union-find,
 * replacing the nearest-centroid guess at coarse levels.
 *
 * <p>Regression shape (the t=35 cliff FAIL): keep-all fragments make sealed interior pockets REAL records —
 * a faceless fragment's centroid defaults to the region center and out-attracts every open fragment's
 * face-averaged centroid for a mid-region bot, so the search anchored to a fragment with zero admissible
 * edges and drained after one expansion. Containment must map the bot's child fragment to the OPEN parent
 * component and the pocket to its own faceless parent fragment. Pure POJO like {@link PyramidMergerTest}
 * (parent = level-1 node {@code (0,0,0)}; child slot bit0=X, bit1=Z, bit2=Y).
 */
public final class ContainmentAnchorTest {

    private static final int FULL = RegionFragments.packFootprint(0, 15, 0, 15);

    /** Seed child {@code i} as built uniform {@code kind}. */
    private static void seedUniform(CostPyramid p, int i, int kind) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(kind);
        rf.setPassFrac(kind == RegionFragments.KIND_SOLID ? 0 : 15);
        p.setBuilt(0, row, true);
    }

    /** Seed child {@code i} as built MIXED with the given per-fragment face masks (full footprints on set faces). */
    private static void seedMixed(CostPyramid p, int i, int... faceMasks) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(4);
        final int[] packed = new int[6];
        for (int f = 0; f < faceMasks.length; f++) {
            for (int face = 0; face < 6; face++) {
                packed[face] = ((faceMasks[f] >> face) & 1) != 0 ? FULL : RegionFragments.NO_FACE;
            }
            rf.setFragment(f, faceMasks[f], packed);
            rf.setFragmentTypes(f, RegionFragments.TYPE_S);
        }
        rf.setFragmentCount(faceMasks.length);
        p.setBuilt(0, row, true);
    }

    /**
     * The regression shape: child 0 carries an open fragment (unions across the internal X face with child 1's)
     * AND a sealed faceless pocket; children 2..7 solid. Containment must resolve each child fragment to the
     * parent component it merged into — never to the pocket by proximity.
     */
    @Test
    void sealedPocketAndOpenComponent_resolveByContainment() {
        final CostPyramid p = new CostPyramid();
        // Child 0's f0 spans −X (parent-outer) → +X (the internal split face, where it unions with child 1's
        // f0, which itself reaches its own +X = parent-outer). f1 is the SEALED pocket (mask 0).
        seedMixed(p, 0, (1 << 0) | (1 << 1), 0);
        seedMixed(p, 1, (1 << 0) | (1 << 1));
        for (int i = 2; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);

        final RegionFragments parent = p.fragmentRecord(1, pr);
        assertEquals(2, parent.fragmentCount(), "open union component + sealed pocket = two parent fragments");
        assertTrue(parent.faceMask(0) != 0, "the union component is open");
        assertEquals(0, parent.faceMask(1), "the pocket rolls up faceless (a faceless item never unions)");

        // Child (0,0,0) f0 and child (1,0,0) f0 merged into parent f0; the pocket child fragment is parent f1.
        assertEquals(0, InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 0),
                "child 0 f0 → the open parent component");
        assertEquals(0, InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 1, 0, 0, 0),
                "child 1 f0 → the same open parent component");
        assertEquals(1, InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 1),
                "the sealed pocket → its own faceless parent fragment");
    }

    /** A child fragment id the merge never saw (renumbered/rebuilt child) must refuse, not guess. */
    @Test
    void unknownChildFragment_refuses() {
        final CostPyramid p = new CostPyramid();
        seedMixed(p, 0, 1 << 1);
        seedMixed(p, 1, 1 << 0);
        for (int i = 2; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);

        assertEquals(-1, InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 5),
                "unknown child fragment id ⇒ -1 (caller falls back to nearest-centroid)");
    }

    /** A stale parent record (component count no longer matching a re-derivation) must refuse, not guess. */
    @Test
    void staleParentRecord_refuses() {
        final CostPyramid p = new CostPyramid();
        seedMixed(p, 0, 1 << 1, 0);
        seedMixed(p, 1, 1 << 0);
        for (int i = 2; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);

        // Rebuild child 1 with an extra fragment WITHOUT re-merging the parent: the derived partition (3
        // components) no longer matches the stored record (2) — containment ids cannot be trusted.
        seedMixed(p, 1, 1 << 0, 1 << 3);
        assertEquals(-1, InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 0),
                "stale parent record ⇒ -1, never a guessed id");
    }

    /** A fragmentless parent record (uniform kind) maps every child fragment to 0 — the A*'s key for it. */
    @Test
    void uniformParent_mapsToZero() {
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_AIR);
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);
        assertEquals(RegionFragments.KIND_AIR, p.fragmentRecord(1, pr).kind());

        assertEquals(0, InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 0),
                "uniform parent ⇒ fragment 0 (how the A* keys such a node)");
    }

    /** Unbuilt siblings take the optimism path and must not break containment for the built child. */
    @Test
    void unbuiltSiblings_containmentStillResolves() {
        final CostPyramid p = new CostPyramid();
        seedMixed(p, 0, (1 << 1) | (1 << 0), 0); // f0 open −X/+X; f1 sealed. Children 1..7 unbuilt.
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);

        final int open = InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 0);
        final int pocket = InvalidationRollup.containedParentFragment(p, 1, 0, 0, 0, 0, 0, 0, 1);
        assertTrue(open >= 0, "built child's open fragment resolves through unbuilt-sibling optimism");
        assertTrue(pocket >= 0, "the pocket resolves too");
        assertFalse(open == pocket, "open fragment and sealed pocket stay distinct parent fragments");
        assertEquals(0, p.fragmentRecord(1, pr).faceMask(pocket),
                "the pocket's parent fragment is still faceless (optimistic items cannot attach to it)");
    }
}
