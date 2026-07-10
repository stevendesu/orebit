package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.HierarchicalRegionPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * DIAGNOSTIC: reproduce the flat-world short-path wander (REPRO 1) faithfully — a real superflat's floor sits at
 * world y=-60 with minY=-64, NOT at a section boundary. Dumps the region skeleton (via the cascade, exactly what
 * PathPlan drives), the driver's window target choice, and the block path, with the region field ON and OFF, plus
 * an origin-shifted positive control. Evidence, not a gate.
 */
public class OriginBlockWanderReproTest {

    private static final int MINY = -64;
    /** World-Y of the walkable floor surface (superflat grass): local y = -60 - (-64) = 4 inside section ry 0. */
    private static final int FLOOR_Y = -60;
    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    private static PalettedContainer<BlockState> newStates() {
        return new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static NavSection section(PalettedContainer<BlockState> states, boolean onlyAir) {
        NavSection s = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, onlyAir, s.getTraversalGrid());
        return s;
    }

    private static NavSection airSection() {
        return section(newStates(), true);
    }

    /**
     * Faithful superflat column: section ry 0 (world y -64..-49) is solid stone from local y 0..4 (world
     * -64..-60), air local y 5..15; sections ry 1..3 all air. The walkable surface is the stone top at y=-60.
     */
    private static NavSection[] flatColumn() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> ground = newStates();
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int ly = 0; ly <= (FLOOR_Y - MINY); ly++) // local y 0..4
                    ground.set(x, ly, z, stone);
        NavSection[] col = { section(ground, false), airSection(), airSection(), airSection() };
        NavSectionBuilder.computeDepth(col);
        return col;
    }

    private static ConcurrentHashMap<Long, NavSection[]> flatWorld(int cxLo, int cxHi, int czLo, int czHi) {
        ConcurrentHashMap<Long, NavSection[]> sections = new ConcurrentHashMap<>();
        NavSection[] flat = flatColumn();
        for (int cx = cxLo; cx <= cxHi; cx++)
            for (int cz = czLo; cz <= czHi; cz++)
                sections.put(NavStore.key(cx, cz), flat);
        return sections;
    }

    /** Only the given chunk keys are built (the rest read as optimistic-AIR unbuilt leaves). */
    private static ConcurrentHashMap<Long, NavSection[]> partialWorld(int[][] chunks) {
        ConcurrentHashMap<Long, NavSection[]> sections = new ConcurrentHashMap<>();
        NavSection[] flat = flatColumn();
        for (int[] c : chunks) sections.put(NavStore.key(c[0], c[1]), flat);
        return sections;
    }

    private static RegionBound cuboidCap(BlockPos a, BlockPos b) {
        int m = 16;
        return new RegionBound(
                Math.min(a.getX(), b.getX()) - m, Math.max(a.getX(), b.getX()) + m,
                Math.min(a.getY(), b.getY()) - m, Math.max(a.getY(), b.getY()) + m,
                Math.min(a.getZ(), b.getZ()) - m, Math.max(a.getZ(), b.getZ()) + m);
    }

    private static void runCase(String label, ConcurrentHashMap<Long, NavSection[]> sections,
                                BlockPos startFloor, BlockPos goalFloor, boolean useField) {
        BotCaps caps = BotCaps.BREAK_PLACE;
        int srx = RegionAddress.regionX(startFloor.getX(), 0);
        int sry = RegionAddress.regionY(startFloor.getY(), 0, MINY);
        int srz = RegionAddress.regionZ(startFloor.getZ(), 0);
        int grx = RegionAddress.regionX(goalFloor.getX(), 0);
        int gry = RegionAddress.regionY(goalFloor.getY(), 0, MINY);
        int grz = RegionAddress.regionZ(goalFloor.getZ(), 0);
        RegionGrid grid = RegionGrid.headless(MINY, sections);

        // --- Region tier via the SAME cascade PathPlan drives ---
        HierarchicalRegionPlan hier = HierarchicalRegionPlan.build(grid, MINY, startFloor, goalFloor, caps,
                RegionMineModel.DEFAULT);
        RegionPathPlan skeleton = hier.l0Skeleton();
        System.out.println("==== " + label + " field=" + (useField ? "ON" : "OFF") + " ====");
        System.out.println("  start=" + xyz(startFloor) + " region=(" + srx + "," + sry + "," + srz + ")"
                + "  goal=" + xyz(goalFloor) + " region=(" + grx + "," + gry + "," + grz + ")");
        if (skeleton == null) {
            System.out.println("  SKELETON = null (cascade FAILED)");
        } else {
            System.out.println("  skeleton size=" + skeleton.size() + " reachedGoalRegion="
                    + skeleton.reachedGoalRegion());
            for (int i = 0; i < skeleton.size(); i++) {
                System.out.println("    [" + i + "] region=(" + skeleton.rx(i) + "," + skeleton.ry(i) + ","
                        + skeleton.rz(i) + ") frag=" + skeleton.fragmentId(i)
                        + " portal=" + (skeleton.hasPortal(i) ? xyz(skeleton.portalCell(i)) : "-"));
            }
        }

        RegionCostField field = null;
        if (useField) {
            RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(srx, sry, srz, grx, gry, grz, 3);
            field = RegionPathfinder.costToGoalField(grid, MINY, goalFloor, startFloor,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(),
                    RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box);
        }
        NavGridView view = new NavGridView(MINY, sections);
        BlockPathPlan plan = BlockPathfinder.findPath(view, startFloor, goalFloor, caps, null,
                cuboidCap(startFloor, goalFloor), null, MovementContext.MODE_STANDING, null, field);
        System.out.println("  BLOCK target=goalFloor expansions=" + BlockPathfinder.lastExpansions()
                + " partial=" + BlockPathfinder.lastWasPartial());
        if (plan == null) {
            System.out.println("  PLAN = null");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size() && i < 24; i++) sb.append(' ').append(xyz(plan.waypoint(i)));
        System.out.println("  size=" + plan.size() + " waypoints[0..23]:" + sb);
    }

    private static String xyz(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    @Test
    void repro() {
        // Faithful superflat straddling the origin. Floor cells are the stone top at y=-60.
        ConcurrentHashMap<Long, NavSection[]> world = flatWorld(-5, 5, -5, 5);
        BlockPos s1 = new BlockPos(-2, FLOOR_Y, 9), g1 = new BlockPos(-2, FLOOR_Y, -1);
        runCase("REPRO1 straddle z=0", world, s1, g1, false);
        runCase("REPRO1 straddle z=0", world, s1, g1, true);

        // Origin-shifted positive control (no boundary crossing).
        BlockPos s2 = new BlockPos(34, FLOOR_Y, 41), g2 = new BlockPos(34, FLOOR_Y, 31);
        runCase("CONTROL positive", world, s2, g2, false);
        runCase("CONTROL positive", world, s2, g2, true);

        // LOAD-STATE variants of REPRO 1: what if only some chunks are built at plan time?
        // start chunk = (-1,0), goal chunk = (-1,-1).
        // (b) ONLY the start chunk built (goal + surroundings read optimistic-AIR):
        runCase("REPRO1 only-start-chunk", partialWorld(new int[][]{{-1, 0}}), s1, g1, true);
        // (c) start + goal chunks built, but NOT the ±1 neighbour ring:
        runCase("REPRO1 start+goal-chunks", partialWorld(new int[][]{{-1, 0}, {-1, -1}}), s1, g1, true);
        // (d) a 3x3 ring around start+goal built, rest unbuilt (a small loaded island):
        runCase("REPRO1 3x3-island", partialWorld(new int[][]{
                {-2, 1}, {-1, 1}, {0, 1}, {-2, 0}, {-1, 0}, {0, 0},
                {-2, -1}, {-1, -1}, {0, -1}, {-2, -2}, {-1, -2}, {0, -2}}), s1, g1, true);
    }
}
