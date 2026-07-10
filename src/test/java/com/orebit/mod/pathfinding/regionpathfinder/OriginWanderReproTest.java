package com.orebit.mod.pathfinding.regionpathfinder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * DIAGNOSTIC (not a pass/fail gate): reproduce the "short path near the origin wanders off" reports by running
 * the real level-0 region A* over a hand-seeded FLAT-WORLD region grid, for origin-straddling start/goal pairs
 * and origin-shifted controls, and dumping the resulting skeleton. Evidence-gathering per the CLAUDE.md cardinal
 * rule — read the skeleton the region tier actually produces, don't guess.
 */
public class OriginWanderReproTest {

    private static final int G = RegionAddress.LEAF_SIZE;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE_HARDNESS = 8;

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        RegionPathfinder.TRACE = false;
        Debug.ENABLED = false;
    }

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** Seed a level-0 leaf from masks via the real FragmentBuilder (solid cells priced as stone). */
    private static void seed(RegionGrid grid, int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += STONE_HARDNESS; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, G, passCount, standCount, 0, hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    /** A flat-world section: standable slab at local y0, air y1..14 across the whole footprint (one fragment). */
    private static void seedFlatFloor(RegionGrid grid, int rx, int ry, int rz) {
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

    /** A broad flat world: cavern floor over every region in [rxLo,rxHi] x [rzLo,rzHi] at ry. */
    private static RegionGrid flatWorld(int rxLo, int rxHi, int rzLo, int rzHi, int ry) {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = rxLo; rx <= rxHi; rx++)
            for (int rz = rzLo; rz <= rzHi; rz++)
                seedFlatFloor(grid, rx, ry, rz);
        return grid;
    }

    /** A feet cell at the centre of region (rx,ry,rz)'s floor band. */
    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }

    private static void dump(String label, RegionGrid grid, BlockPos start, BlockPos goal) {
        int srx = RegionAddress.regionX(start.getX(), 0);
        int sry = RegionAddress.regionY(start.getY(), 0, MINY);
        int srz = RegionAddress.regionZ(start.getZ(), 0);
        int grx = RegionAddress.regionX(goal.getX(), 0);
        int gry = RegionAddress.regionY(goal.getY(), 0, MINY);
        int grz = RegionAddress.regionZ(goal.getZ(), 0);
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);
        System.out.println("==== " + label + " ====");
        System.out.println("  start world=" + xyz(start) + " region=(" + srx + "," + sry + "," + srz + ")");
        System.out.println("  goal  world=" + xyz(goal) + " region=(" + grx + "," + gry + "," + grz + ")");
        if (plan == null) {
            System.out.println("  PLAN = null (no route)");
            return;
        }
        System.out.println("  plan size=" + plan.size() + " reachedGoalRegion=" + plan.reachedGoalRegion()
                + " fragmentModel=" + plan.isFragmentModel());
        for (int i = 0; i < plan.size(); i++) {
            String portal = plan.hasPortal(i)
                    ? xyz(plan.portalCell(i)) + (plan.isDig(i) ? " DIG" : "") : "-";
            System.out.println("  [" + i + "] region=(" + plan.rx(i) + "," + plan.ry(i) + "," + plan.rz(i)
                    + ") frag=" + plan.fragmentId(i) + " portal=" + portal);
        }
    }

    private static String xyz(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    @Test
    void repro() {
        // Broad flat world covering the origin neighbourhood, ry = 1 (world y 16..31).
        RegionGrid grid = flatWorld(-4, 4, -4, 4, 1);

        // REPRO 1: straddle z=0. Start region (-1,1,0), goal region (-1,1,-1) — adjacent in z.
        dump("REPRO 1 straddle z=0  (-1,1,0) -> (-1,1,-1)", grid, feet(-1, 1, 0), feet(-1, 1, -1));
        // CONTROL 1: same shape shifted to all-positive z. Start (-1,1,3) -> goal (-1,1,2).
        dump("CONTROL 1 positive z  (-1,1,3) -> (-1,1,2)", grid, feet(-1, 1, 3), feet(-1, 1, 2));

        // REPRO 2: straddle x=0. Start region (-1,1,0), goal region (0,1,0) — adjacent in x.
        dump("REPRO 2 straddle x=0  (-1,1,0) -> (0,1,0)", grid, feet(-1, 1, 0), feet(0, 1, 0));
        // CONTROL 2: same shape shifted to all-positive x. Start (1,1,0) -> goal (2,1,0).
        dump("CONTROL 2 positive x  (1,1,0) -> (2,1,0)", grid, feet(1, 1, 0), feet(2, 1, 0));
    }
}
