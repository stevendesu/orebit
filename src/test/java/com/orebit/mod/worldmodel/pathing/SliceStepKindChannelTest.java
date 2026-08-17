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
 * Pins the macro re-expansion's <b>break-kind channel</b> — {@code BlockPathfinder.sliceStep}'s
 * {@code bkKind[bn] = all.breakKindAt(i)} copy, the one line that carries each macro step's fold verdict
 * ({@code BROKEN} / {@code BROKEN_WATER} — DESIGN-fluid-flow-prediction.md §6) from the collapsed macro
 * edge onto the per-step {@code StepEdits} the plan ships home. Everything downstream reads it:
 * {@link EditSnapshot#fromRemainingSteps} (the splice baseline), {@link PathEdits#addSnapshot} (the seeded
 * search's diff), and {@code PathPlan.prescribedBreakKind} (which expectation the executor arms, §8.3).
 * Before this test, mutating that copy to always-{@code BROKEN} kept the whole suite green.
 *
 * <p><b>The geometry is load-bearing — a bedrock chimney CANNOT pin this.</b> {@code sliceStep} runs only
 * when a macro edge's jump {@code J > 1} survives reconstruction, and {@code MacroJump}'s escape-hedge is
 * {@code ceil(nearestOrthogonalFace / moveCost)}: a 1×1 shaft has {@code orth == 0} (jump 1 forever), and
 * a stone shaft's ~150-tick per-step mining cost needs an impossible 150+ blocks of uniform lateral
 * clearance (verified the hard way — an always-{@code BROKEN} mutation survived the chimney version of
 * this test because the whole plan rode micro {@code edge.copy()} edges). So the fixture is a
 * <b>3×3-chunk slime slab</b> — hardness 0, the {@code MiningModel} 1-tick insta-mine floor — with the
 * shaft at the slab's centre, {@code orth == 23}. Probe-measured per-step costs (this exact scene): wet
 * {@code 9.633} → {@code J = min(extent 8, goalBound 6, ceil(23/9.633)) = 3}; dry {@code 5.633} →
 * {@code J = 5} — every level of both plans reaches the plan through a sliced macro edge. A single-chunk
 * slab is NOT enough: {@code orth == 7} keeps the wet escape bound at 1 and the wet plan micro
 * (probe-verified), and a mutation that damages only sliced kinds then hides — the dry run's sliced
 * {@code BROKEN}s mutate to the very value the dry test expects. (MineDown emits EITHER the macro jump OR
 * the micro step, never both, so a {@code J ≥ 2} scene cannot silently fall back to the micro channel.)
 *
 * <p><b>Why the water is a full surface plane, not a 1×1 mouth:</b> every dig column must be identically
 * wet, or the (cheap, insta-mine) substrate lets the search dodge sideways to a dry column — the flagship
 * step-out behaviour — and the wet assertions die to a tie. With the plane, the straight shaft is the
 * unique minimum-move route and the tier-0a vertical rule chains {@code BROKEN_WATER} down all six
 * levels: level 1 from the committed plane, each deeper level from the previous level's own in-scratch
 * fold. The pin drives the REAL end-to-end chain: full {@code MACRO_MOVES=true} search → macro collapse →
 * reconstruction → {@code sliceStep} → {@code fromRemainingSteps} → {@code addSnapshot} → {@code kindAt}.
 *
 * <p><b>Substrate choice (stated per the fixture contract):</b> {@code classifyInto}-built, NOT the full
 * column pipeline. Safe here because the wet chain is purely VERTICAL: the funnel's tier 0a (fluid
 * directly above the break) reads descriptors — scratch-first, then the path diff, then the grid — never
 * the scatter-owned {@code HAS_FLUID_NEIGHBOR} flag that only {@code computeDepth} writes. Lateral-fluid
 * scenes need the full pipeline ({@code FluidMacroLatchTest}); this one does not.
 *
 * <p>Water-only by design: water carries no exposure cost term, so these kind/coverage assertions are
 * stable against MineDown pricing changes. Lives in this package for {@link NavGridView}'s
 * package-private synthetic constructor.
 */
class SliceStepKindChannelTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        MiningModel.buildTable(true, 0); // real hardness-derived ticks (slime = the 1-tick insta-mine floor)
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    /** Break-only caps (the {@code SubmergedMiningStanceTest} ctor pattern): no placing, so the search
     *  cannot pillar; the single-chunk world (UNBUILT beyond) confines the frontier. */
    private static final BotCaps BREAK_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, BotCaps.DEFAULT_COST_PER_HITPOINT,
            true, false, BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    private static final BlockPos START = new BlockPos(24, 8, 24);
    private static final BlockPos GOAL = new BlockPos(24, 2, 24);
    /** Covers the whole 3×3-chunk slab — the cuboid growth cap must not clip the {@code orth == 23}. */
    private static final RegionBound CORRIDOR = new RegionBound(-16, 64, 0, 33, -16, 64);

    /**
     * The wet pin: the surface plane sits directly above the first break, so the vertical rule chains
     * {@code BROKEN_WATER} down every macro-sliced step. Every one of the six shaft cells (y=3..8; exact
     * 0/0 goal tolerance digs the full chain to the goal floor) must fold {@code BROKEN_WATER} through
     * {@code fromRemainingSteps} + {@code addSnapshot} — an always-BROKEN mutation of {@code sliceStep}'s
     * kind copy fails every sliced cell (mutation-verified against this exact scene). The single-step
     * suffix then pins the PER-STEP slicing: the last step owns exactly the deepest break, still
     * kind-correct — kinds live on the steps that own the cells, not smeared over the plan.
     */
    @Test
    void wetMacroShaftFoldsBrokenWaterAtEverySlicedStep() {
        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan plan = BlockPathfinder.findPath(
                    buildSlimeSlabWorld(true), START, GOAL, BREAK_ONLY, CORRIDOR, CORRIDOR, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            assertNotNull(plan, "the straight shaft is the unique minimum-move route to the exact goal");
            assertEquals(6, countMineDowns(plan), "exact goal tolerance digs the full six-level chain");

            PathEdits folded = fold(plan, 0);
            assertEquals(6, folded.editCount(), "one break per shaft level, nothing else folded");
            for (int y = 3; y <= 8; y++) {
                assertEquals(PathEdits.BROKEN_WATER, folded.kindAt(24, y, 24),
                        "the wet-column verdict must ride the macro slice at shaft level y=" + y
                                + " (sliceStep's breakKindAt copy — the channel this test exists to pin)");
            }

            // Per-step ownership: the suffix starting at the LAST step folds exactly that step's slice —
            // sliceStep assigns a step the breaks at BOTH its body cells (fy+1 AND fy+2), so the step
            // landing on the goal floor (24,2,24) carries the two deepest breaks (y=3 and y=4; the y=4
            // cell is shared with the previous step's body1, which folding dedups). Pins that
            // fromRemainingSteps reads kinds off the individual sliced StepEdits, not the whole plan.
            PathEdits tail = fold(plan, plan.size() - 1);
            assertEquals(2, tail.editCount(), "the last slice owns its body1 AND body2 breaks (y=3, y=4)");
            assertEquals(PathEdits.BROKEN_WATER, tail.kindAt(24, 3, 24),
                    "the deepest break rides the last slice with its WATER verdict intact");
            assertEquals(PathEdits.BROKEN_WATER, tail.kindAt(24, 4, 24),
                    "…as does the shared body2 break");
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
        }
    }

    /**
     * The dry control: identical slab with no water plane — the same sliced channel must carry plain
     * {@code BROKEN} at every level (dry per-step cost 5.633 ⇒ {@code J = 5} then 1 — still macro-sliced).
     * Kills the inverse mutation (wet-everything / latch-stuck-on) and proves the wet pin above reads the
     * verdict, not a constant.
     */
    @Test
    void dryControlShaftFoldsBrokenAtEverySlicedStep() {
        boolean savedMacro = BlockPathfinder.MACRO_MOVES;
        boolean savedPartial = BlockPathfinder.PARTIAL_PATH;
        try {
            BlockPathfinder.PARTIAL_PATH = false;
            BlockPathfinder.MACRO_MOVES = true;
            BlockPathPlan plan = BlockPathfinder.findPath(
                    buildSlimeSlabWorld(false), START, GOAL, BREAK_ONLY, CORRIDOR, CORRIDOR, null,
                    BlockPathfinder.MODE_AUTO, null, 0L, null, 0, 0);
            assertNotNull(plan);
            assertEquals(6, countMineDowns(plan), "same geometry, same six-level chain");

            PathEdits folded = fold(plan, 0);
            assertEquals(6, folded.editCount());
            for (int y = 3; y <= 8; y++) {
                assertEquals(PathEdits.BROKEN, folded.kindAt(24, y, 24),
                        "a dry shaft folds plain BROKEN at level y=" + y + " — the channel is a copy, not a latch");
            }
        } finally {
            BlockPathfinder.MACRO_MOVES = savedMacro;
            BlockPathfinder.PARTIAL_PATH = savedPartial;
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

    /** The exact consumer chain under test: the plan's remaining-step snapshot folded into a fresh diff —
     *  the same channel the splice baseline rides ({@code PathEdits.addSnapshot} stores kinds verbatim). */
    private static PathEdits fold(BlockPathPlan plan, int fromStep) {
        PathEdits edits = new PathEdits();
        edits.addSnapshot(EditSnapshot.fromRemainingSteps(plan, fromStep));
        return edits;
    }

    /**
     * A 3×3-chunk field (chunks (0,0)..(2,2)) of a full <b>slime</b> slab, y=0..8 (one uniform,
     * insta-mine 48×9×48 cuboid — the class Javadoc's jump arithmetic needs {@code orth == 23} at the
     * centre shaft), with ({@code wet}) a full water plane at y=9 over every column — identically wet
     * everywhere, so the straight shaft is the unique optimum and the wet verdict is untied to any dodge.
     * Every chunk is the same pattern, so one classified slab section (and one air section) is shared
     * across all nine chunk columns — the sections are read-only under the search. {@code classifyInto}-
     * built — see the class Javadoc for why that suffices for a purely vertical chain. Everything outside
     * the nine chunks is UNBUILT, confining the frontier.
     */
    private static NavGridView buildSlimeSlabWorld(boolean wet) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState slime = Blocks.SLIME_BLOCK.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int yy = 0; yy <= 8; yy++) states.set(x, yy, z, slime);
                if (wet) states.set(x, 9, z, water);
            }
        }

        NavSection slab = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, slab.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                chunks.put(NavStore.key(cx, cz), new NavSection[] { slab, airSection, airSection, airSection });
            }
        }
        return new NavGridView(0, chunks);
    }
}
