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
        boolean jumping, sprinting, sneaking;
        float forward;

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
        @Override public boolean onClimbable() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
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
}
