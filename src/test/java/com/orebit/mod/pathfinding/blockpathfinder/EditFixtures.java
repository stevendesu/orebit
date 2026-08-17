package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * Test-only fixture builder for {@link PathEdits}. Lives in the production package so it can reach
 * {@link StepEdits}'s package-private no-arg constructor + {@code load} seam (the search's own arena uses
 * the same) — the public {@link PathEdits#add(StepEdits)} path then registers the edits exactly as a real
 * expansion does, including the AABB bookkeeping the cuboid edit-shrink relies on. Used by the cuboid
 * forward-shrink guard test, which lives in the sibling {@code cuboid} package.
 *
 * <p>Break fixtures carry an explicit per-break {@link PathEdits} kind ({@code BROKEN} /
 * {@code BROKEN_WATER} / {@code BROKEN_LAVA} — DESIGN-fluid-flow-prediction.md §6), matching the
 * {@code byte[] breakKind} channel {@link StepEdits#load} threads; {@link PathEdits#add} stores the kind
 * verbatim, so a fixture must never pass {@code 0} ({@code NONE} is the table's empty-slot marker).
 */
public final class EditFixtures {

    private EditFixtures() {}

    private static final long[] NO_CELLS = new long[0];
    private static final byte[] NO_KINDS = new byte[0];
    private static final boolean[] NO_FLAGS = new boolean[0];

    /** {@code n} copies of the given break {@code kind} — the parallel kind array a fixture loads. */
    private static byte[] kinds(int n, int kind) {
        byte[] k = new byte[n];
        java.util.Arrays.fill(k, (byte) kind);
        return k;
    }

    /** A {@link PathEdits} holding exactly the given PLACED cells (packed {@code BlockPos.asLong}). */
    public static PathEdits withPlaced(long... placedCells) {
        StepEdits se = new StepEdits();
        se.load(NO_CELLS, NO_KINDS, 0, placedCells, placedCells.length, NO_CELLS, NO_FLAGS, 0,
                ClutchModel.NONE, 0L);
        PathEdits edits = new PathEdits();
        edits.add(se);
        return edits;
    }

    /** A {@link PathEdits} holding exactly the given (dry) BROKEN cells (packed {@code BlockPos.asLong}). */
    public static PathEdits withBroken(long... brokenCells) {
        PathEdits edits = new PathEdits();
        edits.add(step(brokenCells, NO_CELLS));
        return edits;
    }

    /** A {@link PathEdits} holding exactly the given BROKEN_WATER cells — breaks the fold funnel decided
     *  will flood with water (packed {@code BlockPos.asLong}). */
    public static PathEdits withBrokenWater(long... brokenCells) {
        PathEdits edits = new PathEdits();
        edits.add(wetBreakStep(brokenCells));
        return edits;
    }

    /** A {@link PathEdits} holding exactly the given BROKEN_LAVA cells — breaks the fold funnel decided
     *  will flood with lava (DESIGN-fluid-flow-prediction.md §4.2; packed {@code BlockPos.asLong}). */
    public static PathEdits withBrokenLava(long... brokenCells) {
        PathEdits edits = new PathEdits();
        edits.add(lavaBreakStep(brokenCells));
        return edits;
    }

    /** A single {@link StepEdits} whose breaks all carry the WATER flood verdict — folds as
     *  {@link PathEdits#BROKEN_WATER} through {@link PathEdits#add}. */
    public static StepEdits wetBreakStep(long... brokenCells) {
        StepEdits se = new StepEdits();
        se.load(brokenCells, kinds(brokenCells.length, PathEdits.BROKEN_WATER), brokenCells.length,
                NO_CELLS, 0, NO_CELLS, NO_FLAGS, 0, ClutchModel.NONE, 0L);
        return se;
    }

    /** A single {@link StepEdits} whose breaks all carry the LAVA flood verdict — folds as
     *  {@link PathEdits#BROKEN_LAVA} through {@link PathEdits#add}. */
    public static StepEdits lavaBreakStep(long... brokenCells) {
        StepEdits se = new StepEdits();
        se.load(brokenCells, kinds(brokenCells.length, PathEdits.BROKEN_LAVA), brokenCells.length,
                NO_CELLS, 0, NO_CELLS, NO_FLAGS, 0, ClutchModel.NONE, 0L);
        return se;
    }

    /** A single {@link StepEdits} holding the given (dry) BROKEN and PLACED cells — the per-step form the
     *  own-edit acknowledgement reads back off a {@link BlockPathPlan} ({@code PathPlanOwnEditTest}). */
    public static StepEdits step(long[] brokenCells, long[] placedCells) {
        StepEdits se = new StepEdits();
        se.load(brokenCells, kinds(brokenCells.length, PathEdits.BROKEN), brokenCells.length,
                placedCells, placedCells.length, NO_CELLS, NO_FLAGS, 0, ClutchModel.NONE, 0L);
        return se;
    }

    /** A single-edge {@link StepEdits} that OPENS ({@code open=true}) or CLOSES the door at each given cell
     *  (DOORS P2). Uses the same package-private {@code load} seam a real edge uses. */
    public static StepEdits doorSetStep(boolean open, long... doorCells) {
        StepEdits se = new StepEdits();
        boolean[] opens = new boolean[doorCells.length];
        java.util.Arrays.fill(opens, open);
        se.load(NO_CELLS, NO_KINDS, 0, NO_CELLS, 0, doorCells, opens, doorCells.length, ClutchModel.NONE, 0L);
        return se;
    }
}
