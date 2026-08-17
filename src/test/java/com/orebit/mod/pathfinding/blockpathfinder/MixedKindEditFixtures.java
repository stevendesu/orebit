package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * Test-only sibling of {@link EditFixtures} for steps whose breaks carry <b>per-cell</b> fold kinds.
 * {@link EditFixtures}' break builders are deliberately uniform-kind (all-{@code BROKEN}, all-water,
 * all-lava); the {@code PathPlan.prescribedBreakKind} pins need one {@link StepEdits} that mixes kinds
 * within a single step — the shape a real {@code Traverse} produces when its two body-cell breaks get
 * different funnel verdicts (DESIGN-fluid-flow-prediction.md §5–§6) — so an implementation that returns
 * index 0's kind for every coordinate is distinguishable from one that matches the cell. Lives in the
 * production package for {@link StepEdits}'s package-private {@code load} seam, exactly as
 * {@link EditFixtures} does; kept a separate file so concurrent fixture work never collides.
 */
public final class MixedKindEditFixtures {

    private MixedKindEditFixtures() {}

    private static final long[] NO_CELLS = new long[0];
    private static final boolean[] NO_FLAGS = new boolean[0];

    /**
     * A single {@link StepEdits} breaking {@code brokenCells[i]} with fold kind {@code kinds[i]}
     * ({@link PathEdits#BROKEN} / {@link PathEdits#BROKEN_WATER} / {@link PathEdits#BROKEN_LAVA} — never
     * {@code NONE}, the empty-slot marker) and placing every {@code placedCells} cell. Arrays must be
     * parallel and same-length on the break side.
     */
    public static StepEdits mixedBreakStep(long[] brokenCells, int[] kinds, long[] placedCells) {
        if (brokenCells.length != kinds.length) {
            throw new IllegalArgumentException("brokenCells and kinds must be parallel");
        }
        byte[] k = new byte[kinds.length];
        for (int i = 0; i < kinds.length; i++) k[i] = (byte) kinds[i];
        StepEdits se = new StepEdits();
        se.load(brokenCells, k, brokenCells.length, placedCells, placedCells.length,
                NO_CELLS, NO_FLAGS, 0, ClutchModel.NONE, 0L);
        return se;
    }
}
