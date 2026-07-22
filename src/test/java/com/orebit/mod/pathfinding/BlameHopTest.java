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

    // ------------------------------------------------------------------------------------------------
    // Start-position blind spot (the treadmill fix): a search STARTING inside skeleton region S_k can
    // never realize a hop ending at-or-before k (cameFrom edges grow outward from the start — a boundary
    // edge INTO the start's own region never survives), so the walk begins at the LAST window index whose
    // region is the search-start region. The 6-arg blameHop carries that start region as a raw cell>>4
    // key; NO_START_REGION (the 5-arg form, all tests above) keeps the historical windowStart walk.
    // ------------------------------------------------------------------------------------------------

    @Test
    void treadmillShape_startRegionSkipsTheJustWalkedHop() {
        // The wall repro: [S0, S1, S2-up, S3], the failed search STARTED in S1's region, realized set
        // empty. The old walk blamed S0->S1 — the lateral crossing the bot had just physically walked
        // (a PROOF row against a valid crossing, every cycle: the treadmill). Hops ending at-or-before
        // the start's own step are unrealizable-by-construction; the blame must land on S1->S2 (the
        // ascent), never S0->S1.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {1, 5, 0, 0}, new int[] {2, 5, 0, 0});
        assertEquals(1, PathPlan.blameHop(plan, 0, 3, edges(), 0, raw(1, 4, 0, 0)),
                "the search started in S1's region — S0->S1 can never be realized by construction, so "
                        + "the ascent hop S1->S2 takes the blame");
    }

    @Test
    void startRegionMidWindow_earlierUnrealizedHopsBehindItAreSkipped() {
        // Start region = S2's region; hops 0 and 1 are unrealized (empty set) but end at-or-before the
        // start's step — behind the search, judged never-blamable. The walk begins at 2.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0}, new int[] {3, 4, 0, 0});
        assertEquals(2, PathPlan.blameHop(plan, 0, 3, edges(), 0, raw(2, 4, 0, 0)),
                "unrealized hops ending at-or-before the start's skeleton step are skipped");
    }

    @Test
    void startRegionRepeatedOnSkeleton_lastOccurrenceAnchorsTheWalk() {
        // A wiggle skeleton revisiting the start's region (A, B, A, C): the LAST index whose region is
        // the start region anchors the walk (index 2), so hops 0 and 1 are both skipped.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {0, 4, 0, 0}, new int[] {0, 5, 0, 0});
        assertEquals(2, PathPlan.blameHop(plan, 0, 3, edges(), 0, raw(0, 4, 0, 0)),
                "the LAST window step sharing the start region anchors the walk");
    }

    @Test
    void startRegionOffSkeleton_keepsTheWindowStartWalk() {
        // An off-route start (its region is on no window step): the historical behaviour — walk from
        // windowStart — is preserved, identical to the no-start-info 5-arg form.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0}, new int[] {3, 4, 0, 0});
        assertEquals(0, PathPlan.blameHop(plan, 0, 3, edges(), 0, raw(9, 9, 9, 0)),
                "a start region on no skeleton step keeps the historical windowStart walk");
        assertEquals(PathPlan.blameHop(plan, 0, 3, edges(), 0),
                PathPlan.blameHop(plan, 0, 3, edges(), 0, raw(9, 9, 9, 0)),
                "off-skeleton start is byte-identical to the 5-arg (no start info) walk");
    }

    @Test
    void startRegionIsTheTargetStep_noOnwardHopToBlame() {
        // Start region == the window's LAST (target) step's region: every window hop ends at-or-before
        // the start, so NO crossing is blamable — the failure is intra-region (the block tier could not
        // reach the target cell from inside its own region). This must be -1 (give-up semantics), NOT
        // the all-realized hop-into-target fallback: that fallback would blacklist a crossing the bot is
        // effectively already past (behind or at the search start), poisoning a valid hop.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 0, 0}, new int[] {1, 4, 0, 0},
                new int[] {2, 4, 0, 0});
        assertEquals(-1, PathPlan.blameHop(plan, 0, 2, edges(), 0, raw(2, 4, 0, 0)),
                "start region == target step region — no onward crossing exists to blame");
        // The realized content is irrelevant here: even a fully-realized window yields -1.
        long[] realized = edges(
                raw(0, 4, 0, 0), raw(1, 4, 0, 0),
                raw(1, 4, 0, 0), raw(2, 4, 0, 0));
        assertEquals(-1, PathPlan.blameHop(plan, 0, 2, realized, 0, raw(2, 4, 0, 0)),
                "the -1 is structural (every hop ends at-or-before the start), not realized-set-driven");
    }

    @Test
    void startRegionRespectsRawKeyConversionAtNegativeMinY() {
        // The start-region compare must happen in the SAME raw cell>>4 key space as the realized set:
        // overworld minY=-64, skeleton ry 0 ⇒ raw ry -4. A start key built raw (as blamedHopIndex builds
        // it from the floor cell: y>>4) must match the rebased skeleton step.
        RegionPathPlan plan = sk(-64, new int[] {0, 0, 0, 0}, new int[] {1, 0, 0, 0},
                new int[] {2, 0, 0, 0}, new int[] {3, 0, 0, 0});
        assertEquals(1, PathPlan.blameHop(plan, 0, 3, edges(), -64,
                        RegionAddress.packLevelKey(1, -4, 0)),
                "a raw start key (floor>>4) must match the minY-rebased skeleton step it stands in");
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
