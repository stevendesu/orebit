package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Headless search tests for OPTIMISTIC CORNER CROSSINGS (DESIGN-region-corner-crossing-v2.md §4, R8 —
 * §6 items 8/11/13 headless halves) over the {@link RegionScenarios} substrate: real
 * {@link FragmentBuilder} floods, no {@code ServerLevel}.
 *
 * <p>The fixture is §0's shape reduced to its region-tier minimum: two diagonal cavern-floor regions
 * {@code A = (0,1,0)} and {@code D = (1,1,1)} (each one TYPE_S fragment), the two orthogonal intermediates
 * pure {@code KIND_AIR}, everything else walled in known SOLID. A no-place bot has NO ordinary route (both
 * intermediates are air-gated) — before the corner arc this was skeleton NONE, the fail-closed total
 * refusal — and exactly one corner crossing qualifies under §4.1.
 */
class CornerCrossingSearchTest {

    private static final int G = RegionAddress.LEAF_SIZE;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int C = RegionPathfinder.CORNER_FRAG;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static void seed(RegionGrid grid, int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += 8; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, null, G, passCount, standCount, 0,
                hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private static void seedSolid(RegionGrid grid, int rx, int ry, int rz) {
        seed(grid, rx, ry, rz, new boolean[CELLS], new boolean[CELLS]);
    }

    private static void seedAir(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        seed(grid, rx, ry, rz, passable, new boolean[CELLS]);
    }

    private static void seedCavernFloor(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(grid, rx, ry, rz, passable, standable);
    }

    /** A MIXED, floorless (typeless-fragment) intermediate: all air except one interior pillar — the §2
     *  KIND_MIXED case AND the R35 crash substrate (a real record for the corner node to index). */
    private static void seedTypelessMixed(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int y = 0; y < G; y++) passable[idx(4, y, 4)] = false; // interior pillar ⇒ MIXED
        seed(grid, rx, ry, rz, passable, new boolean[CELLS]);      // nothing standable ⇒ typeless
    }

