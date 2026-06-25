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
     * (tick-relative; ≥ the search's minimum step cost so the heuristic stays admissible) and the live
     * {@link EditScratch} holding the break/place edits the move folded in ({@code null} for an ordinary
     * move that breaks/places nothing).
     *
     * <p><b>The sink snapshots, and only if it keeps the candidate.</b> The movement passes its reused
     * accumulator, not an immutable {@link StepEdits} — the sink calls {@link EditScratch#snapshot()}
     * itself, <i>after</i> the relaxation gate, so a non-improving candidate (the bulk on build-heavy
     * terrain: every Pillar/MineDown re-reaching an already-cheaper cell) is rejected with <b>zero
     * allocation</b>. The scratch is read synchronously before the movement resets it for its next
     * candidate, so the snapshot is safe.
     */
    void accept(int x, int y, int z, float cost, EditScratch edits);

    /** Accept an ordinary move that carries no break/place edits. */
    default void accept(int x, int y, int z, float cost) {
        accept(x, y, z, cost, null);
    }
}
