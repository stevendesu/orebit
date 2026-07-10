package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the <b>pure connectivity core</b> {@link FragmentBuilder} (HPA-FRAGMENTS.md §3, slice S1) —
 * flood fill + occupiability filter + cap + footprint extraction. These need <b>no Minecraft</b>: the builder
 * takes raw {@code boolean} masks + tallies, so fixtures are synthesized directly (no {@code Bootstrap}, no
 * {@link com.orebit.mod.worldmodel.pathing.NavSection NavSection}).
 *
 * <p>Grids are 16³ in the canonical section-local index {@code i = (y<<8)|(z<<4)|x} (the {@code G == 16}
 * form of {@link FragmentBuilder}'s power-of-two index), matching {@code ConnectivityBenchmark}.
 *
 * <p>Coverage (the S1 acceptance set):
 * <ul>
 *   <li>OPEN (floor + air above) → 1 fragment;</li>
 *   <li>two disjoint tunnels → 2 fragments;</li>
 *   <li>checkerboard → 0 fragments (occupiability strips every singleton), not collapsed;</li>
 *   <li>&gt;63 isolated occupiable pockets → COLLAPSED;</li>
 *   <li>a single tunnel exiting one face → known footprint bbox.</li>
 * </ul>
 */
public class FragmentBuilderTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;

    // Face ids (canonical RegionAddress order).
    private static final int FX_NEG = 0, FX_POS = 1, FY_NEG = 2, FY_POS = 3, FZ_NEG = 4, FZ_POS = 5;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** A scenario's two masks + the tallies the builder needs, computed from the masks themselves. */
    private static RegionFragments build(boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, waterCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += 8; } // pretend all solid is stone (h≈8)
            if (standable[i]) standCount++;
        }
        RegionFragments out = new RegionFragments();
        FragmentBuilder.build(passable, standable, G,
                passCount, standCount, waterCount, hardnessSumSolid, solidCount, out);
        return out;
    }

    // ===================================================================================================
    // OPEN — a solid floor plane (y=0) with air above → one occupiable air component.
    // ===================================================================================================
    @Test
    void open_oneFragment() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;             // solid floor at y=0 (not passable)
                for (int y = 1; y < G; y++) passable[idx(x, y, z)] = true; // air above
            }
        }
        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind(), "floor+air is a MIXED region");
        assertFalse(rf.isCollapsed(), "one open component does not collapse");
        assertEquals(1, rf.fragmentCount(), "open floor+air = exactly one occupiable fragment");

        // The air component reaches the 4 side faces and the top, but NOT the bottom (y=0 is solid floor).
        assertTrue(rf.touchesFace(0, FX_NEG));
        assertTrue(rf.touchesFace(0, FX_POS));
        assertTrue(rf.touchesFace(0, FY_POS), "air reaches the top face");
        assertTrue(rf.touchesFace(0, FZ_NEG));
        assertTrue(rf.touchesFace(0, FZ_POS));
        assertFalse(rf.touchesFace(0, FY_NEG), "solid floor ⇒ no -Y opening");
    }

    // ===================================================================================================
    // TWO TUNNELS — two parallel 2-tall corridors along Z, separated by solid → two fragments.
    // ===================================================================================================
    @Test
    void twoDisjointTunnels_twoFragments() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnel(passable, standable, 4);   // tunnel A at x=4
        carveTunnel(passable, standable, 12);  // tunnel B at x=12 (solid x=5..11 between)

        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertFalse(rf.isCollapsed());
        assertEquals(2, rf.fragmentCount(), "two disjoint tunnels = two fragments");
    }

    /** A 2-tall (y=1,2) air corridor along all Z at column x, with a standable floor at y=0. */
    private static void carveTunnel(boolean[] passable, boolean[] standable, int x) {
        for (int z = 0; z < G; z++) {
            standable[idx(x, 0, z)] = true;
            passable[idx(x, 1, z)] = true;
            passable[idx(x, 2, z)] = true;
        }
    }

    // ===================================================================================================
    // CHECKERBOARD — (x+y+z)%2 passable, no floor anywhere → occupiability strips all 2048 singletons → 0.
    // ===================================================================================================
    @Test
    void checkerboard_zeroFragments() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int y = 0; y < G; y++)
                for (int z = 0; z < G; z++) {
                    // Keep the top layer solid so the optimistic out-of-grid-top headroom policy can't make
                    // a top-row singleton read as occupiable — this isolates the headroom strip we test.
                    boolean pass = ((x + y + z) & 1) == 0 && y < G - 1;
                    passable[idx(x, y, z)] = pass;
                    standable[idx(x, y, z)] = !pass; // the solid (off-parity) cells are walkable tops
                }

        RegionFragments rf = build(passable, standable);

        // standCount > 0 (the solid cells) ⇒ the flood runs; every passable cell is a 6-isolated singleton
        // whose head cell is solid ⇒ no 2-tall headroom ⇒ occupiability strips them all.
        assertEquals(RegionFragments.KIND_MIXED, rf.kind(), "has air + floor cells ⇒ MIXED (flood, not fast-path)");
        assertEquals(0, rf.fragmentCount(), "checkerboard: occupiability strips every singleton → 0 fragments");
        assertFalse(rf.isCollapsed(), "0 occupiable components is NOT the over-cap collapse");
    }

    // ===================================================================================================
    // OVER-CAP — 64 isolated 2-tall occupiable pockets (>63) → COLLAPSED.
    // ===================================================================================================
    @Test
    void overCap_collapses() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        // 8×8 = 64 columns at odd (x,z), separated by solid even rows so none connect.
        int columns = 0;
        for (int x = 1; x < G; x += 2) {
            for (int z = 1; z < G; z += 2) {
                standable[idx(x, 0, z)] = true;     // floor
                passable[idx(x, 1, z)] = true;      // feet
                passable[idx(x, 2, z)] = true;      // head (≥2-tall headroom)
                columns++;
            }
        }
        assertEquals(64, columns, "fixture should build 64 isolated pockets (> the 63 cap)");

        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertTrue(rf.isCollapsed(), "64 occupiable components exceeds the 63 cap → collapsed");
        assertEquals(0, rf.fragmentCount(), "a collapsed region stores no fragment records");
    }

    // ===================================================================================================
    // FOOTPRINT — a single 2-tall tunnel (y=1,2) over z=6..9 spanning all X → exits -X and +X only,
    // with a known bbox on the +X face (u=Y in {1,2}, v=Z in {6..9}).
    // ===================================================================================================
    @Test
    void knownOpening_footprintBbox() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 6; z <= 9; z++) {
                standable[idx(x, 0, z)] = true;
                passable[idx(x, 1, z)] = true;
                passable[idx(x, 2, z)] = true;
            }
        }
        RegionFragments rf = build(passable, standable);
        assertEquals(1, rf.fragmentCount(), "one tunnel = one fragment");

        // Touches exactly -X and +X (it spans all x; z=6..9 ≠ 0/15; y=1,2 ≠ 0/15).
        assertTrue(rf.touchesFace(0, FX_NEG));
        assertTrue(rf.touchesFace(0, FX_POS));
        assertFalse(rf.touchesFace(0, FY_NEG), "y=0 is floor (solid) ⇒ no -Y");
        assertFalse(rf.touchesFace(0, FY_POS), "tunnel top is y=2, not 15 ⇒ no +Y");
        assertFalse(rf.touchesFace(0, FZ_NEG), "z starts at 6 ⇒ no -Z");
        assertFalse(rf.touchesFace(0, FZ_POS), "z ends at 9 ⇒ no +Z");

        // +X footprint: u = Y in [1,2], v = Z in [6,9].
        int packed = rf.footprint(0, FX_POS);
        assertEquals(1, RegionFragments.footprintMinU(packed), "minU (Y)");
        assertEquals(2, RegionFragments.footprintMaxU(packed), "maxU (Y)");
        assertEquals(6, RegionFragments.footprintMinV(packed), "minV (Z)");
        assertEquals(9, RegionFragments.footprintMaxV(packed), "maxV (Z)");
    }

    // ===================================================================================================
    // FRAGMENT-CONTAINING (flood-from-bot, PERF-DESIGN region §4) — the id a cell belongs to must match the
    // id build() assigned that component, and be -1 for a non-fragment cell (fall back to nearest-centroid).
    // ===================================================================================================
    @Test
    void fragmentContaining_matchesBuildIds() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnel(passable, standable, 4);   // lower seed index (y=1,z=0,x=4) ⇒ fragment 0
        carveTunnel(passable, standable, 12);  // ⇒ fragment 1

        assertEquals(2, build(passable, standable).fragmentCount(), "fixture is two disjoint tunnels");

        // A cell is resolved to the fragment that CONTAINS it (not the nearest centroid).
        assertEquals(0, FragmentBuilder.fragmentContaining(passable, standable, G, idx(4, 1, 5)),
                "a cell in tunnel A is fragment 0");
        assertEquals(1, FragmentBuilder.fragmentContaining(passable, standable, G, idx(12, 2, 9)),
                "a cell in tunnel B is fragment 1");
        // A solid cell between the tunnels is in no fragment.
        assertEquals(-1, FragmentBuilder.fragmentContaining(passable, standable, G, idx(8, 1, 5)),
                "a non-passable (solid) cell has no fragment");
    }

    @Test
    void fragmentContaining_nonOccupiableAndCollapsed_returnMinusOne() {
        // Checkerboard: every passable cell is a non-occupiable singleton ⇒ -1.
        boolean[] cbPass = new boolean[CELLS];
        boolean[] cbStand = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int y = 0; y < G; y++)
                for (int z = 0; z < G; z++) {
                    boolean pass = ((x + y + z) & 1) == 0 && y < G - 1;
                    cbPass[idx(x, y, z)] = pass;
                    cbStand[idx(x, y, z)] = !pass;
                }
        int cbSeed = -1;
        for (int i = 0; i < CELLS; i++) if (cbPass[i]) { cbSeed = i; break; }
        assertTrue(cbSeed >= 0, "checkerboard has a passable cell");
        assertEquals(-1, FragmentBuilder.fragmentContaining(cbPass, cbStand, G, cbSeed),
                "a non-occupiable component yields no fragment id");

        // Over-cap collapse: a pocket cell ⇒ -1 (the stored record holds no fragments → fall back to centroid).
        boolean[] ocPass = new boolean[CELLS];
        boolean[] ocStand = new boolean[CELLS];
        for (int x = 1; x < G; x += 2)
            for (int z = 1; z < G; z += 2) {
                ocStand[idx(x, 0, z)] = true;
                ocPass[idx(x, 1, z)] = true;
                ocPass[idx(x, 2, z)] = true;
            }
        assertTrue(build(ocPass, ocStand).isCollapsed(), "64 pockets collapse");
        assertEquals(-1, FragmentBuilder.fragmentContaining(ocPass, ocStand, G, idx(1, 1, 1)),
                "a collapsed region resolves to no fragment id");
    }

    // ===================================================================================================
    // LABEL-ALL (the goal dig-flood's per-build label slabs) — for EVERY cell, the slab must answer exactly
    // what the single-target fragmentContaining resolver answers: kept id / -1, incl. the all--1 collapsed case.
    // ===================================================================================================
    @Test
    void labelAll_matchesFragmentContainingEverywhere() {
        // Two disjoint tunnels (kept ids 0 and 1) + solid in between.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnel(passable, standable, 4);
        carveTunnel(passable, standable, 12);
        assertLabelAllMatches(passable, standable, "two tunnels");

        // Checkerboard: every passable cell is a non-occupiable singleton ⇒ all -1.
        boolean[] cbPass = new boolean[CELLS];
        boolean[] cbStand = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int y = 0; y < G; y++)
                for (int z = 0; z < G; z++) {
                    boolean pass = ((x + y + z) & 1) == 0 && y < G - 1;
                    cbPass[idx(x, y, z)] = pass;
                    cbStand[idx(x, y, z)] = !pass;
                }
        assertLabelAllMatches(cbPass, cbStand, "checkerboard");

        // Over-cap collapse: fragmentContaining answers -1 everywhere ⇒ so must the slab.
        boolean[] ocPass = new boolean[CELLS];
        boolean[] ocStand = new boolean[CELLS];
        for (int x = 1; x < G; x += 2)
            for (int z = 1; z < G; z += 2) {
                ocStand[idx(x, 0, z)] = true;
                ocPass[idx(x, 1, z)] = true;
                ocPass[idx(x, 2, z)] = true;
            }
        assertLabelAllMatches(ocPass, ocStand, "collapsed");

        // OPEN floor+air (one big fragment touching most faces).
        boolean[] opPass = new boolean[CELLS];
        boolean[] opStand = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int z = 0; z < G; z++) {
                opStand[idx(x, 0, z)] = true;
                for (int y = 1; y < G; y++) opPass[idx(x, y, z)] = true;
            }
        assertLabelAllMatches(opPass, opStand, "open");
    }

    /** Assert {@code labelAll}'s slab equals {@code fragmentContaining}'s answer for all 4096 cells. */
    private static void assertLabelAllMatches(boolean[] passable, boolean[] standable, String what) {
        byte[] slab = new byte[CELLS];
        FragmentBuilder.labelAll(passable, standable, G, slab);
        for (int i = 0; i < CELLS; i++) {
            assertEquals(FragmentBuilder.fragmentContaining(passable, standable, G, i), slab[i],
                    what + ": labelAll diverges from fragmentContaining at cell " + i);
        }
    }

    // ===================================================================================================
    // BUILD-EMITTED LABELS (label-slab membership) — build()'s own flood stamps the record's
    // label slab; where published (≥2 kept, un-collapsed) it must equal fragmentContaining cell-for-cell,
    // and it must NOT be published for the trivial/degenerate records.
    // ===================================================================================================
    @Test
    void buildLabels_matchFragmentContainingEverywhere() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnel(passable, standable, 4);
        carveTunnel(passable, standable, 12);
        RegionFragments rf = build(passable, standable);

        byte[] labels = rf.labels();
        assertTrue(labels != null, "a 2-fragment build publishes its label slab");
        for (int i = 0; i < CELLS; i++) {
            assertEquals(FragmentBuilder.fragmentContaining(passable, standable, G, i), labels[i],
                    "build-emitted labels diverge from fragmentContaining at cell " + i);
        }
    }

    @Test
    void buildLabels_unpublishedForTrivialRecords() {
        // Single fragment: membership is trivial (no slab consumer) ⇒ not published.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnel(passable, standable, 4);
        assertEquals(null, build(passable, standable).labels(), "1 kept fragment ⇒ no label slab");

        // Over-cap collapse: the stored record holds no fragments ⇒ not published (all--1 contract).
        boolean[] ocPass = new boolean[CELLS];
        boolean[] ocStand = new boolean[CELLS];
        for (int x = 1; x < G; x += 2)
            for (int z = 1; z < G; z += 2) {
                ocStand[idx(x, 0, z)] = true;
                ocPass[idx(x, 1, z)] = true;
                ocPass[idx(x, 2, z)] = true;
            }
        assertEquals(null, build(ocPass, ocStand).labels(), "collapsed ⇒ no label slab");

        // Uniform fast-path (all solid) ⇒ not published.
        boolean[] soPass = new boolean[CELLS];
        boolean[] soStand = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) soStand[i] = true;
        assertEquals(null, build(soPass, soStand).labels(), "uniform SOLID ⇒ no label slab");

        // A rebuild that DROPS to one fragment must retract a previously published slab (reset()).
        RegionFragments reused = new RegionFragments();
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardness = 0;
        boolean[] twoPass = new boolean[CELLS];
        boolean[] twoStand = new boolean[CELLS];
        carveTunnel(twoPass, twoStand, 4);
        carveTunnel(twoPass, twoStand, 12);
        for (int i = 0; i < CELLS; i++) {
            if (twoPass[i]) passCount++; else { solidCount++; hardness += 8; }
            if (twoStand[i]) standCount++;
        }
        FragmentBuilder.build(twoPass, twoStand, G, passCount, standCount, 0, hardness, solidCount, reused);
        assertTrue(reused.labels() != null, "two tunnels ⇒ published");
        passCount = standCount = solidCount = 0; hardness = 0;
        boolean[] onePass = new boolean[CELLS];
        boolean[] oneStand = new boolean[CELLS];
        carveTunnel(onePass, oneStand, 4);
        for (int i = 0; i < CELLS; i++) {
            if (onePass[i]) passCount++; else { solidCount++; hardness += 8; }
            if (oneStand[i]) standCount++;
        }
        FragmentBuilder.build(onePass, oneStand, G, passCount, standCount, 0, hardness, solidCount, reused);
        assertEquals(null, reused.labels(), "rebuild to 1 fragment retracts the slab");
    }

    // ===================================================================================================
    // UNIFORM fast-paths — all-solid, all-air, all-water.
    // ===================================================================================================
    @Test
    void uniformKinds() {
        // All solid: no passable cells.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) standable[i] = true; // every solid top reads standable
        RegionFragments solid = build(passable, standable);
        assertEquals(RegionFragments.KIND_SOLID, solid.kind(), "no passable cell ⇒ SOLID");
        assertEquals(0, solid.fragmentCount());

        // All air: passable everywhere, no floor.
        boolean[] airPass = new boolean[CELLS];
        boolean[] airStand = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) airPass[i] = true;
        RegionFragments air = build(airPass, airStand);
        assertEquals(RegionFragments.KIND_AIR, air.kind(), "passable, no floor, dry ⇒ AIR");

        // All water: passable everywhere, no floor, mostly water — call the builder directly with waterCount.
        RegionFragments water = new RegionFragments();
        FragmentBuilder.build(airPass, airStand, G, CELLS, 0, CELLS, 0, 0, water);
        assertEquals(RegionFragments.KIND_WATER, water.kind(), "passable, no floor, mostly water ⇒ WATER");
    }
}
