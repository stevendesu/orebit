package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Step down exactly one block to a cardinal-adjacent floor cell (MOVEMENT-DESIGN.md §2, Tier 1) — the
 * gentle counterpart to {@link Ascend}. The bot walks off the edge into the neighbour column and drops
 * a single block; no jump, always safe. Deeper drops are {@link Fall}'s job.
 *
 * <p>The step-off transit is the three cells {@code (nx, y..y+2, nz)} — head clearance stepping off, the
 * transit, and the new head — which are exactly the destination floor's body column. So the dest's
 * {@code JUMP}-level HEADROOM bit proves the transit clear with ZERO additional reads (corrected
 * 2026-08-11 — "in a single read" over-stated it: the flags ride the one {@code packedAt} slot already
 * resolved for the dest-floor standability test); where it can't (near a
 * section face, or a block in the way), the cells are read and folded into a break-set under the
 * fold-sited {@code RISKS_GRAVITY} gate — read at each cell actually broken, so the {@code y+2} head
 * break is gated now too (it never was under the old floor-framed bit).
 *
 * <p><b>Place modifier (MOVEMENT-DESIGN §1, decision 1).</b> When there's no footing one block down, a
 * throwaway floor is <i>placed</i> against the wall to descend onto (the counterpart to {@link Ascend}'s
 * staircase-up). Repeated Descend+place builds a staircase down a sheer drop the bot can't safely
 * {@link Fall} — completing controlled 3D descent through the existing kinds.
 *
 * <p><b>Trapdoors (DESIGN-trapdoors.md §5–§6).</b> The dest floor rides {@code requireFloorOrToggle}: a
 * toggleable OPEN trapdoor one down closes into a standable hatch (a 1+13/16 drop onto the BOTTOM-half
 * plate, an exact 1 onto a flush TOP half — both within the gentle step-down, no rise gate needed), and
 * every other cell behaves bit-identically to the historical {@code requireFloor}. The two lower transit
 * cells were already cleared face-aware ({@code requireAirToward}) and inherit the trapdoor arms from
 * that primitive: an open panel parallel to travel passes free, one across the crossed face folds its
 * SET. The step-off head cell {@code y+2} and the transit cell {@code y+1} stay strict (no §4 ceiling
 * admit): both are crossed at the START level mid-step, where a top-band plate genuinely bisects.
 */
public final class Descend implements Movement {

