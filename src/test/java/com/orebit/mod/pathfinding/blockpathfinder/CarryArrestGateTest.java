package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The generalized <b>cross-axis carry arrest</b> ({@link MovePlan.Phase#arrestCarryFrom}, honoured by
 * {@link PhaseRunner}) — the owner-ratified step-off gate lifted off {@code Descend} onto every grounded
 * move that commits into a new column (owner ruling 2026-08-01).
 *
 * <p>Specimen: the 2026-07-31 post-replan Ascend hold. A sprinting {@code Parkour d(0,-3,-5)} runup had
 * driven the bot's centre to within ~0.15 of the −z lip of {@code (46,*,215)} (by design — {@code
 * Parkour.TAKEOFF_EDGE}); a region re-derive then swapped the route ~90° and the follower adopted an
 * {@code Ascend −X} from that same cell. The unbled −z carry coasted the centre across the boundary within
 * a tick, grounding the bot on {@code (46,147,214)} — off BOTH columns the Ascend's validity envelope
 * admits — a permanent fail→HOLD. The arrest bleeds the carry first and jumps after.
 *
 * <p>The assertions use each move's own committing input as the discriminator (Ascend's jump, WalkOff's
 * sprint) rather than reaching into the servo's numbers, so they pin BEHAVIOUR, not tuning.
 */
class CarryArrestGateTest {

    /** The witnessed frame: Ascend from floor (46,146,215) to floor (45,147,215) — a −x step. */
    private static final int FX = 46, FY = 146, FZ = 215;
    private static final int TX = 45, TY = 147, TZ = 215;
    private static final int FROM_FOOT_Y = 147, TO_FOOT_Y = 148;

    /** A pose/velocity-settable {@link BotSteering} that reports met geometry, so a plan's needs never hold
     *  the runner and the committing phase is actually reached. */
    private static final class CarryBot implements BotSteering {
        double x, y, z, vx, vz;
        boolean grounded = true;
        boolean climbable, climbBelow;
        boolean jumping, sprinting, sneaking;
        float forward;
        /**
         * The commanded thrust DIRECTION. Recorded because {@link SteerControl#stepOffGate}'s whole output is
         * {@code faceHorizontally(err) + setForward(+)} — the sign of the correction lives ONLY here, and this
         * double used to discard it ({@code faceHorizontally(dx,dz) { }}). Every arrest assertion in this file
         * therefore checked THAT an arrest happened and never WHICH WAY it pushed, which is exactly the gap a
         * 2026-08-12 flagship wedge fell through: the servo's own {@code crossErr} put the centreline 0.424 to
         * −x and the bot accelerated +x across the region boundary it had just crossed.
         */
        double faceDx = Double.NaN, faceDz = Double.NaN;

        CarryBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        CarryBot vel(double vx, double vz) { this.vx = vx; this.vz = vz; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return vx; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return vz; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public boolean onClimbable() { return climbable; }
        @Override public boolean climbableBelow() { return climbBelow; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { }
        // Geometry: everything the plans ask for is already established.
        @Override public boolean solidAt(int x, int y, int z) { return true; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return true; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; } // stone
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** The Ascend's segment, feet-frame cell centres: (46.5,147,215.5) → (45.5,148,215.5). */
    private static final class Seg implements SteerView {
        @Override public double sx() { return FX + 0.5; }
        @Override public double sy() { return FROM_FOOT_Y; }
        @Override public double sz() { return FZ + 0.5; }
        @Override public double tx() { return TX + 0.5; }
        @Override public double ty() { return TO_FOOT_Y; }
        @Override public double tz() { return TZ + 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** Drive the Ascend to its CLIMB phase (build advances on the first tick: its needs are met and its
     *  advanceWhen is solidAt(target), which the double reports true). */
    private static PhaseRunner ascendAtClimb(CarryBot bot) {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(MovementRegistry.ASCEND.plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y));
        runner.run(bot, new Seg()); // build → advance
        bot.jumping = false;        // clear anything build wrote; the climb tick is what we measure
        return runner;
    }

    @Test
    void ascendWithPerpendicularCarryArrestsInsteadOfJumping() {
        // The witnessed pose: centre parked 0.15 from the −z lip, still carrying the abandoned Parkour's
        // −z sprint. The step itself is −x, so that carry is pure CROSS.
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.15).vel(0, -0.2);
        PhaseRunner runner = ascendAtClimb(bot);

        assertFalse(runner.run(bot, new Seg()), "an arrested step is not done");
        assertFalse(runner.failed(), "arresting is not an envelope failure — the move is still viable");
        assertFalse(bot.jumping, "the jump must NOT be pressed while the cross carry would coast it off-lane");
    }

    @Test
    void ascendFromACleanStandJumpsImmediately() {
        // No carry, centred: the prediction is contained on the first tick, so the gate is a no-op and the
        // climb drives exactly as before. This is the common case and it must cost nothing.
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.5).vel(0, 0);
        PhaseRunner runner = ascendAtClimb(bot);

        runner.run(bot, new Seg());
        assertTrue(bot.jumping, "a clean, aligned entry jumps on the very first climb tick");
    }

    @Test
    void alongAxisMomentumIsNotArrested() {
        // Momentum ALONG the step (−x, the direction of travel) is not a corner-slip risk and must not be
        // braked — otherwise every normal approach would stutter.
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.5).vel(-0.2, 0);
        PhaseRunner runner = ascendAtClimb(bot);

        runner.run(bot, new Seg());
        assertTrue(bot.jumping, "along-axis carry is the normal run-up — never gated");
    }

    @Test
    void theGateReleasesOnceTheCarryIsBled() {
        // Convergence: the arrest servo drives cross velocity toward zero, so the same pose with the carry
        // spent commits. (Pins that the hold is transient by construction — never a freeze.)
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.15).vel(0, -0.2);
        PhaseRunner runner = ascendAtClimb(bot);
        runner.run(bot, new Seg());
        assertFalse(bot.jumping, "still carrying → arrested");

        bot.vel(0, 0).at(FX + 0.5, FROM_FOOT_Y, FZ + 0.5); // carry bled, pulled back to the centreline
        runner.run(bot, new Seg());
        assertTrue(bot.jumping, "carry spent and back on the lane → the gate releases and the jump fires");
    }

    @Test
    void theGateCannotReEngageOnceOffTheFromColumn() {
        // Self-limiting: mid-move (airborne, or foot already in the target column) the gate is inert, so it
        // can never interrupt a jump in flight.
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.15).vel(0, -0.9);
        PhaseRunner runner = ascendAtClimb(bot);

        bot.grounded = false; // airborne — the arc is committed
        runner.run(bot, new Seg());
        assertTrue(bot.jumping, "an airborne tick is never gated, however large the cross velocity");

