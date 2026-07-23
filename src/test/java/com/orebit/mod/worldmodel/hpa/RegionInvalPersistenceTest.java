package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;

/**
 * Headless round-trip tests for the <b>v4 invalidation section</b> of the cost persistence format
 * (DESIGN-persisted-invalidation-memory.md §4 — Phase 2a): {@link RegionCrossingMemory} rows written into each
 * cost shard's trailing section (assign-to-FROM at the row's level) and into the per-dimension coarse file
 * (level-{@link RegionAddress#MAX_COARSE_LEVEL} rows), then merged back through {@code decode → record}. Follows
 * the {@link RegionPersistenceRoundTripTest} pattern: codec-level, no Minecraft server, pyramids hand-seeded via
 * the package-private {@link RegionFragments} setters. Also pins the block-change expiry
 * ({@link RegionCrossingMemory#evictLeafTouching}) — FROM-side, TO-side, and containing-coarse-cell eviction
 * with the FROM-shard re-dirty callback.
 */
public class RegionInvalPersistenceTest {

    private static final int G = 16;

    // ---- caps / sig helpers (mirror RegionCrossingMemoryTest) -----------------------------------------

    private static BotCaps walkOnly() {
        return new BotCaps(1, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f);
    }

    private static BotCaps breakPlace() {
        return new BotCaps(1, 3, 16, true, 100.0f, true, true, 255, false, 10000, 2.0f);
    }

    // ---- pyramid seeding (mirrors RegionPersistenceRoundTripTest) -------------------------------------

    private static void seedUniform(CostPyramid p, int rx, int ry, int rz, int kind, int hardness) {
        int row = p.rowFor(0, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(G);
        rf.setKind(kind);
        rf.setAvgSolidHardness(hardness);
        rf.setFragmentCount(0);
        p.setBuilt(0, row, true);
    }

    /** One fragment-node key, straight from the production packer. */
    private static long key(int rx, int ry, int rz, int frag) {
        return RegionPathfinder.fragmentNodeKey(rx, ry, rz, frag);
    }

    private static byte[] encodeShard(CostPyramid p, int sx, int sz, RegionCrossingMemory mem) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(p, sx, sz, mem, bos);
        return bos.toByteArray();
    }

