package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;
import com.orebit.mod.worldmodel.persistence.RegionReconciler;
import com.orebit.mod.worldmodel.persistence.ResourcePyramidCodec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * Headless unit tests for the <b>Stage-2 §2b reconciliation</b> pieces (DESIGN-worldmodel-persistence.md — bounded
 * region RAM): (a) {@code decode} reporting the straddle set of coarse cells it skipped by live-world-wins, and
 * (b) {@link RegionReconciler} re-deriving exactly those cells (ascending in-shard + above-shard propagation) so a
 * post-lazy-load pyramid becomes byte-identical to a fully-merged one — without a full {@code mergeUp} of the shard.
 *
 * <p>Pure POJO, no Minecraft server (mirrors {@link ClobberGuardTest} / {@code RegionPersistenceRoundTripTest}):
 * cost leaves are seeded through {@link RegionFragments}'s package-private setters (hence the {@code hpa} package);
 * the {@link ResourcePyramid} uses its public API. The straddle scenario is driven through the REAL decode-with-
 * collector path (encode a ground-truth shard, decode it into a partial pyramid with a {@link StraddleSet}).
 *
 * <p>The subtree under test: four level-0 leaves at {@code ry=0} — {@code (0,0,0),(1,0,0),(0,0,1),(1,0,1)} — the
 * four horizontal octree children of the level-1 cell {@code (0,0,0)}. All fall in level-0 shard {@code (0,0)}, so
 * one shard file carries them and their whole coarse ancestor chain {@code (0,0,0)} up levels 1..5.
 */
public final class ReconcileTest {

    private static final int G = 16;
    private static final int COST_TOP = RegionAddress.MAX_COARSE_LEVEL;    // 6
    private static final int RES_TOP = ResourcePyramid.RESOURCE_TOP_LEVEL; // 21
    private static final int COL = 5;                                      // one indexed resource column under test

    /** Level-0 coords of the four horizontal children (bit0=X, bit1=Z; ry pinned 0). */
    private static int childRx(int i) { return i & 1; }
    private static int childRz(int i) { return (i >> 1) & 1; }

    // ---- cost seeding (mirrors ClobberGuardTest.seedUniform, which sets passFrac) ---------------------

