package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Integrity of {@link BlockPathPlan#stepCost} — the per-step search costs reconstruct fills from
 * per-edge g-deltas (DESIGN-replan-handoff.md §3), the input the horizon-seam walk prefix-sums.
 * Over REAL searches (the synthetic in-memory grid, {@link MacroPillarTest}'s fixture): the
 * telescoping invariant {@code sum(stepCosts) == plan.cost()} within float-summation tolerance,
 * every entry strictly positive (every A* edge costs real ticks), and — on a macro-bearing plan —
 * the uniform division of a collapsed edge across its re-expanded waypoints (macro runs are
 * uniform, so equal per-step shares that sum back to the edge cost). Lives in this package to
 * reach {@link NavGridView}'s package-private synthetic constructor.
 */
class StepCostsIntegrityTest {

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

    /** Sum of the plan's per-step costs, asserting each entry positive on the way. */
    private static float sumAssertingPositive(BlockPathPlan plan) {
        float sum = 0f;
        for (int i = 0; i < plan.size(); i++) {
            float c = plan.stepCost(i);
            assertTrue(c > 0f, "stepCost[" + i + "] must be a positive tick count, was " + c
                    + " (" + plan.movement(i).getClass().getSimpleName() + ")");
            sum += c;
        }
        return sum;
    }

    @Test
    void walkPlanStepCostsSumToTheSearchCost() {
        // A plain ground walk: every edge is a single waypoint, so each stepCost IS one g-delta and the
        // sum telescopes to the goal's g — plan.cost() — up to float re-summation.
        NavGridView grid = buildFlatWorld();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(8, 0, 8),
                new BlockPos(14, 0, 12), BotCaps.BREAK_PLACE, null);

        assertNotNull(plan, "a flat-ground beeline must be found");
        assertTrue(plan.size() > 0, "a non-trivial walk has steps");
        float sum = sumAssertingPositive(plan);
        assertEquals(plan.cost(), sum, 0.01f,
                "sum(stepCosts) must telescope back to the plan's total search cost");
    }

    @Test
    void macroPillarStepCostsDivideTheEdgeUniformlyAndSumBack() {
        // The TOWER shape (MacroPillarTest): a goal 30 straight up forces macro-Pillar collapse, so
        // reconstruct re-expands collapsed edges into runs of per-block waypoints, each carrying
        // edgeCost / j. The division must (a) still sum back to the total and (b) be visibly UNIFORM —
        // a j>=2 macro edge yields consecutive Pillar steps with bit-identical per-step shares.
        NavGridView grid = buildFlatWorld();
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        try {
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(8, 0, 8),
                    new BlockPos(8, 30, 8), BotCaps.BREAK_PLACE, corridor);

            assertNotNull(plan, "macro-Pillar should reach the goal floating 30 blocks up");
            float sum = sumAssertingPositive(plan);
            assertEquals(plan.cost(), sum, 0.05f,
                    "the uniform per-step division must sum back to the collapsed edges' costs");

            // Uniformity evidence: at least one adjacent Pillar pair sharing one macro edge — and a
            // shared edge means bit-identical shares (edgeCost / j computed once). Edge BOUNDARIES are
            // invisible in the reconstructed plan, so this is the strongest headless form of the claim.
            boolean anyUniformPillarPair = false;
            for (int i = 0; i + 1 < plan.size(); i++) {
                if (plan.movement(i) == MovementRegistry.PILLAR
                        && plan.movement(i + 1) == MovementRegistry.PILLAR
                        && Float.floatToIntBits(plan.stepCost(i))
                                == Float.floatToIntBits(plan.stepCost(i + 1))) {
                    anyUniformPillarPair = true;
                    break;
                }
            }
            assertTrue(anyUniformPillarPair,
                    "a 30-block macro climb must contain a re-expanded run of >= 2 Pillar steps with "
                            + "bit-identical uniform per-step shares");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
        }
    }

    /**
     * Stone floor at world y=0, air above, spanning chunks (-4..4) — {@link MacroPillarTest}'s fixture:
     * all 81 chunks share one ground + three air {@link NavSection}s, no live level.
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
