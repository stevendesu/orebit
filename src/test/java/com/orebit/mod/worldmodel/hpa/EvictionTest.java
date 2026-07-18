package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;
import com.orebit.mod.worldmodel.persistence.RegionEvictor;
import com.orebit.mod.worldmodel.persistence.RegionReconciler;
import com.orebit.mod.worldmodel.persistence.ResourcePyramidCodec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * Headless unit tests for the <b>Stage-2 cold-shard EVICTOR</b> (DESIGN-worldmodel-persistence.md — bounded region
 * RAM, increment 4): the piece that actually BOUNDS live region RAM by paging the coldest resident shards back to
 * disk. Increment 3 built lazy-load (coarse-only startup + on-demand page-in); this proves eviction is (a) a real
 * RAM drop that keeps the coarse tier intact, (b) byte-identical on the evict→reload round-trip, (c) off by default
 * (cap 0), and (d) safe under a concurrent live re-merge (the clobber-guard defers on an evicted child).
 *
 * <p>Pure POJO, no Minecraft server (mirrors {@link LazyLoadOracleTest} / {@link ReconcileTest} /
 * {@link ClobberGuardTest}): the NavStore evictable gate + the tick-drain wiring need a live level and are left for
 * in-game verification, so the eviction MECHANICS are tested directly — {@link RegionEvictor#freeShard} (bypassing
 * the gate) and {@link RegionEvictor#evictDownTo} (with an injected {@link RegionEvictor.EvictionEnv}). Cost leaves
 * are seeded through {@link RegionFragments}'s package-private setters (hence the {@code hpa} package); the
 * {@link ResourcePyramid} uses its public API.
 *
 * <p>The shard under test is level-5 {@code (0,0)}: the 8 octree children of the level-1 cell {@code (0,0,0)} —
 * coords {@code (i&1,(i>>2)&1,(i>>1)&1)} — whose chunk-X 0..1 all fall in {@code shardOf(rx,0)==0}, and whose whole
 * coarse ancestor chain {@code (0,·,0)} at levels 1..5 also lives in shard {@code (0,0)} ({@link RegionAddress#shardOf}).
 * Both shard tops roll up to the per-dimension coarse level 6.
 */
public final class EvictionTest {

    private static final int G = 16;
    private static final int COST_TOP = RegionAddress.MAX_COARSE_LEVEL;    // 6
    private static final int RES_TOP = ResourcePyramid.RESOURCE_TOP_LEVEL; // 21
    private static final int COL = 5;                                      // one indexed resource column under test

    // ---- octree child i of L1(0,0,0): bit0=X, bit1=Z, bit2=Y -------------------------------------------
    private static int childRx(int i) { return i & 1; }
    private static int childRy(int i) { return (i >> 2) & 1; }
    private static int childRz(int i) { return (i >> 1) & 1; }

    private static int kindFor(int i)   { return (i % 2 == 0) ? RegionFragments.KIND_AIR : RegionFragments.KIND_SOLID; }
    private static int hardFor(int i)   { return (i % 2 == 0) ? 0 : (2 + i); }
    private static int resValFor(int i) { return 1 + i; }

    // ---- seeding (mirrors ReconcileTest.seedUniformCost EXACTLY so a live-seeded leaf packs byte-identically
    //      to its decoded persisted twin) ------------------------------------------------------------------
    private static void seedUniformCost(CostPyramid p, int level, int rx, int ry, int rz, int kind, int hardness) {
        final int row = p.rowFor(level, rx, ry, rz);
        final RegionFragments rf = p.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(kind);
        rf.setAvgSolidHardness(hardness);
        rf.setFragmentCount(0);
        p.setBuilt(level, row, true);
    }

    /** Seed shard (0,0)'s 8 octree leaves into both pyramids (varied kinds/hardness/tallies → genuine MIXED coarse). */
    private static void seedShard(CostPyramid cost, ResourcePyramid res) {
        for (int i = 0; i < 8; i++) {
            seedUniformCost(cost, 0, childRx(i), childRy(i), childRz(i), kindFor(i), hardFor(i));
            final int rr = res.rowFor(0, childRx(i), childRy(i), childRz(i));
            res.setLog2(0, rr, COL, (byte) resValFor(i));
            res.setBuilt(0, rr, true);
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

    // ---- byte-identity helpers (copied from LazyLoadOracleTest) ----------------------------------------
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
                assertTrue(back.isBuilt(level, br), "reloaded cost L" + level + " (" + rx + "," + ry + "," + rz + ") built");
                assertArrayEquals(packRow(src, level, r), packRow(back, level, br),
                        "cost L" + level + " (" + rx + "," + ry + "," + rz + ") record bytes must match pre-eviction");
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
                assertTrue(back.isBuilt(level, br), "reloaded res L" + level + " (" + rx + "," + ry + "," + rz + ") built");
                for (int c = 0; c < cols; c++) {
                    assertEquals(src.getLog2(level, r, c), back.getLog2(level, br, c),
                            "res L" + level + " (" + rx + "," + ry + "," + rz + ") col " + c + " must match pre-eviction");
                }
            }
            assertEquals(builtResRows(src, level), builtResRows(back, level), "res L" + level + " built-row count");
        }
    }

    private static byte[] encCostShard(CostPyramid p, int sx, int sz) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(p, sx, sz, bos);
        return bos.toByteArray();
    }

    private static byte[] encResShard(ResourcePyramid p, int sx, int sz) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encodeShard(p, sx, sz, bos);
        return bos.toByteArray();
    }

    // ===================================================================================================
    // (1) Eviction frees the shard's L0-L5 leaf RAM, keeps the L6 coarse intact, drops the resident count,
    //     and leaves the shard persisted-non-resident.
    // ===================================================================================================
    @Test
    void evictFreesLeafRamKeepsCoarse() {
        final CostPyramid cost = new CostPyramid();
        final ResourcePyramid res = new ResourcePyramid();
        seedShard(cost, res);
        mergeUpAllCost(cost);
        mergeUpAllRes(res);
        assertTrue(cost.rowCount(COST_TOP) > 0, "mergeUp reaches the cost coarse top");

        final RegionShardResidency residency = new RegionShardResidency();
        residency.markPersisted(0, 0); // the shard is on disk (a flush already wrote it)
        cost.setResidency(residency);
        res.setResidency(residency);

        // Snapshot the L6 coarse cell (must be untouched by eviction).
        final int c6 = cost.rowIfPresent(COST_TOP, 0, 0, 0);
        assertTrue(c6 != -1 && cost.isBuilt(COST_TOP, c6), "L6 coarse is built pre-eviction");
        final byte[] coarseBefore = packRow(cost, COST_TOP, c6);

        final int before = RegionEvictor.residentLeafCount(cost);
        assertEquals(8, before, "8 resident built L0 cost leaves before eviction");

        final int freed = RegionEvictor.freeShard(cost, res, residency, 0, 0);
        assertEquals(8, freed, "freeing shard (0,0) frees all 8 of its L0 leaves");

        // Every L0..L5 cost row of shard (0,0) is now null + unbuilt.
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            int rows = cost.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (RegionAddress.shardOf(cost.rowRX(level, r), level) != 0
                        || RegionAddress.shardOf(cost.rowRZ(level, r), level) != 0) continue;
                assertFalse(cost.isBuilt(level, r), "evicted cost L" + level + " row must be !built");
                assertNull(cost.fragmentRecord(level, r), "evicted cost L" + level + " row frags must be null");
            }
            int rrows = res.rowCount(level);
            for (int r = 0; r < rrows; r++) {
                if (RegionAddress.shardOf(res.rowRX(level, r), level) != 0
                        || RegionAddress.shardOf(res.rowRZ(level, r), level) != 0) continue;
                assertFalse(res.isBuilt(level, r), "evicted res L" + level + " row must be !built");
            }
        }

        // The L6 coarse cell is UNCHANGED (still correct-full).
        assertArrayEquals(coarseBefore, packRow(cost, COST_TOP, cost.rowIfPresent(COST_TOP, 0, 0, 0)),
                "the L6 coarse cell must be untouched by eviction");
        assertTrue(res.rowCount(RES_TOP) > 0 && res.isBuilt(RES_TOP, res.rowIfPresent(RES_TOP, 0, 0, 0)),
                "resource coarse top stays resident + built");

        // The resident-leaf count dropped, and the shard is now persisted-non-resident.
        assertEquals(before - freed, RegionEvictor.residentLeafCount(cost), "resident-leaf count dropped by freed");
        assertEquals(0, RegionEvictor.residentLeafCount(cost), "no resident L0 leaves remain");
        assertTrue(residency.isPersistedNonResident(0, 0), "the evicted shard is persisted-non-resident");
        assertFalse(residency.isResident(0, 0), "the evicted shard is not resident");
    }

    // ===================================================================================================
    // (2) Evict → reload is byte-identical (the evict returns the subtree to the coarse-only-not-resident
    //     state a coarse-only startup produces; the increment-3 reload path re-fills it exactly).
    // ===================================================================================================
    @Test
    void evictThenReloadIsByteIdentical() throws IOException {
        // Ground truth (never mutated) — the pre-eviction reference.
        final CostPyramid gtCost = new CostPyramid();
        final ResourcePyramid gtRes = new ResourcePyramid();
        seedShard(gtCost, gtRes);
        mergeUpAllCost(gtCost);
        mergeUpAllRes(gtRes);

        // The subject pyramid, built identically.
        final CostPyramid pCost = new CostPyramid();
        final ResourcePyramid pRes = new ResourcePyramid();
        seedShard(pCost, pRes);
        mergeUpAllCost(pCost);
        mergeUpAllRes(pRes);

        // Serialize the shard exactly as loadShard would read it (the on-disk L0-5 leaf files), BEFORE eviction.
        final byte[] costBytes = encCostShard(pCost, 0, 0);
        final byte[] resBytes = encResShard(pRes, 0, 0);

        final RegionShardResidency residency = new RegionShardResidency();
        residency.markPersisted(0, 0);
        pCost.setResidency(residency);
        pRes.setResidency(residency);

        // Evict, then reload = decode-with-straddle + reconcile + markResident (the increment-3 loadShard body).
        RegionEvictor.freeShard(pCost, pRes, residency, 0, 0);
        assertEquals(0, RegionEvictor.residentLeafCount(pCost), "no resident L0 leaves after eviction");

        final StraddleSet costStraddle = new StraddleSet();
        final StraddleSet resStraddle = new StraddleSet();
        CostPyramidCodec.decode(new ByteArrayInputStream(costBytes), pCost, costStraddle);
        ResourcePyramidCodec.decode(new ByteArrayInputStream(resBytes), pRes, resStraddle);
        RegionReconciler.reconcile(pCost, costStraddle, pRes, resStraddle);
        residency.markResident(0, 0);

        // Byte-identical to ground truth at every level (L0-6 cost, L0-21 resource), incl. the kept coarse tops.
        assertCostIdentical(gtCost, pCost, COST_TOP);
        assertResIdentical(gtRes, pRes, RES_TOP);
        assertTrue(residency.isResident(0, 0) && !residency.isPersistedNonResident(0, 0),
                "the reloaded shard is resident again");
    }

    // ===================================================================================================
    // (3) cap 0 (the default) evicts nothing — 0 means UNBOUNDED / eviction off, NOT "evict everything".
    // ===================================================================================================
    @Test
    void capZeroIsNoOp() {
        final CostPyramid cost = new CostPyramid();
        final ResourcePyramid res = new ResourcePyramid();
        seedShard(cost, res);
        mergeUpAllCost(cost);
        mergeUpAllRes(res);
        final RegionShardResidency residency = new RegionShardResidency();
        cost.setResidency(residency);
        res.setResidency(residency);

        final int before = RegionEvictor.residentLeafCount(cost);
        assertEquals(8, before, "8 resident L0 leaves");

        // A trivial env (everything evictable, always clean) — proves cap 0 short-circuits BEFORE any of it runs.
        final RegionEvictor.EvictionEnv env = new RegionEvictor.EvictionEnv() {
            @Override public boolean columnLoaded(int chunkX, int chunkZ) { return false; }
            @Override public boolean ensureFlushed(int sx, int sz) { return true; }
        };
        final int freed = RegionEvictor.evictDownTo(cost, res, residency, 0, env);

        assertEquals(0, freed, "cap 0 frees nothing");
        assertEquals(before, RegionEvictor.residentLeafCount(cost), "cap 0 leaves the resident count unchanged");
        assertFalse(residency.isPersistedNonResident(0, 0), "nothing evicted ⇒ nothing persisted-non-resident");

        // Sanity: a POSITIVE cap below the current count DOES evict (so the no-op above is really the cap-0 guard).
        residency.markPersisted(0, 0); // pretend on disk so the evicted shard round-trips
        final int freedPos = RegionEvictor.evictDownTo(cost, res, residency, 4, env);
        assertTrue(freedPos > 0, "a cap below the resident count evicts at least one shard");
    }

    // ===================================================================================================
    // (4) After eviction, a live re-merge of the coarse ancestor over the evicted shard DEFERS (the guard)
    //     and enqueues the reload — it does NOT clobber the resident coarse value.
    // ===================================================================================================
    @Test
    void guardDefersOnEvictedChild() {
        final CostPyramid cost = new CostPyramid();
        final ResourcePyramid res = new ResourcePyramid();
        final RegionShardResidency residency = new RegionShardResidency();
        cost.setResidency(residency); // attached (persistedIndex empty) → mergeUp builds fully, guard never fires yet
        res.setResidency(residency);

        seedShard(cost, res);
        mergeUpAllCost(cost);
        final int c6 = cost.rowIfPresent(COST_TOP, 0, 0, 0);
        assertTrue(c6 != -1 && cost.isBuilt(COST_TOP, c6), "L6 coarse built pre-eviction");
        final byte[] coarseBefore = packRow(cost, COST_TOP, c6);

        // Evict shard (0,0): its L5 top becomes interned-but-!built; mark it persisted (on disk).
        residency.markPersisted(0, 0);
        RegionEvictor.freeShard(cost, res, residency, 0, 0);
        assertTrue(residency.isPersistedNonResident(0, 0), "evicted shard is persisted-non-resident");

        // A live re-merge of the coarse ancestor (L6) whose L5 child is now the evicted shard.
        PyramidMerger.reconcileNode(cost, COST_TOP, 0, 0, 0);

        // The guard DEFERRED: L6 is unchanged (not clobbered by the partial rollup), the node is reconcile-pending,
        // and the missing shard was enqueued for reload.
        assertArrayEquals(coarseBefore, packRow(cost, COST_TOP, cost.rowIfPresent(COST_TOP, 0, 0, 0)),
                "the guard must keep the resident L6 coarse value intact (no partial-rollup clobber)");
        assertTrue(residency.isReconcilePending(COST_TOP, 0, 0, 0), "the deferred coarse node is reconcile-pending");
        assertTrue(residency.hasLoadRequests(), "the guard enqueued a reload for the evicted shard");
    }
}
