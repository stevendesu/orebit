package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.orebit.mod.pathfinding.blockpathfinder.movements.Swim;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the <b>fluid-as-a-medium</b> design (NOTES-vanilla-fluid-physics.md §1/§3): the upright
 * {@link Swim} is six-directional, enters a fluid INTERIOR (not merely a surface), owns the vertical axis alone
 * now that {@code SprintSwim} has none, and yields the lateral axis to the prone family only where that family
 * can actually make progress.
 *
 * <p>The motivating scene is the flagship waterfall at {@code (154,*,104)}, 2026-08-06: the bot could not walk
 * into the body of a fall (every {@code Swim} rung demanded an open-AIR head), so the planner bridged a cobble
 * to the one spread cell that had air above it; and once inside, the only way up was a prone sprint-swim whose
 * pure-vertical rungs inject lateral drift that ejects the bot from a 1×1 shaft. Both are structural, and both
 * are reproduced below at 1×1 scale.
 *
 * <p>Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class FluidMediumSwimTest {

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

    private static final RegionBound BOUND = new RegionBound(0, 15, 0, 15, 0, 15);

    // ---- §3.2 ENTRY: a dry bot walks into the BODY of a fall, no surface cell required -------------------

    /**
     * The waterfall entry, minimised: a dry bot on a ledge beside a water column whose cell at the bot's own
     * feet height has water ABOVE it too (i.e. the interior of a fall, not its surface). Before §3.2 the
     * destination-head test was {@code passable}, which excludes fluid, so this rejected — and because the
     * rejection was a {@code break} it also killed the downward scan, leaving the column wholly unenterable.
     */
    @Test
    void dryBotEntersASubmergedColumnSideways() {
        // Dry ledge at x=4, a 3-wide submerged span at x=5..7 (water at the bot's own feet height AND above
        // it — a fall interior), dry standable bank at x=8..9. BotCaps.DEFAULT cannot break or place, so if a
        // route exists the bot swam it; there is no bridge available.
        NavGridView grid = submergedSpan();
        BlockPathPlan plan = BlockPathfinder.findPath(grid,
                new BlockPos(4, 5, 8), new BlockPos(9, 5, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "a dry bot must be able to walk into the body of a fall" + dump(plan));
        assertTrue(contains(plan, MovementRegistry.SWIM),
                "entry into submerged fluid is a Swim rung — no bridge, no surface cell required" + dump(plan));
    }

    /** The same crossing with a SOLID head over the fluid is refused: that is a 1-tall gap the upright pose
     *  does not fit in (vanilla falls back through CROUCHING to SWIMMING), so it is the prone family's
     *  business, not Swim's. The head test is "not solid", never "anything". With no 2-deep water anywhere to
     *  initiate a sprint-swim, the prone family cannot take it either — so there is no route at all. */
    @Test
    void aOneTallSubmergedGapIsNotAnUprightSwimEntry() {
        NavGridView grid = cappedSpan();
        BlockPathPlan plan = BlockPathfinder.findPath(grid,
                new BlockPos(4, 5, 8), new BlockPos(9, 5, 8), BotCaps.DEFAULT, BOUND);

        assertNull(plan, "a 1-tall submerged channel is not enterable upright, and there is nowhere to go "
                + "prone — so it must be impassable" + dump(plan));
    }

    // ---- §3.1 + §4 VERTICAL: the upright rise owns the column, and the prone family has no verticals ------

    /**
     * A sealed 1×1 water shaft: the bot starts at the bottom and the goal is at the top. There is no lateral
     * water neighbour anywhere in the shaft, so {@code SprintSwim} can never make progress — and since §4
     * deleted its verticals it could not climb even if it went prone. The ONLY thing that can reach the top is
     * the upright {@link Swim} rise, which is exactly the case the flagship failed.
     */
    @Test
    void aOneByOneShaftIsClimbedByTheUprightRise() {
        NavGridView grid = shaft();
        BlockPathPlan plan = BlockPathfinder.findPath(grid,
                new BlockPos(8, 2, 8), new BlockPos(8, 8, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "a 1x1 water shaft must be climbable — the upright rise is the only rung that can");
        assertTrue(contains(plan, MovementRegistry.SWIM), "the climb is upright Swim rungs" + dump(plan));
        assertFalse(contains(plan, MovementRegistry.SPRINT_SWIM),
                "SprintSwim has no verticals since §4 — it must not appear in a pure vertical climb" + dump(plan));
        // The bot starts with feet AND head submerged, so MODE_AUTO seeds it PRONE — and the first thing the
        // plan does is stand up in place. That edge did not exist before §5; without it a bot that starts
        // submerged has no upright rung available and cannot use the rise at all.
        assertTrue(contains(plan, MovementRegistry.END_SPRINT_SWIM),
                "a bot seeded PRONE must EndSprintSwim before it can use the upright rise" + dump(plan));
        // Start feet y=3. The plan stops at feet y=7 rather than the goal floor's y=9 because the search's
        // arrival tolerance is DEFAULT_GOAL_TOL_Y = 2 (floor 6 vs goal floor 8) — a completed climb, not a
        // stall; the assertion pins "it actually climbed" without re-encoding that tolerance.
        assertTrue(lastWaypoint(plan).getY() >= 7,
                "the plan must actually climb the shaft, not stall at the bottom" + dump(plan));
        assertEquals(8, lastWaypoint(plan).getX(), "a 1x1 shaft cannot leave its column" + dump(plan));
    }

    /** The mirror: the same shaft descended. Sinking is cheaper than rising because vanilla's in-fluid gravity
     *  assists it — {@code DOWN_COST < UP_COST} is a derivation, not a preference, so pin it. */
    @Test
    void sinkingIsCheaperThanRising() {
        assertTrue(Swim.DOWN_COST < Swim.UP_COST,
                "gravity assists the sink and opposes the rise: " + Swim.DOWN_COST + " vs " + Swim.UP_COST);
        assertTrue(Swim.COST < Swim.SUBMERGED_COST,
                "the submerged paddle (1.97 b/s) is slower than the surface paddle (2.2 b/s)");
    }

    // ---- §5.3 THE DOMINANCE GATE: scoped to lateral rungs, and asks about PROGRESS ------------------------

    /**
     * The gate's whole purpose, and the reason it may not be deleted: in open water the prone
     * {@code StartSprintSwim → SprintSwim} branch is strictly cheaper per block (3.56 vs 9.09) but the search
     * is greedy (w = 2.0), so a lateral surface paddle toward the goal is f-DECREASING while the in-place dive
     * is an f-hill — the dive node is never popped unless the paddle is suppressed. A wide pool must therefore
     * be crossed prone.
     */
    @Test
    void aWidePoolIsCrossedProneNotPaddled() {
        NavGridView grid = pool();
        BlockPathPlan plan = BlockPathfinder.findPath(grid,
                new BlockPos(2, 5, 8), new BlockPos(12, 5, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "the pool must be crossable");
        assertTrue(contains(plan, MovementRegistry.SPRINT_SWIM),
                "a wide pool is sprint-swum — the dominance gate suppresses the slow lateral paddle so the "
                        + "greedy search can find the dive at all");
    }

    /**
     * And the re-scoping that §5.3 required: the gate must NOT fire on the vertical rungs. The 1×1 shaft above
     * satisfies the OLD gate's question ("can sprint-swim initiate here?" — feet and head both water) at every
     * level, so the old predicate would have suppressed every rung in the column and left the bot with nothing.
     * This pins that the shaft climb survives the gate.
     */
    @Test
    void theGateNeverSuppressesTheVerticalRungs() {
        NavGridView grid = shaft();
        BlockPathPlan plan = BlockPathfinder.findPath(grid,
                new BlockPos(8, 2, 8), new BlockPos(8, 8, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "every cell of a 1x1 shaft is 2-deep, so the OLD initiate-based gate would have "
                + "suppressed the entire column and produced no path");
    }

    // ---- scene builders ---------------------------------------------------------------------------------

    /**
     * A lane on {@code z=8} with stone floor at {@code y=5} throughout: dry stand cells at {@code x=4} and
     * {@code x=8..9} (bodies carved to air), and a 3-wide SUBMERGED span at {@code x=5..7} where BOTH body
     * cells ({@code y=6} and {@code y=7}) are water — a fall interior, never a surface. Everything off the
     * lane stays solid, so the span is the only route.
     */
    private static NavGridView submergedSpan() {
        return lane(Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState());
    }

    /** The same lane with the span's HEAD cell solid stone — a 1-tall submerged channel the upright pose
     *  cannot fit in, and (no 2-deep water anywhere) nowhere to go prone either. */
    private static NavGridView cappedSpan() {
        return lane(Blocks.WATER.defaultBlockState(), null);
    }

    /**
     * Shared lane builder: floor stone at {@code y=5}, dry stand cells at {@code x=4} and {@code x=8..9}, and
     * the span {@code x=5..7} filled with {@code feet} at {@code y=6} and {@code head} at {@code y=7}
     * ({@code null} head leaves the stone in place).
     */
    private static NavGridView lane(BlockState feet, BlockState head) {
        PalettedContainer<BlockState> s = solid();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x : new int[] {4, 8, 9}) {
            s.set(x, 6, 8, air);
            s.set(x, 7, 8, air);
        }
        for (int x = 5; x <= 7; x++) {
            s.set(x, 6, 8, feet);
            if (head != null) s.set(x, 7, 8, head);
        }
        return grid(s);
    }

    /** A sealed 1×1 water shaft at {@code (8,*,8)} from {@code y=3} to {@code y=10}, standing on stone at
     *  {@code y=2}, with two air cells above the water so the bot can top out upright. No lateral water
     *  anywhere, so the prone family can never make progress out of it. */
    private static NavGridView shaft() {
        PalettedContainer<BlockState> s = solid();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int y = 3; y <= 10; y++) s.set(8, y, 8, water);
        s.set(8, 11, 8, air);
        s.set(8, 12, 8, air);
        return grid(s);
    }

    /** A wide, 2-deep pool: water at {@code y=6..7} across {@code x=2..12} on the {@code z=8} lane, with dry
     *  standable banks at each end and air above so the bot can be upright at the surface. */
    private static NavGridView pool() {
        PalettedContainer<BlockState> s = solid();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = 2; x <= 12; x++) {
            for (int y = 6; y <= 7; y++) s.set(x, y, 8, water);
            s.set(x, 8, 8, air);
            s.set(x, 9, 8, air);
        }
        // Dry standable ends: floor stone at y=5 stays, bodies carved (the start/goal stand cells).
        for (int x : new int[] {2, 12}) {
            for (int y = 6; y <= 9; y++) s.set(x, y, 8, air);
        }
        return grid(s);
    }

    private static PalettedContainer<BlockState> solid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        return s;
    }

    private static NavGridView grid(PalettedContainer<BlockState> s) {
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        if (plan == null) return false;
        for (int i = 0; i < plan.size(); i++) if (plan.movement(i) == move) return true;
        return false;
    }

    private static BlockPos lastWaypoint(BlockPathPlan plan) {
        return plan.waypoint(plan.size() - 1);
    }

    /** The plan, rendered for an assertion message — a failure here is almost always "which rungs did it
     *  actually pick", and guessing that from a bare boolean wastes a whole run. */
    private static String dump(BlockPathPlan plan) {
        if (plan == null) return "\n  (no plan)";
        StringBuilder sb = new StringBuilder("\n  plan:");
        for (int i = 0; i < plan.size(); i++) {
            sb.append("\n    ").append(i).append(' ').append(plan.waypoint(i)).append(" via ")
              .append(plan.movement(i) == null ? "-" : plan.movement(i).getClass().getSimpleName());
        }
        return sb.toString();
    }
}
