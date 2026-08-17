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
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
 * bot standing in a settled surface pool steps OUT of the water and digs a dry column — clear of the
 * pool AND of every column under its spread — instead of paying the 5× flooded shaft. Lives in this
 * package for {@link NavGridView}'s package-private synthetic constructor.
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

    /** {@link #BREAK_ONLY} with {@code takesDamage=false} — identical on every other axis, so any cost
     *  difference between the two on the SAME world is the damage pricing alone (the lava-exposure gate). */
    private static final BotCaps IMMUNE_BREAK_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false, BotCaps.DEFAULT_COST_PER_HITPOINT,
            true, false, BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    // ---- The four stance quadrants, read directly off the context ---------------------------------

    /**
     * One chunk, five columns at z=8, floor level y=4 (stone fill below):
     * x=2 dry grounded; x=4 grounded under 3-deep water (head wet); x=6 floating in a 4-deep water
     * column (node cell itself is water); x=8 grounded in a 1-deep pool (feet wet, head dry — the eye is
     * above the surface, so NO submerged penalty); x=10 grounded with water at the HEAD cell only (feet
     * dry) — owner-verified in-game 2026-08-16: still 5×, because vanilla tests the EYE, which sits ~1.62
     * above the feet inside the head cell.
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

        ctx.setNode(10, 4, 8);
        assertEquals(5f * base, ctx.breakCost(stone), 0f,
                "head-only water: the eye is IN the head cell, so feet-dry still pays the 5x");
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
                    buildChimneyWorld(Blocks.AIR.defaultBlockState()), start, goal, BREAK_ONLY, corridor);
            BlockPathPlan wet = BlockPathfinder.findPath(
                    buildChimneyWorld(Blocks.WATER.defaultBlockState()), start, goal, BREAK_ONLY, corridor);
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
                    buildChimneyWorld(Blocks.WATER.defaultBlockState()), start, goal, BREAK_ONLY, corridor);
            assertNotNull(wetMacro);
            assertEquals(wet.cost(), wetMacro.cost(), 1e-3f,
                    "macro wet-column latch == micro per-node stance, level for level");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /**
     * The LAVA twin of the water chimney — the pin for the per-level <b>lava-exposure term</b>
     * (DESIGN-fluid-flow-prediction.md §6, the 2026-08-17 adversarial-review correction: without it a
     * {@code BROKEN_LAVA} verdict chooses the {@code PathEdits} kind while adding ZERO cost, and a mortal
     * bot prices a lava-flooding shaft identically to a dry one — a free lethal offer). Same 1×1 bedrock
     * chimney, LAVA at the mouth: the tier-0a vertical rule chains {@code BROKEN_LAVA} down the whole
     * shaft, and EVERY level digs with its feet in lava — level 1's feet stand in the mouth pool itself
     * (unlike water's dry-eye level 1), each deeper level's feet cell is the previous level's lava-flooded
     * break. Lava never submerges the eye (vanilla tests {@code FluidTags.WATER}), so mining time stays
     * the dry 1× {@code T} per level and the exposure is a pure damage term:
     *
     * <pre>  mortal cost == dry cost + digs × T × LAVA_HP_PER_TICK × costPerHitpoint</pre>
     *
     * exact arithmetic ({@code T} = the bare-hand stone break, rate = vanilla's 4 HP per 10-tick
     * immersion window). Three companion pins: an immune bot's cost equals the dry cost EXACTLY (the
     * §4.2 priced-not-forbidden split — bit-identical, not merely close); the macro collapse reproduces
     * the micro arithmetic (macro == micro level for level); and the plan's diff records the flood
     * honestly ({@code BROKEN_LAVA} at the mouth-column break).
     */
    @Test
    void lavaChimneyChargesTheImmersionExposurePerLevelForAMortalBotOnly() {
        BlockPos start = new BlockPos(8, 8, 8);
        BlockPos goal = new BlockPos(8, 2, 8);
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);
        float stoneTicks = MiningModel.bareHandTicks(desc(Blocks.STONE));
        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = false;

            BlockPathPlan dry = BlockPathfinder.findPath(
                    buildChimneyWorld(air), start, goal, BREAK_ONLY, corridor);
            BlockPathPlan mortal = BlockPathfinder.findPath(
                    buildChimneyWorld(lava), start, goal, BREAK_ONLY, corridor);
            BlockPathPlan immune = BlockPathfinder.findPath(
                    buildChimneyWorld(lava), start, goal, IMMUNE_BREAK_ONLY, corridor);
            assertNotNull(dry);
            assertNotNull(mortal);
            assertNotNull(immune);

            int digs = countMineDowns(dry);
            assertTrue(digs >= 2, "the scenario must actually dig a multi-level shaft; got " + digs);
            assertEquals(digs, countMineDowns(mortal), "same geometry, same shaft depth");
            assertEquals(digs, countMineDowns(immune), "same geometry, same shaft depth");

            // Every level digs feet-in-lava at the dry 1x mining time — the exposure is per-level mining
            // ticks x the vanilla immersion rate x the one damage currency, digs levels of it.
            float exposure = digs * stoneTicks
                    * MovementContext.LAVA_HP_PER_TICK * BotCaps.DEFAULT_COST_PER_HITPOINT;
            assertEquals(dry.cost() + exposure, mortal.cost(), 0.1f,
                    "a mortal bot pays the lava-exposure term on every level of the flooding shaft"
                            + " (tolerance is float32 rounding at the ~10^4-tick scale, ~3 ppm)");

            // The §4.2 split: an immune bot charges NOTHING for the lava — bit-identical to the dry dig.
            assertEquals(dry.cost(), immune.cost(), 0f,
                    "an immune bot's lava shaft is bit-identical to the dry shaft (exposure gated on"
                            + " takesDamage, multiply-by-nothing)");

            // The diff is honest: the mouth-column level-1 break records the lava flood (tier 0a).
            PathEdits folded = new PathEdits();
            folded.addSnapshot(EditSnapshot.fromRemainingSteps(mortal, 0));
            assertEquals(PathEdits.BROKEN_LAVA, folded.kindAt(8, 8, 8),
                    "level 1's break sits under the mouth pool — BROKEN_LAVA via the vertical rule");

            // Macro parity: the collapsed lava shaft must reproduce the micro chain's exposure exactly.
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan mortalMacro = BlockPathfinder.findPath(
                    buildChimneyWorld(lava), start, goal, BREAK_ONLY, corridor);
            assertNotNull(mortalMacro);
            assertEquals(mortal.cost(), mortalMacro.cost(), 0.1f,
                    "macro per-level lava exposure == micro per-node exposure, level for level");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /**
     * The flagship shape, strengthened for the fluid-flow arc (DESIGN-fluid-flow-prediction.md §5/§9):
     * the bot stands in the SOURCE cell of a settled surface pool — centre source, min-level arms on the
     * four laterals — over the same buried goal. Digging in place floods every deep level at 5×; so does
     * digging any LATERALLY-ADJACENT column, because the arm water sits directly above its first break
     * (the tier-0a vertical rule) and the flood then chains down that shaft too. The cheapest dry dig is
     * therefore DIAGONAL (or further) from the pool — the diagonal-dig behaviour the whole arc exists to
     * produce — followed by a short dry tunnel back to the goal at depth.
     *
     * <p>Two assertions, both tie-robust:
     * <ul>
     *   <li>every surface-level dig (floor y=8 — the level at which the column CHOICE is made) is at
     *       Manhattan distance ≥ 2 from the pool centre: neither the pool column nor any arm column.
     *       Deeper digs are deliberately unconstrained — once below ground, cut-over variants through
     *       already-broken dry cells tie on cost and the tie-break may pick any of them;</li>
     *   <li>no edit anywhere in the plan folds {@code BROKEN_WATER}: the chosen plan is entirely dry,
     *       which is the actual claim (any plan touching a wet column at the surface pays the 5× chain
     *       and is strictly costlier, so no optimal variant folds wet).</li>
     * </ul>
     *
     * <p>The substrate is built through the FULL column pipeline (see {@link #buildSlabWorld}) so the
     * scatter-owned {@code HAS_FLUID_NEIGHBOR} flag exists and the funnel's lateral tiers run for real —
     * on a {@code classifyInto} grid the flag is permanently clear and tier 0b would short-circuit every
     * lateral verdict to dry (the synthetic-grid trap).
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
                if (floor.getY() == 8) { // the surface plane — where the dig column is chosen
                    int manhattan = Math.abs(floor.getX() - 8) + Math.abs(floor.getZ() - 8);
                    assertTrue(manhattan >= 2,
                            "a surface dig must be neither the pool column nor laterally adjacent to it"
                                    + " (the arm water floods those shafts via the vertical rule); dug at "
                                    + floor);
                }
            }
            assertTrue(anyDig, "the plan must still reach the buried goal by digging");

            // The dry-plan pin: no break anywhere in the chosen plan folds BROKEN_WATER — the whole
            // route, tunnel-back included, digs dry (kinds ride EditSnapshot verbatim).
            PathEdits folded = new PathEdits();
            folded.addSnapshot(EditSnapshot.fromRemainingSteps(plan, 0));
            for (int i = 0; i < folded.editCount(); i++) {
                assertFalse(folded.kindAt(folded.editAt(i)) == PathEdits.BROKEN_WATER,
                        "the chosen plan must be entirely dry; wet fold at packed cell " + folded.editAt(i));
            }
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

    /** Five stance columns at z=8 (see the quadrant test), one chunk field at (0,0). */
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
        states.set(10, 4, 8, stone);                                   // grounded, head-cell water only…
        states.set(10, 6, 8, water);                                   // …feet (y=5) dry, eye submerged

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
     * {@code (8,*,8)}), which is stone y=0..8 with the mouth at y=9 holding the given state — water/lava
     * (the flooding fixtures) or air (the dry baseline). Every lateral dig is refused (bedrock is
     * unbreakable at these caps), so the ONLY route down is the straight MineDown shaft —
     * exact-arithmetic cost assertions with no tie-break dependence. The bot can still hop onto the 16×16
     * bedrock roof, which is deliberately tiny (single chunk) so exhausting it costs a few hundred
     * expansions, not the budget.
     */
    private static NavGridView buildChimneyWorld(BlockState mouth) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int yy = 0; yy <= 9; yy++) states.set(x, yy, z, bedrock);
            }
        }
        for (int yy = 0; yy <= 8; yy++) states.set(8, yy, 8, stone);
        states.set(8, 9, 8, mouth);

        NavSection chimney = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, chimney.getTraversalGrid());
        return assembleWorld(chimney);
    }

    /**
     * The flagship shape in a single chunk: an open stone slab (y=0..8) under air, with a settled surface
     * pool at the start column's feet level — a SOURCE at (8,9,8) with MIN-LEVEL flowing arms
     * ({@code amount == 1}, the spent edge of a spread that can flow no further) on its four laterals.
     * The arms are what make the "laterally adjacent" half of the flagship assertion bite: their water
     * sits directly above the adjacent columns' first break, so those shafts flood by the tier-0a
     * vertical rule exactly like the pool column itself.
     *
     * <p>Built through the FULL column pipeline ({@link #assembleFlaggedWorld}) — not {@code classifyInto}
     * — so the scatter-owned {@code HAS_FLUID_NEIGHBOR} flag is populated and the fold funnel's lateral
     * tiers (DESIGN-fluid-flow-prediction.md §5, tier 0b) run against real flags in this fixture.
     */
    private static NavGridView buildSlabWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState source = Blocks.WATER.defaultBlockState();
        BlockState minLevel = Blocks.WATER.defaultBlockState()
                .setValue(BlockStateProperties.LEVEL, 7); // amount 1 — cannot spread further

        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int yy = 0; yy <= 8; yy++) states.set(x, yy, z, stone);
            }
        }
        states.set(8, 9, 8, source);   // the pool the bot starts in…
        states.set(7, 9, 8, minLevel); // …and its settled spread arms
        states.set(9, 9, 8, minLevel);
        states.set(8, 9, 7, minLevel);
        states.set(8, 9, 9, minLevel);

        return assembleFlaggedWorld(states);
    }

    /**
     * A SINGLE-chunk world — chunk (0,0) only, {@code section} at y 0..15 under three air sections (world
     * y 0..63). Everything outside the chunk is UNBUILT, which confines the search frontier.
     *
     * <p>{@code classifyInto}-built (flags gathered, depth UNKNOWN, NO scatter): the chimney fixtures stay
     * on this path deliberately, and it is verified safe for their exact-arithmetic pins — their water is
     * only ever DIRECTLY ABOVE the dug column inside bedrock walls, so every wet fold fires through the
     * funnel's tier-0a vertical rule, which reads descriptors and never consults the scatter-owned
     * {@code HAS_FLUID_NEIGHBOR} flag. The (5−1)×T×(digs−1) surcharge and the macro==micro parity are
     * therefore identical with or without the column pipeline; lateral digs (the only reads tier 0b
     * gates) are refused by the bedrock regardless.
     */
    private static NavGridView assembleWorld(NavSection section) {
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { section, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }

    /**
     * The FULL-column-pipeline counterpart of {@link #assembleWorld}: {@code classifyNavtypes} per
     * section, {@code computeFlags} with the above-grid overscan, then {@code computeDepth} over the
     * whole column — exactly as {@code ChunkNavBuilder} drives it live. This is the only build path that
     * populates the scatter-owned {@code HAS_FLUID_NEIGHBOR} flag the fold funnel's tier 0b reads
     * (DESIGN-fluid-flow-prediction.md §4); the {@code classifyInto} one-liner never runs
     * {@code computeDepth}, leaving such a grid's flag permanently clear (the synthetic-grid trap).
     */
    private static NavGridView assembleFlaggedWorld(PalettedContainer<BlockState> states) {
        NavSection s0 = NavSection.create(BlockPos.ZERO);
        boolean air0 = NavSectionBuilder.classifyNavtypes(states, false, s0.getTraversalGrid(), null);
        NavSection[] col = new NavSection[4];
        boolean[] allAir = { air0, true, true, true };
        col[0] = s0;
        for (int i = 1; i < col.length; i++) {
            col[i] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyNavtypes((PalettedContainer<BlockState>) null, true,
                    col[i].getTraversalGrid(), null);
        }
        for (int i = 0; i < col.length; i++) {
            TraversalGrid above = (i + 1 < col.length && !allAir[i + 1])
                    ? col[i + 1].getTraversalGrid() : null;
            NavSectionBuilder.computeFlags(col[i].getTraversalGrid(), allAir[i], above);
        }
        NavSectionBuilder.computeDepth(col);

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), col);
        return new NavGridView(0, chunks);
    }
}
