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

    /** A {@link PathEdits} holding exactly the given PLACED cells (packed {@code BlockPos.asLong}). */
    public static PathEdits withPlaced(long... placedCells) {
        StepEdits se = new StepEdits();
        se.load(new long[0], 0, placedCells, placedCells.length);
        PathEdits edits = new PathEdits();
        edits.add(se);
        return edits;
    }

    /** A {@link PathEdits} holding exactly the given BROKEN cells (packed {@code BlockPos.asLong}). */
    public static PathEdits withBroken(long... brokenCells) {
        StepEdits se = new StepEdits();
        se.load(brokenCells, brokenCells.length, new long[0], 0);
        PathEdits edits = new PathEdits();
        edits.add(se);
        return edits;
    }
}
