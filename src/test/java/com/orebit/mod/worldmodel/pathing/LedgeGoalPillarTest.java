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
 * End-to-end guard for the far-face exclusion in {@code GoalForcedCost.probe}: {@link MacroPillarTest}'s
 * floating-goal scenario with ONE extra stone block — a ledge directly ABOVE the goal at {@code (8,31,8)}.
 *
 * <p>Without the exclusion, that standable ledge is a "cheap approach" on the goal's {@code (Y,−1)} face and
 * short-circuits the entire forced-build premium to 0 — re-opening the horizontal ground flood the premium
 * exists to kill: over this deliberately wide (~33×33) corridor the search floods past the 10k expansion cap
 * and (with {@code PARTIAL_PATH} off) returns {@code null}. With the exclusion, the approach from below
 * discards the far {@code (Y,−1)} face, the {@code (Y,+1)} build premium survives, and the search pillars
 * straight up exactly as in the ledge-free {@link MacroPillarTest}. Same synthetic flat world + fixture
 * style; lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class LedgeGoalPillarTest {

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
    void pillarStillReachesAFloatingGoalUnderALedge() {
        NavGridView grid = buildFlatWorldWithLedge();
        BlockPos start = new BlockPos(8, 0, 8);
        BlockPos goal = new BlockPos(8, 30, 8);  // floating, with stone at (8,31,8) directly above it
        // Wide corridor (as in MacroPillarTest): big enough that a zeroed premium floods to the node cap.
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathfinder.PARTIAL_PATH = false; // a non-null plan must mean the goal was genuinely reached
            BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.BREAK_PLACE, corridor);

            assertNotNull(plan, "the ledge above the goal must not zero the build premium "
                    + "(far-face exclusion) — a null here means the ground flood returned");

            BlockPos last = plan.waypoint(plan.size() - 1);
            assertTrue(last.getY() >= goal.getY() - 2,
                    "the plan should climb to the goal height; ended at y=" + last.getY());

            boolean anyPillar = false;
            for (int i = 0; i < plan.size(); i++) {
                if (plan.movement(i) == MovementRegistry.PILLAR) { anyPillar = true; break; }
            }
            assertTrue(anyPillar, "the plan should pillar up to the goal under the ledge");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /**
     * {@link MacroPillarTest}'s flat world (stone floor at y=0 over chunks −4..4) plus one stone ledge block
     * at world {@code (8,31,8)} — section 1 of chunk (0,0), local y=15 — directly above the goal.
     */
    private static NavGridView buildFlatWorldWithLedge() {
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

        PalettedContainer<BlockState> ledgeStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        ledgeStates.set(8, 15, 8, stone); // world y = 16 + 15 = 31 — one block above the goal
        NavSection ledge = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(ledgeStates, false, ledge.getTraversalGrid());

        NavSection[] plainColumn = { ground, airSection, airSection, airSection }; // y 0..63
        NavSection[] goalColumn = { ground, ledge, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), cx == 0 && cz == 0 ? goalColumn : plainColumn);
            }
        }
        return new NavGridView(0, chunks); // minY=0, synthetic (no live level)
    }
}
