package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * Test-only fixture builder for {@link PathEdits}. Lives in the production package so it can reach
 * {@link StepEdits}'s package-private no-arg constructor + {@code load} seam (the search's own arena uses
 * the same) — the public {@link PathEdits#add(StepEdits)} path then registers the edits exactly as a real
 * expansion does, including the AABB bookkeeping the cuboid edit-shrink relies on. Used by the cuboid
 * forward-shrink guard test, which lives in the sibling {@code cuboid} package.
 */
public final class EditFixtures {

    private EditFixtures() {}

    private static final long[] NO_CELLS = new long[0];
    private static final boolean[] NO_FLAGS = new boolean[0];

    /** A {@link PathEdits} holding exactly the given PLACED cells (packed {@code BlockPos.asLong}). */
    public static PathEdits withPlaced(long... placedCells) {
        StepEdits se = new StepEdits();
        se.load(NO_CELLS, 0, placedCells, placedCells.length, NO_CELLS, NO_FLAGS, 0);
        PathEdits edits = new PathEdits();
        edits.add(se);
        return edits;
    }

    /** A {@link PathEdits} holding exactly the given BROKEN cells (packed {@code BlockPos.asLong}). */
    public static PathEdits withBroken(long... brokenCells) {
        StepEdits se = new StepEdits();
        se.load(brokenCells, brokenCells.length, NO_CELLS, 0, NO_CELLS, NO_FLAGS, 0);
        PathEdits edits = new PathEdits();
        edits.add(se);
        return edits;
    }

    /** A single-edge {@link StepEdits} that OPENS ({@code open=true}) or CLOSES the door at each given cell
     *  (DOORS P2). Uses the same package-private {@code load} seam a real edge uses. */
    public static StepEdits doorSetStep(boolean open, long... doorCells) {
        StepEdits se = new StepEdits();
        boolean[] opens = new boolean[doorCells.length];
        java.util.Arrays.fill(opens, open);
        se.load(NO_CELLS, 0, NO_CELLS, 0, doorCells, opens, doorCells.length);
        return se;
    }
}
