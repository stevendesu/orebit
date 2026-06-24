package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The callback a {@link Movement} pushes its valid destination cells into during node expansion. The
 * A* owns the sink; it tags each accepted edge with the movement currently expanding (so the resulting
 * {@link BlockPathPlan} carries the chosen move per step) and runs the relaxation. Keeping the move's
 * job to "emit (cell, cost)" lets the search internals (open set, g-scores, came-from) stay private to
 * {@link BlockPathfinder}.
 */
public interface CandidateSink {

    /**
     * Accept a reachable destination <b>floor cell</b> {@code (x,y,z)} with the per-step {@code cost}
     * (tick-relative; ≥ the search's minimum step cost so the heuristic stays admissible) and the {@link
     * StepEdits} the move folds in ({@code null} for an ordinary move that breaks/places nothing). The
     * search records the edits on the chosen edge so the follower can mine/place them before completing
     * the step.
     */
    void accept(int x, int y, int z, float cost, StepEdits edits);

    /** Accept an ordinary move that carries no break/place edits. */
    default void accept(int x, int y, int z, float cost) {
        accept(x, y, z, cost, null);
    }
}
