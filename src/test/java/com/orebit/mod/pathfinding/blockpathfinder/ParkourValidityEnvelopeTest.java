package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;

/**
 * The P1 off-plan-wedge fixes, headless (the {@link PhaseRunnerDoorTest} pattern — a pose-settable
 * {@link BotSteering} double, no Minecraft):
 *
 * <ul>
 *   <li><b>Grounded-gated {@code reached} for the committed family</b> ({@link Movement#reached} gates on
 *       {@link Movement#commitsAcrossArrival}): a falling bot whose feet block transits a parkour waypoint
 *       stand cell mid-air must NOT match it — that fly-through match advanced the cursor onto a step the
 *       bot never stood at and poisoned the settle anchor (P1 §3). A grounded exact match still fires
 *       (landings are grounded by definition), and an UNcommitted move keeps the ungated match.</li>
 *   <li><b>The plan-level validity envelope</b> ({@link MovePlan#failWhen} → {@link PhaseRunner#failed}):
 *       a committed jump grounded anywhere off the plan's own cells (takeoff stand / landing column) is
 *       provably outside the move's model, so the runner reports the step FAILED instead of driving — and
 *       it can never fire during normal execution (runup grounded on the takeoff stand, airborne ticks,
 *       the touchdown tick, a falling arc's descent-column touchdown).</li>
 *   <li><b>{@link PhaseRunner#doneNow}</b> (the P4 stale-done fix): the terminal {@code done} guard asked
 *       fresh on the CURRENT state, so the follower's ABANDONED tripwire can discriminate a same-state
 *       completion (suppress) from a genuinely mid-flight plan (log) — {@code run}'s return is one tick
 *       stale at the advance boundary by construction.</li>
 * </ul>
 *
 * The follower wiring itself (BotNavigator's advance loop + FAILED → clearPlan → replan-from-live-floor)
 * is MC-coupled and only verifiable in-game; these pin the seam predicates that wiring consumes.
 */
class ParkourValidityEnvelopeTest {

    /** A {@link BotSteering} double with a settable pose: foot cell + grounded; entity position derived at
     *  the cell centre (the drives only read pose/velocity and press no-op inputs). */
    private static final class PoseBot implements BotSteering {
        int footX, footY, footZ;
        boolean grounded;

        PoseBot at(int fx, int fy, int fz, boolean onGround) {
            this.footX = fx; this.footY = fy; this.footZ = fz; this.grounded = onGround;
            return this;
        }

        @Override public double x() { return footX + 0.5; }
        @Override public double y() { return footY; }
        @Override public double z() { return footZ + 0.5; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return footX; }
        @Override public int footY() { return footY; }
        @Override public int footZ() { return footZ; }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** A trivial non-degenerate segment for the phases' drives (never asserted on). */
    private static final class View implements SteerView {
        @Override public double sx() { return 0.5; }
        @Override public double sy() { return 11.0; }
        @Override public double sz() { return 0.5; }
        @Override public double tx() { return 3.5; }
        @Override public double ty() { return 11.0; }
        @Override public double tz() { return 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    // ---- Fix B: the grounded gate on the committed family's cursor-advance test -----------------------

    @Test
    void airborneFlyThroughDoesNotReachACommittedWaypoint() {
        PoseBot bot = new PoseBot();
        // Feet block exactly on the waypoint stand cell, but mid-air (the P1 ballistic-transit transient).
        bot.at(5, 81, 5, /*grounded=*/false);
        assertFalse(MovementRegistry.PARKOUR.reached(bot, 5, 81, 5),
                "a mid-air feet-block transit must not advance a Parkour step");
        assertFalse(MovementRegistry.DIAGONAL_PARKOUR.reached(bot, 5, 81, 5),
                "a mid-air feet-block transit must not advance a DiagonalParkour step");
        assertFalse(MovementRegistry.WALK_OFF.reached(bot, 5, 81, 5),
                "a mid-air feet-block transit must not advance a WalkOff step");

        // The legitimate landing IS grounded — the gate delays a real advance by zero ticks.
        bot.at(5, 81, 5, /*grounded=*/true);
        assertTrue(MovementRegistry.PARKOUR.reached(bot, 5, 81, 5),
                "a grounded exact match still advances (touchdown tick)");
        assertTrue(MovementRegistry.DIAGONAL_PARKOUR.reached(bot, 5, 81, 5),
                "a grounded exact match still advances (touchdown tick)");
    }

    @Test
    void uncommittedMovesKeepTheUngatedBlockExactMatch() {
        PoseBot bot = new PoseBot();
        bot.at(5, 81, 5, /*grounded=*/false);
        // Traverse (and the rest of the reversible family) is byte-identical: the gate rides
        // commitsAcrossArrival, which is false here — no behavior change outside the committed moves.
        assertTrue(MovementRegistry.TRAVERSE.reached(bot, 5, 81, 5),
                "an uncommitted move's reached is unchanged (no grounded gate)");
        assertTrue(MovementRegistry.FALL.reached(bot, 5, 81, 5),
                "Fall stays ungated (its landing may be buoyant water, never grounded)");
    }

    // ---- Fix A: the validity envelope → PhaseRunner.failed ---------------------------------------------

    /** The P1 frame: a flat diagonal jump, takeoff floor (0,10,0) → landing floor (2,10,2) (g=1). */
    private static MovePlan diagPlan() {
        return MovementRegistry.DIAGONAL_PARKOUR.plan(0, 10, 0, 2, 10, 2);
    }

    @Test
    void envelopeFailsTheStepOnTheFirstGroundedOffPlanTick() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(diagPlan());
        // Grounded a level BELOW the plan frame and off the diagonal line — the observed adoption cell
        // class. The very first run() must report FAILED without driving.
        PoseBot bot = new PoseBot().at(1, 10, 3, /*grounded=*/true);
        assertFalse(runner.run(bot, new View()), "a failed step is never done");
        assertTrue(runner.failed(), "grounded off the plan's cells → the validity envelope fires");
        // A fresh plan starts clean.
        runner.begin(diagPlan());
        assertFalse(runner.failed(), "begin() resets the FAILED verdict");
    }

