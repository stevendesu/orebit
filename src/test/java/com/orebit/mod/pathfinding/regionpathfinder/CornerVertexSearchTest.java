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
 * Headless search tests for the 3-AXIS VERTEX chain (DESIGN-region-corner-crossing-v2.md §4.3's
 * chain-of-two, made realizable by the dry 3-axis moves — DESIGN-diagonal-vertical-moves.md) and for the
 * D7 edge-gate fidelity (the retired conservative arm's replacement). Same substrate as
 * {@link CornerCrossingSearchTest}: real {@link FragmentBuilder} floods, no {@code ServerLevel}.
 *
 * <p>The fixture is the vertex analogue of §0's shape: A {@code (0,1,0)} a sky-open cavern (floor layer,
 * passable to its +Y face), D {@code (1,2,1)} the mirrored corner room (a floor PATCH at its −X/−Y/−Z
 * corner quadrant's far side, so its AIR fragment touches all three corner faces), the three face
 * intermediates AND the three edge regions pure {@code KIND_AIR}, everything else SOLID. A no-place bot
 * has no ordinary route and exactly one vertex qualifies — before the dry 3-axis moves this chain existed
 * only as untested machinery.
 */
class CornerVertexSearchTest {

    private static final int G = RegionAddress.LEAF_SIZE;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int C = RegionPathfinder.CORNER_FRAG;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static void seed(RegionGrid grid, int rx, int ry, int rz,
                             boolean[] passable, boolean[] standable, boolean[] water) {
        int passCount = 0, standCount = 0, waterCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += 8; }
            if (standable[i]) standCount++;
            if (water != null && water[i]) waterCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, water, G, passCount, standCount, waterCount,
                hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private static void seedSolid(RegionGrid grid, int rx, int ry, int rz) {
        seed(grid, rx, ry, rz, new boolean[CELLS], new boolean[CELLS], null);
    }

    private static void seedAir(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        seed(grid, rx, ry, rz, passable, new boolean[CELLS], null);
    }

    private static void seedWater(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] water = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        java.util.Arrays.fill(water, true);
        seed(grid, rx, ry, rz, passable, new boolean[CELLS], water);
    }

    /** A: a full floor layer at local y=0, passable all the way to the +Y face — TYPE_S and vertex-open. */
    private static void seedSkyCavern(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y < G; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(grid, rx, ry, rz, passable, standable, null);
    }

    /** D: air everywhere except a floor PATCH (x 0..7, z 0..7, local y 0) — the AIR fragment is TYPE_S
     *  (footing over the patch) and touches −X/−Y/−Z with footprints reaching the corner extremes (the
     *  patch is an embedded island; the rest of the y=0 layer stays passable, so −Y is touched). */
    private static void seedCornerRoom(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                passable[idx(x, 0, z)] = false;
                standable[idx(x, 0, z)] = true;
            }
        }
        seed(grid, rx, ry, rz, passable, standable, null);
    }

    /** The base vertex grid; callers overwrite individual regions for the D7 variants. */
    private static RegionGrid vertexGrid() {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = -1; rx <= 2; rx++)
            for (int ry = 0; ry <= 3; ry++)
                for (int rz = -1; rz <= 2; rz++) seedSolid(grid, rx, ry, rz);
        seedSkyCavern(grid, 0, 1, 0);   // A
        seedCornerRoom(grid, 1, 2, 1);  // D — diagonal on ALL THREE axes
        seedAir(grid, 1, 1, 0);         // face intermediates…
        seedAir(grid, 0, 2, 0);
        seedAir(grid, 0, 1, 1);
        seedAir(grid, 1, 2, 0);         // …and the three edge regions (Exy, Exz, Eyz)
        seedAir(grid, 1, 1, 1);
        seedAir(grid, 0, 2, 1);
        return grid;
    }

    private static final BlockPos START = new BlockPos(8, 17, 8);   // feet over A's floor (abs 16)
    private static final BlockPos GOAL = new BlockPos(20, 33, 20);  // feet over D's patch (abs 32)

    @Test
    void noPlaceBot_getsTheVertexChainOfTwo() {
        RegionPathPlan plan = RegionPathfinder.plan(null, vertexGrid(), START, GOAL, BotCaps.DEFAULT);
        assertNotNull(plan, "the vertex chain turns the 3-axis fail-closed refusal into a route");
        assertTrue(plan.reachedGoalRegion());
        assertEquals(5, plan.size(), "A → B.CORNER → C.CORNER → D → V (§4.3's chain-of-two)");
        assertEquals(C, plan.fragmentId(1), "B carries the corner-cut id");
        assertEquals(C, plan.fragmentId(2), "C carries the corner-cut id");
        assertFalse(plan.hasPortal(1), "R32 on B");
        assertFalse(plan.hasPortal(2), "R32 on C");
        // §4.4's forced X→Y→Z order: B = A+X, C = B+Y, D = C+Z.
        assertEquals(1, plan.rx(1));
        assertEquals(1, plan.ry(1));
        assertEquals(0, plan.rz(1));
        assertEquals(1, plan.rx(2));
        assertEquals(2, plan.ry(2));
        assertEquals(0, plan.rz(2));
        assertTrue(plan.fragmentId(3) < RegionFragments.MAX_FRAGMENTS, "D is real");
        assertTrue(plan.hasPortal(3), "D carries the R15 corner-column portal");
        BlockPos p = plan.portalCell(3);
        assertEquals(16, p.getX(), "portal pinned to D's −X corner edge");
        assertEquals(16, p.getZ(), "portal pinned to D's −Z corner edge");
        assertTrue(RegionPathfinder.isVirtualGoal(plan.fragmentId(4)), "the tail is V");
    }

    @Test
    void placeCapableBot_neverMintsTheVertex() {
        RegionPathPlan plan = RegionPathfinder.plan(null, vertexGrid(), START, GOAL, BotCaps.BREAK_PLACE);
        assertNotNull(plan);
        for (int i = 0; i < plan.size(); i++) {
            assertFalse(RegionPathfinder.isCornerCut(plan.fragmentId(i)),
                    "precondition 1 (hoisted) keeps the whole mechanism dormant for a place-capable bot");
        }
    }

    // ---- the D7 edge-gate fidelity pins ---------------------------------------------------------------

    @Test
    void farSideTypedFragmentInAnEdgeRegion_noLongerRejectsTheVertex() {
        // The RETIRED conservative gate rejected the vertex on ANY typed fragment anywhere in an edge
        // region. D7's two-leg test requires the fragment to actually SUBSTITUTE — corner-2-qualify toward
        // A and face-route toward D. Exy here is the base fixture's open air (so precondition 5's
        // corner-column proof still holds, via the TYPELESS air fragment) plus a SEALED interior room:
        // solid cube (x,z 10..15, y 0..15) hollowed at (12..14, 8..10, 12..14) with footing — a TYPE_S
        // fragment touching NO region face at all. The old gate rejects on its mere existence; D7 must not.
        RegionGrid grid = vertexGrid();
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int x = 10; x < G; x++) {
            for (int z = 10; z < G; z++) {
                for (int y = 0; y < G; y++) passable[idx(x, y, z)] = false; // the sealed cube
            }
        }
        for (int x = 12; x <= 14; x++) {
            for (int z = 12; z <= 14; z++) {
                standable[idx(x, 7, z)] = true;                    // the room's floor cells
                for (int y = 8; y <= 10; y++) passable[idx(x, y, z)] = true; // the hollow (TYPE_S, sealed)
            }
        }
        seed(grid, 1, 2, 0, passable, standable, null); // Exy: open air + a sealed far-side TYPE_S room
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, START, GOAL, BotCaps.DEFAULT);
        assertNotNull(plan, "a far-side pocket that can substitute for nothing must not kill the vertex");
        assertEquals(C, plan.fragmentId(1));
        assertEquals(C, plan.fragmentId(2));
    }

    @Test
    void edgeRegionThatGenuinelySubstitutes_rejectsTheVertexForTheCorner2Route() {
        // Exy holds a half-floor room whose fragment corner-2-qualifies toward A (touches −X and −Y with
        // footprints at the corner — the floor is a HALF layer so the −Y face stays touched) AND face-routes
        // into D across +Z: the ordinary corner-2(A→Exy) + face(Exy→D) family exists, so D7 rejects the
        // vertex and the search takes the two-leg route — ONE corner id in the skeleton, not two, and the
        // Exy step is a REAL fragment.
        RegionGrid grid = vertexGrid();
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        for (int x = 8; x < G; x++) {
            for (int z = 0; z < G; z++) {
                passable[idx(x, 0, z)] = false; // the half floor (x 8..15) — −Y stays touched via x 0..7
                standable[idx(x, 0, z)] = true;
            }
        }
        seed(grid, 1, 2, 0, passable, standable, null);
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, START, GOAL, BotCaps.DEFAULT);
        assertNotNull(plan, "the corner-2 + face family through Exy routes the goal");
        int cornerSteps = 0;
        boolean exyReal = false;
        for (int i = 0; i < plan.size(); i++) {
            if (RegionPathfinder.isCornerCut(plan.fragmentId(i))) cornerSteps++;
            if (plan.rx(i) == 1 && plan.ry(i) == 2 && plan.rz(i) == 0) {
                exyReal = plan.fragmentId(i) < RegionFragments.MAX_FRAGMENTS;
            }
        }
        assertEquals(1, cornerSteps, "one corner-2 intermediate, never the vertex's chain-of-two");
        assertTrue(exyReal, "the substituting edge region is a REAL step on the route");
    }

    @Test
    void waterEdgeRegion_refusesAtPrecondition5_notAtTheEdgeArm() {
        // The old gate auto-rejected a uniform-WATER edge region at the precondition-3 arm. D7: no record
        // ⇒ no corner-2 into it ⇒ the EDGE ARM no longer rejects — but precondition 5 still refuses the
        // vertex (a water corner column offers the dry chain no passable proof), so the observable outcome
        // stays "no vertex"; only the counter moves from p3 to p5.
        RegionGrid grid = vertexGrid();
        seedWater(grid, 1, 2, 0);
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, START, GOAL, BotCaps.DEFAULT);
        if (plan != null) {
            assertFalse(plan.reachedGoalRegion(), "no route: the vertex is refused and nothing else exists");
        }
        int[] stats = RegionPathfinder.lastCornerStats();
        assertEquals(0, stats[3], "D7: the edge arm no longer rejects a water edge region (was p3)");
        assertTrue(stats[5] >= 1, "…precondition 5 owns the refusal now");
    }
}
