package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;

/**
 * Unit tests for the Fix-A blame walk ({@link PathPlan#blameHop}) — pure static logic over hand-built
 * fragment-model {@link RegionPathPlan}s and hand-packed realized-crossing arrays (no {@code ServerLevel},
 * no grid). Covers: first/middle unrealized hop, the virtual-goal rule (approach→V unrealized by definition
 * on BLOCKED), the all-realized fallback (blame the hop INTO the target step), the intra-region dig rule
 * (judged by any-exit-realized), the no-onward-hop give-up, and the minY raw-Y key conversion.
 */
class BlameHopTest {

    private static final int V = RegionPathfinder.VIRTUAL_GOAL_FRAG;

    /** Fragment-model skeleton from {@code {rx,ry,rz,frag}} steps (portals irrelevant to the blame walk). */
    private static RegionPathPlan sk(int minY, int[]... steps) {
        int n = steps.length;
        int[] rxs = new int[n], rys = new int[n], rzs = new int[n], frags = new int[n];
        int[] px = new int[n], py = new int[n], pz = new int[n];
        for (int i = 0; i < n; i++) {
            rxs[i] = steps[i][0];
            rys[i] = steps[i][1];
            rzs[i] = steps[i][2];
            frags[i] = steps[i][3];
            px[i] = RegionPathPlan.NO_PORTAL;
            py[i] = RegionPathPlan.NO_PORTAL;
            pz[i] = RegionPathPlan.NO_PORTAL;
        }
        return new RegionPathPlan(rxs, rys, rzs, frags, px, py, pz, n, minY, true);
    }

    /** The raw {@code cell>>4} key of a minY-rebased region coord — the realized-set convention. */
    private static long raw(int rx, int ry, int rz, int minY) {
        return RegionAddress.packLevelKey(rx, (minY + (ry << RegionAddress.LEAF_BITS)) >> RegionAddress.LEAF_BITS, rz);
    }

    private static long[] edges(long... fromToPairs) {
        return fromToPairs;
    }

    @Test
    void firstHopUnrealizedIsBlamed() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0}, new int[] {3, 4, 0, 0});
        assertEquals(0, PathPlan.blameHop(plan, 0, 3, edges(), 0),
                "an empty realized set means the search never left the start region — blame hop 0");
    }

    @Test
    void firstRealizedHopIsExonerated_middleHopBlamed() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0}, new int[] {3, 4, 0, 0});
        long[] realized = edges(raw(0, 4, 0, 0), raw(1, 4, 0, 0));
        assertEquals(1, PathPlan.blameHop(plan, 0, 3, realized, 0),
                "hop 0 was realized; the first unrealized hop (1) takes the blame");
    }

    @Test
    void windowStartOffsetsTheWalk() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0}, new int[] {3, 4, 0, 0});
        assertEquals(1, PathPlan.blameHop(plan, 1, 3, edges(), 0),
                "the walk starts at the snapshotted windowStart, not 0");
    }

    @Test
    void allRealizedWithVirtualGoalTailBlamesTheApproachHop() {
        // The manual-run death loop: every real crossing realized, target = the V tail. Rule (a): on a
        // BLOCKED result the (approach → V) hop is unrealized by definition — reaching the goal cell would
        // have been a FOUND — so it takes the blame (and relaxVirtualGoal skips this approach on re-plan).
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0}, new int[] {2, 4, 0, V});
        long[] realized = edges(raw(0, 4, 0, 0), raw(1, 4, 0, 0));
        assertEquals(1, PathPlan.blameHop(plan, 0, 2, realized, 0),
                "the approach→V hop (size-2) is blamed when every earlier hop was realized");
    }

    @Test
    void earlierUnrealizedHopBeatsTheVirtualGoalRule() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0}, new int[] {2, 4, 0, V});
        assertEquals(0, PathPlan.blameHop(plan, 0, 2, edges(), 0),
                "an earlier genuinely-unrealized crossing is blamed before the V rule fires");
    }

    @Test
    void allRealizedPortalTargetBlamesTheHopIntoTheTargetStep() {
        // Rule (c): every window hop realized but the target cell unreached — reaching the crossing's far
        // side elsewhere on the face did not yield a route to the committed portal, so blame targetStep-1.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0}, new int[] {3, 4, 0, 0}, new int[] {4, 4, 0, 0});
        long[] realized = edges(
                raw(0, 4, 0, 0), raw(1, 4, 0, 0),
                raw(1, 4, 0, 0), raw(2, 4, 0, 0));
        assertEquals(1, PathPlan.blameHop(plan, 0, 2, realized, 0),
                "all hops up to the target step realized → blame the hop INTO the target step");
    }

    @Test
    void intraRegionDigHopWithoutRealizedExitIsBlamed() {
        // Hop 0 is a dig between two fragments of ONE region; the realized set is region-pair-coarse, so the
        // judgeable half is: no realized crossing EXITS the region ⇒ the search never got past the dig.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {0, 4, 0, 1}, new int[] {1, 4, 0, 0});
        assertEquals(0, PathPlan.blameHop(plan, 0, 2, edges(), 0),
                "no exit realized from the dig's region → the dig hop is blamed");
    }

    @Test
    void intraRegionDigHopWithRealizedExitIsExonerated() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {0, 4, 0, 1}, new int[] {1, 4, 0, 0});
        long[] realized = edges(raw(0, 4, 0, 0), raw(1, 4, 0, 0));
        assertEquals(1, PathPlan.blameHop(plan, 0, 2, realized, 0),
                "an exit crossing exonerates the dig at this granularity; the walk moves on (and the "
                        + "all-realized fallback blames the hop into the target step)");
    }

    @Test
    void noOnwardHopReturnsMinusOne() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0});
        assertEquals(-1, PathPlan.blameHop(plan, 1, 1, edges(), 0),
                "targetStep == windowStart → no onward hop — a genuine give-up");
        assertEquals(-1, PathPlan.blameHop(plan, 1, 0, edges(), 0),
                "a target behind the window start can blame nothing");
    }

    @Test
    void negativeMinYConvertsSkeletonRyToRawCellKeys() {
        // Overworld minY=-64: skeleton ry 0 spans world y -64..-49, whose raw cell>>4 is -4. A realized set
        // keyed with raw -4 must exonerate hop 0 — a wrong (unconverted) key would mis-blame it.
        RegionPathPlan plan = sk(-64, new int[] {0, 0, 0, 0}, new int[] {1, 0, 0, 0}, new int[] {2, 0, 0, 0});
        long[] realized = edges(
                RegionAddress.packLevelKey(0, -4, 0), RegionAddress.packLevelKey(1, -4, 0));
        assertEquals(1, PathPlan.blameHop(plan, 0, 2, realized, -64),
                "the minY-rebased skeleton ry must be converted to the raw cell key before matching");
    }
}
