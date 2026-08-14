package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * The flagship waterfall TOP, at 1×1 scale (2026-08-07). The bot climbed the column correctly, arrived at the
 * last swim node, and then held for 378 ticks because the arrival test asked for a height the ceiling made
 * physically impossible.
 *
 * <p><b>The real geometry</b>, read out of the save at {@code x=154 z=103}: water at {@code y=-7} (the top of
 * the fall), air at {@code y=-6}, tuff at {@code y=-5}. The plan's last swim waypoint is the feet cell
 * {@code (154,-7,103)}; the {@code Diagonal} exit onto a deepslate ledge at {@code (155,-8,104)} is the very
 * next step. A 1.8-tall body under a ceiling whose underside is {@code -5.0} tops out at {@code botY = -6.800}
 * — and the un-clamped test wanted {@code |botY - (wy+1)| < 0.6}, i.e. {@code botY > -6.6}. Short by 0.2
 * blocks, forever, with {@code holdDepth} holding jump into a vertical collision the whole time
 * ({@code dm.y} pinned at exactly {@code -0.0050}).
 *
 * <p>These tests pin both halves of the fix: the capped cell now arrives, and the uncapped case is left
 * exactly as it was (the clamp must be a no-op wherever there is headroom, or it would re-tune a climb
 * cadence that the same flagship run showed working at 7–9 ticks per cell).
 */
class SwimCeilingReachTest {

    /** The waterfall-top cell: feet in the top water block, one air cell above the head, then solid. */
    private static final int WX = 154, WY = -7, WZ = 103;