    // Mirrors RegionPersistenceRoundTripTest.seedUniform EXACTLY (no manual passFrac): a leaf must pack/unpack
    // byte-identically so a live-seeded child equals its decoded twin — otherwise ground-truth coarse (all live)
    // would diverge from reconciled coarse (whose persisted children came via the codec).
    private static void seedUniformCost(CostPyramid p, int level, int rx, int ry, int rz, int kind, int hardness) {
        final int row = p.rowFor(level, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(kind);
        rf.setAvgSolidHardness(hardness);
        rf.setFragmentCount(0);
        p.setBuilt(level, row, true);
    }

    /** Seed leaf child {@code i} of L1(0,0,0) as a built uniform cost region. */
    private static void seedCostLeaf(CostPyramid p, int i, int kind, int hardness) {
        seedUniformCost(p, 0, childRx(i), 0, childRz(i), kind, hardness);
    }

    /** Seed leaf child {@code i} of L1(0,0,0) as a built resource tally of {@code val} in column {@link #COL}. */
    private static void seedResLeaf(ResourcePyramid p, int i, int val) {
        final int row = p.rowFor(0, childRx(i), 0, childRz(i));
        p.setLog2(0, row, COL, (byte) val);
        p.setBuilt(0, row, true);
    }

    /** Pack a cost row's fragment record to bytes (structural identity — mirrors the round-trip test). */
    private static byte[] packRow(CostPyramid p, int level, int row) {
        final RegionFragments rf = p.fragmentRecord(level, row);
        final byte[] buf = new byte[(CostCodec.regionBitLength(rf) + 7) >> 3];
        CostCodec.packRegion(rf, buf, 0);
        return buf;
    }

    private static int builtCostRows(CostPyramid p, int level) {
        int n = 0, rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(level, r) && p.fragmentRecord(level, r) != null) n++;
        }
        return n;
    }

    private static int builtResRows(ResourcePyramid p, int level) {
        int n = 0, rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(level, r)) n++;
        }
        return n;
    }

    /** Every built cost row of {@code src} must exist and be byte-identical in {@code back}, levels 0..maxLevel. */
    private static void assertCostIdentical(CostPyramid src, CostPyramid back, int maxLevel) {
        for (int level = 0; level <= maxLevel; level++) {
            int rows = src.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!src.isBuilt(level, r) || src.fragmentRecord(level, r) == null) continue;
                int rx = src.rowRX(level, r), ry = src.rowRY(level, r), rz = src.rowRZ(level, r);
                int br = back.rowIfPresent(level, rx, ry, rz);
                assertTrue(br != -1, "cost L" + level + " (" + rx + "," + ry + "," + rz + ") must be present");
                assertTrue(back.isBuilt(level, br), "reconciled cost L" + level + " row must be built");
                assertArrayEquals(packRow(src, level, r), packRow(back, level, br),
                        "cost L" + level + " (" + rx + "," + ry + "," + rz + ") record bytes must match ground truth");
            }
            assertEquals(builtCostRows(src, level), builtCostRows(back, level), "cost L" + level + " built-row count");
        }
    }

    private static void assertResIdentical(ResourcePyramid src, ResourcePyramid back, int maxLevel) {
        int cols = ResourceClasses.COLUMN_COUNT;
        for (int level = 0; level <= maxLevel; level++) {
            int rows = src.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!src.isBuilt(level, r)) continue;
                int rx = src.rowRX(level, r), ry = src.rowRY(level, r), rz = src.rowRZ(level, r);
                int br = back.rowIfPresent(level, rx, ry, rz);
                assertTrue(br != -1, "res L" + level + " (" + rx + "," + ry + "," + rz + ") must be present");
                assertTrue(back.isBuilt(level, br), "reconciled res L" + level + " row must be built");
                for (int c = 0; c < cols; c++) {
                    assertEquals(src.getLog2(level, r, c), back.getLog2(level, br, c),
                            "res L" + level + " (" + rx + "," + ry + "," + rz + ") col " + c + " must match ground truth");
                }
            }
            assertEquals(builtResRows(src, level), builtResRows(back, level), "res L" + level + " built-row count");
        }
    }

    // ===================================================================================================
    // THE ORACLE: a post-lazy-load partial pyramid, reconciled from the decode-reported straddle set, becomes
    // byte-identical to a ground-truth pyramid built with ALL children present + full mergeUp.
    // ===================================================================================================
    @Test
    void reconcileMakesPartialCoarseCorrectFull() throws IOException {
        // ---- ground truth: ALL four children built, full mergeUp (cost→L6, resource→L21) ----
        final CostPyramid gtCost = new CostPyramid();
        seedCostLeaf(gtCost, 0, RegionFragments.KIND_AIR, 0);     // (0,0,0) [live]
        seedCostLeaf(gtCost, 1, RegionFragments.KIND_SOLID, 8);   // (1,0,0) [live]
        // The two PERSISTED children carry a distinct hardness so the coarse aggregate (avgSolidHardness) is
        // genuinely wrong under the 2-live-leaf partial rollup — the pre-reconcile difference check below is real.
        seedCostLeaf(gtCost, 2, RegionFragments.KIND_SOLID, 2);   // (0,0,1) [persisted]
        seedCostLeaf(gtCost, 3, RegionFragments.KIND_AIR, 0);     // (1,0,1) [persisted]
        for (int r = 0; r < gtCost.rowCount(0); r++) {
            if (gtCost.isBuilt(0, r)) PyramidMerger.mergeUpFragments(gtCost, gtCost.rowRX(0, r), gtCost.rowRY(0, r), gtCost.rowRZ(0, r));
        }
        final ResourcePyramid gtRes = new ResourcePyramid();
        seedResLeaf(gtRes, 0, 3);
        seedResLeaf(gtRes, 1, 5);
        seedResLeaf(gtRes, 2, 5);
        seedResLeaf(gtRes, 3, 7); // full fold over col 5 of {3,5,5,7} = 7; the live subset {3,5} only folds to 5
        for (int r = 0; r < gtRes.rowCount(0); r++) {
            if (gtRes.isBuilt(0, r)) ResourceMerger.mergeUpTallies(gtRes, gtRes.rowRX(0, r), gtRes.rowRY(0, r), gtRes.rowRZ(0, r));
        }
        assertTrue(gtCost.rowCount(COST_TOP) > 0 && gtRes.rowCount(RES_TOP) > 0, "ground truth must reach the coarse tops");

        // ---- partial post-lazy-load state: only the FIRST TWO children built live + partial mergeUp ----
        final CostPyramid pCost = new CostPyramid();
        seedCostLeaf(pCost, 0, RegionFragments.KIND_AIR, 0);
        seedCostLeaf(pCost, 1, RegionFragments.KIND_SOLID, 8);
        for (int r = 0; r < pCost.rowCount(0); r++) {
            if (pCost.isBuilt(0, r)) PyramidMerger.mergeUpFragments(pCost, pCost.rowRX(0, r), pCost.rowRY(0, r), pCost.rowRZ(0, r));
        }
        final ResourcePyramid pRes = new ResourcePyramid();
        seedResLeaf(pRes, 0, 3);
        seedResLeaf(pRes, 1, 5);
        for (int r = 0; r < pRes.rowCount(0); r++) {
            if (pRes.isBuilt(0, r)) ResourceMerger.mergeUpTallies(pRes, pRes.rowRX(0, r), pRes.rowRY(0, r), pRes.rowRZ(0, r));
        }

        // Pre-reconcile: the partial coarse really IS wrong (proves the test isn't vacuous).
        final byte[] partialL1Before = packRow(pCost, 1, pCost.rowIfPresent(1, 0, 0, 0));
        assertFalse(java.util.Arrays.equals(partialL1Before, packRow(gtCost, 1, gtCost.rowIfPresent(1, 0, 0, 0))),
                "partial cost L1 must differ from ground truth BEFORE reconcile");
        assertEquals((byte) 5, pRes.getLog2(1, pRes.rowIfPresent(1, 0, 0, 0), COL), "partial res L1 = 5 (2-leaf fold)");
        assertEquals((byte) 7, gtRes.getLog2(1, gtRes.rowIfPresent(1, 0, 0, 0), COL), "ground-truth res L1 = 7 (4-leaf fold)");

        // ---- lazy shard-load: decode the ground-truth shard(0,0) into the partial pyramids WITH a collector ----
        // Interns the two persisted leaves beside the live pair AND reports the skipped (live-partial) coarse cells.
        final StraddleSet costStraddle = new StraddleSet();
        CostPyramidCodec.decode(new ByteArrayInputStream(encodeCostShard(gtCost)), pCost, costStraddle);
        final StraddleSet resStraddle = new StraddleSet();
        ResourcePyramidCodec.decode(new ByteArrayInputStream(encodeResShard(gtRes)), pRes, resStraddle);

        // Decode must have reported the coarse straddle cells at levels 1..5 (never level 0).
        assertFalse(costStraddle.isEmpty(), "cost decode reports straddle coarse cells");
        assertFalse(resStraddle.isEmpty(), "res decode reports straddle coarse cells");
        assertTrue(costStraddle.at(0).isEmpty() && resStraddle.at(0).isEmpty(), "level-0 skips are never recorded");
        for (int level = 1; level <= RegionAddress.SHARD_LEVEL; level++) {
            assertTrue(costStraddle.at(level).contains(RegionAddress.packLevelKey(0, 0, 0)),
                    "cost straddle must include coarse (0,0,0) at L" + level);
            assertTrue(resStraddle.at(level).contains(RegionAddress.packLevelKey(0, 0, 0)),
                    "res straddle must include coarse (0,0,0) at L" + level);
        }

        // ---- reconcile ----
        RegionReconciler.reconcile(pCost, costStraddle, pRes, resStraddle);

        // ---- the oracle: byte-identical to ground truth at EVERY level (incl. the propagated coarse tops) ----
        assertCostIdentical(gtCost, pCost, COST_TOP);
        assertResIdentical(gtRes, pRes, RES_TOP);
    }

    // ===================================================================================================
    // Empty straddle set (the fully-unloaded-shard load — the common case) is a strict no-op.
    // ===================================================================================================
    @Test
    void reconcileEmptySetNoOp() {
        final CostPyramid cost = new CostPyramid();
        seedCostLeaf(cost, 0, RegionFragments.KIND_AIR, 0);
        seedCostLeaf(cost, 1, RegionFragments.KIND_SOLID, 8);
        for (int r = 0; r < cost.rowCount(0); r++) {
            if (cost.isBuilt(0, r)) PyramidMerger.mergeUpFragments(cost, cost.rowRX(0, r), cost.rowRY(0, r), cost.rowRZ(0, r));
        }
        final ResourcePyramid res = new ResourcePyramid();
        seedResLeaf(res, 0, 3);
        for (int r = 0; r < res.rowCount(0); r++) {
            if (res.isBuilt(0, r)) ResourceMerger.mergeUpTallies(res, res.rowRX(0, r), res.rowRY(0, r), res.rowRZ(0, r));
        }
        final byte[] costL1 = packRow(cost, 1, cost.rowIfPresent(1, 0, 0, 0));
        final byte resL1 = res.getLog2(1, res.rowIfPresent(1, 0, 0, 0), COL);
        final byte resTop = res.getLog2(RES_TOP, res.rowIfPresent(RES_TOP, 0, 0, 0), COL);

        RegionReconciler.reconcile(cost, new StraddleSet(), res, new StraddleSet());

        assertArrayEquals(costL1, packRow(cost, 1, cost.rowIfPresent(1, 0, 0, 0)), "empty reconcile leaves cost L1 untouched");
        assertEquals(resL1, res.getLog2(1, res.rowIfPresent(1, 0, 0, 0), COL), "empty reconcile leaves res L1 untouched");
        assertEquals(resTop, res.getLog2(RES_TOP, res.rowIfPresent(RES_TOP, 0, 0, 0), COL), "empty reconcile leaves res top untouched");
        // (a null straddle set is likewise a no-op)
        RegionReconciler.reconcileCost(cost, null);
        RegionReconciler.reconcileResource(res, null);
        assertArrayEquals(costL1, packRow(cost, 1, cost.rowIfPresent(1, 0, 0, 0)), "null reconcile leaves cost L1 untouched");
    }

    // ===================================================================================================
    // Boundary case: a straddle cell whose child sits in a persisted-NON-resident OTHER shard — reconcile routes
    // through the guarded driver, so the clobber-guard DEFERS (marks reconcile-pending) instead of clobbering.
    // ===================================================================================================
    @Test
    void reconcileDefersAtNonResidentBoundary() {
        // COST: L1(0,0,0) seeded with a distinctive persisted SOLID value; only children 0..3 present, 4..7 absent
        // and belonging to persisted-non-resident shard (0,0). reconcileNode → combineFragments → guard fires.
        final CostPyramid cost = new CostPyramid();
        seedCostLeaf(cost, 0, RegionFragments.KIND_AIR, 0);
        seedCostLeaf(cost, 1, RegionFragments.KIND_AIR, 0);
        seedCostLeaf(cost, 2, RegionFragments.KIND_AIR, 0);
        seedCostLeaf(cost, 3, RegionFragments.KIND_AIR, 0);
        seedUniformCost(cost, 1, 0, 0, 0, RegionFragments.KIND_SOLID, 8); // the persisted-loaded coarse value

        final RegionShardResidency costRes = new RegionShardResidency();
        costRes.markPersisted(0, 0); // on disk, NOT resident ⇒ persisted-non-resident
        cost.setResidency(costRes);

        final StraddleSet cs = new StraddleSet();
        cs.add(1, 0, 0, 0);
        RegionReconciler.reconcileCost(cost, cs);

        final int c = cost.rowIfPresent(1, 0, 0, 0);
        assertEquals(RegionFragments.KIND_SOLID, cost.fragmentRecord(1, c).kind(),
                "the straddle cell must be UNCHANGED (guard deferred), not clobbered by a partial rollup");
        assertEquals(8, cost.fragmentRecord(1, c).avgSolidHardness(), "its persisted hardness is intact");
        assertTrue(costRes.isReconcilePending(1, 0, 0, 0), "the deferred cost cell is re-marked reconcile-pending");

        // RESOURCE: same shape — a distinctive persisted tally must not be summed down toward the partial child set.
        final ResourcePyramid res = new ResourcePyramid();
        for (int i = 0; i < 4; i++) seedResLeaf(res, i, 2);
        final int rc = res.rowFor(1, 0, 0, 0);
        res.setLog2(1, rc, COL, (byte) 9);
        res.setBuilt(1, rc, true);
        final RegionShardResidency resRes = new RegionShardResidency();
        resRes.markPersisted(0, 0);
        res.setResidency(resRes);

        final StraddleSet rs = new StraddleSet();
        rs.add(1, 0, 0, 0);
        RegionReconciler.reconcileResource(res, rs);

        assertEquals((byte) 9, res.getLog2(1, res.rowIfPresent(1, 0, 0, 0), COL),
                "the straddle tally must be UNCHANGED (guard deferred), not summed toward the partial child set");
        assertTrue(resRes.isReconcilePending(1, 0, 0, 0), "the deferred resource cell is re-marked reconcile-pending");
    }

    // ---- shard encode helpers (shard (0,0) holds every coord under test) ------------------------------

    private static byte[] encodeCostShard(CostPyramid p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(p, 0, 0, bos);
        return bos.toByteArray();
    }

    private static byte[] encodeResShard(ResourcePyramid p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encodeShard(p, 0, 0, bos);
        return bos.toByteArray();
    }
}
