package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Initiate a sprint-swim — the STANDING→PRONE mode transition (the stateful water rule). It does NOT move
 * the bot: it flips the search node's mode in place at the same {@code (x,y,z)}, and is valid only where
 * vanilla lets you <i>start</i> sprint-swimming — in <b>2-deep</b> water (feet AND head submerged), the only
 * place the prone {@code Pose.SWIMMING} can be entered. Once {@link MovementContext#MODE_PRONE PRONE},
 * {@link SprintSwim} carries the bot on through 1-deep water and 1-tall gaps (the pose is retained) — exactly
 * the move-state continuation a position-only search could never express.
 *
 * <p>Because mode is part of the node key, this same-cell edge lands on a DISTINCT row (the PRONE one), so it
 * is a real search edge, not a zero-progress self-loop. The small {@link #COST} (the time to go prone) keeps
 * the search from toggling pose gratuitously; any non-negative cost keeps the position-only heuristic
 * admissible.
 */
public final class StartSprintSwim implements Movement {

    /** Ticks to drop into the prone sprint-swim pose — small, just enough to discourage idle toggling. */
    public static final float COST = 2f;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // already prone (or other) — nothing to start
        if (!ctx.built(x, y + 1, z) || !ctx.water(x, y + 1, z)) return; // must be in water (feet wet) to swim

        // (1) Already fully submerged here (feet AND head water = 2-deep) → go prone IN PLACE. (Mostly the
        //     start cell when a replan fires mid-dive; an ordinary STANDING node can't be fully submerged,
        //     since walking/swimming only ever lands it on dry ground or a water surface.)
        if (ctx.built(x, y + 2, z) && ctx.water(x, y + 2, z)) {
            out.accept(x, y, z, COST, MovementContext.MODE_PRONE);
            return;
        }
        // (2) Treading at the SURFACE of deep water (head is open air, but the floor cell below the feet is
        //     also water) → DIVE in and go prone one cell down, where feet + head are now both water (2-deep).
        //     This is how a swimming bot actually initiates: sprint forward, submerge, prone. Without it a
        //     surface-bound STANDING bot could never reach a fully-submerged cell to start from.
        if (ctx.built(x, y, z) && ctx.water(x, y, z)) {
            out.accept(x, y - 1, z, COST, MovementContext.MODE_PRONE);
        }
    }

    /**
     * Initiate by diving + sprinting: look toward the (often lower) target and sprint, so vanilla submerges
     * the bot and adopts the prone {@code Pose.SWIMMING} — the same drive as {@link SprintSwim}. The
     * {@link SteerControl#holdDepth depth autopilot} rides {@link SteerControl#SUBMERGE_BIAS} under the
     * planned depth (the sink half IS the dive — the bot must submerge to enter the prone pose instead of
     * floating at the surface where it can't).
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.swimTowards(b, path);
        b.setSprinting(true);
        SteerControl.holdDepth(b, path, SteerControl.SUBMERGE_BIAS);
    }
}