    @Test
    void aCeilingCappedSurfaceCellIsReachableAtTheHeightTheBotCanActuallyHold() {
        Bot b = new Bot(WX + 0.5, -6.800, WZ + 0.5);
        b.ceiling = WY + 2;                                  // tuff at y=-5, underside -5.0
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ),
                "a 1.8-tall body under a ceiling at -5.0 tops out at exactly -6.800; that IS arrival here, "
                        + "and demanding the nominal -6.0 wedged the flagship for 378 ticks");
    }

    @Test
    void theClampIsANoOpWhenThereIsHeadroom() {
        // Same cell, no ceiling: the nominal target -6.0 stands, so a bot still 0.8 below it has NOT arrived
        // and keeps rising — byte-identical to the behaviour that climbed the column correctly.
        Bot b = new Bot(WX + 0.5, -6.800, WZ + 0.5);
        b.ceiling = Integer.MIN_VALUE;                       // open water above
        assertFalse(Swim.reachedSwim(b, WX, WY, WZ),
                "with headroom the bot can and should keep rising to the nominal ride height");

        b.y = -6.500;                                        // within 0.6 of -6.0
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ), "the open-water threshold is unchanged");
    }

    /** The clamp must not let a bot claim a cell it is nowhere near vertically — a capped ceiling lowers the
     *  bar to the attainable height, it does not remove it. */
    @Test
    void theClampStillRejectsABotFarBelowTheCappedHeight() {
        Bot b = new Bot(WX + 0.5, -7.900, WZ + 0.5);         // more than REACHED_Y below the -6.8 cap
        b.ceiling = WY + 2;
        assertFalse(Swim.reachedSwim(b, WX, WY, WZ),
                "clamping the target must not turn the Y term off");
    }

    /** Horizontal is untouched by any of this: the wrong column is never arrival, capped or not. */
    @Test
    void theWrongColumnIsNeverReached() {
        Bot b = new Bot(WX + 1.5, -6.800, WZ + 0.5);
        b.ceiling = WY + 2;
        assertFalse(Swim.reachedSwim(b, WX, WY, WZ), "footX must still match");
    }

    /**
     * A PRONE bot is 0.6 tall, so the same ceiling caps it far higher than an upright one — high enough that
     * the clamp never binds. Pins that the pose is read rather than 1.8 being hardcoded.
     */
    @Test
    void theProneCapUsesTheProneHeight() {
        Bot b = new Bot(WX + 0.5, WY + 1.0 - SteerControl.SUBMERGE_BIAS, WZ + 0.5);
        b.prone = true;
        b.ceiling = WY + 2;
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ, SteerControl.SUBMERGE_BIAS),
                "a 0.6-tall body has room to spare under this ceiling; the clamp must not bind on it");
    }

    // ---- The SURFACE clamp: the hydrostatic mirror, added 2026-08-14 ----------------------------------
    //
    // Same predicate, same failure, other axis. At the BASE of a waterfall the spreading apron is thin:
    // (247,51,16) is water[level=3] — surface ~0.56 up the cell — over stone at (247,50,16), air above. The
    // bot swam down and grounded on its OWN waypoint at botY=51.000, satisfying atWaypoint and settled(),
    // then held 5200 ticks. `wy + 1` is a RIDE height and a partial top cell has no such height to offer,
    // exactly as the tuff overhead had none. Nothing logged, because this predicate is not an envelope.
    private static final int SX = 247, SY = 51, SZ = 16;

    @Test
    void aBotRestingOnTheFloorOfAThinApronHasArrived() {
        Bot b = new Bot(SX + 0.5, 51.000, SZ + 0.5);
        b.surface = 5.0 / 9.0;                               // water[level=3] -> amount 5 -> 5/9 of a block
        assertTrue(Swim.reachedSwim(b, SX, SY, SZ),
                "the nominal ride height is hydrostatically unreachable here; resting on the floor IS arrival");
    }

    @Test
    void theSurfaceClampIsANoOpInAFullColumn() {
        // getHeight() reports 1.0 wherever the same fluid continues above, so every mid-column node keeps
        // the old target to the digit — the byte-identity guard for the tuned open-water climb cadence.
        Bot b = new Bot(SX + 0.5, 51.000, SZ + 0.5);         // surface stays 1.0
        assertFalse(Swim.reachedSwim(b, SX, SY, SZ),
                "a full column still demands the ride height — the bot can reach it, so it must keep rising");
    }

    @Test
    void theLoweredTargetStillAdmitsABotThatDoesFloat() {
        // The clamp only ever LOWERS the bar, so a breached bot cannot be locked out by it.
        Bot b = new Bot(SX + 0.5, 51.900, SZ + 0.5);
        b.surface = 5.0 / 9.0;
        assertTrue(Swim.reachedSwim(b, SX, SY, SZ),
                "breached at wy+0.9 against a target lowered to wy+0.56 — still inside REACHED_Y");
    }

    /** Minimal BotSteering fake: position, pose, a solid ceiling row, and the feet cell's fluid surface. */
    private static final class Bot implements BotSteering {
        double x, y, z;
        boolean prone;
        int ceiling = Integer.MIN_VALUE;
        /** Feet-cell fluid surface height. {@code 1.0} == "full column", the default that makes the SURFACE
         *  clamp a no-op — which is why every ceiling test above is unaffected by it. */
        double surface = 1.0;

        Bot(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

        @Override public double fluidTopAt(int bx, int by, int bz) { return surface; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return false; }
        @Override public boolean inWater() { return true; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return prone; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int bx, int by, int bz) { return by == ceiling; }
        @Override public boolean airAt(int bx, int by, int bz) { return by != ceiling; }
        @Override public boolean movementBlockedAt(int bx, int by, int bz, int dx, int dz) { return false; }
        @Override public boolean swimHazardAt(int bx, int by, int bz) { return false; }
        @Override public boolean bubbleUpAt(int bx, int by, int bz) { return false; }
        @Override public void mine(int bx, int by, int bz) { }
        @Override public void place(int bx, int by, int bz) { }
        @Override public void setDoorOpen(int bx, int by, int bz, boolean open) { }
        @Override public boolean doorOpenAt(int bx, int by, int bz) { return false; }
        @Override public double slipperinessAt(int bx, int by, int bz) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int bx, int by, int bz) { return false; }
    }
}
