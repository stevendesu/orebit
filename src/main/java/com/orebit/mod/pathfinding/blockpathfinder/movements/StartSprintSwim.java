package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Initiate a sprint-swim — the STANDING→PRONE mode transition (the stateful water rule). It does NOT move
 * the bot: it flips the search node's mode in place at the same {@code (x,y,z)}, and is valid only where
 * vanilla lets you <i>start</i> sprint-swimming — with the feet in water and the EYES under its surface, the only
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

        // (1) Already fully submerged here → go prone IN PLACE. (Mostly the start cell when a replan fires
        //     mid-dive; an ordinary STANDING node can't be fully submerged, since walking/swimming only ever
        //     lands it on dry ground or a water surface.)
        //
        //     "Submerged" is an EYE test, not a two-cells-of-water test. Vanilla only starts a swim while
        //     isUnderWater(), i.e. with the fluid surface above getEyeY() — and a flowing block's surface is
        //     amount/9 of its cell, which can sit BELOW eye height even though the cell is unambiguously
        //     "water". This is precisely the 2026-08-20 noodle-cave wedge: a 1-wide stream ran four blocks
        //     horizontally before dropping, leaving a shallow flow in the head cell; the planner emitted this
        //     move, the physics declined the pose, and the bot stalled in the transition forever.
        //     See MovementContext#eyesSubmergedWithHeadIn for the derivation and the version sweep.
        if (ctx.built(x, y + 2, z) && ctx.water(x, y + 2, z)) {
            if (ctx.eyesSubmergedWithHeadIn(x, y + 2, z)) {
                out.accept(x, y, z, COST, MovementContext.MODE_PRONE);
            }
            // Deliberately RETURN either way. A head cell that holds water but not enough of it is not a
            // surface-tread pose, so branch (2)'s dive does not describe it; falling through would emit a
            // dive on geometry we have not reasoned about. Emitting NOTHING is the correct answer — the bot
            // must break through or route around, exactly as the owner ruled for the analogous Swim
            // step-down-into-water case (2026-08-20). The only vanilla ways to initiate from a sub-eye cell
            // are an already-underwater pose or a trapdoor crawl, and we model neither.
            return;
        }
        // (2) Treading at the SURFACE of deep water (head is open air, but the floor cell below the feet is
        //     also water) → DIVE in and go prone one cell down, where feet + head are now both water (2-deep).
        //     This is how a swimming bot actually initiates: sprint forward, submerge, prone. Without it a
        //     surface-bound STANDING bot could never reach a fully-submerged cell to start from.
        //     THE DIVE NEEDS THE SAME EYE TEST AS (1) (owner ruling 2026-08-21). Both tests above ask only
        //     whether water is PRESENT — the feet cell at the top of this method, the floor cell here — and
        //     presence is not depth. After the dive the bot's feet are (x,y,z) and its EYES sit at
        //     feet+1.62, i.e. in the cell that is the FEET cell right now: (x, y+1, z). If that cell holds a
        //     shallow flow, vanilla declines the prone pose and the bot stalls in the transition — the same
        //     wedge (1)'s gate exists to prevent, reached by the branch that actually fires.
        //
        //     Convicted on the flagship at (1837,-10,1854), twice, the SECOND time after (1) was gated: the
        //     bot trod at feet -9 (head -8 AIR, so (1) was skipped entirely), dived to feet -10, and its eyes
        //     landed in (1837,-9,1854) — raw state {@code water level=4}, i.e. amount 4 of the 6 an upright
        //     eye needs. The foot cell was {@code level=8} (FALLING water, amount 8), which is why the
        //     executor's fluid readout looked full and the geometry looked fine: the foot cell was never the
        //     question. Refusing here makes the planner break through or route around, exactly as ruled for
        //     the analogous Swim step-down-into-water case.
        if (ctx.built(x, y, z) && ctx.water(x, y, z)
                && ctx.eyesSubmergedWithHeadIn(x, y + 1, z)) {
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
        b.setSprinting(b.prone() || b.eyesUnderWater());    // see plan()'s drive — same legality gate
        SteerControl.uprightSwimServo(b, path);
    }

    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // Swim floors are non-standable water → feetYOf returns floorY+1; fromFootY/toFootY == fy+1/ty+1.
        MovePlan plan = new MovePlan();
        plan.phase("submerge")
                .drive((b, v) -> {
                    // SPRINT ONLY WHEN IT CAN DO ITS JOB (owner ruling 2026-08-30; the (909,61,1095)
                    // flagship wedge). Sprint's one purpose here is Entity.updateSwimming's
                    // `isSprinting() && isUnderWater()` — but holding the FLAG while the eyes are still
                    // dry is a state no real client can produce (LocalPlayer.shouldStopSwimSprinting
                    // drops sprint with no forward impulse off-ground), and it is actively harmful:
                    // LivingEntity.getFluidFallingAdjustedMovement skips water gravity entirely for a
                    // sprinting entity, so an eyes-dry bot holding sprint HOVERS at whatever height the
                    // dive transient left it. Convicted: parked at y=61.304 for 51k ticks, 0.035 above
                    // the eyes-under line, pose forever STANDING. Gating sprint on the eye test is the
                    // vanilla client's own rule: no sprint => normal water gravity sinks the bot the
                    // last fraction, the eyes go under, sprint comes on, and the pose flips next tick.
                    // `prone()` keeps it held once the pose HAS flipped (dropping sprint would drop the
                    // pose right back out from under the move).
                    b.setSprinting(b.prone() || b.eyesUnderWater());
                    // The UPRIGHT servo, because the bot is still upright until the pose flips — it station-
                    // keeps on the initiation column AND owns the sink (holdDepthAt at the planned depth) that
                    // pushes the head under and makes the bot isUnderWater(), which is what vanilla's
                    // Entity.updateSwimming actually requires to ENTER the prone pose.
                    //
                    // Was recenterOnTarget + holdDepth. recenterOnTarget is a pure POSITION P-controller with
                    // an exact-zero deadband: it cannot see velocity, so on water's 4.0-block coast it settles
                    // at a standing offset and answers a drift-through with a correction that becomes the next
                    // overshoot. That is the class-1 defect the ground family was converted away from; the
                    // dive-init is the last swim site that still had it. SUBMERGE_BIAS is 0.0 (identity since
                    // 2026-08-15), so the depth set-point is byte-identical to the call it replaces.
                    SteerControl.uprightSwimServo(b, v);
                })
                .done(BotSteering::prone);
        return plan;
    }

    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz, Movement next) {
        // THE VERTICAL TERM THIS NEVER HAD (2026-08-26). It used to read
        //     b.prone() && b.footX() == wx && b.footZ() == wz && Movement.teedUp(...)
        // which takes wy as a parameter and never uses it POSITIONALLY — teedUp is
        // next.entryReady(b, wx, wy, wz), and every swim move answers that TRUE unconditionally, so it
        // contributes no vertical constraint at all. The predicate was Y-blind, and any waypoint sharing
        // the column matched.
        //
        // That is fatal for a DIVE in particular, because a dive plan IS a vertical column: every waypoint
        // shares x and z, and the follower scans END->START for the furthest waypoint reached, so it takes
        // the deepest one. Measured on the long flagship, in a 52-step underwater descent:
        //
        //     cursor-advance 14→34 via StartSprintSwim @wp(1465,33,1377) skipped=19
        //
        // Nineteen waypoints skipped without the bot moving: the cursor landed at feet y=34 while the bot
        // stood at y=50. The swim servo then drove full throttle at a target 15 blocks below it, pressed
        // into a wall at vel=(0,0), and — swim moves having no failWhen envelope — nothing could declare the
        // step invalid. A permanent wedge out of one unused parameter.
        //
        // Swim.reachedSwim is the family's own arrival test (foot cell on all three axes plus the
        // horizontal containment band), which is what the other four swim moves already use. SUBMERGE_BIAS
        // matches SprintSwim's call and is identity (0.0) since 2026-08-15.
        return b.prone() && Swim.reachedSwim(b, wx, wy, wz, SteerControl.SUBMERGE_BIAS)
                && Movement.teedUp(b, wx, wy, wz, next);
    }

    /** Permissive entry: this move's servo establishes its own stance, so it accepts any pose (see
     *  {@link Movement#entryReady}). Also exactly the pre-2026-08-15 behaviour, where no entry test existed. */
    @Override
    public boolean entryReady(BotSteering b, int wx, int wy, int wz) {
        return true;
    }
}
