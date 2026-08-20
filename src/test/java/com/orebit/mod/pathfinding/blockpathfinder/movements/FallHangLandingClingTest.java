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
 * <p><b>The gate is ARRESTED ({@code velY > CLIMBABLE_ARREST_VY}), never merely-in-a-vine:</b>
 * {@code onClimbable()} is true for every tick of a fall THROUGH a vine column, and sneaking mid-transit
 * would stop the drop at the wrong cell. The velocity guard is also what keeps
 * {@code CarryArrestGateTest.aDeliberateDescentReleasesBothHolds} green — a deliberate release-drop rides
 * the clamp, below the arrest threshold, so the cling never sees it. {@code clingHold}'s own gates make
 * it a no-op off a climbable and wherever something already holds the bot up, so the ordinary dry fall is
 * byte-identical.
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

    /** A pose-settable bot that reports all plan geometry already established (the VineBot clone). */
    private static final class VineBot implements BotSteering {
        double x, y, z, vy;
        boolean grounded, climbable, climbBelow, sneaking, hcol;
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
        @Override public boolean sneakHeld() { return sneaking; }
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
     * THE REGRESSION. Arrested mid-cell — above the settle band, so the stance servo's descend branch
     * writes nothing, and {@code atWaypoint}'s band clause withholds {@code done} — the hang must STILL
     * be clung, or the {@code -0.15} clamp walks the feet out the bottom of the run before the next step
     * can take over. (Fails without Fall's hang-landing cling — verified by disabling that one line.)
     */
    @Test
    void anArrestedHangAboveTheSettleBandIsStillClung() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 0.55, 254.5);
        bot.grounded = false;
        bot.climbable = true;   // the vine run
        bot.vy = 0.0;           // ARRESTED — the fall already stopped here
        assertFalse(run(bot, 2), "0.55 above the band is not arrival — done must stay withheld");
        assertTrue(bot.sneaking,
                "an arrested hang above the settle band must be HELD for the rest of the Fall's tenure; "
                        + "with no input the -0.15 clamp slides the feet out the bottom of the vine run");
    }

    /** In the band the stance servo and the cling agree: held, positionally arrived, move complete. */
    @Test
    void anInBandHangStaysClungAndCompletes() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 0.1, 254.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = 0.0;
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
