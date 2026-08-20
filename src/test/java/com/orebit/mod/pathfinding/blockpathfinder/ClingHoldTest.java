package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SteerControl#clingHold} — the PLANLESS/HELD CLING (owner-ratified 2026-08-19, the vine-hang
 * wedge). A bot whose feet are inside a climbable slides out at vanilla's {@code -0.15}/t clamp on any
 * tick nobody presses an input, and the follower's planless states press only {@code setForward(0)} — so
 * a replan in flight walked the bot out the bottom of the very column its next plan was framed from.
 * Sneak IS the arrest ({@code isSuppressingSlidingDownLadder}); the cling presses it, and ONLY it.
 *
 * <p>Pins the mode's BEHAVIOR over an input-recording double (the {@code RestHoldTest} pattern): the
 * cling engages exactly where nothing else holds the bot up; every suppression gate (support underneath,
 * grounded, scaffolding's sneak-exemption) refuses; the §4 tag is stamped unconditionally
 * (DESIGN-servo-normalization.md — a drive that ran and chose to write nothing still names itself); and
 * the hold is purely VERTICAL — the climbable zero-horizontal-input ruling means the caller's own
 * {@code setForward(0)} is the whole horizontal story.
 */
class ClingHoldTest {

    /** A pose-settable {@link BotSteering} that records the written inputs (the {@code RestHoldTest}
     *  double, plus settable climbable/grounded/support facts — the four gates {@code clingHold} reads). */
    private static final class SettleBot implements BotSteering {
        double x, y, z, vx, vz;
        float forward, strafe;
        boolean jumping, sprinting, sneaking;
        boolean grounded, climbable, standable, scaffolding;
        double faceDx = Double.NaN, faceDz = Double.NaN;

        SettleBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }

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
        @Override public boolean standableBelow() { return standable; }
        @Override public boolean scaffoldingBelow() { return scaffolding; }
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

    /** THE CLING. Feet in a vine, nothing holding the bot up: sneak engages and the tick names itself. */
    @Test
    void feetInAVineWithNothingBelowSneaks() {
        SettleBot bot = new SettleBot().at(58.5, 170.5, 254.5);
        bot.climbable = true;   // feet inside the vine; grounded/standable/scaffolding all false
        assertTrue(SteerControl.clingHold(bot), "the cling reports it engaged");
        assertTrue(bot.sneaking, "sneak IS the arrest — the one input that zeroes the -0.15 slide");
        assertTrue(SteerControl.lastDrive.endsWith("hold:cling"),
                "the engaged cling tags its own tick (§4)");
    }

    /** Off a climbable there is nothing to arrest — no input, but the §4 tag is still stamped. */
    @Test
    void offAClimbableWritesNothingButStillTags() {
        SettleBot bot = new SettleBot().at(58.5, 170.5, 254.5);
        assertFalse(SteerControl.clingHold(bot), "nothing to hold — the cling declines");
        assertFalse(bot.sneaking, "no climbable, no arrest input");
        assertTrue(SteerControl.lastDrive.endsWith("hold:cling:dead"),
                "the drive ran and chose to write nothing — it still names itself (§4, before any early-out)");
    }

    /** Support underneath means the bot is not sliding anywhere; sneak would only arm the edge-guard. */
    @Test
    void aStandableBelowSuppressesTheCling() {
        SettleBot bot = new SettleBot().at(58.5, 170.5, 254.5);
        bot.climbable = true;
        bot.standable = true;   // something already holds us
        assertFalse(SteerControl.clingHold(bot), "a supported bot has nothing to cling against");
        assertFalse(bot.sneaking,
                "sneak here buys nothing and arms vanilla's maybeBackOffFromEdge against the next step-off");
    }

    /** A grounded bot in a vine cell (trunk vine over solid footing) is held by the floor, not the vine. */
    @Test
    void groundedInAVineNeverSneaks() {
        SettleBot bot = new SettleBot().at(58.5, 170.0, 254.5);
        bot.climbable = true;
        bot.grounded = true;
        assertFalse(SteerControl.clingHold(bot), "grounded means already held");
        assertFalse(bot.sneaking, "the floor is the hold; sneak would only arm the ledge edge-guard");
    }

    /** Scaffolding is sneak-EXEMPT in vanilla: sneak DESCENDS through it, the opposite of a hold. */
    @Test
    void scaffoldingBelowNeverSneaks() {
        SettleBot bot = new SettleBot().at(58.5, 170.5, 254.5);
        bot.climbable = true;
        bot.scaffolding = true;
        assertFalse(SteerControl.clingHold(bot), "scaffolding refuses the cling");
        assertFalse(bot.sneaking, "sneaking on scaffolding sinks the bot through the deck it is standing in");
    }

    /** The cling is purely VERTICAL (the climbable zero-horizontal-input ruling): thrust is untouched. */
    @Test
    void theClingWritesNoHorizontalInput() {
        SettleBot bot = new SettleBot().at(58.5, 170.5, 254.5);
        bot.climbable = true;
        bot.forward = Float.NaN;   // poisoned: any write is a failure
        bot.strafe = Float.NaN;
        assertTrue(SteerControl.clingHold(bot), "the cling engages…");
        assertTrue(Float.isNaN(bot.forward),
                "…but never writes forward — the caller's own setForward(0) is the whole horizontal story");
        assertTrue(Float.isNaN(bot.strafe), "…and never strafes");
        assertTrue(Double.isNaN(bot.faceDx), "…and never yaws");
    }
}
