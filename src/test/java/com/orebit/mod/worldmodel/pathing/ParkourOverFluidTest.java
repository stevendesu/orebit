package com.orebit.mod.worldmodel.pathing;

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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the "arc-safe floor obstacle" parkour rule (issue 3): a sprint jump clears a 1-wide
 * gap whose FLOOR cell holds a no-collision obstacle — a lava/water SOURCE — because the arc keeps the
 * hitbox above the fluid (zero contact). The old gate {@code passable = SHAPE_EMPTY && fluid==0} wrongly
 * rejected the fluid floor cell and refused the jump; the fix relaxes the floor-obstacle cell to {@code
 * MovementContext.overJumpable} ({@code topY <= 16}, so any no-taller-than-a-full-block collision top —
 * air, fluid, décor) while the body-arc prism ({@code y+1..y+3}) stays strict passable.
 *
 * <p>Scene: a sealed stone section with a 1-wide corridor at {@code z=8}, floor at {@code y=0}. Takeoff at
 * {@code x=2}, a 1-wide gap at {@code x=3} whose floor cell is the obstacle, landing at {@code x=4},
 * goal at {@code x=5}; the corridor bodies {@code y=1..4} are open air and everything else is solid, so
 * the ONLY route to the goal is a flat 1-gap parkour over the obstacle cell.
 *
 * <ul>
 *   <li><b>Positive (lava / water):</b> the obstacle floor cell is a lava (then water) SOURCE — {@code
 *       topY 0}, no collision — so a PARKOUR crosses it and reaches the goal.</li>
 *   <li><b>Negative (fence):</b> a fence floor cell ({@code topY ≈ 24}) pokes into the feet path — NOT
 *       jumpable — so with parkour the only route, the goal is unreachable (proves we did not
 *       over-relax to "any non-standable cell").</li>
 *   <li><b>Negative (full solid):</b> a full stone floor cell is continuous ground — the bot WALKS
 *       across (Traverse); no PARKOUR is emitted (a full block is a STEP, not a gap).</li>
 * </ul>
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class ParkourOverFluidTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);
    private static final BlockPos START = new BlockPos(2, 0, 8);
    private static final BlockPos GOAL = new BlockPos(5, 0, 8);

    @Test
    void parkoursOverAOneWideLavaGap() {
        NavGridView grid = buildCorridor(Blocks.LAVA.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a bot should sprint-jump the 1-wide lava pool and reach the bank");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the crossing should be a PARKOUR jump over the lava floor cell");
        assertReachesGoal(plan);
    }

    @Test
    void parkoursOverAOneWideWaterGap() {
        NavGridView grid = buildCorridor(Blocks.WATER.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the same arc clears a 1-wide water pool (no-collision floor cell)");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the crossing should be a PARKOUR jump over the water floor cell");
        assertReachesGoal(plan);
    }

    @Test
    void refusesToJumpAFenceObstacle() {
        NavGridView grid = buildCorridor(Blocks.OAK_FENCE.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        // A fence floor cell (topY ≈ 24) clips the feet path — not jumpable. Parkour is the only route,
        // so the goal is unreachable. (Proves the relaxation did NOT admit tall obstacles.)
        assertNull(plan, "a fence pokes into the feet path — the gap must not be jumped");
    }

    @Test
    void walksAcrossAFullSolidBlockInsteadOfJumping() {
        NavGridView grid = buildCorridor(Blocks.STONE.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a full solid floor cell is continuous ground — walkable to the goal");
        assertFalse(contains(plan, MovementRegistry.PARKOUR),
                "a full block is a STEP, not a gap — the bot should Traverse, never PARKOUR over it");
        assertReachesGoal(plan);
    }

    private static void assertReachesGoal(BlockPathPlan plan) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - GOAL.getX()) <= 1 && Math.abs(last.getZ() - GOAL.getZ()) <= 1,
                "the plan should end at the goal; ended at " + last);
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return true;
        }
        return false;
    }

    /**
     * One sealed stone section (chunk 0,0) with a 1-wide corridor carved along {@code z=8}, floor at
     * {@code y=0}: takeoff {@code x=2}, the gap OBSTACLE cell {@code (3,0,8)} set to {@code obstacle},
     * landing {@code x=4}, goal {@code x=5}. Corridor bodies {@code y=1..4} are air; everything else is
     * solid stone, so the only way across the gap is a flat 1-gap parkour over the obstacle floor cell.
     */
    private static NavGridView buildCorridor(BlockState obstacle) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone); // solid rock; the corridor is carved out below
                }
            }
        }

        final int z = 8;
        for (int x = 1; x <= 6; x++) {
            for (int y = 1; y <= 4; y++) {
                s.set(x, y, z, air); // clear feet/head/apex air over the whole corridor
            }
        }
        s.set(3, 0, z, obstacle); // the gap's floor obstacle cell (lava / water / fence / stone)

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection }; // y 0..63 (only y 0..15 used)
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
