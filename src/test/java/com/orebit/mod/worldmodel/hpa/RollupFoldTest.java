package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;

/**
 * Headless unit tests for the Phase-2b invalidation ROLL-UP fold ({@link InvalidationRollup}, §4b): a
 * newly-recorded proof completes (or fails to complete) its parent crossing's kill-set, producing (or
 * withholding) a {@link RegionCrossingMemory#PROV_ROLLED_UP} row — including the frontier property (an
 * unbuilt child never kills), the ESCALATION exclusion, the sig-dominance rule, multi-level recursion, the
 * expiry revive, and the persistence round-trip of fold-produced rows. Pyramids are hand-seeded via the
 * package-private {@link RegionFragments} setters and rolled up with the production
 * {@link PyramidMerger#mergeUpFragments} (the {@link RegionInvalPersistenceTest} pattern — no Minecraft).
 *
 * <h2>The two-constituent fixture</h2>
 * Parents A=(0,0,0) and B=(1,0,0) at level 1, crossing face +X. Each parent has two MIXED children open on
 * the shared face (A: (1,0,0) faces {+X,+Y} and (1,1,0) faces {+X,−Y}; B mirrored), vertically connected so
 * each parent rolls up to ONE fragment; every other child is SOLID. The parent crossing therefore has
 * exactly two constituents:
 * <pre>  c1 = (1,0,0,f0) → (2,0,0,f0)      c2 = (1,1,0,f0) → (2,1,0,f0)</pre>
 */
public class RollupFoldTest {

    private static final int G = 16;
    private static final long REGION_MASK = (1L << 49) - 1; // 2026-07 repack: region 0..48, frag 49..54

    private static BotCaps walkOnly() {
        return new BotCaps(1, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f);
    }

    private static BotCaps breakPlace() {
        return new BotCaps(1, 3, 16, true, 100.0f, true, true, 255, false, 10000, 2.0f);
    }

    private static long key(int rx, int ry, int rz, int frag) {
        return InvalidationRollup.fragmentKey(rx, ry, rz, frag);
    }

