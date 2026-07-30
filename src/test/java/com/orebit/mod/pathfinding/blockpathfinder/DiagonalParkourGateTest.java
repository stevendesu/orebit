package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour;

/**
 * The {@link DiagonalParkour} gate-point runup + corner-gate takeoff trigger, headless (the
 * {@link ParkourValidityEnvelopeTest} pattern — a positionable {@link BotSteering} double driving the plan's
 * phases through a {@link PhaseRunner}, no Minecraft). The properties under test are the churn-specimen fix
 * (the longrun-7 Traverse↔DiagonalParkour flip-flop):
 *
 * <ul>
 *   <li><b>The takeoff fires EARLY, while the bot is still grounded on the takeoff cell</b> — the trigger is
 *       the along-diagonal crossing of the takeoff-corner GATE ({@link SteerControl#pastGate}), not the old
 *       drive-to-the-edge overshoot whose late fire let a laterally offset approach spill the grounded foot
 *       cell into the diagonal-adjacent ground cell (the validity envelope's fail set → replan churn).</li>
 *   <li><b>The runup AIMS at the gate</b> ({@link SteerControl#steerViaGate}) — position pursuit, so a
 *       lateral approach is pulled back toward the diagonal instead of drifting past the lip (the hole the
 *       pure velocity-alignment servo left open).</li>
 *   <li><b>The hazard predictive early-takeoff variant is preserved</b> (magma/honey gap floor — Fix 3's
 *       {@code proj + 3·v} predictive fire).</li>
 * </ul>
 */
class DiagonalParkourGateTest {

    private static final double ALONG = 1.0 / Math.sqrt(2.0); // per-axis blocks per along-line block

    /** A {@link BotSteering} double with a continuously positionable pose + settable velocity/gap-hazard,
     *  recording the look + jump/sprint inputs the phases press. */
    private static final class PosBot implements BotSteering {
        double x, y, z;
        double velX, velZ;
        boolean grounded;
        // recorded outputs
        boolean jumping, sprinting;
        double faceDx, faceDz;
        // settable gap-floor hazard cell (Fix 3), -1 = none
        int hazX = Integer.MIN_VALUE, hazY, hazZ;

        PosBot at(double x, double y, double z, boolean onGround) {
            this.x = x; this.y = y; this.z = z; this.grounded = onGround;
            return this;
        }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return velX; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return velZ; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) {
            return x == hazX && y == hazY && z == hazZ;
        }
    }

    /** A trivial non-degenerate segment for the phases' drives (the gate math anchors on the plan's own
     *  cells, never this view). */
    private static final class View implements SteerView {
        @Override public double sx() { return 0.5; }
        @Override public double sy() { return 11.0; }
        @Override public double sz() { return 0.5; }
        @Override public double tx() { return 2.5; }
        @Override public double ty() { return 11.0; }
        @Override public double tz() { return 2.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** The g=1 flat diagonal frame the P1/churn forensics used: takeoff floor (0,10,0) → landing (2,10,2).
     *  Takeoff stand (0,11,0), centre (0.5, 0.5); exit corner (1,1); gate ≈ (0.7525, 0.7525) (along ≈ 0.357). */
    private static MovePlan plan() {
        return MovementRegistry.DIAGONAL_PARKOUR.plan(0, 10, 0, 2, 10, 2, 11, 11); // full-block feet == floor+1
    }

    /** Position the bot ON the takeoff diagonal, {@code along} blocks along-line past the cell centre. */
    private static PosBot onDiagonal(PosBot b, double along, boolean grounded) {
        return b.at(0.5 + along * ALONG, 11, 0.5 + along * ALONG, grounded);
    }

    @Test
    void takeoffFiresWhileStillGroundedOnTheTakeoffCell() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        View view = new View();
        PosBot bot = new PosBot();

        // At the cell centre the runup holds — the gate crossing is well ahead.
        runner.run(onDiagonal(bot, 0.0, true), view);
        assertEquals(0, runner.phase(), "at the takeoff centre the runup holds");

        // Just past the gate's (deadbanded) projection ≈ 0.307 along-line — the centre is still ≈ 0.27
        // blocks inside the cell on each axis, the foot cell unchanged. The trigger must fire HERE.
        runner.run(onDiagonal(bot, 0.32, true), view);
        assertEquals(1, runner.phase(), "the corner-gate crossing advances runup → takeoff");
        assertTrue(bot.footX() == 0 && bot.footZ() == 0 && bot.grounded(),
                "…while the bot is still grounded ON the takeoff cell (the anti-spill property)");
        assertFalse(runner.failed(), "inside the validity envelope the whole way");

        // The takeoff phase now presses the jump — still on the takeoff cell.
        runner.run(bot, view);
        assertTrue(bot.jumping, "takeoff presses jump on the very next tick");
        assertTrue(bot.footX() == 0 && bot.footZ() == 0, "…before the centre ever leaves the cell");
    }

    @Test
    void shortOfTheGateTheRunupHolds() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        PosBot bot = new PosBot();
        runner.run(onDiagonal(bot, 0.20, true), new View());
        assertEquals(0, runner.phase(), "along 0.20 is short of the gate — keep running up");
        assertFalse(bot.jumping, "no jump pressed during the runup");
    }

    @Test
    void theGateTriggerRequiresGrounded() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        PosBot bot = new PosBot();
        runner.run(onDiagonal(bot, 0.32, false), new View());
        assertEquals(0, runner.phase(), "past the gate but airborne: the trigger never fires off the ground");
    }

    @Test
    void runupPullsALateralApproachBackTowardTheGate() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        // Laterally offset toward +z (the spill side), short of the gate along-line (along ≈ 0.283), vel 0:
        // the recorded aim must have +x (toward the diagonal axis) DOMINANT over z — position control toward
        // the gate point (0.7525, 0.7525). The old velocity-only alignment saw zero velocity error direction
        // along the axis and never corrected the offset — the spill hole.
        PosBot bot = new PosBot().at(0.55, 11, 0.85, true);
        runner.run(bot, new View());
        assertEquals(0, runner.phase(), "short of the gate — still the runup");
        assertTrue(bot.faceDx > 0, "thrusts toward the gate");
        assertTrue(bot.faceDz < 0, "…and pulls BACK toward the diagonal axis (position correction)");
    }

    @Test
    void hazardousGapFloorKeepsThePredictiveEarlyTakeoff() {
        View view = new View();

        // Magma on the first diagonal gap-floor cell (1,10,1): Fix 3's predictive trigger (proj + 3·v) fires
        // from the cell centre at cruise speed — long before the gate crossing.
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        PosBot hot = new PosBot();
        hot.hazX = 1; hot.hazY = 10; hot.hazZ = 1;
        hot.velX = 0.2; hot.velZ = 0.2;
        runner.run(onDiagonal(hot, 0.0, true), view);
        assertEquals(1, runner.phase(), "hazardous gap floor: the predictive early takeoff fires at the centre");

        // Control: identical pose + momentum over a safe gap floor holds the runup (the gate is positional —
        // velocity never fires it early).
        PhaseRunner control = new PhaseRunner();
        control.begin(plan());
        PosBot safe = new PosBot();
        safe.velX = 0.2; safe.velZ = 0.2;
        control.run(onDiagonal(safe, 0.0, true), view);
        assertEquals(0, control.phase(), "safe gap floor: the gate trigger stays positional");
    }
}
