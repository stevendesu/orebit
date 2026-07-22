package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Unit tests for the fragment-model region A* ({@link RegionPathfinder}, HPA-FRAGMENTS.md §S3). These need
 * <b>no Minecraft</b>: a
 * {@link RegionGrid#headless headless} grid is hand-seeded with {@link RegionFragments} records built by the
 * pure {@link FragmentBuilder} from raw passable/standable masks (exactly the {@code FragmentBuilderTest}
 * fixtures), then {@link RegionPathfinder#plan} is run over it. No {@code ServerLevel}, no {@code NavStore} —
 * a not-seeded region reads the §6 optimistic AIR default.
 *
 * <p>Coverage (the S3 acceptance set):
 * <ul>
 *   <li><b>Sealed cave</b> — one region holding two vertically-separated tunnels (two fragments): the cheapest
 *       route from the lower to the upper is the intra-region MINE edge (a dig of ~8 blocks ≈ 24 ticks, vs an
 *       out-and-back air detour of ~192). Asserts the plan is found (no FAIL), stays in the one region, and
 *       crosses fragments — i.e. an intra-region mine edge was taken.</li>
 *   <li><b>Open portal</b> — a start region adjacent to a two-fragment goal region whose lower fragment lines
 *       up with the start's opening (footprints overlap) and whose upper fragment does not. The goal is in the
 *       lower fragment; asserts the plan terminates at the virtual goal carrying the goal cell (the low/open
 *       target), never the misaligned high tunnel.</li>
 * </ul>
 */
public class RegionPathfinderFragmentTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private RegionGrid grid;

    @BeforeEach
    void setUp() {
        grid = RegionGrid.headless(0); // minY = 0 → region ry 0 spans world y 0..15
    }

    /** Seed a level-0 leaf region's fragment record from masks, computing the tallies the builder needs. */
    private void seed(int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += 8; } // treat solid as stone (per-cell h ≈ 8)
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, G,
                passCount, standCount, 0, hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    /**
     * Seed a level-0 region as fully-solid rock (a built, uniform {@code KIND_SOLID} node). Used to <b>seal</b>
     * a cave with known rock on all sides so the region A* cannot escape through a neighbour cheaply. An
     * <i>unbuilt</i> neighbour reads as FREE optimistic passage (unloaded = "assume traversable, correct on
     * approach"), so a cave must be sealed with explicitly-built solid neighbours to test that the intra-region
     * mine edge is the chosen route.
     */
    private void seedSolid(int rx, int ry, int rz) {
        seed(rx, ry, rz, new boolean[CELLS], new boolean[CELLS]); // all-false masks → uniform SOLID
    }

    /** A 2-tall tunnel spanning all X at the given Z band and feet Y (touches both ±X faces). */
    private static void carveTunnelX(boolean[] passable, boolean[] standable, int zLo, int zHi, int feetY) {
        for (int x = 0; x < G; x++) {
            for (int z = zLo; z <= zHi; z++) {
                standable[idx(x, feetY - 1, z)] = true;
                passable[idx(x, feetY, z)] = true;
                passable[idx(x, feetY + 1, z)] = true;
            }
        }
    }

    // ===================================================================================================
    // Sealed cave — two interior pockets in ONE region, only reachable from each other by mining.
    // ===================================================================================================
    @Test
    void sealedCave_routesThroughMineEdge_noFail() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        // Two tunnels in ONE region, vertically separated by solid: lower feet y=2, upper feet y=10. They are
        // distinct fragments; the cheap route between them is the intra-region mine edge (a ~8-block dig),
        // far below mining out-and-back through a sealing-rock neighbour, so the A* must take the mine edge.
        carveTunnelX(passable, standable, 0, G - 1, 2);
        carveTunnelX(passable, standable, 0, G - 1, 10);
        seed(0, 0, 0, passable, standable);
        // SEAL with built solid rock on all six sides. The tunnels span the full region, so both fragments
        // reach the four horizontal faces; without a sealing neighbour the A* would escape into a FREE unbuilt
        // (unloaded) neighbour and re-enter the upper pocket for nothing (a 3-step plan), never taking the mine
        // edge. Real underground caves are surrounded by LOADED solid rock — model that explicitly here.
        seedSolid(1, 0, 0); seedSolid(-1, 0, 0);
        seedSolid(0, 0, 1); seedSolid(0, 0, -1);
        seedSolid(0, 1, 0); seedSolid(0, -1, 0);

        // Sanity: the seed really produced two fragments (else the test would be vacuous).
        RegionFragments rf = grid.fragmentRecord(0, 0, 0, 0);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertEquals(2, rf.fragmentCount(), "two vertically-separated tunnels = two fragments");

        BlockPos start = new BlockPos(8, 2, 8);    // standing in the lower tunnel
        BlockPos goal = new BlockPos(8, 10, 8);    // standing in the upper tunnel
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);

        assertNotNull(plan, "sealed cave must NOT FAIL — it routes through an intra-region mine edge");
        assertTrue(plan.isFragmentModel(), "fragment-flag plan carries fragment ids");
        assertEquals(2, plan.size(), "lower fragment → (mine) → upper fragment");
        // Both steps are in the SAME region (the dig never leaves it) and they are different fragments.
        for (int i = 0; i < plan.size(); i++) {
            assertEquals(0, plan.rx(i));
            assertEquals(0, plan.ry(i));
            assertEquals(0, plan.rz(i));
        }
        assertTrue(plan.fragmentId(0) != plan.fragmentId(1), "the path crosses fragments (a mine edge)");
        assertTrue(plan.hasPortal(1), "the mine edge carries a portal/target cell into the goal fragment");
    }

    // ===================================================================================================
    // Open portal — a 2-fragment goal region: the path takes the OVERLAPPING (open) fragment, not the
    // misaligned one.
    // ===================================================================================================
    @Test
    void twoFragmentRegion_routesThroughOpenPortal() {
        // Region A (0,0,0): one open tunnel at feet y=1 spanning all X → touches +X with footprint Y in [1,2].
        boolean[] aPass = new boolean[CELLS];
        boolean[] aStand = new boolean[CELLS];
        carveTunnelX(aPass, aStand, 0, G - 1, 1);
        seed(0, 0, 0, aPass, aStand);

        // Region B (1,0,0): TWO tunnels — a LOW one (feet y=1 → −X footprint Y[1,2], overlaps A's +X) and a
        // HIGH one (feet y=10 → −X footprint Y[10,11], does NOT overlap). Flood-seed order ⇒ low = fragment 0.
        boolean[] bPass = new boolean[CELLS];
        boolean[] bStand = new boolean[CELLS];
        carveTunnelX(bPass, bStand, 0, G - 1, 1);
        carveTunnelX(bPass, bStand, 0, G - 1, 10);
        seed(1, 0, 0, bPass, bStand);

        RegionFragments rfB = grid.fragmentRecord(0, 1, 0, 0);
        assertEquals(2, rfB.fragmentCount(), "region B has a low + a high tunnel = two fragments");

        BlockPos start = new BlockPos(8, 1, 8);    // in region A's tunnel
        BlockPos goal = new BlockPos(24, 1, 8);    // x=24 → region B; y=1 → the LOW (open) fragment
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);

        assertNotNull(plan, "an open portal must route, not FAIL");
        assertTrue(plan.isFragmentModel());
        int last = plan.size() - 1;
        assertEquals(1, plan.rx(last), "the path ends in region B");
        // The goal (24,1,8) is in region B's LOW fragment, reachable through the open portal. The region tier
        // now terminates at the virtual goal V (the per-approach model): the skeleton ends at V — sitting at the
        // goal region and carrying the GOAL CELL as its portal — reached from region A's overlapping (open)
        // opening. The misaligned HIGH tunnel (feet y=10) is never targeted; V's portal proves it (y=1 = the low
        // fragment). (Pre-virtual-goal this step was the low fragment id with the x=16 boundary portal.)
        assertTrue(RegionPathfinder.isVirtualGoal(plan.fragmentId(last)),
                "the skeleton commits to the goal cell via the virtual goal (the per-approach terminus)");
        assertTrue(plan.hasPortal(last), "the virtual-goal step carries the goal cell");
        assertEquals(new BlockPos(24, 1, 8), plan.portalCell(last),
                "the virtual goal's portal is the goal cell in the LOW open fragment (y=1), not the high tunnel");
    }

    // ===================================================================================================
    // No-break bot — mining edges are dropped: a mine-only connection is impassable (FAIL), but a genuine
    // walkable portal still routes. Guards the fix for a no-break bot routed at unmineable rock then thrashing.
    // ===================================================================================================
    @Test
    void sealedCave_noBreakBot_failsRatherThanRoutingThroughRock() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnelX(passable, standable, 0, G - 1, 2);   // lower pocket
        carveTunnelX(passable, standable, 0, G - 1, 10);  // upper pocket — only reachable by mining
        seed(0, 0, 0, passable, standable);
        seedSolid(1, 0, 0); seedSolid(-1, 0, 0);          // sealed in known rock on all sides
        seedSolid(0, 0, 1); seedSolid(0, 0, -1);
        seedSolid(0, 1, 0); seedSolid(0, -1, 0);

        BlockPos start = new BlockPos(8, 2, 8);
        BlockPos goal = new BlockPos(8, 10, 8);
        // DEFAULT caps = canBreak false. The only connection between the pockets is a mine edge, which is now
        // dropped, so there is NO route — the region A* must FAIL rather than promise a dig the bot can't do.
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.DEFAULT);
        assertNull(plan, "a no-break bot cannot dig between the two pockets → the region A* must FAIL, not "
                + "route through unmineable rock (the noBreakCap thrash bug)");
    }

    @Test
    void twoFragmentRegion_noBreakBot_stillRoutesOpenPortal() {
        // Same open-portal layout as twoFragmentRegion_routesThroughOpenPortal, but with a no-break bot: a real
        // walkable opening (overlapping footprints) is NOT a mine edge, so it must still route.
        boolean[] aPass = new boolean[CELLS];
        boolean[] aStand = new boolean[CELLS];
        carveTunnelX(aPass, aStand, 0, G - 1, 1);
        seed(0, 0, 0, aPass, aStand);
        boolean[] bPass = new boolean[CELLS];
        boolean[] bStand = new boolean[CELLS];
        carveTunnelX(bPass, bStand, 0, G - 1, 1);
        carveTunnelX(bPass, bStand, 0, G - 1, 10);
        seed(1, 0, 0, bPass, bStand);

        BlockPos start = new BlockPos(8, 1, 8);
        BlockPos goal = new BlockPos(24, 1, 8);
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.DEFAULT);

        assertNotNull(plan, "a no-break bot must still route through a genuine open portal (walk, not mine)");
        int last = plan.size() - 1;
        assertEquals(1, plan.rx(last), "the path ends in region B via the open portal");
        assertTrue(RegionPathfinder.isVirtualGoal(plan.fragmentId(last)),
                "it commits to the goal cell (LOW open fragment) via the virtual goal, reached by walking");
        assertEquals(new BlockPos(24, 1, 8), plan.portalCell(last),
                "the virtual goal's portal is the goal cell in the LOW fragment (y=1), reached by a walk not a mine");
    }

    // ===================================================================================================
    // Per-approach online repair (the Fix-A consumer): a blacklisted (approach → V) crossing forces the
    // re-derived skeleton to terminate at V via a DIFFERENT approach; exhausting every approach is an
    // honest FAIL, never a re-offer of a dead entry.
    // ===================================================================================================
    @Test
    void virtualGoal_blacklistedApproach_reroutesThenHonestlyFails() {
        // The open-portal fixture: region A's tunnel, region B with a LOW (open, goal) and HIGH (misaligned)
        // tunnel; the goal is in B-low, so the virtual-goal mode engages with several approaches (B-low's own
        // fragment, region A's opening, and the optimistic unbuilt neighbours of B-low).
        boolean[] aPass = new boolean[CELLS];
        boolean[] aStand = new boolean[CELLS];
        carveTunnelX(aPass, aStand, 0, G - 1, 1);
        seed(0, 0, 0, aPass, aStand);
        boolean[] bPass = new boolean[CELLS];
        boolean[] bStand = new boolean[CELLS];
        carveTunnelX(bPass, bStand, 0, G - 1, 1);
        carveTunnelX(bPass, bStand, 0, G - 1, 10);
        seed(1, 0, 0, bPass, bStand);

        BlockPos start = new BlockPos(8, 1, 8);
        BlockPos goal = new BlockPos(24, 1, 8);
        RegionEdgeBlacklist blacklist = new RegionEdgeBlacklist();

        // Blacklist each plan's used approach (the step before V, keyed exactly as PathPlan.repairBlocked
        // records it) and re-plan: every re-derived skeleton must reach V via an approach not blamed before,
        // and once every approach is exhausted the region tier must FAIL honestly.
        java.util.Set<Long> blamed = new java.util.HashSet<>();
        RegionPathPlan plan = RegionPathfinder.planWithin(0, grid, 0, start, goal, goal,
                BotCaps.DEFAULT, blacklist);
        int rounds = 0;
        while (plan != null && rounds++ < 16) {
            int last = plan.size() - 1;
            assertTrue(RegionPathfinder.isVirtualGoal(plan.fragmentId(last)),
                    "every found plan terminates at the virtual goal");
            long approach = RegionPathfinder.fragmentNodeKey(plan.rx(last - 1), plan.ry(last - 1),
                    plan.rz(last - 1), plan.fragmentId(last - 1));
            assertFalse(blamed.contains(approach),
                    "a blacklisted approach must never be re-offered (round " + rounds + ")");
            blamed.add(approach);
            long vKey = RegionPathfinder.fragmentNodeKey(plan.rx(last), plan.ry(last), plan.rz(last),
                    plan.fragmentId(last));
            blacklist.add(approach, vKey);
            plan = RegionPathfinder.planWithin(0, grid, 0, start, goal, goal, BotCaps.DEFAULT, blacklist);
        }
        assertNull(plan, "with every approach blacklisted the region tier must FAIL honestly");
        assertTrue(blamed.size() >= 2,
                "the fixture offers multiple approaches — the repair must have rerouted at least once "
                        + "(got " + blamed.size() + ")");
    }

    // ===================================================================================================
    // Cap-safe level selection (HPA-FRAGMENTS.md §S5) — the cascade picks the finest level whose start→goal
    // box fits the node budget, so an area flood can never reach the backstop. (Long-range routing itself is
    // covered by HierarchicalCascadeTest; RegionPathfinder.plan is now the direct level-0 entry.)
    // ===================================================================================================
    @Test
    void capSafe_levelSelection_boxNeverExceedsTheBackstop() {
        // The whole point of the cap-safe level rule: a search confined to ±maxCheb(L) per horizontal axis
        // explores at most (2·maxCheb+1)² × vert(L) cells, which MUST stay under the hard expansion backstop —
        // so an area flood can never reach the cap. Assert the invariant at every selectable level.
        for (int L = 0; L <= com.orebit.mod.worldmodel.hpa.RegionAddress.MAX_COARSE_LEVEL; L++) {
            int m = RegionPathfinder.maxChebAtLevel(L);
            int vert = com.orebit.mod.worldmodel.hpa.RegionAddress.verticalRegions(L);
            long box = (long) (2 * m + 1) * (2 * m + 1) * vert;
            assertTrue(box <= RegionPathfinder.MAX_REGION_EXPANSIONS,
                    "level " + L + " cap-safe box " + box + " must be ≤ backstop "
                            + RegionPathfinder.MAX_REGION_EXPANSIONS);
            assertTrue(m >= 1, "every level must allow at least a 1-cell search");
        }
        // And the selector picks a cap-safe level for a sample of distances (near → very far).
        for (int d : new int[] {1, 8, 50, 300, 5000, 200000}) {
            int L = RegionPathfinder.chooseCapSafeLevel(0, 0, 0, d, 0, 0);
            int chebL = (d >> L);
            assertTrue(L <= com.orebit.mod.worldmodel.hpa.RegionAddress.MAX_COARSE_LEVEL);
            assertTrue(chebL <= RegionPathfinder.maxChebAtLevel(L) || L == com.orebit.mod.worldmodel.hpa.RegionAddress.MAX_COARSE_LEVEL,
                    "distance " + d + " → level " + L + " must be cap-safe (or the clamped top level)");
        }
    }
}
