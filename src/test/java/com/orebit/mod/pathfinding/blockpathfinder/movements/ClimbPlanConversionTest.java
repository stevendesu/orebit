package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

import org.junit.jupiter.api.Test;

/**
 * {@link Climb}'s conversion onto the phase model (2026-08-05) — the last movement off the bare
 * {@code steer} path.
 *
 * <p><b>Why it mattered.</b> A steer-only move gets no validity envelope, no carry arrest, and — because
 * the gate lives in {@link PhaseRunner} — no implicit settle. Climb owns vines, and every follower failure
 * of the 2026-08-03..05 arc lived on a vine, so the move most exposed to the problem was the one opted out
 * of all its protections.
 *
 * <p>The conversion must change NO inputs (the phase delegates to the existing six-regime servo) and must
 * preserve the CURTAIN TOP-OUT, which is the one arrival {@code settled()} deliberately does not cover.
 */
class ClimbPlanConversionTest {

    /** A vine column climb: floor (60,140,200) -> (60,141,200), feet 141 -> 142. */
    private static final int FX = 60, FY = 140, FZ = 200;
    private static final int TX = 60, TY = 141, TZ = 200;
    private static final int FROM_FOOT_Y = 141, TO_FOOT_Y = 142;

    private static final class ClimbBot implements BotSteering {
        double x = FX + 0.5, y = TO_FOOT_Y, z = FZ + 0.5;
        boolean grounded, climbable, climbBelow, sneaking, jumping;

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
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
        @Override public boolean sneakHeld() { return sneaking; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { }
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

    private static final class Up implements SteerView {
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

    private static MovePlan plan() {
        return new Climb().plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y);
    }

    /** The conversion is LIVE: a non-null plan is what routes the follower through PhaseRunner at all. */
    @Test
    void climbNowProducesAPlan() {
        assertNotNull(plan(), "Climb must produce a MovePlan, or it stays on the unprotected steer path");
    }

    /**
     * THE CURTAIN TOP-OUT must still complete. Feet above a curtain are not grounded, not in fluid, and
     * {@code onClimbable()} is false (the climbable is UNDER the feet), so a {@code done} written against
     * {@code settled()} could never fire — the livelock measured 2026-08-01 at {@code (55,~140,207)}, 20+
     * ticks alternating footY with no envelope failure because nothing was failing. The phase's {@code done}
     * delegates to {@code reached}, which admits {@code climbableBelow()}, so the two cannot drift.
     */
    @Test
    void curtainTopOutStillCompletes() {
        ClimbBot bot = new ClimbBot();
        bot.grounded = false;
        bot.climbable = false;   // the curtain is BELOW the feet, not in them
        bot.climbBelow = true;
        PhaseRunner runner = new PhaseRunner();
        // Gate deliberately NOT armed: this fixture injects the DESTINATION pose, and the gate would rightly
        // refuse to run a plan whose START cell the bot is nowhere near. The terminal guard is under test.
        runner.begin(plan());
        boolean done = false;
        for (int i = 0; i < 3; i++) done = runner.run(bot, new Up());
        assertTrue(done, "a curtain top-out is a legitimate Climb arrival and must fire done");
    }

    /**
     * And the ENTRY gate must admit a top-out too, or a move beginning from one deadlocks. A bot standing on
     * a curtain top is supported by it but is not grounded, not in fluid, and not {@code onClimbable()} — so
     * {@code settled()} cannot see it, and {@code settleIntoBand} cannot rescue it either (it returns
     * immediately off a climbable). {@code inRestingPose} therefore admits {@code climbableBelow()},
     * mirroring {@code Climb.reached}.
     */
    @Test
    void toppedOutAtTheStartCellPassesTheSettleGate() {
        ClimbBot bot = new ClimbBot();
        bot.y = FROM_FOOT_Y;     // resting ON the curtain top, at the cell the plan is framed from
        bot.grounded = false;
        bot.climbable = false;   // the curtain is below the feet, not in them
        bot.climbBelow = true;
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan(), FROM_FOOT_Y);
        runner.run(bot, new Up());
        assertTrue(bot.jumping,
                "the gate must pass a top-out stance so the climb can actually drive; blocking it strands "
                        + "the bot forever at a pose it legitimately reached");
    }

    /** A bot nowhere near the column, settled on the ground, is off-plan and must FAIL rather than latch. */
    @Test
    void settledOffTheColumnFailsTheEnvelope() {
        ClimbBot bot = new ClimbBot();
        bot.x = FX + 4.5;        // four cells away, on the floor
        bot.grounded = true;
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan(), FROM_FOOT_Y);
        runner.run(bot, new Up());
        assertTrue(runner.failed(),
                "the envelope is the whole point of converting Climb — an off-column settle must report "
                        + "FAILED instead of silently latching, which is what a steer-only move did");
    }

    /** A mid-climb hang is airborne and must NOT be judged — the envelope only rules on settled poses. */
    @Test
    void midClimbHangIsNotAVerdict() {
        ClimbBot bot = new ClimbBot();
        bot.y = FROM_FOOT_Y + 0.4;   // part-way up the column
        bot.grounded = false;
        bot.climbable = true;
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan(), FROM_FOOT_Y);
        runner.run(bot, new Up());
        assertFalse(runner.failed(), "a hang IS airborne; judging it would fail every climb mid-flight");
    }
}
