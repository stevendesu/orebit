package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The follower-side runtime that executes a {@link MovePlan} — the reactive counterpart to the declarative
 * plan a {@link Movement} builds. It owns the one piece of per-execution state a stateless movement singleton
 * can't: the phase <b>cursor</b> ("what we're trying to do"). Everything else is re-derived from the live world
 * each tick, so the move self-heals instead of latching a stale "done."
 *
 * <p>Per tick ({@link #run}):
 * <ol>
 *   <li><b>Reality override.</b> If the plan's {@link MovePlan#resetWhen regression} guard fires, snap the
 *       cursor back to the first phase — the move physically fell back and must re-attempt.</li>
 *   <li><b>Validity envelope.</b> If the plan's {@link MovePlan#failWhen} guard fires, report the step
 *       {@link #failed FAILED} — the live state is outside the move's model, so no phase can complete and
 *       nothing is driven; the follower drops the plan and replans from the bot's real floor.</li>
 *   <li><b>Establish geometry.</b> Re-check the current phase's {@link MovePlan.Need needs} against the LIVE
 *       world and act on any unmet one: {@code AIR} still solid &rarr; {@link BotSteering#mine} it (timed, one
 *       at a time); {@code FOOTING} still missing &rarr; {@link BotSteering#place} it (instant). While anything
 *       is unmet the bot <b>holds</b> on the column rather than driving — "stop and fix, like a player." A
 *       break or place missed for any reason is simply re-issued next tick (the self-heal).</li>
 *   <li><b>Drive + advance.</b> Once the geometry holds, run the phase's {@code drive} inputs; advance the
 *       cursor when its {@code advanceWhen} guard fires, or report the move complete when the last phase's
 *       {@code done} guard fires.</li>
 * </ol>
 *
 * <p>MC-free (drives the bot only through the {@link BotSteering}/{@link SteerView} seams), so it — and every
 * movement's plan — stays headless-testable, exactly like the rest of the {@code steer} path.
 */
public final class PhaseRunner {

    private MovePlan plan;
    private int cursor;

    /**
     * The feet cell this plan is FRAMED FROM, and whether the implicit settle gate has been satisfied yet.
     *
     * <p><b>The gate (owner ruling 2026-08-05).</b> Every move's geometry — which cells CLEAR breaks, where
     * the body sits during the step-off, which columns the envelope admits — is derived from "feet resting
     * at {@code fromFootY}", i.e. inside the {@code [X.00, X.20]} band. {@link
     * com.orebit.mod.pathfinding.blockpathfinder.Movement#atWaypoint} now enforces that at ARRIVAL, so a
     * clean chain of moves preserves it for free. But any break in the chain — a replan, a window swap, a
     * fall, a mid-plan adoption — starts a move from whatever pose the bot happens to hold, and a bot a
     * full block high has a 1.8-tall box spanning THREE cells instead of two, fouling geometry the plan
     * never checked. Enforcing it here rather than in fourteen {@code plan()} methods is the whole point:
     * the canopy-vine failure was chased through five separate arrival/entry sites before it became clear
     * they were one rule applied inconsistently.
     *
     * <p><b>Latched, not re-tested.</b> Once satisfied the gate never re-arms, because a move legitimately
     * leaves the band the moment it commits — a Descend's whole purpose is to stop resting at
     * {@code fromFootY}. Re-testing would deadlock every step at its own first drive tick.
     */
    private int fromFootY = Integer.MIN_VALUE;
    private boolean settled;

    // ---- Execution diagnostics (read by the follower's Debug.VERBOSE forensics; never drive behavior) ----
    /** Regression snaps since {@link #begin} — a climbing count is the attempt/fall-back/re-attempt livelock. */
    private int regressions;
    /** The unmet need {@link #run} held on THIS tick ({@code null} = not holding), plus its cell. */
    private MovePlan.Need holdNeed;
    private int holdX, holdY, holdZ;
    /** Whether the last {@link #run} hit the plan's {@link MovePlan#failWhen validity envelope} — see {@link #failed}. */
    private boolean failed;

    /** Begin executing {@code plan} from its first phase (called when a new step's plan is built). */
    public void begin(MovePlan plan) {
        begin(plan, Integer.MIN_VALUE);
    }

    /**
     * Begin {@code plan}, with the feet cell it is FRAMED FROM so the implicit settle gate can enforce it
     * ({@code Integer.MIN_VALUE} disables the gate). See {@link #settling}.
     */
    public void begin(MovePlan plan, int fromFootY) {
        this.plan = plan;
        this.cursor = 0;
        this.regressions = 0;
        this.holdNeed = null;
        this.failed = false;
        this.fromFootY = fromFootY;
        this.settled = false;
    }

    /** Whether a plan is currently loaded (the follower runs {@link #run} only then; else it uses {@code steer}). */
    public boolean active() {
        return plan != null;
    }

    /**
     * Whether the last {@link #run} reported the step FAILED: the plan's {@link MovePlan#failWhen validity
     * envelope} fired, so the live state is outside the move's model and no phase of this plan can ever
     * complete from it. Terminal like done but unsuccessful — the follower drops its block plan (the plan's
     * frame is fiction from here) and lets the normal planless replan re-search from the bot's real floor.
     */
    public boolean failed() {
        return failed;
    }

    /** Drop the current plan (step finished / window swapped) so the next step rebuilds from scratch. */
    public void clear() {
        this.plan = null;
        this.cursor = 0;
        this.regressions = 0;
        this.holdNeed = null;
        this.failed = false;
    }

    // ---- Diagnostic getters (Debug.VERBOSE only; see AllyBotEntity.logPhaseDiagnostics) ---------------

    /** The current phase cursor (0-based). */
    public int phase() {
        return cursor;
    }

    /** Total phases in the loaded plan (0 when none). */
    public int phases() {
        return plan != null ? plan.size() : 0;
    }

    /** Regression snaps since {@link #begin} (the move physically fell back and re-attempted). */
    public int regressions() {
        return regressions;
    }

    /** The unmet need the last {@link #run} held on ({@code null} = it drove/finished instead). */
    public MovePlan.Need holdNeed() {
        return holdNeed;
    }

    public int holdX() { return holdX; }
    public int holdY() { return holdY; }
    public int holdZ() { return holdZ; }

    /**
     * Evaluate the loaded plan's TERMINAL {@code done} guard against the CURRENT bot state — pure (no cursor
     * motion, no needs established, no inputs driven). For the follower's advance-boundary forensics: {@link
     * #run}'s return is necessarily one tick stale by the time the cursor-advance loop runs (it sampled last
     * tick's physics state), so a step whose {@code done} and {@code reached} predicates flip on the SAME
     * state — every single-phase converted move, and a grounded-gated parkour landing — would read as
     * "abandoned mid-phase" on 100% of its normal completions. Asking the terminal guard on the live state
     * discriminates a same-state completion (not abandoned — log nothing) from a genuinely mid-flight plan.
     * {@code false} when no plan is loaded or the cursor has not reached the terminal phase.
     */
    public boolean doneNow(BotSteering bot) {
        return plan != null && cursor == plan.size() - 1 && plan.phaseAt(cursor).isDone(bot);
    }

    /**
     * Advance the plan one tick against the live world. Returns {@code true} when the move is complete (the last
     * phase's {@code done} guard fired), so the follower can advance its waypoint cursor.
     */
    public boolean run(BotSteering bot, SteerView view) {
        if (plan == null) {
            return false;
        }
        failed = false;
        if (cursor > 0 && plan.regressed(bot)) {
            cursor = 0; // reality override: the move fell back to its start — re-attempt from phase 0
            regressions++;
        }
        // Validity envelope — checked AFTER resetWhen (so the legitimate balk-at-the-start retry keeps
        // precedence; an envelope always excludes the reset cell, so both can't be true at once) and BEFORE
        // any doors/needs/drive (a plan whose frame reality has left must never mine, place, or press inputs
        // from it). Firing is terminal: the follower drops the plan and the normal replan path re-searches
        // from the bot's real floor.
        if (plan.failed(bot)) {
            failed = true;
            holdNeed = null;
            return false;
        }

        // IMPLICIT SETTLE — before any needs, drive or advance. The plan's frame is fiction until the bot is
        // actually resting where it assumes; establishing that first is cheaper and safer than every move
        // re-deriving it. Inert in the overwhelming case: a grounded bot satisfies it on the first tick and
        // pays one predicate. Latched so a committing move is never dragged back.
        if (!settled && fromFootY != Integer.MIN_VALUE) {
            if (SteerControl.inRestingPose(bot, fromFootY)) {
                settled = true;
            } else {
                SteerControl.settleIntoBand(bot, view, fromFootY);
                return false;
            }
        }

        MovePlan.Phase phase = plan.phaseAt(cursor);

        boolean holding = false;
        holdNeed = null;

        // Openables FIRST (DOORS P3; trapdoors identically — DESIGN-trapdoors.md §7), before any body-cell
        // geometry — an openable-state is a precondition of the whole crossing, so it is established BEFORE the
        // bot drives through (and the exit double-toggle CLOSEs before the exit segment drives). The toggle is
        // instant (a direct server set), so all reqs resolve this tick; we re-validate against the LIVE block
        // via doorOpenAt (the shared OPEN property covers doors, trapdoors and fence gates) — NOT solidAt, because an open
        // door keeps a thin collision box and an open trapdoor its wall panel (solidAt would stay true and
        // never clear the hold). Self-healing like FOOTING: re-issued each tick until the openable reads the
        // target state — which is also the anti-trick property: an EXTERNALLY re-flipped door/trapdoor simply
        // mismatches again next tick and is re-toggled, no timers. The executor verb dispatches on the req's
        // kind flag (resolved once at injection from the live block — see MovePlan.requireTrapdoor).
        // Almost always empty (an openable crossing is rare).
        for (MovePlan.Req d : plan.doorReqs()) {
            if (bot.doorOpenAt(d.x, d.y, d.z) != d.open) {
                if (d.openableKind == MovePlan.Req.OPENABLE_TRAPDOOR) {
                    bot.setTrapdoorOpen(d.x, d.y, d.z, d.open);
                } else if (d.openableKind == MovePlan.Req.OPENABLE_GATE) {
                    bot.setGateOpen(d.x, d.y, d.z, d.open);
                } else {
                    bot.setDoorOpen(d.x, d.y, d.z, d.open);
                }
                if (holdNeed == null) { holdNeed = MovePlan.Need.OPEN; holdX = d.x; holdY = d.y; holdZ = d.z; }
                holding = true; // re-validate next tick (the toggle is instant)
            }
        }

        // Establish this phase's geometry. Mining is timed and one-cell-at-a-time, so the first unmet AIR
        // need claims the tick and we hold; placements are instant, so all missing footings resolve now. While
        // anything is unmet, hold on the target column instead of driving the phase (stop and fix the geometry).
        for (MovePlan.Req r : phase.needs()) {
            if (r.kind == MovePlan.Need.AIR) {
                // An openable cell (door OR trapdoor) is governed by a Need.OPEN (toggled by hand above) —
                // NEVER mine it, even a CLOSED toggleable one (which DOES obstruct the corridor): the crossing
                // operates it by hand, not by force.
                if (plan.isOpenableCell(r.x, r.y, r.z)) continue;
                // Mine only a GENUINE obstruction — the block's live collision actually intrudes into the bot's
                // body corridor (movementBlockedAt), NOT merely "has some collision" (solidAt). This is the
                // general fix for the whole passable-collision class: a movement's plan() declares Need.AIR on its
                // body column blind to what occupies it (Traverse.plan), and many blocks the bot moves THROUGH or
                // stands ON still read solidAt (thin panel / low floor) — an already-open door or open trapdoor
                // (edge panel outside the corridor), and a carpet / pressure plate / bottom slab / snow layer in
                // the feet cell (collision below the auto-step). solidAt made the runner swing at all of them
                // (arm-swing + crack overlay on blocks that need nothing); the geometry test lets the bot pass/
                // stand while still clearing a real full-block wall. No per-block-type ("is it a door") gate.
                if (bot.movementBlockedAt(r.x, r.y, r.z, plan.moveDx(), plan.moveDz())) {
                    bot.mine(r.x, r.y, r.z);
                    if (holdNeed == null) { holdNeed = r.kind; holdX = r.x; holdY = r.y; holdZ = r.z; }
                    holding = true;
                    break; // one timed break per tick
                }
            } else { // FOOTING
                // A CLIMBABLE TOP is footing the search may have priced deliberately (Traverse's
                // CLIMBABLE_TOP_COST node, declared via Phase.needFootingOrClimbable). It has no collision, so
                // solidAt reads false and the strict branch below would place a plank the plan never budgeted
                // — or, barehanded, hold on this cell forever. Same predicate the planner used, read live.
                if (r.climbableOk && bot.climbableFloorAt(r.x, r.y, r.z)) {
                    continue; // geometry already established: nothing to place, nothing to hold
                }
                if (!bot.solidAt(r.x, r.y, r.z)) {
                    // SELF-ENTOMBMENT GUARD (the 2026-08-19 run-5 forensic; DESIGN-replan-handoff.md §5/R3 —
                    // defense-in-depth beside Pillar's failWhen envelope): NEVER place into a cell the bot's
                    // own body occupies — its feet cell (footY) or head cell (footY+1). A correctly-framed
                    // FOOTING is never there: Pillar's place phase only runs past its jump gate
                    // (advanceWhen b.y() >= fy+2), so at legitimate place time footY >= r.y+1 and the cell
                    // is BENEATH the feet; Descend's step-down floor and Traverse's bridge planks sit in
                    // another column or at footY-1 (audited: these three are the only FOOTING declarers;
                    // clutches ride the separate placeClutch verb). Only a plan whose frame reality has left
                    // can aim a place at the bot itself (run-5: a seam adoption re-ran an already-executed
                    // Pillar with the frame shifted +1 onto the bot's column, and the executor's place()
                    // clears soft occupants and writes server-side with no entity-collision check — it
                    // sealed the bot in, silently and permanently). Refuse and HOLD, don't place: holdNeed
                    // still reports FOOTING, so a persistent refusal is visible in the follower's hold log,
                    // and the cell re-tests each tick as the bot moves. Grounded, the failWhen envelope
                    // fails the mis-framed step; this guard is the AIRBORNE half of the same defense.
                    boolean ownBodyCell = r.x == bot.footX() && r.z == bot.footZ()
                            && (r.y == bot.footY() || r.y == bot.footY() + 1);
                    if (!ownBodyCell) {
                        bot.place(r.x, r.y, r.z);
                    }
                    if (holdNeed == null) { holdNeed = r.kind; holdX = r.x; holdY = r.y; holdZ = r.z; }
                    holding = true; // re-validate next tick (place is instant; a refused self-cell clears as the bot moves)
                }
            }
        }
        if (holding) {
            // "Stop and fix, like a player" — and STOP means stop. A SETTLED bot station-keeps on its OWN
            // column (SteerControl.stationKeep): re-centring on the step TARGET instead drove it at full
            // forward into the very block it was mining, which on a curtain ratchets it out of its stance
            // faster than any block can be broken (the (58,133,189) wedge; full mechanism on stationKeep).
            // An AIRBORNE bot cannot stop, so it keeps homing on the landing column as before — killing its
            // input mid-flight would drop it short of a gap it is already committed across.
            // onClimbable counts as stoppable even when settled() reads false (2026-08-05). settled()
            // requires the bot to be HELD by the climbable (sneak held, or velocity above the arrest
            // threshold), so a bot already SLIDING down a vine falls to the else-branch — and
            // recenterOnTarget drives it at the NEXT column with no stance hold, the worst possible input
            // for a bot sliding out of the very cell its plan is framed from. A bot in a climbable is not
            // ballistic: it can always stop, and stationKeep now holds it unconditionally. Only a genuinely
            // airborne bot must keep homing.
            //
            // "STOP" IS MEDIUM-RELATIVE (2026-08-10). Zero inputs stops a bot only where something already
            // holds it up. In fluid a no-input bot SINKS ~0.025/tick: measured on a submerged wall break,
            // 41 hold ticks took botY 39.992 -> 39.018, the foot cell left the Traverse's admitted band,
            // failWhen fired, and BotMining's reactive progress reset with the block unbroken (a break
            // continues only while the mover keeps asking for the SAME cell). stationKeep therefore asks
            // what HOLDS the bot in its current medium — the depth autopilot in fluid, sneak on a
            // climbable, nothing on the ground. Nothing here needs a budget: failWhen is purely POSITIONAL,
            // so once the bot genuinely holds position an arbitrarily long break is fine.
            if (bot.settled() || bot.onClimbable()) {
                SteerControl.stationKeep(bot, view);
            } else {
                SteerControl.recenterOnTarget(bot, view);
            }
            return false;
        }

        // CROSS-AXIS CARRY ARREST (owner ruling 2026-08-01) — see MovePlan.Phase.arrestCarryFrom. A phase
        // that commits into a NEW column must not drive while the bot's perpendicular momentum would coast
        // it out of the one-wide lane: the gate WRITES the arrest inputs for this tick and we skip the
        // drive, bleeding the carry first and committing after. Deliberately AFTER the needs block (geometry
        // prep proceeds while the carry bleeds) and BEFORE the drive. Inert on phases that did not declare
        // it; a no-op on the common straight-line step, which has no cross velocity to bleed.
        if (phase.carryUncontained(bot, view)) {
            return false;
        }

        // Geometry holds — drive the phase, then advance or finish. While the step-off gate is ARMED but the
        // carry is CONTAINED (the gate above declined to hold), the generic ground drive must not steer for
        // the corner racing line the gate's lane law forbids — stepGateArmed routes SteerControl.drive's land
        // branch onto the step-target ARRIVE for exactly these ticks (the (259,78,448) two-tick thrust/hold
        // limit cycle; owner-ratified 2026-08-19, DESIGN-servo-normalization.md §2.5). Set and cleared AROUND
        // the drive so no other SteerControl caller can ever read it stale; phases that write their own
        // servos (Ascend's jump-climb, Fall/Descend's arriveOnTarget) never consult it and keep their inputs.
        SteerControl.stepGateArmed = phase.carryGateArmed(bot);
        phase.drive(bot, view);
        SteerControl.stepGateArmed = false;
        if (cursor == plan.size() - 1) {
            return phase.isDone(bot);
        }
        if (phase.shouldAdvance(bot)) {
            // PHASE ADVANCE is otherwise INVISIBLE (owner request, 2026-08-03). The follower's exec log prints
            // once per state change AFTER run() has already driven and advanced, so a phase that satisfies its
            // own shouldAdvance on its first drive tick never appears in the log at all — it is only detectable
            // as a gap in the phase histogram. That is exactly how the 2026-08-03 wedge hid: every Descend in
            // the run showed phase=1/2 and never 0/2, so its CLEAR phase (the one that mines the step-off
            // column and places the footing) was advancing past without performing its edits, and the bot then
            // tried to descend through a 1-tall gap it had never opened. Logging the transition names the
            // phase that yielded and the tick it happened on.
            if (com.orebit.mod.Debug.VERBOSE) {
                com.orebit.mod.OrebitCommon.LOGGER.info("[Orebit]   phase advance {}/{} -> {}/{} (left '{}')",
                        cursor, plan.size(), cursor + 1, plan.size(), plan.phaseAt(cursor).name);
            }
            cursor++;
        }
        return false;
    }
}