    @Test
    void envelopeIsSilentThroughNormalExecutionStates() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(diagPlan());
        View view = new View();
        PoseBot bot = new PoseBot();

        // Runup/takeoff ticks: grounded ON the takeoff stand (0,11,0).
        runner.run(bot.at(0, 11, 0, true), view);
        assertFalse(runner.failed(), "grounded on the takeoff stand is the runup — never a failure");

        // Airborne ticks: not grounded, anywhere along the arc (even over no cell of the plan).
        runner.run(bot.at(1, 11, 1, false), view);
        assertFalse(runner.failed(), "airborne ticks can never fire the grounded envelope");

        // Touchdown tick: grounded on the landing stand (2,11,2).
        runner.run(bot.at(2, 11, 2, true), view);
        assertFalse(runner.failed(), "the landing stand is the move's own completion cell");
    }

    @Test
    void fallingParkourAdmitsItsDescentColumnButNotTheGap() {
        // A falling cardinal 2-gap: takeoff floor (0,10,0) → landing floor (3,7,0); the descent runs down
        // the landing column, so a grounded touch there (feet band ty+1..fy+1) is the move's own geometry.
        MovePlan plan = MovementRegistry.PARKOUR.plan(0, 10, 0, 3, 7, 0);
        PhaseRunner runner = new PhaseRunner();
        View view = new View();
        PoseBot bot = new PoseBot();

        runner.begin(plan);
        runner.run(bot.at(3, 9, 0, true), view); // grounded mid-band on the landing column
        assertFalse(runner.failed(), "a grounded touch on the planned descent column is not a failure");

        runner.begin(plan);
        runner.run(bot.at(3, 8, 0, true), view); // the landing stand itself (ty+1)
        assertFalse(runner.failed(), "the landing stand is inside the envelope");

        runner.begin(plan);
        runner.run(bot.at(1, 10, 0, true), view); // short — grounded IN the gap, below takeoff level
        assertTrue(runner.failed(), "a short landing in the gap is outside the move's model → FAILED");
    }

    // ---- Fix P4: doneNow — the terminal guard on the CURRENT state -------------------------------------

    @Test
    void doneNowReadsTheTerminalGuardOnTheCurrentState() {
        // A single-phase converted-move shape (the sprint-swim/Traverse class): done and reached flip on
        // the same physics state, so the advance boundary needs the fresh sample to see the completion.
        MovePlan mp = new MovePlan();
        mp.phase("cross")
                .drive((b, v) -> { })
                .done(b -> b.grounded() && b.footX() == 5 && b.footY() == 2 && b.footZ() == 5);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        PoseBot bot = new PoseBot().at(4, 2, 5, true);

        assertFalse(runner.run(bot, new View()), "pre-arrival: the terminal guard is false");
        assertFalse(runner.doneNow(bot), "doneNow agrees with run on the same state");

        // The next tick's physics lands the bot on the target cell: the advance loop consumes this state
        // FIRST (reached fires), so run()'s stale sample still says false — but doneNow, asked fresh,
        // sees the same-state completion and suppresses the false ABANDONED.
        bot.at(5, 2, 5, true);
        assertTrue(runner.doneNow(bot), "the terminal done, evaluated fresh, reports the completion");
    }

