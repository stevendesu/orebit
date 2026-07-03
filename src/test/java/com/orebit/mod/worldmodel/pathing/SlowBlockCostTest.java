package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Headless proof that slow blocks are priced, not ignored (the slow-FLOOR {@link Traverse#SLOW_SURCHARGE}
 * and the through-slow {@code MovementContext.WEB_TRANSIT_COST} body term):
 *
 * <ul>
 *   <li><b>Slow floor</b> — of two equal-length corridors, one floored with soul soil (SURFACE_SLOW; note
 *       soul <i>sand</i>'s 14/16-tall collision box classifies {@code SHAPE_OTHER} = un-standable in
 *       {@link com.orebit.mod.worldmodel.navblock.NavBlock}, so the strip is soul <i>soil</i>, the same
 *       slow class), the search takes the clean one.</li>
 *   <li><b>Cobweb corridor</b> — a bot that may dig tunnels a few blocks of dirt around a webbed passage
 *       (~15 ticks per bare-hand dirt break, ≈ 200 total) instead of paying the ~88-tick-per-cell web
 *       surcharge (≈ 310 total); a walk-only bot has no alternative and pushes through the webs — proving
 *       webs are costed, not blocked.</li>
 *   <li><b>Macro run</b> — the SAME flat straight run costs exactly {@code walked × (FLAT + SLOW_SURCHARGE)}
 *       over a soul-soil floor vs {@code walked × FLAT} over stone, pinning that a collapsed macro run
 *       charges the slow surcharge for EVERY cell (MACRO-MOVEMENTS §3b), not once per jump.</li>
 * </ul>
 *
 * <p>Route-choice searches run micro-only (no cuboid bound) with {@code greedyWeight = 1.0} so the returned
 * path is the true cost-optimal one; the macro test passes a corridor bound so the cuboid collapse actually
 * fires. Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class SlowBlockCostTest {

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

    // Weight-1.0 (optimal) walk-only and dig-capable caps; damage flag irrelevant here (nothing damages).
    // DIG's maxBreakHardness is pinned to 10 so dirt (quantized hardness 3) stays diggable but the cobweb
    // (hardness 20) sits outside the break-through fold's cap: these headless searches run with no
    // MiningModel table, where a web break would price at 0 and punching straight through would beat the
    // dig-around under test (the same free-break tie the hazard/protected suites pin their caps against).
    private static final BotCaps WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    private static final BotCaps DIG = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, true, false,
            10, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    // ---- (a) slow floor: clean corridor beats an equal-length soul-soil corridor --------------

    @Test
    void cleanCorridorBeatsAnEqualLengthSoulSoilCorridor() {
        BlockPos start = new BlockPos(2, 0, 7);
        BlockPos goal = new BlockPos(12, 0, 7);
        BlockPathPlan plan = BlockPathfinder.findPath(twinCorridors(), start, goal, WALK);

        assertNotNull(plan, "both corridors reach the goal — a plan must exist");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - goal.getX()) <= 1 && Math.abs(last.getZ() - goal.getZ()) <= 1,
                "the plan should end at the goal; ended at " + last);
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            assertFalse(wp.getZ() == 5 && wp.getX() >= 3 && wp.getX() <= 11,
                    "the equal-length soul-soil corridor (z=5) costs SLOW_SURCHARGE per step — the clean "
                            + "z=9 corridor must win; stepped on slow floor at " + wp);
        }
        assertTrue(anyAtZ(plan, 9), "the plan should run the clean z=9 corridor");
    }

    // ---- (b) cobweb corridor vs mine-around ---------------------------------------------------

    @Test
    void diggerMinesAroundTheWebbedPassage() {
        BlockPos start = new BlockPos(2, 0, 8);
        BlockPos goal = new BlockPos(12, 0, 8);
        NavGridView grid = webbedDirtCorridor();

        BlockPathPlan dig = BlockPathfinder.findPath(grid, start, goal, DIG);
        assertNotNull(dig, "the dirt box is minable everywhere — the digger must reach the goal");
        BlockPos last = dig.waypoint(dig.size() - 1);
        assertTrue(Math.abs(last.getX() - goal.getX()) <= 1 && Math.abs(last.getZ() - goal.getZ()) <= 1,
                "the dig plan should end at the goal; ended at " + last);

        // The webbed passage cells (x 6..8 at z=8) each cost ~88 ticks of through-slow; digging a short
        // dirt bypass (~15 ticks/block bare-hand) is far cheaper — the digger must not enter any of them.
        // Waypoints are STAND positions (floorCell.above()), so standing in a webbed column is y == 1.
        for (int i = 0; i < dig.size(); i++) {
            BlockPos wp = dig.waypoint(i);
            assertFalse(wp.getY() == 1 && wp.getZ() == 8 && wp.getX() >= 6 && wp.getX() <= 8,
                    "digging around is cheaper than the web surcharge — must not transit a webbed column; "
                            + "stood at " + wp);
        }
        boolean dugAnything = false;
        for (int i = 0; i < dig.size(); i++) {
            if (dig.edits(i) != null) { dugAnything = true; break; }
        }
        assertTrue(dugAnything, "the bypass is through solid dirt — the plan must carry break edits");

        // And the mined bypass really is cheaper than what the walk-only bot pays through the webs.
        BlockPathPlan walk = BlockPathfinder.findPath(grid, start, goal, WALK);
        assertNotNull(walk);
        assertTrue(dig.cost() < walk.cost(),
                "mining around (" + dig.cost() + ") must undercut pushing through the webs (" + walk.cost() + ")");
    }

    @Test
    void walkOnlyBotPushesThroughTheWebs() {
        BlockPos start = new BlockPos(2, 0, 8);
        BlockPos goal = new BlockPos(12, 0, 8);
        BlockPathPlan plan = BlockPathfinder.findPath(webbedDirtCorridor(), start, goal, WALK);

        // Webs are passable-but-slow, NOT walls: with no dig caps the only route is through them.
        assertNotNull(plan, "cobwebs must stay traversable (costed, not blocked) for a walk-only bot");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - goal.getX()) <= 1 && Math.abs(last.getZ() - goal.getZ()) <= 1,
                "the plan should end at the goal; ended at " + last);
        // Waypoints are STAND positions (floorCell.above()): standing in the middle webbed column is (7,1,8).
        assertTrue(contains(plan, 7, 1, 8), "the walk-only route runs straight through the webbed passage");
    }

    // ---- (c) macro run: a slow floor charges its surcharge for EVERY collapsed cell -----------

    @Test
    void macroRunOverSlowFloorChargesTheSurchargePerCell() {
        BlockPos start = new BlockPos(2, 0, 8);
        BlockPos goal = new BlockPos(13, 0, 8);
        RegionBound corridor = new RegionBound(0, 15, 0, 4, 0, 15); // in-chunk; enables the cuboid collapse

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        try {
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan clean = BlockPathfinder.findPath(
                    flatFloorWorld(Blocks.STONE.defaultBlockState()), start, goal, WALK, corridor);
            BlockPathPlan slow = BlockPathfinder.findPath(
                    flatFloorWorld(Blocks.SOUL_SOIL.defaultBlockState()), start, goal, WALK, corridor);

            assertNotNull(clean);
            assertNotNull(slow);

            // Both runs are the same straight flat walk; per the tick cost model the totals are EXACTLY
            // walked × per-step — the slow run's surcharge scales with every collapsed cell, so a macro
            // jump that charged SLOW_SURCHARGE once per JUMP (not per cell) fails the equality.
            int walkedClean = assertStraightRun(clean, start);
            int walkedSlow = assertStraightRun(slow, start);
            assertEquals(walkedClean * Traverse.FLAT_COST, clean.cost(), 0.01f,
                    "clean flat run must cost exactly walked × FLAT_COST");
            assertEquals(walkedSlow * (Traverse.FLAT_COST + Traverse.SLOW_SURCHARGE), slow.cost(), 0.01f,
                    "soul-soil run must cost exactly walked × (FLAT_COST + SLOW_SURCHARGE) — per CELL, "
                            + "even when the run is collapsed into macro jumps");
            assertTrue(slow.cost() > clean.cost(), "the slow floor must cost more than the clean one");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
        }
    }

    /** The plan is a straight flat +X run ending near the goal; returns the blocks walked. Waypoints are
     *  STAND positions (floorCell.above()), so a flat run over the y=0 floor holds waypoint y = start.y + 1. */
    private static int assertStraightRun(BlockPathPlan plan, BlockPos start) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertEquals(start.getZ(), last.getZ(), "a straight +X run must not wander in Z; ended at " + last);
        assertEquals(start.getY() + 1, last.getY(), "a flat run must not change level; ended at " + last);
        int walked = last.getX() - start.getX();
        assertTrue(walked >= 10, "the run must cover the corridor (goal tolerance ±1); walked " + walked);
        return walked;
    }

    // ---- helpers / fixtures --------------------------------------------------------------------

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    private static boolean anyAtZ(BlockPathPlan plan, int z) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.waypoint(i).getZ() == z) return true;
        }
        return false;
    }

    /**
     * One sealed stone section with two EQUAL-LENGTH corridors {@code x=3..11} at {@code z=5} (floor =
     * soul soil) and {@code z=9} (floor = stone), joined by connector columns spanning {@code z=5..9} at
     * {@code x=2} and {@code x=12}. Start/goal sit mid-connector, so both routes are 14 steps — the only
     * difference is the 9 slow-floor cells.
     */
    private static NavGridView twinCorridors() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState soulSoil = Blocks.SOUL_SOIL.defaultBlockState();

        PalettedContainer<BlockState> s = filled(stone, air);
        for (int z = 5; z <= 9; z++) {
            carve(s, air, 2, z);   // west connector
            carve(s, air, 12, z);  // east connector
        }
        for (int x = 3; x <= 11; x++) {
            carve(s, air, x, 5);   // slow corridor ...
            s.set(x, 0, 5, soulSoil); // ... floored with soul soil
            carve(s, air, x, 9);   // clean corridor
        }
        return singleSectionView(s, air);
    }

    /**
     * One sealed DIRT section (soft — bare-hand minable in ~15 ticks) with a single carved corridor
     * {@code z=8, x=2..12} whose middle three columns ({@code x=6..8}) hold a COBWEB at body height
     * ({@code y=1}). The only web-free route is a freshly-mined bypass.
     */
    private static NavGridView webbedDirtCorridor() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState cobweb = Blocks.COBWEB.defaultBlockState();

        PalettedContainer<BlockState> s = filled(dirt, air);
        for (int x = 2; x <= 12; x++) carve(s, air, x, 8);
        for (int x = 6; x <= 8; x++) s.set(x, 1, 8, cobweb);
        return singleSectionView(s, air);
    }

    /** A single-chunk flat world: the given floor block over all of y=0, air above (no live level). */
    private static NavGridView flatFloorWorld(BlockState floorBlock) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                s.set(x, 0, z, floorBlock);
            }
        }
        return singleSectionView(s, air);
    }

    private static PalettedContainer<BlockState> filled(BlockState fill, BlockState air) {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, fill);
                }
            }
        }
        return s;
    }

    /** Carve a 2-tall walking gap (air at y=1..2) over the floor at column {@code (x, z)}. */
    private static void carve(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        s.set(x, 1, z, air);
        s.set(x, 2, z, air);
    }

    /** Wrap one classified section (plus pure-air sections above) as the synthetic chunk (0,0) view. */
    private static NavGridView singleSectionView(PalettedContainer<BlockState> states, BlockState air) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection }; // y 0..63
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
