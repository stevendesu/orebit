package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * The own-edit acknowledgement's load-bearing predicate ({@code PathPlan.prescribesEdit}): a world change is
 * forgiven ONLY when this plan actually folded an edit at that exact cell.
 *
 * <p><b>What this guards.</b> The bot's edits reach the nav grid through the same {@code onBlockChanged} seam
 * as any other block change, so every break the plan ordered used to bump its own chunk version, trip
 * {@code planImpacted}, and re-search the plan that ordered it — 16 of 27 re-searches in the 2026-08-06
 * flagship run. {@code expectOwnEdit} announces the exact mutation to the grid instead, so only FOREIGN
 * changes move the counter a plan snapshots. The danger in that fix is over-forgiveness: {@code /bot mine}, a
 * gather run, and the navigator all drive the SAME actuators ({@code BotMining}, {@code AllyBotEntity.place}),
 * so an announcement that trusted the caller would quietly suppress re-searches for edits the plan never
 * predicted — silently blinding the invalidation gate, which is the one direction an invalidation bug must
 * never fail.
 *
 * <p>So the predicate is checked against the plan's OWN {@link StepEdits}, and this pins that: prescribed
 * break and prescribed place are recognised on any step, and a cell the plan never mentions is not — even
 * when it sits between two cells that are prescribed, and even when it differs on a single axis.
 *
 * <p>The classification around it (announce the mutation, compare {@code foreignVersion} to the snapshot) is
 * welded to a live {@code ServerLevel} and cannot be stood up under the Knot test classloader — the same
 * split {@code NavGridEpochTest} and {@code NetherPortalIndexTest} document. It is an equality check over
 * this predicate's result; the predicate is the part with semantics.
 */
class PathPlanOwnEditTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final long BREAK_A = new BlockPos(10, 64, 10).asLong();
    private static final long BREAK_B = new BlockPos(10, 65, 10).asLong();
    private static final long PLACE_A = new BlockPos(11, 63, 10).asLong();

    /** A two-step plan: step 0 folds nothing, step 1 folds the breaks + the place above. */
    private static BlockPathPlan plan() {
        final Movement any = MovementRegistry.TIER1.get(0);   // stateless singleton; identity is irrelevant here
        final List<BlockPos> waypoints = Arrays.asList(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10));
        final List<Movement> moves = Arrays.asList(any, any);
        final StepEdits step1 = EditFixtures.step(new long[] {BREAK_A, BREAK_B}, new long[] {PLACE_A});
        return new BlockPathPlan(waypoints, moves, Arrays.asList(null, step1), 0f);
    }

    @Test
    void prescribedBreakIsRecognised() {
        final BlockPathPlan bp = plan();
        assertTrue(PathPlan.prescribesEdit(bp, 10, 64, 10), "the plan folded a break here");
        assertTrue(PathPlan.prescribesEdit(bp, 10, 65, 10), "every broken cell of the step counts, not just the first");
    }

    @Test
    void prescribedPlaceIsRecognised() {
        assertTrue(PathPlan.prescribesEdit(plan(), 11, 63, 10), "a place is an own edit exactly as a break is");
    }

    @Test
    void unprescribedEditIsNotForgiven() {
        final BlockPathPlan bp = plan();
        // The gather / `/bot mine` case: same actuator, same tick, a cell this plan never mentioned.
        assertFalse(PathPlan.prescribesEdit(bp, 20, 64, 20), "a cell the plan never mentions is divergence");
        // Between the two prescribed breaks on the Y axis — a range test rather than an exact match would
        // wrongly accept this, and would forgive a vine growing in the middle of a mined shaft.
        assertFalse(PathPlan.prescribesEdit(bp, 10, 66, 10), "adjacency is not prescription");
        // One axis off each way: the match must be on all three coordinates.
        assertFalse(PathPlan.prescribesEdit(bp, 11, 64, 10), "X differs");
        assertFalse(PathPlan.prescribesEdit(bp, 10, 64, 11), "Z differs");
    }

    @Test
    void editFreeStepsAreSkippedSafely() {
        // Step 0's StepEdits is null (the overwhelmingly common case) — the scan must tolerate it rather
        // than NPE on the first edit the bot executes.
        final BlockPathPlan bp = plan();
        assertFalse(PathPlan.prescribesEdit(bp, 0, 0, 0));
    }
}
