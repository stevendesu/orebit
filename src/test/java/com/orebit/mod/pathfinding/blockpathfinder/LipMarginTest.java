package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;

/**
 * <b>Margin at the cell boundary</b> — the two 2026-08-14 flagship wedges, which are the same defect wearing
 * two faces: a follower that manages no margin where a move crosses a lip. Both were convicted on the vd=12
 * flagship autotest and both fixes are "use the servo the sibling move already uses".
 *
 * <ul>
 *   <li><b>{@code Parkour} — the backwards hot entry.</b> At {@code (158,112,114)→(158,111,109)} a
 *       {@code Diagonal d(1,0,1)} handed off across the takeoff cell's −z corner, grounding the bot at
 *       {@code z=114.029}. Against a −z jump line that is {@code proj = 0.471 ≥ TAKEOFF_EDGE} on the FIRST
 *       grounded runup tick, so the 2026-07-31 hot-entry latch pressed jump same-tick — while the velocity
 *       was {@code (+0.053,+0.052)}, i.e. {@code vAlong = −0.052}, moving INTO the cell. Both phases fired
 *       in two ticks with no run-up, the sprint boost launched from the wrong sign, and the bot covered
 *       2.68 of the 4.53 blocks it needed. The latch's premise — "inbound momentum is about to carry the
 *       bot off the lip, so waiting a tick loses the jump" — is only true for {@code vAlong > 0}, which it
 *       never tested.</li>
 *   <li><b>{@code Descend} — the deadband disc.</b> At {@code (105,92,171)→(105,91,170)} the step phase
 *       zeroed forward inside {@code COLUMN_DEADBAND} of the target and drove at full throttle outside it.
 *       A disc centred on the target suppresses thrust only while the bot is NEAR the column, so momentum
 *       carried it out the FAR side and the servo re-engaged pushing further out. It grounded at
 *       {@code z=169.995} — five millimetres outside its own target cell — which the validity envelope
 *       correctly fail→HOLDs.</li>
 * </ul>
 *
 * Both assertions pin BEHAVIOUR through each move's own committing input (Parkour's jump, Descend's signed
 * forward) rather than the servo's numbers, per the {@link CarryArrestGateTest} precedent.
 */
class LipMarginTest {

    /** Pose/velocity-settable {@link BotSteering} that reports met geometry, so no plan need ever holds the
     *  runner and the phase under test is actually reached ({@link CarryArrestGateTest}'s double). */
    private static final class LipBot implements BotSteering {
        double x, y, z, vx, vz;
        boolean grounded = true;
        boolean jumping, sprinting;
        float forward, strafe;
        /** Commanded thrust direction — the sign of a re-centre lives ONLY here. */
        double faceDx = Double.NaN, faceDz = Double.NaN;

        LipBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        LipBot vel(double vx, double vz) { this.vx = vx; this.vz = vz; return this; }
        LipBot airborne() { this.grounded = false; return this; }

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
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setStrafe(float xxa) { strafe = xxa; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { }
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

    /** A straight segment between two feet-frame cell centres. */
    private static final class Seg implements SteerView {
        private final double sx, sy, sz, tx, ty, tz;
        Seg(double sx, double sy, double sz, double tx, double ty, double tz) {
            this.sx = sx; this.sy = sy; this.sz = sz; this.tx = tx; this.ty = ty; this.tz = tz;
        }
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
    }

    // ---- Parkour: the witnessed frame (158,112,114) → (158,111,109), a −z gap-4 falling jump ------------
    private static final int PFX = 158, PFY = 112, PFZ = 114;
    private static final int PTX = 158, PTY = 111, PTZ = 109;
    private static final int P_FROM_FOOT = 113, P_TO_FOOT = 112;

    private static Seg parkourSeg() {
        return new Seg(PFX + 0.5, P_FROM_FOOT, PFZ + 0.5, PTX + 0.5, P_TO_FOOT, PTZ + 0.5);
    }

    private static PhaseRunner parkourRunup() {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(MovementRegistry.PARKOUR.plan(PFX, PFY, PFZ, PTX, PTY, PTZ, P_FROM_FOOT, P_TO_FOOT));
        return runner;
    }

    @Test
    void backwardsHotEntryRecentresInsteadOfJumping() {
        // The measured pose to the digit: handed off across the −z corner, already past TAKEOFF_EDGE, with
        // the Diagonal's +x+z carry still on — so the along-line velocity points INTO the cell.
        LipBot bot = new LipBot().at(158.031, P_FROM_FOOT, 114.029).vel(0.053, 0.052);
        PhaseRunner runner = parkourRunup();

        assertFalse(runner.run(bot, parkourSeg()), "a re-centring runup is not done");
        assertFalse(runner.failed(), "re-centring is not an envelope failure — the bot is on its takeoff stand");
        assertFalse(bot.jumping, "a backwards hot entry must NOT launch: the envelope's reach assumes a sprint takeoff");
        assertTrue(bot.faceDz > 0, "the re-centre drives back toward the takeoff cell centre (+z here), not off the lip");
        assertTrue(bot.forward > 0, "and it walks, rather than coasting");
    }

    @Test
    void forwardHotEntryStillLaunchesSameTick() {
        // The 2026-07-31 ruling's own case, unchanged: past the trigger AND running at the gap, so waiting a
        // tick would coast the bot off the lip with no jump ever pressed.
        LipBot bot = new LipBot().at(158.5, P_FROM_FOOT, 114.029).vel(0.0, -0.12);
        PhaseRunner runner = parkourRunup();

        runner.run(bot, parkourSeg());
        assertTrue(bot.jumping, "a FORWARD hot entry keeps the same-tick launch the latch was built for");
    }

    @Test
    void aRecentredRunupThenTakesOffOnTheNormalTrigger() {
        // Tick 1 arms the re-centre; tick 2 finds the bot centred, which clears the latch; tick 3 is an
        // ordinary runup tick from the middle of the cell — a whole half-cell of runway ahead of the lip.
        LipBot bot = new LipBot().at(158.031, P_FROM_FOOT, 114.029).vel(0.053, 0.052);
        PhaseRunner runner = parkourRunup();
        runner.run(bot, parkourSeg());                       // arm
        bot.at(158.5, P_FROM_FOOT, 114.5).vel(0.0, 0.0);     // the re-centre reached the cell centre
        runner.run(bot, parkourSeg());                       // latch clears
        assertFalse(bot.jumping, "still short of TAKEOFF_EDGE — no launch from the centre");

        // Now run it up to the lip the way an aligned approach would. Sit CLEAR of TAKEOFF_EDGE rather than
        // exactly on it: `114.5 - 0.35` lands a few ulps SHORT of the trigger in double arithmetic, which
        // pins nothing but the rounding.
        bot.at(158.5, P_FROM_FOOT, 114.5 - (Parkour.TAKEOFF_EDGE + 0.05)).vel(0.0, -0.12);
        runner.run(bot, parkourSeg());
        assertTrue(bot.jumping, "past the edge and moving at the gap — the normal late takeoff fires");
    }

    // ---- Descend: the witnessed frame (105,92,171) → (105,91,170) --------------------------------------
    private static final int DFX = 105, DFY = 92, DFZ = 171;
    private static final int DTX = 105, DTY = 91, DTZ = 170;
    private static final int D_FROM_FOOT = 93, D_TO_FOOT = 92;

    private static Seg descendSeg() {
        return new Seg(DFX + 0.5, D_FROM_FOOT, DFZ + 0.5, DTX + 0.5, D_TO_FOOT, DTZ + 0.5);
    }

    private static PhaseRunner descendAtStep(LipBot bot) {
        PhaseRunner runner = new PhaseRunner();
        runner.begin(MovementRegistry.DESCEND.plan(DFX, DFY, DFZ, DTX, DTY, DTZ, D_FROM_FOOT, D_TO_FOOT));
        runner.run(bot, descendSeg());   // CLEAR: every need is met on the double → advance to STEP
        return runner;
    }

    @Test
    void airborneOvershootBrakesInsteadOfCoasting() {
        // The convicted tick: the bot has left the lip carrying its full walk-off speed and is one tick past
        // the target centre. The old disc read |error| = 0.21 > 0.15 and drove FORWARD; the projected stop
        // (position + AIR_COAST × velocity) is a full block beyond the column, so the arrive servo brakes.
        LipBot bot = new LipBot().at(105.441, D_TO_FOOT + 0.766, 170.295).vel(0.016, -0.102).airborne();
        PhaseRunner runner = descendAtStep(bot);
        bot.forward = Float.NaN;         // ignore whatever the CLEAR tick wrote

        runner.run(bot, descendSeg());
        assertTrue(bot.forward < 0.0f,
                "a projected overshoot must brake with REVERSE input; the old deadband disc thrust forward here");
    }

    @Test
    void aSettledBotOnItsColumnCommandsExactlyZero() {
        // The deadband survives where it belongs: at rest on the target column the projection is the bot's
        // own position, so the servo presses nothing (the vine-bounce ruling's load-bearing detail).
        LipBot bot = new LipBot().at(DTX + 0.5, D_TO_FOOT, DTZ + 0.5).vel(0.0, 0.0);
        PhaseRunner runner = descendAtStep(bot);
        bot.forward = Float.NaN;

        runner.run(bot, descendSeg());
        assertEquals(0.0f, bot.forward, 0.0f, "a settled, centred bot presses nothing");
    }
}
