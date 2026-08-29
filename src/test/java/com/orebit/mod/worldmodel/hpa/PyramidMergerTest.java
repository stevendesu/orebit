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
        assertFalse(parent.typeS(0), "uniform AIR items claim nothing — the parent air fragment is ¬S");
        assertFalse(parent.typeW(0), "truly-uniform AIR children are provably dry ⇒ ¬W");

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

    /** Seed level-0 child {@code i} as a built MIXED region with {@code nFrags} isolated (no-face) fragments. */
    private static void seedMixedFragments(CostPyramid p, int i, int nFrags) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        final int[] noFaces = new int[6];
        java.util.Arrays.fill(noFaces, RegionFragments.NO_FACE);
        for (int f = 0; f < nFrags; f++) rf.setFragment(f, 0, noFaces); // faceMask 0 ⇒ never unions
        rf.setFragmentCount(nFrags);
        p.setBuilt(0, row, true);
    }

    /** Seed level-0 child {@code i} as a built MIXED collapsed/stripped mass ({@code fc==0}) with {@code passFrac}. */
    private static void seedMixedMass(CostPyramid p, int i, int passFrac, boolean collapsed) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(passFrac);
        rf.setFragmentCount(0);
        rf.setCollapsed(collapsed);
        p.setBuilt(0, row, true);
    }

    // ===================================================================================================
    // The component cap at merge (61 = RegionFragments.MAX_FRAGMENTS since the corner sentinel took 61):
    // exactly MAX_FRAGMENTS disjoint components are kept; one more collapses the parent. Faceless fragments
    // never union, so each child fragment is its own component and the counts are exact.
    // ===================================================================================================
    @Test
    void mergeAtCap_61Components_notCollapsed() {
        final CostPyramid p = new CostPyramid();
        for (int i = 1; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID); // walls — no items
        seedMixedFragments(p, 0, RegionFragments.MAX_FRAGMENTS); // an at-cap set of disjoint fragments in one child
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind());
        assertFalse(parent.isCollapsed(), "an at-cap component count — no collapse");
        assertEquals(RegionFragments.MAX_FRAGMENTS, parent.fragmentCount(), "all at-cap components kept exactly");
    }

    @Test
    void mergeOverCap_62ndComponent_collapses() {
        final CostPyramid p = new CostPyramid();
        for (int i = 1; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedMixedFragments(p, 0, RegionFragments.MAX_FRAGMENTS); // an at-cap component set…
        seedMixedFragments(p, 3, 1);                             // …plus one more (diagonal child, faceless anyway)
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind());
        assertTrue(parent.isCollapsed(), "one component past the cap → collapsed");
        assertEquals(0, parent.fragmentCount(), "a collapsed parent stores no fragment records");
    }

    // ===================================================================================================
    // The nItems == 0 mine-through path: a MIXED parent with no passable item (solid children + an
    // impassable count-0 mass) is fc=0 / collapsed=FALSE — an honestly-zero uniform mass, NOT the cap
    // collapse (the distinction the v6 count-field sentinel now persists).
    // ===================================================================================================
    @Test
    void mineThroughParent_fcZero_notCollapsed() {
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 7; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedMixedMass(p, 7, /*passFrac*/ 0, /*collapsed*/ false); // impassable stripped mass — no item
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind(), "solid + MIXED-mass children ⇒ MIXED parent");
        assertEquals(0, parent.fragmentCount(), "no passable item ⇒ uniform mine-through mass");
        assertFalse(parent.isCollapsed(), "mine-through mass is honestly-zero, NOT the cap collapse");
    }

    // ===================================================================================================
    // Typed fragments (DESIGN-typed-fragments.md §5.5): the union-merge ORs the type bits — a parent
    // component's S/W = OR over its merged children's bits; synthetic items type per their optimism class.
    // ===================================================================================================

    /** Seed level-0 child {@code i} as a built MIXED region with ONE full-face fragment typed {@code typeBits}. */
    private static void seedTypedOpen(CostPyramid p, int i, int typeBits) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        final int[] packed = new int[6];
        for (int f = 0; f < 6; f++) packed[f] = RegionFragments.packFootprint(0, 15, 0, 15);
        rf.setFragment(0, 0x3F, packed);
        rf.setFragmentTypes(0, typeBits);
        rf.setFragmentCount(1);
        p.setBuilt(0, row, true);
    }

    @Test
    void typeBits_orMergeAcrossUnion() {
        // Children 0 (typed S) and 1 (typed W) are X-adjacent with overlapping full faces ⇒ they union into
        // ONE parent fragment whose types are the OR: {S,W}. Everything else solid.
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedTypedOpen(p, 0, RegionFragments.TYPE_S);
        seedTypedOpen(p, 1, RegionFragments.TYPE_W);
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind());
        assertEquals(1, parent.fragmentCount(), "the two open children union into one parent fragment");
        assertTrue(parent.typeS(0), "S ORs across the union-merge");
        assertTrue(parent.typeW(0), "W ORs across the union-merge");
    }

    @Test
    void syntheticItems_typeByOptimismClass() {
        // Unbuilt child: optimistic S (a gate must never refuse a crossing because unexplored terrain
        // "proved" ¬S·¬W), no W claim.
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 7; i++) seedUniform(p, i, RegionFragments.KIND_SOLID); // slot 7 left unbuilt
        final RegionFragments parent = merge8(p);
        assertEquals(RegionFragments.KIND_MIXED, parent.kind());
        assertEquals(1, parent.fragmentCount(), "the unbuilt child contributes one synthetic fragment");
        assertTrue(parent.typeS(0), "unbuilt ⇒ optimistic S");
        assertFalse(parent.typeW(0), "unbuilt ⇒ no W claim");

        // Uniform WATER child: kind implies water ⇒ the item claims W; uniform records never claim S.
        final CostPyramid q = new CostPyramid();
        for (int i = 0; i < 7; i++) seedUniform(q, i, RegionFragments.KIND_SOLID);
        seedUniform(q, 7, RegionFragments.KIND_WATER);
        final RegionFragments wparent = merge8(q);
        assertEquals(1, wparent.fragmentCount());
        assertTrue(wparent.typeW(0), "uniform WATER child ⇒ W");
        assertFalse(wparent.typeS(0), "uniform records never claim S");
    }

    // ===================================================================================================
    // §4.9 CORNER UNIONS (DESIGN-region-corner-crossing-v2.md, R27/R27a/R38, and the I2 confirmation of
    // §0.2): two corner-adjacent child masses whose footprints meet at the shared edge fuse into ONE
    // parent fragment iff BOTH masses carry TYPE_S; a refuted L0 corner row (§4.10's consult) refuses the
    // union. disjointAirColumns_twoFragments above stays the ¬S pin: typeless air columns never fuse.
    // ===================================================================================================

    /** Seed child {@code i} as MIXED with one TYPE_S fragment whose footprints cover the given faces fully. */
    private static void seedCornerMass(CostPyramid p, int i, int faceMaskBits) {
        final int rx = i & 1, rz = (i >> 1) & 1, ry = (i >> 2) & 1;
        final int row = p.rowFor(0, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        final int[] packed = new int[6];
        for (int f = 0; f < 6; f++) {
            packed[f] = ((faceMaskBits >> f) & 1) != 0
                    ? RegionFragments.packFootprint(0, 15, 0, 15) : RegionFragments.NO_FACE;
        }
        rf.setFragment(0, faceMaskBits, packed);
        rf.setFragmentTypes(0, RegionFragments.TYPE_S);
        rf.setFragmentCount(1);
        p.setBuilt(0, row, true);
    }

    /** The I2 confirmation (§0.2): an X+Z corner-connected TYPE_S pair — slots 0 (x0,z0) and 3 (x1,z1),
     *  everything else solid — WAS two parent fragments under the 6-face union; the §4.9 corner union
     *  makes it ONE, because both masses are S and their full face footprints meet at the shared edge. */
    @Test
    void cornerConnectedTypeSPair_fusesToOneParentFragment() {
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedCornerMass(p, 0, 0x3F);
        seedCornerMass(p, 3, 0x3F);
        final RegionFragments parent = merge8(p);

        assertEquals(RegionFragments.KIND_MIXED, parent.kind());
        assertEquals(1, parent.fragmentCount(),
                "an S+S corner-connected pair fuses (I2: the split WAS real under 6-face union — "
                        + "disjointAirColumns pins the ¬S case at two)");
        assertTrue(parent.typeS(0), "the fused mass ORs both children's S");
    }

    /** R38: one mass typeless ⇒ NO union — the gate is MASS-level TYPE_S on both sides. */
    @Test
    void cornerPairWithTypelessMass_doesNotFuse() {
        final CostPyramid p = new CostPyramid();
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedCornerMass(p, 0, 0x3F);
        // Slot 3: same shape but TYPELESS (a pure-air pocket) — the §2 case the airGated gate refuses;
        // fusing it would launder it into a surfaceable mass at BUILD time (R38's exact hazard).
        final int row = p.rowFor(0, 1, 0, 1);
        final RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        final int[] packed = new int[6];
        for (int f = 0; f < 6; f++) packed[f] = RegionFragments.packFootprint(0, 15, 0, 15);
        rf.setFragment(0, 0x3F, packed);
        rf.setFragmentTypes(0, 0);
        rf.setFragmentCount(1);
        p.setBuilt(0, row, true);
        final RegionFragments parent = merge8(p);

        assertEquals(2, parent.fragmentCount(), "a typeless mass never corner-fuses (R38)");
    }

    /** §4.10's consult: a surviving L0 corner-refutation row between the two leaves' exact fragments
     *  refuses the union — the structural half of R30's settling argument. */
    @Test
    void cornerConsult_refutedRowRefusesTheFusion() {
        final CostPyramid p = new CostPyramid();
        final RegionCrossingMemory mem = new RegionCrossingMemory();
        // The refuted diagonal pair the blame collapse emits: (leaf 0,0,0 frag 0) → (leaf 1,0,1 frag 0).
        mem.record(0, InvalidationRollup.fragmentKey(0, 0, 0, 0), InvalidationRollup.fragmentKey(1, 0, 1, 0),
                0L, RegionCrossingMemory.PROV_PROOF, (a, b) -> a == b);
        p.setCrossingMemory(mem);
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedCornerMass(p, 0, 0x3F);
        seedCornerMass(p, 3, 0x3F);
        final RegionFragments parent = merge8(p);

        assertEquals(2, parent.fragmentCount(),
                "the surviving L0 refutation gates the union — the parent stays split (§4.10/R30)");
    }

    /** The consult is exact-keyed at L1: a row naming a DIFFERENT fragment pair does not refuse. */
    @Test
    void cornerConsult_unrelatedRowDoesNotRefuse() {
        final CostPyramid p = new CostPyramid();
        final RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, InvalidationRollup.fragmentKey(0, 0, 0, 7), InvalidationRollup.fragmentKey(1, 0, 1, 7),
                0L, RegionCrossingMemory.PROV_PROOF, (a, b) -> a == b);
        p.setCrossingMemory(mem);
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedCornerMass(p, 0, 0x3F);
        seedCornerMass(p, 3, 0x3F);
        final RegionFragments parent = merge8(p);

        assertEquals(1, parent.fragmentCount(),
                "a row naming other fragments is not this corner's refutation — the union stands");
    }

    /** R30's structural corrective end-to-end at the merge layer: fuse, refute, re-merge ⇒ split. */
    @Test
    void remergeAfterRefutation_splitsTheParent() {
        final CostPyramid p = new CostPyramid();
        final RegionCrossingMemory mem = new RegionCrossingMemory();
        p.setCrossingMemory(mem);
        for (int i = 0; i < 8; i++) seedUniform(p, i, RegionFragments.KIND_SOLID);
        seedCornerMass(p, 0, 0x3F);
        seedCornerMass(p, 3, 0x3F);
        assertEquals(1, merge8(p).fragmentCount(), "fused while unrefuted");

        mem.record(0, InvalidationRollup.fragmentKey(0, 0, 0, 0), InvalidationRollup.fragmentKey(1, 0, 1, 0),
                0L, RegionCrossingMemory.PROV_PROOF, (a, b) -> a == b);
        PyramidMerger.remergeSharedAncestors(p, 0, 0, 0, 1, 0, 1);
        final RegionFragments parent = p.fragmentRecord(1, p.rowFor(1, 0, 0, 0));
        assertEquals(2, parent.fragmentCount(),
                "the refutation un-fuses the parent on re-merge (R30 — evict-and-rediscover is upstream)");
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