        bot.grounded = true;
        bot.at(TX + 0.5, TO_FOOT_Y, TZ + 0.5); // now standing in the TARGET column
        bot.jumping = false;
        runner.run(bot, new Seg());
        assertFalse(runner.failed(), "the landing column is inside the envelope");
    }

    /**
     * The climbable-transit jump release ({@code Ascend}'s climb phase). Log-convicted 2026-08-01: a bot
     * that rode a vine up to {@code landFootY} and then left the curtain ({@code onClimbable} false) kept
     * the jump held and launched from one block too high, overshooting the landing to {@code landFootY+1} —
     * outside the target column's admitted band, a permanent fail→HOLD. The rule is the HEIGHT alone.
     */
    @Test
    void theJumpIsReleasedAtOrAboveTheLandingFeetInEitherMedium() {
        // At landFootY in the FROM column, having just left the vine (the witnessed pose): no height left
        // to gain, so the jump must be released whether or not the bot is still on a climbable.
        CarryBot offVine = new CarryBot().at(FX + 0.5, TO_FOOT_Y, FZ + 0.5).vel(0, 0);
        PhaseRunner runner = ascendAtClimb(offVine);
        runner.run(offVine, new Seg());
        assertFalse(offVine.jumping,
                "at landFootY there is no height left to gain — the jump must release off the climbable too");

        // And the mirror case: standing ON the landing stand itself must not re-jump off it.
        CarryBot onLanding = new CarryBot().at(TX + 0.5, TO_FOOT_Y, TZ + 0.5).vel(0, 0);
        PhaseRunner r2 = ascendAtClimb(onLanding);
        r2.run(onLanding, new Seg());
        assertFalse(onLanding.jumping, "arriving on the landing stand must not press another jump");
    }

    @Test
    void theJumpIsStillHeldWhileBelowTheLandingFeet() {
        // The load-bearing half: from the real takeoff stand the Ascend must still jump, or it can never
        // climb at all.
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.5).vel(0, 0);
        PhaseRunner runner = ascendAtClimb(bot);
        runner.run(bot, new Seg());
        assertTrue(bot.jumping, "below the landing feet the climb still needs height — jump stays held");
    }

    /**
     * The STALE-THRUST HANDOFF GAP (owner-ratified 2026-08-17; specimen: mineshaft-run3, the frozen-jungle
     * {@code Traverse(+z into (366,·,526))} → 90° {@code Descend(+x to (367,62,526))} corner). Steering
     * inputs are persistent key-state; the predecessor Traverse's final tick left {@code stepOffGate}'s
     * full-reverse arrest pressed when its ~0.02-block boundary transient advanced the cursor. Descend's
     * phase-0 {@code clear} had met needs, an unconditional {@code advanceWhen}, and an input-inert drive
     * ({@code holdUntilOverTargetColumn} bails off a climbable) — so the handoff tick wrote nothing, vanilla
     * physics ran the latched reverse thrust, and the bot was shoved back across the boundary into a
     * permanent fail→HOLD ({@code step FAILED ... bot=(366,64,525)}). The arm on {@code clear} makes the
     * runner write the centring arrest on that very tick and HOLD the phase until the friction-horizon
     * prediction is contained.
     */
    @Test
    void descendClearPhaseArrestsTheStaleHandoffCarryInsteadOfAdvancing() {
        // The witnessed handoff pose: entered (366,·,526) at z=526.019 (0.481 off the centreline the +x
        // step needs), still moving +z. The position alone puts the predicted stop outside the 0.2 lane
        // bound, so the gate must take the tick.
        MovePlan plan = MovementRegistry.DESCEND.plan(366, 63, 526, 367, 62, 526, 64, 63);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        CarryBot bot = new CarryBot().at(366.591, 64, 526.019).vel(-0.0225, 0.0222);

        assertFalse(runner.run(bot, seg(366.5, 64, 526.5, 367.5, 63, 526.5)), "an arrested step is not done");
        assertFalse(runner.failed(), "arresting is not an envelope failure — the move is still viable");
        assertTrue(runner.phase() == 0,
                "the gate must HOLD phase 0 (clear) while the carry is uncontained — advancing hands the "
                        + "input-inert tick to the predecessor's latched keys. phase=" + runner.phase());
        assertTrue(bot.forward > 0.0f,
                "the arrest must WRITE inputs on the handoff tick — an input-inert tick is the whole bug");
        assertTrue(bot.faceDz > 0.0,
                "the lane centreline z=526.5 lies +z of the bot at 526.019, so the commanded thrust must "
                        + "be +z (toward it). commanded dx=" + bot.faceDx + " dz=" + bot.faceDz);
    }

    @Test
    void descendClearPhaseAdvancesCleanlyWithoutCarry() {
        // Centred, no carry: the prediction is contained on the first tick, the gate is a no-op, and clear
        // advances immediately exactly as before the arm (the common straight-chain case must cost nothing).
        MovePlan plan = MovementRegistry.DESCEND.plan(366, 63, 526, 367, 62, 526, 64, 63);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        CarryBot bot = new CarryBot().at(366.5, 64, 526.5).vel(0, 0);

        runner.run(bot, seg(366.5, 64, 526.5, 367.5, 63, 526.5));
        assertTrue(runner.phase() == 1,
                "a clean, centred entry must advance clear -> step on its first tick. phase=" + runner.phase());
    }

    @Test
    void descendClearGateReleasesOnceTheCarryIsBled() {
        // Convergence (mirrors theGateReleasesOnceTheCarryIsBled): the same wedge pose commits once the
        // carry is spent and the centreline recovered — the hold is transient by construction, never a freeze.
        MovePlan plan = MovementRegistry.DESCEND.plan(366, 63, 526, 367, 62, 526, 64, 63);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        CarryBot bot = new CarryBot().at(366.591, 64, 526.019).vel(-0.0225, 0.0222);
        runner.run(bot, seg(366.5, 64, 526.5, 367.5, 63, 526.5));
        assertTrue(runner.phase() == 0, "still carrying → held in clear");

        bot.vel(0, 0).at(366.5, 64, 526.5); // carry bled, pulled back to the centreline
        runner.run(bot, seg(366.5, 64, 526.5, 367.5, 63, 526.5));
        assertTrue(runner.phase() == 1, "carry spent and back on the lane → clear advances to step");
    }

    /** A minimal segment view for the Descend fixtures (feet-cell-base frame, matching SegmentCursor). */
    private static SteerView seg(double sx, double sy, double sz, double tx, double ty, double tz) {
        return new SteerView() {
            @Override public double sx() { return sx; }
            @Override public double sy() { return sy; }
            @Override public double sz() { return sz; }
            @Override public double tx() { return tx; }
            @Override public double ty() { return ty; }
            @Override public double tz() { return tz; }
            @Override public boolean hasNext() { return false; }
            @Override public double nx() { return 0; }
            @Override public double ny() { return 0; }
            @Override public double nz() { return 0; }
        };
    }

    @Test
    void walkOffWithPerpendicularCarryDoesNotSprintOffTheLip() {
        // The irreversible member of the family: WalkOff leaves the lip SPRINTING, so an unbled cross carry
        // is at its worst exactly where recovery is impossible. Its drive sets sprint; the arrest does not.
        MovePlan plan = MovementRegistry.WALK_OFF.plan(FX, FY, FZ, FX - 3, FY, FZ, FROM_FOOT_Y, FROM_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        CarryBot bot = new CarryBot().at(FX + 0.5, FROM_FOOT_Y, FZ + 0.15).vel(0, -0.25);

        runner.run(bot, new Seg());
        assertFalse(bot.sprinting, "must not sprint off the lip while the cross carry is uncontained");
    }

    // ---- climbable-stance hold (SteerControl.drive) -----------------------------------------------
    // Owner physics, manual proof 2026-08-01. A curtain is not a floor: a ground move executing at one
    // must press the input that HOLDS its stance or the bot sinks and the move can never complete.
    // Convicted by the flagship stall at (129,~115.5,132) - a Descend atop a curtain bounced 115<->116
    // for ~12000 ticks, invisible to every envelope because it is never settled.

    /** A level segment (no descent) for the stance tests. */
    /**
     * FRAME (corrected 2026-08-12): {@code sy}/{@code ty} are the "feet-cell + 1.0" values
     * {@link com.orebit.mod.BotNavigator}'s {@code SegmentCursor} builds — {@code sy = start.getY() + 1.0},
     * {@code ty = target.getY() + 1.0} over FEET cells (BotNavigator:1658-1659). These bots sit at
     * {@code y == 10}, i.e. feet cell 10, so the segment values must be <b>11.0</b>, not 10.0.
     *
     * <p>They read 10.0 until now, which put {@code floorY = ty - 1.0 = 9.0} and made every bot here look a
     * FULL BLOCK above its target. That was invisible while {@code holdClimbableStance} gated its height
     * correction on the step's INTENT: these are Δy==0 segments, so {@code dy} collapsed to 0.0 and the
     * absolute comparison was never evaluated. Once the band decides (the (61,169,253) lateral-Climb wedge),
     * the error becomes live and these fixtures would assert a release instead of the hold they exist to pin.
     * Corrected here rather than in the servo: the servo agrees with production, the fixtures did not.
     */
    private static final class Level implements SteerView {
        // Verticals are the BASE of the feet cell (SegmentCursor's frame since 2026-08-15). These bots stand
        // in feet cell 10, so both ends read 10.0; they read 11.0 under the old feet-cell + 1.0 frame.
        @Override public double sx() { return 0.5; }
        @Override public double sy() { return 10.0; }
        @Override public double sz() { return 0.5; }
        @Override public double tx() { return 3.5; }
        @Override public double ty() { return 10.0; }
        @Override public double tz() { return 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** A descending segment - the one case where sinking IS the intent. */
    private static final class Down implements SteerView {
        @Override public double sx() { return 0.5; }
        @Override public double sy() { return 10.0; }
        @Override public double sz() { return 0.5; }
        @Override public double tx() { return 1.5; }
        @Override public double ty() { return 6.0; }
        @Override public double tz() { return 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    @Test
    void toppedOutOnACurtainHoldsJumpNotSneak() {
        // Feet ABOVE the climbable: jump out-runs the sink by re-climbing at the surface. Sneak would hold
        // too, but its ledge edge-guard would forbid stepping OFF the top and trap the bot.
        CarryBot b = new CarryBot().at(0.5, 10, 0.5);
        b.grounded = false; b.climbBelow = true;
        SteerControl.drive(b, new Level());
        assertTrue(b.jumping, "topped out on a curtain must hold jump to keep the stance");
        assertFalse(b.sneaking, "sneak would edge-guard the step off the curtain top");
    }

    @Test
    void feetInsideACurtainHoldSneakNotJump() {
        // Feet INSIDE: jump CLIMBS rather than holds, so sneak is the only stance-hold.
        CarryBot b = new CarryBot().at(0.5, 10, 0.5);
        b.grounded = false; b.climbable = true;
        SteerControl.drive(b, new Level());
        assertTrue(b.sneaking, "lateral cling inside a curtain must hold sneak to hold height");
        assertFalse(b.jumping, "holding jump inside a curtain climbs, it does not hold position");
    }

    /**
     * THE (207,−9,58) BACKPEDAL (2026-08-12) — an arrest must push TOWARD the lane centreline.
     *
     * <p>Witnessed on the headless flagship: the bot finished a −x Traverse and entered cell 207 at
     * {@code x=207.924}. That crossing is a REGION boundary (L0 cells are 16 wide, so 208 starts region 13),
     * which triggered a replan; the new first step was a {@code Descend (207,−8,58) → (207,−9,57)}, i.e. a
     * −z move, so {@code x} became the CROSS axis and the bot's fresh-entry offset of 0.424 became a lane
     * violation. The gate correctly fired — but the bot then accelerated {@code +x} to 208.004, back across
     * the boundary it had just crossed, and the Descend's validity envelope fail→HELD it there for the rest
     * of the run.
     *
     * <p>Hand-computed for this pose: {@code crossUx=+1}, {@code crossErr = 207.5 − 207.924 = −0.424},
     * {@code predictedOffset = +0.420} against the {@code 0.5 − PARKOUR_CELL_MARGIN = 0.2} threshold, so the
     * gate fires; {@code desiredCross = clamp(0.75 × −0.424, ±0.13) = −0.13} and
     * {@code errx = −0.13 − (−0.0018) = −0.128}. The commanded thrust is unambiguously −x. Back-solving the
     * observed velocities through ground friction gives an APPLIED impulse of ≈{@code (+0.082, −0.049)} — a
     * near-exact 180° negation of what was commanded.
     *
     * <p>This assertion could not have existed before: {@code CarryBot.faceHorizontally} discarded its
     * arguments, so every arrest test in this file checked THAT the gate fired and none checked WHICH WAY it
     * pushed. If this test passes, the servo is correct and the inversion lives downstream of
     * {@link SteerControl} (the {@code faceHorizontally} → yaw → forward path in the entity layer); if it
     * fails, the sign error is here.
     */
    @Test
    void arrestPushesTowardTheCentrelineNotAwayFromIt() {
        // The exact witnessed pose: mid-cell-entry, x-carry already bled, still travelling −z.
        CarryBot b = new CarryBot().at(207.924, -8.0, 58.829).vel(-0.0018, -0.0699);
        final SteerView seg = new SteerView() {
            @Override public double sx() { return 207.5; }
            @Override public double sy() { return -8.0; }
            @Override public double sz() { return 58.5; }
            @Override public double tx() { return 207.5; }
            @Override public double ty() { return -9.0; }
            @Override public double tz() { return 57.5; }
            @Override public boolean hasNext() { return false; }
            @Override public double nx() { return 0; }
            @Override public double ny() { return 0; }
            @Override public double nz() { return 0; }
        };

        final boolean arrested = SteerControl.stepOffGate(b, seg);

        assertTrue(arrested,
                "cross offset 0.424 exceeds 0.5 - PARKOUR_CELL_MARGIN (0.2), so the gate must take the tick");
        assertTrue(b.forward > 0.0f, "an arrest drives the correction; it does not idle");
        assertTrue(b.faceDx < 0.0,
                "the centreline x=207.5 lies 0.424 blocks in -x, so the commanded thrust must be -x. "
                        + "A +x command is the inversion that carried the bot to 208.004, back over the "
                        + "region boundary, and out of its own validity envelope. commanded dx="
                        + b.faceDx + " dz=" + b.faceDz);
    }

    @Test
    void aDeliberateDescentReleasesBothHolds() {
        // The one case where sinking is the intent - the move is descending through/off the curtain.
        CarryBot inside = new CarryBot().at(0.5, 10, 0.5);
        inside.grounded = false; inside.climbable = true;
        SteerControl.drive(inside, new Down());
        assertFalse(inside.sneaking, "a descending segment must not hold the stance");

        CarryBot above = new CarryBot().at(0.5, 10, 0.5);
        above.grounded = false; above.climbBelow = true;
        SteerControl.drive(above, new Down());
        assertFalse(above.jumping, "a descending segment must not hold the stance");
    }

    @Test
    void ordinaryGroundWalkPressesNeither() {
        // The overwhelmingly common case must be untouched: no curtain anywhere near the feet.
        CarryBot b = new CarryBot().at(0.5, 10, 0.5);
        b.grounded = true;
        SteerControl.drive(b, new Level());
        assertFalse(b.jumping, "an ordinary ground walk must not press jump");
        assertFalse(b.sneaking, "an ordinary ground walk must not press sneak");
    }

    @Test
    void groundedInAVineWalksNormally() {
        // The jungle case, and the reason the !grounded() guard is load-bearing: standing on a real block
        // with vines in the FEET cell is an ordinary walk, not a cling. Holding sneak here would edge-guard
        // every ledge and trap the bot (measured: flagship best regressed 58.43 -> 212.55 without this).
        CarryBot b = new CarryBot().at(0.5, 10, 0.5);
        b.grounded = true; b.climbable = true;
        SteerControl.drive(b, new Level());
        assertFalse(b.sneaking, "grounded in a vine is a walk - sneak would trap the bot on ledges");
        assertFalse(b.jumping, "and it certainly must not climb");
    }

    @Test
    void aVineHangingBelowSolidGroundIsNotATopOut() {
        // climbableBelow() alone is not the stance: a bot standing on real ground with a curtain one cell
        // under it (a vine under an overhang) must not hop. The !grounded() conjunct is load-bearing.
        CarryBot b = new CarryBot().at(0.5, 10, 0.5);
        b.grounded = true; b.climbBelow = true;
        SteerControl.drive(b, new Level());
        assertFalse(b.jumping, "grounded on solid footing is not a curtain top-out");
    }
}
