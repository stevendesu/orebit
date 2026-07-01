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
 * Headless proof of the stateful {@code (x,y,z,mode)} search: a 1-TALL underwater hole in a wall is passable
 * ONLY by a bot that has initiated a sprint-swim (gone PRONE) in 2-deep water, and that prone state is
 * RETAINED through the 1-deep channel and the 1-tall gap — exactly what a position-only search could never
 * express. The maze is a single sealed stone section with a water channel at {@code z=8}: a 2-deep
 * initiation cell at {@code x=2}, a 1-deep run {@code x=3..5}, a 1-tall hole in the wall at {@code x=6}
 * (water at the feet, solid above), and a dry bank at {@code x=7} (the goal). The bot has NO break/place
 * ({@link BotCaps#DEFAULT}), so the hole is the only way through.
 *
 * <p>Positive: starting submerged (PRONE) at {@code x=2}, the search sprint-swims the channel, threads the
 * 1-tall hole, and {@code Surface}s onto the bank. Negative: with the initiation cell made 1-deep (so the bot
 * starts STANDING and there is nowhere to go prone), the hole — and the goal beyond it — is unreachable.
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class StatefulSwimTest {

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
    // Goal two cells onto the dry bank, so reaching it (within the search's ±1 tolerance) REQUIRES surfacing
    // out of the water and standing — not merely threading the hole (whose far lip is within ±1 of x=7).
    private static final BlockPos GOAL = new BlockPos(8, 0, 8);

    @Test
    void proneBotThreadsTheOneTallHoleAndSurfaces() {
        NavGridView grid = buildMaze(true);                 // 2-deep initiation cell present
        BlockPos start = new BlockPos(2, 0, 8);             // stands on the bottom, feet+head in water → PRONE
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a submerged (prone) bot should sprint-swim through the 1-tall hole to the bank");
        assertTrue(contains(plan, MovementRegistry.SPRINT_SWIM), "it should sprint-swim the channel");
        assertTrue(contains(plan, MovementRegistry.SURFACE), "it should Surface out onto the dry bank");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - GOAL.getX()) <= 1 && Math.abs(last.getZ() - GOAL.getZ()) <= 1,
                "the plan should end on the bank at the goal; ended at " + last);
    }

    @Test
    void standingBotCannotEnterAOneTallHoleWithNowhereToInitiate() {
        NavGridView grid = buildMaze(false);                // initiation cell is only 1-deep — no 2-deep anywhere
        BlockPos start = new BlockPos(2, 0, 8);             // feet water, head air → STANDING, can't go prone
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "with no 2-deep water to initiate a sprint-swim, the 1-tall hole is impassable");
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return true;
        }
        return false;
    }

    /**
     * One sealed stone section (chunk 0,0) with a water channel carved along z=8: x=2 initiation cell
     * (2-deep when {@code deepInit}, else 1-deep), x=3..5 a 1-deep run, x=6 the 1-tall hole (water feet,
     * stone above), x=7 the dry bank. Everything else is solid stone, so the bot is enclosed and the hole is
     * the only way to the bank.
     */
    private static NavGridView buildMaze(boolean deepInit) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone); // solid rock everywhere; the channel is carved out below
                }
            }
        }

        final int z = 8;
        s.set(2, 1, z, water);                       // initiation cell feet
        s.set(2, 2, z, deepInit ? water : air);      // head: water = 2-deep (can initiate), air = 1-deep (cannot)
        for (int x = 3; x <= 5; x++) {               // 1-deep channel run
            s.set(x, 1, z, water);
            s.set(x, 2, z, air);
        }
        s.set(6, 1, z, water);                       // the 1-TALL hole: water at the feet, stone wall above (y=2 stays stone)
        s.set(7, 1, z, air); s.set(7, 2, z, air);    // dry bank beyond the wall
        s.set(8, 1, z, air); s.set(8, 2, z, air);    // …continuing to the goal cell, so the bot must walk out

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
}
