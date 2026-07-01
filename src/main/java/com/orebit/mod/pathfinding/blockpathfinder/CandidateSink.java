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

    /**
     * Accept a destination in an explicit movement <b>mode</b> ({@link MovementContext#MODE_STANDING} or
     * {@link MovementContext#MODE_PRONE}) — the search keys nodes by {@code (x,y,z,mode)}, so this lands on a
     * distinct row from the same cell in another mode. Only the mode-TRANSITION moves use these (e.g.
     * {@code StartSprintSwim} STANDING→PRONE, {@code Surface} PRONE→STANDING); every ordinary move calls the
     * plain {@link #accept(int,int,int,float,EditScratch)} overloads, which preserve the current node's mode.
     *
     * <p>This is a {@code default} (delegating to the mode-agnostic overload) so {@link CandidateSink} keeps a
     * single abstract method and stays usable as a lambda — the real search relaxer overrides it to honour the
     * mode, while diagnostic/probe sinks (which never expand transition edges meaningfully) just ignore it.
     */
    default void accept(int x, int y, int z, float cost, EditScratch edits, int mode) {
        accept(x, y, z, cost, edits);
    }

    /** Accept a mode-transition destination that carries no break/place edits. */
    default void accept(int x, int y, int z, float cost, int mode) {
        accept(x, y, z, cost, null, mode);
    }
}
