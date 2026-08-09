package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * Traverse existing ladders / vines / scaffolding (MOVEMENT-DESIGN Tier 1 climb — the first consumer of
 * the {@link com.orebit.mod.worldmodel.navblock.NavBlock#isClimbable CLIMB} descriptor bit; the gap
 * edges are NOTES-movement-physics.md §4). Seven rules, all edit-free:
 *
 * <ul>
 *   <li><b>Climb up</b> — one cell up the climb column while the surface continues (the cell the feet move
 *       into is itself climbable). Top-outs are NOT this move's job: from the topmost climb node,
 *       {@link Ascend} emits onto the adjacent floor one up (it never checks the <i>source</i> floor), and
 *       its held-jump steer is exactly the vanilla ladder top-out input.
 *   <li><b>Climb down</b> — one cell down while the surface continues, or the final dismount step onto
 *       standable ground at the column's base.
 *   <li><b>Grab (entry)</b> — a sideways step into an adjacent climb column at feet level. This is the only
 *       way <i>into</i> a ladder column sideways: ladder ({@code SHAPE_OTHER}) and scaffolding
 *       ({@code SHAPE_FULL}) are non-passable to every walking movement — though their full-height
 *       collision tops ARE standable (the classifier truth; NOTES-movement-physics.md §3). Vines are
 *       empty-shape and passable — the same grab rule covers both. Scaffolding is refused (§3.6): the
 *       lateral hold is a SNEAK, and scaffolding is sneak-exempt (the bot would sink while crossing).
 *   <li><b>Jump-grab (§3.3)</b> — from SOLID FOOTING (standable, non-climbable — owner ruling: no jump
 *       launches from climbable stances), a grounded jump lifts the feet across ONE air cell into a
 *       climbable overhead ("jump to reach the bottom of a vine or ladder"). Feet+2 is never reachable
 *       (jump apex 1.25).
 *   <li><b>Exit-top (§3.4)</b> — from a hang whose own feet cell is a FULL-faced standable climbable (the
 *       scaffold deck; a ladder's NARROW_TOP plate is excluded — the 3/16 stance is unplannable), climb
 *       out of the column's top and stand on it.
 *   <li><b>Sink-in (§3.5)</b> — from standing ATOP a solid climbable (ladder plate / scaffold deck),
 *       enter the column below. The reverse of exit-top; kills the atop-ladder-plate dead end.
 *   <li><b>Dismount (§3.7)</b> — the mirror of grab: from a HANG, ease sideways OUT of the column onto
 *       adjacent standable ground at the same feet height. Emitted only from a genuine hang (feet cell
 *       climbable AND the node's own floor not standable), because that is exactly the stance with no walk
 *       alternative — a grounded bot beside a vine reaches the same cell with a plain {@link Traverse}.
 * </ul>
 *
 * <h2>Node semantics — no new mode</h2>
 * "On a ladder" is fully derivable from geometry (the node's feet cell {@code (x,y+1,z)} is climbable), so
 * the node stays {@link MovementContext#MODE_STANDING} and the plain mode-preserving accepts are used. The
 * node floor follows {@link Swim}'s non-solid-floor convention: the cell below the feet, which may be air,
 * the climbable itself, or ground — no global support invariant exists ({@code Relaxer.relax} gates only on
 * corridor + g-improvement), and expansions from a mid-ladder node compose correctly with the existing
 * moves ({@link Traverse} exits sideways onto a standable cell at node level, {@link Ascend} tops out).
 *
 * <h2>Clearance reads — never through HEADROOM</h2>
 * {@code NavFlags.walkClear} reads a {@code SHAPE_OTHER} ladder as blocked, so the resident HEADROOM bit
 * under-claims beside ladders and {@link MovementContext#requireBodyClear} would fold a break of the ladder
 * itself. Climb therefore reads its cells directly ({@code packedAt}/{@code descriptorOf} read-once,
 * {@code UNBUILT} gates as usual) against the one predicate that unifies both climbable shapes: a body cell
 * along a climb is enterable iff {@link MovementContext#passableOrClimbable passable OR climbable} — a
 * predicate that (DESIGN-trapdoors.md §4/§6) also admits OPEN-trapdoor cells (body-passable: the wall
 * panel coexists with the climb column), so climbing through an open hatch above a ladder shaft plans
 * naturally where the ladder continues. The vanilla open-trapdoor-as-ladder-extension rule stays DEFERRED
 * (§10 — trapdoor FACING is not packed); the executor's {@code onClimbable} honours it for free.
 *
 * <h2>Edit-free by design</h2>
 * Climb folds no breaks/places (the edit-free {@code accept}): a blocked climb simply isn't emitted, and
 * the existing break-through moves already price mining a ladder/vine out of the way. v1 trusts the
 * classifier bit alone for vines (no backing-block check) — the miss mode is a failed climb → follower
 * stall recovery → replan. Chained climbs are repeated ±1 edges; a macro Y-collapse (mirroring
 * {@link Pillar}'s, gated {@code ctx.macroAxis() == AXIS_Y}) is a later follow-up, out of v1. The
 * pass-through hazard/slow surcharges are likewise not consulted: the transited cells are the climbable
 * blocks themselves (never webs/fire), and pricing a hazardous open cell above a vine is left with the
 * moves that fold body clearance.
 *
 * <h2>Execution</h2>
 * Vanilla {@code LivingEntity.onClimbable()} tests the feet block against {@code BlockTags.CLIMBABLE};
 * while on a climbable, {@code (horizontalCollision || jumping)} sets vertical velocity to {@code +0.2}
 * (climb up) and otherwise clamps the fall to {@code −0.15} — so holding the jump input climbs, and doing
 * nothing descends. No new {@link BotSteering} input and no {@code MovePlan} (nothing to break/place; the
 * motion is monotone): {@link #steer} re-centres on the column and holds jump only when the target is
 * above. The default block-exact {@link #reached} stands — at 0.117 b/t the feet occupy each waypoint cell
 * for ~8 ticks. A stalled climb (slide-back at −0.15/t while off the ground) has NO recovery machinery
 * (s52 deleted the airborne-stall recovery; the no-recovery rule): it surfaces as a visible stall, and a
 * climbable hang counts as a plan-anchor-stable state so window handoffs keep servicing mid-climb.
 */
public final class Climb implements Movement {

    /**
     * Ticks per block climbed up: {@code 20 / 2.35 ≈ 8.51} — vanilla ladder ascent is 2.35 m/s (the
     * {@code +0.2}/tick climb velocity net of gravity re-application). Sanity: dearer than a natural
     * staircase ({@link Traverse#FLAT_COST} 4.633/block) but cheaper than pillar-with-place (~10.6/block),
     * so A* takes an existing ladder over building and takes stairs over both — correct on both sides.
     */
    public static final float CLIMB_UP_COST = 20f / 2.35f;

    /**
     * Ticks per block climbed down: {@code 20 / 3.0 ≈ 6.67} — descent on a climbable is the fall clamp
     * ({@code −0.15}/tick ⇒ 3.0 m/s max), no input needed.
     */
    public static final float CLIMB_DOWN_COST = 20f / 3.0f;

    /**
     * The vanilla crouch (sneak) horizontal movement-input factor: a sneaking player moves at ~0.3× walk
     * speed (walk 4.317 m/s → sneak ~1.295 m/s). This is the input the bot must hold to translate ALONG a
     * climbable without changing height — {@code Player.isSuppressingSlidingDownLadder()} (==
     * {@code isShiftKeyDown()}) zeroes the {@code −0.15}/t climbable fall in
     * {@code LivingEntity.handleOnClimbable}, so a sneaking bot holds its height on a vine/ladder while it
     * eases sideways (verified against 1.21.11 vanilla; both conjuncts — {@code motion.y < 0} and
     * {@code this instanceof Player} — hold for the ServerPlayer-subclass bot).
     *
     * <p><b>Single source of truth</b> for the sneak factor: this constant prices {@link #GRAB_LATERAL_COST}
     * (the PLAN cost) AND is read by the follower ({@link com.orebit.mod.AllyBotEntity}) to scale the bot's
     * horizontal movement input while sneaking — the client-only slowdown vanilla's {@code LocalPlayer.aiStep}
     * applies, which the headless bot otherwise skips. With it applied, the executed lateral speed (~0.065 b/t,
     * below the ±0.15 climbable clamp) genuinely matches the ~15.44 t/block plan cost instead of coasting at
     * the clamp.
     */
    public static final float SNEAK_SPEED_FACTOR = 0.3f;

    /**
     * Ticks per block for a LATERAL grab/cling step (Δy == 0) — a genuine must-cling sideways move on a
     * climbable: entering a {@link com.orebit.mod.worldmodel.navblock.NavBlock#SHAPE_OTHER SHAPE_OTHER}
     * ladder / scaffolding WALL, or clinging to a vine hanging over air. Physically derived:
     * {@code FLAT_COST / SNEAK_SPEED_FACTOR = 4.633 / 0.3 ≈ 15.44}. To translate laterally along a climbable
     * WITHOUT ratcheting up (holding jump climbs at {@code +0.2}/t) or sliding down (the {@code −0.15}/t
     * fall clamp), the bot must SNEAK, which caps its horizontal input at ~0.3× walk (~1.295 m/s =
     * 0.065 b/t). Vanilla also clamps on-climbable horizontal motion to ±0.15 b/t, but the sneak speed
     * (0.065) is well UNDER that clamp, so sneak — not the clamp — governs the traversal time. This is far
     * dearer than a flat walk ({@link Traverse#FLAT_COST} 4.633), so A* only clings when there is genuinely
     * no walkable footing; the guard in {@link #candidates} additionally refuses the cling outright whenever
     * a plain Traverse/Descend route through the (passable) climbable already reaches standable ground.
     * (Vertical grabs — climb up/down — keep their own {@link #CLIMB_UP_COST}/{@link #CLIMB_DOWN_COST}.)
     */
    public static final float GRAB_LATERAL_COST = Traverse.FLAT_COST / SNEAK_SPEED_FACTOR;

    /**
     * §3.3 jump-grab, ticks: 3 (rise — a grounded 0.42 jump's feet cross +1.0 during tick 3:
     * 0.42 + 0.3332 + 0.2481 ≈ 1.0013) + 2 (arrest settle at the −0.15 clamp) + 3 (the established
     * jump-commit surcharge the parkour family pays) ≈ 8. Physically derived per the ruler
     * (NOTES-movement-physics.md §4).
     */
    public static final float JUMP_GRAB_COST = 3f + 2f + 3f;

    /**
     * §3.4 exit-top, ticks: one climbed cell ({@link #CLIMB_UP_COST}) + 2 to settle grounded on the deck
     * (the climb-out pop peaks only ≈ +0.154 above the cell top, then drops onto the full top face).
     */
    public static final float EXIT_TOP_COST = CLIMB_UP_COST + 2f;

    /**
     * §3.5 sink-in, ticks: one descended cell ({@link #CLIMB_DOWN_COST}) + 2 to enter/arrest (sneak
     * through a scaffold deck's vanishing top, or the recenter-off-the-plate drop on a ladder).
     */
    public static final float SINK_IN_COST = CLIMB_DOWN_COST + 2f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Does this lateral step END on solid ground — i.e. is it a §3.7 DISMOUNT rather than a §3.3 jump-grab
     * entry? Read off the waypoint's own floor cell, which is the honest discriminator: a grab entry targets
     * a climbable hanging in open air (no floor to catch it, which is precisely why it needs the jump arc),
     * while a dismount targets a ledge the planner already proved standable.
     *
     * <p>Without this the two regimes collide on the dismount's ARRIVAL tick. {@code PhaseRunner.run} drives
     * BEFORE it tests {@code isDone}, so the tick the bot lands on the ledge it is grounded, no longer
     * {@code onClimbable()}, and still inside the lateral branch — it would press jump and hop straight back
     * off the ledge it just reached, one tick before the move reported success.
     */
    private static boolean landsOnGround(BotSteering b, SteerView path) {
        return b.solidAt((int) Math.floor(path.tx()),
                (int) Math.floor(path.ty()) - 1,
                (int) Math.floor(path.tz()));
    }

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // upright climb — vanilla has no prone climb

        // The node's feet cell (x,y+1,z): climbable ⇒ the bot is ON the climb surface, and the two vertical
        // rules below apply. Read-once; UNBUILT reads as "not on a climb."
        int pf = ctx.packedAt(x, y + 1, z);
        long selfFeetD = pf == MovementContext.UNBUILT ? 0L : ctx.descriptorOf(x, y + 1, z, pf);
        boolean onClimb = pf != MovementContext.UNBUILT && ctx.isClimbable(selfFeetD);

        if (onClimb) {
            // Climb up: the climb rule is a precondition on the cell the FEET ARE IN, never on the cell they
            // ENTER (owner ruling 2026-08-01, manual proof). While onClimbable, jump drives vy=+0.2 and keeps
            // driving it every tick the feet are still inside — so the bot rises out of the column's top cell
            // into whatever passable cell sits above it. Two distinct emissions, one node:
            int p2 = ctx.packedAt(x, y + 2, z);
            if (p2 != MovementContext.UNBUILT) {
                long d2 = ctx.descriptorOf(x, y + 2, z, p2);
                if (ctx.isClimbable(d2)) {
                    // (1) CONTINUE the surface — the new feet cell is climbable too, so the stance is
                    // self-sustaining and the column simply carries on upward.
                    int p3 = ctx.packedAt(x, y + 3, z);
                    if (p3 != MovementContext.UNBUILT
                            && ctx.passableOrClimbable(ctx.descriptorOf(x, y + 3, z, p3))) {
                        out.accept(x, y + 1, z, CLIMB_UP_COST);
                    }
                } else if (ctx.passable(d2)) {
                    // (2) §3.4 TOP-OUT: rise out of the column's top cell. The resulting node's floor is the
                    // climbable itself, and its stance depends on what that climbable is:
                    //
                    //   STANDABLE top (scaffold deck) — the +0.154 climb-out pop lands the feet on the full
                    //       top face and the bot genuinely stands there, needing no held input.
                    //   NON-STANDABLE top (a vine curtain) — nothing to stand on, so the stance is the
                    //       curtain top-out: the feet settle at the boundary and vanilla re-lifts them
                    //       whenever they dip back in. HELD BY JUMP, which is exactly what
                    //       SteerControl.holdClimbableStance presses on climbableBelow(). From there an
                    //       ordinary Traverse steps onto any adjacent full-topped block at the same height —
                    //       no jump impulse anywhere in the sequence, so R1 is never violated.
                    //
                    //   NARROW standable top (a ladder's 3/16 plate) — a real, if awkward, stance. Also
                    //       admitted: NARROW_TOP is a TAKEOFF and precision-LANDING restriction, never a
                    //       standing one (owner ruling 2026-08-01).
                    //
                    // All are the SAME emission; only the follower's stance-hold differs, so this stays one
                    // branch. Both former gates were wrong:
                    //
                    //   standable — silently deleted the ONLY route off a vine curtain top once Ascend was
                    //       (correctly) gated on solidFooting in c84c4b9. That is what forced the flagship's
                    //       absurd (58,131,189) exit — place a floor at (59,131,189) and mine the leaves at
                    //       (59,132,189) — when the free move was to top out one higher and walk straight
                    //       onto those same leaves, whose top face IS the top-out feet height.
                    //   !isNarrowTop — aimed at the wrong hazard. What must never be planned is a JUMP off a
                    //       ladder plate (the alternating ladder/air/ladder ascent, geometrically valid only
                    //       when the ladders swap sides for headroom, and FACING isn't packed in the
                    //       descriptor). But that is already refused twice over and by the right test:
                    //       MovementContext.solidFooting rejects any climbable floor cell, killing Ascend /
                    //       Parkour / DiagonalParkour / WalkOff takeoffs, and §3.3's jump-grab below excludes
                    //       it again with its own !isClimbable(d0). Blocking the climb-OUT bought no safety
                    //       and cost the whole ladder-top vocabulary. NARROW_TOP keeps its real jobs — the
                    //       jump-TAKEOFF refusal inside solidFooting, the precision-LANDING refusal in
                    //       Parkour/DiagonalParkour, and Traverse's step-assist gate — all of which are
                    //       restrictions on LEAVING the stance, never on being in it.
                    int p3 = ctx.packedAt(x, y + 3, z);
                    if (p3 != MovementContext.UNBUILT
                            && ctx.passableOrClimbable(ctx.descriptorOf(x, y + 3, z, p3))) {
                        out.accept(x, y + 1, z, EXIT_TOP_COST);
                    }
                }
            }

            // Climb down: the cell the feet descend into (x,y,z) continues the surface, OR is passable with
            // standable ground under it — the dismount-at-base step.
            int p0 = ctx.packedAt(x, y, z);
            if (p0 != MovementContext.UNBUILT) {
                long d0 = ctx.descriptorOf(x, y, z, p0);
                if (ctx.isClimbable(d0)) {
                    out.accept(x, y - 1, z, CLIMB_DOWN_COST);
                } else if (ctx.passable(d0)) {
                    int pb = ctx.packedAt(x, y - 1, z);
                    if (pb != MovementContext.UNBUILT
                            && ctx.standable(ctx.descriptorOf(x, y - 1, z, pb))) {
                        out.accept(x, y - 1, z, CLIMB_DOWN_COST);
                    }
                }
            }
        } else if (pf != MovementContext.UNBUILT && ctx.passable(selfFeetD)) {
            // Grounded-stance vertical edges (the feet cell is OPEN, not climbable): §3.3 + §3.5.
            int p0 = ctx.packedAt(x, y, z);
            if (p0 != MovementContext.UNBUILT) {
                long d0 = ctx.descriptorOf(x, y, z, p0);
                if (ctx.parkourLandable(d0)) {
                    // §3.3 jump-grab: from real FULL-FACED footing, a grounded 0.42 jump lifts the feet
                    // across ONE air cell into a climbable at (y+2); feet cross +1.0 on rise tick 3 and
                    // vanilla arrests them there. The gate matches solidFooting exactly (owner ruling
                    // 2026-08-01) — standable minus NARROW_TOP. It formerly read !isClimbable(d0), which
                    // also excluded the SCAFFOLD DECK; that was wrong for the same reason it was wrong in
                    // solidFooting (the deck is solid ground, the climbable is below the feet, not in them),
                    // and it left the ladder-plate refusal resting on the wrong term. Note this is now a
                    // sibling `if`, NOT an `else if`: a scaffold deck satisfies BOTH this and §3.5 below,
                    // and must be offered both the jump-grab above and the sink-in below.
                    // Entering a ladder cell from BELOW is robust — the plate can push rising feet
                    // sideways but never catch them (unlike from-above entry, which is the refused
                    // 0.0125-block knife-edge). The passable-non-climb feet cell is load-bearing too: a
                    // grounded jump STARTED inside a climbable is truncated to the 0.2 climb by vanilla.
                    // (y+3) is jump clearance — a ceiling at y+4 still leaves the feet reaching ~+1.2.
                    int p2 = ctx.packedAt(x, y + 2, z);
                    if (p2 != MovementContext.UNBUILT
                            && ctx.isClimbable(ctx.descriptorOf(x, y + 2, z, p2))) {
                        int p3 = ctx.packedAt(x, y + 3, z);
                        if (p3 != MovementContext.UNBUILT
                                && ctx.passableOrClimbable(ctx.descriptorOf(x, y + 3, z, p3))) {
                            out.accept(x, y + 1, z, JUMP_GRAB_COST);
                        }
                    }
                }
                if (ctx.isClimbable(d0) && !ctx.passable(d0)) {
                    // The gate is "a climbable with COLLISION" (ladder plate / scaffold deck), NOT
                    // "standable": once narrow tops stopped being standable (owner ruling 2026-08-01) a
                    // ladder no longer satisfied the old term, which silently deleted the only way DOWN
                    // into a ladder column from above and turned the new exit-top node into a one-way
                    // trap. The collision test selects EXACTLY the pair the standable test used to —
                    // vines and the other empty-shape climbables are excluded because you cannot be atop
                    // them at all. Preserves the previous behaviour; it just says what it means.
                    // §3.5 sink-in: standing ON a solid climbable's top (ladder plate / scaffold deck) —
                    // enter the column below; the new feet cell is the climbable itself, a hang. The
                    // single-cell drop is deterministic: the plate the bot just stood on cannot re-catch
                    // feet already below its top face. Execution: sneak through a scaffold deck (its top
                    // shape vanishes for a descending entity); recenter off a ladder's 3/16 plate +
                    // gravity + the −0.15 arrest (see steer's Δy<0 grounded branch).
                    out.accept(x, y - 1, z, SINK_IN_COST);
                }
            }
        }

        // A genuine HANG: the feet are on the climb surface AND the node's own floor is not something the bot
        // could stand on. This is the exact stance with no walk alternative, and it is what gates §3.7 below.
        // Resolved once per node, and only for climb nodes (the && short-circuits) — a vine's own cell is not
        // standable (empty shape) and neither is a ladder's plate (NARROW_TOP), so both read as hangs; a
        // SCAFFOLD deck does not, correctly, because standing on it a plain Traverse already walks off.
        boolean hanging = onClimb && !ctx.standable(ctx.descriptorAt(x, y, z));

        // Grab (entry): step sideways into an adjacent climb column at feet level. The destination floor
        // (nx,y,nz) may be air — vanilla climbing holds the bot (the Swim non-solid-floor precedent) — so
        // only the column's feet + head cells are read. No caps gate: climbing is universal.
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int pn = ctx.packedAt(nx, y + 1, nz);
            if (pn == MovementContext.UNBUILT) continue;
            long feetD = ctx.descriptorOf(nx, y + 1, nz, pn);
            if (!ctx.isClimbable(feetD)) {
                // §3.7 DISMOUNT — the mirror of the grab below: ease sideways off the column onto the ledge.
                //
                // STATUS (2026-08-05, measured): this edge is currently DOMINATED and therefore inert. The
                // jump moves do refuse a hang (Ascend/Parkour/WalkOff gate on solidFooting, which a climbable
                // feet cell fails by R1) — but TRAVERSE DOES NOT. Traverse never gates on its SOURCE floor;
                // its startTopY falls back to 16 when the floor is not standable, so it emits the identical
                // hang -> ledge step at FLAT_COST (4.633) and A* always takes that over this cling (15.44).
                //
                // Which makes the open question a costing one, not a missing-edge one: crossing OFF a
                // climbable is not a walk. Vanilla clamps on-climbable horizontal motion to 0.15 b/t (6.7
                // t/blk floor) and, without a sneak held, sinks the bot at 0.15/t the whole way across — so
                // Traverse's grounded-walk model both under-prices the step and mis-executes it. If Traverse
                // is gated to a standable source floor, this arm becomes the honest owner of the move at the
                // real sneak cost. Kept as-is meanwhile: it is emitted, it is correct, and nothing routes
                // through it. NOTE the TOP-OUT is a different stance and must keep its Traverse — there the
                // feet are ABOVE the climbable (feet cell air, so onClimb is false) and a flat walk across at
                // that height is the ratified behaviour (CurtainTopOutTest).
                //
                // Gated hard, because this is the arm that could re-open the cling flood the guard below
                // exists to stop:
                //   hanging          — from a grounded stance the same cell is a plain Traverse. Refusing here
                //                      keeps that arbitration where it already is instead of duplicating it.
                //   passable(feetD)  — the bot's body has to fit in the cell it eases into.
                //   standable floor  — the ledge must actually catch the feet at THIS height. Deliberately not
                //                      the guard's `belowStandable`: ground one cell lower is a DROP, which is
                //                      Fall's job and not a lateral cling's, and emitting it here would land
                //                      the bot at a node whose floor is air.
                // Costed as the cling it is (GRAB_LATERAL_COST): the crossing is the same sneak-speed ease,
                // the destination just happens to hold the bot up when it arrives.
                if (!hanging || !ctx.passable(feetD)) continue;
                int pdf = ctx.packedAt(nx, y, nz);
                if (pdf == MovementContext.UNBUILT
                        || !ctx.standable(ctx.descriptorOf(nx, y, nz, pdf))) {
                    continue;
                }
                int pdh = ctx.packedAt(nx, y + 2, nz);
                // LATERAL head cell: face-aware, not the face-blind bodyPassable widening — an open trapdoor
                // panel admits only when it does not hug this crossing's entry face (DESIGN-trapdoors.md §4:
                // the per-cell blocked-face rule applies to lateral climb entries exactly as to walks; the
                // face-blind form is reserved for the VERTICAL column cells, where a cardinal panel face can
                // never be the crossed face).
                long dismountHead;
                if (pdh == MovementContext.UNBUILT) continue;
                dismountHead = ctx.descriptorOf(nx, y + 2, nz, pdh);
                if (!ctx.passable(dismountHead) && !ctx.isClimbable(dismountHead)
                        && !ctx.trapdoorEntryClear(dismountHead, MovementContext.ordinalOf(-d[0], -d[1]))) {
                    continue;
                }
                out.accept(nx, y, nz, GRAB_LATERAL_COST);
                continue;
            }
            // §3.6 scaffold guard: the lateral hold is a SNEAK, and scaffolding is exempt from the
            // climbable sneak-hold (a sneaking bot SINKS through it — the block-exact reached would never
            // fire). Allow only climbables the hold works on: passable (vine — sneak holds) or NARROW_TOP
            // (the ladder plate — sneak holds); the full solid climbable (scaffolding) is refused. One
            // extra AND on the already-loaded long.
            if (!ctx.passable(feetD) && !NavBlock.isNarrowTop(feetD)) continue;
            int ph = ctx.packedAt(nx, y + 2, nz);
            if (ph == MovementContext.UNBUILT) continue;
            // LATERAL grab head cell — face-aware like the dismount above (§4 per-cell blocked-face): an
            // open panel across this crossing's entry face refuses; parallel panels and climbables admit.
            long grabHead = ctx.descriptorOf(nx, y + 2, nz, ph);
            if (!ctx.passable(grabHead) && !ctx.isClimbable(grabHead)
                    && !ctx.trapdoorEntryClear(grabHead, MovementContext.ordinalOf(-d[0], -d[1]))) {
                continue;
            }

            // Standability guard — the fix for the "cling to a vine that hangs over walkable ground" flood.
            // A PASSABLE climbable (a vine — empty shape) is NOT a wall: a plain Traverse/Descend already
            // steps onto the standable floor beneath it, its body passing THROUGH the passable vine, so a
            // lateral cling here is REDUNDANT (and, held at the wrong Δy, ratchets the bot up the vine). Emit
            // the grab ONLY for a genuine must-cling: either the feet climbable is a SHAPE_OTHER wall
            // (ladder / scaffolding — NOT passable, so no walk enters it), or the vine has NO standable
            // footing at the destination floor (nx,y,nz) NOR one below it (nx,y-1,nz) — a true mid-air cling.
            // Both levels are checked because the walk alternative lands the bot either AT the node floor (a
            // grass cell with the vine in the body) or ONE below via Descend (grass under a full vine column).
            if (ctx.passable(feetD)) {
                int pf0 = ctx.packedAt(nx, y, nz);
                boolean floorStandable = pf0 != MovementContext.UNBUILT
                        && ctx.standable(ctx.descriptorOf(nx, y, nz, pf0));
                int pfb = ctx.packedAt(nx, y - 1, nz);
                boolean belowStandable = pfb != MovementContext.UNBUILT
                        && ctx.standable(ctx.descriptorOf(nx, y - 1, nz, pfb));
                if (floorStandable || belowStandable) continue; // walkable — Traverse/Descend own it
            }

            out.accept(nx, y, nz, GRAB_LATERAL_COST);
        }
    }

    /**
     * A climb waypoint is reached from a climb stance — including the CURTAIN TOP-OUT, which {@link
     * BotSteering#settled} deliberately does not cover.
     *
     * <p>Feet above a curtain are not grounded, not in fluid, and {@code onClimbable()} is false (the
     * climbable is UNDER the feet, not in them), so the default {@code settled()} test can never fire on a
     * top-out waypoint — the climb livelocks, bouncing across the cell boundary forever (measured
     * 2026-08-01 on the flagship at {@code (55,~140,207)}: 20+ ticks alternating {@code footY} 139/140,
     * {@code botY} drifting 139.961 → 139.892, with NO envelope failure because nothing was failing — the
     * step simply never completed).
     *
     * <p>Scoped to {@code Climb} on purpose. Putting {@code climbableBelow()} into {@code settled()} itself
     * was tried and reverted the same day: it is true for any bot whose feet cell sits above a climbable,
     * including one in BALLISTIC FLIGHT over a vine — a Parkour arc at {@code (57,113,160)},
     * {@code botY=113.905}, fired {@code reached} mid-arc, advanced the waypoint while still airborne, and
     * landed off-plan into a cascade of envelope failures. Here the waypoint is a climb cell reached by a
     * climb, so the stance is the expected outcome of the move rather than an accident of the terrain
     * beneath an unrelated arc.
     */
    /**
     * The phase-model execution plan — Climb's conversion off the bare {@code steer} path (owner request
     * 2026-08-05). It was the LAST unconverted movement, and by then that was the single largest structural
     * gap in the follower: a steer-only move gets no validity envelope, no carry arrest, and — since the gate
     * lives in {@link com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner} — <b>no implicit settle</b>.
     * Climb is the move that owns vines, and every follower failure of the 2026-08-03..05 arc lived on a
     * vine, so the one move most exposed to the problem was the one move opted out of all its protections.
     *
     * <p><b>ONE phase, NO needs, and the drive is the existing {@link #steer} verbatim.</b> Climb folds no
     * edits ({@code candidates} refuses a blocked climb rather than pricing a break), so there is nothing to
     * reconcile; and its steering is a six-regime servo convicted case by case from in-game geometry (the
     * jump-grab entry, the airborne stance hand-off, the scaffold sink-in). Re-deriving that here would risk
     * every one of those cases for no gain, so the phase delegates to it and the conversion changes NO
     * inputs. What it adds is the envelope, the arrest, and the settle gate.
     *
     * <p><b>{@code done} is {@link #reached}, not a fresh predicate.</b> Climb overrides {@code reached} to
     * admit the CURTAIN TOP-OUT — feet above a curtain are not grounded, not in fluid, and not
     * {@code onClimbable()}, so {@code settled()} can never fire there and the step would livelock. A
     * separate {@code done} would have to re-state that exception and could drift from it; calling
     * {@code reached} makes them the same test by construction.
     *
     * <p><b>The envelope is deliberately loose.</b> Climb spends almost its whole duration airborne (a hang
     * IS airborne), so — as with every other converted move — the verdict is only taken when the bot is
     * SETTLED in the grounded/fluid sense. Admitted: the from and to columns, within the step's own feet
     * band. That catches "dismounted onto the wrong cell" and "fell out of the column", which are the real
     * Climb failures, without second-guessing the mid-climb pose.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        final int bandLo = Math.min(fromFootY, toFootY);
        final int bandHi = Math.max(fromFootY, toFootY);
        MovePlan plan = new MovePlan();
        // Fell back to the start cell after leaving it. Inert on the common hang-to-hang climb (never
        // grounded); meaningful for the GROUNDED entries — a jump-grab that drops back onto its ledge.
        plan.resetWhen(b -> b.grounded()
                && b.footX() == fx && b.footY() == fromFootY && b.footZ() == fz);
        plan.failWhen(b -> {
            if (!(b.grounded() || b.inWater() || b.inLava())) {
                return false;                                   // a hang is airborne — never a verdict
            }
            if (b.footY() < bandLo || b.footY() > bandHi) {
                return true;                                    // out the top or bottom of the column
            }
            final int bx = b.footX();
            final int bz = b.footZ();
            return !((bx == fx && bz == fz) || (bx == tx && bz == tz));
        });
        plan.phase("climb")
                .arrestCarryFrom(fx, fz)
                .drive(this::steer)                             // the convicted six-regime servo, unchanged
                .done(b -> reached(b, tx, toFootY, tz));        // == reached, incl. the curtain top-out
        return plan;
    }

    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        return (b.settled() || b.climbableBelow()) && atWaypoint(b, wx, wy, wz);
    }

    /**
     * Hold the column and let the vanilla climbable physics do the vertical work, keyed on the MOVE's
     * intended Δy — {@code path.ty() - path.sy()} in the cursor's UNIFORM feet-cell frame (both cells feet,
     * so the delta is the planned climb direction; the follower guarantees the step-0 start is lifted into
     * that frame — a mixed floor/feet feed once read a first-step climb-down as lateral and sneak-pinned
     * the bot on a lip at the head of a vine descent) — NOT the bot's
     * current (possibly sagging) height. Keying on {@code b.y()} was the vine-flood bug: on a non-solid vine
     * "floor" the bot sags below the feet-target {@code ty}, so {@code ty > b.y()} held jump on a LATERAL
     * move and ratcheted the bot UP the vine ({@code +0.2}/t) instead of easing across it. The three cases:
     * <ul>
     *   <li><b>Δy &gt; 0 (climb up)</b> — hold jump: vanilla climbs at {@code +0.2}/t while jumping on a
     *       climbable.</li>
     *   <li><b>Δy == 0 (lateral grab/cling)</b> — hold SNEAK: {@code isSuppressingSlidingDownLadder()} zeroes
     *       the {@code −0.15}/t slide so the bot holds its height, and re-centre eases it sideways along the
     *       surface. No jump (jumping would climb).</li>
     *   <li><b>Δy &lt; 0 (climb down)</b> — no input: {@code onClimbable} clamps the descent to {@code −0.15}/t
     *       and gravity does the rest (a held sneak here would stop the intended descent).</li>
     * </ul>
     * Re-centre first (a climb/cling segment is near-degenerate — face the column, ease forward to ~0 once
     * centred, keeping the bot pressed onto the surface).
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.recenterOnTarget(b, path);
        double ddy = path.ty() - path.sy(); // the MOVE's planned Δy (feet targets), not the sagging pose
        if (!b.grounded()) {
            // AIRBORNE — hand the whole stance to the per-tick servo, which classifies the step's vertical
            // intent (rise / hold / descend) and, crucially, TERMINATES each one against the bot's live
            // height: a descend holds nothing only until the target is reached, then the hold arrests it.
            //
            // This branch used to be reachable only for ddy >= -0.1; a climb-DOWN fell through to the final
            // `else` and pressed NOTHING, on the assumption that "vanilla slow-fall carries the descent". It
            // does carry it — at the -0.15/t climbable clamp — but nothing ever stops it. Measured 2026-08-02
            // on the flagship: a Climb down the vine at (61,169,253) rode the clamp past its own target, out
            // the bottom of the column, and 17 blocks into free fall to y=152. The stance servo was never
            // even called (its call counter did not advance for the whole descent), so every per-tick rule
            // built for this case was dead code on the one path that needed it.
            SteerControl.holdClimbableStance(b, path);
        } else if (ddy > 0.1) {
            // Climb up — and, unchanged, the §3.3/§3.4 ascents: a GROUNDED bot holding jump fires the
            // real 0.42 jump (the jump-grab), an on-climbable bot gets the 0.2 climb (the exit-top run);
            // vanilla picks the right physics off the same held input.
            b.setJumping(true);
        } else if (ddy >= -0.1) {
            // Lateral — three regimes, all keyed on state the steer already reads (latvine card
            // convictions, 2026-07-31):
            //  - HANGING: sneak. It suppresses the −0.15/t slide; recenter eases the bot across.
            //  - GROUNDED on a ledge (feet cell NOT climbable): JUMP-grab entry. Sneak here arms the
            //    vanilla edge-guard, which forbids exactly the walk-off the transfer needs (measured:
            //    frozen at the lip forever, box edge 0.001 past the ledge, input pressing, spd 0.0000) —
            //    and a plain un-sneaked walk-off exits a feet-level vine row out its BOTTOM on the first
            //    airborne tick (feet enter the cell at its exact lower edge; measured: fell). The jump
            //    arc is the one entry that works: the apex clears the row, the falling re-entry passes
            //    the vine cell at |vy| ≲ 0.3 with sneak already engaged (airborne → the branch above),
            //    so the slide suppression arrests on the first in-cell tick — the vanilla player idiom.
            //  - GROUNDED on/in the climbable itself (a ladder-plate lateral): plain walk. No sneak (the
            //    same edge-guard), no jump (an in-climbable jump is a pointless truncated 0.2 hop);
            //    the plates are contiguous and collision carries the crossing.
            // (The HANGING / TOPPED-OUT case is handled by the airborne branch at the top, which routes every
            // vertical intent through the stance servo.) Only the two GROUNDED regimes remain here.
            if (!b.onClimbable() && !landsOnGround(b, path)) {
                b.setJumping(true);
            }
        } else if (b.grounded() && !b.onClimbable() && b.scaffoldingBelow()) {
            // §3.5 sink-in through a scaffold DECK: a held sneak removes its stand-on-top shape and the
            // bot descends at the −0.15 clamp. Deliberately scaffolding-ONLY: on a ladder plate a sneak
            // would edge-guard-pin the bot ON the plate — there the recenter above walks the bot off the
            // 3/16 strip toward the cell centre and gravity + the climbable arrest do the rest.
            b.setSneak(true);
        }
        // else (climb down / ladder sink-in): no jump, no sneak — vanilla slow-fall carries the descent
    }
}
