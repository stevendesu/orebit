package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * The swim cursor-advance contract: <b>arrival is the feet CELL, nothing else</b>
 * ({@code Swim.reachedSwim}).
 *
 * <p>This file replaces {@code SwimCeilingReachTest}, which pinned two CLAMPS that no longer exist. Those
 * clamps were corrections to a set-point that was wrong: the ride height was {@code wy + 1.0}, the feet
 * cell's CEILING, and the reach window {@code |botY - target| < 0.6} therefore straddled the cell boundary
 * in both directions. {@link SteerControl#SWIM_RIDE} now parks the bot at {@code wy + 0.2} with a
 * {@code ±0.2} dead-band — {@code [wy+0.0, wy+0.4]}, wholly inside the cell — so the height question and the
 * cell question became the same question and the clamps had nothing left to correct.
 *
 * <p>The fixture is kept because the geometry is real, read out of the flagship save at {@code x=154 z=103}:
 * water at {@code y=-7} (the top of the fall), air at {@code y=-6}, tuff at {@code y=-5}. It is the shape
 * that produced BOTH historical failures, so both are pinned below as regressions:
 * <ul>
 *   <li><b>378-tick hold (2026-08-07)</b> — a 1.8-tall body under a ceiling whose underside is {@code -5.0}
 *       tops out at {@code botY = -6.800}, while the unclamped test demanded {@code botY > -6.6}. The
 *       ceiling clamp fixed it by computing {@code (wy+2) - 1.8 == wy + 0.2} — which is exactly the ride
 *       height now used everywhere, so this case is no longer special at all.</li>
 *   <li><b>Flagship {@code Diagonal} envelope failure at {@code (154,-8,103)} (2026-08-15)</b> — that same
 *       clamped target opened the window at {@code -7.4}, so the bot was accepted at {@code botY = -7.289}
 *       with its feet in cell {@code -8}, one BELOW the waypoint, and the next step failed on its first
 *       tick. The cell test refuses that outright.</li>
 * </ul>
 */
class SwimReachCellTest {

    /** The waterfall-top cell: feet in the top water block, one air cell above the head, then solid. */
    private static final int WX = 154, WY = -7, WZ = 103;

    @Test
    void arrivalIsTheFeetCell_theCeilingCaseIsNoLongerSpecial() {
        // -6.800 is where a 1.8-tall body under the tuff at -5.0 actually tops out, and it is also exactly
        // the SWIM_RIDE height wy+0.2. It floors to cell -7: arrived.
        Bot b = new Bot(WX + 0.5, -6.800, WZ + 0.5);
        b.ceiling = WY + 2;
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ),
                "wy+0.2 under a real ceiling is the ride height AND inside the waypoint cell");
    }

    @Test
    void theWholeDeadBandIsInsideTheCell() {
        // SWIM_RIDE 0.2 with WATER_RISE_DEADBAND 0.2 => [wy+0.0, wy+0.4]. Every point of it must arrive,
        // or the bot chatters between "arrived" and "not arrived" while merely holding station.
        for (double off : new double[] { 0.0, 0.1, 0.2, 0.3, 0.399 }) {
            Bot b = new Bot(WX + 0.5, WY + off, WZ + 0.5);
            assertTrue(Swim.reachedSwim(b, WX, WY, WZ), "wy+" + off + " is inside the feet cell");
        }
    }

    @Test
    void aBotOneCellLowDoesNotArrive_theFlagshipDiagonalRegression() {
        // The measured pose at the (154,-8,103) failure: feet cell -8, one below the waypoint's -7. The old
        // clamped window accepted it and handed the Diagonal a frame it failed on immediately.
        Bot b = new Bot(WX + 0.5, -7.289, WZ + 0.5);
        b.ceiling = WY + 2;
        assertFalse(Swim.reachedSwim(b, WX, WY, WZ),
                "botY=-7.289 has its feet in cell -8; accepting it there is what broke the Diagonal handoff");
    }

    @Test
    void aBotOneCellHighDoesNotArrive_theSwimCourseEntryRegression() {
        // The mirror case, measured on SwimCourse `cross`: the surface clamp put the target at 160.889 and
        // the bot was accepted at 161.000 while still standing DRY on the lip, one cell ABOVE the waypoint.
        Bot b = new Bot(WX + 0.5, WY + 1.0, WZ + 0.5);
        assertFalse(Swim.reachedSwim(b, WX, WY, WZ),
                "wy+1.0 is the cell ABOVE the waypoint — the old ride height was itself out of frame");
    }

    @Test
    void proneAndUprightAgree_theBiasIsIdentityNow() {
        // SUBMERGE_BIAS was 0.8 purely to drag wy+1.0 down to wy+0.2; both poses now ride there directly,
        // so the bias argument must not move the verdict.
        Bot b = new Bot(WX + 0.5, WY + 0.2, WZ + 0.5);
        b.prone = true;
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ, SteerControl.SUBMERGE_BIAS), "prone arrives at wy+0.2");
        b.prone = false;
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ), "upright arrives at the same height");
    }

    @Test
    void aPartialTopCellArrivesWhenRestingOnItsFloor() {
        // The residual the SURFACE clamp explicitly left open: a nearly-full top cell where a bot resting on
        // the floor sat further than the old REACHED_Y from the ride height. Resting there puts the feet in
        // the waypoint cell, so the cell test closes it for free.
        Bot b = new Bot(WX + 0.5, WY + 0.0, WZ + 0.5);
        b.surface = 0.9;
        assertTrue(Swim.reachedSwim(b, WX, WY, WZ), "resting on a partial top cell's floor is arrival");
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
