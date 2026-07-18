package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Traverse existing ladders / vines / scaffolding (MOVEMENT-DESIGN Tier 1 climb — the first consumer of
 * the {@link com.orebit.mod.worldmodel.navblock.NavBlock#isClimbable CLIMB} descriptor bit). Three rules,
 * all edit-free:
 *
 * <ul>
 *   <li><b>Climb up</b> — one cell up the climb column while the surface continues (the cell the feet move
 *       into is itself climbable). Top-outs are NOT this move's job: from the topmost climb node,
 *       {@link Ascend} emits onto the adjacent floor one up (it never checks the <i>source</i> floor), and
 *       its held-jump steer is exactly the vanilla ladder top-out input.
 *   <li><b>Climb down</b> — one cell down while the surface continues, or the final dismount step onto
 *       standable ground at the column's base.
 *   <li><b>Grab (entry)</b> — a sideways step into an adjacent climb column at feet level. This is the only
 *       way <i>into</i> a ladder column: ladder/scaffolding classify {@code SHAPE_OTHER} (non-empty,
 *       tall collision), so they are walls to {@link MovementContext#passable}/{@code standable} and every
 *       existing movement. (Vines are empty-shape and passable — the same grab rule covers both.)
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
 * along a climb is enterable iff {@link MovementContext#passableOrClimbable passable OR climbable}.
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
 * for ~8 ticks. A stalled climb (slide-back at −0.15/t while off the ground) trips the follower's
 * airborne-stall recovery, which is the intended self-heal.
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

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // upright climb — vanilla has no prone climb

        // The node's feet cell (x,y+1,z): climbable ⇒ the bot is ON the climb surface, and the two vertical
        // rules below apply. Read-once; UNBUILT reads as "not on a climb."
        int pf = ctx.packedAt(x, y + 1, z);
        boolean onClimb = pf != MovementContext.UNBUILT
                && ctx.isClimbable(ctx.descriptorOf(x, y + 1, z, pf));

        if (onClimb) {
            // Climb up: stay on the surface — the NEW feet cell (y+2) must itself be climbable (a top-out
            // onto adjacent ground is Ascend's job), and the new head (y+3) must admit the body.
            int p2 = ctx.packedAt(x, y + 2, z);
            if (p2 != MovementContext.UNBUILT
                    && ctx.isClimbable(ctx.descriptorOf(x, y + 2, z, p2))) {
                int p3 = ctx.packedAt(x, y + 3, z);
                if (p3 != MovementContext.UNBUILT
                        && ctx.passableOrClimbable(ctx.descriptorOf(x, y + 3, z, p3))) {
                    out.accept(x, y + 1, z, CLIMB_UP_COST);
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
        }

        // Grab (entry): step sideways into an adjacent climb column at feet level. The destination floor
        // (nx,y,nz) may be air — vanilla climbing holds the bot (the Swim non-solid-floor precedent) — so
        // only the column's feet + head cells are read. No caps gate: climbing is universal.
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int pn = ctx.packedAt(nx, y + 1, nz);
            if (pn == MovementContext.UNBUILT) continue;
            long feetD = ctx.descriptorOf(nx, y + 1, nz, pn);
            if (!ctx.isClimbable(feetD)) continue;
            int ph = ctx.packedAt(nx, y + 2, nz);
            if (ph == MovementContext.UNBUILT
                    || !ctx.passableOrClimbable(ctx.descriptorOf(nx, y + 2, nz, ph))) {
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
     * Hold the column and let the vanilla climbable physics do the vertical work, keyed on the MOVE's
     * intended Δy — {@code path.ty() - path.sy()}, the planned dest-floor minus source-floor — NOT the bot's
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
        if (ddy > 0.1) {
            b.setJumping(true);  // climb up
        } else if (ddy >= -0.1) {
            b.setSneak(true);    // lateral: hold height (suppress the ladder/vine slide) and ease across
        }
        // else (climb down): no jump, no sneak — vanilla slow-fall carries the descent
    }
}