    private static byte[] encodeCoarse(CostPyramid p, RegionCrossingMemory mem) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeCoarse(p, mem, bos);
        return bos.toByteArray();
    }

    private static void decodeInto(byte[] bytes, CostPyramid dest, RegionCrossingMemory mem) throws IOException {
        CostPyramidCodec.decode(new ByteArrayInputStream(bytes), dest, null, mem, BotCaps::sigDominates);
    }

    /** Index of the row {@code (from, to)} at {@code level} in {@code mem}, or −1. */
    private static int rowOf(RegionCrossingMemory mem, int level, long from, long to) {
        for (int i = 0; i < mem.count(level); i++) {
            if (mem.fromAt(level, i) == from && mem.toAt(level, i) == to) return i;
        }
        return -1;
    }

    private static void assertRow(RegionCrossingMemory mem, int level, long from, long to, long sig, int prov) {
        int i = rowOf(mem, level, from, to);
        assertTrue(i >= 0, "row (L" + level + ", " + from + " -> " + to + ") must be present");
        assertEquals(sig, mem.sigAt(level, i), "sig of the reloaded row");
        assertEquals(prov, mem.provAt(level, i), "provenance of the reloaded row");
    }

    // ===================================================================================================
    // The headline round-trip: L0–L5 rows in their FROM shard's file, L6 rows in the coarse file, with
    // level + sig + provenance intact — across positive AND negative shard coordinates.
    // ===================================================================================================
    @Test
    void invalSectionRoundTrip_shardScopedByFrom_andL6InCoarse() throws IOException {
        CostPyramid cp = new CostPyramid();
        // One built leaf per touched shard (a shard file exists only where cost rows exist — the production
        // flush precedent), plus mergeUp so the coarse file carries real L6 cost rows beside the L6 inval rows.
        seedUniform(cp, 2, 3, 3, RegionFragments.KIND_SOLID, 6);    // shard (0, 0)
        seedUniform(cp, 40, 5, 7, RegionFragments.KIND_AIR, 0);     // shard (1, 0)
        seedUniform(cp, -5, 2, 3, RegionFragments.KIND_SOLID, 8);   // shard (-1, 0)
        for (int r = 0; r < cp.rowCount(0); r++) {
            if (cp.isBuilt(0, r)) PyramidMerger.mergeUpFragments(cp, cp.rowRX(0, r), cp.rowRY(0, r), cp.rowRZ(0, r));
        }

        long sigWalk = walkOnly().realizabilitySig();
        long sigBreak = breakPlace().realizabilitySig();

        RegionCrossingMemory mem = new RegionCrossingMemory();
        // Shard (0,0): an L0 PROOF row and an L3 ROLLED_UP row (L3 region 1 -> shard 1>>2 = 0). ESCALATION
        // rows are session-only (filtered at encode — see escalationRows_areNotPersisted), so the coarse
        // round-trip row uses a persistable provenance.
        long f00 = key(2, 3, 3, 0), t00 = key(3, 3, 3, 1);
        long f03 = key(1, 0, 1, 0), t03 = key(1, 0, 2, 2);
        mem.record(0, f00, t00, sigWalk, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(3, f03, t03, sigBreak, RegionCrossingMemory.PROV_ROLLED_UP, BotCaps::sigDominates);
        // Shard (1,0): an L0 row (chunk 40 >> 5 = 1).
        long f10 = key(40, 5, 7, 1), t10 = key(41, 5, 7, 0);
        mem.record(0, f10, t10, sigBreak, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        // Shard (-1,0): an L0 row at negative coords (chunk -5 >> 5 = -1; exercises sign round-trip under the
        // level byte in the stored fromKey's high bits).
        long fn0 = key(-5, 2, 3, 0), tn0 = key(-6, 2, 3, 3);
        mem.record(0, fn0, tn0, sigWalk, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        // The coarse file: an L6 ROLLED_UP row (reserved provenance — must still round-trip).
        long f6 = key(0, 0, 0, 2), t6 = key(1, 0, 0, 0);
        mem.record(RegionAddress.MAX_COARSE_LEVEL, f6, t6, sigWalk,
                RegionCrossingMemory.PROV_ROLLED_UP, BotCaps::sigDominates);

        // Decode ONLY shard (0,0): exactly its two FROM-assigned rows appear (shard scoping is by FROM).
        CostPyramid back = new CostPyramid();
        RegionCrossingMemory memBack = new RegionCrossingMemory();
        decodeInto(encodeShard(cp, 0, 0, mem), back, memBack);
        assertEquals(2, memBack.total(), "shard (0,0) carries exactly its own FROM-assigned rows");
        assertRow(memBack, 0, f00, t00, sigWalk, RegionCrossingMemory.PROV_PROOF);
        assertRow(memBack, 3, f03, t03, sigBreak, RegionCrossingMemory.PROV_ROLLED_UP);

        // Decode the remaining shards + the coarse file: the full memory reassembles, levels/sigs/prov intact.
        decodeInto(encodeShard(cp, 1, 0, mem), back, memBack);
        decodeInto(encodeShard(cp, -1, 0, mem), back, memBack);
        decodeInto(encodeCoarse(cp, mem), back, memBack);
        assertEquals(mem.total(), memBack.total(), "every recorded row reloads across the shard + coarse files");
        assertRow(memBack, 0, f10, t10, sigBreak, RegionCrossingMemory.PROV_PROOF);
        assertRow(memBack, 0, fn0, tn0, sigWalk, RegionCrossingMemory.PROV_PROOF);
        assertRow(memBack, RegionAddress.MAX_COARSE_LEVEL, f6, t6, sigWalk, RegionCrossingMemory.PROV_ROLLED_UP);

        // Idempotence (the antichain dedup): re-decoding the same shard changes nothing — the load-merge can
        // safely run beside rows already recorded live this session.
        decodeInto(encodeShard(cp, 0, 0, mem), back, memBack);
        assertEquals(mem.total(), memBack.total(), "a re-decode merges idempotently (equal sigs dedup)");

        // And the cost rows themselves still round-trip (the section rides BEHIND an intact cost body).
        assertTrue(back.rowIfPresent(0, 2, 3, 3) != -1 && back.isBuilt(0, back.rowIfPresent(0, 2, 3, 3)),
                "cost rows load alongside the invalidation section");
    }

    // ===================================================================================================
    // Byte-identity: the periodic flush's bucket path must emit the same bytes as the per-shard encode for
    // the same shard + memory (the RoundTripTest byte-identity contract, extended to the inval section).
    // ===================================================================================================
    @Test
    void bucketEncode_isByteIdenticalToPerShardEncode_withInvalRows() throws IOException {
        CostPyramid cp = new CostPyramid();
        seedUniform(cp, 2, 3, 3, RegionFragments.KIND_SOLID, 6); // shard (0,0)
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, key(2, 3, 3, 0), key(3, 3, 3, 1), walkOnly().realizabilitySig(),
                RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(2, key(0, 0, 0, 1), key(0, 0, 1, 0), breakPlace().realizabilitySig(),
                RegionCrossingMemory.PROV_ROLLED_UP, BotCaps::sigDominates);
        // A session-only ESCALATION row in the same shard: BOTH encode paths must filter it identically, so
        // the byte-identity contract holds with it present.
        mem.record(1, key(1, 1, 1, 0), key(1, 1, 2, 0), walkOnly().realizabilitySig(),
                RegionCrossingMemory.PROV_ESCALATION, BotCaps::sigDominates);

        byte[] perShard = encodeShard(cp, 0, 0, mem);
        long shardKey = 0L; // shard (0,0) packed as RegionPersistence.shardKey(0,0)
        ByteArrayOutputStream bucket = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShardBucket(CostPyramidCodec.bucketShard(cp, shardKey), 0, 0, mem, bucket);
        assertArrayEquals(perShard, bucket.toByteArray(),
                "bucket-path shard bytes (incl. the inval section) must equal the per-shard encode");
    }

    // ===================================================================================================
    // The escalation persistence filter (the cascade-fix guard): PROV_ESCALATION rows are cascade-INFERRED
    // realized-blame picks — session-memory only. They must never reach disk (a wrong one would be re-seeded
    // into every future plan and turn a corridor bottleneck into a permanent false no-route), while PROOF
    // and ROLLED_UP rows persist unchanged.
    // ===================================================================================================
    @Test
    void escalationRows_areNotPersisted() throws IOException {
        CostPyramid cp = new CostPyramid();
        seedUniform(cp, 2, 3, 3, RegionFragments.KIND_SOLID, 6); // shard (0,0)
        long sig = walkOnly().realizabilitySig();

        RegionCrossingMemory mem = new RegionCrossingMemory();
        long fp = key(2, 3, 3, 0), tp = key(3, 3, 3, 1);   // L0 PROOF — persists
        long fe = key(0, 0, 0, 0), te = key(0, 0, 1, 0);   // L2 ESCALATION — session-only
        long fr = key(1, 1, 1, 0), tr = key(2, 1, 1, 0);   // L1 ROLLED_UP — persists
        mem.record(0, fp, tp, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(2, fe, te, sig, RegionCrossingMemory.PROV_ESCALATION, BotCaps::sigDominates);
        mem.record(1, fr, tr, sig, RegionCrossingMemory.PROV_ROLLED_UP, BotCaps::sigDominates);

        CostPyramid back = new CostPyramid();
        RegionCrossingMemory memBack = new RegionCrossingMemory();
        decodeInto(encodeShard(cp, 0, 0, mem), back, memBack);

        assertEquals(2, memBack.total(), "only the PROOF and ROLLED_UP rows reload — the escalation row is filtered");
        assertRow(memBack, 0, fp, tp, sig, RegionCrossingMemory.PROV_PROOF);
        assertRow(memBack, 1, fr, tr, sig, RegionCrossingMemory.PROV_ROLLED_UP);
        assertEquals(-1, rowOf(memBack, 2, fe, te), "the PROV_ESCALATION row must be absent from the reload");
    }

    // ===================================================================================================
    // The virtual-goal decode filter (journey-scoped rows must not be durable): a row whose TO fragment id
    // is not a real fragment (>= RegionFragments.MAX_FRAGMENTS = 62 — the reserved id 62 or the search's
    // VIRTUAL_GOAL_FRAG 63, which lives in the fragment-ID space, deliberately NOT MAX_FRAGMENTS since the
    // 63→62 cap change) carries no realized evidence and names only the goal REGION — not the goal cell —
    // so a persisted V-row from one goal would poison every later goal in that region. The record site no
    // longer writes them; decode drops any a LEGACY file still carries (self-cleaning on load), so they
    // never survive an encode → decode round-trip.
    // ===================================================================================================
    @Test
    void virtualGoalRows_doNotSurviveRoundTrip_legacyFilesSelfClean() throws IOException {
        CostPyramid cp = new CostPyramid();
        seedUniform(cp, 2, 3, 3, RegionFragments.KIND_SOLID, 6); // shard (0,0)
        long sig = walkOnly().realizabilitySig();

        RegionCrossingMemory mem = new RegionCrossingMemory();
        long fp = key(2, 3, 3, 0), tp = key(3, 3, 3, 1);   // normal L0 PROOF row — persists
        // A hand-recorded (approach → V) row: TO fragment = VIRTUAL_GOAL_FRAG (63). Simulates a legacy file
        // written before the onBlocked record-site scoping existed (the archived autotest worlds have them).
        long fv = key(2, 3, 3, 1);
        long tv = key(3, 3, 3, RegionPathfinder.VIRTUAL_GOAL_FRAG);
        mem.record(0, fp, tp, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, fv, tv, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);

        CostPyramid back = new CostPyramid();
        RegionCrossingMemory memBack = new RegionCrossingMemory();
        decodeInto(encodeShard(cp, 0, 0, mem), back, memBack);

        assertEquals(1, memBack.total(), "only the real crossing reloads — the virtual-goal row is dropped");
        assertRow(memBack, 0, fp, tp, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(-1, rowOf(memBack, 0, fv, tv),
                "a TO-fragment-63 (virtual goal) row must not survive the round-trip");
    }

    // ===================================================================================================
    // Section-level cache semantics: a sigSchemaVersion / graphClassId mismatch skips ONLY the section —
    // the cost rows above it still load; the memory just re-learns.
    // ===================================================================================================
    @Test
    void schemaOrGraphClassMismatch_skipsSectionButLoadsCostRows() throws IOException {
        CostPyramid cp = new CostPyramid();
        seedUniform(cp, 2, 3, 3, RegionFragments.KIND_SOLID, 6);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, key(2, 3, 3, 0), key(3, 3, 3, 1), walkOnly().realizabilitySig(),
                RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        byte[] good = encodeShard(cp, 0, 0, mem);
        // Section layout (LAST in the file): schema(1) + graphClass(1) + count(4) + 1 row (24) = 30 bytes.
        final int sectionLen = 1 + 1 + 4 + 24;

        byte[] badSchema = good.clone();
        badSchema[badSchema.length - sectionLen] = 99; // future sigSchemaVersion
        CostPyramid p1 = new CostPyramid();
        RegionCrossingMemory m1 = new RegionCrossingMemory();
        decodeInto(badSchema, p1, m1);
        assertEquals(0, m1.total(), "an unknown sig schema drops the section (re-learn)");
        assertTrue(p1.rowIfPresent(0, 2, 3, 3) != -1, "…but the cost rows above it still load");

        byte[] badGraph = good.clone();
        badGraph[badGraph.length - sectionLen + 1] = 7; // a capability-aware graph we don't model
        CostPyramid p2 = new CostPyramid();
        RegionCrossingMemory m2 = new RegionCrossingMemory();
        decodeInto(badGraph, p2, m2);
        assertEquals(0, m2.total(), "an unknown graph class drops the section (rows never transfer across graphs)");
        assertTrue(p2.rowIfPresent(0, 2, 3, 3) != -1, "…but the cost rows above it still load");
    }

    // ===================================================================================================
    // The empty section: a no-memory (legacy-form) encode round-trips, and the memory-less decode overloads
    // read a rows-carrying file without touching its section.
    // ===================================================================================================
    @Test
    void emptySection_andMemorylessDecode_roundTrip() throws IOException {
        CostPyramid cp = new CostPyramid();
        seedUniform(cp, 2, 3, 3, RegionFragments.KIND_SOLID, 6);

        // count=0 section (null memory AND an empty memory — same bytes).
        byte[] noMem = encodeShard(cp, 0, 0, null);
        assertArrayEquals(noMem, encodeShard(cp, 0, 0, new RegionCrossingMemory()),
                "null memory and empty memory encode identically");
        CostPyramid p1 = new CostPyramid();
        RegionCrossingMemory m1 = new RegionCrossingMemory();
        decodeInto(noMem, p1, m1);
        assertEquals(0, m1.total(), "a count=0 section merges nothing");
        assertTrue(p1.rowIfPresent(0, 2, 3, 3) != -1, "cost rows round-trip under an empty section");

        // The pre-existing decode overloads (no memory) must read a ROWS-carrying v4 file cleanly.
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, key(2, 3, 3, 0), key(3, 3, 3, 1), walkOnly().realizabilitySig(),
                RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        CostPyramid p2 = new CostPyramid();
        CostPyramidCodec.decode(new ByteArrayInputStream(encodeShard(cp, 0, 0, mem)), p2);
        assertTrue(p2.rowIfPresent(0, 2, 3, 3) != -1, "memory-less decode ignores the section, loads cost");

        // Coarse file with an empty section round-trips too.
        CostPyramid p3 = new CostPyramid();
        RegionCrossingMemory m3 = new RegionCrossingMemory();
        decodeInto(encodeCoarse(cp, null), p3, m3);
        assertEquals(0, m3.total());
    }

    // ===================================================================================================
    // Block-change expiry: a rebuilt leaf evicts FROM-side, TO-side, and containing-coarse-cell rows —
    // and reports each dropped row's (level, fromKey) so its FROM shard can be re-dirtied. Unrelated rows
    // survive.
    // ===================================================================================================
    @Test
    void expiry_evictsFromSide_toSide_andContainingCoarseRows() {
        long sig = walkOnly().realizabilitySig();
        RegionCrossingMemory mem = new RegionCrossingMemory();
        // The rebuilt leaf will be (5, 2, 5).
        long fromSide = key(5, 2, 5, 0);                       // L0, FROM = the rebuilt leaf
        long toSideFrom = key(9, 9, 9, 1);                     // L0, TO = the rebuilt leaf (FROM elsewhere —
        long toSideTo = key(5, 2, 5, 2);                       //   possibly a NEIGHBOURING shard's file)
        long l2From = key(1, 0, 1, 0);                         // L2 cell (5>>2, 2>>2, 5>>2) = (1,0,1) CONTAINS
        long l2To = key(2, 0, 1, 1);                           //   the leaf → conservative coarse eviction
        long l6From = key(0, 0, 0, 0);                         // L6 cell (0,0,0) contains the leaf too
        long l6To = key(1, 0, 0, 0);
        long survivorFrom = key(20, 1, 20, 0);                 // L0, unrelated
        long survivorTo = key(21, 1, 20, 0);
        mem.record(0, fromSide, key(6, 2, 5, 1), sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, toSideFrom, toSideTo, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(2, l2From, l2To, sig, RegionCrossingMemory.PROV_ESCALATION, BotCaps::sigDominates);
        mem.record(RegionAddress.MAX_COARSE_LEVEL, l6From, l6To, sig,
                RegionCrossingMemory.PROV_ROLLED_UP, BotCaps::sigDominates);
        mem.record(0, survivorFrom, survivorTo, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);

        List<long[]> reported = new ArrayList<>();
        int evicted = mem.evictLeafTouching(5, 2, 5, (level, fromKey) -> reported.add(new long[] { level, fromKey }));

        assertEquals(4, evicted, "FROM-side, TO-side, containing-L2 and containing-L6 rows all evict");
        assertEquals(1, mem.total(), "only the unrelated row survives");
        assertEquals(0, rowOf(mem, 0, survivorFrom, survivorTo), "the survivor is the unrelated crossing");
        assertEquals(4, reported.size(), "every evicted row is reported for FROM-shard re-dirtying");
        assertTrue(containsReport(reported, 0, fromSide), "FROM-side eviction reports its own fromKey");
        assertTrue(containsReport(reported, 0, toSideFrom),
                "TO-side eviction reports the row's FROM key (the shard that must re-flush)");
        assertTrue(containsReport(reported, 2, l2From), "containing-L2 eviction reported");
        assertTrue(containsReport(reported, RegionAddress.MAX_COARSE_LEVEL, l6From),
                "containing-L6 eviction reported (rides the coarse file)");

        // A rebuild elsewhere in the same L2 cell but a different leaf: the L0 rows of OTHER leaves are
        // untouched (exact leaf match at L0), while any remaining coarse row containing it would evict.
        int again = mem.evictLeafTouching(4, 3, 4, (level, fromKey) -> { });
        assertEquals(0, again, "a different leaf in the same coarse cell does not re-evict L0 rows of others");
        assertEquals(1, mem.total());
    }

    private static boolean containsReport(List<long[]> reported, int level, long fromKey) {
        for (long[] r : reported) {
            if (r[0] == level && r[1] == fromKey) return true;
        }
        return false;
    }

    // ===================================================================================================
    // Fragment-blind expiry: rows keyed by ANY fragment of the touched region evict (a rebuild may have
    // renumbered fragment ids, so region identity — not fragment identity — is the match).
    // ===================================================================================================
    @Test
    void expiry_matchesEveryFragmentOfTheTouchedRegion() {
        long sig = walkOnly().realizabilitySig();
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, key(5, 2, 5, 7), key(6, 2, 5, 3), sig,
                RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, key(5, 2, 5, 42), key(4, 2, 5, 0), sig,
                RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        assertEquals(2, mem.evictLeafTouching(5, 2, 5, null), "every fragment row of the region evicts");
        assertEquals(0, mem.total());
    }
}
