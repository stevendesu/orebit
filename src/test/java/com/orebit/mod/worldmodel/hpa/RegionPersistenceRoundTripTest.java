package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;
import com.orebit.mod.worldmodel.persistence.ResourcePyramidCodec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * Headless round-trip tests for the world-model persistence file codecs
 * ({@link CostPyramidCodec} / {@link ResourcePyramidCodec}, DESIGN-worldmodel-persistence.md §7). These need
 * <b>no Minecraft server</b>: a pyramid is hand-built, encoded to a byte[], decoded into a fresh pyramid, and
 * every level-0 row's fragments/columns must survive the trip. The per-record fragment bitstream itself is
 * already covered by {@code CostCodecTest}; this exercises the FILE wrapper — the header, multi-row framing,
 * gzip body, coord round-trip, live-world-wins guard, and resource sparsity.
 *
 * <p>Lives in the {@code hpa} package to reach {@link RegionFragments}'s package-private setters + the
 * {@link FragmentBuilder} (as {@code CostCodecTest} does) when seeding cost leaves; the {@link ResourcePyramid}
 * uses its public API.
 */
public class RegionPersistenceRoundTripTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    // ---- cost-pyramid seeding helpers (mirror CostCodecTest) ------------------------------------------

    private static void seedUniform(CostPyramid p, int rx, int ry, int rz, int kind, int hardness) {
        int row = p.rowFor(0, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(G);
        rf.setKind(kind);
        rf.setAvgSolidHardness(hardness);
        rf.setFragmentCount(0);
        p.setBuilt(0, row, true);
    }

    /** Seed a MIXED leaf with real fragments flooded from a corridor of standable+passable cells. */
    private static void seedMixed(CostPyramid p, int rx, int ry, int rz, int[] xs) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        int passCount = 0, standCount = 0, solidCount, ignore = 0;
        long hardnessSumSolid;
        for (int x : xs) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                passable[idx(x, 1, z)] = true;
                passable[idx(x, 2, z)] = true;
            }
        }
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            if (standable[i]) standCount++;
        }
        solidCount = CELLS - passCount;
        hardnessSumSolid = (long) solidCount * 8;

        int row = p.rowFor(0, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, G, passCount, standCount, ignore, hardnessSumSolid, solidCount, rf);
        p.setBuilt(0, row, true);
    }

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

    private static CostPyramid roundTripCost(CostPyramid src) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encode(src, bos);
        CostPyramid back = new CostPyramid();
        CostPyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), back);
        return back;
    }

    // ===================================================================================================
    // Cost pyramid: several level-0 leaves of every kind survive the file round-trip.
    // ===================================================================================================
    @Test
    void costPyramid_levelZeroLeaves_roundTrip() throws IOException {
        CostPyramid src = new CostPyramid();
        // A mix of coords (incl. negatives) so the int rx/rz + byte ry framing is exercised.
        seedUniform(src, 0, 0, 0, RegionFragments.KIND_SOLID, 6);
        seedUniform(src, -3, 5, 7, RegionFragments.KIND_AIR, 0);
        seedUniform(src, 1000000, 31, -2000000, RegionFragments.KIND_WATER, 0);
        seedMixed(src, 4, 2, -9, new int[] { 4, 12 });   // two disjoint corridors → 2 fragments
        seedMixed(src, -1, 0, 1, new int[] { 8 });       // one corridor → 1 fragment
        // An interned-but-unbuilt row must NOT be persisted.
        src.rowFor(0, 50, 0, 50);

        CostPyramid back = roundTripCost(src);

        int[][] coords = { {0, 0, 0}, {-3, 5, 7}, {1000000, 31, -2000000}, {4, 2, -9}, {-1, 0, 1} };
        for (int[] c : coords) {
            int rowSrc = src.rowIfPresent(0, c[0], c[1], c[2]);
            int rowBack = back.rowIfPresent(0, c[0], c[1], c[2]);
            assertTrue(rowBack != -1, "leaf (" + c[0] + "," + c[1] + "," + c[2] + ") must survive");
            assertTrue(back.isBuilt(0, rowBack), "reloaded leaf must be built");
            assertSchemaEqual(src.fragmentRecord(0, rowSrc), back.fragmentRecord(0, rowBack));
        }
        assertEquals(-1, back.rowIfPresent(0, 50, 0, 50), "an unbuilt leaf is not persisted");
    }

    // ===================================================================================================
    // Live world wins (§6): decode must NOT clobber a leaf already built this session.
    // ===================================================================================================
    @Test
    void costPyramid_decodeDoesNotClobberLiveLeaf() throws IOException {
        CostPyramid src = new CostPyramid();
        seedUniform(src, 2, 3, 4, RegionFragments.KIND_SOLID, 6);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encode(src, bos);

        // A fresh session where the same leaf is already live as AIR — the live value must survive the decode.
        CostPyramid live = new CostPyramid();
        seedUniform(live, 2, 3, 4, RegionFragments.KIND_AIR, 0);
        CostPyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), live);

        int row = live.rowIfPresent(0, 2, 3, 4);
        assertEquals(RegionFragments.KIND_AIR, live.fragmentRecord(0, row).kind(),
                "live-built leaf must win over the persisted one");
    }

    // ===================================================================================================
    // Resource pyramid: sparse level-0 tallies survive the file round-trip.
    // ===================================================================================================
    @Test
    void resourcePyramid_levelZeroTallies_roundTrip() throws IOException {
        ResourcePyramid src = new ResourcePyramid();
        int cols = ResourceClasses.COLUMN_COUNT;

        // Row A: a couple of non-zero columns (a typical ore-bearing section).
        int a = src.rowFor(0, 7, 1, -3);
        src.setLog2(0, a, 1, (byte) 5);
        src.setLog2(0, a, 7, (byte) 2);
        src.setBuilt(0, a, true);
        // Row B: a single non-zero column near the end of the range.
        int b = src.rowFor(0, -100, 30, 200);
        src.setLog2(0, b, cols - 1, (byte) 9);
        src.setBuilt(0, b, true);
        // Row C: all-zero but built (a built-empty tally still round-trips).
        int c = src.rowFor(0, 0, 0, 0);
        src.setBuilt(0, c, true);
        // An interned-but-unbuilt row must NOT be persisted.
        src.rowFor(0, 9, 9, 9);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encode(src, bos);
        ResourcePyramid back = new ResourcePyramid();
        ResourcePyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), back);

        int[][] coords = { {7, 1, -3}, {-100, 30, 200}, {0, 0, 0} };
        for (int[] cc : coords) {
            int rowSrc = src.rowIfPresent(0, cc[0], cc[1], cc[2]);
            int rowBack = back.rowIfPresent(0, cc[0], cc[1], cc[2]);
            assertTrue(rowBack != -1, "tally (" + cc[0] + "," + cc[1] + "," + cc[2] + ") must survive");
            assertTrue(back.isBuilt(0, rowBack), "reloaded tally must be built");
            for (int col = 0; col < cols; col++) {
                assertEquals(src.getLog2(0, rowSrc, col), back.getLog2(0, rowBack, col),
                        "column " + col + " of tally " + cc[0] + "," + cc[1] + "," + cc[2]);
            }
        }
        assertEquals(-1, back.rowIfPresent(0, 9, 9, 9), "an unbuilt tally is not persisted");
    }

    @Test
    void resourcePyramid_decodeDoesNotClobberLiveTally() throws IOException {
        ResourcePyramid src = new ResourcePyramid();
        int r = src.rowFor(0, 5, 5, 5);
        src.setLog2(0, r, 3, (byte) 8);
        src.setBuilt(0, r, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encode(src, bos);

        ResourcePyramid live = new ResourcePyramid();
        int lr = live.rowFor(0, 5, 5, 5);
        live.setLog2(0, lr, 3, (byte) 1); // a different, live value
        live.setBuilt(0, lr, true);
        ResourcePyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), live);

        assertEquals((byte) 1, live.getLog2(0, live.rowIfPresent(0, 5, 5, 5), 3),
                "live-built tally must win over the persisted one");
    }

    // ===================================================================================================
    // Corruption tolerance: a bad magic is rejected (the caller then treats the file as absent).
    // ===================================================================================================
    @Test
    void badMagic_isRejected() {
        byte[] garbage = { 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77 };
        CostPyramid dest = new CostPyramid();
        boolean threw = false;
        try {
            CostPyramidCodec.decode(new ByteArrayInputStream(garbage), dest);
        } catch (IOException expected) {
            threw = true;
        }
        assertTrue(threw, "a bad-magic cost file must throw (treated as absent by the loader)");
        assertFalse(dest.rowIfPresent(0, 0, 0, 0) != -1 && dest.isBuilt(0, 0),
                "nothing is interned from a rejected file");
    }
}
