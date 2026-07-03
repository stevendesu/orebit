package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.splice.SpliceSeam;

import net.minecraft.core.BlockPos;

/**
 * Pure-logic tests for the splice primitive's value types (DESIGN-background-pathfinding.md P0 /
 * DESIGN-portal-route-layer.md §4.3): {@link EditSnapshot}'s latest-step-wins folding,
 * {@link PathEdits#addSnapshot}'s the-path-shadows-the-baseline ordering, and
 * {@link SpliceSeam#accepts}. Lives in the production package to reach {@link StepEdits}'s
 * package-private ctor + {@code load} seam (same as {@link EditFixtures}). No MC bootstrap needed —
 * {@link BlockPos#asLong} is pure bit math.
 */
class EditSnapshotTest {

    private static final long CELL_A = BlockPos.asLong(10, 64, 10);
    private static final long CELL_B = BlockPos.asLong(11, 64, 10);
    private static final long CELL_C = BlockPos.asLong(12, 64, 10);

    private static StepEdits step(long[] breaks, long[] places) {
        StepEdits se = new StepEdits();
        se.load(breaks, breaks.length, places, places.length);
        return se;
    }

    /** A plan whose only meaningful content is its per-step edits (waypoints/moves never read here). */
    private static BlockPathPlan planOf(StepEdits... steps) {
        BlockPos[] wps = new BlockPos[steps.length];
        Movement[] moves = new Movement[steps.length];
        Arrays.fill(wps, BlockPos.ZERO);
        return new BlockPathPlan(Arrays.asList(wps), Arrays.asList(moves), Arrays.asList(steps), 0f);
    }

    // ---- EditSnapshot.fromRemainingSteps -------------------------------------------------------------

    @Test
    void latestStepWinsAcrossSteps() {
        // Step 0 breaks A, step 1 places A: executing in order the world ends with A PLACED.
        BlockPathPlan plan = planOf(
                step(new long[] { CELL_A }, new long[0]),
                step(new long[0], new long[] { CELL_A }));

        EditSnapshot s = EditSnapshot.fromRemainingSteps(plan, 0);

        assertEquals(1, s.placeCount(), "the later step's PLACE must win");
        assertEquals(CELL_A, s.placeAt(0));
        assertEquals(0, s.breakCount(), "the earlier step's BREAK was overwritten");
    }

    @Test
    void duplicateCellsFoldOnce() {
        BlockPathPlan plan = planOf(
                step(new long[] { CELL_A, CELL_B }, new long[0]),
                step(new long[] { CELL_A }, new long[0]));

        EditSnapshot s = EditSnapshot.fromRemainingSteps(plan, 0);

        assertEquals(2, s.breakCount(), "A broken twice must appear once");
    }

    @Test
    void fromStepSlicesExecutedPrefixOff() {
        // Step 0's unique cell (B) is already applied by the follower; only step 1's A remains.
        BlockPathPlan plan = planOf(
                step(new long[] { CELL_B }, new long[0]),
                step(new long[] { CELL_A }, new long[0]));

        EditSnapshot s = EditSnapshot.fromRemainingSteps(plan, 1);

        assertEquals(1, s.breakCount());
        assertEquals(CELL_A, s.breakAt(0));
    }

    @Test
    void emptyCases() {
        assertSame(EditSnapshot.EMPTY, EditSnapshot.fromRemainingSteps(null, 0));
        BlockPathPlan plan = planOf(step(new long[] { CELL_A }, new long[0]));
        assertSame(EditSnapshot.EMPTY, EditSnapshot.fromRemainingSteps(plan, 1), "fromStep past the end");
        BlockPathPlan editFree = planOf(new StepEdits[] { null, null });
        assertSame(EditSnapshot.EMPTY, EditSnapshot.fromRemainingSteps(editFree, 0), "edit-free steps");
        assertTrue(EditSnapshot.EMPTY.isEmpty());
    }

    // ---- PathEdits.addSnapshot (the per-pop seed) -----------------------------------------------------

    @Test
    void pathEditsShadowTheBaseline() {
        // The in-search path BREAKS A; the baseline PLACED A (and PLACED C, untouched by the path).
        // Seed order (chain walk first, snapshot after) must leave A = BROKEN, C = PLACED.
        PathEdits pe = EditFixtures.withBroken(CELL_A);
        BlockPathPlan earlier = planOf(step(new long[0], new long[] { CELL_A, CELL_C }));

        pe.addSnapshot(EditSnapshot.fromRemainingSteps(earlier, 0));

        assertEquals(PathEdits.BROKEN, pe.kindAt(CELL_A), "the path's own edit must shadow the baseline");
        assertEquals(PathEdits.PLACED, pe.kindAt(CELL_C), "a baseline-only cell must read as its baseline kind");
        assertEquals(PathEdits.NONE, pe.kindAt(CELL_B));
    }

    @Test
    void snapshotSeedsAnOtherwiseEmptyDiff() {
        // The anyEdits-gate case: a search with no edits of its own must still see the baseline —
        // including through the bbox-gated coordinate read (the movement layer's form).
        PathEdits pe = new PathEdits();
        BlockPathPlan earlier = planOf(step(new long[] { CELL_B }, new long[0]));

        pe.addSnapshot(EditSnapshot.fromRemainingSteps(earlier, 0));

        assertFalse(pe.isEmpty(), "a seeded diff must not take the empty fast path");
        assertEquals(PathEdits.BROKEN, pe.kindAt(11, 64, 10), "the bbox-gated read must see the seed");
    }

    @Test
    void nullSnapshotIsANoOp() {
        PathEdits pe = new PathEdits();
        pe.addSnapshot(null);
        assertTrue(pe.isEmpty());
    }

    // ---- SpliceSeam.accepts ---------------------------------------------------------------------------

    @Test
    void seamAcceptanceIsChebyshev() {
        SpliceSeam seam = new SpliceSeam(new BlockPos(100, 64, 100), BlockPathfinder.MODE_AUTO,
                EditSnapshot.EMPTY);

        assertTrue(seam.accepts(new BlockPos(100, 64, 100)), "exact arrival");
        assertTrue(seam.accepts(new BlockPos(103, 66, 97)), "on the default tolerance boundary (3)");
        assertFalse(seam.accepts(new BlockPos(104, 64, 100)), "one past tolerance on X");
        assertFalse(seam.accepts(new BlockPos(100, 68, 100)), "one past tolerance on Y");
        assertEquals(SpliceSeam.DEFAULT_TOLERANCE_CHEB, seam.toleranceCheb());
    }
}
