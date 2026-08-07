package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * Headless unit tests for the <b>Stage-2 clobber-guard</b> (NOTES-perf-and-persistence.md §4 — bounded region
 * RAM): the load-bearing correctness invariant that stops a coarse-only startup's first live chunk build from
 * OVERWRITING a correct, fully-explored persisted coarse value with a partial rollup, when some children are
 * absent-because-persisted-not-yet-resident rather than absent-because-unexplored.
 *
 * <p>Pure POJO — the cost/resource pyramids + fragment records are MC-free, so children and coarse cells are
 * seeded directly with no {@code Bootstrap} / {@code NavSection} (mirrors {@link PyramidMergerTest} /
 * {@code RegionPersistenceRoundTripTest}). The guard is driven through a {@link RegionShardResidency} attached
 * to the pyramid via {@code setResidency}.
 *
 * <p>The parent under test is the level-1 node {@code (0,0,0)} (a "coarse cell C"); its 8 octree children live at
 * level-0 coords {@code (i&1, (i>>2)&1, (i>>1)&1)} (bit0=X, bit1=Z, bit2=Y — see {@link RegionAddress#childRX}).
 * All eight children fall in level-0 shard {@code (0,0)} ({@link RegionAddress#shardOf}), so registering that one
 * shard persisted-non-resident makes every ABSENT child look "on disk, not resident".
 */
public final class ClobberGuardTest {

    /** Seed node {@code (level, rx, ry, rz)} as a built uniform region of {@code kind}. */
    private static void seedUniform(CostPyramid p, int level, int rx, int ry, int rz, int kind, int hardness) {
        final int row = p.rowFor(level, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(level, row);
        rf.reset(RegionAddress.LEAF_SIZE);
        rf.setKind(kind);
        rf.setAvgSolidHardness(hardness);
        rf.setPassFrac(kind == RegionFragments.KIND_SOLID ? 0 : 15);
        rf.setFragmentCount(0);
        p.setBuilt(level, row, true);
    }

    /** Level-0 coords of octree child {@code i} of parent (0,0,0): (i&1, (i>>2)&1, (i>>1)&1). */
    private static int childRx(int i) { return i & 1; }
    private static int childRy(int i) { return (i >> 2) & 1; }
    private static int childRz(int i) { return (i >> 1) & 1; }

    /** Seed level-0 child {@code i} of parent (0,0,0) as a built uniform region of {@code kind}. */
    private static void seedChild(CostPyramid p, int i, int kind) {
        seedUniform(p, 0, childRx(i), childRy(i), childRz(i), kind, 0);
    }

    /** Pack a cost row's fragment record to bytes (structural identity — mirrors the round-trip test's packRow). */
    private static byte[] packRow(CostPyramid p, int level, int row) {
        final RegionFragments rf = p.fragmentRecord(level, row);
        final int nbytes = (CostCodec.regionBitLength(rf) + 7) >> 3;
        final byte[] buf = new byte[nbytes];
        CostCodec.packRegion(rf, buf, 0);
        return buf;
    }

    // ===================================================================================================
    // COST: the guard defers a coarse recompute when an absent child is persisted-but-not-resident.
    // ===================================================================================================
    @Test
    void guardDefersOnPersistedNonResidentChild_cost() {
        // Coarse cell C = level-1 (0,0,0), seeded with a distinctive "correct-full" value as if loaded from disk.
        // Some children live-built (0..3 = AIR), the rest (4..7) ABSENT and belonging to a persisted-not-resident
        // shard. Without the guard the partial rollup (4 air + 4 absent-optimistic-open) would clobber C to MIXED.
        final CostPyramid guarded = new CostPyramid();
        for (int i = 0; i < 4; i++) seedChild(guarded, i, RegionFragments.KIND_AIR);
        seedUniform(guarded, 1, 0, 0, 0, RegionFragments.KIND_SOLID, 8); // the persisted-loaded coarse value

        final RegionShardResidency res = new RegionShardResidency();
        res.markPersisted(0, 0); // shard (0,0) is on disk, and NOT marked resident ⇒ persisted-non-resident
        guarded.setResidency(res);

        // Drive the production incremental path from a built leaf.
        PyramidMerger.mergeUpFragments(guarded, 0, 0, 0);

        final int c = guarded.rowIfPresent(1, 0, 0, 0);
        assertTrue(c != -1 && guarded.isBuilt(1, c), "C stays built");
        assertEquals(RegionFragments.KIND_SOLID, guarded.fragmentRecord(1, c).kind(),
                "C must be UNCHANGED (persisted SOLID), not clobbered to the partial rollup");
        assertEquals(8, guarded.fragmentRecord(1, c).avgSolidHardness(), "C's persisted hardness is intact");
        assertTrue(res.isReconcilePending(1, 0, 0, 0), "the deferred node is recorded reconcile-pending");

        // Control: the SAME scenario with NO residency clobbers C to a partial MIXED — proving the guard is what
        // prevented the overwrite (not some incidental early-out).
        final CostPyramid control = new CostPyramid();
        for (int i = 0; i < 4; i++) seedChild(control, i, RegionFragments.KIND_AIR);
        seedUniform(control, 1, 0, 0, 0, RegionFragments.KIND_SOLID, 8);
        PyramidMerger.mergeUpFragments(control, 0, 0, 0);
        assertEquals(RegionFragments.KIND_MIXED, control.fragmentRecord(1, control.rowIfPresent(1, 0, 0, 0)).kind(),
                "without the guard the partial child set clobbers C to MIXED");
    }

    // ===================================================================================================
    // RESOURCE: the guard defers a coarse re-tally — the seeded coarse tally is not zeroed toward the partial sum.
    // ===================================================================================================
    @Test
    void guardDefersOnPersistedNonResidentChild_resource() {
        final int COL = 5;
        final ResourcePyramid guarded = new ResourcePyramid();
        for (int i = 0; i < 4; i++) { // children 0..3 built with a small tally in column COL
            final int r = guarded.rowFor(0, childRx(i), childRy(i), childRz(i));
            guarded.setLog2(0, r, COL, (byte) 2);
            guarded.setBuilt(0, r, true);
        }
        final int c = guarded.rowFor(1, 0, 0, 0); // coarse cell C, seeded with a distinctive persisted value
        guarded.setLog2(1, c, COL, (byte) 9);
        guarded.setBuilt(1, c, true);

        final RegionShardResidency res = new RegionShardResidency();
        res.markPersisted(0, 0);
        guarded.setResidency(res);

        ResourceMerger.mergeUpTallies(guarded, 0, 0, 0);

        assertEquals((byte) 9, guarded.getLog2(1, c, COL),
                "C's persisted tally must be UNCHANGED (not summed down toward the partial child set)");
        assertTrue(res.isReconcilePending(1, 0, 0, 0), "the deferred resource node is recorded reconcile-pending");

        // Control: NO residency ⇒ C is recomputed from the partial (4-of-8) child set ⇒ its value changes.
        final ResourcePyramid control = new ResourcePyramid();
        for (int i = 0; i < 4; i++) {
            final int r = control.rowFor(0, childRx(i), childRy(i), childRz(i));
            control.setLog2(0, r, COL, (byte) 2);
            control.setBuilt(0, r, true);
        }
        final int cc = control.rowFor(1, 0, 0, 0);
        control.setLog2(1, cc, COL, (byte) 9);
        control.setBuilt(1, cc, true);
        ResourceMerger.mergeUpTallies(control, 0, 0, 0);
        assertNotEquals((byte) 9, control.getLog2(1, cc, COL),
                "without the guard the partial child set overwrites C's tally");
    }

    // ===================================================================================================
    // NO-OP INVARIANT: an EMPTY residency (default) produces byte-identical merge results to the un-guarded path.
    // Exercises the absent-child branch (children 4..7 uninterned) so isPersistedNonResident is actually consulted
    // and must return false — the whole point of the byte-identical guarantee under eager-load-all.
    // ===================================================================================================
    @Test
    void guardNoOpWhenAllResidentOrUnexplored() {
        // ---- cost ----
        final CostPyramid noGuard = new CostPyramid();   // residency == null (the un-guarded reference path)
        final CostPyramid empty = new CostPyramid();     // an EMPTY, non-null residency attached
        for (int i = 0; i < 4; i++) { seedChild(noGuard, i, RegionFragments.KIND_AIR); seedChild(empty, i, RegionFragments.KIND_AIR); }
        final RegionShardResidency emptyRes = new RegionShardResidency(); // no persisted shards
        empty.setResidency(emptyRes);

        PyramidMerger.mergeUpFragments(noGuard, 0, 0, 0);
        PyramidMerger.mergeUpFragments(empty, 0, 0, 0);

        // Every built coarse row must match byte-for-byte (levels 1..MAX_COARSE_LEVEL).
        for (int level = 1; level <= RegionAddress.MAX_COARSE_LEVEL; level++) {
            assertEquals(builtRows(noGuard, level), builtRows(empty, level), "cost L" + level + " built-row count");
            final int rows = noGuard.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!noGuard.isBuilt(level, r) || noGuard.fragmentRecord(level, r) == null) continue;
                final int rx = noGuard.rowRX(level, r), ry = noGuard.rowRY(level, r), rz = noGuard.rowRZ(level, r);
                final int er = empty.rowIfPresent(level, rx, ry, rz);
                assertTrue(er != -1 && empty.isBuilt(level, er), "cost L" + level + " row present under empty residency");
                assertArrayEquals(packRow(noGuard, level, r), packRow(empty, level, er),
                        "cost L" + level + " (" + rx + "," + ry + "," + rz + ") bytes identical with empty residency");
            }
        }
        assertFalse(emptyRes.hasPending(), "an empty residency records nothing reconcile-pending");

        // ---- resource ----
        final int COL = 5;
        final ResourcePyramid rNoGuard = new ResourcePyramid();
        final ResourcePyramid rEmpty = new ResourcePyramid();
        for (int i = 0; i < 4; i++) {
            int a = rNoGuard.rowFor(0, childRx(i), childRy(i), childRz(i));
            rNoGuard.setLog2(0, a, COL, (byte) 2); rNoGuard.setBuilt(0, a, true);
            int b = rEmpty.rowFor(0, childRx(i), childRy(i), childRz(i));
            rEmpty.setLog2(0, b, COL, (byte) 2); rEmpty.setBuilt(0, b, true);
        }
        final RegionShardResidency rEmptyRes = new RegionShardResidency();
        rEmpty.setResidency(rEmptyRes);

        ResourceMerger.mergeUpTallies(rNoGuard, 0, 0, 0);
        ResourceMerger.mergeUpTallies(rEmpty, 0, 0, 0);

        for (int level = 1; level <= ResourcePyramid.RESOURCE_TOP_LEVEL; level++) {
            final int rows = rNoGuard.rowCount(level);
            assertEquals(rows, rEmpty.rowCount(level), "res L" + level + " row count");
            for (int r = 0; r < rows; r++) {
                if (!rNoGuard.isBuilt(level, r)) continue;
                final int rx = rNoGuard.rowRX(level, r), ry = rNoGuard.rowRY(level, r), rz = rNoGuard.rowRZ(level, r);
                final int er = rEmpty.rowIfPresent(level, rx, ry, rz);
                assertTrue(er != -1 && rEmpty.isBuilt(level, er), "res L" + level + " row present under empty residency");
                assertEquals(rNoGuard.getLog2(level, r, COL), rEmpty.getLog2(level, er, COL),
                        "res L" + level + " (" + rx + "," + ry + "," + rz + ") col identical with empty residency");
            }
        }
        assertFalse(rEmptyRes.hasPending(), "an empty resource residency records nothing reconcile-pending");
    }

    private static int builtRows(CostPyramid p, int level) {
        int n = 0;
        final int rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(level, r) && p.fragmentRecord(level, r) != null) n++;
        }
        return n;
    }
}
