package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The R40 boundary rule (DESIGN-region-corner-crossing-v2.md §4.5.1, §6 item 15): no index naming a
 * skeleton POSITION may name a corner-cut step — a chain node is not a place, and a boundary landing on
 * one surfaces three layers away as a finer level aiming at open air. {@link RegionPathPlan#splice} is the
 * enforcement point (the producers are corner-free by construction — {@code reconstructFragments} trims a
 * partial-best corner tail, the commit cursor is fragment-gated, the window target rides R32's
 * {@code NO_PORTAL}); a corner drop/join/tail is a caller bug and throws AT the boundary.
 */
class CornerSkeletonBoundaryTest {

    private static final int C = RegionPathfinder.CORNER_FRAG;

    private static RegionPathPlan sk(int[]... steps) {
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
        return new RegionPathPlan(rxs, rys, rzs, frags, px, py, pz, n, 0, true);
    }

    @Test
    void spliceThrowsWhenTheDropLandsOnACornerStep() {
        RegionPathPlan old = sk(new int[] {0, 0, 0, 0}, new int[] {1, 0, 0, C}, new int[] {1, 0, 1, 1});
        RegionPathPlan suffix = sk(new int[] {1, 0, 1, 1}, new int[] {2, 0, 1, 1});
        assertThrows(IllegalArgumentException.class, () -> RegionPathPlan.splice(old, 1, suffix),
                "a drop landing on the chain node must fail AT the boundary (R40)");
    }

    @Test
    void spliceThrowsWhenTheJoinOrTailIsACornerStep() {
        RegionPathPlan cornerTail = sk(new int[] {0, 0, 0, 0}, new int[] {1, 0, 0, C});
        RegionPathPlan suffix = sk(new int[] {1, 0, 0, C}, new int[] {1, 0, 1, 1});
        assertThrows(IllegalArgumentException.class, () -> RegionPathPlan.splice(cornerTail, 0, suffix),
                "a corner join (old tail == suffix[0] == CORNER) must fail at the boundary");

        RegionPathPlan clean = sk(new int[] {0, 0, 0, 0}, new int[] {1, 0, 0, 1});
        RegionPathPlan cornerSuffixTail = sk(new int[] {1, 0, 0, 1}, new int[] {2, 0, 0, C});
        assertThrows(IllegalArgumentException.class,
                () -> RegionPathPlan.splice(clean, 0, cornerSuffixTail),
                "a splice whose RESULT would end on a corner step must fail (a skeleton never ends on one)");
    }

    @Test
    void spliceKeepsAMidChainCornerVerbatim() {
        // The chain itself is a first-class skeleton run — only BOUNDARIES may not name it (INV-1: the kept
        // prefix is preserved verbatim, corner steps included).
        RegionPathPlan old = sk(new int[] {0, 0, 0, 0}, new int[] {1, 0, 0, C}, new int[] {1, 0, 1, 1});
        RegionPathPlan suffix = sk(new int[] {1, 0, 1, 1}, new int[] {2, 0, 1, 2});
        RegionPathPlan spliced = RegionPathPlan.splice(old, 0, suffix);
        assertEquals(4, spliced.size());
        assertEquals(C, spliced.fragmentId(1), "the mid-chain corner step splices through untouched");
        assertEquals(2, spliced.fragmentId(3));
    }
}
