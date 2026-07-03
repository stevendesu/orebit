package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the sparse SoA {@link ResourcePyramid} (find-mine-resources design §3). Pure data-plane —
 * <b>no Minecraft</b>: the pyramid is hand-seeded and read back. Pins the invariants the phase-4 tally write and
 * the phase-5 drill-down depend on: stable intern, a non-creating {@link ResourcePyramid#rowIfPresent} probe,
 * the flat row-major column round-trip, and correctness across the doubling grow/rehash.
 */
public class ResourcePyramidTest {

    @Test
    void internIsStable() {
        ResourcePyramid p = new ResourcePyramid();
        int r1 = p.rowFor(0, 3, 1, 5);
        int r2 = p.rowFor(0, 3, 1, 5);
        assertEquals(r1, r2, "rowFor twice for the same coords must return the same row");
        assertEquals(1, p.rowCount(0), "the second rowFor must not create a new row");
        assertEquals(3, p.rowRX(0, r1));
        assertEquals(1, p.rowRY(0, r1));
        assertEquals(5, p.rowRZ(0, r1));
    }

    @Test
    void rowIfPresentDoesNotCreate() {
        ResourcePyramid p = new ResourcePyramid();
        assertEquals(-1, p.rowIfPresent(0, 7, 0, 2), "probe of an un-interned row must be -1");
        assertEquals(0, p.rowCount(0), "a probe must NOT intern a row");
        assertEquals(-1, p.rowIfPresent(4, 0, 0, 0), "probe of an untouched level must be -1 (no level alloc)");
        assertEquals(0, p.rowCount(4), "probing an untouched level must not allocate it");

        int r = p.rowFor(0, 7, 0, 2);
        assertEquals(r, p.rowIfPresent(0, 7, 0, 2), "after intern, the probe finds the row");
    }

    @Test
    void newRowStartsEmpty() {
        ResourcePyramid p = new ResourcePyramid();
        int r = p.rowFor(0, 0, 0, 0);
        assertFalse(p.isBuilt(0, r), "a freshly interned row is not built");
        for (int c = 0; c < ResourcePyramid.COLUMNS; c++) {
            assertEquals(0, p.getLog2(0, r, c) & 0xFF, "column " + c + " defaults to 0 (no resource)");
        }
    }

    @Test
    void getSetLog2AndBuiltRoundTrip() {
        ResourcePyramid p = new ResourcePyramid();
        int r = p.rowFor(0, 1, 2, 3);
        p.setLog2(0, r, 7, (byte) 9);
        assertEquals(9, p.getLog2(0, r, 7) & 0xFF);
        assertEquals(0, p.getLog2(0, r, 6) & 0xFF, "a neighbouring column is untouched");
        p.setBuilt(0, r, true);
        assertTrue(p.isBuilt(0, r));
    }

    @Test
    void setRowReadRowRoundTrip() {
        ResourcePyramid p = new ResourcePyramid();
        int r = p.rowFor(0, 5, 0, 5);
        byte[] vec = new byte[ResourcePyramid.COLUMNS];
        for (int c = 0; c < vec.length; c++) vec[c] = (byte) (c + 1);
        p.setRow(0, r, vec);

        byte[] out = new byte[ResourcePyramid.COLUMNS];
        p.readRow(0, r, out);
        assertArrayEquals(vec, out, "readRow must return exactly what setRow wrote");
        for (int c = 0; c < vec.length; c++) {
            assertEquals((byte) (c + 1), p.getLog2(0, r, c), "per-column agreement with the whole-vector write");
        }
    }

    @Test
    void growsPastInitialCapacity() {
        ResourcePyramid p = new ResourcePyramid();
        int n = ResourcePyramid.INITIAL_ROW_CAP * 3 + 7; // force several row-array + map doublings
        int[] rows = new int[n];
        for (int i = 0; i < n; i++) {
            rows[i] = p.rowFor(0, i, 0, 0);
            p.setLog2(0, rows[i], 0, (byte) ((i % 15) + 1));
        }
        assertEquals(n, p.rowCount(0), "all interned rows are present");
        for (int i = 0; i < n; i++) {
            int r = p.rowIfPresent(0, i, 0, 0);
            assertEquals(rows[i], r, "row index stays stable across the grow/rehash for i=" + i);
            assertEquals((byte) ((i % 15) + 1), p.getLog2(0, r, 0), "payload survives the grow for i=" + i);
            assertEquals(i, p.rowRX(0, r), "coords survive the grow for i=" + i);
        }
    }
}