    /**
     * Base cost, in <b>ticks</b> = one walk step ({@link Traverse#FLAT_COST}): a flat step plus a free
     * one-block drop (gravity is "free" time the bot would spend walking anyway), so descending existing
     * terrain costs no more than a Traverse — matching Baritone's {@code MovementDescend} traversal term.
     * A folded place/break (building/digging a step where there's no terrain) adds its own real ticks.
     */
    public static final float COST = Traverse.FLAT_COST;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground step-down — only while upright
        for (int[] d : CARDINALS) {
            // §2b door EXIT (shared with Traverse / Ascend): if the bot STANDS in an intact door whose panel
            // blocks THIS horizontal travel edge, a step-down can't leave that way either. EXIT_CLEAR off any
            // door; EXIT_BLOCKED (iron / flag-off) skips the direction; EXIT_TOGGLE folds a door SET (below).
            int exitDoor = ctx.exitDoorDecision(x, y, z, d[0], d[1]);
            if (exitDoor == MovementContext.EXIT_BLOCKED) continue;
            boolean exitDoorToggle = exitDoor == MovementContext.EXIT_TOGGLE;
            int nx = x + d[0];
            int nz = z + d[1];
            int dy = y - 1; // destination floor one below

            // Destination floor (nx,dy,nz) is read both standable and flags — resolve its slot once.
            int packed = ctx.packedAt(nx, dy, nz);
            if (packed == MovementContext.UNBUILT) continue;

            long dstDesc = ctx.descriptorOf(nx, dy, nz, packed);
            boolean dstStandable = ctx.standable(dstDesc);
            int flags = MovementContext.flagsOf(packed);
            EditScratch e = ctx.edits().reset();
            // §2b: fold the exit-door toggle onto this arm when leaving through a blocked (toggleable) feet door.
            if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z, d[0], d[1]);
            // Footing: step onto the block below, CLOSE an open hatch into one (§5 requireFloorOrToggle —
            // the toggle arm only), or BUILD A STEP DOWN (the historical requireFloor place arm). The
            // TOGGLED descriptor feeds the cost reads below (a closed hatch is never slow nor damaging);
            // the PLACE arm deliberately keeps pricing the PRE-place grid descriptor (dstDesc — the Ascend
            // parity rule: the historical cost read, so e.g. a step-down-place into a FIRE cell still
            // charges its floor hazard and non-trapdoor searches are byte-identical to the float).
            long floorDesc = dstDesc;
            if (!dstStandable) {
                if (ctx.trapdoorSetFloors(dstDesc)) {
                    floorDesc = e.requireFloorOrToggle(nx, dy, nz);
                } else {
                    e.requireFloor(nx, dy, nz);
                }
            }
            // The step-off transit (nx, y..y+2, nz) is the dest floor's body column; clear it through the
            // dest's JUMP-level HEADROOM, else read/break the three cells (each under its own fold-sited
            // RISKS_GRAVITY gate). A door in
            // the dest column is a LOWERED doorway (a door standing on the step the bot drops onto): it occupies
            // the two body cells directly above the dest floor — (nx,y,nz) lower + (nx,y+1,nz) upper — which are
            // cleared DOOR-AWARE (walked past free / opened rather than mined, as Traverse handles a flat one).
            // The step-off head cell (nx,y+2,nz) sits ABOVE any such doorway (a door never reaches it), so it
            // stays a plain requireAir. A door reads non-passable (SHAPE_OTHER), so HEADROOM never proves clear
            // through one — this cold path always runs when a door is present; a door-free descend is unchanged.
            if (!ctx.headroomProves(flags, nx, dy, nz, MovementContext.HEADROOM_JUMP)) {
                int entryEdge = MovementContext.ordinalOf(-d[0], -d[1]); // edge of the dest column the step enters
                e.requireAir(nx, y + 2, nz);                  // head clearance stepping off (above any doorway)
                e.requireAirToward(nx, y + 1, nz, entryEdge); // transit feet / new head — dest door upper half
                e.requireAirToward(nx, y, nz, entryEdge);     // new feet — dest door lower half
            }
            if (e.valid()) {
                // Slow-FLOOR surcharge on the landing (same rule as Traverse/Diagonal; a PLACED step-down
                // floor reads as the conjured cube, never slow) plus the pass-through hazard/through-slow
                // surcharge for the landing body cells (nx, y-1's body = y, y+1 — the transit), zero-read
                // when the dest flag bits are clear; the edit-folding form breaks through a bush/web where
                // that's cheaper. The step-off head cell (y+2) is clearance-only.
                float cost = (ctx.isSlow(floorDesc) ? COST * Traverse.SLOW_COST_FACTOR : COST)
                        + ctx.floorHazardCost(floorDesc)
                        + ctx.bodyTransitCost(e, flags, nx, dy, nz);
                out.accept(nx, dy, nz, cost + e.extraCost(), e);
            }
        }
    }

    /**
     * The phase-model execution plan (Stage 2 — the reactive-reconcile path that replaces {@code steer} +
     * one-shot edits). Descend is <b>CLEAR &rarr; STEP</b>: establish ALL of the step-off geometry up front —
     * break the three transit cells over the destination column and build the step-down floor — then walk off
     * the edge and let gravity supply the one-block drop.
     *
     * <p><b>Geometry (plan coords).</b> {@code from} floor {@code (fx,fy,fz)} &rarr; {@code to} floor
     * {@code (tx,ty,tz)} with {@code ty == fy-1} and {@code (tx,tz)} a cardinal neighbour of {@code (fx,fz)}.
     * The bot starts standing on {@code (fx,fy,fz)} (feet block {@code (fx,fy+1,fz)}) and ends standing on
     * {@code (tx,fy-1,tz)} (feet block {@code (tx,fy,tz)}), one block lower and one over. The step-off column is
     * {@code (tx, fy..fy+2, tz)} — new feet, new head, and the step-off head-clearance — and the step-down
     * floor is {@code (tx,fy-1,tz)}.
     *
     * <p><b>Why all geometry in CLEAR, none in STEP.</b> Unlike {@link Pillar}, Descend has no kinematic
     * ordering constraint: the placed floor cell {@code (tx,fy-1,tz)} is never occupied by the bot (it lands
     * <i>on</i> it, one over), so there is no "place only after airborne" gate. Establishing everything in the
     * prep phase also walls the bot in while the transit column is still solid — it physically cannot walk off
     * before the floor exists, and because the runner issues the footing place the same tick the last transit
     * cell clears (before any drive), the floor is always down before the bot can step into the cleared column.
     * Keeping STEP need-free makes it pure locomotion.
     *
     * <p><b>CLEAR's drive is a full drive tick</b> (owner ruling 2026-08-20 — see the phase body). It runs
     * exactly once, on the tick the geometry finally holds, and it writes the same input set STEP writes:
     * the column-gated climbable hold, the stance hold, and {@code arriveOnTarget}. It used to write only
     * the column-gated hold, which for a grounded bot is no write at all — and an unwritten yaw is the
     * PREVIOUS tick's yaw, which is how the flagship-r8 {@code (340,69,481)} wedge re-fired a stale
     * north-facing thrust and walked the bot out of its own from-column.
     *
     * <p><b>Coverage.</b> The three {@code Need.AIR} cells cover {@code candidates()}'s three {@code requireAir}
     * breaks (folded only in the {@code !headroomProves} case), and the {@code Need.FOOTING} covers its
     * {@code requireFloor} place (folded only when the dest isn't already standable). Both are declared
     * unconditionally — the runner no-ops a {@code Need.AIR} whose cell is already air (the {@code headroomProves}
     * case) and a {@code Need.FOOTING} whose cell is already solid (real terrain), so the superset is exactly
     * correct. The two {@code bodyTransitCost} break-through cells are a subset of the transit {@code Need.AIR}
     * cells, so any punch-through the search priced is covered.
     *
     * <p><b>Regression guard (armed).</b> {@code resetWhen} fires only once the bot has physically LEFT the
     * start cell and then finds itself grounded back on it — a genuine balk (knocked back mid-step). The
     * {@code left[0]} arm (set in STEP's drive after departure, cleared on re-entry to CLEAR) avoids the Parkour
     * aliasing trap: the first STEP tick, still grounded at start, must not satisfy a bare "grounded at start"
     * guard and bounce {@code STEP&rarr;CLEAR} forever. An overshoot that lands off the dest floor is not a reset
     * case — that is the follower's grounded-stall recovery / replan arm (as with {@link Fall}/{@link Parkour}).
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        final boolean[] left = new boolean[1]; // reset-guard arm — set once the bot leaves the start floor
        MovePlan plan = new MovePlan();
        // Physically back on the START floor AFTER having left it → re-establish geometry. Armed by STEP and
        // disarmed on (re)entry to CLEAR, so it can't alias the first STEP tick (bot still grounded at start).
        plan.resetWhen(b -> left[0]
                && b.grounded() && atWaypoint(b, fx, fromFootY, fz));
        // Validity envelope (PATHOLOGY P1 family — the Parkour/Ascend failWhen precedent): settled
        // (grounded, or bodily in fluid) at a foot cell outside the step's own cells is off-plan —
        // done/resetWhen can never fire there and re-attempting latches. Allowed: the from stand
        // (fx, fy+1, fz) and the destination COLUMN's feet band [fy .. fy+1] — fy is the destination
        // stand (a shallow-water landing is in-fluid there, still inside), and fy+1 is the LIP TRANSIT:
        // stepping off the edge, the bot's centre crosses into the destination column while its box is
        // still grounded on the from-block's lip (foot cell (tx, fy+1, tz)) — a legitimate mid-step
        // state, NOT displacement (the longrun-8 first-hold false positive). The drop itself is
        // airborne-exempt.
        // Allowed band = the two REAL feet heights (topY-aware): the destination stand (toFootY) up through
        // the from-stand / lip transit (fromFootY). For a full block this is [fy, fy+1] — unchanged.
        final int bandLo = Math.min(fromFootY, toFootY);
        final int bandHi = Math.max(fromFootY, toFootY);
        plan.failWhen(b -> (b.grounded() || b.inWater() || b.inLava())
                && !(inWaypointCell(b, fx, fromFootY, fz))
                && !(b.footX() == tx && b.footZ() == tz
                        && b.footY() >= bandLo && b.footY() <= bandHi));
        // CLEAR: break the step-off transit column and build the step-down floor. The runner mines one AIR cell
        // per tick (holding, recentring) and places the FOOTING once the AIR cells are clear; while the transit
        // is still solid the bot is walled in, so it cannot walk off before the floor exists. The transit column
        // spans the bot's body from the new feet (toFootY) up through the step-off head (fromFootY+1) — a
        // topY-aware span (a partial destination makes a deeper foot-drop than the full-block fy..fy+2 run).
        // NO explicit SETTLE phase: the runner enforces the start band implicitly for EVERY move
        // (PhaseRunner.settling / SteerControl.inRestingPose). A per-move copy existed here first and was
        // removed the same day it was written — two gates with slightly different predicates is precisely
        // the "one rule applied inconsistently" that cost five debugging rounds on the canopy vine, and
        // this one already disagreed with the shared definition on fluid (a bobbing bot passes
        // inRestingPose but would have failed this phase's settled()-and-in-band test, deadlocking a
        // water descend).
        MovePlan.Phase clear = plan.phase("clear");
        for (int cy = toFootY; cy <= fromFootY + 1; cy++) {
            clear.need(MovePlan.Need.AIR, tx, cy, tz);         // break: step-off transit column (new feet … step-off head)
        }
        clear.need(MovePlan.Need.FOOTING, tx, ty, tz)          // place: step-down floor (no-op on real terrain)
                .drive((b, v) -> {
                    left[0] = false;                           // disarm on (re)entry; advances same tick
                    // HOLD THE STANCE on the hand-off tick (owner ruling 2026-08-04, the (55,173,256) vine
                    // drop-off). While a need is unmet the RUNNER holds the bot (stationKeep -> sneak on a
                    // climbable). The tick the geometry finally holds, the runner stops holding and calls
                    // THIS drive instead. On a hang an un-held tick is fatal: one un-sneaked tick is a 0.15
                    // slide, enough to carry the feet out of the single vine cell supporting them, and once
                    // off the climbable nothing can arrest the fall. Measured exactly — sneak held at
                    // botY=173.043 while the cobble was placed, released on the hand-off tick, feet left
                    // cell 173 at 172.965, and the bot free-fell 173 -> 171.4 into the two-tall gap it had
                    // just walled off with its own placed block.
                    //
                    // The hold is column-gated (holdUntilOverTargetColumn): from a hang, letting go before
                    // the bot is over the DESTINATION drops it down its current column, which on a curtain
                    // is often one cell tall — it exits the climbable and free-falls. Once over the target
                    // this is a no-op and gravity does the one-block drop as always.
                    SteerControl.holdUntilOverTargetColumn(b, v);
                    // AND THEN DRIVE — every path, every tick (owner ruling 2026-08-20, the (340,69,481)
                    // creep-wedge conviction, flagship r8). This drive used to STOP at the line above, and
                    // for a GROUNDED bot that call writes nothing at all: holdUntilOverTargetColumn returns
                    // immediately off a climbable (tag hold:overcol:dead) and the whole tick's observable
                    // effect was clearing a boolean. The old comment called that "invisible on solid
                    // ground". It is not. zza is reset at the top of AllyBotEntity's tick, but YAW is a
                    // persistent entity property, so a drive that writes no facing INHERITS the previous
                    // tick's heading — and on the conviction the previous tick was the retired ground-servo
                    // pirouette, a half-throttle thrust yawed −178.9° (due north) on a bot whose plan went
                    // east. This CLEAR tick re-fired that stale north thrust, the bot crossed back out of
                    // cell z=481, and the Descend validity envelope fail->HOLDed it permanently at
                    // (340,69,480). The INVARIANT this restores: the CLEAR tick never leaves a stale
                    // forward or a stale yaw standing.
                    //
                    // Driving toward the TARGET here is correct, not premature: PhaseRunner establishes a
                    // phase's needs BEFORE its drive and holds (stationKeep on the bot's OWN column) while
                    // any is unmet, so this drive only ever runs on the tick the step-off transit is
                    // already broken and the step-down floor already placed. CLEAR then advances to STEP
                    // the same tick (advanceWhen -> true), so this is a ONE-TICK handoff into the identical
                    // chain STEP runs on every following tick — same holds, same servo, one tick earlier.
                    // What is deliberately NOT mirrored is STEP's arrestCarryFrom gate: the carry arrest is
                    // STEP's own declarative phase gate (the runner runs it before STEP's drive), and
                    // arriveOnTarget is a projected-stop servo that brakes rather than a full-throttle push.
                    //
                    // No conviction in this class is resurrected. The (55,173,256) hang keeps its
                    // column-gated sneak (above, unchanged and still first). The 2026-07-31 vine-bounce
                    // ruling is untouched: arriveOnTarget short-circuits to recenterOn — deadband and all —
                    // whenever the bot is on a climbable. The (58,133,189) "hold on your OWN column, never
                    // the target" wedge is a HOLD-path ruling and cannot be re-entered from here, because
                    // the runner's hold owns every tick where a need is unmet and this drive owns none of
                    // them.
                    if (b.inWater()) {
                        // Fluid keeps the medium-aware drive, exactly as STEP does: the upright swim servo
                        // + holdDepth own a wet descend, and arriveOnTarget's drag model is the AIR/GROUND
                        // pair, not water's.
                        SteerControl.drive(b, v);
                        return;
                    }
                    SteerControl.holdClimbableStance(b, v, true);
                    SteerControl.arriveOnTarget(b, v);
                })
                .advanceWhen(b -> true);                       // geometry held (runner drives only when met) → STEP
        // STEP: walk off the edge toward the dest column; gravity does the one-block drop. Complete once
        // standing on the new floor (feet block == (tx, ty+1, tz) == (tx, fy, tz)). While still standing on
        // the FROM column, the step-off is gated on VELOCITY ALIGNMENT (SteerControl.stepOffGate — the
        // chained-step corner-slip fix): a previous step's cross-axis carry (a −z Descend chaining into
        // this +x one) would drift the bot across the one-wide lane during the walk-off and ground it on
        // the diagonally adjacent cell — a real off-plan settle the envelope fail→HOLDs. The gate arrests
        // the carry (pure cross servo toward the lane centreline) until the friction-horizon prediction
        // keeps the bot inside the lane, then the normal drive commits. Once the bot has left the from
        // column (foot moved or airborne) the gate never re-engages — the drop is gravity's.
        plan.phase("step")
                .arrestCarryFrom(fx, fz)   // the gate, now declarative (MovePlan.Phase.arrestCarryFrom):
                                           // the runner arrests BEFORE this drive, same tick, same predicate
                .drive((b, v) -> {
                    if (!b.grounded() || b.footX() != fx || b.footZ() != fz) left[0] = true; // left start → arm
                    // From a HANG the drop must wait until the bot is over the DESTINATION column: releasing
                    // here falls down the CURRENT column, not the destination (the (55,173,256) two-block
                    // drop into the bot's own placed cobble). No-op off a climbable, so the ordinary
                    // ground step-off is unchanged.
                    SteerControl.holdUntilOverTargetColumn(b, v);
                    if (b.inWater()) {
                        // Fluid keeps the medium-aware drive: the upright swim servo + holdDepth own a wet
                        // descend, and arriveOnTarget's drag model is the AIR/GROUND pair, not water's.
                        SteerControl.drive(b, v);
                        return;
                    }
                    SteerControl.holdClimbableStance(b, v, true);
                    // ARRIVE, not a position deadband (owner ruling 2026-08-14, the (105,91,170) 5-mm miss).
                    // This phase used to zero forward inside COLUMN_DEADBAND of the target and drive at full
                    // throttle outside it. That disc is centred on the target, so it suppresses thrust only
                    // while the bot is NEAR the column — and the tick momentum carries it out the FAR side the
                    // servo re-engages and shoves it further out. Measured on the flagship: the bot left the
                    // lip at −0.113 b/t, took two `descend:dead` ticks with the momentum at its largest, then
                    // thrust for one more tick before the servo reversed, and grounded at z=169.995 — five
                    // millimetres outside its own target cell, at the right height, on good ground. The
                    // envelope correctly fail→HOLDs that, so a coin-flip on tick phase wedges the bot.
                    //
                    // arriveOnTarget servos the PROJECTED stopping point (position + coast × velocity) instead
                    // of the position, so it brakes from the first airborne tick — where the same 0.15 disc
                    // was commanding exactly zero. Re-simulated on the convicted sequence: full reverse from
                    // the first airborne tick, touchdown ≈ 170.23 instead of 169.995. This is the servo Fall
                    // already runs on both its walkoff and fall phases; Descend was simply left behind when
                    // it landed (2026-08-06). The vine-bounce ruling is preserved untouched — arriveOnTarget
                    // short-circuits to recenterOn (deadband and all) whenever the bot is on a climbable.
                    SteerControl.arriveOnTarget(b, v);
                })
                // SETTLED, not grounded (owner ruling 2026-08-03, the (58,171,254) vine wedge). A waypoint is
                // a STAND cell and reaching it means being SUPPORTED there — which on a climbable means
                // HANGING, never grounded. Gating on grounded() made this predicate UNSATISFIABLE for any
                // descend whose destination cell holds a vine: the bot arrived dead-centre in the settle band
                // (measured botY=171.022, reached=true, footY==toFootY) and the move still never completed, so
                // STEP kept driving full-forward, vanilla's (horizontalCollision && onClimbable -> vy=+0.2)
                // ratcheted the bot back UP out of the cell one block, and it wedged against the leaf ceiling
                // at 172.200. settled() is the exact predicate for "supported in this medium" and is what
                // Movement.reached and Fall's terminal guard already use; grounded() was the narrower test.
                .done(b -> b.settled()
                        && atWaypoint(b, tx, toFootY, tz));
        return plan;
    }
}
