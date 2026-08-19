package com.orebit.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.SpliceTestPlans;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The §10 U4 move-compatibility rule ({@link BotNavigator#firstIncompatibleStep} /
 * {@link BotNavigator#incompatibleCell}, DESIGN-replan-handoff.md §10) as a table over hand-built
 * plans and hand-classified grids — the {@code HorizonSeamWalkTest} precedent for the pure static,
 * the {@code WindowTargetingWaterYTest} pattern for the synthetic {@link NavGridView#overSections}
 * view (real classifier, no {@code ServerLevel}).
 *
 * <p>What is pinned: a cell edit invalidates a step iff the new NavType no longer supports that
 * step's SELECTED movement — the ground family's wall/floor rules (incl. floor→fluid: you cannot
 * walk on fluid; with the Fall water-cushion carve-out), the fluid family's fluid-feet rule, the
 * Climb arm, and the mine-cell rule for unexecuted folded breaks — and every ambiguous class is
 * deliberately NOT breakage: openables in any state (the Need machinery's property), partial-height
 * intrusions, partial floors, unbuilt cells, and every movement without a ratified arm. The
 * live-tick consequences (U1 prompt replan / U5 emergency drop) are the ReplanCourse's to prove,
 * not this test's.
 */
class PrefixIntegrityTest {

    private static final int MINY = 0;

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    /**
     * One chunk (0,0), minY 0: a stone floor layer at y=0, air above, then {@code mutate} stamps the
     * scenario's blocks into the y 0..15 section before classification. Bot-relevant cells all sit in
     * that first section.
     */
    private static NavGridView grid(Consumer<PalettedContainer<BlockState>> mutate) {
        PalettedContainer<BlockState> s0 = emptyStates();
        fillLayer(s0, 0, Blocks.STONE.defaultBlockState());
        if (mutate != null) {
            mutate.accept(s0);
        }
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0),
                new NavSection[] { classify(s0, false), airSection(), airSection(), airSection() });
        return NavGridView.overSections(MINY, chunks);
    }

    private static PalettedContainer<BlockState> emptyStates() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static void fillLayer(PalettedContainer<BlockState> states, int yLocal, BlockState state) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                states.set(x, yLocal, z, state);
            }
        }
    }

    private static NavSection classify(PalettedContainer<BlockState> states, boolean allAir) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, allAir, section.getTraversalGrid());
        return section;
    }

    private static NavSection airSection() {
        return classify(emptyStates(), true);
    }

    /** Three stands (1,1,0) → (2,1,0) → (3,1,0) on the y=0 floor (floorY 0), all driven by {@code move}. */
    private static BlockPathPlan linePlan(Movement move) {
        return linePlan(move, null, -1);
    }

    /** As {@link #linePlan(Movement)}, with {@code edits} attached to step {@code editStep}. */
    private static BlockPathPlan linePlan(Movement move, StepEdits edits, int editStep) {
        BlockPos[] stands = { new BlockPos(1, 1, 0), new BlockPos(2, 1, 0), new BlockPos(3, 1, 0) };
        Movement[] moves = new Movement[stands.length];
        Arrays.fill(moves, move);
        StepEdits[] stepEdits = new StepEdits[stands.length];
        if (editStep >= 0) {
            stepEdits[editStep] = edits;
        }
        int[] floorYs = { 0, 0, 0 };
        float[] costs = { 1f, 1f, 1f };
        return new BlockPathPlan(Arrays.asList(stands), Arrays.asList(moves),
                Arrays.asList(stepEdits), floorYs, costs, 3f);
    }

    /** The ground-family walk plan (all Traverse) most cases use. */
    private static BlockPathPlan walkPlan() {
        return linePlan(MovementRegistry.TRAVERSE);
    }

    /** A single-step plan: {@code move} into the stand (2,1,0) (floorY 0). */
    private static BlockPathPlan oneStep(Movement move) {
        return new BlockPathPlan(Arrays.asList(new BlockPos[] { new BlockPos(2, 1, 0) }),
                Arrays.asList(new Movement[] { move }), Arrays.asList(new StepEdits[1]),
                new int[] { 0 }, new float[] { 1f }, 1f);
    }

    /** Shorthand: first incompatible step over the whole remaining plan, everything unexecuted. */
    private static int firstBad(BlockPathPlan p, int cursor, NavGridView g) {
        return BotNavigator.firstIncompatibleStep(p, cursor, 0, g);
    }

    // ---- ground family: definite breakage -------------------------------------------------------------

    @Test
    void anUntouchedGridBreaksNothing() {
        assertEquals(-1, firstBad(walkPlan(), 0, grid(null)));
    }

    @Test
    void aFullBlockInAPlannedFeetCellIsBreakage() {
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g),
                "step 1's feet cell (2,1,0) now reads a full-height wall");
        assertEquals(new BlockPos(2, 1, 0), BotNavigator.incompatibleCell(walkPlan(), 1, true, g));
    }

    @Test
    void aFullBlockInAPlannedHeadCellIsBreakage() {
        NavGridView g = grid(s -> s.set(2, 2, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g));
        assertEquals(new BlockPos(2, 2, 0), BotNavigator.incompatibleCell(walkPlan(), 1, true, g));
    }

    @Test
    void aFenceInAPlannedFeetCellIsBreakage() {
        // topY ≥ 16 (a fence tops at 24), not == 16 — the narrow-top blocker family still walls the cell.
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.OAK_FENCE.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g));
    }

    @Test
    void aVanishedDestFloorIsBreakage() {
        // The floor under step 1 is gone: nothing to stand on, float in, or hang from at (2,0,0).
        NavGridView g = grid(s -> s.set(2, 0, 0, Blocks.AIR.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g));
        assertEquals(new BlockPos(2, 0, 0), BotNavigator.incompatibleCell(walkPlan(), 1, true, g));
    }

    @Test
    void aGroundFloorTurnedWaterIsBreakage() {
        // U4: floor→fluid invalidates a walking step — you cannot walk on fluid. (The first shape read a
        // fluid floor as "supported", a swim-family accommodation the family dispatch now owns.)
        NavGridView g = grid(s -> s.set(2, 0, 0, Blocks.WATER.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g));
        assertEquals(new BlockPos(2, 0, 0), BotNavigator.incompatibleCell(walkPlan(), 1, true, g));
    }

    @Test
    void aGroundFloorTurnedLavaIsBreakage() {
        NavGridView g = grid(s -> s.set(2, 0, 0, Blocks.LAVA.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g));
    }

    @Test
    void aHeadWallOverAFloodedFeetCellIsStillBreakageForAWalkingStep() {
        // The first shape skipped the head test whenever the feet cell read fluid (the family was
        // unknown). Under U4 the SELECTED move is a Traverse: a 1.8-tall walker cannot pass a solid
        // head cell regardless of the puddle at its feet.
        NavGridView g = grid(s -> {
            s.set(2, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(2, 2, 0, Blocks.STONE.defaultBlockState());
        });
        assertEquals(1, firstBad(walkPlan(), 0, g));
    }

    @Test
    void theCurrentStepItselfIsValidatedToo() {
        // U5's discriminator is the caller's (broken == cursor); the rule itself is cursor-uniform.
        NavGridView g = grid(s -> s.set(1, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(0, firstBad(walkPlan(), 0, g));
    }

    // ---- ground family: the Fall water-cushion carve-out ----------------------------------------------

    @Test
    void aFallLandingIntoDeepWaterKeepsItsFluidFloor() {
        // Feet AND floor fluid (non-damaging) under a FALL: the water-cushion landing arrangement the
        // planner itself prices — indistinguishable from, and physically equivalent to, the planned
        // shape. Not breakage.
        NavGridView g = grid(s -> {
            s.set(2, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(2, 0, 0, Blocks.WATER.defaultBlockState());
        });
        assertEquals(-1, firstBad(oneStep(MovementRegistry.FALL), 0, g));
    }

    @Test
    void aFallLandingOverAOneDeepFluidFloorIsBreakage() {
        // Dry feet over a fluid floor cell is NOT the cushion arrangement — a 1-deep flood arrived
        // under the landing. Invalidate (U4 floor→fluid).
        NavGridView g = grid(s -> s.set(2, 0, 0, Blocks.WATER.defaultBlockState()));
        assertEquals(0, firstBad(oneStep(MovementRegistry.FALL), 0, g));
    }

    @Test
    void aFallLandingIntoDeepLavaIsBreakage() {
        // The cushion carve-out is water-only: lava is a damaging fluid, never a planned cushion.
        NavGridView g = grid(s -> {
            s.set(2, 1, 0, Blocks.LAVA.defaultBlockState());
            s.set(2, 0, 0, Blocks.LAVA.defaultBlockState());
        });
        assertEquals(0, firstBad(oneStep(MovementRegistry.FALL), 0, g));
    }

    // ---- fluid family ---------------------------------------------------------------------------------

    @Test
    void aSwimStepWhoseFeetStayWaterIsCompatible() {
        NavGridView g = grid(s -> {
            s.set(1, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(2, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(3, 1, 0, Blocks.WATER.defaultBlockState());
        });
        assertEquals(-1, firstBad(linePlan(MovementRegistry.SWIM), 0, g));
    }

    @Test
    void aSwimStepWhoseFluidCellTurnedSolidIsBreakage() {
        // Planned fluid cell became solid (U4 fluid family) — step 1's water was filled in.
        NavGridView g = grid(s -> {
            s.set(1, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(2, 1, 0, Blocks.STONE.defaultBlockState());
            s.set(3, 1, 0, Blocks.WATER.defaultBlockState());
        });
        assertEquals(1, firstBad(linePlan(MovementRegistry.SWIM), 0, g));
        assertEquals(new BlockPos(2, 1, 0),
                BotNavigator.incompatibleCell(linePlan(MovementRegistry.SWIM), 1, true, g));
    }

    @Test
    void aSwimStepWhoseFluidCellDrainedToAirIsBreakage() {
        // Planned fluid cell became air (U4 fluid family) — the water step 1 swims through drained.
        NavGridView g = grid(s -> {
            s.set(1, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(3, 1, 0, Blocks.WATER.defaultBlockState());
        });
        assertEquals(1, firstBad(linePlan(MovementRegistry.SWIM), 0, g));
    }

    @Test
    void aSolidCeilingOverAProneSwimIsNotBreakage() {
        // The fluid family takes no head-cell verdict: a prone sprint-swim legitimately runs under
        // solid ceilings — only the fluid itself is load-bearing.
        NavGridView g = grid(s -> {
            s.set(1, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(2, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(3, 1, 0, Blocks.WATER.defaultBlockState());
            s.set(2, 2, 0, Blocks.STONE.defaultBlockState());
        });
        assertEquals(-1, firstBad(linePlan(MovementRegistry.SPRINT_SWIM), 0, g));
    }

    // ---- Climb ----------------------------------------------------------------------------------------

    @Test
    void anIntactRungIsCompatible() {
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.LADDER.defaultBlockState()));
        assertEquals(-1, firstBad(oneStep(MovementRegistry.CLIMB), 0, g));
    }

    @Test
    void aMidShaftRungThatVanishedIsBreakage() {
        // Stand (2,2,0) with floorY 1: the rung is gone (feet air) and nothing below offers support
        // (air there too) — the hang has no substrate left.
        BlockPathPlan p = new BlockPathPlan(Arrays.asList(new BlockPos[] { new BlockPos(2, 2, 0) }),
                Arrays.asList(new Movement[] { MovementRegistry.CLIMB }),
                Arrays.asList(new StepEdits[1]), new int[] { 1 }, new float[] { 1f }, 1f);
        NavGridView g = grid(null);
        assertEquals(0, firstBad(p, 0, g));
        assertEquals(new BlockPos(2, 1, 0), BotNavigator.incompatibleCell(p, 0, true, g));
    }

    @Test
    void aClimbColumnFilledSolidIsBreakage() {
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(0, firstBad(oneStep(MovementRegistry.CLIMB), 0, g));
    }

    @Test
    void aClimbDismountStandOverRealGroundIsCompatible() {
        // The dismount/exit-top arm: feet air, floorY 0 with the stone floor below — a Climb step may
        // legitimately END on plain ground, so a non-climbable feet cell over support is no verdict.
        assertEquals(-1, firstBad(oneStep(MovementRegistry.CLIMB), 0, grid(null)));
    }

    // ---- the mine-cell rule (edit-carrying steps) -----------------------------------------------------

    @Test
    void anUnchangedPlannedMineCellIsCompatible() {
        BlockPathPlan p = linePlan(MovementRegistry.TRAVERSE,
                SpliceTestPlans.breakStepEdits(BlockPos.asLong(2, 1, 0)), 1);
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(-1, firstBad(p, 0, g),
                "a breakable solid still sits where the fold planned to mine — the mine still runs");
    }

    @Test
    void aPlannedMineCellSwappedToUnbreakableIsBreakage() {
        BlockPathPlan p = linePlan(MovementRegistry.TRAVERSE,
                SpliceTestPlans.breakStepEdits(BlockPos.asLong(2, 1, 0)), 1);
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.BEDROCK.defaultBlockState()));
        assertEquals(1, firstBad(p, 0, g));
        assertEquals(new BlockPos(2, 1, 0), BotNavigator.incompatibleCell(p, 1, true, g));
    }

    @Test
    void aPlannedMineCellThatFloodedIsBreakage() {
        BlockPathPlan p = linePlan(MovementRegistry.TRAVERSE,
                SpliceTestPlans.breakStepEdits(BlockPos.asLong(2, 1, 0)), 1);
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.WATER.defaultBlockState()));
        assertEquals(1, firstBad(p, 0, g));
    }

    @Test
    void aPlannedMineCellAlreadyBrokenByAnotherIsCompatible() {
        // The cell reads air: the planned break is already satisfied — the plan still walks.
        BlockPathPlan p = linePlan(MovementRegistry.TRAVERSE,
                SpliceTestPlans.breakStepEdits(BlockPos.asLong(2, 1, 0)), 1);
        assertEquals(-1, firstBad(p, 0, grid(null)));
    }

    @Test
    void anExecutedStepsMineCellTakesNoVerdict() {
        // firstUnedited past the step: its edits already ran (its own break made these cells air, and
        // any refill is the Need machinery's reactive property) — even a bedrock swap is no verdict.
        BlockPathPlan p = linePlan(MovementRegistry.TRAVERSE,
                SpliceTestPlans.breakStepEdits(BlockPos.asLong(2, 1, 0)), 1);
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.BEDROCK.defaultBlockState()));
        assertEquals(-1, BotNavigator.firstIncompatibleStep(p, 1, 2, g),
                "cursor on the executed step, firstUnedited beyond it — the mine rule is exempt");
        assertNull(BotNavigator.incompatibleCell(p, 1, false, g));
    }

    @Test
    void anEditCarryingStepsOtherCellsTakeNoVerdict() {
        // The step's own break/place/toggle machinery owns its cells (a wall across an edit-carrying
        // step re-arms Need reactively) — only the mine-cell rule applies, and this step breaks nothing.
        BlockPathPlan p = linePlan(MovementRegistry.TRAVERSE, SpliceTestPlans.emptyStepEdits(), 1);
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(-1, firstBad(p, 0, g));
        assertNull(BotNavigator.incompatibleCell(p, 1, true, g));
    }

    // ---- deliberate non-breakage (the conservative side) ----------------------------------------------

    @Test
    void aDoorIsNeverBreakage() {
        // A (closed) door across the corridor is the Need machinery's property — a toggle, not a wall.
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.OAK_DOOR.defaultBlockState()));
        assertEquals(-1, firstBad(walkPlan(), 0, g));
    }

    @Test
    void aPartialHeightIntrusionIsNotDefiniteBreakage() {
        // A bottom slab in the feet cell (topY 8 < 16) is a judgement call, not a definite wall — skip.
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.OAK_SLAB.defaultBlockState()));
        assertEquals(-1, firstBad(walkPlan(), 0, g));
    }

    @Test
    void aPartialFloorStandSkipsTheFeetCellTest() {
        // floorY == wp.y (the bot stands WITHIN the slab's cell): the feet cell legitimately carries
        // collision, so the body test must skip it — only the head cell above is judged.
        BlockPos[] stands = { new BlockPos(1, 1, 0), new BlockPos(2, 1, 0) };
        Movement[] moves = { MovementRegistry.TRAVERSE, MovementRegistry.TRAVERSE };
        int[] floorYs = { 0, 1 }; // step 1's floor IS its feet cell — the bottom-partial arrangement
        float[] costs = { 1f, 1f };
        BlockPathPlan p = new BlockPathPlan(Arrays.asList(stands), Arrays.asList(moves),
                Arrays.asList(new StepEdits[2]), floorYs, costs, 2f);
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.OAK_SLAB.defaultBlockState()));
        assertEquals(-1, firstBad(p, 0, g));
    }

    @Test
    void anUnbuiltCellGivesNoVerdict() {
        // Chunk (1,0) is never built: a plan step there reads AIR everywhere — no flag either way.
        BlockPos[] stands = { new BlockPos(17, 1, 0) };
        Movement[] moves = { MovementRegistry.TRAVERSE };
        BlockPathPlan p = new BlockPathPlan(Arrays.asList(stands), Arrays.asList(moves),
                Arrays.asList(new StepEdits[1]), new int[] { 0 }, new float[] { 1f }, 1f);
        assertEquals(-1, firstBad(p, 0, grid(null)));
    }

    @Test
    void aClimbableFeetCellExemptsAGroundStepsFloorVerdict() {
        // A vine grown into the stand cell supports a hang regardless of what sits below — the floor
        // checks (vanished AND floor→fluid) take no verdict while the feet read climbable.
        NavGridView g = grid(s -> {
            s.set(2, 1, 0, Blocks.VINE.defaultBlockState());
            s.set(2, 0, 0, Blocks.AIR.defaultBlockState());
        });
        assertEquals(-1, firstBad(walkPlan(), 0, g));
    }

    @Test
    void aMovementWithoutARatifiedArmIsAlwaysCompatible() {
        // U4's conservative direction: no invalidation is invented beyond the ratified arms — a Pillar
        // step whose feet cell walled over takes no verdict here (its own edits/phases own it).
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(-1, firstBad(linePlan(MovementRegistry.PILLAR), 0, g));
        assertNull(BotNavigator.incompatibleCell(linePlan(MovementRegistry.PILLAR), 1, true, g));
    }

    // ---- scan bounds ----------------------------------------------------------------------------------

    @Test
    void theScanCoversTheWholeRemainingPlanButNeverLooksBack() {
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(1, firstBad(walkPlan(), 0, g),
                "the U1 scan is cursor..end — a breakage anywhere in the remainder is found");
        assertEquals(-1, firstBad(walkPlan(), 2, g),
                "a cursor past the broken step never looks back");
    }
}
