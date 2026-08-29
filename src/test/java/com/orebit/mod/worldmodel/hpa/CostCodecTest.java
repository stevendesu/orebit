package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Round-trip identity tests for the fragment-model on-disk codec {@link CostCodec#packRegion} /
 * {@link CostCodec#unpackRegion} (HPA-FRAGMENTS.md §5, slice S2). These need <b>no Minecraft</b>: records are
 * synthesized via {@link FragmentBuilder} from raw masks (as in {@code FragmentBuilderTest}) or built directly
 * through the package-private setters, then packed into a bitstream and unpacked into a fresh record; every
 * field the schema persists must survive the trip unchanged.
 *
 * <p>The schema persists (v7, typed fragments): {@code kind}, {@code avgSolidHardness}, {@code passFrac}
 * (MIXED only), {@code fragmentCount}, per fragment {@code faceMask} + the 2 type bits (S, W — after the
 * faceMask, before the footprints) + the packed footprint of each touched face, and the
 * collapsed flag (via the {@link RegionFragments#FRAGMENT_COUNT_COLLAPSED} count-field sentinel: on-wire
 * count 0 = honestly-zero/stripped ({@code collapsed=false}), 1..62 exact, 63 = cap-collapsed
 * ({@code collapsed=true})). Uniform records stay 6 bits — under the amended truly-uniform fast path the
 * kind alone is exact (AIR implies dry, WATER implies all-water), so there is no extra water flag. It does
 * NOT persist {@code gridSize} (a build/level attribute — unpack restores it from the caller's {@code G}).
 */
public class CostCodecTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** Build a record from masks, computing the tallies the builder needs (stone-hardness solids). */
    private static RegionFragments build(boolean[] passable, boolean[] standable) {
        return build(passable, standable, null);
    }

    /** Build a record from masks incl. a water mask, computing the tallies the builder needs. */
    private static RegionFragments build(boolean[] passable, boolean[] standable, boolean[] water) {
        int passCount = 0, standCount = 0, waterCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) {
                passCount++;
                if (water != null && water[i]) waterCount++;
            } else { solidCount++; hardnessSumSolid += 8; }
            if (standable[i]) standCount++;
        }
        RegionFragments out = new RegionFragments();
        FragmentBuilder.build(passable, standable, water, G,
                passCount, standCount, waterCount, hardnessSumSolid, solidCount, out);
        return out;
    }

    /** Pack {@code rf} then unpack into a fresh record; assert the bit length matches the cursor advance. */
    private static RegionFragments roundTrip(RegionFragments rf) {
        int bits = CostCodec.regionBitLength(rf);
        byte[] buf = new byte[(bits >> 3) + 2];
        int endPack = CostCodec.packRegion(rf, buf, 0);
        assertEquals(bits, endPack, "packRegion must write exactly regionBitLength bits");

        RegionFragments back = new RegionFragments();
        int endUnpack = CostCodec.unpackRegion(buf, 0, G, back);
        assertEquals(endPack, endUnpack, "unpack must consume exactly what pack wrote");
        return back;
    }

    /** Assert two records are field-identical for everything the on-disk schema persists. */
    private static void assertSchemaEqual(RegionFragments a, RegionFragments b) {
        assertEquals(a.kind(), b.kind(), "kind");
        assertEquals(a.avgSolidHardness(), b.avgSolidHardness(), "avgSolidHardness");
        assertEquals(a.fragmentCount(), b.fragmentCount(), "fragmentCount");
        if (a.kind() == RegionFragments.KIND_MIXED) {
            assertEquals(a.passFrac(), b.passFrac(), "passFrac");
            for (int f = 0; f < a.fragmentCount(); f++) {
                assertEquals(a.faceMask(f), b.faceMask(f), "faceMask[" + f + "]");
                assertEquals(a.typeBits(f), b.typeBits(f), "typeBits[" + f + "]");
                for (int face = 0; face < 6; face++) {
                    assertEquals(a.footprint(f, face), b.footprint(f, face),
                            "footprint[" + f + "][" + face + "]");
                }
            }
        }
    }

    // ===================================================================================================
    // Uniform kinds: 6 bits (kind + hardness), no passFrac / count / fragments.
    // ===================================================================================================
    @Test
    void uniformSolid_roundTrips() {
        RegionFragments solid = new RegionFragments();
        solid.reset(G);
        solid.setKind(RegionFragments.KIND_SOLID);
        solid.setAvgSolidHardness(6);
        solid.setFragmentCount(0);

        assertEquals(6, CostCodec.regionBitLength(solid), "uniform region = 6 bits");
        RegionFragments back = roundTrip(solid);
        assertSchemaEqual(solid, back);
        assertEquals(RegionFragments.KIND_SOLID, back.kind());
        assertEquals(6, back.avgSolidHardness());
    }

    @Test
    void uniformAir_roundTrips() {
        RegionFragments air = new RegionFragments();
        air.reset(G);
        air.setKind(RegionFragments.KIND_AIR);
        air.setAvgSolidHardness(0);
        air.setFragmentCount(0);

        assertEquals(6, CostCodec.regionBitLength(air), "uniform AIR stays 6 bits (no water flag — §5.5 amended)");
        RegionFragments back = roundTrip(air);
        assertSchemaEqual(air, back);
        assertEquals(RegionFragments.KIND_AIR, back.kind());
    }

    @Test
    void uniformWater_roundTrips_sixBits() {
        // A truly-uniform all-water cube: kind alone carries the water truth (WATER ⇒ all water).
        boolean[] pass = new boolean[CELLS];
        boolean[] water = new boolean[CELLS];
        java.util.Arrays.fill(pass, true);
        java.util.Arrays.fill(water, true);
        RegionFragments rf = build(pass, new boolean[CELLS], water);
        assertEquals(RegionFragments.KIND_WATER, rf.kind(), "fixture is a truly-uniform WATER cube");
        assertEquals(6, CostCodec.regionBitLength(rf), "uniform WATER is 6 bits");

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
        assertEquals(RegionFragments.KIND_WATER, back.kind());
    }

    // ===================================================================================================
    // v7: the per-fragment type bits (S, W) survive the trip — an ocean-surface leaf's {S,W} fragment and
    // a dry tunnel's {S} fragment reload bit-exact (assertSchemaEqual covers typeBits; this pins the
    // interesting values).
    // ===================================================================================================
    @Test
    void fragmentTypeBits_roundTrip() {
        // Ocean-surface leaf: water y0..7 + air above, no solid ⇒ MIXED, one {S,W} fragment.
        boolean[] pass = new boolean[CELLS];
        boolean[] water = new boolean[CELLS];
        java.util.Arrays.fill(pass, true);
        for (int x = 0; x < G; x++)
            for (int z = 0; z < G; z++)
                for (int y = 0; y <= 7; y++) water[idx(x, y, z)] = true;
        RegionFragments rf = build(pass, new boolean[CELLS], water);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertEquals(1, rf.fragmentCount());
        assertTrue(rf.typeS(0) && rf.typeW(0), "fixture fragment is {S,W}");

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
        assertTrue(back.typeS(0), "S survives the trip");
        assertTrue(back.typeW(0), "W survives the trip");
    }

    // ===================================================================================================
    // MIXED, one fragment, with a known footprint (the FragmentBuilderTest "knownOpening" fixture).
    // ===================================================================================================
    @Test
    void mixedOneFragment_footprintRoundTrips() {
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
        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertEquals(1, rf.fragmentCount());

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);

        // Spot-check the +X footprint survived: u=Y in [1,2], v=Z in [6,9].
        int packed = back.footprint(0, 1);
        assertEquals(1, RegionFragments.footprintMinU(packed));
        assertEquals(2, RegionFragments.footprintMaxU(packed));
        assertEquals(6, RegionFragments.footprintMinV(packed));
        assertEquals(9, RegionFragments.footprintMaxV(packed));
    }

    // ===================================================================================================
    // MIXED, two fragments (two disjoint tunnels) — variable-length record over several faces.
    // ===================================================================================================
    @Test
    void mixedTwoFragments_roundTrips() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x : new int[] { 4, 12 }) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                passable[idx(x, 1, z)] = true;
                passable[idx(x, 2, z)] = true;
            }
        }
        RegionFragments rf = build(passable, standable);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertEquals(2, rf.fragmentCount());

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
    }

    // ===================================================================================================
    // The count-field sentinel (v6): collapsed and honestly-stripped count-0 records are DISTINCT on the
    // wire (63 vs 0) and round-trip their collapsed flag — the old schema conflated them (count==0 reloaded
    // as collapsed), so a stripped record could not survive a save/load cycle with collapsed == false.
    // ===================================================================================================
    @Test
    void mixedCollapsed_writesSentinel_reloadsCollapsed() {
        RegionFragments rf = new RegionFragments();
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setAvgSolidHardness(4);
        rf.setPassFrac(9);
        rf.setFragmentCount(0);
        rf.setCollapsed(true);

        // The on-wire count field (bits 10..15: kind 2 + hardness 4 + passFrac 4, then count 6) must carry
        // the FRAGMENT_COUNT_COLLAPSED sentinel, not the in-RAM count 0.
        byte[] buf = new byte[4];
        CostCodec.packRegion(rf, buf, 0);
        assertEquals(RegionFragments.FRAGMENT_COUNT_COLLAPSED, CostCodec.readBits(buf, 10, 6),
                "a collapsed record writes count=63 (the sentinel) on the wire");

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
        assertEquals(9, back.passFrac(), "collapsed mass keeps passFrac (the crossing cost)");
        assertTrue(back.isCollapsed(), "the sentinel decodes to collapsed == true");
        assertEquals(0, back.fragmentCount(), "…with fragmentCount 0 in RAM (the sentinel is a flag, not a count)");
    }

    @Test
    void mixedStripped_reloadsNotCollapsed() {
        // Honestly-zero kept fragments (the checkerboard/speckle strip, or a coarse no-passable-item mass):
        // count 0 on the wire, and — the previously-impossible assertion — collapsed stays FALSE on reload.
        RegionFragments rf = new RegionFragments();
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setAvgSolidHardness(4);
        rf.setPassFrac(2);
        rf.setFragmentCount(0); // reset() left collapsed == false

        byte[] buf = new byte[4];
        CostCodec.packRegion(rf, buf, 0);
        assertEquals(0, CostCodec.readBits(buf, 10, 6), "a stripped record writes count=0 on the wire");

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
        assertEquals(0, back.fragmentCount());
        assertFalse(back.isCollapsed(), "count==0 now reloads as honestly-zero, NOT collapsed");
    }

    // ===================================================================================================
    // Cap boundary: a record with the full MAX_FRAGMENTS (61) fragments round-trips exactly (the largest
    // legal count — the corner sentinel 61 and the collapsed sentinel 63 sit above it in the 6-bit field).
    // ===================================================================================================
    @Test
    void mixedAtCap_61Fragments_roundTripsExactly() {
        // 61 isolated 2-tall pockets on the 8×8 odd-(x,z) grid (the FragmentBuilderTest cap fixture, short
        // of 64 so the build keeps every component).
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        int columns = 0;
        for (int x = 1; x < G && columns < RegionFragments.MAX_FRAGMENTS; x += 2) {
            for (int z = 1; z < G && columns < RegionFragments.MAX_FRAGMENTS; z += 2) {
                standable[idx(x, 0, z)] = true;
                passable[idx(x, 1, z)] = true;
                passable[idx(x, 2, z)] = true;
                columns++;
            }
        }
        RegionFragments rf = build(passable, standable);
        assertFalse(rf.isCollapsed(), "an at-cap component count — no collapse");
        assertEquals(RegionFragments.MAX_FRAGMENTS, rf.fragmentCount(), "fixture builds the full at-cap fragments");

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
        assertFalse(back.isCollapsed(), "an at-cap record reloads exact, never as the sentinel");
        assertEquals(RegionFragments.MAX_FRAGMENTS, back.fragmentCount());
    }

    // ===================================================================================================
    // LEGACY over-cap count (corner-crossing R21): a file written before the MAX_FRAGMENTS 62 → 61
    // reduction can carry count == 62 (a then-at-cap region). The persistence version is PINNED at 1
    // pre-release, so no version fence exists — the decoder must absorb it as cap-collapsed instead of
    // indexing past the 61-sized arrays (AIOOBE on load).
    // ===================================================================================================
    @Test
    void legacyOverCapCount_decodesAsCollapsed() {
        // Hand-write the wire prefix a pre-reduction encoder produced: kind=MIXED(2b), hardness(4b),
        // passFrac(4b), count=62(6b). The fragment records that would follow are never reached.
        byte[] buf = new byte[8];
        int p = 0;
        p = CostCodec.writeBits(buf, p, RegionFragments.KIND_MIXED, 2);
        p = CostCodec.writeBits(buf, p, 5, 4);   // hardness
        p = CostCodec.writeBits(buf, p, 9, 4);   // passFrac
        p = CostCodec.writeBits(buf, p, RegionFragments.MAX_FRAGMENTS + 1, 6); // 62 — legal pre-reduction
        RegionFragments out = new RegionFragments();
        CostCodec.unpackRegion(buf, 0, RegionAddress.LEAF_SIZE, out);
        assertEquals(0, out.fragmentCount(), "a legacy over-cap count decodes with no fragment records");
        assertTrue(out.isCollapsed(), "…as cap-collapsed (priced from passFrac), never an AIOOBE");
        assertEquals(9, out.passFrac(), "the header fields before the count still decode");
    }
}
