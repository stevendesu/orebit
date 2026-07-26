package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the <b>pure connectivity core</b> {@link FragmentBuilder} (HPA-FRAGMENTS.md §3, slice S1;
 * DESIGN-typed-fragments.md §5, §7) — flood fill + keep-all typed-fragment annotation + cap + footprint
 * extraction. These need <b>no Minecraft</b>: the builder takes raw {@code boolean} masks + tallies, so
 * fixtures are synthesized directly (no {@code Bootstrap}, no
 * {@link com.orebit.mod.worldmodel.pathing.NavSection NavSection}).
 *
 * <p>Grids are 16³ in the canonical section-local index {@code i = (y<<8)|(z<<4)|x} (the {@code G == 16}
 * form of {@link FragmentBuilder}'s power-of-two index), matching {@code ConnectivityBenchmark}.
 *
 * <p>Coverage (the S1 acceptance set + the §7 typed-fragments data-layer set):
 * <ul>
 *   <li>OPEN (floor + air above) → 1 fragment (typed S·¬W);</li>
 *   <li>two disjoint tunnels → 2 fragments;</li>
 *   <li>checkerboard → COLLAPSED (keep-all: every 6-isolated singleton is a component, 2048 &gt; the cap —
 *       the old occupiability strip is gone; the cap is the abstraction policy, §8 ruling);</li>
 *   <li>&gt;{@value RegionFragments#MAX_FRAGMENTS} isolated pockets → COLLAPSED (with the exact
 *       62-kept / 63rd-collapses cap boundary);</li>
 *   <li>a single tunnel exiting one face → known footprint bbox;</li>
 *   <li>keep-all: a wall-bisected floorless air leaf → 2 kept ¬S·¬W fragments with disjoint footprints
 *       (previously a blind uniform-AIR record);</li>
 *   <li>the S/W truth table; the water-mask (not tally) drives per-fragment W; the floorless uniform record's
 *       majority-vote kind + hasWater flag.</li>
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

    /** Dry scenario: two masks + the tallies the builder needs, computed from the masks themselves. */
    private static RegionFragments build(boolean[] passable, boolean[] standable) {
        return build(passable, standable, null);
    }

    /** A scenario's masks + the tallies the builder needs, computed from the masks themselves. */
    private static RegionFragments build(boolean[] passable, boolean[] standable, boolean[] water) {
        int passCount = 0, standCount = 0, waterCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) {
                passCount++;
                if (water != null && water[i]) waterCount++;
            } else { solidCount++; hardnessSumSolid += 8; } // pretend all solid is stone (h≈8)
            if (standable[i]) standCount++;
        }
        RegionFragments out = new RegionFragments();
        FragmentBuilder.build(passable, standable, water, G,
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
        assertEquals(1, rf.fragmentCount(), "open floor+air = exactly one fragment");
        assertTrue(rf.typeS(0), "dry floor + air above ⇒ S (surfaceable)");
        assertFalse(rf.typeW(0), "no water ⇒ ¬W");

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
    // CHECKERBOARD — (x+y+z)%2 passable, ~2048 6-isolated singletons. FIXTURE SHIFT (keep-all,
    // DESIGN-typed-fragments.md §5.2): the old occupiability filter stripped every singleton → 0 fragments,
    // not collapsed. Under keep-all every singleton IS a component, so 2048 components blow the 62 cap and
    // the region COLLAPSES — the cap (ratified as the de-facto abstraction policy, §8) now absorbs the
    // noise the filter used to. Same downstream shape either way: no stored fragments, passFrac-crossed mass.
    // ===================================================================================================
    @Test
    void checkerboard_collapsesUnderKeepAll() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int y = 0; y < G; y++)
                for (int z = 0; z < G; z++) {
                    boolean pass = ((x + y + z) & 1) == 0 && y < G - 1;
                    passable[idx(x, y, z)] = pass;
                    standable[idx(x, y, z)] = !pass; // the solid (off-parity) cells are walkable tops
                }

        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind(), "has air + floor cells ⇒ MIXED (flood, not fast-path)");
        assertTrue(rf.isCollapsed(), "keep-all: 2048 singleton components exceed the "
                + RegionFragments.MAX_FRAGMENTS + " cap → collapsed");
        assertEquals(0, rf.fragmentCount(), "a collapsed region stores no fragment records");
    }

    // ===================================================================================================
    // OVER-CAP — 64 isolated 2-tall occupiable pockets (> the 62 cap) → COLLAPSED.
    // (Cap moved 63 → 62 for the persisted count-field sentinel: 63 on the wire = FRAGMENT_COUNT_COLLAPSED.)
    // ===================================================================================================

    /** Carve {@code n} isolated 2-tall occupiable pockets from the 8×8 odd-(x,z) grid (row-major order). */
    private static int carvePockets(boolean[] passable, boolean[] standable, int n) {
        int columns = 0;
        for (int x = 1; x < G && columns < n; x += 2) {
            for (int z = 1; z < G && columns < n; z += 2) {
                standable[idx(x, 0, z)] = true;     // floor
                passable[idx(x, 1, z)] = true;      // feet
                passable[idx(x, 2, z)] = true;      // head (≥2-tall headroom)
                columns++;
            }
        }
        return columns;
    }

    @Test
    void overCap_collapses() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        // 8×8 = 64 columns at odd (x,z), separated by solid even rows so none connect.
        int columns = carvePockets(passable, standable, 64);
        assertEquals(64, columns, "fixture should build 64 isolated pockets (> the 62 cap)");

        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertTrue(rf.isCollapsed(), "64 occupiable components exceeds the "
                + RegionFragments.MAX_FRAGMENTS + " cap → collapsed");
        assertEquals(0, rf.fragmentCount(), "a collapsed region stores no fragment records");
    }

    @Test
    void capBoundary_exactly62Kept_notCollapsed() {
        // EXACTLY at the cap: 62 isolated pockets all survive as real fragments — no collapse. Guards the
        // 63 → 62 threshold move (a build must keep components while kept < MAX_FRAGMENTS).
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        assertEquals(RegionFragments.MAX_FRAGMENTS,
                carvePockets(passable, standable, RegionFragments.MAX_FRAGMENTS), "fixture carves 62 pockets");

        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertFalse(rf.isCollapsed(), "62 components is AT the cap, not over it — no collapse");
        assertEquals(RegionFragments.MAX_FRAGMENTS, rf.fragmentCount(), "all 62 components kept exactly");
    }

    @Test
    void capBoundary_63rdComponent_collapses() {
        // ONE past the cap: the 63rd occupiable component trips the collapse (the mechanism is unchanged from
        // the old 63-cap; only the threshold moved so the persisted count field can spend 63 on the sentinel).
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        assertEquals(RegionFragments.MAX_FRAGMENTS + 1,
                carvePockets(passable, standable, RegionFragments.MAX_FRAGMENTS + 1), "fixture carves 63 pockets");

        RegionFragments rf = build(passable, standable);

        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertTrue(rf.isCollapsed(), "the 63rd occupiable component exceeds the 62 cap → collapsed");
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
        assertEquals(0, FragmentBuilder.fragmentContaining(passable, standable, null, G, idx(4, 1, 5)),
                "a cell in tunnel A is fragment 0");
        assertEquals(1, FragmentBuilder.fragmentContaining(passable, standable, null, G, idx(12, 2, 9)),
                "a cell in tunnel B is fragment 1");
        // A solid cell between the tunnels is in no fragment.
        assertEquals(-1, FragmentBuilder.fragmentContaining(passable, standable, null, G, idx(8, 1, 5)),
                "a non-passable (solid) cell has no fragment");
    }

    @Test
    void fragmentContaining_collapsedAndUniform_returnMinusOne() {
        // Checkerboard: 2048 singleton components collapse the region (keep-all) ⇒ no stored fragments ⇒ -1.
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
        assertEquals(-1, FragmentBuilder.fragmentContaining(cbPass, cbStand, null, G, cbSeed),
                "a collapsed (over-cap) region yields no fragment id");

        // TRULY-uniform box (floorless, no solid, all-dry): build() emits a UNIFORM record (no fragments)
        // ⇒ -1 to match (§5.5 amended).
        boolean[] unifPass = new boolean[CELLS];
        java.util.Arrays.fill(unifPass, true);
        assertEquals(-1, FragmentBuilder.fragmentContaining(unifPass, new boolean[CELLS], null, G, idx(3, 3, 3)),
                "a truly-uniform (uniform-record) region yields no fragment id");

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
        assertEquals(-1, FragmentBuilder.fragmentContaining(ocPass, ocStand, null, G, idx(1, 1, 1)),
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

        // Checkerboard: 2048 singleton components collapse the region (keep-all) ⇒ all -1.
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
        FragmentBuilder.labelAll(passable, standable, null, G, slab);
        for (int i = 0; i < CELLS; i++) {
            assertEquals(FragmentBuilder.fragmentContaining(passable, standable, null, G, i), slab[i],
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
            assertEquals(FragmentBuilder.fragmentContaining(passable, standable, null, G, i), labels[i],
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
        FragmentBuilder.build(twoPass, twoStand, null, G, passCount, standCount, 0, hardness, solidCount, reused);
        assertTrue(reused.labels() != null, "two tunnels ⇒ published");
        passCount = standCount = solidCount = 0; hardness = 0;
        boolean[] onePass = new boolean[CELLS];
        boolean[] oneStand = new boolean[CELLS];
        carveTunnel(onePass, oneStand, 4);
        for (int i = 0; i < CELLS; i++) {
            if (onePass[i]) passCount++; else { solidCount++; hardness += 8; }
            if (oneStand[i]) standCount++;
        }
        FragmentBuilder.build(onePass, oneStand, null, G, passCount, standCount, 0, hardness, solidCount, reused);
        assertEquals(null, reused.labels(), "rebuild to 1 fragment retracts the slab");
    }

    // ===================================================================================================
    // UNIFORM fast-paths (§5.5 AMENDED: truly-uniform ONLY) — all-solid, all-air, all-water. Kind is exact
    // by construction: AIR implies dry, WATER implies all-water; no vote, no extra water flag.
    // ===================================================================================================
    @Test
    void uniformKinds_trulyUniformOnly() {
        // All solid: no passable cells.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) standable[i] = true; // every solid top reads standable
        RegionFragments solid = build(passable, standable);
        assertEquals(RegionFragments.KIND_SOLID, solid.kind(), "no passable cell ⇒ SOLID");
        assertEquals(0, solid.fragmentCount());

        // All air: passable everywhere, no floor, ZERO water.
        boolean[] airPass = new boolean[CELLS];
        boolean[] airStand = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) airPass[i] = true;
        RegionFragments air = build(airPass, airStand);
        assertEquals(RegionFragments.KIND_AIR, air.kind(), "floorless, no solid, provably dry ⇒ AIR");
        assertEquals(0, air.fragmentCount(), "a uniform record stores no fragments");

        // All water: passable everywhere, no floor, EVERY cell water ⇒ uniform WATER (no fragments).
        // ¬S is genuinely correct here — a fully-submerged cube's surface lives in the leaf above.
        boolean[] allWater = new boolean[CELLS];
        java.util.Arrays.fill(allWater, true);
        RegionFragments water = build(airPass, airStand, allWater);
        assertEquals(RegionFragments.KIND_WATER, water.kind(), "floorless, no solid, all water ⇒ WATER");
        assertEquals(0, water.fragmentCount(), "an all-water cube stays a uniform record");
    }

    // ===================================================================================================
    // The AMENDED uniform boundary (§5.5): a floorless leaf that is NOT truly uniform — mixed media, or any
    // solid content — takes the FRAGMENT path and gets exact per-fragment types. The majority vote and the
    // v5 any-water⇒WATER rule are both gone from the codebase.
    // ===================================================================================================
    @Test
    void oceanSurfaceLeaf_oneFragment_surfaceableWater() {
        // Water y 0..7, air y 8..15, no solid anywhere: floorless mixed-media ⇒ MIXED with ONE fragment.
        // The water cells at y=7 have air-only headroom above ⇒ {S=1, W=1} — the open-ocean-surface row of
        // the §2 truth table (tread and breathe, no land needed). Previously this leaf was a blind uniform.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        boolean[] water = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int x = 0; x < G; x++)
            for (int z = 0; z < G; z++)
                for (int y = 0; y <= 7; y++) water[idx(x, y, z)] = true;

        RegionFragments rf = build(passable, standable, water);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind(), "mixed-media floorless leaf is NOT uniform (amended §5.5)");
        assertEquals(1, rf.fragmentCount(), "water+air are 6-connected ⇒ one fragment");
        assertTrue(rf.typeW(0), "contains water ⇒ W");
        assertTrue(rf.typeS(0), "water cell with air-only headroom (the ocean surface) ⇒ S");
    }

    @Test
    void pillarInAirLeaf_fragmentPath_notSurfaceable() {
        // A non-standable solid pillar (glass-like) rising through open air: floorless but WITH solid
        // content ⇒ the fragment path (previously: standCount==0 ⇒ blind uniform-AIR). The air wraps the
        // pillar into ONE component; nothing has footing (the pillar isn't standable, there's no water)
        // ⇒ ¬S·¬W.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int y = 0; y < G; y++) passable[idx(8, y, 8)] = false; // the full-height pillar

        RegionFragments rf = build(passable, standable);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind(), "floorless-with-solid takes the fragment path");
        assertEquals(1, rf.fragmentCount(), "air wraps the pillar into one component");
        assertFalse(rf.typeS(0), "no footing anywhere (non-standable pillar, no water) ⇒ ¬S");
        assertFalse(rf.typeW(0), "dry ⇒ ¬W");
    }

    // ===================================================================================================
    // KEEP-ALL (§7): a wall-bisected floorless air leaf — the previously-DISCARDED case (occupiability
    // stripped both components ⇒ the leaf read as a blind uniform with both sides "connected"). Now: two
    // kept ¬S·¬W fragments with disjoint footprints.
    // ===================================================================================================
    @Test
    void wallBisectedAirLeaf_twoTypedFragmentsWithDisjointFootprints() {
        // A full-height non-standable solid wall at x=8 splits an otherwise-empty leaf into two air halves.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int y = 0; y < G; y++)
            for (int z = 0; z < G; z++) passable[idx(8, y, z)] = false;

        RegionFragments rf = build(passable, standable);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind(), "bisected floorless leaf is MIXED, not uniform");
        assertFalse(rf.isCollapsed());
        assertEquals(2, rf.fragmentCount(), "keep-all: both air components are kept fragments");
        for (int f = 0; f < 2; f++) {
            assertFalse(rf.typeS(f), "pure air (no footing) ⇒ ¬S for fragment " + f);
            assertFalse(rf.typeW(f), "dry ⇒ ¬W for fragment " + f);
        }
        // Disjoint side-face footprints: fragment 0 (x 0..7) touches -X but not +X; fragment 1 (x 9..15)
        // touches +X but not -X — the structure the old uniform-AIR record was blind to.
        assertTrue(rf.touchesFace(0, FX_NEG));
        assertFalse(rf.touchesFace(0, FX_POS), "the wall seals fragment 0 off the +X face");
        assertTrue(rf.touchesFace(1, FX_POS));
        assertFalse(rf.touchesFace(1, FX_NEG), "the wall seals fragment 1 off the -X face");
        // On a shared face (e.g. -Z) their X spans must not overlap (u=X for ±Z faces): [0,7] vs [9,15].
        int fp0 = rf.footprint(0, FZ_NEG);
        int fp1 = rf.footprint(1, FZ_NEG);
        assertEquals(0, RegionFragments.footprintMinU(fp0));
        assertEquals(7, RegionFragments.footprintMaxU(fp0));
        assertEquals(9, RegionFragments.footprintMinU(fp1));
        assertEquals(15, RegionFragments.footprintMaxU(fp1));
    }

    // ===================================================================================================
    // The S truth table (§2, §7): footing = (standable floor below OR water at the cell); headroom must be
    // AIR-only (passable and NOT water; grid-top optimistic). Dry floor+air = S (covered by
    // open_oneFragment); water+air above = S (covered by oceanSurfaceLeaf); the sealed rows follow.
    // ===================================================================================================
    @Test
    void submergedFloorAndSealedPocket_notSurfaceable() {
        // Standable floor at y=0, water filling y 1..8, solid (standable) ceiling y 9..15: a sealed
        // underwater pocket. Footing exists everywhere (floor below y=1, water in-cell above), but no cell
        // has AIR-only headroom — the old occupiability headroom used passable[] (which includes water), so
        // a submerged floor wrongly counted; the typed S must not. ⇒ ¬S·W.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        boolean[] water = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;                    // floor (solid, not passable)
                for (int y = 1; y <= 8; y++) {
                    passable[idx(x, y, z)] = true;
                    water[idx(x, y, z)] = true;
                }
                for (int y = 9; y < G; y++) standable[idx(x, y, z)] = true; // sealed ceiling
            }

        RegionFragments rf = build(passable, standable, water);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertEquals(1, rf.fragmentCount(), "one connected water pocket");
        assertTrue(rf.typeW(0), "water pocket ⇒ W");
        assertFalse(rf.typeS(0), "submerged floor / sealed pocket: no air-only headroom anywhere ⇒ ¬S");
    }

    @Test
    void canopyAir_oneTallGapOverFloor_notSurfaceable() {
        // A 1-tall air slab at y=8 between solid standable layers (the jungle-canopy shape): every air cell
        // has footing (standable below) but its headroom cell is solid ⇒ ¬S·¬W.
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++)
            for (int z = 0; z < G; z++)
                for (int y = 0; y < G; y++) {
                    if (y == 8) passable[idx(x, y, z)] = true;
                    else standable[idx(x, y, z)] = true;
                }

        RegionFragments rf = build(passable, standable);
        assertEquals(1, rf.fragmentCount(), "one connected 1-tall air slab");
        assertFalse(rf.typeS(0), "footing but no 2-tall air headroom (canopy gap) ⇒ ¬S");
        assertFalse(rf.typeW(0), "dry ⇒ ¬W");
    }

    // ===================================================================================================
    // WATER-MASK SEAM (§7): the per-fragment W bit is driven by the water MASK (cell placement), not the
    // scalar tally — the same waterCount lands on different fragments depending on where the water sits.
    // ===================================================================================================
    @Test
    void waterMask_drivesPerFragmentW_notTheTally() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnel(passable, standable, 4);   // fragment 0
        carveTunnel(passable, standable, 12);  // fragment 1

        // Water in tunnel A only.
        boolean[] waterA = new boolean[CELLS];
        waterA[idx(4, 1, 5)] = true;
        RegionFragments a = build(passable, standable, waterA);
        assertEquals(2, a.fragmentCount());
        assertTrue(a.typeW(0), "water cell in tunnel A ⇒ fragment 0 is W");
        assertFalse(a.typeW(1), "tunnel B stays dry");

        // SAME tally (one water cell), placed in tunnel B: the W bit moves with the mask.
        boolean[] waterB = new boolean[CELLS];
        waterB[idx(12, 1, 5)] = true;
        RegionFragments b = build(passable, standable, waterB);
        assertEquals(2, b.fragmentCount());
        assertFalse(b.typeW(0), "tunnel A stays dry");
        assertTrue(b.typeW(1), "water cell in tunnel B ⇒ fragment 1 is W");

        // Both tunnels remain surfaceable (dry floor + air headroom exists beside the water cell).
        assertTrue(a.typeS(0) && a.typeS(1) && b.typeS(0) && b.typeS(1),
                "floored 2-tall tunnels are surfaceable regardless of the water speck");
    }
}