    @Test
    void doneNowIsFalseWhileTheCursorIsShortOfTheTerminalPhase() {
        MovePlan mp = new MovePlan();
        mp.phase("first")
                .drive((b, v) -> { })
                .advanceWhen(b -> false); // pinned on phase 0
        mp.phase("last")
                .drive((b, v) -> { })
                .done(b -> true);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        PoseBot bot = new PoseBot().at(5, 2, 5, true);
        runner.run(bot, new View());
        assertFalse(runner.doneNow(bot),
                "a plan genuinely mid-phase is NOT done, whatever the terminal guard would say");
    }
    /** Regression pin (longrun-8 first fail->hold specimen): stepping off a Descend's edge, the bot's
     *  centre crosses into the destination column while its box is still grounded on the from-block's
     *  lip — foot cell (tx, fy+1, tz). That LIP TRANSIT is a legitimate mid-step state, inside the
     *  envelope; one cell higher is displacement and must fail. */
    @Test
    void descendLipTransitIsInsideTheEnvelope() {
        // Descend from floor (70,71,-53) to floor (69,70,-53): from-stand foot (70,72,-53),
        // dest stand foot (69,71,-53), lip transit foot (69,72,-53).
        MovePlan plan = MovementRegistry.DESCEND.plan(70, 71, -53, 69, 70, -53);
        PoseBot bot = new PoseBot();
        assertFalse(plan.failed(bot.at(70, 72, -53, true)), "from stand is inside the envelope");
        assertFalse(plan.failed(bot.at(69, 72, -53, true)), "the lip transit must NOT fail (longrun-8)");
        assertFalse(plan.failed(bot.at(69, 71, -53, true)), "the destination stand is inside");
        assertFalse(plan.failed(bot.at(69, 70, -53, false)), "the airborne drop tick is exempt");
        assertTrue(plan.failed(bot.at(69, 73, -53, true)), "grounded ABOVE the lip band is displacement");
        assertTrue(plan.failed(bot.at(68, 71, -53, true)), "grounded off both columns is displacement");
    }

    /** Regression pin (longrun-9 second fail->hold specimen): approaching an Ascend, the bot's centre
     *  crosses into the TARGET column at the pre-jump height while its box is still grounded on the
     *  from-block — foot (tx, landFootY-1, tz). That FACE-PRESS transit is legitimate (the mirror of
     *  Descend's lip transit); one cell lower is a genuine fell-short displacement and must fail. */
    @Test
    void ascendFacePressTransitIsInsideTheEnvelope() {
        // Ascend from floor (87,63,-32) to floor (87,64,-31): from-stand foot (87,64,-32),
        // landing stand foot (87,65,-31), face-press transit foot (87,64,-31).
        MovePlan plan = MovementRegistry.ASCEND.plan(87, 63, -32, 87, 64, -31);
        PoseBot bot = new PoseBot();
        assertFalse(plan.failed(bot.at(87, 64, -32, true)), "from stand is inside the envelope");
        assertFalse(plan.failed(bot.at(87, 64, -31, true)), "the face-press transit must NOT fail (longrun-9)");
        assertFalse(plan.failed(bot.at(87, 65, -31, true)), "the landing stand is inside");
        assertFalse(plan.failed(bot.at(87, 65, -32, true)), "the from-column climb band is inside");
        assertTrue(plan.failed(bot.at(87, 63, -31, true)), "grounded DEEP in the target column is displacement");
        assertTrue(plan.failed(bot.at(86, 64, -31, true)), "grounded off both columns is displacement");
    }

    /** Regression pin (battA-cliff third fail->hold specimen): the runup's TAKEOFF_EDGE trigger lets the
     *  centre cross the takeoff boundary a grounded tick before the jump registers — foot cell one PAST
     *  the takeoff along the jump axis (the first gap column at takeoff feet height). Legitimate
     *  transitional state; two past, or laterally off-axis, stays displacement. */
    @Test
    void parkourLipCrossingTransitIsInsideTheEnvelope() {
        // Flat 2-gap Parkour from floor (0,10,0) to floor (3,10,0): takeoff foot (0,11,0),
        // lip-crossing transit foot (1,11,0), landing foot (3,11,0).
        MovePlan plan = MovementRegistry.PARKOUR.plan(0, 10, 0, 3, 10, 0);
        PoseBot bot = new PoseBot();
        assertFalse(plan.failed(bot.at(0, 11, 0, true)), "takeoff stand is inside the envelope");
        assertFalse(plan.failed(bot.at(1, 11, 0, true)), "the lip-crossing transit must NOT fail (battA-cliff)");
        assertFalse(plan.failed(bot.at(3, 11, 0, true)), "the landing stand is inside");
        assertFalse(plan.failed(bot.at(2, 11, 0, false)), "airborne mid-gap is exempt");
        assertTrue(plan.failed(bot.at(2, 11, 0, true)), "grounded TWO past the lip is displacement");
        assertTrue(plan.failed(bot.at(1, 11, 1, true)), "grounded laterally off-axis is displacement");
    }

}
