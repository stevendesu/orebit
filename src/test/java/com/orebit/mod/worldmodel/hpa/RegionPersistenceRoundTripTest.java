package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;
import com.orebit.mod.worldmodel.persistence.ResourcePyramidCodec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * Headless round-trip tests for the <b>Stage-1 sharded</b> world-model persistence codecs
 * ({@link CostPyramidCodec} / {@link ResourcePyramidCodec}, NOTES-perf-and-persistence.md §1). These need
 * <b>no Minecraft server</b>: a pyramid is hand-built, the real {@code mergeUp} drivers populate its coarse
 * levels, it is FLUSHED via the new per-shard writer + per-dimension coarse writer, RELOADED via the new
 * reader <b>with no {@code mergeUp} replay</b>, and every row at every level must survive byte-identically —
 * which proves (a) the shard split/reassembly is lossless and (b) persisted-coarse == recomputed-coarse (the
 * whole point of persisting the coarse levels directly).
 *
 * <p>Lives in the {@code hpa} package to reach {@link RegionFragments}'s package-private setters + the
 * {@link FragmentBuilder} (as {@code CostCodecTest} does) when seeding cost leaves; the {@link ResourcePyramid}
 * uses its public API. The per-record fragment bitstream itself is already covered by {@code CostCodecTest};
 * this exercises the FILE wrapper — the multi-shard/per-level framing, the coarse files, the gzip body, coord
 * round-trip, the live-world-wins guard, resource sparsity, and the reserved invalidation section.
 */
public class RegionPersistenceRoundTripTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;

    /** Cost pyramid rolls up to level 6; resource to level 21. */
    private static final int COST_TOP = RegionAddress.MAX_COARSE_LEVEL;               // 6
    private static final int RES_TOP = ResourcePyramid.RESOURCE_TOP_LEVEL;            // 21

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
        FragmentBuilder.build(passable, standable, null, G, passCount, standCount, ignore, hardnessSumSolid, solidCount, rf);
        p.setBuilt(0, row, true);
    }

    // ---- shard-aware flush/reload of a whole pyramid (mirrors RegionPersistence.flushDimension) --------

    /** L5 shard key packing, identical to {@code RegionPersistence.shardKey}. */
    private static long shardKey(int sx, int sz) {
        return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
    }

    /** Shards holding a built cost row across levels 0..5. */
    private static Set<Long> costShards(CostPyramid p) {
        Set<Long> shards = new HashSet<>();
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) continue;
                shards.add(shardKey(RegionAddress.shardOf(p.rowRX(level, r), level),
                                    RegionAddress.shardOf(p.rowRZ(level, r), level)));
            }
        }
        return shards;
    }

    private static Set<Long> resourceShards(ResourcePyramid p) {
        Set<Long> shards = new HashSet<>();
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!p.isBuilt(level, r)) continue;
                shards.add(shardKey(RegionAddress.shardOf(p.rowRX(level, r), level),
                                    RegionAddress.shardOf(p.rowRZ(level, r), level)));
            }
        }
        return shards;
    }

    /** Flush {@code src} to per-shard + coarse byte blobs and reload them (no mergeUp) into a fresh pyramid. */
    private static CostPyramid flushReloadCost(CostPyramid src) throws IOException {
        CostPyramid back = new CostPyramid();
        for (long key : costShards(src)) {
            int sx = (int) (key >> 32), sz = (int) key;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CostPyramidCodec.encodeShard(src, sx, sz, bos);
            CostPyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), back);
        }
        ByteArrayOutputStream coarse = new ByteArrayOutputStream();
        CostPyramidCodec.encodeCoarse(src, coarse);
        CostPyramidCodec.decode(new ByteArrayInputStream(coarse.toByteArray()), back);
        return back;
    }

    private static ResourcePyramid flushReloadResource(ResourcePyramid src) throws IOException {
        ResourcePyramid back = new ResourcePyramid();
        for (long key : resourceShards(src)) {
            int sx = (int) (key >> 32), sz = (int) key;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ResourcePyramidCodec.encodeShard(src, sx, sz, bos);
            ResourcePyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), back);
        }
        ByteArrayOutputStream coarse = new ByteArrayOutputStream();
        ResourcePyramidCodec.encodeCoarse(src, coarse);
        ResourcePyramidCodec.decode(new ByteArrayInputStream(coarse.toByteArray()), back);
        return back;
    }

    // ---- structural equality -------------------------------------------------------------------------

    private static byte[] packRow(CostPyramid p, int level, int row) {
        RegionFragments rf = p.fragmentRecord(level, row);
        int nbytes = (CostCodec.regionBitLength(rf) + 7) >> 3;
        byte[] buf = new byte[nbytes];
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

    private static void assertCostIdentical(CostPyramid src, CostPyramid back, int maxLevel) {
        for (int level = 0; level <= maxLevel; level++) {
            int rows = src.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!src.isBuilt(level, r) || src.fragmentRecord(level, r) == null) continue;
                int rx = src.rowRX(level, r), ry = src.rowRY(level, r), rz = src.rowRZ(level, r);
                int br = back.rowIfPresent(level, rx, ry, rz);
                assertTrue(br != -1, "cost L" + level + " (" + rx + "," + ry + "," + rz + ") must reload");
                assertTrue(back.isBuilt(level, br), "reloaded cost L" + level + " row must be built");
                assertArrayEquals(packRow(src, level, r), packRow(back, level, br),
                        "cost L" + level + " (" + rx + "," + ry + "," + rz + ") record bytes");
            }
            assertEquals(builtCostRows(src, level), builtCostRows(back, level),
                    "cost L" + level + " built-row count");
        }
    }

    private static int builtResourceRows(ResourcePyramid p, int level) {
        int n = 0, rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(level, r)) n++;
        }
        return n;
    }

    private static void assertResourceIdentical(ResourcePyramid src, ResourcePyramid back, int maxLevel) {
        int cols = ResourceClasses.COLUMN_COUNT;
        for (int level = 0; level <= maxLevel; level++) {
            int rows = src.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!src.isBuilt(level, r)) continue;
                int rx = src.rowRX(level, r), ry = src.rowRY(level, r), rz = src.rowRZ(level, r);
                int br = back.rowIfPresent(level, rx, ry, rz);
                assertTrue(br != -1, "res L" + level + " (" + rx + "," + ry + "," + rz + ") must reload");
                assertTrue(back.isBuilt(level, br), "reloaded res L" + level + " row must be built");
                for (int c = 0; c < cols; c++) {
                    assertEquals(src.getLog2(level, r, c), back.getLog2(level, br, c),
                            "res L" + level + " (" + rx + "," + ry + "," + rz + ") col " + c);
                }
            }
            assertEquals(builtResourceRows(src, level), builtResourceRows(back, level),
                    "res L" + level + " built-row count");
        }
    }

    // ===================================================================================================
    // The headline test: multi-shard, multi-section leaves + real mergeUp coarse → flush → reload → identical.
    // ===================================================================================================
    @Test
    void shardedRoundTrip_persistedCoarseEqualsRecomputedCoarse() throws IOException {
        CostPyramid cp = new CostPyramid();
        ResourcePyramid rp = new ResourcePyramid();

        // Leaves spanning THREE L5 shards (chunkX>>5): shard 0 (chunkX 2,5), shard 1 (chunkX 40,45),
        // shard -1 (chunkX -5) — and multiple vertical sections (ry 0, 3, 5, 31).
        seedUniform(cp, 2, 0, 3, RegionFragments.KIND_SOLID, 6);
        seedUniform(cp, 2, 31, 3, RegionFragments.KIND_AIR, 0);
        seedMixed(cp, 5, 3, 7, new int[] { 4, 12 });                       // 2 fragments
        seedUniform(cp, 40, 0, 3, RegionFragments.KIND_WATER, 0);          // shard 1
        seedMixed(cp, 45, 5, 7, new int[] { 8 });                          // shard 1, 1 fragment
        seedUniform(cp, -5, 2, 3, RegionFragments.KIND_SOLID, 8);          // shard -1

        // Resource tallies at a subset of the same sections, across the same shards.
        seedResource(rp, 2, 0, 3, new int[][] { {1, 5}, {7, 2} });
        seedResource(rp, 5, 3, 7, new int[][] { {0, 3} });
        seedResource(rp, 40, 0, 3, new int[][] { {ResourceClasses.COLUMN_COUNT - 1, 9} });
        seedResource(rp, -5, 2, 3, new int[][] { {2, 1}, {11, 4} });

        // Sanity: the seed really does span multiple shards (so sharding is exercised, not a degenerate 1 shard).
        assertTrue(costShards(cp).size() >= 3, "test must span >=3 cost shards");
        assertTrue(resourceShards(rp).size() >= 3, "test must span >=3 resource shards");

        // Populate the coarse levels with the REAL merge drivers (production incremental path), then snapshot by
        // flush→reload. mergeUp per built leaf: cost → level 6, resource → level 21.
        for (int r = 0; r < cp.rowCount(0); r++) {
            if (cp.isBuilt(0, r)) {
                PyramidMerger.mergeUpFragments(cp, cp.rowRX(0, r), cp.rowRY(0, r), cp.rowRZ(0, r));
            }
        }
        for (int r = 0; r < rp.rowCount(0); r++) {
            if (rp.isBuilt(0, r)) {
                ResourceMerger.mergeUpTallies(rp, rp.rowRX(0, r), rp.rowRY(0, r), rp.rowRZ(0, r));
            }
        }

        // The coarse files must actually carry rows (else the "persist coarse directly" claim is untested).
        assertTrue(cp.rowCount(COST_TOP) > 0, "cost must have level-6 rows after mergeUp");
        assertTrue(rp.rowCount(RES_TOP) > 0, "resource must have top-level rows after mergeUp");

        // FLUSH via the sharded writer, RELOAD via the sharded reader (direct — no mergeUp on `back`).
        CostPyramid costBack = flushReloadCost(cp);
        ResourcePyramid resBack = flushReloadResource(rp);

        // The reloaded pyramids must be structurally identical to the pre-flush ones at EVERY level, incl. the
        // coarse levels that were loaded directly (never re-merged) — proving persisted-coarse == recomputed.
        assertCostIdentical(cp, costBack, COST_TOP);
        assertResourceIdentical(rp, resBack, RES_TOP);
    }

    private static void seedResource(ResourcePyramid p, int rx, int ry, int rz, int[][] colVals) {
        int row = p.rowFor(0, rx, ry, rz);
        for (int[] cv : colVals) {
            p.setLog2(0, row, cv[0], (byte) cv[1]);
        }
        p.setBuilt(0, row, true);
    }

    // ===================================================================================================
    // Scan-once bucket byte-identity: the periodic flush's O(dirtyShards*rows) -> O(rows) rewrite changes only
    // HOW rows are gathered — encodeShardBucket MUST emit byte-for-byte the same shard file as encodeShard.
    // ===================================================================================================
    @Test
    void scanOnceBucket_isByteIdenticalToPerShardEncode() throws IOException {
        CostPyramid cp = new CostPyramid();
        ResourcePyramid rp = new ResourcePyramid();

        // Multi-shard, multi-section, multi-fragment — and three consecutive-ry SOLID leaves in one column so the
        // bucket path exercises the ry column-run collapse, plus mergeUp so levels 1..5 carry rows too.
        seedUniform(cp, 2, 0, 3, RegionFragments.KIND_SOLID, 6);
        seedUniform(cp, 2, 1, 3, RegionFragments.KIND_SOLID, 6);
        seedUniform(cp, 2, 2, 3, RegionFragments.KIND_SOLID, 6);
        seedUniform(cp, 2, 31, 3, RegionFragments.KIND_AIR, 0);
        seedMixed(cp, 5, 3, 7, new int[] { 4, 12 });
        seedUniform(cp, 40, 0, 3, RegionFragments.KIND_WATER, 0);
        seedMixed(cp, 45, 5, 7, new int[] { 8 });
        seedUniform(cp, -5, 2, 3, RegionFragments.KIND_SOLID, 8);

        seedResource(rp, 2, 0, 3, new int[][] { {1, 5}, {7, 2} });
        seedResource(rp, 2, 1, 3, new int[][] { {1, 5}, {7, 2} });
        seedResource(rp, 5, 3, 7, new int[][] { {0, 3} });
        seedResource(rp, 40, 0, 3, new int[][] { {ResourceClasses.COLUMN_COUNT - 1, 9} });
        seedResource(rp, -5, 2, 3, new int[][] { {2, 1}, {11, 4} });

        for (int r = 0; r < cp.rowCount(0); r++) {
            if (cp.isBuilt(0, r)) PyramidMerger.mergeUpFragments(cp, cp.rowRX(0, r), cp.rowRY(0, r), cp.rowRZ(0, r));
        }
        for (int r = 0; r < rp.rowCount(0); r++) {
            if (rp.isBuilt(0, r)) ResourceMerger.mergeUpTallies(rp, rp.rowRX(0, r), rp.rowRY(0, r), rp.rowRZ(0, r));
        }

        // Cost: the bucket's shard set must match enumerate, and each shard's bucket bytes == encodeShard bytes —
        // for BOTH the whole-dimension bucketShards (the interim oracle) AND the index-driven per-shard bucketShard
        // (the live periodic-flush path). Both must be byte-for-byte the scanning encodeShard.
        Map<Long, CostPyramidCodec.ShardColumns> costBuckets = CostPyramidCodec.bucketShards(cp);
        assertEquals(costShards(cp), costBuckets.keySet(), "cost bucket shard set must match the enumerate");
        for (long key : costShards(cp)) {
            int sx = (int) (key >> 32), sz = (int) key;
            ByteArrayOutputStream perShard = new ByteArrayOutputStream();
            CostPyramidCodec.encodeShard(cp, sx, sz, perShard);
            ByteArrayOutputStream bucket = new ByteArrayOutputStream();
            CostPyramidCodec.encodeShardBucket(costBuckets.get(key), bucket);
            assertArrayEquals(perShard.toByteArray(), bucket.toByteArray(),
                    "cost shard (" + sx + "," + sz + ") bucketShards bytes must equal encodeShard bytes");
            // Index-driven gather (drainDimension's actual path) — same bytes.
            ByteArrayOutputStream idx = new ByteArrayOutputStream();
            CostPyramidCodec.encodeShardBucket(CostPyramidCodec.bucketShard(cp, key), idx);
            assertArrayEquals(perShard.toByteArray(), idx.toByteArray(),
                    "cost shard (" + sx + "," + sz + ") index bucketShard bytes must equal encodeShard bytes");
        }

        // Resource: same byte-identity per shard, for both the whole-dim bucketShards and the index bucketShard.
        Map<Long, ResourcePyramidCodec.ShardRows> resBuckets = ResourcePyramidCodec.bucketShards(rp);
        assertEquals(resourceShards(rp), resBuckets.keySet(), "resource bucket shard set must match the enumerate");
        for (long key : resourceShards(rp)) {
            int sx = (int) (key >> 32), sz = (int) key;
            ByteArrayOutputStream perShard = new ByteArrayOutputStream();
            ResourcePyramidCodec.encodeShard(rp, sx, sz, perShard);
            ByteArrayOutputStream bucket = new ByteArrayOutputStream();
            ResourcePyramidCodec.encodeShardBucket(resBuckets.get(key), bucket);
            assertArrayEquals(perShard.toByteArray(), bucket.toByteArray(),
                    "resource shard (" + sx + "," + sz + ") bucketShards bytes must equal encodeShard bytes");
            // Index-driven gather (drainDimension's actual path) — same bytes.
            ByteArrayOutputStream idx = new ByteArrayOutputStream();
            ResourcePyramidCodec.encodeShardBucket(ResourcePyramidCodec.bucketShard(rp, key), idx);
            assertArrayEquals(perShard.toByteArray(), idx.toByteArray(),
                    "resource shard (" + sx + "," + sz + ") index bucketShard bytes must equal encodeShard bytes");
        }
    }

    // ===================================================================================================
    // Live world wins (§6): decode must NOT clobber a row already built this session (cost + resource).
    // ===================================================================================================
    @Test
    void decodeDoesNotClobberLiveRows() throws IOException {
        CostPyramid src = new CostPyramid();
        seedUniform(src, 2, 3, 4, RegionFragments.KIND_SOLID, 6);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(src, RegionAddress.shardOf(2, 0), RegionAddress.shardOf(4, 0), bos);

        CostPyramid live = new CostPyramid();
        seedUniform(live, 2, 3, 4, RegionFragments.KIND_AIR, 0); // a different, live value
        CostPyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), live);
        assertEquals(RegionFragments.KIND_AIR, live.fragmentRecord(0, live.rowIfPresent(0, 2, 3, 4)).kind(),
                "live-built cost leaf must win over the persisted one");

        ResourcePyramid rsrc = new ResourcePyramid();
        int rr = rsrc.rowFor(0, 5, 5, 5);
        rsrc.setLog2(0, rr, 3, (byte) 8);
        rsrc.setBuilt(0, rr, true);
        ByteArrayOutputStream rbos = new ByteArrayOutputStream();
        ResourcePyramidCodec.encodeShard(rsrc, RegionAddress.shardOf(5, 0), RegionAddress.shardOf(5, 0), rbos);

        ResourcePyramid rlive = new ResourcePyramid();
        int lr = rlive.rowFor(0, 5, 5, 5);
        rlive.setLog2(0, lr, 3, (byte) 1);
        rlive.setBuilt(0, lr, true);
        ResourcePyramidCodec.decode(new ByteArrayInputStream(rbos.toByteArray()), rlive);
        assertEquals((byte) 1, rlive.getLog2(0, rlive.rowIfPresent(0, 5, 5, 5), 3),
                "live-built resource tally must win over the persisted one");
    }

    // ===================================================================================================
    // An interned-but-unbuilt row is never persisted; an all-zero built resource row still round-trips.
    // ===================================================================================================
    @Test
    void unbuiltRowsNotPersisted_zeroBuiltResourceRoundTrips() throws IOException {
        CostPyramid cp = new CostPyramid();
        seedUniform(cp, 3, 0, 3, RegionFragments.KIND_SOLID, 6);
        cp.rowFor(0, 3, 1, 3); // interned but never built — must NOT persist
        CostPyramid cb = flushReloadCost(cp);
        assertEquals(-1, cb.rowIfPresent(0, 3, 1, 3), "an unbuilt cost leaf is not persisted");
        assertTrue(cb.rowIfPresent(0, 3, 0, 3) != -1, "a built cost leaf is persisted");

        ResourcePyramid rp = new ResourcePyramid();
        int z = rp.rowFor(0, 3, 0, 3); // all-zero but BUILT — must round-trip
        rp.setBuilt(0, z, true);
        rp.rowFor(0, 3, 1, 3);          // interned but unbuilt — must NOT persist
        ResourcePyramid rb = flushReloadResource(rp);
        assertTrue(rb.rowIfPresent(0, 3, 0, 3) != -1 && rb.isBuilt(0, rb.rowIfPresent(0, 3, 0, 3)),
                "a built all-zero resource tally round-trips");
        assertEquals(-1, rb.rowIfPresent(0, 3, 1, 3), "an unbuilt resource tally is not persisted");
    }

    // ===================================================================================================
    // Version gating: a pre-v7 file (v6 = the pre-typed-fragments layout, no per-fragment type bits and
    // stale keep-all populations) must read as ABSENT (cache-miss → rebuild from live), never mis-decode.
    // ===================================================================================================
    @Test
    void staleV6File_isRejectedAsCacheMiss() throws IOException {
        CostPyramid src = new CostPyramid();
        seedUniform(src, 2, 3, 4, RegionFragments.KIND_SOLID, 6);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(src, RegionAddress.shardOf(2, 0), RegionAddress.shardOf(4, 0), bos);
        byte[] bytes = bos.toByteArray();
        bytes[4] = 0;
        bytes[5] = 6; // patch the big-endian version short back to v6

        CostPyramid dest = new CostPyramid();
        boolean threw = false;
        try {
            CostPyramidCodec.decode(new ByteArrayInputStream(bytes), dest);
        } catch (IOException expected) {
            threw = true;
        }
        assertTrue(threw, "a v6 file must throw under the v7 typed-fragments codec (cache semantics)");
        assertEquals(-1, dest.rowIfPresent(0, 2, 3, 4), "nothing is interned from a rejected file");
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
