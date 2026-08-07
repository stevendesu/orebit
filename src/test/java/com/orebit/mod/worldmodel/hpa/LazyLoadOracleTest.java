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
 * The <b>Stage-2 bounded-region-RAM ORACLE</b> (NOTES-perf-and-persistence.md §4 — coarse-only startup +
 * on-demand atomic lazy-load). Proves the whole live lazy path is byte-identical to eager-load-all:
 * <b>coarse-only startup + partial-live exploration (with the clobber-guard active) + per-shard lazy-load
 * (decode-with-straddle + reconcile) == a fully eager pyramid</b>, at every level.
 *
 * <p>Pure POJO, no Minecraft server (mirrors {@link ReconcileTest} / {@link ClobberGuardTest}): the codecs, the
 * reconciler and the residency are driven DIRECTLY (this stands in for {@code RegionShardLoader}'s inner logic,
 * whose file-I/O glue — {@code loadCoarseOnly} / {@code loadShard} / directory listing / the tick-drain — needs a
 * live {@code ServerLevel} and is left for in-game verification, like Stage-1's glue). Cost leaves are seeded
 * through {@link RegionFragments}'s package-private setters (hence the {@code hpa} package); the
 * {@link ResourcePyramid} uses its public API.
 *
 * <h2>The multi-shard subtree under test</h2>
 * Two level-0 shards ({@link RegionAddress#SHARD_LEVEL} == 5, {@link RegionAddress#shardOf}):
 * <ul>
 *   <li><b>Shard A = (0,0)</b>: the 8 octree children of level-1 {@code (0,0,0)} — coords {@code (i&1,(i>>2)&1,(i>>1)&1)},
 *       all with chunk-X 0..1 ⇒ {@code shardOf(rx,0)==0}. They roll up the coarse chain {@code (0,·,0)} at levels 1..5.</li>
 *   <li><b>Shard B = (1,0)</b>: the 8 octree children of level-1 {@code (16,0,0)} — coords {@code (32+(i&1),(i>>2)&1,(i>>1)&1)},
 *       chunk-X 32..33 ⇒ {@code shardOf(rx,0)==1}. They roll up the chain {@code (16,·,0)→(8)→(4)→(2)→(1)} at levels 1..5.</li>
 * </ul>
 * Both shard tops (level-5 {@code (0,·,0)} and {@code (1,·,0)}) share the SAME level-6 coarse parent {@code (0,0,0)}
 * ({@link RegionAddress#parentRX}), which lives in the per-dimension coarse file.
 */
public final class LazyLoadOracleTest {

    private static final int G = 16;
    private static final int COST_TOP = RegionAddress.MAX_COARSE_LEVEL;    // 6
    private static final int RES_TOP = ResourcePyramid.RESOURCE_TOP_LEVEL; // 21
    private static final int COL = 5;                                      // one indexed resource column under test

    // ---- shard-A / shard-B leaf coords (octree child i: bit0=X, bit1=Z, bit2=Y) -----------------------
    private static int aRx(int i) { return i & 1; }
    private static int aRy(int i) { return (i >> 2) & 1; }
    private static int aRz(int i) { return (i >> 1) & 1; }
    private static int bRx(int i) { return 32 + (i & 1); }   // level-1 parent (16,0,0): childRX = 32 | (i&1)
    private static int bRy(int i) { return (i >> 2) & 1; }
    private static int bRz(int i) { return (i >> 1) & 1; }

    /** A deterministic non-uniform leaf mix so the coarse rollups are genuine MIXED records (not a trivial uniform). */
    private static int kindFor(int i) { return (i % 2 == 0) ? RegionFragments.KIND_AIR : RegionFragments.KIND_SOLID; }
    private static int hardFor(int i) { return (i % 2 == 0) ? 0 : (2 + i); }
    private static int resValFor(int i) { return 1 + i; }   // column-COL log₂ value per leaf

    // ---- cost seeding: mirrors ReconcileTest.seedUniformCost EXACTLY (no manual passFrac) so a live-seeded
    //      leaf packs byte-identically to its decoded persisted twin --------------------------------------
    private static void seedUniformCost(CostPyramid p, int level, int rx, int ry, int rz, int kind, int hardness) {
        final int row = p.rowFor(level, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(kind);
        rf.setAvgSolidHardness(hardness);
        rf.setFragmentCount(0);
        p.setBuilt(level, row, true);
    }

    private static void seedResLeaf(ResourcePyramid p, int rx, int ry, int rz, int val) {
        final int row = p.rowFor(0, rx, ry, rz);
        p.setLog2(0, row, COL, (byte) val);
        p.setBuilt(0, row, true);
    }

    /** Seed shard A's 8 leaves into both pyramids (used for ground truth AND the partial-live subset). */
    private static void seedShardALeaves(CostPyramid cost, ResourcePyramid res) {
        for (int i = 0; i < 8; i++) {
            seedUniformCost(cost, 0, aRx(i), aRy(i), aRz(i), kindFor(i), hardFor(i));
            seedResLeaf(res, aRx(i), aRy(i), aRz(i), resValFor(i));
        }
    }

    /** Seed shard B's 8 leaves (ground-truth only — never built live in the lazy pyramid). */
    private static void seedShardBLeaves(CostPyramid cost, ResourcePyramid res) {
        for (int i = 0; i < 8; i++) {
            seedUniformCost(cost, 0, bRx(i), bRy(i), bRz(i), kindFor(i), hardFor(i));
            seedResLeaf(res, bRx(i), bRy(i), bRz(i), resValFor(i));
        }
    }

    private static void mergeUpAllCost(CostPyramid p) {
        for (int r = 0; r < p.rowCount(0); r++) {
            if (p.isBuilt(0, r)) PyramidMerger.mergeUpFragments(p, p.rowRX(0, r), p.rowRY(0, r), p.rowRZ(0, r));
        }
    }

    private static void mergeUpAllRes(ResourcePyramid p) {
        for (int r = 0; r < p.rowCount(0); r++) {
            if (p.isBuilt(0, r)) ResourceMerger.mergeUpTallies(p, p.rowRX(0, r), p.rowRY(0, r), p.rowRZ(0, r));
        }
    }

    // ---- byte-identity assertions (copied from ReconcileTest) ------------------------------------------
    private static byte[] packRow(CostPyramid p, int level, int row) {
        final RegionFragments rf = p.fragmentRecord(level, row);
        final byte[] buf = new byte[(CostCodec.regionBitLength(rf) + 7) >> 3];
        CostCodec.packRegion(rf, buf, 0);
        return buf;
    }

    private static int builtCostRows(CostPyramid p, int level) {
        int n = 0, rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) if (p.isBuilt(level, r) && p.fragmentRecord(level, r) != null) n++;
        return n;
    }

    private static int builtResRows(ResourcePyramid p, int level) {
        int n = 0, rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) if (p.isBuilt(level, r)) n++;
        return n;
    }

    private static void assertCostIdentical(CostPyramid src, CostPyramid back, int maxLevel) {
        for (int level = 0; level <= maxLevel; level++) {
            int rows = src.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!src.isBuilt(level, r) || src.fragmentRecord(level, r) == null) continue;
                int rx = src.rowRX(level, r), ry = src.rowRY(level, r), rz = src.rowRZ(level, r);
                int br = back.rowIfPresent(level, rx, ry, rz);
                assertTrue(br != -1, "cost L" + level + " (" + rx + "," + ry + "," + rz + ") must be present");
                assertTrue(back.isBuilt(level, br), "lazy cost L" + level + " (" + rx + "," + ry + "," + rz + ") must be built");
                assertArrayEquals(packRow(src, level, r), packRow(back, level, br),
                        "cost L" + level + " (" + rx + "," + ry + "," + rz + ") record bytes must match eager");
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
                assertTrue(back.isBuilt(level, br), "lazy res L" + level + " (" + rx + "," + ry + "," + rz + ") must be built");
                for (int c = 0; c < cols; c++) {
                    assertEquals(src.getLog2(level, r, c), back.getLog2(level, br, c),
                            "res L" + level + " (" + rx + "," + ry + "," + rz + ") col " + c + " must match eager");
                }
            }
            assertEquals(builtResRows(src, level), builtResRows(back, level), "res L" + level + " built-row count");
        }
    }

    // ---- shard / coarse encode helpers ----------------------------------------------------------------
    private static byte[] encCostShard(CostPyramid p, int sx, int sz) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(p, sx, sz, bos);
        return bos.toByteArray();
    }
    private static byte[] encCostCoarse(CostPyramid p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeCoarse(p, bos);
        return bos.toByteArray();
    }
    private static byte[] encResShard(ResourcePyramid p, int sx, int sz) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encodeShard(p, sx, sz, bos);
        return bos.toByteArray();
    }
    private static byte[] encResCoarse(ResourcePyramid p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encodeCoarse(p, bos);
        return bos.toByteArray();
    }

    /** Emulate {@code RegionShardLoader.loadShard}: decode-with-straddle, reconcile, mark resident. */
    private static void lazyLoadShard(CostPyramid cost, byte[] costBytes, ResourcePyramid res, byte[] resBytes,
                                      RegionShardResidency residency, int sx, int sz,
                                      StraddleSet costStraddle, StraddleSet resStraddle) throws IOException {
        CostPyramidCodec.decode(new ByteArrayInputStream(costBytes), cost, costStraddle);
        ResourcePyramidCodec.decode(new ByteArrayInputStream(resBytes), res, resStraddle);
        RegionReconciler.reconcile(cost, costStraddle, res, resStraddle);
        residency.markResident(sx, sz);
    }

    // ===================================================================================================
    // THE ORACLE
    // ===================================================================================================
    @Test
    void coarseOnlyStartupPlusPartialExploreLazyLoadEqualsEager() throws IOException {
        // ---- (1) GROUND TRUTH: all 16 leaves (both shards) built + full mergeUp, NO residency ----
        final CostPyramid gtCost = new CostPyramid();
        final ResourcePyramid gtRes = new ResourcePyramid();
        seedShardALeaves(gtCost, gtRes);
        seedShardBLeaves(gtCost, gtRes);
        mergeUpAllCost(gtCost);
        mergeUpAllRes(gtRes);
        assertTrue(gtCost.rowCount(COST_TOP) > 0 && gtRes.rowCount(RES_TOP) > 0, "ground truth reaches the coarse tops");

        // Serialize to per-shard leaf bytes + per-dimension coarse bytes (the on-disk Stage-1 layout).
        final byte[] costA = encCostShard(gtCost, 0, 0), costB = encCostShard(gtCost, 1, 0), costCoarse = encCostCoarse(gtCost);
        final byte[] resA = encResShard(gtRes, 0, 0), resB = encResShard(gtRes, 1, 0), resCoarse = encResCoarse(gtRes);

        // ---- (2) LAZY STARTUP: decode ONLY the coarse bytes; index the shards persisted-non-resident ----
        final CostPyramid pCost = new CostPyramid();
        final ResourcePyramid pRes = new ResourcePyramid();
        CostPyramidCodec.decode(new ByteArrayInputStream(costCoarse), pCost);   // coarse L6 only, direct
        ResourcePyramidCodec.decode(new ByteArrayInputStream(resCoarse), pRes); // coarse L6..21 only, direct
        assertEquals(0, pCost.rowCount(0), "coarse-only startup interns NO leaves");
        assertTrue(pCost.rowCount(COST_TOP) > 0, "coarse-only startup DID load the coarse top");

        final RegionShardResidency residency = new RegionShardResidency();
        residency.setPersistedIndex(java.util.List.of(
                RegionShardResidency.shardKey(0, 0), RegionShardResidency.shardKey(1, 0)));
        pCost.setResidency(residency);
        pRes.setResidency(residency);

        // ---- (3) PARTIAL-LIVE EXPLORE shard A only, with the clobber-guard ACTIVE ----
        seedShardALeaves(pCost, pRes);
        mergeUpAllCost(pCost);
        mergeUpAllRes(pRes);
        // The guard DEFERRED the level-2 coarse recompute (7 of 8 level-1 children are absent + persisted-non-resident),
        // so the persisted coarse values above the shard were NOT clobbered, and the missing shard was requested.
        assertTrue(residency.isReconcilePending(2, 0, 0, 0), "guard defers the L2 recompute → reconcile-pending");
        assertTrue(residency.hasLoadRequests(), "the guard enqueued a load for the persisted-non-resident shard");
        // Coarse top must still equal ground truth (loaded from the coarse file, never clobbered by the partial build).
        assertArrayEquals(packRow(gtCost, COST_TOP, gtCost.rowIfPresent(COST_TOP, 0, 0, 0)),
                packRow(pCost, COST_TOP, pCost.rowIfPresent(COST_TOP, 0, 0, 0)),
                "coarse top must be intact (guard prevented the partial-rollup clobber)");

        // ---- (4) LAZY-LOAD each shard: decode-with-straddle + reconcile + mark resident ----
        final StraddleSet costStrA = new StraddleSet(), resStrA = new StraddleSet();
        lazyLoadShard(pCost, costA, pRes, resA, residency, 0, 0, costStrA, resStrA);
        // Shard A's coarse chain WAS live-built (its L1 cell), so decode reports straddle coarse cells to reconcile.
        assertFalse(costStrA.isEmpty(), "shard-A decode reports straddle coarse cells (live-partial L1 skipped)");
        assertTrue(costStrA.at(0).isEmpty(), "level-0 leaf skips are never recorded as straddles");

        final StraddleSet costStrB = new StraddleSet(), resStrB = new StraddleSet();
        lazyLoadShard(pCost, costB, pRes, resB, residency, 1, 0, costStrB, resStrB);

        assertTrue(residency.isResident(0, 0) && residency.isResident(1, 0), "both shards are now resident");

        // ---- (5) THE ORACLE: byte-identical to eager at every level (incl. the persisted coarse tops) ----
        assertCostIdentical(gtCost, pCost, COST_TOP);
        assertResIdentical(gtRes, pRes, RES_TOP);
    }

    // ===================================================================================================
    // EAGER PATH UNCHANGED: an empty (non-null) residency is byte-identical to no residency — the
    // hpa.lazyLoad=false fallback (persistedShardIndex empty ⇒ guard never fires ⇒ nothing deferred/enqueued).
    // ===================================================================================================
    @Test
    void eagerPathUnchanged() {
        final CostPyramid noRes = new CostPyramid();
        final ResourcePyramid noResR = new ResourcePyramid();
        seedShardALeaves(noRes, noResR);
        seedShardBLeaves(noRes, noResR);
        mergeUpAllCost(noRes);
        mergeUpAllRes(noResR);

        final CostPyramid emptyRes = new CostPyramid();
        final ResourcePyramid emptyResR = new ResourcePyramid();
        final RegionShardResidency empty = new RegionShardResidency(); // non-null but empty persisted index
        emptyRes.setResidency(empty);
        emptyResR.setResidency(empty);
        seedShardALeaves(emptyRes, emptyResR);
        seedShardBLeaves(emptyRes, emptyResR);
        mergeUpAllCost(emptyRes);
        mergeUpAllRes(emptyResR);

        assertCostIdentical(noRes, emptyRes, COST_TOP);
        assertResIdentical(noResR, emptyResR, RES_TOP);
        assertFalse(empty.hasPending(), "an empty residency defers nothing");
        assertFalse(empty.hasLoadRequests(), "an empty residency enqueues no loads");
    }
}
