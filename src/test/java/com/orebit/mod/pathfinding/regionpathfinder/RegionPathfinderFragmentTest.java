package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        // far below any out-and-back air detour through a neighbour, so the A* must take the mine edge.
        carveTunnelX(passable, standable, 0, G - 1, 2);
        carveTunnelX(passable, standable, 0, G - 1, 10);
        seed(0, 0, 0, passable, standable);

        // Sanity: the seed really produced two fragments (else the test would be vacuous).
        RegionFragments rf = grid.fragmentRecord(0, 0, 0, 0);
        assertEquals(RegionFragments.KIND_MIXED, rf.kind());
        assertEquals(2, rf.fragmentCount(), "two vertically-separated tunnels = two fragments");

        BlockPos start = new BlockPos(8, 2, 8);    // standing in the lower tunnel
        BlockPos goal = new BlockPos(8, 10, 8);    // standing in the upper tunnel
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal);

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
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal);

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
}
