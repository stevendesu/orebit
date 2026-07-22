package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Gate-level verification of the floorless air-vs-water reclassification ({@link FragmentBuilder#build}:
 * a floorless leaf is {@link RegionFragments#KIND_AIR} only when provably dry — ANY water ⇒
 * {@link RegionFragments#KIND_WATER}).
 *
 * <p>The consumer that motivated the fix is {@link RegionPathfinder}'s no-place air gate (the relax loop's
 * {@code !canPlace && kind()==KIND_AIR && f != 2} drop): correct in itself — a no-place bot cannot pillar up
 * a genuinely dry floorless shaft, so lateral/upward entry into uniform AIR is physically impossible for it —
 * but it treats KIND_AIR as a <b>proof of "nothing swimmable here"</b>. Under the old majority vote a
 * surface leaf that was mostly air over a sliver of ocean got KIND_AIR, and the gate then refused transit
 * through genuinely swimmable cells: a coarse-graph FALSE DISCONNECTION (never converges — the block tier
 * can invalidate an optimistic edge online, but a pessimistically-missing edge is never rediscovered).
 *
 * <p>Fixture idiom: {@link RegionFloodGuardTest} / {@code RegionScenarios} — real {@link FragmentBuilder}
 * floods over hand-authored masks on a {@link RegionGrid#headless headless} grid, everything sealed in
 * explicit solid so an unbuilt-optimistic leak can't fake a route.
 */
public class RegionWaterGateTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE = 8;

    private static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    /**
     * Sealed solid box rx −1..3, ry 0..2, rz −1..1 with three carved regions in a lateral row:
     * <pre>
     *   A = (0,1,0)  cavern floor — start (feet world (8,17,8))
     *   M = (1,1,0)  FLOORLESS    — all-passable, no floor; waterCount per test variant
     *   G = (2,1,0)  cavern floor — goal (feet world (40,17,8))
     * </pre>
     * The ONLY route A→G crosses M laterally; everything else is unmineable-for-a-no-break-bot solid.
     */
    private RegionGrid buildGrid(int middleWaterCount) {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = -1; rx <= 3; rx++) {
            for (int ry = 0; ry <= 2; ry++) {
                for (int rz = -1; rz <= 1; rz++) {
                    seedSolid(grid, rx, ry, rz);
                }
            }
        }
        seedFloor(grid, 0, 1, 0);                       // A — start
        seedFloorless(grid, 1, 1, 0, middleWaterCount); // M — the gate's subject
        seedFloor(grid, 2, 1, 0);                       // G — goal
        return grid;
    }

    /**
     * A no-place bot's region search must route laterally THROUGH a floorless water-bearing leaf: even one
     * water cell in 4095 air reclassifies M to KIND_WATER (swimmable — the gate exempts WATER), so the
     * A→M→G swim crossing exists. Under the old majority vote this exact leaf read KIND_AIR and the route
     * was falsely disconnected.
     */
    @Test
    void noPlaceBot_routesThroughFloorlessWaterBearingCell() {
        RegionGrid grid = buildGrid(1); // 1 water cell among 4095 air — minority, still WATER
        assertEquals(RegionFragments.KIND_WATER, grid.fragmentRecord(0, 1, 1, 0).kind(),
                "a floorless leaf with ANY water must classify KIND_WATER");

        RegionPathPlan plan = RegionPathfinder.plan(null, grid, feet(0, 1, 0), feet(2, 1, 0), BotCaps.DEFAULT);
        assertNotNull(plan, "the no-place bot must route laterally through the water-bearing floorless cell");
        assertTrue(plan.reachedGoalRegion(), "the route reaches the goal region, not a partial");
        assertEquals(2, plan.rx(plan.size() - 1), "the route ends in G = (2,1,0)");
    }

    /**
     * The gate itself stays intact: a provably-dry floorless leaf (waterCount == 0 ⇒ KIND_AIR) still blocks
     * lateral entry for a no-place bot — there is genuinely nothing to swim or pillar in it — while a
     * place-capable bot keeps its (dear) pillar crossing. The fix widens WATER, it does not weaken the
     * AIR gate.
     */
    @Test
    void noPlaceBot_stillGatedThroughPureAirCell() {
        RegionGrid grid = buildGrid(0);
        assertEquals(RegionFragments.KIND_AIR, grid.fragmentRecord(0, 1, 1, 0).kind(),
                "a floorless zero-water leaf still classifies KIND_AIR");

        RegionPathPlan gated = RegionPathfinder.plan(null, grid, feet(0, 1, 0), feet(2, 1, 0), BotCaps.DEFAULT);
        assertNull(gated, "a no-place bot cannot cross a dry floorless shaft laterally — no route exists");

        RegionPathPlan capable = RegionPathfinder.plan(null, grid, feet(0, 1, 0), feet(2, 1, 0), BotCaps.BREAK_PLACE);
        assertNotNull(capable, "a place-capable bot still crosses (pillar/dig) — the gate is caps-scoped");
    }

    // ---- seed helpers (real FragmentBuilder flood — the RegionFloodGuardTest idiom) -----------------------

    private static void seed(RegionGrid grid, int rx, int ry, int rz,
                             boolean[] passable, boolean[] standable, int waterCount) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += STONE; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, G, passCount, standCount, waterCount,
                hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private static void seedSolid(RegionGrid grid, int rx, int ry, int rz) {
        seed(grid, rx, ry, rz, new boolean[CELLS], new boolean[CELLS], 0);
    }

    /** Full cavern floor: standable slab at local y=0, passable air y1..14 (reaches the four side faces). */
    private static void seedFloor(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(grid, rx, ry, rz, passable, standable, 0);
    }

    /** Floorless: all-passable, none standable; {@code waterCount} of the cells hold water. */
    private static void seedFloorless(RegionGrid grid, int rx, int ry, int rz, int waterCount) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        seed(grid, rx, ry, rz, passable, new boolean[CELLS], waterCount);
    }

    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }
}
