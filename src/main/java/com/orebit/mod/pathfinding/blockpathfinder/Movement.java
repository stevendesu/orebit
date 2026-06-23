package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * One kind of block-tier move the bot can make (walk, jump-up, drop, …) — the Strategy the block A*
 * expands a node with (MOVEMENT-DESIGN.md §1). Given a stand position (a <b>floor cell</b>, the block
 * the bot stands on), a movement reads the geometry of the cells it touches and emits every valid
 * destination floor cell reachable by <i>this</i> move, each with its tick cost.
 *
 * <p><b>Why movements, not block flags.</b> A move spans multiple cells — an ascend reads the source
 * head-clearance cell, the destination floor, and the destination body space; a (future) parkour gap
 * reads the takeoff, the air over the gap, and the landing. No single block can answer "is this move
 * valid," so the rule lives in the movement. Each movement is a stateless singleton in {@link
 * MovementRegistry}; adding a capability is adding a class, never editing an existing one (so the
 * search's correctness for moves already shipped can't regress when a new one lands).
 *
 * <h2>The two-resolution interplay</h2>
 * A movement uses {@link MovementContext#built} (the cached 2-bit grid) only as a cheap "is this cell's
 * nav data loaded" gate, and {@link MovementContext#descriptorAt}-derived predicates ({@link
 * MovementContext#standable}, {@link MovementContext#passable}) for the <i>precise</i> per-cell checks.
 * The coarse grid finds candidates; live geometry decides whether the move actually works — which is
 * what fixes the "head-in-block" class of bug precisely at the move level rather than approximating it
 * in the grid.
 */
public interface Movement {

    /**
     * Emit every destination floor cell reachable from floor cell {@code (x,y,z)} by this movement,
     * with its tick cost, into {@code out}. Implementations must be pure (no state) and must validate
     * each candidate against {@code ctx} so the cost and validity are identical at planning and
     * execution time.
     */
    void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out);
}