    /** Seed a MIXED node with ONE fragment carrying full-face footprints on the faces in {@code faceMaskBits}. */
    private static void seedOpen(CostPyramid p, int level, int rx, int ry, int rz, int faceMaskBits) {
        int row = p.rowFor(level, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        rf.setAvgSolidHardness(4);
        int[] packed = new int[6];
        for (int f = 0; f < 6; f++) {
            packed[f] = ((faceMaskBits >> f) & 1) != 0
                    ? RegionFragments.packFootprint(0, 15, 0, 15) : RegionFragments.NO_FACE;
        }
        rf.setFragment(0, faceMaskBits, packed);
        rf.setFragmentCount(1);
        p.setBuilt(level, row, true);
    }

    private static void seedSolid(CostPyramid p, int level, int rx, int ry, int rz) {
        int row = p.rowFor(level, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_SOLID);
        rf.setAvgSolidHardness(6);
        rf.setFragmentCount(0);
        p.setBuilt(level, row, true);
    }

    private static void mergeAllLeaves(CostPyramid p) {
        for (int r = 0; r < p.rowCount(0); r++) {
            if (p.isBuilt(0, r)) {
                PyramidMerger.mergeUpFragments(p, p.rowRX(0, r), p.rowRY(0, r), p.rowRZ(0, r));
            }
        }
    }

    /**
     * The two-constituent fixture (class doc). {@code omitB2} leaves B's second open child (2,1,0) UNBUILT
     * (the frontier variant) instead of seeding it.
     */
    private static CostPyramid twoConstituentFixture(boolean omitB2) {
        CostPyramid p = new CostPyramid();
        seedOpen(p, 0, 1, 0, 0, (1 << 1) | (1 << 3)); // A child, faces +X,+Y
        seedOpen(p, 0, 1, 1, 0, (1 << 1) | (1 << 2)); // A child, faces +X,−Y (connects down to the first)
        seedOpen(p, 0, 2, 0, 0, (1 << 0) | (1 << 3)); // B child, faces −X,+Y
        if (!omitB2) {
            seedOpen(p, 0, 2, 1, 0, (1 << 0) | (1 << 2)); // B child, faces −X,−Y
        }
        // Every other child of both parents is SOLID (no items — a wall).
        for (int[] c : new int[][] { { 0, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 }, { 0, 1, 1 },
                                     { 1, 0, 1 }, { 1, 1, 1 } }) {
            seedSolid(p, 0, c[0], c[1], c[2]);
        }
        for (int[] c : new int[][] { { 3, 0, 0 }, { 3, 1, 0 }, { 3, 0, 1 }, { 3, 1, 1 },
                                     { 2, 0, 1 }, { 2, 1, 1 } }) {
            seedSolid(p, 0, c[0], c[1], c[2]);
        }
        mergeAllLeaves(p);
        return p;
    }

    private static final long C1_FROM = key(1, 0, 0, 0), C1_TO = key(2, 0, 0, 0);
    private static final long C2_FROM = key(1, 1, 0, 0), C2_TO = key(2, 1, 0, 0);

    /** Index of the row {@code (from, to)} at {@code level}, or −1. */
    private static int rowOf(RegionCrossingMemory mem, int level, long from, long to) {
        for (int i = 0; i < mem.count(level); i++) {
            if (mem.fromAt(level, i) == from && mem.toAt(level, i) == to) return i;
        }
        return -1;
    }

    private static void record(RegionCrossingMemory mem, int level, long from, long to, long sig, int prov) {
        mem.record(level, from, to, sig, prov, BotCaps::sigDominates);
    }

    private static int fold(CostPyramid p, RegionCrossingMemory mem, int level, long from, long to, long sig) {
        return InvalidationRollup.foldFrom(p, mem, level, from, to, sig, BotCaps::sigDominates);
    }

    // ===================================================================================================
    // (1)+(2) The kill-set: one alive constituent withholds the parent row; completing the last one
    // records it — with the correct parent fragment ids, sig S0, and PROV_ROLLED_UP.
    // ===================================================================================================
    @Test
    void lastConstituentKillsParent_oneAliveDoesNot() {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();

        record(mem, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(0, fold(p, mem, 0, C1_FROM, C1_TO, sig), "c2 is still alive — the parent survives");
        assertEquals(0, mem.count(1));

        record(mem, 0, C2_FROM, C2_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(1, fold(p, mem, 0, C2_FROM, C2_TO, sig), "the last constituent completes the kill-set");
        assertEquals(1, mem.count(1), "exactly one parent row");
        // Both parents rolled up to a single fragment (id 0), so the parent keys are exact.
        int i = rowOf(mem, 1, key(0, 0, 0, 0), key(1, 0, 0, 0));
        assertTrue(i >= 0, "the parent row carries the parent cells' fragment-node keys");
        assertEquals(sig, mem.sigAt(1, i), "the ROLLED_UP row carries S0");
        assertEquals(RegionCrossingMemory.PROV_ROLLED_UP, mem.provAt(1, i));
        // Both L1 parents share the L2 cell (0,0,0) — the crossing is L2-internal, so recursion stops.
        assertEquals(0, mem.count(2), "a parent-internal crossing never recurses further");
    }

    @Test
    void intraRegionCrossing_neverFolds() {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        // A fragment→fragment mine crossing within one region has no crossing face — nothing to fold.
        long from = key(1, 0, 0, 0), to = key(1, 0, 0, 1);
        record(mem, 0, from, to, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(0, fold(p, mem, 0, from, to, sig));
        assertEquals(0, mem.count(1));
    }

    // ===================================================================================================
    // (3) Frontier property: an UNBUILT child reads as the optimistic full-face synthetic — an
    // always-alive constituent no recorded row can kill, so the fold never fires at the frontier.
    // ===================================================================================================
    @Test
    void unbuiltChild_neverKills() {
        CostPyramid p = twoConstituentFixture(true); // B's (2,1,0) left unbuilt
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        record(mem, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF);
        // Even a recorded row against the unbuilt cell's crossing does not count — optimism wins.
        record(mem, 0, C2_FROM, C2_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(0, fold(p, mem, 0, C1_FROM, C1_TO, sig));
        assertEquals(0, fold(p, mem, 0, C2_FROM, C2_TO, sig));
        assertEquals(0, mem.count(1), "an exploration-frontier fold must never kill the parent");
    }

    // ===================================================================================================
    // (3b) Vacuous kill-set: an all-sealed face (the A* would only propose dig edges there) enumerates
    // zero walk constituents — the fold must not kill on vacuous truth.
    // ===================================================================================================
    @Test
    void zeroConstituents_noKill() {
        CostPyramid p = new CostPyramid();
        seedOpen(p, 0, 1, 0, 0, 1 << 1); // A's lone opening toward B
        seedOpen(p, 0, 2, 0, 0, 1 << 1); // B's fragment exists but does NOT touch the shared −X face
        for (int[] c : new int[][] { { 0, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 }, { 0, 1, 1 },
                                     { 1, 1, 0 }, { 1, 0, 1 }, { 1, 1, 1 } }) {
            seedSolid(p, 0, c[0], c[1], c[2]);
        }
        for (int[] c : new int[][] { { 3, 0, 0 }, { 3, 1, 0 }, { 3, 0, 1 }, { 3, 1, 1 },
                                     { 2, 1, 0 }, { 2, 0, 1 }, { 2, 1, 1 } }) {
            seedSolid(p, 0, c[0], c[1], c[2]);
        }
        mergeAllLeaves(p);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = breakPlace().realizabilitySig();
        record(mem, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF); // a proven-dead DIG crossing
        assertEquals(0, fold(p, mem, 0, C1_FROM, C1_TO, sig), "no walk opening was enumerated — no kill");
        assertEquals(0, mem.count(1));
    }

    // ===================================================================================================
    // (4) PROV_ESCALATION rows neither trigger nor count: the escalation record site never invokes the
    // fold, and an escalation row for a constituent leaves it ALIVE in a fold triggered by a real proof.
    // ===================================================================================================
    @Test
    void escalationRows_neitherTriggerNorCount() {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();

        record(mem, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(0, fold(p, mem, 0, C1_FROM, C1_TO, sig));
        // c2 recorded as ESCALATION — the record site does NOT fold (mirrored here), and the row must not
        // count as a dead constituent when the c1 proof is re-evaluated.
        record(mem, 0, C2_FROM, C2_TO, sig, RegionCrossingMemory.PROV_ESCALATION);
        assertEquals(0, fold(p, mem, 0, C1_FROM, C1_TO, sig), "an ESCALATION row must not count as dead");
        assertEquals(0, mem.count(1));

        // A genuine proof for c2 (replacing the equal-sig escalation row is a no-op for record — same sig
        // dominates — so record the proof under a fresh memory to keep provenance clean).
        RegionCrossingMemory mem2 = new RegionCrossingMemory();
        record(mem2, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF);
        record(mem2, 0, C2_FROM, C2_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(1, fold(p, mem2, 0, C2_FROM, C2_TO, sig), "real proofs still complete the kill-set");
    }

    // ===================================================================================================
    // (5) Sig rule: a constituent dead only under a WEAKER sig does not kill for a stronger S0; one dead
    // under a DOMINATING sig does — and the parent row carries S0.
    // ===================================================================================================
    @Test
    void sigRule_weakerConstituentDoesNotKillForStrongerS0() {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long weak = walkOnly().realizabilitySig();
        long strong = breakPlace().realizabilitySig();
        assertTrue(BotCaps.sigDominates(strong, weak) && !BotCaps.sigDominates(weak, strong));

        record(mem, 0, C1_FROM, C1_TO, weak, RegionCrossingMemory.PROV_PROOF);
        record(mem, 0, C2_FROM, C2_TO, strong, RegionCrossingMemory.PROV_PROOF);
        assertEquals(0, fold(p, mem, 0, C2_FROM, C2_TO, strong),
                "c1's weak-sig proof does not bind the stronger S0 — the parent must survive");
        assertEquals(0, mem.count(1));
    }

    @Test
    void sigRule_dominatingConstituentKills_rowCarriesS0() {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long weak = walkOnly().realizabilitySig();
        long strong = breakPlace().realizabilitySig();

        record(mem, 0, C1_FROM, C1_TO, strong, RegionCrossingMemory.PROV_PROOF);
        record(mem, 0, C2_FROM, C2_TO, weak, RegionCrossingMemory.PROV_PROOF);
        assertEquals(1, fold(p, mem, 0, C2_FROM, C2_TO, weak),
                "c1's DOMINATING proof binds S0; c2's equal sig binds it too — the kill-set completes");
        int i = rowOf(mem, 1, key(0, 0, 0, 0), key(1, 0, 0, 0));
        assertTrue(i >= 0);
        assertEquals(weak, mem.sigAt(1, i), "the ROLLED_UP row carries S0 (the fold's sig), not the proofs'");
    }

    // ===================================================================================================
    // (6) Recursion: a deep chain crossing at the 63|64 boundary stays a distinct parent pair at every
    // level, so one L0 proof cascades a ROLLED_UP row all the way to MAX_COARSE_LEVEL.
    // ===================================================================================================

    /**
     * The DEEP chain fixture: one open L0 crossing (63,0,0)→(64,0,0) whose parent pair is distinct at every
     * level up to L6 (63|64 → 31|32 → … → 0|1). At each level the flush siblings around the chain are
     * SOLID (seeded at L0; hand-seeded coarse rows above — cells no merge ever touches), so every parent
     * crossing's kill-set is exactly the one chain constituent below it. Interior siblings stay unbuilt:
     * their optimistic synthetics join each parent's OTHER fragment (they never reach the chain fragment's
     * face), exercising the containment derivation against multi-fragment parents.
     */
    private static CostPyramid deepChainFixture() {
        CostPyramid p = new CostPyramid();
        seedOpen(p, 0, 63, 0, 0, 1 << 1); // +X only
        seedOpen(p, 0, 64, 0, 0, 1 << 0); // −X only
        int[][] sibs = { { 1, 0 }, { 0, 1 }, { 1, 1 } };
        for (int[] s : sibs) {
            seedSolid(p, 0, 63, s[0], s[1]);
            seedSolid(p, 0, 64, s[0], s[1]);
        }
        mergeAllLeaves(p);
        // Coarse flush siblings around the chain crossing at L1..L4 (octree: 3 per side) and L5 (quadtree:
        // 1 per side). Hand-seeded AFTER the merge — no merge walks these cells (they are not ancestors of
        // any seeded leaf), so the production-built chain records stay untouched.
        for (int L = 1; L <= 4; L++) {
            int xa = (1 << (6 - L)) - 1;
            int xb = 1 << (6 - L);
            for (int[] s : sibs) {
                seedSolid(p, L, xa, s[0], s[1]);
                seedSolid(p, L, xb, s[0], s[1]);
            }
        }
        seedSolid(p, 5, 1, 0, 1);
        seedSolid(p, 5, 2, 0, 1);
        return p;
    }

    @Test
    void recursion_cascadesToMaxCoarseLevel() {
        CostPyramid p = deepChainFixture();
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        long from = key(63, 0, 0, 0), to = key(64, 0, 0, 0);
        record(mem, 0, from, to, sig, RegionCrossingMemory.PROV_PROOF);

        assertEquals(RegionAddress.MAX_COARSE_LEVEL, fold(p, mem, 0, from, to, sig),
                "one ROLLED_UP row per level, L1..L" + RegionAddress.MAX_COARSE_LEVEL);
        for (int L = 1; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            assertEquals(1, mem.count(L), "exactly one row at L" + L);
            int xa = (1 << (6 - L)) - 1;
            int xb = 1 << (6 - L);
            assertEquals(RegionAddress.packLevelKey(xa, 0, 0), mem.fromAt(L, 0) & REGION_MASK,
                    "L" + L + " FROM region is the chain cell");
            assertEquals(RegionAddress.packLevelKey(xb, 0, 0), mem.toAt(L, 0) & REGION_MASK,
                    "L" + L + " TO region is the chain cell");
            assertEquals(RegionCrossingMemory.PROV_ROLLED_UP, mem.provAt(L, 0));
            assertEquals(sig, mem.sigAt(L, 0));
        }
    }

    // ===================================================================================================
    // (7) Revive: a leaf rebuild under the parent drops the ROLLED_UP ancestors (the existing
    // containing-coarse eviction — asserted here against fold-produced rows).
    // ===================================================================================================
    @Test
    void evictLeafTouching_dropsRolledUpAncestors() {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        record(mem, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF);
        record(mem, 0, C2_FROM, C2_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(1, fold(p, mem, 0, C2_FROM, C2_TO, sig));
        assertEquals(1, mem.count(1));

        // Rebuilding leaf (1,0,0) — under the parent — evicts its own L0 row AND the containing L1 theorem.
        int evicted = mem.evictLeafTouching(1, 0, 0, null);
        assertEquals(2, evicted, "the touching L0 row and the containing ROLLED_UP ancestor both evict");
        assertEquals(0, mem.count(1), "the ROLLED_UP ancestor is revived (dropped) by the leaf change");
        assertEquals(0, rowOf(mem, 0, C2_FROM, C2_TO), "the untouched sibling proof survives");
    }

    // ===================================================================================================
    // (8) Round-trip: a fold-produced ROLLED_UP row persists through the v4 invalidation section and
    // reloads with level, sig, and provenance intact.
    // ===================================================================================================
    @Test
    void foldProducedRow_roundTripsThroughCodec() throws IOException {
        CostPyramid p = twoConstituentFixture(false);
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        record(mem, 0, C1_FROM, C1_TO, sig, RegionCrossingMemory.PROV_PROOF);
        record(mem, 0, C2_FROM, C2_TO, sig, RegionCrossingMemory.PROV_PROOF);
        assertEquals(1, fold(p, mem, 0, C2_FROM, C2_TO, sig));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CostPyramidCodec.encodeShard(p, 0, 0, mem, bos); // every row's FROM falls in shard (0,0)
        CostPyramid back = new CostPyramid();
        RegionCrossingMemory memBack = new RegionCrossingMemory();
        CostPyramidCodec.decode(new ByteArrayInputStream(bos.toByteArray()), back, null, memBack,
                BotCaps::sigDominates);

        assertEquals(3, memBack.total(), "both proofs and the rolled-up theorem reload");
        int i = rowOf(memBack, 1, key(0, 0, 0, 0), key(1, 0, 0, 0));
        assertTrue(i >= 0, "the fold-produced L1 row reloads");
        assertEquals(sig, memBack.sigAt(1, i));
        assertEquals(RegionCrossingMemory.PROV_ROLLED_UP, memBack.provAt(1, i));
    }

    // ===================================================================================================
    // Key-layout pin: InvalidationRollup's hpa-local key packer must stay bit-identical to the production
    // RegionPathfinder.fragmentNodeKey (the keys memory rows are recorded and seeded by).
    // ===================================================================================================
    @Test
    void keyLayoutMatchesRegionPathfinder() {
        int[][] coords = { { 0, 0, 0, 0 }, { 63, 0, 0, 1 }, { -5, 2, 3, 7 }, { 1234, 31, -4321, 62 },
                           { -1, 1, -1, 63 } };
        for (int[] c : coords) {
            assertEquals(RegionPathfinder.fragmentNodeKey(c[0], c[1], c[2], c[3]),
                    InvalidationRollup.fragmentKey(c[0], c[1], c[2], c[3]),
                    "fragmentKey layout drifted from RegionPathfinder.fragmentNodeKey");
            assertEquals(c[3], InvalidationRollup.fragOf(InvalidationRollup.fragmentKey(c[0], c[1], c[2], c[3])));
        }
    }
}
