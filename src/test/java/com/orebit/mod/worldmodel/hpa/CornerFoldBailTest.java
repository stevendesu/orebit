package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;

/**
 * The roll-up fold's CORNER PROPERTY (review 2026-08-29, dim3 F1; {@link InvalidationRollup}'s class
 * header): since optimistic corner crossings shipped, a parent crossing can be realized by an emittable
 * §4.1 corner the face-only kill-set cannot see — so {@code foldOnce} must BAIL (no ROLLED_UP row, the
 * pre-existing safe degrade) whenever a corner-capable child-item pair spans the fold face. Without the
 * bail the fold records a durable, persisted false parent negative that R30 can never repair (the corner
 * was never refuted, so no diagonal row exists to trigger the un-merge).
 *
 * <p><b>Fixture (the dim3-F1 scenario):</b> parents A=(0,0,0), B=(1,0,0) at level 1, fold face +X.
 * A holds two connected open children — a1=(1,0,0) (faces +X,+Y) and a3=(1,1,0) (faces +X,−Y) — one mass;
 * B holds one open child b1=(2,0,0) (faces −X,+Y); everything else SOLID. The only structural face opening
 * is a1↔b1 (a3's +X pairs with the SOLID (2,1,0), so it contributes no constituent). a3 is DIAGONAL to b1
 * across the face (Δ=(+1,−1,0)) with full footprints — corner-capable iff both items are TYPE_S. The type
 * test is deliberately ITEM-level (§4.1 preconditions 2/4 test the child fragments the emitter would use),
 * so the typeless-a3 control folds exactly as before the corner arc.
 */
public class CornerFoldBailTest {

    private static final int G = 16;

    private static BotCaps walkOnly() {
        return new BotCaps(1, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f);
    }

    private static long key(int rx, int ry, int rz, int frag) {
        return InvalidationRollup.fragmentKey(rx, ry, rz, frag);
    }

    private static void seedOpen(CostPyramid p, int rx, int ry, int rz, int faceMaskBits, int typeBits) {
        int row = p.rowFor(0, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(0, row);
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
        rf.setFragmentTypes(0, typeBits);
        rf.setFragmentCount(1);
        p.setBuilt(0, row, true);
    }

    private static void seedSolid(CostPyramid p, int rx, int ry, int rz) {
        int row = p.rowFor(0, rx, ry, rz);
        RegionFragments rf = p.ensureFragments(0, row);
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_SOLID);
        rf.setAvgSolidHardness(6);
        rf.setFragmentCount(0);
        p.setBuilt(0, row, true);
    }

    private static CostPyramid fixture(int a3TypeBits) {
        CostPyramid p = new CostPyramid();
        seedOpen(p, 1, 0, 0, (1 << 1) | (1 << 3), RegionFragments.TYPE_S); // a1: +X,+Y
        seedOpen(p, 1, 1, 0, (1 << 1) | (1 << 2), a3TypeBits);             // a3: +X,−Y (the corner arm)
        seedOpen(p, 2, 0, 0, (1 << 0) | (1 << 3), RegionFragments.TYPE_S); // b1: −X,+Y
        for (int[] c : new int[][] { { 0, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 }, { 0, 1, 1 },
                                     { 1, 0, 1 }, { 1, 1, 1 },
                                     { 3, 0, 0 }, { 3, 1, 0 }, { 3, 0, 1 }, { 3, 1, 1 },
                                     { 2, 0, 1 }, { 2, 1, 1 }, { 2, 1, 0 } }) {
            seedSolid(p, c[0], c[1], c[2]);
        }
        for (int r = 0; r < p.rowCount(0); r++) {
            if (p.isBuilt(0, r)) {
                PyramidMerger.mergeUpFragments(p, p.rowRX(0, r), p.rowRY(0, r), p.rowRZ(0, r));
            }
        }
        return p;
    }

    private static int foldDeadA1B1(CostPyramid p) {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        long from = key(1, 0, 0, 0), to = key(2, 0, 0, 0);
        mem.record(0, from, to, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        return InvalidationRollup.foldFrom(p, mem, 0, from, to, sig, BotCaps::sigDominates);
    }

    @Test
    void cornerCapablePair_bailsTheFold() {
        assertEquals(0, foldDeadA1B1(fixture(RegionFragments.TYPE_S)),
                "a1↔b1 dead but the S-typed a3↔b1 corner could still realize the parent crossing — "
                        + "the face-only kill-set must not roll up (dim3 F1)");
    }

    @Test
    void typelessCornerArm_foldsAsBefore() {
        assertEquals(1, foldDeadA1B1(fixture(0)),
                "a typeless a3 is a corner the emitter's own §4.1 gates refuse — the fold proceeds");
    }
}
