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
    private static final long CELL_D = BlockPos.asLong(13, 64, 10);

    private static StepEdits step(long[] breaks, long[] places) {
        StepEdits se = new StepEdits();
        byte[] kinds = new byte[breaks.length];
        Arrays.fill(kinds, (byte) PathEdits.BROKEN); // dry breaks — the kind is stored verbatim now
        se.load(breaks, kinds, breaks.length, places, places.length,
                new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
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
    void wetBreakFlagsSurviveTheSnapshotAndFoldAsBrokenWater() {
        // A wet break (the fold funnel's flood verdict) must stay wet through the splice baseline: the
        // seeded search reads the flooded shaft as WATER, not phantom air — else it re-prices the dig dry.
        StepEdits se = new StepEdits();
        se.load(new long[] { CELL_A, CELL_B },
                new byte[] { (byte) PathEdits.BROKEN_WATER, (byte) PathEdits.BROKEN }, 2,
                new long[0], 0, new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
        EditSnapshot s = EditSnapshot.fromRemainingSteps(planOf(se), 0);

        assertEquals(2, s.breakCount());
        boolean sawWet = false;
        boolean sawDry = false;
        for (int i = 0; i < s.breakCount(); i++) {
            if (s.breakAt(i) == CELL_A) { assertEquals(PathEdits.BROKEN_WATER, s.breakKindAt(i)); sawWet = true; }
            if (s.breakAt(i) == CELL_B) { assertEquals(PathEdits.BROKEN, s.breakKindAt(i)); sawDry = true; }
        }
        assertTrue(sawWet && sawDry);

        PathEdits edits = new PathEdits();
        edits.addSnapshot(s);
        assertEquals(PathEdits.BROKEN_WATER, edits.kindAt(CELL_A), "wet break folds as BROKEN_WATER");
        assertEquals(PathEdits.BROKEN, edits.kindAt(CELL_B), "dry break folds as plain BROKEN");
    }

    @Test
    void lavaBreakKindSurvivesTheSliceAndFoldsAsBrokenLava() {
        // The BROKEN_LAVA thread (DESIGN-fluid-flow-prediction.md §4.2/§6): a lava-verdict break is
        // not water to any consumer — different damage, different transit slow — so the splice must
        // carry each break's own fold kind verbatim. Mixed kinds in ONE plan step, behind an executed
        // prefix step the slice must drop: every surviving break keeps its kind, none bleed into a
        // neighbour's.
        StepEdits executed = step(new long[] { CELL_D }, new long[0]); // the follower already applied this
        StepEdits mixed = new StepEdits();
        mixed.load(new long[] { CELL_A, CELL_B, CELL_C },
                new byte[] { (byte) PathEdits.BROKEN_LAVA, (byte) PathEdits.BROKEN_WATER,
                        (byte) PathEdits.BROKEN }, 3,
                new long[0], 0, new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
        EditSnapshot s = EditSnapshot.fromRemainingSteps(planOf(executed, mixed), 1);

        assertEquals(3, s.breakCount(), "only the unexecuted suffix's breaks survive the slice");
        assertEquals(PathEdits.BROKEN_LAVA, kindOf(s, CELL_A), "the lava kind survives the slice");
        assertEquals(PathEdits.BROKEN_WATER, kindOf(s, CELL_B), "the water kind survives beside it");
        assertEquals(PathEdits.BROKEN, kindOf(s, CELL_C), "the dry kind survives beside both");

        PathEdits edits = new PathEdits();
        edits.addSnapshot(s);
        assertEquals(PathEdits.BROKEN_LAVA, edits.kindAt(CELL_A), "lava break folds as BROKEN_LAVA");
        assertEquals(PathEdits.BROKEN_WATER, edits.kindAt(CELL_B), "water break folds as BROKEN_WATER");
        assertEquals(PathEdits.BROKEN, edits.kindAt(CELL_C), "dry break folds as plain BROKEN");
        assertEquals(PathEdits.NONE, edits.kindAt(CELL_D), "the executed prefix's cell must not fold");
    }

    /** The snapshot's kind at {@code cell}, failing outright when the cell is absent (so a kind
     *  assertion can never pass vacuously on a missing break). */
    private static byte kindOf(EditSnapshot s, long cell) {
        for (int i = 0; i < s.breakCount(); i++) {
            if (s.breakAt(i) == cell) return s.breakKindAt(i);
        }
        throw new AssertionError("cell not in snapshot: " + cell);
    }

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

    // ---- EditSnapshot.fromSteps (the seam-seed sub-range fold, DESIGN-replan-handoff.md §4) -----------

    @Test
    void subRangeExcludesPhantomEditsPastTheSeam() {
        // latestStepWinsAcrossSteps' twin with the upper bound at the seam: a search seeded at waypoint 0
        // must see step 0's BREAK, not step 1's later PLACE — step 1 lies past the seam and will never
        // execute under the new plan (the phantom-edit exclusion, DESIGN-replan-handoff.md §4).
        BlockPathPlan plan = planOf(
                step(new long[] { CELL_A }, new long[0]),
                step(new long[0], new long[] { CELL_A }));

        EditSnapshot s = EditSnapshot.fromSteps(plan, 0, 0);

        assertEquals(1, s.breakCount(), "the in-range step's BREAK must survive");
        assertEquals(CELL_A, s.breakAt(0));
        assertEquals(0, s.placeCount(), "the past-the-seam PLACE is a phantom edit");
    }

    @Test
    void latestStepInRangeWinsAcrossTheRangeBoundary() {
        // Steps 0 (break A) and 1 (place A) both in range, step 2 (break A again) excluded: the fold
        // resolves A to the LATEST IN-RANGE edit — step 1's place — not step 2's.
        BlockPathPlan plan = planOf(
                step(new long[] { CELL_A }, new long[0]),
                step(new long[0], new long[] { CELL_A }),
                step(new long[] { CELL_A }, new long[0]));

        EditSnapshot s = EditSnapshot.fromSteps(plan, 0, 1);

        assertEquals(1, s.placeCount(), "step 1's PLACE is the latest edit IN RANGE");
        assertEquals(CELL_A, s.placeAt(0));
        assertEquals(0, s.breakCount());
    }

    @Test
    void fromStepsEmptyAndClampedRanges() {
        BlockPathPlan plan = planOf(
                step(new long[] { CELL_A }, new long[0]),
                step(new long[] { CELL_B }, new long[0]));
        assertSame(EditSnapshot.EMPTY, EditSnapshot.fromSteps(null, 0, 0));
        assertSame(EditSnapshot.EMPTY, EditSnapshot.fromSteps(plan, 1, 0), "inverted range");
        assertSame(EditSnapshot.EMPTY, EditSnapshot.fromSteps(plan, 2, 5), "fromStep past the end");
        // toStep past the end clamps to the last step — identical to fromRemainingSteps.
        EditSnapshot clamped = EditSnapshot.fromSteps(plan, 0, 99);
        assertEquals(2, clamped.breakCount(), "toStep clamps to size-1");
    }

    @Test
    void fromStepsOverTheFullSuffixByteMatchesFromRemainingSteps() {
        // fromSteps(plan, from, size()-1) IS fromRemainingSteps(plan, from) — same loop, same latest-wins
        // dedup, same entry ORDER, same per-break fold kinds. Exercised over a suffix that carries a
        // wet/dry kind mix, a place, and a cross-step duplicate cell (step 2 re-edits step 1's CELL_A),
        // behind an executed prefix step both folds must drop.
        StepEdits executed = step(new long[] { CELL_D }, new long[0]);
        StepEdits mixed = new StepEdits();
        mixed.load(new long[] { CELL_A, CELL_B },
                new byte[] { (byte) PathEdits.BROKEN_WATER, (byte) PathEdits.BROKEN }, 2,
                new long[] { CELL_C }, 1, new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
        BlockPathPlan plan = planOf(executed, mixed, step(new long[0], new long[] { CELL_A }));

        EditSnapshot full = EditSnapshot.fromRemainingSteps(plan, 1);
        EditSnapshot ranged = EditSnapshot.fromSteps(plan, 1, plan.size() - 1);

        assertEquals(full.breakCount(), ranged.breakCount(), "break counts must match");
        for (int i = 0; i < full.breakCount(); i++) {
            assertEquals(full.breakAt(i), ranged.breakAt(i), "break cell + order at " + i);
            assertEquals(full.breakKindAt(i), ranged.breakKindAt(i), "break fold kind at " + i);
        }
        assertEquals(full.placeCount(), ranged.placeCount(), "place counts must match");
        for (int i = 0; i < full.placeCount(); i++) {
            assertEquals(full.placeAt(i), ranged.placeAt(i), "place cell + order at " + i);
        }
        assertEquals(full.doorSetCount(), ranged.doorSetCount(), "door-set counts must match");
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
