package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;

/**
 * Headless proof of the {@link RideBubbleColumn} FOLLOWER's arrival gate ({@link RideBubbleColumn#reached}) —
 * the predicate {@code BotNavigator} advances waypoints on. It reads only the MC-free {@link BotSteering} seam,
 * so it needs no Minecraft: a {@link FakeBot} double supplies an arbitrary pose/velocity/medium and the test
 * asserts whether the exit cell is considered reached.
 *
 * <p>Pins the <b>medium-aware settle</b> fix: a grounded dry-land BANK exit settles on {@code grounded()}
 * ALONE (its resting {@code getDeltaMovement().y} ≈ −0.078 — gravity×drag, never zeroed by ground collision —
 * exceeds {@code SETTLE_VELY}=0.06, so the old {@code |velY|<SETTLE_VELY} gate NEVER fired on land and froze the
 * follower forever, no recovery); the velY-stillness gate applies ONLY to the buoyant in-water float-out.
 */
class RideBubbleColumnFollowerTest {

    private static final RideBubbleColumn RIDE = new RideBubbleColumn();

    // The exit cell the ride reports as its target (an arbitrary bank/float-out node).
    private static final int WX = 4, WY = 10, WZ = 8;

    @Test
    void groundedLandExit_reachedEvenThoughRestingVelYExceedsSettleGate() {
        // THE BUG CASE: a grounded bank bot at the exit cell with the vanilla resting velY ≈ −0.078 (> 0.06).
        // Pre-fix this returned FALSE (velY gate never satisfied on land) → follower froze. Now: grounded settles.
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = true;
        b.inWater = false;
        b.velY = -0.078;
        assertTrue(RIDE.reached(b, WX, WY, WZ),
                "a grounded land exit is reached BY being grounded, despite the resting gravity×drag velY");
    }

    @Test
    void inWaterFloatOut_reachedWhenBuoyantVelYIsStill() {
        // The buoyant water float-out: in water, bobbing within the ±0.04 buoyancy increment (|velY| < 0.06).
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = false;
        b.inWater = true;
        b.velY = 0.04;
        assertTrue(RIDE.reached(b, WX, WY, WZ), "an in-water exit with a bled (still) velY is reached");
    }

    @Test
    void inWaterStillAscending_notReachedWhileEjectionMomentumRemains() {
        // In water but still being launched by the conveyor (|velY| > 0.06) — the stillness gate keeps it unsettled
        // so the next lateral move does not start mid-launch.
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = false;
        b.inWater = true;
        b.velY = 0.5;
        assertFalse(RIDE.reached(b, WX, WY, WZ), "a still-ejecting in-water bot has not settled");
    }

    @Test
    void notAtExitCell_neverReached() {
        // Off the exit column entirely — grounded and settled, but the wrong x/z, so not reached.
        FakeBot b = new FakeBot(WX + 2.5, WY, WZ + 0.5);
        b.grounded = true;
        b.inWater = false;
        b.velY = -0.078;
        assertFalse(RIDE.reached(b, WX, WY, WZ), "not at the exit cell → never reached");
    }

    @Test
    void airborneAtExitCell_notReached() {
        // At the exit x/z but neither grounded nor in water (mid-fall) → not settled in either medium.
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = false;
        b.inWater = false;
        b.velY = -0.02; // even a small velY: no medium to settle in
        assertFalse(RIDE.reached(b, WX, WY, WZ), "airborne at the exit cell is not settled in any medium");
    }

    /** Minimal {@link BotSteering} double: settable pose/velocity/medium; the reached() test reads only
     *  footX/footZ (derived from x/z), grounded, inWater, velY. All other seam methods are inert stubs. */
    private static final class FakeBot implements BotSteering {
        double x, y, z;
        double velY;
        boolean grounded, inWater;

        FakeBot(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return velY; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return inWater; }
        @Override public boolean inLava() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public boolean prone() { return false; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }
}
