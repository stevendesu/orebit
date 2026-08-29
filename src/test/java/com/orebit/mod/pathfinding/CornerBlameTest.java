package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;

/**
 * Blame-side corner machinery (DESIGN-region-corner-crossing-v2.md §4.6, R17 — §6 items 7 and 10): the
 * corner-run COLLAPSE ({@link PathPlan#collapseCornerRun}) must emit the DIAGONAL {@code (A,fragA) →
 * (D,fragD)} pair no matter which chain hop the blame walk landed on — the virtual intermediates carry no
 * stable identity across {@code (A, D)} pairs, so a row naming one would kill every corner through that
 * intermediate — and {@link PathPlan#blameHop}'s {@code lo}-anchor must skip corner-cut steps exactly as it
 * skips V (a chain node sits in a real region the bot can never occupy). Pure static logic over hand-built
 * skeletons, the {@link BlameHopTest} substrate.
 */
class CornerBlameTest {

    private static final int C = RegionPathfinder.CORNER_FRAG;

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

    private static long key(RegionPathPlan sk, int i) {
        return RegionPathfinder.fragmentNodeKey(sk.rx(i), sk.ry(i), sk.rz(i), sk.fragmentId(i));
    }

    @Test
    void collapse_blameOnTheFirstChainHop_emitsTheDiagonalPair() {
        // A.1 → B.CORNER → D.2 (the X-then-Z chain); the blame walk lands on A → B.
        RegionPathPlan plan = sk(0, new int[] {1, 4, 1, 1}, new int[] {2, 4, 1, C}, new int[] {2, 4, 2, 2});
        long[] out = new long[2];
        PathPlan.collapseCornerRun(plan, 0, out);
        assertEquals(key(plan, 0), out[0], "FROM = A, the real step before the run");
        assertEquals(key(plan, 2), out[1], "TO = D, the real step after the run — never B.CORNER");
    }

    @Test
    void collapse_vertexChain_everyBlamedHopCollapsesToTheSamePair() {
        // A.1 → B.CORNER → C.CORNER → D.3 (the 3-axis vertex chain).
        RegionPathPlan plan = sk(0, new int[] {1, 4, 1, 1}, new int[] {2, 4, 1, C},
                new int[] {2, 5, 1, C}, new int[] {2, 5, 2, 3});
        long[] out = new long[2];
        for (int hop = 0; hop <= 2; hop++) {
            PathPlan.collapseCornerRun(plan, hop, out);
            assertEquals(key(plan, 0), out[0], "hop " + hop + ": FROM collapses back to A");
            assertEquals(key(plan, 3), out[1], "hop " + hop + ": TO collapses forward to D");
        }
    }

    @Test
    void collapse_fromWalkRunsPastWindowStart() {
        // The chain begins BEFORE the window: prefix.0, A.1, B.CORNER, D.2 — a blame on hop 2 (B → D)
        // under windowStart 2 must still recover A at index 1 (key CONSTRUCTION, not blame selection —
        // §4.6's "do not clamp it to lo"). collapseCornerRun takes no windowStart at all, which IS the
        // property; this pins the walk actually reaching back across it.
        RegionPathPlan plan = sk(0, new int[] {0, 4, 1, 0}, new int[] {1, 4, 1, 1},
                new int[] {2, 4, 1, C}, new int[] {2, 4, 2, 2});
        long[] out = new long[2];
        PathPlan.collapseCornerRun(plan, 2, out);
        assertEquals(key(plan, 1), out[0], "FROM walked backward past the (conceptual) window start to A");
        assertEquals(key(plan, 3), out[1]);
    }

    @Test
    void collapse_ordinaryHopIsUntouched() {
        RegionPathPlan plan = sk(0, new int[] {0, 4, 1, 0}, new int[] {1, 4, 1, 1});
        long[] out = new long[2];
        PathPlan.collapseCornerRun(plan, 0, out);
        assertEquals(key(plan, 0), out[0], "no corner run — the hop's own endpoints are emitted");
        assertEquals(key(plan, 1), out[1]);
    }

    @Test
    void blameAnchor_neverLandsOnACornerStep() {
        // The search started in B's region (the corner intermediate). The lo-anchor walk scans hi → lo for
        // the last step sharing the start region; without the corner skip it would anchor ON the chain node
        // (a step the bot can never occupy) and mis-scope the walk to hop 1. With the skip, no non-corner
        // step matches the start region, lo stays windowStart, and hop 0 takes the blame (empty realized
        // set — the search never left its start region).
        RegionPathPlan plan = sk(0, new int[] {1, 4, 1, 1}, new int[] {2, 4, 1, C}, new int[] {2, 4, 2, 2});
        long startRegionRaw = com.orebit.mod.worldmodel.hpa.RegionAddress.packLevelKey(2, 4, 1); // B's region
        assertEquals(0, PathPlan.blameHop(plan, 0, 2, new long[0], 0, startRegionRaw),
                "the anchor skips the corner step (not a physical bot position) — blame stays at hop 0");
    }

    @Test
    void centerFallback_walksForwardPastACornerRun() {
        // R40's CENTER-fallback producer rule (review dim2 F2 — the census arm the design missed): the
        // window's far index lands on the chain and every real portal in the window proved unusable, so
        // the CENTER arm would have aimed the block search at centerOf(B) — the middle of the pure-air
        // intermediate — and handed a corner choice.step to the forward-slide/splice path. The walk lands
        // on the first real step PAST the run instead.
        RegionPathPlan plan = sk(0, new int[] {1, 4, 1, 1}, new int[] {2, 4, 1, C},
                new int[] {2, 5, 1, C}, new int[] {2, 5, 2, 2});
        assertEquals(3, WindowTargeting.centerFallbackStep(plan, 1),
                "a corner far-index walks FORWARD past the whole run to D");
        assertEquals(3, WindowTargeting.centerFallbackStep(plan, 2),
                "…from anywhere inside the run");
        assertEquals(0, WindowTargeting.centerFallbackStep(plan, 0),
                "a real far-index is untouched");
        assertEquals(3, WindowTargeting.centerFallbackStep(plan, 3),
                "the tail is real by the reconstruct trim and stays put");
    }
}
