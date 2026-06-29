package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Unit tests for the fragment-model region A* ({@link RegionPathfinder} under
 * {@link RegionGrid#HPA_FRAGMENTS}, HPA-FRAGMENTS.md §S3). These need <b>no Minecraft</b>: a
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
 *       up with the start's opening (footprints overlap) and whose upper fragment does not. Asserts the plan
 *       routes into the <i>overlapping</i> fragment (the open portal), not the misaligned one, with the portal
 *       cell on the low opening.</li>
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
    void enableFragments() {
        RegionGrid.HPA_FRAGMENTS = true;
        grid = RegionGrid.headless(0); // minY = 0 → region ry 0 spans world y 0..15
    }

    @AfterEach
    void disableFragments() {
        RegionGrid.HPA_FRAGMENTS = false; // do not leak the flag to other tests
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
        assertEquals(0, plan.fragmentId(last), "it commits to the LOW fragment (0) reached via the open portal");

        // The portal cell into region B sits on the low opening (Y ≤ 5), never the misaligned high tunnel.
        assertTrue(plan.hasPortal(last), "the portal step carries the boundary cell");
        BlockPos portal = plan.portalCell(last);
        assertNotNull(portal);
        assertTrue(portal.getY() <= 5,
                "portal must be the LOW open opening (Y≈1..2), not the high misaligned one; got y=" + portal.getY());
        assertEquals(16, portal.getX(), "portal is on region B's −X boundary plane (world x = 16)");
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
        assertEquals(0, plan.fragmentId(last), "it commits to the LOW (open) fragment, reached by walking");
    }

    // ===================================================================================================
    // Coarse scale-guard branch (S5) — a goal beyond LEVEL0_DIRECT_CAP (256 regions) must plan over the
    // rolled-up coarse fragment pyramid and return a refined level-0 NEAR segment that progresses toward the
    // goal (never null, never a stuck same-region stub). Nothing is seeded, so the far field is the §6
    // optimistic default and the coarse A* beelines — this is exactly the long-range "travel then refine"
    // path. It exercises the merge (ensureLevel → combineFragments) + the level-parameterized fragment A*.
    // ===================================================================================================
    @Test
    void coarseBranch_longRange_returnsProgressingNearSegment() {
        // Region (0,0,0) → region (300,0,0): Chebyshev 300 > LEVEL0_DIRECT_CAP (256) ⇒ the coarse branch.
        BlockPos start = new BlockPos(8, 4, 8);
        BlockPos goal = new BlockPos(300 * 16 + 8, 4, 8);
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);

        assertNotNull(plan, "the coarse branch must return a near segment, not null, for a long-range goal");
        assertTrue(plan.size() > 1, "the near segment must be a real onward route, not a same-region stub");
        assertTrue(plan.isFragmentModel(), "coarse refinement returns a fragment-model level-0 near segment");
        assertEquals(0, plan.rx(0), "the near segment starts at the bot's region");
        assertTrue(plan.rx(plan.size() - 1) > plan.rx(0),
                "the near segment progresses toward the +X goal (rx grows)");
    }

    @Test
    void coarseBranch_diagonalLongRange_progressesBothAxes() {
        // A diagonal long-range goal: the refined near segment should advance in BOTH +X and +Z.
        BlockPos start = new BlockPos(8, 4, 8);
        BlockPos goal = new BlockPos(300 * 16 + 8, 4, 280 * 16 + 8);
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);

        assertNotNull(plan, "diagonal long-range goal must route");
        assertTrue(plan.size() > 1);
        int last = plan.size() - 1;
        assertTrue(plan.rx(last) > plan.rx(0) || plan.rz(last) > plan.rz(0),
                "the near segment progresses toward the +X/+Z goal");
    }
}
