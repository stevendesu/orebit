package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
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
 * Pins for the vanilla mining-stance pricing (owner-ratified 2026-08-16 — the fix for the "x25
 * planner/executor mining-cost lie"): {@code Player.getDestroySpeed} divides by 5 for an eye under water
 * (no Aqua Affinity) and by 5 again for {@code !onGround()} (javap-verified on 1.20.1 and 1.21.11), and
 * {@code BotMining} pays those penalties for real. The planner's {@link MovementContext#breakCost} now
 * prices them via the lazy per-pop {@code breakStanceMult}, and a broken cell under water folds as
 * {@link com.orebit.mod.pathfinding.blockpathfinder.PathEdits#BROKEN_WATER} (the wet-above rule) so the
 * flood the dig provably causes is visible to every later expansion.
 *
 * <p>Three layers: the four stance quadrants read directly off a {@link MovementContext}; the
 * BROKEN/BROKEN_WATER diff reads; and two full searches — an all-wet shaft whose cost exceeds the dry
 * shaft by exactly the 5× deep-level surcharge (micro/macro parity included), and the flagship shape: a
 * bot standing in a 1×1 pool steps OUT of the water and digs the dry column instead of paying the 5×
 * flooded shaft. Lives in this package for {@link NavGridView}'s package-private synthetic constructor.
 */
class SubmergedMiningStanceTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        MiningModel.buildTable(true, 0); // real hardness-derived ticks — a zero table would void every assertion
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    private static long desc(Block block) {
        return NavBlock.descriptorFor(block.defaultBlockState());
    }

    /** Break-only caps for the full-search pins: no placing, so the search cannot pillar out of the dig
     *  scenarios and flood the space above them — the worlds below are single-chunk for the same reason
     *  (UNBUILT confines the frontier; a buried goal makes A* prove every cheap walk exhausted before it
     *  commits to 150-tick digs, so an open plateau floods straight past the node budget). */
    private static final BotCaps BREAK_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, BotCaps.DEFAULT_COST_PER_HITPOINT,
            true, false, BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    // ---- The four stance quadrants, read directly off the context ---------------------------------

    /**
     * One chunk, four columns at z=8, floor level y=4 (stone fill below):
     * x=2 dry grounded; x=4 grounded under 3-deep water (feet+head wet); x=6 floating in a 4-deep water
     * column (node cell itself is water); x=8 grounded in a 1-deep pool (feet wet, head dry — eye above
     * the surface).
     */
    @Test
    void stanceQuadrantsPriceAtVanillaMultipliers() {
        NavGridView grid = buildQuadrantWorld();
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        long stone = desc(Blocks.STONE);
        float base = MiningModel.bareHandTicks(stone);
        assertTrue(base > 0, "table must be built or every assertion below is vacuous");

        // Never seeded with a node → neutral stance (grid-less/diagnostic contexts must not probe cells).
        assertEquals(base, ctx.breakCost(stone), 0f, "un-seeded context stays at the historical 1x");

        ctx.setNode(2, 4, 8);
        assertEquals(base, ctx.breakCost(stone), 0f, "dry grounded: bit-identical to the historical cost");

        ctx.setNode(4, 4, 8);
        assertEquals(5f * base, ctx.breakCost(stone), 0f, "submerged grounded: eye under water = x5");

        ctx.setNode(6, 4, 8);
        assertEquals(25f * base, ctx.breakCost(stone), 0f,
                "floating in deep water: ungrounded x5 AND submerged x5 stack to x25");

        ctx.setNode(8, 4, 8);
        assertEquals(base, ctx.breakCost(stone), 0f,
                "1-deep pool: feet wet but the eye is above the surface — no penalty (and grounded)");
    }

    // ---- The BROKEN_WATER diff read ---------------------------------------------------------------

    @Test
    void wetBrokenCellReadsAsWaterAndDryAsAir() {
        NavGridView grid = buildQuadrantWorld();
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);

        long dryCell = BlockPos.asLong(2, 4, 8);
        long wetCell = BlockPos.asLong(4, 4, 8);
        ctx.pathEdits().add(EditFixtures.step(new long[] { dryCell }, new long[0]));
        ctx.pathEdits().add(EditFixtures.wetBreakStep(wetCell));

        long dry = ctx.descriptorAt(2, 4, 8);
        assertTrue(NavBlock.isPassable(dry) && NavBlock.fluid(dry) == 0, "a dry break reads as air");

        long wet = ctx.descriptorAt(4, 4, 8);
        assertTrue(ctx.water(wet), "a wet break reads as water (the flood the dig provably causes)");
        assertTrue(NavBlock.isPassable(wet), "…which is passable (swimmable)");
        assertFalse(NavBlock.isStandable(wet), "…and not standable (water is not a floor)");
    }

    // ---- Full-search pins -------------------------------------------------------------------------

    /**
     * A buried goal straight below the start at the bottom of a 1×1 bedrock chimney — the ONLY route is
     * the straight MineDown shaft (bedrock walls refuse every lateral dig), so total plan cost is exact
     * arithmetic, not tie-break-dependent. With water in the chimney mouth (the bot's feet cell), level 1
     * still digs with a dry eye but the water column follows the shaft down, so every deeper level pays
     * the submerged 5× — the wet plan must cost exactly {@code (5−1)×T} more per deep level than the dry
     * plan (T = the bare-hand stone break). Run at micro steps for unit-countable arithmetic, then again
     * with macros on: the collapsed shaft must reproduce the micro cost (the wet-column latch is the
     * macro's half of the same model).
     */
    @Test
    void wetShaftCostsTheSubmergedSurchargePerDeepLevel() {
        BlockPos start = new BlockPos(8, 8, 8);
        BlockPos goal = new BlockPos(8, 2, 8);
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);
        float stoneTicks = MiningModel.bareHandTicks(desc(Blocks.STONE));

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = false;

            BlockPathPlan dry = BlockPathfinder.findPath(
                    buildChimneyWorld(false), start, goal, BREAK_ONLY, corridor);
            BlockPathPlan wet = BlockPathfinder.findPath(
                    buildChimneyWorld(true), start, goal, BREAK_ONLY, corridor);
            assertNotNull(dry);
            assertNotNull(wet);

            int digs = countMineDowns(dry);
            assertTrue(digs >= 2, "the scenario must actually dig a multi-level shaft; got " + digs);
            assertEquals(digs, countMineDowns(wet), "same geometry, same shaft depth");

            float expectedSurcharge = (MovementContext.SUBMERGED_MINING_MULT - 1f) * stoneTicks * (digs - 1);
            assertEquals(dry.cost() + expectedSurcharge, wet.cost(), 1e-3f,
                    "each level below the first digs flooded at the vanilla 5x; level 1's eye is still dry");

            // Macro parity: the collapsed shaft (wet-column latch) must price exactly like the micro chain.
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan wetMacro = BlockPathfinder.findPath(
                    buildChimneyWorld(true), start, goal, BREAK_ONLY, corridor);
            assertNotNull(wetMacro);
            assertEquals(wet.cost(), wetMacro.cost(), 1e-3f,
                    "macro wet-column latch == micro per-node stance, level for level");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /**
     * The flagship shape: the bot stands in a 1×1 pool (feet wet) over the same buried goal. Digging in
     * place floods every deep level at 5×; one sideways step reaches a dry column and digs at 1×. The
     * planner must now walk out of the water first — before this change both options priced identically
     * and the bot dug the flooded shaft it happened to be standing in.
     */
    @Test
    void plannerStepsOutOfThePoolToDigDry() {
        BlockPos start = new BlockPos(8, 8, 8);
        BlockPos goal = new BlockPos(8, 2, 8);
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = false;
            BlockPathPlan plan = BlockPathfinder.findPath(
                    buildSlabWorld(), start, goal, BREAK_ONLY, corridor);
            assertNotNull(plan);

            boolean anyDig = false;
            for (int i = 0; i < plan.size(); i++) {
                if (plan.movement(i) != MovementRegistry.MINE_DOWN) continue;
                anyDig = true;
                BlockPos floor = plan.floor(i);
                assertFalse(floor.getX() == 8 && floor.getZ() == 8,
                        "every dig must happen OUTSIDE the flooded start column; dug at " + floor);
            }
            assertTrue(anyDig, "the plan must still reach the buried goal by digging");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    private static int countMineDowns(BlockPathPlan plan) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == MovementRegistry.MINE_DOWN) n++;
        }
        return n;
    }

    // ---- Substrates -------------------------------------------------------------------------------

    /** Four stance columns at z=8 (see the quadrant test), one chunk field at (0,0). */
    private static NavGridView buildQuadrantWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int yy = 0; yy <= 3; yy++) states.set(x, yy, z, stone); // fill below the floor level
            }
        }
        states.set(2, 4, 8, stone);                                    // dry grounded floor
        states.set(4, 4, 8, stone);                                    // grounded under deep water…
        for (int yy = 5; yy <= 7; yy++) states.set(4, yy, 8, water);   // …3-deep pool above it
        for (int yy = 4; yy <= 7; yy++) states.set(6, yy, 8, water);   // floating: node cell itself is water
        states.set(8, 4, 8, stone);                                    // grounded, 1-deep pool
        states.set(8, 5, 8, water);

        NavSection floor = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, floor.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { floor, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }

    /**
     * A 1×1 dig chimney in a single chunk: bedrock everywhere from y=0..9 except the start column (world
     * {@code (8,*,8)}), which is stone y=0..8 with the mouth at y=9 holding water ({@code wet}) or air.
     * Every lateral dig is refused (bedrock is unbreakable at these caps), so the ONLY route down is the
     * straight MineDown shaft — exact-arithmetic cost assertions with no tie-break dependence. The bot can
     * still hop onto the 16×16 bedrock roof, which is deliberately tiny (single chunk) so exhausting it
     * costs a few hundred expansions, not the budget.
     */
    private static NavGridView buildChimneyWorld(boolean wet) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int yy = 0; yy <= 9; yy++) states.set(x, yy, z, bedrock);
            }
        }
        for (int yy = 0; yy <= 8; yy++) states.set(8, yy, 8, stone);
        states.set(8, 9, 8, wet ? water : air);

        NavSection chimney = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, chimney.getTraversalGrid());
        return assembleWorld(chimney);
    }

    /**
     * The flagship shape in a single chunk: an open stone slab (y=0..8) under air, with a 1×1 pool at the
     * start column's feet cell (8,9,8) only — one sideways step reaches a dry column.
     */
    private static NavGridView buildSlabWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int yy = 0; yy <= 8; yy++) states.set(x, yy, z, stone);
            }
        }
        states.set(8, 9, 8, water); // the 1×1 pool the bot starts in

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());
        return assembleWorld(section);
    }

    /** A SINGLE-chunk world — chunk (0,0) only, {@code section} at y 0..15 under three air sections (world
     *  y 0..63). Everything outside the chunk is UNBUILT, which confines the search frontier. */
    private static NavGridView assembleWorld(NavSection section) {
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { section, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }
}
