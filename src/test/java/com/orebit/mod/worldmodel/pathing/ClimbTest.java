package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Headless proof of the {@code Climb} movement (ladders / vines): a sealed stone maze where a climbable
 * strip is the ONLY route ({@link BotCaps#DEFAULT} — no break, no place, jump 1), so a found plan proves
 * the grab-entry + up/down-climb candidates work end to end through the real classifier (ladder =
 * {@code SHAPE_OTHER} wall geometry + CLIMB bit; vine = empty shape + CLIMB bit). Negative: with the
 * strip removed the goal is unreachable. Lives in this package to reach {@link NavGridView}'s
 * package-private synthetic constructor. Not testable headless: the 0.2/−0.15 climb physics, the
 * jumping-flag climb trigger, and the Ascend top-out — those are the in-game verification pass.
 */
class ClimbTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 31, 0, 15);
    /** Start floor: the room beside the climb column. */
    private static final BlockPos START = new BlockPos(2, 0, 8);
    /**
     * Goal floor: the top of the wall next to the climb column. The search's ±1 xz / ±2 y arrival
     * tolerance means the upper climb nodes at x=3 satisfy it — reaching them at all REQUIRES climbing
     * (no break/place, and Ascend gains only 1 onto standable ground, of which the shaft has none).
     */
    private static final BlockPos GOAL = new BlockPos(4, 5, 8);

    @Test
    void ladderIsTheOnlyWayUpTheWall() {
        NavGridView grid = buildWall(Blocks.LADDER.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a walk-only bot should climb the ladder strip to the top of the wall");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the plan should contain Climb steps");
    }

    @Test
    void vineIsClimbableToo() {
        // Vines are the OTHER climbable shape: empty collision (passable) rather than a SHAPE_OTHER wall.
        NavGridView grid = buildWall(Blocks.VINE.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a walk-only bot should climb the vine strip to the top of the wall");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the plan should contain Climb steps");
    }

    @Test
    void withoutTheLadderTheTopIsUnreachable() {
        NavGridView grid = buildWall(Blocks.AIR.defaultBlockState()); // same carve, no climbable strip
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "with no ladder (and no break/place) the top of the wall must be unreachable");
    }

    @Test
    void climbsDownALadderShaftDeeperThanAnySurvivableFall() {
        // A 21-deep 1×1 shaft (deeper than BotCaps.DEFAULT's maxFallDistance=16) with a ladder strip:
        // the only way to the pit floor is climbing down. (Fall can't even step in: the ladder cells are
        // SHAPE_OTHER walls to its step-off clearance check — the grab entry is the sole way onto the
        // column, which is exactly the down-climb geometry being proven.)
        NavGridView grid = buildShaft();
        BlockPos start = new BlockPos(2, 21, 8);   // top platform beside the shaft
        BlockPos goal = new BlockPos(3, 0, 8);     // the pit floor at the shaft bottom
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the bot should climb down the ladder to the pit floor");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the descent should be Climb steps");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(last.getY() <= 3, "the plan should end near the pit floor; ended at " + last);
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return true;
        }
        return false;
    }

    /**
     * One sealed stone section: a start room at {@code (2, 1..3, 8)}, a climb strip at {@code (3, 1..5, 8)}
     * ({@code climbable} — ladder, vine, or air for the negative), open cells above the strip at
     * {@code (3, 6..7, 8)} (the top climb node's head + the Ascend takeoff clearance), and a cleared body
     * over the wall top {@code (4, 6..7, 8)} (the wall cell {@code (4,5,8)} itself stays stone — the goal
     * floor). Everything else is solid, so the strip is the only route up.
     */
    private static NavGridView buildWall(BlockState climbable) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidSection();

        final int z = 8;
        for (int y = 1; y <= 3; y++) s.set(2, y, z, air);   // start room (floor (2,0,8))
        for (int y = 1; y <= 5; y++) s.set(3, y, z, climbable); // the climb strip
        s.set(3, 6, z, air);                                 // top climb node's head cell
        s.set(3, 7, z, air);                                 // Ascend takeoff clearance (source y+3)
        s.set(4, 6, z, air);                                 // body over the wall top (goal floor (4,5,8))
        s.set(4, 7, z, air);

        return view(classify(s), null);
    }

    /**
     * Two stacked stone sections (y 0..31): a top platform room at {@code (2, 22..23, 8)} (floor
     * {@code (2,21,8)}), a 1×1 shaft at {@code x=3} with a ladder strip {@code (3, 1..22, 8)} and an open
     * head cell {@code (3,23,8)} for the grab entry; the pit floor {@code (3,0,8)} stays stone.
     */
    private static NavGridView buildShaft() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState ladder = Blocks.LADDER.defaultBlockState();
        PalettedContainer<BlockState> s0 = solidSection();  // y 0..15
        PalettedContainer<BlockState> s1 = solidSection();  // y 16..31

        final int z = 8;
        s1.set(2, 22 - 16, z, air);                          // start room body (floor (2,21,8))
        s1.set(2, 23 - 16, z, air);
        for (int y = 1; y <= 15; y++) s0.set(3, y, z, ladder);
        for (int y = 16; y <= 22; y++) s1.set(3, y - 16, z, ladder);
        s1.set(3, 23 - 16, z, air);                          // head over the ladder top (grab entry)

        return view(classify(s0), classify(s1));
    }

    private static PalettedContainer<BlockState> solidSection() {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    private static NavSection classify(PalettedContainer<BlockState> states) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());
        return section;
    }

    /** A one-chunk synthetic grid: section0 at y 0..15, an optional section1 at y 16..31, air above. */
    private static NavGridView view(NavSection s0, NavSection s1) {
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { s0, s1 != null ? s1 : airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
