package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
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
 * Search-level pin for §9's integration bullet (DESIGN-fluid-flow-prediction.md): "a {@code MineDown}
 * column beside pooled water prices every level submerged; beside a <i>draining</i> stream it does not"
 * — driven through TWO full {@code MACRO_MOVES=true} searches, so the macro MineDown's wet-column latch
 * is exercised against the LATERAL funnel verdict, not just the vertical wet-above rule.
 *
 * <p>The scene: a bedrock-confined 1×1 dig chimney (the {@code SubmergedMiningStanceTest} pattern —
 * break-only caps, bedrock refuses every lateral dig, so the route and its arithmetic are forced) whose
 * MID-DEPTH level (y=5) is laterally adjacent to a 1×1 water source in a bedrock pocket, with a 2-cell
 * east corridor that either ends in a visible drain hole (slope distance 2 ≤ 4) or dead-ends over solid
 * bedrock:
 * <ul>
 *   <li><b>drained ⇒ dry all the way down:</b> the drain beats the broken cell's 1000 outright, the
 *       funnel verdicts {@code BROKEN} at y=5, and nothing below chains wet. The macro plan must cost
 *       EXACTLY what the identical no-water world costs, and every folded kind must be {@code BROKEN}.
 *       This kills the mutation "latch wet off {@code HAS_FLUID_NEIGHBOR} instead of the folded kind" —
 *       the flag IS set beside the stream (the substrate is column-pipeline-built precisely so it is),
 *       but the verdict is dry;</li>
 *   <li><b>sealed ⇒ wet from the stream level down:</b> with the hole filled every direction ties at
 *       1000 and ties ALL win — {@code BROKEN_WATER} at y=5, chaining down y=4 and y=3 through the
 *       diff's own folds (tier 0a reads the previous level's fold as water above), while y=6..8 stay
 *       {@code BROKEN}. Macro cost must equal the {@code MACRO_MOVES=false} rerun to 1e-3 (the latch is
 *       the macro's half of the same model), and the micro plan folds the identical per-level kinds.</li>
 * </ul>
 *
 * <p><b>What "macro" means at this geometry, precisely:</b> in a 1×1 bedrock chimney the MineDown
 * macro's jump is always 1 — {@code MacroJump}'s escape-hedge is
 * {@code ceil(nearestOrthogonalFace / moveCost)} and a 1×1 column's orthogonal face distance is 0 — so
 * the plans here are per-level steps. The macro BRANCH of {@code MineDown.candidates} still runs (macros
 * on, cuboids wired, primary axis Y): its per-node {@code peekBreakKind} probe — the lateral funnel — is
 * exactly the latch these scenes exercise, and a flag-keyed mutation of it breaks (a)'s cost equality by
 * four 5×-stanced stone levels. The {@code J ≥ 2} slice/re-expansion channel is pinned separately by
 * {@code SliceStepKindChannelTest}, whose class Javadoc derives why it needs a wide insta-mine cuboid.
 *
 * <p>Substrate is built through the FULL column pipeline ({@code classifyNavtypes} → {@code computeFlags}
 * with overscan → {@code computeDepth}, the {@code ScatterIdentityTest} pattern) — the lateral
 * funnel's tier 0b reads the scatter-owned {@code HAS_FLUID_NEIGHBOR} flag that only {@code computeDepth}
 * writes; on a {@code classifyInto} grid every lateral verdict collapses to dry and the wet pin would
 * fail while the dry pin passed vacuously (the {@code FluidFlowVerdictTest} class-Javadoc trap).
 *
 * <p>Water-only by design (water carries no exposure cost term, so cost parity is exact against MineDown
 * pricing changes). Exact 0/0 goal tolerance so the shaft digs the full six levels (at the default ±2
 * vertical tolerance the dig would stop a level above the goal and the below-stream chain would never be
 * observable). Lives in this package for {@link NavGridView}'s package-private synthetic constructor.
 */
class FluidMacroLatchTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        MiningModel.buildTable(true, 0); // real hardness-derived ticks — the cost parities are arithmetic
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    /** Break-only caps (the {@code FluidFlowVerdictTest}/{@code SubmergedMiningStanceTest} ctor pattern):
     *  no placing, bedrock unbreakable — the shaft is the only route and its arithmetic exact. */
    private static final BotCaps BREAK_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, BotCaps.DEFAULT_COST_PER_HITPOINT,
            true, false, BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    private static final BlockPos START = new BlockPos(8, 8, 8);
    private static final BlockPos GOAL = new BlockPos(8, 2, 8);
    private static final RegionBound CORRIDOR = new RegionBound(-16, 16, 0, 33, -16, 16);

    /**
     * DRAINED-STREAM DRY PIN: the funnel sees the stream's drain (slope distance 2, another direction),
     * the break at y=5 LOSES the slope competition outright, and the whole shaft digs dry — cost equal to
     * the identical world with the water removed, kind {@code BROKEN} at every level. The flag beside the
     * stream is set (column pipeline), so a latch keyed on the flag rather than the folded kind prices
     * y≤5 submerged and breaks the cost equality.
     */
    @Test
    void drainedStreamDigsDryAtTheDryWorldsExactCost() {
        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = true;

            BlockPathPlan beside = BlockPathfinder.findPath(
                    buildShaftBesideStream(true, true), START, GOAL, BREAK_ONLY, CORRIDOR, CORRIDOR, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            BlockPathPlan noWater = BlockPathfinder.findPath(
                    buildShaftBesideStream(false, true), START, GOAL, BREAK_ONLY, CORRIDOR, CORRIDOR, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            assertNotNull(beside, "the bedrock-confined shaft is the only route — the search must find it");
            assertNotNull(noWater);
            assertEquals(6, countMineDowns(beside), "exact goal tolerance digs the full six-level chain");
            assertEquals(6, countMineDowns(noWater), "same geometry, same chain");

            assertEquals(noWater.cost(), beside.cost(), 1e-3f,
                    "a draining stream beside the shaft must not price a single level submerged —"
                            + " the verdict is dry, whatever the fluid-neighbor flag says");

            PathEdits folded = fold(beside);
            assertEquals(6, folded.editCount(), "one break per shaft level, nothing else folded");
            for (int y = 3; y <= 8; y++) {
                assertEquals(PathEdits.BROKEN, folded.kindAt(8, y, 8),
                        "the drained-stream shaft folds plain BROKEN at level y=" + y);
            }
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /**
     * LATERAL-POOL WET PIN: the same geometry with the drain sealed — the undrained pool ties (ties ALL
     * win), the stream level folds {@code BROKEN_WATER}, and the flood chains down the diff to every
     * deeper level while the levels above it stay dry. The macro plan must reproduce the micro plan's
     * cost to 1e-3 AND its per-level kinds — the wet-column latch and the per-node funnel are two halves
     * of one model.
     */
    @Test
    void sealedPoolFloodsTheStreamLevelDownAndMacroMatchesMicro() {
        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;

            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan macro = BlockPathfinder.findPath(
                    buildShaftBesideStream(true, false), START, GOAL, BREAK_ONLY, CORRIDOR, CORRIDOR, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            assertNotNull(macro);
            assertEquals(6, countMineDowns(macro));
            assertKinds(fold(macro), "macro");

            BlockPathfinder.MACRO_MOVES = false;
            BlockPathPlan micro = BlockPathfinder.findPath(
                    buildShaftBesideStream(true, false), START, GOAL, BREAK_ONLY, CORRIDOR, CORRIDOR, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            assertNotNull(micro);
            assertEquals(6, countMineDowns(micro));
            assertKinds(fold(micro), "micro");

            assertEquals(micro.cost(), macro.cost(), 1e-3f,
                    "macro wet-column latch == micro per-node funnel, level for level");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /** The sealed scene's per-level verdict map: dry above the stream level, wet from it down. */
    private static void assertKinds(PathEdits folded, String label) {
        assertEquals(6, folded.editCount(), label + ": one break per shaft level, nothing else folded");
        for (int y = 6; y <= 8; y++) {
            assertEquals(PathEdits.BROKEN, folded.kindAt(8, y, 8),
                    label + ": levels above the stream dig dry (y=" + y + ")");
        }
        for (int y = 3; y <= 5; y++) {
            assertEquals(PathEdits.BROKEN_WATER, folded.kindAt(8, y, 8),
                    label + ": the undrained pool ties at the stream level and chains down (y=" + y + ")");
        }
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    private static int countMineDowns(BlockPathPlan plan) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == MovementRegistry.MINE_DOWN) n++;
        }
        return n;
    }

    /** Every edit the plan folded, kinds verbatim through {@link EditSnapshot} + {@link PathEdits#addSnapshot}
     *  (the same channel the splice baseline rides). */
    private static PathEdits fold(BlockPathPlan plan) {
        PathEdits edits = new PathEdits();
        edits.addSnapshot(EditSnapshot.fromRemainingSteps(plan, 0));
        return edits;
    }

    /**
     * The shared scene, single chunk: bedrock y=0..9 everywhere; the dig chimney at world {@code (8,*,8)}
     * — stone y=0..8, air mouth at y=9 (the mouth is DRY: the wet pin must come from the LATERAL funnel,
     * never tier 0a's committed-water-above rule). Carved into the bedrock at y=5, east of the shaft:
     * <pre>
     *   (9,5,8)  water source ({@code water}) or air — the stream beside the shaft's mid-depth level
     *   (10,5,8) air   — corridor, over solid bedrock
     *   (11,5,8) air   — corridor end; over air when {@code drained} (the hole, slope distance 2),
     *   (11,4,8)       — air iff {@code drained}, else bedrock (the seal)
     * </pre>
     * North/south/west of the pool are bedrock, so the slope competition is exactly {broken cell east's
     * corridor} vs {the broken shaft cell} — drained: 2 beats 1000, dry; sealed: 1000 ties 1000, flood.
     * Built through the FULL column pipeline ({@link #assembleFlaggedWorld}) so the scatter-owned
     * {@code HAS_FLUID_NEIGHBOR} flag exists at the shaft's stream level (see the class Javadoc).
     */
    private static NavGridView buildShaftBesideStream(boolean water, boolean drained) {
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
        states.set(8, 9, 8, air);                                              // dry mouth
        states.set(9, 5, 8, water ? Blocks.WATER.defaultBlockState() : air);   // the stream
        states.set(10, 5, 8, air);                                             // corridor
        states.set(11, 5, 8, air);                                             // corridor end
        if (drained) states.set(11, 4, 8, air);                                // the drain hole

        return assembleFlaggedWorld(states);
    }

    /**
     * Assemble a single-chunk world through the FULL column pipeline — {@code classifyNavtypes} per
     * section, {@code computeFlags} with the above-grid overscan, then {@code computeDepth} over the whole
     * column, exactly as {@code ChunkNavBuilder} drives it live (the {@code ScatterIdentityTest}
     * pattern). The only build path that populates the scatter-owned {@code HAS_FLUID_NEIGHBOR} flag the
     * funnel's tier 0b reads; {@code classifyInto} never runs {@code computeDepth} and leaves the bit
     * permanently clear (the synthetic-grid trap).
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
