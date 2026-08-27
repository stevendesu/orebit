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

    // PHASE INDICES ARE +1 SINCE 2026-08-27. DiagonalParkour gained a CROSS-AXIS ALIGN phase at index 0, so
    // runup is 1, takeoff 2, airborne 3, land 4. These fixtures place the bot exactly on the diagonal
    // centreline with zero velocity, so parkourCrossSettled holds on the first check and align advances
    // without ever driving — the behaviour every assertion below describes is unchanged, only its index.


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
        float forward = Float.NaN, strafe = Float.NaN;
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
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setStrafe(float xxa) { strafe = xxa; }
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

    /**
     * Drive the plan to its LAND phase, then park the bot at rest on the landing cell, off centre by more
     * than the arrival band. The land drive must command a correction; a zero-input tick here is a wedge,
     * because {@code done} needs {@link Movement#atWaypoint} containment the bot cannot reach on its own.
     */
    private static PosBot drivenToLandPhase(PhaseRunner runner, View view) {
        PosBot bot = new PosBot();
        runner.begin(plan());
        runner.run(onDiagonal(bot, 0.32, true), view);      // runup -> takeoff (past the gate, grounded)
        runner.run(onDiagonal(bot, 0.40, false), view);     // takeoff -> airborne (feet left the ground)
        bot.at(2.5, 11, 2.5, true);
        runner.run(bot, view);                              // airborne -> land (touchdown)
        return bot;
    }

    @Test
    void landPhaseCorrectsAnOffCentreTouchdown_insteadOfHoldingWhereItStopped() {
        // ============================================================================================
        // THE (1157,63,1033) LONG-FLAGSHIP WEDGE, 2026-08-26. The bot completed a DiagonalParkour arc,
        // came to rest 0.2 per-axis (0.283 along the diagonal) past the landing centre, and sat there for
        // 600+ ticks at fwd=0.00 str=0.00 vel=(0,0), phase 3/4:
        //
        //   exec DiagonalParkour wp2 phase=3/4 botFoot=(1157,63,1033) grounded=true
        //     botXZ=(1157.700,1033.700)  landing centre (1157.500,1033.500)
        //     vel=(0.0000,0.0000) fwd=0.00 str=0.00 src=#19437 arrive:runup
        //
        // (`src` frozen at #19437 because parkourAirborne never calls tag() — a stale stamp, not evidence
        // of which servo ran. The zero inputs are the evidence.)
        //
        // CAUSE: the land phase drove parkourAirborne unconditionally. That servo's position term IS the
        // touchdown predictor, and the predictor DEGENERATES once the feet are down — standing still on the
        // landing it reports the current position as the touchdown, so both branches collapse to a
        // zero-VELOCITY setpoint and the bot holds wherever it stopped. Commit 4edf3f4 (2026-08-25) fixed
        // exactly this in Parkour and measured it on ice.chain.g3 (434 ticks parked 0.237 off centre at
        // fwd=0.00). DiagonalParkour was missed, and stayed latent until the swim rewrite let the flagship
        // travel far enough to attempt a diagonal jump on this route.
        //
        // Airborne ticks keep the ballistic law — velocity IS the only lever mid-arc. Grounded ticks get the
        // position-anchored ARRIVE, which is the same split Parkour already has.
        // ============================================================================================
        PhaseRunner runner = new PhaseRunner();
        View view = new View();                              // its target IS the landing centre (2.5, 2.5)
        PosBot bot = drivenToLandPhase(runner, view);
        assertEquals(4, runner.phase(), "reached the land phase");

        // Parked at rest past the landing centre, outside the arrival band (0.25 per axis = 0.354 along;
        // the flagship sat at 0.2 per axis, right on the band's edge — this is the same state, unambiguously
        // outside it so the assertion cannot turn on a floating-point tie).
        bot.at(2.75, 11, 2.75, true);
        bot.velX = 0.0;
        bot.velZ = 0.0;
        runner.run(bot, view);

        assertFalse(runner.doneNow(bot), "containment is unmet, so the move must NOT be complete");
        assertTrue(Math.abs(bot.forward) > 1e-6f || Math.abs(bot.strafe) > 1e-6f,
                "the land drive commands a correction; zero input here is the wedge (fwd=" + bot.forward
                        + " str=" + bot.strafe + ")");
        // ...and it corrects the right way: back toward the landing centre, which lies at -x/-z from here.
        assertTrue(bot.faceDx < 0 && bot.faceDz < 0,
                "aims back at the landing centre (" + bot.faceDx + ", " + bot.faceDz + ")");
    }

    @Test
    void takeoffFiresWhileStillGroundedOnTheTakeoffCell() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        View view = new View();
        PosBot bot = new PosBot();

        // At the cell centre the runup holds — the gate crossing is well ahead.
        runner.run(onDiagonal(bot, 0.0, true), view);
        assertEquals(1, runner.phase(), "at the takeoff centre the runup holds");

        // Just past the gate's (deadbanded) projection ≈ 0.307 along-line — the centre is still ≈ 0.27
        // blocks inside the cell on each axis, the foot cell unchanged. The trigger must fire HERE.
        runner.run(onDiagonal(bot, 0.32, true), view);
        assertEquals(2, runner.phase(), "the corner-gate crossing advances runup → takeoff");
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
        assertEquals(1, runner.phase(), "along 0.20 is short of the gate — keep running up");
        assertFalse(bot.jumping, "no jump pressed during the runup");
    }

    @Test
    void theGateTriggerRequiresGrounded() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan());
        PosBot bot = new PosBot();
        runner.run(onDiagonal(bot, 0.32, false), new View());
        assertEquals(1, runner.phase(), "past the gate but airborne: the trigger never fires off the ground");
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
        // This pose is 0.212 off the diagonal centreline — past COLUMN_DEADBAND — so since 2026-08-27 it is
        // the ALIGN phase that holds and corrects it, not the runup. That is the phase added for precisely
        // this case, and the commanded direction is unchanged: cross-cancel toward the axis is +x/−z here.
        assertEquals(0, runner.phase(), "crooked entry — ALIGN holds until the cross axis is settled");
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
        assertEquals(2, runner.phase(), "hazardous gap floor: the predictive early takeoff fires at the centre");

        // Control: identical pose + momentum over a safe gap floor holds the runup (the gate is positional —
        // velocity never fires it early).
        PhaseRunner control = new PhaseRunner();
        control.begin(plan());
        PosBot safe = new PosBot();
        safe.velX = 0.2; safe.velZ = 0.2;
        control.run(onDiagonal(safe, 0.0, true), view);
        assertEquals(1, control.phase(), "safe gap floor: the gate trigger stays positional");
    }
}
