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
import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
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
 * Pins for the {@link EditScratch} <b>fluid-flow evaluation funnel</b> — the fold-time answer to "will
 * breaking this cell admit fluid, and which?" (DESIGN-fluid-flow-prediction.md §5–§6). Every fixture is
 * built through the <b>full column pipeline</b> ({@code classifyNavtypes} → {@code computeFlags} with
 * overscan → {@code computeDepth}), NOT the {@code classifyInto} one-liner: the funnel's tier-0b early-out
 * reads {@code HAS_FLUID_NEIGHBOR}, which is <i>scatter-owned</i> and written only by {@code computeDepth}
 * — a {@code classifyInto} grid leaves the bit permanently clear and every lateral verdict would collapse
 * to dry, passing these tests vacuously (the {@code FluidScatterIdentityTest} pipeline is the template).
 *
 * <p>Coverage, in the design doc's §9 order:
 * <ul>
 *   <li><b>tier 1, the §1.1 steady-state table</b> — source spreads; mid-fall flowing does not;
 *       flowing-on-solid does; min-level never does;</li>
 *   <li><b>tier 2, the beeline + blind band</b> — a drain the slope search can see (detect-5 at
 *       {@code slopeFind=4}) makes the break lose outright ⇒ dry; one cell past the horizon every
 *       direction ties at 1000 and ties ALL win ⇒ flood;</li>
 *   <li><b>receiving side (§1.2/§8.2)</b> — dry waterloggable partials are walls, waterlogged partials
 *       are not spread sources;</li>
 *   <li><b>lava (§4.2)</b> — same funnel, {@code BROKEN_LAVA} verdict, the unified nether-pinned
 *       {@code slopeFind=4} (§5), and the priced-not-forbidden search behaviour a categorical refusal
 *       could not express;</li>
 *   <li><b>vertical rule, per-fluid (tier 0a)</b> — lava above folds {@code BROKEN_LAVA} (the water-only
 *       {@code wetFromAbove} hole this closed), and an own-fold seal falls THROUGH to the lateral tiers
 *       instead of returning dry outright;</li>
 *   <li><b>read discipline (§5)</b> — an own-scratch fold and a path-diff fold each flip a later verdict,
 *       pinning scratch-first reads and the no-memo ruling;</li>
 *   <li><b>the {@code BROKEN_LAVA} descriptor read (§6)</b> — a lava-folded cell is honest lava (and a
 *       SOURCE, deliberately) to every downstream read.</li>
 * </ul>
 *
 * <p>Lives in this package for {@link NavGridView}'s package-private synthetic constructor. Verdicts are
 * read through {@link EditScratch#peekBreakKind} (the funnel verbatim, minus the fold) and through real
 * folds where the storage path itself is under test.
 */
class FluidFlowVerdictTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        MiningModel.buildTable(true, 0); // the search test prices real breaks; a zero table would tie routes
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    /** Break-only, mortal, default damage pricing — the search-level lava caps ({@code takesDamage} on). */
    private static final BotCaps MORTAL_BREAK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, BotCaps.DEFAULT_COST_PER_HITPOINT,
            true, false, BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    /** {@link #MORTAL_BREAK} with {@code takesDamage=false} — identical on every other axis, so a route
     *  difference between the two is the damage pricing alone (§4.2's "on price alone"). */
    private static final BotCaps IMMUNE_BREAK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false, BotCaps.DEFAULT_COST_PER_HITPOINT,
            true, false, BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    // ---- Tier 1: the §1.1 steady-state table ------------------------------------------------------

    /**
     * The spread-eligibility table a settled NavGrid presents (§1.1), one row per fluid shape, each read
     * as the verdict for breaking the stone cell immediately west of the fluid: a <b>source</b> is always
     * eligible and (with no drain anywhere) every direction ties at 1000 ⇒ flood; a <b>mid-fall flowing
     * column</b> (same fluid directly below) is inert; <b>flowing resting on solid</b> is eligible ⇒
     * flood; <b>min-level</b> ({@code amount == 1}) cannot spread at all. Every row's break cell carries
     * {@code HAS_FLUID_NEIGHBOR} (the fluid is orthogonal), so each verdict is the funnel's tiers deciding
     * — not the tier-0b early-out passing the test vacuously.
     */
    @Test
    void steadyStateSpreadEligibilityTable() {
        PalettedContainer<BlockState> states = slab();
        put(states, 8, 8, 2, Blocks.WATER.defaultBlockState());                    // source
        put(states, 8, 8, 5, flowingWater(8));                                     // mid-fall (falling)…
        put(states, 8, 7, 5, flowingWater(8));                                     // …same fluid below
        put(states, 8, 8, 8, flowingWater(1));                                     // flowing on solid
        put(states, 8, 8, 11, flowingWater(7));                                    // min level (amount 1)
        MovementContext ctx = ctx(states, BotCaps.BREAK_PLACE);
        EditScratch e = ctx.edits().reset();

        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(7, 8, 2),
                "a SOURCE beside the break is spread-eligible; no drain exists, so all directions tie and ties win");
        assertEquals(PathEdits.BROKEN, e.peekBreakKind(7, 8, 5),
                "a mid-fall flowing column (same fluid directly below) never spreads laterally");
        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(7, 8, 8),
                "flowing fluid resting on solid is spread-eligible (!isHole) and ties into the break");
        assertEquals(PathEdits.BROKEN, e.peekBreakKind(7, 8, 11),
                "min-level fluid (amount 1) cannot spread at all — one more step yields amount 0");

        // The real fold stores the same verdict (peek is the funnel verbatim): fold the source-row break
        // and read it back scratch-first — the flooded cell must read as water to this candidate's own
        // later requirements (the storage half of §5's read discipline).
        e.reset().requireAir(7, 8, 2);
        assertTrue(e.valid(), "stone at these caps is breakable; the flood verdict must never refuse (§6)");
        assertTrue(e.readsWater(7, 8, 2), "the folded break reads scratch-first as the water it floods with");
    }

    // ---- Tier 2: the beeline and the blind band ---------------------------------------------------

    /**
     * The owner's two fixtures as unit scenes (§9): a pool with an open drain corridor east and the break
     * candidate west. The slope competition (§1.1's mechanism 2, ties-all-win {@code <=}) decides:
     * a drain the recursion can see makes the break's direction LOSE outright ⇒ dry; the first hole past
     * the detection horizon leaves every direction tied at 1000 ⇒ flood. These pin the
     * {@code slopeFind=4 ⇒ detect-5} arithmetic — holes at corridor distance 4 and 5 are seen (slope
     * distance 3 and 4), distance 6 is the §1.1 blind band.
     */
    @Test
    void beelineDrainSavesTheWaterDigAndTheBlindBandFloods() {
        assertEquals(PathEdits.BROKEN, verdictBesidePoolWithDrain(Blocks.WATER.defaultBlockState(), 4),
                "a drain at corridor distance 4 (slope distance 3) beats the break's 1000 — the dig stays dry");
        assertEquals(PathEdits.BROKEN, verdictBesidePoolWithDrain(Blocks.WATER.defaultBlockState(), 5),
                "corridor distance 5 (slope distance 4) is the LAST detected hole — the detect-5 pin");
        assertEquals(PathEdits.BROKEN_WATER, verdictBesidePoolWithDrain(Blocks.WATER.defaultBlockState(), 6),
                "corridor distance 6 is past the horizon: every direction ties at 1000 and ties ALL win — flood");
    }

    // ---- Receiving side (§1.2 / §8.2) -------------------------------------------------------------

    /**
     * What can RECEIVE flow (§1.2): flowing fluid can never enter a dry waterloggable block, so a dry
     * bottom slab or stair between a pool and the break column is a WALL — the break two cells from the
     * fluid reads dry through the whole pipeline (the partial is not fluid, so it neither scatters
     * {@code HAS_FLUID_NEIGHBOR} onto the break nor competes as a spread direction). And the §8.2 half:
     * a WATERLOGGED partial directly beside the break DOES set the flag (its fluid state is real water —
     * see {@code NavBlock.isFluidSource}'s waterlogged note) but is excluded as a spread source by the
     * genuine-open-cell test — flag set, verdict still dry, the owner-ratified errs-dry inexactness.
     */
    @Test
    void dryPartialIsAWallAndWaterloggedPartialIsNotASource() {
        PalettedContainer<BlockState> states = slab();
        // Row z=4: pool | dry bottom slab | break column.
        put(states, 8, 8, 4, Blocks.WATER.defaultBlockState());
        put(states, 7, 8, 4, Blocks.OAK_SLAB.defaultBlockState());                  // default half = BOTTOM
        // Row z=7: waterlogged bottom slab directly beside the break (no open fluid anywhere).
        put(states, 7, 8, 7, Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        // Row z=10: pool | dry stair | break column.
        put(states, 8, 8, 10, Blocks.WATER.defaultBlockState());
        put(states, 7, 8, 10, Blocks.OAK_STAIRS.defaultBlockState());
        NavGridView grid = world(states);
        MovementContext ctx = new MovementContext(grid, BotCaps.BREAK_PLACE);
        EditScratch e = ctx.edits().reset();

        assertEquals(PathEdits.BROKEN, e.peekBreakKind(6, 8, 4),
                "a dry bottom slab between pool and break is a wall (§1.2) — the dig stays dry");
        assertEquals(PathEdits.BROKEN, e.peekBreakKind(6, 8, 10),
                "a dry stair between pool and break is a wall (§1.2) — the dig stays dry");

        // The §8.2 pin must NOT be vacuous: the waterlogged slab is orthogonal to the break, so the flag
        // fires and the verdict is the funnel's genuine-open exclusion, not the tier-0b early-out.
        assertTrue(NavFlags.hasFluidNeighbor(grid.flagsAt(6, 8, 7)),
                "a waterlogged partial IS fluid to the scatter — the break cell must carry the flag");
        assertEquals(PathEdits.BROKEN, e.peekBreakKind(6, 8, 7),
                "…but it is excluded as a spread SOURCE (§8.2, waterlogged partials errs-dry)");
    }

    // ---- Lava: same funnel, the unified slope constant (§4.2, §5) ---------------------------------

    /**
     * Lava runs the SAME funnel as water at the SAME unified {@code SLOPE_FIND=4} — the NETHER pin
     * (owner-ratified 2026-08-17, §5): ties win, so a smaller slope-find could only remove drain
     * detections and thereby err WET (phantom lava in the nether, the §8-unrecoverable class); the
     * nether pin instead errs DRY in the overworld (a drain at 4–5 real overworld lava cannot see),
     * which self-corrects — a documented §8-table inexactness this test pins as model behaviour. So a
     * drain saves a lava dig exactly where it saves the identical water dig, out to the detect-5
     * horizon; the blind band beyond it floods. The tie case yields {@code BROKEN_LAVA}, the third
     * verdict.
     */
    @Test
    void lavaRunsTheSameFunnelAtTheUnifiedSlopeFind() {
        assertEquals(PathEdits.BROKEN, verdictBesidePoolWithDrain(Blocks.LAVA.defaultBlockState(), 2),
                "a nearby drain (slope distance 1) saves the lava dig");
        assertEquals(PathEdits.BROKEN, verdictBesidePoolWithDrain(Blocks.LAVA.defaultBlockState(), 4),
                "the drain at 4 saves the lava dig exactly as it saves the water dig (unified slope-find)");
        assertEquals(PathEdits.BROKEN, verdictBesidePoolWithDrain(Blocks.LAVA.defaultBlockState(), 5),
                "corridor distance 5 (slope distance 4) is the LAST detected hole — the detect-5 pin");
        assertEquals(PathEdits.BROKEN_LAVA, verdictBesidePoolWithDrain(Blocks.LAVA.defaultBlockState(), 6),
                "the blind band: a drain at 6 is invisible, every direction ties at 1000 — the lava dig floods");

        // No-drain tie: the lava analog of the steady-state source row, yielding the third verdict.
        PalettedContainer<BlockState> states = slab();
        put(states, 8, 8, 8, Blocks.LAVA.defaultBlockState());
        assertEquals(PathEdits.BROKEN_LAVA,
                ctx(states, BotCaps.BREAK_PLACE).edits().reset().peekBreakKind(7, 8, 8),
                "an undrained lava source beside the break ties — BROKEN_LAVA, priced, never refused");
    }

    /**
     * §4.2's behavioural claim, at the search level: lava digs are PRICED, not forbidden — the behaviour
     * the deleted {@code RISKY_EDIT} lava keep-away could not express. One slab world, a 1×1 lava pool at
     * {@code (8,9,8)} over a buried goal at {@code (8,2,8)}, the bot starting dry two cells away: the only
     * way to dig the goal column is to stand IN the pool (the entry is a {@code Swim} rung, priced by the
     * shipped {@code lavaSwimCellCost} — ~1023 ticks of immersion damage for a mortal bot, the bare 2.5×
     * slow ≈ 23 ticks for an immune one), and every dig there folds {@code BROKEN_LAVA} down the shaft
     * (tier 0a lava above, chained through the diff) — which, since the §6 adversarial-review correction,
     * ALSO charges the mortal bot {@code MineDown}'s per-level lava-exposure term (mining ticks ×
     * {@code LAVA_HP_PER_TICK} × {@code costPerHitpoint}, ~6,000 ticks per stone level at defaults) for
     * every level dug with its feet in the pool's chained flood — the mortal gap is now far wider than
     * the swim entry alone, while the immune totals below are untouched ({@code takesDamage}-gated).
     * The alternative is a dry shaft one column over plus
     * a 3-break descend-tunnel into the goal cell at the bottom (~293 ticks of extra mining: measured
     * totals 1307.8 dry vs 1015.2 through the pool). Both searches run at EXACT goal tolerance
     * ({@code 0/0}, the drop-pickup arrival) — this is load-bearing, not cosmetic: at the default
     * {@code ±1/±2} tolerance a dry shaft one column over already ENDS within tolerance of the buried
     * goal, no tunnel is ever dug, both routes fold the same four breaks, and the pool route's 27-tick
     * entry (Traverse + lava Swim gains no depth) loses honestly for BOTH bots. So the SAME search,
     * differing only in {@code takesDamage}, must split: the mortal bot avoids the lava dig on price
     * alone (its plan folds no lava and never stands in the pool), the immune bot accepts it (digs the
     * pool column, and its level-1 break honestly records the lava flood).
     */
    @Test
    void mortalAvoidsTheLavaDigOnPriceAndAnImmuneBotAcceptsIt() {
        BlockPos start = new BlockPos(10, 8, 8);
        BlockPos goal = new BlockPos(8, 2, 8);
        RegionBound corridor = new RegionBound(-16, 16, 0, 33, -16, 16);
        BlockPos poolFloor = new BlockPos(8, 8, 8); // standing here = feet in the lava cell above it

        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = false;

            // Exact goal tolerance (0/0) — see the Javadoc's cost story: the default ±1/±2 arrival
            // box would let the dry one-column-over shaft finish without ever digging the tunnel.
            BlockPathPlan mortal = BlockPathfinder.findPath(
                    buildLavaPoolWorld(), start, goal, MORTAL_BREAK, corridor, corridor, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            BlockPathPlan immune = BlockPathfinder.findPath(
                    buildLavaPoolWorld(), start, goal, IMMUNE_BREAK, corridor, corridor, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            assertNotNull(mortal, "a dry dig-and-tunnel route exists — the mortal search must find it");
            assertNotNull(immune);

            // Mortal: never stands in the pool, folds no lava anywhere.
            for (int i = 0; i < mortal.size(); i++) {
                assertNotEquals(poolFloor, mortal.floor(i),
                        "the mortal bot must never stand in the lava pool (entry costs ~10 HP of immersion)");
            }
            PathEdits mortalEdits = foldedEdits(mortal);
            for (int i = 0; i < mortalEdits.editCount(); i++) {
                assertNotEquals(PathEdits.BROKEN_LAVA, mortalEdits.kindAt(mortalEdits.editAt(i)),
                        "the mortal plan must be lava-free — it routes around the flood on price");
            }
            assertTrue(countMineDowns(mortal) > 0, "…while still reaching the buried goal by digging");

            // Immune: digs the pool column, and the plan's diff says honestly what that dig floods with.
            boolean dugPoolColumn = false;
            for (int i = 0; i < immune.size(); i++) {
                if (immune.movement(i) == MovementRegistry.MINE_DOWN
                        && immune.floor(i).getX() == 8 && immune.floor(i).getZ() == 8) {
                    dugPoolColumn = true;
                }
            }
            assertTrue(dugPoolColumn, "the immune bot takes the shorter lava dig — priced, not forbidden");
            assertEquals(PathEdits.BROKEN_LAVA, foldedEdits(immune).kindAt(8, 8, 8),
                    "the pool-column level-1 break records the lava flood (tier 0a, lava above)");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    // ---- The vertical rule, per-fluid (tier 0a) ---------------------------------------------------

    /**
     * Tier 0a is per-fluid (§5, owner-ratified): swimmable water above ⇒ {@code BROKEN_WATER}, swimmable
     * LAVA above ⇒ {@code BROKEN_LAVA} — the shipped water-only {@code wetFromAbove} folded plain
     * {@code BROKEN} under lava, the exact hole this closes. And an own-fold PLACE above no longer returns
     * dry outright: it seals the vertical certainty and falls THROUGH to the lateral tiers, where an
     * eligible source beside the break still floods it — a sealed column can flood sideways.
     */
    @Test
    void verticalRuleIsPerFluidAndAnOwnSealFallsThroughToLateral() {
        PalettedContainer<BlockState> states = slab();
        put(states, 4, 9, 4, Blocks.LAVA.defaultBlockState());    // lava directly above a stone cell
        put(states, 10, 9, 10, Blocks.WATER.defaultBlockState()); // water directly above a stone cell
        put(states, 5, 8, 10, Blocks.WATER.defaultBlockState());  // lateral source for the seal scene
        put(states, 10, 9, 4, Blocks.WATER.defaultBlockState());  // water column to seal in scene two
        MovementContext ctx = ctx(states, BotCaps.BREAK_PLACE);

        EditScratch e = ctx.edits().reset();
        assertEquals(PathEdits.BROKEN_LAVA, e.peekBreakKind(4, 8, 4),
                "digging directly under lava folds BROKEN_LAVA — the most certain flood there is (§5 tier 0a)");
        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(10, 8, 10),
                "digging directly under water folds BROKEN_WATER (the shipped wet-above rule, unchanged)");

        // Seal scene one: dry column above the break, eligible source beside it. Folding an own PLACE
        // into the above cell must not change the lateral flood — sealed falls THROUGH, never early-dry.
        e.reset();
        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(4, 8, 10), "lateral source ties pre-seal");
        e.requireFloor(4, 9, 10); // folds a place into the (air) cell above the break — the seal
        assertTrue(e.valid(), "the place must fold (open cell, solid face below, canPlace caps)");
        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(4, 8, 10),
                "an own seal above kills only the VERTICAL certainty — the lateral tiers still flood the break");

        // Seal scene two: the place goes INTO the water cell above (water is vanilla-replaceable), and no
        // lateral fluid exists — the seal removes the tier-0a flood and the lateral tiers find nothing.
        // Note the break cell still carries HAS_FLUID_NEIGHBOR from the COMMITTED water above (the flag is
        // scatter-owned, blind to this candidate's folds), so the dry verdict is the funnel's, not tier 0b's.
        e.reset();
        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(10, 8, 4), "unsealed: the column above floods it");
        e.requireFloor(10, 9, 4); // place into the water cell — fluids are open for placement (s52b)
        assertTrue(e.valid());
        assertEquals(PathEdits.BROKEN, e.peekBreakKind(10, 8, 4),
                "sealed above and nothing lateral: the break is dry — through the funnel, not the early-out");
    }

    // ---- Read discipline: scratch-first, diff-layered, no memo (§5) -------------------------------

    /**
     * The funnel resolves against the candidate's own in-scratch folds FIRST, then the {@link PathEdits}
     * diff — and memoises nothing (§5, owner-ratified: a cached verdict shared across search branches
     * could err WET, the one unrecoverable direction §8). Scene: a source whose east corridor dead-ends
     * over solid ground, so the break west of it ties and floods — until an earlier break OPENS the cell
     * under the corridor's end, turning it into a drain the slope search sees through the edit layer,
     * flipping the same break's verdict WATER → DRY. Asserted twice: the fold in this candidate's own
     * scratch (two peeks on ONE scratch disagree — no memo, scratch-first), and the same fold arriving
     * as an ancestor step through the path diff.
     */
    @Test
    void anEarlierFoldFlipsALaterVerdict() {
        // Scratch-first: peek, fold the drain-opening break into the SAME scratch, peek again.
        MovementContext ctx = ctx(drainableCorridorStates(), BotCaps.BREAK_PLACE);
        EditScratch e = ctx.edits().reset();
        assertEquals(PathEdits.BROKEN_WATER, e.peekBreakKind(4, 8, 8),
                "corridor floored: nothing drains, the break ties and floods");
        e.requireAir(8, 7, 8); // break the floor under the corridor's end — an own-scratch drain
        assertTrue(e.valid());
        assertEquals(PathEdits.BROKEN, e.peekBreakKind(4, 8, 8),
                "the SAME scratch now sees its own drain (slope distance 2 beats 1000) — verdict flips to dry;"
                        + " two answers from one scratch is the no-memo pin");

        // Diff-layered: the identical drain break folded by an ANCESTOR step (PathEdits), fresh scratch.
        MovementContext ctx2 = ctx(drainableCorridorStates(), BotCaps.BREAK_PLACE);
        ctx2.pathEdits().add(EditFixtures.step(new long[] { BlockPos.asLong(8, 7, 8) }, new long[0]));
        assertEquals(PathEdits.BROKEN, ctx2.edits().reset().peekBreakKind(4, 8, 8),
                "the path diff opens the same drain — canFlowDown/isHole read THROUGH PathEdits (§5)");
    }

    // ---- The BROKEN_LAVA descriptor read (§6) -----------------------------------------------------

    /**
     * A lava-folded break reads as honest LAVA to every later pop — damaging, fluid, transit-slow, not
     * swimmable-water, not standable — and as a SOURCE, deliberately (§6: the diff constants are the
     * {@code defaultBlockState()} source states, so later tier-1 evaluations on the same path treat the
     * flooded cell as spread-eligible, the conservative direction). A dry break beside it reads as air.
     * Mirrors {@code SubmergedMiningStanceTest.wetBrokenCellReadsAsWaterAndDryAsAir}.
     */
    @Test
    void lavaBrokenCellReadsAsLavaAndDryAsAir() {
        MovementContext ctx = ctx(slab(), BotCaps.BREAK_PLACE);
        long lavaCell = BlockPos.asLong(4, 8, 4);
        long dryCell = BlockPos.asLong(10, 8, 10);
        ctx.pathEdits().add(EditFixtures.lavaBreakStep(lavaCell));
        ctx.pathEdits().add(EditFixtures.step(new long[] { dryCell }, new long[0]));

        long lava = ctx.descriptorAt(4, 8, 4);
        assertTrue(NavBlock.isSwimmableLava(lava), "a lava-folded break reads as swimmable lava");
        assertTrue(NavBlock.isDamaging(lava), "…which is damaging (the costPerHitpoint pricing hook)");
        assertEquals(NavBlock.TRANSIT_FLUID, NavBlock.transitSlow(lava), "…and fluid-transit slow");
        assertFalse(ctx.water(lava), "…and NOT water to any consumer — no prone pose, no submerged stance");
        assertFalse(NavBlock.isStandable(lava), "…and not a floor");
        assertTrue(NavBlock.isFluidSource(lava),
                "…and a SOURCE, deliberately (§6): later tier-1 reads on this path see it spread-eligible");

        long dry = ctx.descriptorAt(10, 8, 10);
        assertTrue(NavBlock.isPassable(dry) && NavBlock.fluid(dry) == 0, "a dry break reads as air");
    }

    // ---- Fixture plumbing -------------------------------------------------------------------------

    private static int countMineDowns(BlockPathPlan plan) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == MovementRegistry.MINE_DOWN) n++;
        }
        return n;
    }

    /** Every break/place/set the plan folded, as a queryable {@link PathEdits} (kinds preserved verbatim
     *  through {@link EditSnapshot} — the same channel the splice baseline rides). */
    private static PathEdits foldedEdits(BlockPathPlan plan) {
        PathEdits edits = new PathEdits();
        edits.addSnapshot(EditSnapshot.fromRemainingSteps(plan, 0));
        return edits;
    }

    /** Flowing water at the given LEVEL blockstate value (1–7 = amount {@code 8-level}; ≥8 = falling,
     *  amount 8, {@code isSource() == false} — the mid-fall column cell). */
    private static BlockState flowingWater(int level) {
        return Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, level);
    }

    /**
     * The tier-2 competition scene: a 16×16 stone slab (floor plane y=8), the pool at {@code (5,8,8)},
     * the break candidate at {@code (4,8,8)}, and an east drain corridor — air cells over solid ground
     * from {@code x=6}, with the first HOLE (air below too) at corridor distance {@code holeDistance}
     * from the pool. Everything else is solid, so the break's own direction always scores 1000.
     */
    private static int verdictBesidePoolWithDrain(BlockState fluid, int holeDistance) {
        PalettedContainer<BlockState> states = slab();
        put(states, 5, 8, 8, fluid);
        for (int i = 1; i < holeDistance; i++) {
            put(states, 5 + i, 8, 8, Blocks.AIR.defaultBlockState()); // corridor floor stays solid
        }
        put(states, 5 + holeDistance, 8, 8, Blocks.AIR.defaultBlockState());
        put(states, 5 + holeDistance, 7, 8, Blocks.AIR.defaultBlockState()); // the hole: open below too
        return ctx(states, BotCaps.BREAK_PLACE).edits().reset().peekBreakKind(4, 8, 8);
    }

    /**
     * The read-discipline scene: source at {@code (5,8,8)}, break candidate at {@code (4,8,8)}, an east
     * corridor of air at {@code (6..8, 8, 8)} whose every cell sits over SOLID ground — no drain exists
     * until something opens {@code (8,7,8)}, at which point {@code (8,8,8)} becomes a hole at slope
     * distance 2 from the pool.
     */
    private static PalettedContainer<BlockState> drainableCorridorStates() {
        PalettedContainer<BlockState> states = slab();
        put(states, 5, 8, 8, Blocks.WATER.defaultBlockState());
        put(states, 6, 8, 8, Blocks.AIR.defaultBlockState());
        put(states, 7, 8, 8, Blocks.AIR.defaultBlockState());
        put(states, 8, 8, 8, Blocks.AIR.defaultBlockState());
        return states;
    }

    /** The §4.2 search scene: an open stone slab with a 1×1 LAVA pool at the start column's feet level.
     *  Single chunk (UNBUILT confines the frontier — the {@code SubmergedMiningStanceTest} pattern). */
    private static NavGridView buildLavaPoolWorld() {
        PalettedContainer<BlockState> states = slab();
        put(states, 8, 9, 8, Blocks.LAVA.defaultBlockState());
        return world(states);
    }

    /** A full 16×16 stone slab, y 0..8, in one section-0 container — the shared substrate. */
    private static PalettedContainer<BlockState> slab() {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= 8; y++) states.set(x, y, z, stone);
            }
        }
        return states;
    }

    private static void put(PalettedContainer<BlockState> states, int x, int y, int z, BlockState state) {
        states.set(x, y, z, state);
    }

    private static MovementContext ctx(PalettedContainer<BlockState> states, BotCaps caps) {
        return new MovementContext(world(states), caps);
    }

    /**
     * Assemble a single-chunk world through the FULL column pipeline — {@code classifyNavtypes} per
     * section, {@code computeFlags} with the above-grid overscan, then {@code computeDepth} over the whole
     * column, exactly as {@code ChunkNavBuilder} drives it live. This is what populates the scatter-owned
     * {@code HAS_FLUID_NEIGHBOR} the funnel's tier 0b reads; the {@code classifyInto} one-liner never runs
     * {@code computeDepth} and would leave the bit clear everywhere (the scout-documented synthetic-grid
     * trap — every lateral test here would pass vacuously dry).
     */
    private static NavGridView world(PalettedContainer<BlockState> states) {
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
