package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

import org.junit.jupiter.api.Test;

/**
 * {@link Fall}'s <b>hang-landing cling</b> (owner-ratified 2026-08-19, the vine-hang wedge): once the
 * fall's arrest has happened, the Fall holds the cling for the rest of its OWN tenure.
 *
 * <p><b>The gap.</b> The fall phase's stance servo ({@code holdClimbableStance}) only sneaks INSIDE the
 * settle band {@code [floorY, floorY+0.20]}; on every tick the feet are above it the descend branch
 * returns having written nothing. On a 1-cell vine run the arrest can land anywhere in
 * {@code [floorY, floorY+1)}, and {@code atWaypoint}'s band clause simultaneously withholds
 * {@code done}/{@code reached} — so an arrested-but-high hang got NO input from anyone and slid back out
 * the bottom at the vanilla {@code -0.15}/t clamp before the next step (usually Climb) could take over.
 *
 * <p><b>The design is CONVERGENCE, not a sticky hold (settled by the 2026-08-20 run-4 conviction,
 * flagship-r4-async-parked.log):</b> the vanilla climbable slide is {@code 0.15}/t, strictly less than
 * the {@code 0.20} settle band, so a released above-band arrest cannot skip the band — it slides IN,
 * where the stance servo's unconditional in-band hold arrests it and {@code done}/{@code reached}
 * (atWaypoint's band clause) fire. A sneakHeld-gated sticky hold was tried and REVERTED the same night:
 * one seeded sneak tick (fix A's planless cling during a window-swap gap) became self-sustaining ABOVE
 * the band, where atWaypoint withholds done forever and Fall has no failWhen — the bot parked at +0.47
 * for 59k ticks. The remaining velocity arm ({@code velY > CLIMBABLE_ARREST_VY}) is deliberately narrow:
 * a suppressed hang's stored velY reads the one-tick gravity {@code -0.0784}, below the gate, so the
 * line never sustains a hold on its own. A transiting fall has no sneak writer and rides the clamp below
 * the gate, so nothing arrests mid-drop; the deliberate release-drop
 * ({@code CarryArrestGateTest.aDeliberateDescentReleasesBothHolds}) likewise stays green.
 *
 * <p>Harness cloned from {@link DescendVineLandingTest} (VineBot + Seg + run), retargeted at
 * {@code new Fall().plan(...)} framed for a hang landing: {@code toFootY} is the vine run's bottom cell.
 * The poses are injected DESTINATION poses, so the runner's implicit settle gate is deliberately not
 * armed (the archetype's ARRIVAL-test pattern — {@code completes()}).
 */
class FallHangLandingClingTest {

    /** The frame: a Fall down a 1-wide vine column, floor (58,172,254) -> floor (58,169,254). */
    private static final int FX = 58, FY = 172, FZ = 254;
    private static final int TX = 58, TY = 169, TZ = 254;
    private static final int FROM_FOOT_Y = 173, TO_FOOT_Y = 170; // toFootY = the vine run's bottom cell

    /** A pose-settable bot that reports all plan geometry already established (the VineBot clone).
     *  {@code sneakLastTick} mirrors AllyBotEntity's post-physics snapshot (6751c12): {@code sneakHeld()}
     *  is "held now, or in force when this pose was produced" — the test's driver models the tick-top
     *  reset + snapshot cycle so the across-reset hold is pinned against the REAL input lifecycle. */
    private static final class VineBot implements BotSteering {
        double x, y, z, vy;
        boolean grounded, climbable, climbBelow, sneaking, sneakLastTick, hcol;
        float forward;

        VineBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return vy; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public boolean onClimbable() { return climbable; }
        @Override public boolean climbableBelow() { return climbBelow; }
        @Override public boolean horizontalCollision() { return hcol; }
        @Override public boolean sneakHeld() { return sneaking || sneakLastTick; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { }
        // Geometry: everything the plan asks for is already established (the real cells were vine + air).
        @Override public boolean solidAt(int x, int y, int z) { return true; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return true; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** The step's segment in the feet frame: (58.5,173,254.5) -> (58.5,170,254.5) — a straight drop. */
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

    /** A runner begun on a fresh Fall plan, settle gate NOT armed (injected destination poses). */
    private static PhaseRunner newRunner() {
        MovePlan plan = new Fall().plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        return runner;
    }

    /**
     * Tick the real plan through the real runner {@code ticks} times; report whether the move completed.
     * Tick 1 drives WALKOFF (its advance fires the same tick on an airborne, un-clinging bot); tick 2
     * onward drives the terminal FALL phase against the pose under test.
     */
    private static boolean run(VineBot bot, int ticks) {
        PhaseRunner runner = newRunner();
        SteerView view = new Seg();
        boolean done = false;
        for (int i = 0; i < ticks; i++) done = runner.run(bot, view);
        return done;
    }

    /**
     * THE CONVERGENCE PIN (the 2026-08-20 run-4 conviction, in two acts). Act 1: a pose arrested ABOVE
     * the settle band by a prior writer (fix A's planless cling during a window-swap gap — the physical
     * seeding: sneak survives only as the 6751c12 snapshot, stored velY reads the suppressed-hang
     * {@code -0.0784}) must be RELEASED by the Fall's drive, not re-held — a sticky in-tenure hold parks
     * the bot above the band forever (atWaypoint withholds done; Fall has no failWhen; 59k-tick park).
     * Act 2: once the released slide brings the pose into the band, the stance servo's unconditional
     * in-band hold arrests it and the step completes HELD — the convergence the design guarantees
     * (a 0.15/t slide cannot skip the 0.20 band).
     */
    @Test
    void anAboveBandArrestReleasesAndConvergesIntoTheHeldBand() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 0.55, 254.5);
        bot.grounded = false;
        bot.climbable = true;      // the vine run
        bot.vy = -0.0784;          // the suppressed-hang stored velY — below CLIMBABLE_ARREST_VY
        PhaseRunner runner = newRunner();
        SteerView view = new Seg();
        runner.run(bot, view);     // WALKOFF advances (airborne, over-column) — the fall phase is next
        bot.sneakLastTick = true;  // a prior writer arrested this pose (its input produced it)
        boolean done = false;
        for (int i = 0; i < 2; i++) {
            bot.sneaking = false;              // the tick-top input reset (AllyBotEntity.tick)
            done = runner.run(bot, view);      // the fall drive judges the pose
            bot.sneakLastTick = bot.sneaking;  // the post-physics snapshot (6751c12)
        }
        assertFalse(done, "0.55 above the band is not arrival — done must stay withheld");
        assertFalse(bot.sneaking,
                "an above-band arrest must RELEASE (no writer re-holds it) so the -0.15 slide can carry "
                        + "the feet into the settle band — a sticky hold here parks the bot above the "
                        + "band forever (the run-4 59k-tick park)");
        // Act 2: the released slide has brought the pose into the band — the stance servo holds, done fires.
        bot.at(58.5, TO_FOOT_Y + 0.10, 254.5);
        bot.vy = -0.15;            // riding the clamp into the band
        bot.sneaking = false;
        done = runner.run(bot, view);
        assertTrue(done, "in the band the hang is a resting pose — the Fall completes");
        assertTrue(bot.sneaking, "and it completes HELD by the stance servo's in-band hold");
    }

    /** In the band the stance servo and the cling agree: held, positionally arrived, move complete. */
    @Test
    void anInBandHangStaysClungAndCompletes() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 0.1, 254.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = -0.0784; // the physical suppressed-hang reading (2026-08-20 review: vy=0.0 is a state
                          // real suppression never stores — gravity rebuilds one tick each tick)
        assertTrue(run(bot, 2), "an in-band hang is a resting pose — the Fall completes");
        assertTrue(bot.sneaking, "and it completes HELD, not mid-release");
    }

    /**
     * MUST NOT REGRESS. A fall still TRANSITING the vine run is below the arrest threshold — sneaking
     * there would stop the drop at the wrong cell (onClimbable is true for EVERY tick of a fall through
     * a vine column; the velocity gate, not the medium, is what says "arrested").
     */
    @Test
    void aFallStillTransitingTheVineIsNotArrested() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 1.5, 254.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = -0.2254;       // the measured third free-fall tick — genuinely dropping
        assertFalse(run(bot, 2), "still transiting — nowhere near arrival");
        assertFalse(bot.sneaking,
                "a fall still moving through the vine must NOT be arrested — the cling is gated on the "
                        + "arrest having already happened, never on merely being in a vine");
    }

    /** The ordinary dry landing: no climbable anywhere, so the cling's own gates make it inert. */
    @Test
    void anOrdinaryDryGroundLandingIsByteIdentical() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 1.0, 254.5);
        bot.grounded = false;   // tick 1: airborne mid-drop — WALKOFF advances
        bot.vy = -0.3;
        PhaseRunner runner = newRunner();
        SteerView view = new Seg();
        runner.run(bot, view);
        bot.at(58.5, TO_FOOT_Y, 254.5); // tick 2: touched down on the landing floor
        bot.grounded = true;
        bot.vy = 0.0;
        assertTrue(runner.run(bot, view), "a plain grounded landing on the destination floor completes");
        assertFalse(bot.sneaking, "no climbable — the cling never engages and the dry fall is unchanged");
    }
}
