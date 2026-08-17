package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.MixedKindEditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Pins {@code PathPlan.prescribedBreakKind} — the package-private seam beside {@code prescribesEdit}
 * ({@link PathPlanOwnEditTest}) that recovers a folded break's {@link PathEdits} kind from the plan's own
 * {@link StepEdits}. It is the only link deciding WHICH expectation {@code expectOwnEdit} arms for an
 * executed break (DESIGN-fluid-flow-prediction.md §8.3): a fluid kind arms the two-phase {air, fluid}
 * set ({@code NavGridUpdater.expectFloodedBreak}), everything else the plain to-air direction — so a
 * wrong answer here silently mis-arms the invalidation gate in one direction or the other.
 *
 * <p>What each case kills: the mixed-kind single step kills "return index 0's kind" and "a place at the
 * coordinate counts as a break"; the multi-step plan kills "return the first step's kind" (the kind must
 * come from the step that OWNS the cell, {@code BROKEN_LAVA} included); the no-match cases pin the
 * documented contract — {@link PathEdits#NONE} for any cell no step breaks, even one the plan prescribes
 * as a place, and even one adjacent to or a single axis off a real break.
 */
class PathPlanBreakKindTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final long WET_CELL = new BlockPos(10, 64, 10).asLong();
    private static final long DRY_CELL = new BlockPos(11, 64, 10).asLong();
    private static final long PLACE_CELL = new BlockPos(12, 63, 10).asLong();

    /** A plan of {@code steps.length} waypoints carrying the given per-step edits (nulls allowed — the
     *  overwhelmingly common edit-free step, which the walk must skip safely). */
    private static BlockPathPlan plan(StepEdits... steps) {
        final Movement any = MovementRegistry.TIER1.get(0); // stateless singleton; identity irrelevant here
        final BlockPos[] wps = new BlockPos[steps.length];
        final Movement[] mvs = new Movement[steps.length];
        for (int i = 0; i < steps.length; i++) {
            wps[i] = new BlockPos(10 + i, 64, 10);
            mvs[i] = any;
        }
        return new BlockPathPlan(Arrays.asList(wps), Arrays.asList(mvs), Arrays.asList(steps), 0f);
    }

    /** One step, three edits, three kinds of answer: the WATER break, the DRY break beside it, and a
     *  PLACE — each coordinate must resolve to ITS OWN entry, not the step's first break and not a
     *  place-as-break match. */
    @Test
    void mixedKindStepReturnsTheMatchedCellsKind() {
        final StepEdits mixed = MixedKindEditFixtures.mixedBreakStep(
                new long[] { WET_CELL, DRY_CELL },
                new int[] { PathEdits.BROKEN_WATER, PathEdits.BROKEN },
                new long[] { PLACE_CELL });
        final BlockPathPlan bp = plan(null, mixed);

        assertEquals(PathEdits.BROKEN_WATER, PathPlan.prescribedBreakKind(bp, 10, 64, 10),
                "the wet break's own kind");
        assertEquals(PathEdits.BROKEN, PathPlan.prescribedBreakKind(bp, 11, 64, 10),
                "the dry break beside it — NOT index 0's kind");
        assertEquals(PathEdits.NONE, PathPlan.prescribedBreakKind(bp, 12, 63, 10),
                "a PLACE at the coordinate is not a break — kind recovery must not match the place list");
        assertTrue(PathPlan.prescribesEdit(bp, 12, 63, 10),
                "…even though the cell IS prescribed (the broader question prescribesEdit answers first)");
    }

    /** Three edit-bearing steps, one kind each ({@code BROKEN_WATER} / {@code BROKEN_LAVA} /
     *  {@code BROKEN}) — each cell must return the kind of the step that OWNS it, pinning the walk across
     *  steps rather than a first-step (or first-edit-anywhere) shortcut. */
    @Test
    void multiStepPlanReturnsTheOwningStepsKind() {
        final long lavaCell = new BlockPos(11, 63, 10).asLong();
        final BlockPathPlan bp = plan(
                null,
                EditFixtures.wetBreakStep(WET_CELL),
                EditFixtures.lavaBreakStep(lavaCell),
                EditFixtures.step(new long[] { DRY_CELL }, new long[0]));

        assertEquals(PathEdits.BROKEN_WATER, PathPlan.prescribedBreakKind(bp, 10, 64, 10),
                "step 1's water break");
        assertEquals(PathEdits.BROKEN_LAVA, PathPlan.prescribedBreakKind(bp, 11, 63, 10),
                "step 2's lava break — the third kind rides the same recovery (§4.2)");
        assertEquals(PathEdits.BROKEN, PathPlan.prescribedBreakKind(bp, 11, 64, 10),
                "step 3's dry break — not the first step's kind");
    }

    /** The no-match contract, exactly as the method documents it: {@link PathEdits#NONE} when no step
     *  breaks the cell — a cell nowhere in the plan, a cell adjacent to a break, and a cell one axis off
     *  a break each way (the same all-three-coordinates discipline {@code prescribesEdit} pins). */
    @Test
    void unmatchedCellReturnsNone() {
        final BlockPathPlan bp = plan(
                null,
                MixedKindEditFixtures.mixedBreakStep(
                        new long[] { WET_CELL }, new int[] { PathEdits.BROKEN_WATER },
                        new long[] { PLACE_CELL }));

        assertEquals(PathEdits.NONE, PathPlan.prescribedBreakKind(bp, 20, 64, 20),
                "a cell the plan never mentions");
        assertEquals(PathEdits.NONE, PathPlan.prescribedBreakKind(bp, 10, 65, 10),
                "adjacency is not a break match");
        assertEquals(PathEdits.NONE, PathPlan.prescribedBreakKind(bp, 9, 64, 10), "X differs");
        assertEquals(PathEdits.NONE, PathPlan.prescribedBreakKind(bp, 10, 64, 11), "Z differs");
    }
}
