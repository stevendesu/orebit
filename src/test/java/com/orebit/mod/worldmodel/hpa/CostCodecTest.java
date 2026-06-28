package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Round-trip identity tests for the fragment-model on-disk codec {@link CostCodec#packRegion} /
 * {@link CostCodec#unpackRegion} (HPA-FRAGMENTS.md §5, slice S2). These need <b>no Minecraft</b>: records are
 * synthesized via {@link FragmentBuilder} from raw masks (as in {@code FragmentBuilderTest}) or built directly
 * through the package-private setters, then packed into a bitstream and unpacked into a fresh record; every
 * field the schema persists must survive the trip unchanged.
 *
 * <p>The schema persists: {@code kind}, {@code avgSolidHardness}, {@code passFrac} (MIXED only),
 * {@code fragmentCount}, and per fragment {@code faceMask} + the packed footprint of each touched face. It does
 * NOT persist {@code gridSize} (a build/level attribute — unpack restores it from the caller's {@code G}) nor
 * the build-time collapsed-vs-stripped distinction (a {@code count==0} MIXED record reloads as collapsed).
 */
public class CostCodecTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** Build a record from masks, computing the tallies the builder needs (stone-hardness solids). */
    private static RegionFragments build(boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += 8; }
            if (standable[i]) standCount++;
        }
        RegionFragments out = new RegionFragments();
        FragmentBuilder.build(passable, standable, G,
                passCount, standCount, 0, hardnessSumSolid, solidCount, out);
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

        RegionFragments back = roundTrip(air);
        assertSchemaEqual(air, back);
        assertEquals(RegionFragments.KIND_AIR, back.kind());
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
    // MIXED collapsed (count == 0): reloads as collapsed (the schema's uniform-mass marker).
    // ===================================================================================================
    @Test
    void mixedCollapsed_roundTrips() {
        RegionFragments rf = new RegionFragments();
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setAvgSolidHardness(4);
        rf.setPassFrac(9);
        rf.setFragmentCount(0);
        rf.setCollapsed(true);

        RegionFragments back = roundTrip(rf);
        assertSchemaEqual(rf, back);
        assertEquals(9, back.passFrac(), "collapsed mass keeps passFrac (the crossing cost)");
        assertTrue(back.isCollapsed(), "a count==0 MIXED record reloads as collapsed");
    }
}
