package com.orebit.mod.worldmodel.pathing;

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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof that the A* walker routes AROUND teleport portals (portal-avoidance arc). A NavBlock
 * portal cell classifies {@link com.orebit.mod.worldmodel.navblock.NavBlock#SHAPE_EMPTY SHAPE_EMPTY}
 * (empty collision), so before this arc the walker could step its body through one and get teleported;
 * {@code MovementContext.passable} now subtracts {@code NavBlock.isPortal} (mirroring the {@code isBubble}
 * exclusion in {@code isSwimmableWater}), so the bot never OCCUPIES a portal cell — feet, head, or a swept
 * jump-arc prism cell.
 *
 * <p><b>Corridor regime.</b> One sealed stone section carries a straight 1-wide corridor with a short
 * z=8 detour around a single obstacle cell. With an end portal (then a nether portal) at the obstacle the
 * walker must take the detour (the portal cell is non-passable); with the same cell left as AIR it walks
 * straight through — the contrast proves the PORTAL, not the geometry, caused the detour.
 *
 * <p><b>Follower-approach regime.</b> Making portal cells non-passable must NOT starve
 * {@code BotPortalFollower}: it goals the A* at {@code portalTarget.below()} and relies on the ±1 xz / ±2 y
 * goal tolerance to stop ADJACENT to the portal (then a manual walk-in ENTERs). {@link
 * #followerApproachStillReachesTheToleranceBoxBesideANetherPortal} pins that the search to that goal still
 * succeeds, ending in the tolerance box without ever standing with feet in the portal.
 *
 * <p>Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class PortalAvoidanceTest {

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

    private static final BlockPos START = new BlockPos(2, 0, 6);
    private static final BlockPos GOAL = new BlockPos(12, 0, 6);
    // The obstacle sits at (7,1,6) — the feet cell of floor node (7,0,6). Plan waypoints are STAND positions
    // (floorCell.above()), so the waypoint of that node is exactly the obstacle cell (OBST_X, 1, OBST_Z).
    private static final int OBST_X = 7, OBST_Y = 1, OBST_Z = 6;

    // Walk-only caps (no break/place — the stone maze confines; portals are unbreakable anyway), admissible
    // greedyWeight 1.0 so the returned path is the true cost-optimal one (deterministic, no greedy wobble).
    private static final BotCaps WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    // ---- Corridor regime: the walker routes around a portal, straight through air ---------------------

    @Test
    void walkerRoutesAroundAnEndPortal() {
        BlockPathPlan plan = BlockPathfinder.findPath(
                buildCorridor(Blocks.END_PORTAL.defaultBlockState()), START, GOAL, WALK);

        assertNotNull(plan, "the corridor has a clear detour — the bot must still reach the goal");
        assertReachedGoal(plan);
        assertFalse(contains(plan, OBST_X, OBST_Y, OBST_Z),
                "the bot must not occupy the end-portal cell (non-passable → route around, no teleport)");
        assertTrue(anyAtZ(plan, 8), "the bot should take the carved z=8 detour around the end portal");
    }

    @Test
    void walkerRoutesAroundANetherPortal() {
        BlockPathPlan plan = BlockPathfinder.findPath(
                buildCorridor(Blocks.NETHER_PORTAL.defaultBlockState()), START, GOAL, WALK);

        assertNotNull(plan, "the corridor has a clear detour — the bot must still reach the goal");
        assertReachedGoal(plan);
        assertFalse(contains(plan, OBST_X, OBST_Y, OBST_Z),
                "the walker must not occupy the nether-portal cell (the FOLLOWER enters portals, not the walker)");
        assertTrue(anyAtZ(plan, 8), "the bot should take the carved z=8 detour around the nether portal");
    }

    @Test
    void airInPlaceOfThePortalLetsTheWalkerGoStraight() {
        // Contrast: the SAME corridor with the obstacle cell left as AIR — the straight line is now clear, so
        // the bot walks through it and never takes the detour. Proves the PORTAL (not the geometry) forces the
        // detour in the two tests above.
        BlockPathPlan plan = BlockPathfinder.findPath(
                buildCorridor(Blocks.AIR.defaultBlockState()), START, GOAL, WALK);

        assertNotNull(plan, "the straight corridor is open — the bot must reach the goal");
        assertReachedGoal(plan);
        assertTrue(contains(plan, OBST_X, OBST_Y, OBST_Z),
                "with the cell air the bot walks the straight line through (OBST_X,1,OBST_Z)");
        assertFalse(anyAtZ(plan, 8), "the bot has no reason to take the z=8 detour when the cell is air");
    }

    // ---- Follower-approach regime: the A* goal at portalTarget.below() still reaches the tolerance box ----

    @Test
    void followerApproachStillReachesTheToleranceBoxBesideANetherPortal() {
        // The follower's bottom portal cell (the seek target) and the A* goal floor it drives to. Standing ON
        // the goal floor would put the bot's feet IN the portal (non-passable now), but the ±1 xz / ±2 y goal
        // tolerance lets the search stop on an adjacent standable floor — exactly where the ENTER walk-in
        // takes over. This is the proof the avoidance doesn't starve the follower's approach.
        BlockPos portalTarget = new BlockPos(8, 1, 6);   // bottom NETHER_PORTAL cell of the column
        BlockPos goalFloor = portalTarget.below();       // == what BotPortalFollower passes to A*

        BlockPathPlan plan = BlockPathfinder.findPath(buildApproach(), START, goalFloor, WALK);

        assertNotNull(plan, "the approach must succeed — the search stops adjacent to the portal");
        BlockPos last = plan.waypoint(plan.size() - 1);
        // Within the search's default ±1 xz / ±2 y tolerance of the goal STAND position (goalFloor.above()).
        assertTrue(Math.abs(last.getX() - portalTarget.getX()) <= 1
                        && Math.abs(last.getZ() - portalTarget.getZ()) <= 1
                        && Math.abs(last.getY() - portalTarget.getY()) <= 2,
                "the plan must end within the goal tolerance box beside the portal; ended at " + last);
        assertFalse(contains(plan, portalTarget.getX(), portalTarget.getY(), portalTarget.getZ()),
                "the approach must NOT require the bot to stand with its feet in the portal cell");
    }

    // ---- assertions / grid builders -------------------------------------------------------------------

    private static void assertReachedGoal(BlockPathPlan plan) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - GOAL.getX()) <= 1 && Math.abs(last.getZ() - GOAL.getZ()) <= 1,
                "the plan should end at the goal; ended at " + last);
    }

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
     * One sealed stone section (chunk 0,0) with two carved routes (air at y=1..2 over stone floors at y=0):
     * the straight corridor {@code z=6, x=2..12} whose body cell {@code (7,1,6)} holds {@code obstacle}, and
     * a clear detour leaving it at {@code x=5} (via {@code z=7} to {@code z=8}), running {@code x=5..9} along
     * {@code z=8}, and rejoining at {@code x=9}. When {@code obstacle} is a portal the straight body cell is
     * non-passable and the bot detours; when it is AIR the straight line is clear.
     */
    private static NavGridView buildCorridor(BlockState obstacle) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 12; x++) carve(s, air, x, 6);   // straight corridor
        s.set(OBST_X, OBST_Y, OBST_Z, obstacle);             // the obstacle in the body path
        carve(s, air, 5, 7);                                 // detour out at x=5 ...
        for (int x = 5; x <= 9; x++) carve(s, air, x, 8);    // ... along z=8 ...
        carve(s, air, 9, 7);                                 // ... and back in at x=9

        return oneSectionColumn(s);
    }

    /**
     * A flat approach: a straight stone-floored corridor {@code z=6, x=2..8} (air at y=1..2), with a single
     * NETHER_PORTAL cell at {@code (8,1,6)} — the feet cell of floor node {@code (8,0,6)}, which is therefore
     * unstandable. The nearest standable floor to the goal {@code (8,0,6)} is {@code (7,0,6)}, one cell west.
     */
    private static NavGridView buildApproach() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 8; x++) carve(s, air, x, 6);
        s.set(8, 1, 6, Blocks.NETHER_PORTAL.defaultBlockState());

        return oneSectionColumn(s);
    }

    /** Carve a 2-tall walking gap (air at y=1..2) over the stone floor at column {@code (x, z)}. */
    private static void carve(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        s.set(x, 1, z, air);
        s.set(x, 2, z, air);
    }

    /** A section container pre-filled with solid stone (routes are carved out of it). */
    private static PalettedContainer<BlockState> filledStone(BlockState air, BlockState stone) {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    /** Wrap a floor section (chunk 0,0) under three air sections into a single-column NavGridView. */
    private static NavGridView oneSectionColumn(PalettedContainer<BlockState> floor) {
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(floor, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { section, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }
}
