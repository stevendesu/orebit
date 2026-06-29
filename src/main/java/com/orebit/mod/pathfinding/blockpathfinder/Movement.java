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

    // ---- Execution (cold, tick-rate) — the follower drives these once per tick -----------------------
    // These three default methods make a movement own how the bot EXECUTES it, the way candidates() owns
    // how the search PLANS it (MOVEMENT-DESIGN.md §1). They run at tick rate (the follower), not on the A*
    // hot path, so virtual dispatch here is fine — the no-polymorphism rule is hot-path-only. The defaults
    // reproduce a plain ground walk, so a move with no special execution (Traverse/Diagonal/Descend/
    // MineDown) needs no override; only moves with special inputs (Ascend/Pillar jump, Fall homing, Swim
    // vertical control) override. All callbacks go through the MC-free {@link BotSteering} seam.

    /**
     * Whether the bot's feet block {@code (b.footX,footY,footZ)} has reached waypoint {@code (wx,wy,wz)} —
     * the follower's cursor-advance test. Default is an exact block match (waypoints and feet are both
     * blocks, so this is block-exact, no distance epsilon). Swim overrides it with a vertical tolerance
     * because a floating bot's Y bobs with buoyancy.
     */
    default boolean reached(BotSteering b, int wx, int wy, int wz) {
        return b.footX() == wx && b.footY() == wy && b.footZ() == wz;
    }

    /**
     * Whether this step's folded break/place edits ({@link com.orebit.mod.pathfinding.blockpathfinder.StepEdits})
     * should be applied <i>this</i> tick. Default {@code true} (clear/fill the cells in front before moving
     * into them). Pillar overrides it to wait until the bot is airborne, because its footing is placed in the
     * bot's own feet cell — placing it while still standing there would set a block inside the bot.
     */
    default boolean editsReadyNow(BotSteering b) {
        return true;
    }

    /**
     * Drive the bot's per-tick movement inputs toward waypoint {@code (wx,wy,wz)}. Default is the generic
     * walk: face the waypoint centre and apply full forward input; vanilla physics handles step-assist,
     * descent, and gravity. Overrides add a move's special inputs (a real jump, mid-air homing, direct
     * swim vertical velocity). The follower applies a generic stuck-jump backstop AFTER this, so a move's
     * {@code steer} need only contribute its <i>deterministic</i> inputs.
     */
    default void steer(BotSteering b, int wx, int wy, int wz) {
        b.faceHorizontally((wx + 0.5) - b.x(), (wz + 0.5) - b.z());
        b.setForward(1.0f);
    }
}
