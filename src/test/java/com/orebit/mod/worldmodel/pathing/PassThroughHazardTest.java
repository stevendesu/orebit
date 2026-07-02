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

/**
 * Headless proof of the caps-honest pass-through hazard surcharge ({@code MovementContext.bodyTransitCost}
 * / {@code DAMAGE_TRANSIT_COST}, gated on {@link BotCaps#takesDamage}): the SAME maze, two bots, two routes.
 *
 * <p>A sealed stone section holds two carved routes from start to goal: a straight 10-step corridor with ONE
 * fire cell in the body path (cost {@code 10×FLAT + 40 ≈ 86}), and a 14-step clear detour ({@code ≈ 65}).
 * A <b>mortal</b> bot ({@code takesDamage = true}) must pay the 40-tick fire surcharge, so the detour is
 * cheaper and it routes around; an <b>invulnerable</b> bot ({@code takesDamage = false}) pays nothing and
 * walks straight through the fire — mirroring how the immune fall window already zeroes the Fall damage
 * penalty. Both searches run micro-only (no cuboid bound) with {@code greedyWeight = 1.0} so the returned
 * path is the true cost-optimal one (deterministic, no greedy wobble). Lives in this package to reach
 * {@link NavGridView}'s package-private synthetic constructor.
 */
class PassThroughHazardTest {

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
    // The fire cell sits at (7,1,6) — the body/feet cell of floor node (7,0,6). Plan waypoints are STAND
    // positions (floorCell.above(), see BlockPathfinder "Cells, not feet"), so the waypoint of that node
    // is exactly the fire cell's coordinates: (FIRE_X, 1, FIRE_Z).
    private static final int FIRE_X = 7, FIRE_Y = 1, FIRE_Z = 6;

    // Walk-only caps (no break/place — the stone maze confines), admissible weight 1.0 for optimal paths;
    // the ONLY difference between the two bots is the takesDamage flag under test.
    private static final BotCaps MORTAL_WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, false, false,
            BotCaps.UNBREAKABLE, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    private static final BotCaps IMMUNE_WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false, false, false,
            BotCaps.UNBREAKABLE, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    @Test
    void mortalBotDetoursAroundTheFire() {
        BlockPathPlan plan = BlockPathfinder.findPath(buildMaze(), START, GOAL, MORTAL_WALK);

        assertNotNull(plan, "the maze has a clear detour — a mortal bot must still reach the goal");
        assertReachedGoal(plan);
        assertFalse(contains(plan, FIRE_X, FIRE_Y, FIRE_Z),
                "a mortal bot must not walk its body through the fire cell (40-tick surcharge > 4-step detour)");
        assertTrue(anyAtZ(plan, 8), "the mortal bot should take the carved z=8 detour around the fire");
    }

    @Test
    void invulnerableBotTakesTheShortRouteThroughTheFire() {
        BlockPathPlan plan = BlockPathfinder.findPath(buildMaze(), START, GOAL, IMMUNE_WALK);

        assertNotNull(plan, "the straight corridor is open — an immune bot must reach the goal");
        assertReachedGoal(plan);
        assertTrue(contains(plan, FIRE_X, FIRE_Y, FIRE_Z),
                "an invulnerable bot pays NO damage surcharge — the straight route through the fire is cheapest");
        assertFalse(anyAtZ(plan, 8), "the immune bot has no reason to take the longer z=8 detour");
    }

    /** The plan genuinely ended at the goal (within the search's ±1 horizontal arrival tolerance) —
     *  guards the waypoint-shape assertions against a spurious partial/short plan. */
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
     * the straight corridor {@code z=6, x=2..12} with FIRE at {@code (7,1,6)}, and a clear detour leaving it
     * at {@code x=5} (via {@code z=7} to {@code z=8}), running {@code x=5..9} along {@code z=8}, and
     * rejoining at {@code x=9}. Straight = 10 steps + one fire body cell; detour = 14 steps, hazard-free.
     */
    private static NavGridView buildMaze() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState fire = Blocks.FIRE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone); // solid rock everywhere; the routes are carved out below
                }
            }
        }

        for (int x = 2; x <= 12; x++) carve(s, air, x, 6);   // straight corridor
        s.set(FIRE_X, 1, FIRE_Z, fire);                      // the hazard: fire in the body path
        carve(s, air, 5, 7);                                 // detour out at x=5 ...
        for (int x = 5; x <= 9; x++) carve(s, air, x, 8);    // ... along z=8 ...
        carve(s, air, 9, 7);                                 // ... and back in at x=9

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection }; // y 0..63 (only y 0..15 used)
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    /** Carve a 2-tall walking gap (air at y=1..2) over the stone floor at column {@code (x, z)}. */
    private static void carve(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        s.set(x, 1, z, air);
        s.set(x, 2, z, air);
    }
}
