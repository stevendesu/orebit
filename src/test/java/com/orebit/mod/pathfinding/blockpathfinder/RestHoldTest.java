package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SteerControl#restHold} — the ARRIVAL-SETTLE hold (owner-ratified 2026-08-19,
 * DESIGN-servo-normalization.md §2.6): on plan completion the bot drives to rest on the CENTRE of its
 * final plan cell instead of parking wherever it first clipped the arrival radius. Pins the mode's
 * BEHAVIOR over an input-recording double (the {@code CarryArrestGateTest} pattern), not the constants:
 * off-centre the servo pulls TOWARD the anchor; centred and still it writes explicit zero inputs and
 * holds the last yaw (the rest state); displaced past the anchor the pull REVERSES, because the law is
 * position-anchored — an external push is actively re-centred, never coasted with.
 */
class RestHoldTest {

    /** The specimen frame: the icediag conviction's final plan cell (45,151,45) — centre (45.5, 45.5). */
    private static final double AX = 45.5, AZ = 45.5;

    /** A pose/velocity-settable {@link BotSteering} that records the written inputs and the commanded
     *  facing (the {@code CarryArrestGateTest} lesson: a double that discards {@code faceHorizontally}
     *  can assert THAT a hold ran but never WHICH WAY it pushed). */
    private static final class SettleBot implements BotSteering {
        double x, y, z, vx, vz;
        float forward, strafe;
        boolean jumping, sprinting, sneaking;
        double faceDx = Double.NaN, faceDz = Double.NaN;

        SettleBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        SettleBot vel(double vx, double vz) { this.vx = vx; this.vz = vz; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return vx; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return vz; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return true; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setStrafe(float xxa) { strafe = xxa; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
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
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; } // stone
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    @Test
    void anOffCentreArrivalIsPulledTowardTheAnchor() {
        // The convicted parking spot: the near corner of the final cell (icediag stopped at x≈45.19).
        SettleBot bot = new SettleBot().at(45.2, 151, 45.5).vel(0, 0);
        SteerControl.restHold(bot, AX, AZ);

        assertTrue(bot.faceDx > 0, "the correction points +x, back toward the cell centre");
        assertTrue(bot.forward > 0, "the settle DRIVES toward the anchor — it is not a dead-band no-op");
        assertEquals(0.0, bot.strafe, 1e-6, "a pure-x error has no cross component to strafe");
        assertTrue(SteerControl.lastDrive.endsWith("hold:rest"), "the settle tags its own ticks (§4)");
    }

    @Test
    void aCentredStillBotRestsWithExplicitZeroInputsAndHeldYaw() {
        SettleBot bot = new SettleBot().at(AX, 151, AZ).vel(0, 0);
        SteerControl.restHold(bot, AX, AZ);

        assertEquals(0.0f, bot.forward, "quiescent: explicit zero, never a stale key");
        assertEquals(0.0f, bot.strafe, "quiescent: explicit zero, never a stale key");
        assertTrue(Double.isNaN(bot.faceDx), "at rest ON the anchor the last yaw is simply held");
        assertTrue(SteerControl.lastDrive.endsWith("hold:rest:dead"), "the quiescent tick tags :dead");
    }

    @Test
    void aPushPastTheAnchorReversesThePull() {
        // Displaced beyond the centre and still carrying outward: a velocity-only law would fight the
        // carry and stop wherever it died; the anchored law pulls BACK to the centre (§1 class 1).
        SettleBot bot = new SettleBot().at(45.8, 151, 45.5).vel(0.1, 0);
        SteerControl.restHold(bot, AX, AZ);

        assertTrue(bot.faceDx < 0, "the correction points −x — back INTO the cell, against the carry");
        assertTrue(bot.forward > 0, "full-key re-centre: the push is answered, not coasted with");
    }

    @Test
    void aSmallResidualErrorKeepsSemanticYawAtTheAnchor() {
        // Inside the FACE_ERR_THRESHOLD saturation point the head must NOT chase the error vector (the
        // no-pirouettes ruling, §2.2): the facing is the SEMANTIC anchor direction, the hands correct.
        SettleBot bot = new SettleBot().at(45.54, 151, 45.5).vel(0, 0);
        SteerControl.restHold(bot, AX, AZ);

        assertTrue(bot.faceDx < 0, "faces the anchor (−x of the bot), the semantic heading");
        assertTrue(bot.forward > 0 && bot.forward < 1.0f,
                "a sub-saturation correction drives a proportional, not full, key");
        assertTrue(SteerControl.lastDrive.endsWith("hold:rest"), "still a live correction, not :dead");
    }
}