    /** The §0-shaped corner fixture. {@code mixedIntermediate} swaps B = (1,1,0) from uniform AIR to the
     *  typeless-MIXED form (R35's AIOOBE only reproduces over a real record). */
    private static RegionGrid cornerGrid(boolean mixedIntermediate) {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = -1; rx <= 2; rx++)
            for (int ry = 0; ry <= 2; ry++)
                for (int rz = -1; rz <= 2; rz++) seedSolid(grid, rx, ry, rz);
        seedCavernFloor(grid, 0, 1, 0);   // A
        seedCavernFloor(grid, 1, 1, 1);   // D
        if (mixedIntermediate) seedTypelessMixed(grid, 1, 1, 0);
        else seedAir(grid, 1, 1, 0);      // the chain's B
        seedAir(grid, 0, 1, 1);           // the other orthogonal intermediate
        return grid;
    }

    private static final BlockPos START = new BlockPos(8, 17, 8);    // feet over A's floor
    private static final BlockPos GOAL = new BlockPos(24, 17, 24);   // feet over D's floor

    @Test
    void noPlaceBot_getsTheCornerChain() {
        RegionPathPlan plan = RegionPathfinder.plan(null, cornerGrid(false), START, GOAL, BotCaps.DEFAULT);
        assertNotNull(plan, "the corner crossing turns §0's skeleton-NONE into a route");
        assertTrue(plan.reachedGoalRegion());
        // A → B.CORNER → D → V: the passable goal cell engages the virtual-goal machinery, so the tail is
        // V exactly as in RegionPathfinderFragmentTest's fixtures — the chain itself is one intermediate.
        assertEquals(4, plan.size(), "A → B.CORNER → D → V");
        assertEquals(C, plan.fragmentId(1), "the intermediate carries the corner-cut id");
        assertFalse(plan.hasPortal(1), "R32: a corner-cut step is NO_PORTAL — not a place");
        assertTrue(plan.fragmentId(0) < RegionFragments.MAX_FRAGMENTS, "R40: the skeleton begins real");
        assertTrue(plan.fragmentId(2) < RegionFragments.MAX_FRAGMENTS, "D is a real fragment");
        assertTrue(RegionPathfinder.isVirtualGoal(plan.fragmentId(3)), "the tail is V (goal approach)");
        // R15: D's portal = the corner column ∩ D's footprint — the corner cell's feet space.
        assertEquals(new BlockPos(16, 17, 16), plan.portalCell(2),
                "the D step aims the block tier at the corner column, floor-anchored");
        int[] stats = RegionPathfinder.lastCornerStats();
        assertTrue(stats[7] >= 1, "§5.1: the emission counter saw the crossing");
        assertEquals(0, stats[1], "precondition 1 was live (no-place search)");
    }

    @Test
    void placeCapableBot_mechanismStaysDormant() {
        RegionPathPlan plan = RegionPathfinder.plan(null, cornerGrid(false), START, GOAL, BotCaps.BREAK_PLACE);
        assertNotNull(plan, "a place-capable bot routes through the air intermediate (pillar pricing)");
        for (int i = 0; i < plan.size(); i++) {
            assertFalse(RegionPathfinder.isCornerCut(plan.fragmentId(i)),
                    "a place-capable search never mints a corner node (§4.1 precondition 1, hoisted)");
        }
        int[] stats = RegionPathfinder.lastCornerStats();
        assertEquals(1, stats[1], "§5.1: dormant — precondition 1 short-circuited the whole enumerator");
        assertEquals(0, stats[0], "…so no candidate was ever considered");
    }

    @Test
    void mixedIntermediate_chainSurvivesTheRealRecord() {
        // R35: the corner node pops in a region WITH a real MIXED record — typeBits(61)/footprint(61,·)
        // would AIOOBE without the top-of-expandNode short-circuit, and a uniform-AIR B hides that in
        // testing (this fixture is §6 item 13's first half).
        RegionPathPlan plan = RegionPathfinder.plan(null, cornerGrid(true), START, GOAL, BotCaps.DEFAULT);
        assertNotNull(plan, "a typeless MIXED intermediate is §2's own trigger case — still routed");
        assertEquals(C, plan.fragmentId(1));
    }

    @Test
    void refutedCorner_isNotReEmitted() {
        // §6 item 8 (headless half): blacklist the DIAGONAL pair — §4.1 precondition 6's probe — and the
        // corner may not come back; with no other route for a no-place bot the search honestly fails.
        RegionGrid grid = cornerGrid(false);
        RegionPathPlan first = RegionPathfinder.plan(null, grid, START, GOAL, BotCaps.DEFAULT);
        assertNotNull(first);
        RegionEdgeBlacklist blacklist = new RegionEdgeBlacklist();
        blacklist.add(RegionPathfinder.fragmentNodeKey(0, 1, 0, first.fragmentId(0)),
                RegionPathfinder.fragmentNodeKey(1, 1, 1, first.fragmentId(2)));
        RegionPathPlan second = RegionPathfinder.planWithin(0, grid, MINY, START, GOAL, GOAL,
                BotCaps.DEFAULT, blacklist);
        if (second != null) {
            for (int i = 0; i < second.size(); i++) {
                assertFalse(RegionPathfinder.isCornerCut(second.fragmentId(i)),
                        "a refuted corner must not re-emit (§4.1 precondition 6 — else re-emitted forever)");
            }
            assertFalse(second.reachedGoalRegion(), "no other route exists for a no-place bot");
        }
        assertTrue(RegionPathfinder.lastCornerStats()[6] >= 1, "§5.1: the rejection was counted as p6");
    }

    @Test
    void reverseField_seesTheCorner() {
        // R29/§4.8: the goal-rooted field crosses the corner via the direct diagonal relax, so
        // isSealedWithin no longer declares a corner-reachable goal SEALED (the same fail-closed refusal
        // through a different door).
        RegionGrid grid = cornerGrid(false);
        assertFalse(RegionPathfinder.isSealedWithin(grid, MINY, GOAL, START, 0, 3,
                        false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "the field reaches the observer across the corner — not sealed");
    }
}
