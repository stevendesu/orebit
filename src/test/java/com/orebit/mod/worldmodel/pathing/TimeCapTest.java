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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Headless proof of the wall-clock search cap (DESIGN-background-pathfinding.md §6): {@code budgetNanos}
 * expires a search through the exact budget-exhausted path the node cap takes, and {@code 0} is
 * uncapped/byte-identical. Deterministic by construction: a 1 ns budget has ALWAYS expired by the first
 * per-256-pop check (µs have passed since {@code t0}), so no assertion depends on machine speed.
 */
class TimeCapTest {

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

    private static final BotCaps WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    @Test
    void expiredBudgetStopsTheSearchBeforeItReachesTheGoal() {
        // 1 ns: already expired at the first (expansions & 255) == 0 check — the search stops with zero
        // progress (best == start), which suppresses the partial and returns null, exactly the node cap's
        // zero-progress semantics.
        assertNull(BlockPathfinder.findPath(corridor(), START, GOAL, WALK, null, null, null,
                BlockPathfinder.MODE_AUTO, null, 1L));
    }

    @Test
    void zeroBudgetIsUncapped() {
        BlockPathPlan plan = BlockPathfinder.findPath(corridor(), START, GOAL, WALK, null, null, null,
                BlockPathfinder.MODE_AUTO, null, 0L);
        assertNotNull(plan, "budgetNanos = 0 must behave exactly like the historical node-cap-only search");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - GOAL.getX()) <= 1 && Math.abs(last.getZ() - GOAL.getZ()) <= 1,
                "uncapped search must reach the goal");
    }

    @Test
    void generousBudgetFindsTheSameGoal() {
        // A generous real budget (1 s) must not perturb a search that completes well inside it.
        BlockPathPlan capped = BlockPathfinder.findPath(corridor(), START, GOAL, WALK, null, null, null,
                BlockPathfinder.MODE_AUTO, null, 1_000_000_000L);
        BlockPathPlan uncapped = BlockPathfinder.findPath(corridor(), START, GOAL, WALK, null, null, null,
                BlockPathfinder.MODE_AUTO, null, 0L);
        assertNotNull(capped);
        assertNotNull(uncapped);
        assertTrue(capped.size() == uncapped.size() && capped.cost() == uncapped.cost(),
                "a completed-under-deadline search must be identical to the uncapped one");
    }

    /** One sealed stone section with the plain corridor {@code z=6, x=2..12} carved 2-tall at y=1..2. */
    private static NavGridView corridor() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        for (int x = 2; x <= 12; x++) {
            s.set(x, 1, 6, air);
            s.set(x, 2, 6, air);
        }

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { section, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }
}
