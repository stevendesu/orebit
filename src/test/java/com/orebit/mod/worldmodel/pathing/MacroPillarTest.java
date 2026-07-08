package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Headless proof that cuboid macro-Pillar collapses the open-air-pillar flood (MACRO-IMPLEMENTATION.md §10).
 * A goal hovering 30 blocks straight up over flat ground — reachable ONLY by pillaring — is FOUND with macro
 * collapse on, where the un-collapsed search floods the corridor toward the expansion cap and fails (the
 * canonical pathology the whole macro arc exists to fix). Runs over the same synthetic in-memory grid as
 * {@link PathfinderBenchmark}'s TOWER scenario (no live {@code ServerLevel}); lives in this package to reach
 * {@link NavGridView}'s package-private synthetic constructor.
 */
class MacroPillarTest {

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

    @Test
    void macroPillarReachesFloatingGoal() {
        NavGridView grid = buildFlatWorld();
        BlockPos start = new BlockPos(8, 0, 8);
        BlockPos goal = new BlockPos(8, 30, 8); // 30 straight up, open air — forced pillaring
        // A WIDE corridor (~33×33 footprint) — deliberately big enough that the open-ground flood would
        // exceed the 10k expansion cap if the search weren't collapsing it. So this guards BOTH halves of the
        // fix at once: the macro-Pillar jumps + the goal-cuboid premium must crush the flood to a few dozen
        // nodes, or the search floods and returns null. (A too-narrow corridor would pass even with a broken
        // premium, as an earlier version of this test did.)
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        try {
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.BREAK_PLACE, corridor);

            // PARTIAL_PATH is off, so a non-null plan means the search genuinely REACHED the floating goal
            // (not a best-effort partial) — the open-air-pillar pathology is solved.
            assertNotNull(plan, "macro-Pillar should reach the goal floating 30 blocks up");

            // The plan must climb to the goal's height — within the goal's ±2 floor tolerance (isGoal), the
            // last stand position sits at ~goal.Y (floor goal.Y-2 → stand goal.Y-1 at the lowest)...
            BlockPos last = plan.waypoint(plan.size() - 1);
            assertTrue(last.getY() >= goal.getY() - 2,
                    "the plan should climb to the goal height; ended at y=" + last.getY());

            // ...by pillaring — which also proves reconstruct re-expanded the collapsed macro edge back into
            // per-block Pillar waypoints (so the follower sees an ordinary block-by-block climb).
            boolean anyPillar = false;
            for (int i = 0; i < plan.size(); i++) {
                if (plan.movement(i) == MovementRegistry.PILLAR) { anyPillar = true; break; }
            }
            assertTrue(anyPillar, "the plan should pillar up to the floating goal");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
        }
    }

    /**
     * Stone floor at world y=0, air above, spanning chunks (-4..4) so the corridor stays inside built terrain
     * (the synthetic view's {@code level==null} live-block fallback never fires). All 81 chunks share one
     * ground + three air {@link NavSection}s — the fixture is two classified sections, not 324.
     */
    private static NavGridView buildFlatWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> groundStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                groundStates.set(x, 0, z, stone);
            }
        }
        NavSection ground = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(groundStates, false, ground.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { ground, airSection, airSection, airSection }; // y 0..63
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), column);
            }
        }
        return new NavGridView(0, chunks); // minY=0, synthetic (no live level)
    }
}
