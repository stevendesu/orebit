package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Planner-side proof of the two mining policy knobs, at both the vocabulary and the search level.
 *
 * <p><b>{@code mining.protectedBlocks}</b> rides the classification fingerprint: {@link
 * NavBlock#applyProtected} re-fingerprints matching states with the PROTECTED bit (splitting navtypes),
 * the derived BREAKABLE bit excludes them, and every planner break gate ({@link
 * MovementContext#breakable}, {@link MovementContext#breakableThrough}) refuses in one bit test — so a
 * grid classified after the list is applied routes AROUND protected blocks instead of folding breaks the
 * executor would refuse (the parity rule). Each mutating test restores the empty list in {@code finally}
 * and asserts the navtype mapping reverted, so the shared-JVM table is left untouched for other tests
 * (mirroring how these suites deliberately never bake the global {@link MiningModel} table).
 *
 * <p><b>{@code mining.allowUnbreakable}</b> rides {@link BotCaps#allowUnbreakable}: the vanilla-
 * unbreakable sentinel (hardness 255) becomes breakable at the tool-derived {@link
 * MiningModel#unbreakableTicks} stand-in — its own axis (not subject to {@code
 * maxBreakHardness}), with PROTECTED always winning.
 *
 * <p>The search course reuses {@code PassThroughHazardTest}'s sealed-stone maze shape: a straight
 * corridor {@code z=6, x=2..12} whose body cells at {@code x=7} are WALLED, and (for the protected case)
 * a clear {@code z=8} detour. The digging bot's {@code maxBreakHardness} is pinned to {@code 0} so the
 * stone shell confines (these headless searches run with no MiningModel table, where a stone break would
 * otherwise price at 0) while the hardness-0 TNT wall stays diggable. Lives in this package to reach
 * {@link NavGridView}'s package-private synthetic constructor.
 */
class ProtectedBlockPolicyTest {

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
    /** The wall column blocking the straight corridor: body cells (7,1,6) and (7,2,6). */
    private static final int WALL_X = 7, WALL_Z = 6;

    /** Weight-1.0 digger confined by the stone shell (cap 0 — only hardness-0 blocks are diggable). */
    private static final BotCaps DIGGER = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, true, false,
            0, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    /** DIGGER plus the mining.allowUnbreakable opt-in (cap still 0 — the opt-in is its own axis). */
    private static final BotCaps GRINDER = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, true, false,
            0, true, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    private static long desc(Block block) {
        return NavBlock.descriptorFor(block.defaultBlockState());
    }

    // ---- Vocabulary: the PROTECTED bit splits the navtype and every break gate refuses ---------------

    @Test
    void applyProtectedSplitsTheNavtypeAndClearsBreakable() {
        BlockState tnt = Blocks.TNT.defaultBlockState();
        BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        short tntBefore = NavBlock.navtypeFor(tnt);
        short bushBefore = NavBlock.navtypeFor(bush);
        int countBefore = NavBlock.navtypeCount();

        int remapped = NavBlock.applyProtected(s -> s.is(Blocks.TNT) || s.is(Blocks.SWEET_BERRY_BUSH));
        try {
            assertTrue(remapped > 0, "TNT + bush states must be re-fingerprinted");
            assertNotEquals(tntBefore, NavBlock.navtypeFor(tnt),
                    "protected-ness is part of the fingerprint — a protected TNT is a NEW navtype");

            long tntDesc = desc(Blocks.TNT);
            assertTrue(NavBlock.isProtected(tntDesc));
            assertFalse(NavBlock.isBreakable(tntDesc),
                    "the derived BREAKABLE bit excludes protected cells — every solid break gate sees it");
            assertTrue(NavBlock.hasCollision(tntDesc), "protection changes breakability, not geometry");

            MovementContext ctx = new MovementContext(null, BotCaps.BREAK_PLACE);
            assertFalse(ctx.breakable(tntDesc), "requireAir's gate refuses a protected solid");

            long bushDesc = desc(Blocks.SWEET_BERRY_BUSH);
            assertTrue(NavBlock.isProtected(bushDesc));
            assertFalse(ctx.breakableThrough(bushDesc),
                    "the passable punch-through fold refuses a protected hazard cell (explicit bit test —"
                            + " it doesn't ride BREAKABLE)");
            assertFalse(NavBlock.isOpenForPlace(bushDesc),
                    "a placement REPLACES its occupant — OPEN_PLACE excludes protected cells too "
                            + "(the executor's clear-then-place would otherwise refuse what the planner planned)");

            // Idempotent: re-applying the same list remaps nothing (and adds no navtypes).
            int again = NavBlock.applyProtected(s -> s.is(Blocks.TNT) || s.is(Blocks.SWEET_BERRY_BUSH));
            assertEquals(0, again, "same list twice is a no-op");
        } finally {
            NavBlock.applyProtected(s -> false);
        }

        // Fully reversible: the base navtypes come back (shared-JVM hygiene for the other suites), the
        // orphaned protected navtypes merely remain interned (bounded growth, never reused wrongly).
        assertEquals(tntBefore, NavBlock.navtypeFor(tnt));
        assertEquals(bushBefore, NavBlock.navtypeFor(bush));
        assertFalse(NavBlock.isProtected(desc(Blocks.TNT)));
        assertTrue(NavBlock.navtypeCount() >= countBefore);
    }

    // ---- Vocabulary: the unbreakable opt-in is its own axis, protected always wins --------------------

    @Test
    void allowUnbreakableIsItsOwnAxisAndProtectedOverrides() {
        long bedrock = desc(Blocks.BEDROCK);
        assertEquals(MovementContext.UNBREAKABLE_HARDNESS, NavBlock.hardness(bedrock),
                "bedrock carries the unbreakable sentinel (negative destroy time → 255)");
        assertFalse(NavBlock.isBreakable(bedrock), "today: never breakable geometry");

        MovementContext plain = new MovementContext(null, DIGGER);
        MovementContext grinder = new MovementContext(null, GRINDER);
        assertFalse(plain.breakable(bedrock), "without the opt-in the sentinel stays unmineable");
        assertTrue(grinder.breakable(bedrock),
                "mining.allowUnbreakable opts in — NOT gated by maxBreakHardness (cap is 0 here: its own axis)");
        // No inventory snapshot on this grinder → the tool-derived stand-in falls back to the bare-hand tier
        // (the same value the executor's grind spends, parity in time). A better pickaxe would price cheaper.
        assertEquals(MiningModel.unbreakableTicks(MiningModel.Tier.BARE.ordinal()),
                grinder.breakCost(bedrock), 1e-4f,
                "priced at the tool-derived stand-in the executor's grind actually spends (parity in time)");

        // Protected ALWAYS wins, even over the opt-in.
        NavBlock.applyProtected(s -> s.is(Blocks.BEDROCK));
        try {
            assertFalse(grinder.breakable(desc(Blocks.BEDROCK)),
                    "mining.protectedBlocks overrides mining.allowUnbreakable");
        } finally {
            NavBlock.applyProtected(s -> false);
        }
        assertTrue(grinder.breakable(desc(Blocks.BEDROCK)), "restored after the protected list is emptied");
    }

    // ---- Search level: the planner routes around a protected wall it used to dig through --------------

    @Test
    void plannerDigsAnUnprotectedWallButDetoursAProtectedOne() {
        // Unprotected: the straight line + two folded hardness-0 TNT breaks beats the 14-step detour.
        BlockPathPlan digPlan = BlockPathfinder.findPath(buildWalledMaze(Blocks.TNT), START, GOAL, DIGGER);
        assertNotNull(digPlan);
        assertTrue(reachedGoal(digPlan), "the digging bot must reach the goal");
        assertTrue(hasBreakAt(digPlan, WALL_X, 1, WALL_Z),
                "unprotected TNT wall: the cheap straight route folds the wall break");
        assertFalse(anyAtZ(digPlan, 8), "no reason to detour around a diggable wall");

        // Protected: SAME course, but the grid is classified AFTER the list applies (the live ordering —
        // ConfigLoader.install runs before any chunk nav build), so the wall cells carry the PROTECTED
        // fingerprint and the planner must take the detour instead of folding a break the executor
        // (Config.mayBreak) would refuse — the planner/executor parity rule, planner side.
        NavBlock.applyProtected(s -> s.is(Blocks.TNT));
        try {
            BlockPathPlan detourPlan =
                    BlockPathfinder.findPath(buildWalledMaze(Blocks.TNT), START, GOAL, DIGGER);
            assertNotNull(detourPlan, "the detour keeps the goal reachable");
            assertTrue(reachedGoal(detourPlan), "protecting the wall must not strand the bot");
            assertFalse(hasBreakAt(detourPlan, WALL_X, 1, WALL_Z),
                    "a protected block is NEVER planned as a break");
            assertFalse(hasBreakAt(detourPlan, WALL_X, 2, WALL_Z));
            assertTrue(anyAtZ(detourPlan, 8), "the planner takes the carved z=8 detour instead");
        } finally {
            NavBlock.applyProtected(s -> false);
        }
    }

    // ---- Search level: a bedrock seal opens only under the opt-in ------------------------------------

    @Test
    void bedrockSealOpensOnlyWithAllowUnbreakable() {
        // No detour this time: the corridor's x=7 column is sealed with BEDROCK and z=8 stays solid.
        NavGridView sealed = buildWalledMaze(Blocks.BEDROCK, false);

        BlockPathPlan refused = BlockPathfinder.findPath(sealed, START, GOAL, DIGGER);
        assertFalse(refused != null && reachedGoal(refused),
                "without mining.allowUnbreakable the bedrock seal is a hard wall (partial path at best)");

        BlockPathPlan ground = BlockPathfinder.findPath(sealed, START, GOAL, GRINDER);
        assertNotNull(ground, "opted in, the seal is diggable at the stand-in price");
        assertTrue(reachedGoal(ground), "the grinder reaches the goal through the seal");
        assertTrue(hasBreakAt(ground, WALL_X, 1, WALL_Z),
                "the plan folds the bedrock break the executor's grind will actually perform");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static boolean reachedGoal(BlockPathPlan plan) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        return Math.abs(last.getX() - GOAL.getX()) <= 1 && Math.abs(last.getZ() - GOAL.getZ()) <= 1;
    }

    private static boolean hasBreakAt(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            var edits = plan.edits(i);
            if (edits == null) continue;
            for (int b = 0; b < edits.breakCount(); b++) {
                BlockPos p = edits.breakPos(b);
                if (p.getX() == x && p.getY() == y && p.getZ() == z) return true;
            }
        }
        return false;
    }

    private static boolean anyAtZ(BlockPathPlan plan, int z) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.waypoint(i).getZ() == z) return true;
        }
        return false;
    }

    private static NavGridView buildWalledMaze(Block wall) {
        return buildWalledMaze(wall, true);
    }

    /**
     * One sealed stone section (chunk 0,0), routes carved 2-tall (air y=1..2) over stone floors at y=0:
     * the straight corridor {@code z=6, x=2..12} with its body cells at {@code x=7} filled with
     * {@code wall}, and — when {@code withDetour} — the {@code PassThroughHazardTest} z=8 detour (out at
     * x=5, along z=8 for x=5..9, back at x=9). Classified via {@link NavSectionBuilder#classifyInto}
     * AFTER any {@link NavBlock#applyProtected} the caller did, mirroring the live ordering.
     */
    private static NavGridView buildWalledMaze(Block wall, boolean withDetour) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }

        for (int x = 2; x <= 12; x++) carve(s, air, x, 6);        // straight corridor
        s.set(WALL_X, 1, WALL_Z, wall.defaultBlockState());       // the wall: both body cells at x=7
        s.set(WALL_X, 2, WALL_Z, wall.defaultBlockState());
        if (withDetour) {
            carve(s, air, 5, 7);                                  // detour out at x=5 ...
            for (int x = 5; x <= 9; x++) carve(s, air, x, 8);     // ... along z=8 ...
            carve(s, air, 9, 7);                                  // ... back in at x=9
        }

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

    /** Carve a 2-tall walking gap (air at y=1..2) over the stone floor at column {@code (x, z)}. */
    private static void carve(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        s.set(x, 1, z, air);
        s.set(x, 2, z, air);
    }
}
