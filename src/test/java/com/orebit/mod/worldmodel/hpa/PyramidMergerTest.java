package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the HPA* fragment <b>pyramid merge</b> (HPA-FRAGMENTS.md §S5; {@link PyramidMerger}
 * {@code combineFragments} / {@code mergeLevelFragments}). Pure POJO — the cost pyramid + fragment records are
 * MC-free, so children are seeded directly with no {@code Bootstrap} / {@code NavSection} (mirrors
 * {@link FragmentBuilderTest} / {@link CostCodecTest}).
 *
 * <p>The parent under test is the level-1 node {@code (0,0,0)}; its 8 octree children live at level 0 coords
 * {@code (i&1, (i>>2)&1, (i>>1)&1)} (bit0=X, bit1=Z, bit2=Y — see {@link RegionAddress#childRX} et al). The
 * "bottom layer" (parent −Y, {@code bitY==0}) is children {@code i ∈ 0..3}; the "top layer" is {@code 4..7}.
 */
public final class PyramidMergerTest {

    /** Seed level-0 child {@code i} of parent (0,0,0) as a built uniform region of {@code kind}. */
    private static void seedUniform(CostPyramid p, int i, int kind) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(kind);
        rf.setPassFrac(kind == RegionFragments.KIND_SOLID ? 0 : 15);
        p.setBuilt(0, row, true);
    }

    private static RegionFragments merge8(CostPyramid p) {
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);
        assertTrue(p.isBuilt(1, pr), "parent built when ≥1 child is built");
        final RegionFragments parent = p.fragmentRecord(1, pr);
        assertNotNull(parent, "parent fragment record materialized");
        return parent;
    }

    @Test
    void allAirChildren_parentUniformAir() {
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_AIR);
        final RegionFragments parent = merge8(p);
        assertEquals(RegionFragments.KIND_AIR, parent.kind(), "all-air children ⇒ uniform AIR parent");
        assertEquals(0, parent.fragmentCount(), "a uniform kind carries no fragment records");
    }

    @Test
    void allSolidChildren_parentUniformSolid() {
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        final RegionFragments parent = merge8(p);
        assertEquals(RegionFragments.KIND_SOLID, parent.kind(), "all-solid children ⇒ uniform SOLID parent");
        assertEquals(0, parent.fragmentCount());
    }

    @Test
    void noBuiltChild_parentLeftUnbuilt() {
        // Children unbuilt (the §6 optimistic-default case): the parent must stay unbuilt so the planner reads
        // the optimistic default rather than a fabricated "known" record.
        final CostPyramid p = new CostPyramid();
        final int pr = p.rowFor(1, 0, 0, 0);
        PyramidMerger.combineFragments(p, 1, pr, 0, 0, 0);
        assertFalse(p.isBuilt(1, pr), "no built descendant ⇒ parent unbuilt");
    }

    @Test
    void airBottomSolidTop_oneFragment_noPlusYopening() {
        // Bottom layer (bitY=0, i 0..3) is open air; top layer (bitY=1, i 4..7) is solid rock. The four bottom
        // air children connect through their shared internal X/Z faces into ONE component; the solid top wall
        // them off, so the parent fragment opens on the four sides + the bottom (−Y) but NOT the top (+Y).
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 4; i++) seedUniform(p, i, RegionFragments.KIND_AIR);
        for (int i = 4; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind(), "mixed air/solid children ⇒ MIXED parent");
        assertEquals(1, parent.fragmentCount(), "the four connected air children = one parent fragment");

        assertTrue(parent.touchesFace(0, 2), "−Y opening (bottom layer is flush with the parent floor)");
        assertFalse(parent.touchesFace(0, 3), "no +Y opening (top layer is solid)");
        assertTrue(parent.touchesFace(0, 0), "−X opening");
        assertTrue(parent.touchesFace(0, 1), "+X opening");
        assertTrue(parent.touchesFace(0, 4), "−Z opening");
        assertTrue(parent.touchesFace(0, 5), "+Z opening");

        // −X footprint: in-face axes (u=Y, v=Z). Only the bottom layer is flush, so the Y span projects to the
        // lower half [0,7]; both Z children are present, so the Z span is full [0,15].
        final int fp = parent.footprint(0, 0);
        assertEquals(0, RegionFragments.footprintMinU(fp), "−X minU (Y) = bottom of the lower half");
        assertEquals(7, RegionFragments.footprintMaxU(fp), "−X maxU (Y) = top of the lower half");
        assertEquals(0, RegionFragments.footprintMinV(fp), "−X minV (Z)");
        assertEquals(15, RegionFragments.footprintMaxV(fp), "−X maxV (Z) = full Z span");
    }

    @Test
    void disjointAirColumns_twoFragments() {
        // Two vertical air columns that never touch horizontally: the (x=0,z=0) corner stacked (i0 bottom, i4
        // top) and the (x=1,z=1) corner stacked (i3 bottom, i7 top); everything else solid. The two columns are
        // diagonal neighbours (differ on TWO axes) so they never union ⇒ two separate parent fragments.
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedUniform(p, 0, RegionFragments.KIND_AIR); // (x0,z0,y0)
        seedUniform(p, 4, RegionFragments.KIND_AIR); // (x0,z0,y1) — stacked above i0 (shared +Y/−Y face)
        seedUniform(p, 3, RegionFragments.KIND_AIR); // (x1,z1,y0)
        seedUniform(p, 7, RegionFragments.KIND_AIR); // (x1,z1,y1) — stacked above i3
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind());
        assertEquals(2, parent.fragmentCount(), "two disjoint air columns = two parent fragments");
    }

    @Test
    void mergeLevelFragments_bulkBuildsParents() {
        // The bulk driver builds level 1 from every interned level-0 row.
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_AIR);
        PyramidMerger.mergeLevelFragments(p, 0);
        final int pr = p.rowIfPresent(1, 0, 0, 0);
        assertTrue(pr >= 0 && p.isBuilt(1, pr), "the level-1 parent was bulk-built");
        assertEquals(RegionFragments.KIND_AIR, p.fragmentRecord(1, pr).kind());
    }
}
