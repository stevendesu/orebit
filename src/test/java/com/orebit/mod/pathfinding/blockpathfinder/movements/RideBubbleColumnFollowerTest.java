package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Headless proof of the {@link RideBubbleColumn} FOLLOWER's arrival gate ({@link RideBubbleColumn#reached}) —
 * the predicate {@code BotNavigator} advances waypoints on — and of the ENTER phase's <b>planned-column
 * targeting</b>. Both read only the MC-free {@link BotSteering} seam, so no Minecraft is needed: a
 * {@link FakeBot} double supplies pose/velocity/medium plus a settable set of live up-columns.
 *
 * <p>Pins the <b>medium-aware settle</b> fix: a grounded dry-land BANK exit settles on {@code grounded()}
 * ALONE (its resting {@code getDeltaMovement().y} ≈ −0.078 — gravity×drag, never zeroed by ground collision —
 * exceeds {@code SETTLE_VELY}=0.06, so the old {@code |velY|<SETTLE_VELY} gate NEVER fired on land and froze the
 * follower forever, no recovery); the velY-stillness gate applies ONLY to the buoyant in-water float-out.
 *
 * <p>Pins the <b>planned-column ENTER</b> fix (2026-08-15, the SwimCourse {@code swimmaze} ejection): with TWO
 * up-columns adjacent to the ride's start node the drive must steer into the one the plan committed to —
 * derived from the plan's own (from, to) cells — never the first one a fixed-order cardinal scan happens to
 * find, and the phase must not advance while the bot's feet sit in a merely-adjacent wall column.
 */
class RideBubbleColumnFollowerTest {

    private static final RideBubbleColumn RIDE = new RideBubbleColumn();

    // The exit cell the ride reports as its target (an arbitrary bank/float-out node).
    private static final int WX = 4, WY = 10, WZ = 8;

    // ---- ENTER planned-column targeting (the swimmaze geometry, verbatim from the trace) ----------------
    //
    // Start node floor (18,156,102) / feet 157; exit node floor (18,159,104) / feet 160. TWO live up-columns
    // adjacent to the start: (19,*,102) — a maze WALL, and FIRST in the old fixed cardinal scan order — and
    // (18,*,103), the column the plan actually rides (the only cell cardinal-adjacent to both start and exit).

    private static final int FX = 18, FY = 156, FZ = 102;
    private static final int TX = 18, TY = 159, TZ = 104;
    private static final int FROM_FOOT_Y = 157, TO_FOOT_Y = 160;

    /** The ride's segment in feet-frame coordinates (only read by drives this test doesn't assert on). */
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

    private static FakeBot mazeBot(double x, double y, double z) {
        FakeBot b = new FakeBot(x, y, z);
        b.inWater = true;
        b.columns.add(new int[]{19, 102});  // the wall column — +X, FIRST in the old cardinal scan
        b.columns.add(new int[]{18, 103});  // the planned ride column — +Z
        return b;
    }

    @Test
    void enterSteersIntoThePlannedColumn_notTheFirstAdjacentOne() {
        // THE swimmaze BUG CASE: bot at the ride's start feet cell, two adjacent columns. The old drive scanned
        // {+X,-X,+Z,-Z} from the bot's own cell and steered +X into the wall column (trace: x 18.5→19.3 while
        // the segment was pure +Z, ejected at the surface). The fixed drive must aim at (18.5, *, 103.5).
        FakeBot b = mazeBot(FX + 0.5, FROM_FOOT_Y + 0.2, FZ + 0.5);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(RIDE.plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y));
        runner.run(b, new Seg());

        assertTrue(b.faceDz > 0, "ENTER must steer +Z toward the PLANNED column (18,*,103)");
        assertTrue(Math.abs(b.faceDx) < 0.25, "ENTER must not steer toward the +X wall column: dx=" + b.faceDx);
        assertTrue(b.forward > 0.9f, "ENTER drives forward into the column");
        assertFalse(b.sprinting, "the conveyor entry is never sprinted");
    }

    @Test
    void enterKeepsSteeringToPlannedColumnFromInsideAWrongOne() {
        // The bot has drifted so its feet sit IN the wall column (19,*,102) — a live up-column, but not the
        // plan's. Pre-fix the ENTER advanceWhen fired on ANY bubble feet cell, handing RIDE the wrong column to
        // centre on. Post-fix the phase must stay in ENTER and keep steering toward (18.5, *, 103.5): -X, +Z.
        FakeBot b = mazeBot(19.5, FROM_FOOT_Y + 0.2, 102.5);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(RIDE.plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y));
        runner.run(b, new Seg());
        runner.run(b, new Seg()); // a second tick: were the phase (wrongly) advanced, RIDE would centre on own cell

        assertTrue(b.faceDx < 0, "still ENTER: steer -X back out of the wall column toward the planned one");
        assertTrue(b.faceDz > 0, "still ENTER: steer +Z toward the planned column");
        assertTrue(b.forward > 0.9f, "still driving (RIDE's own-cell centring would be ~0 forward when centred)");
    }

    @Test
    void enterAdvancesOnceInsideThePlannedColumn() {
        // Feet in the planned column (18,*,103): ENTER advances and RIDE holds centre on the bot's own cell —
        // observable as a (near-)zero forward for a centred bot, where ENTER would drive forward hard.
        FakeBot b = mazeBot(18.5, FROM_FOOT_Y + 0.2, 103.5);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(RIDE.plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y));
        runner.run(b, new Seg()); // ENTER drives, then advanceWhen fires (feet in the planned column)
        runner.run(b, new Seg()); // RIDE tick: centred → hold, no forward push

        assertTrue(b.forward < 0.1f, "RIDE holds a centred bot (forward ~0); ENTER would still drive ~1.0");
    }

    @Test
    void groundedLandExit_reachedEvenThoughRestingVelYExceedsSettleGate() {
        // THE BUG CASE: a grounded bank bot at the exit cell with the vanilla resting velY ≈ −0.078 (> 0.06).
        // Pre-fix this returned FALSE (velY gate never satisfied on land) → follower froze. Now: grounded settles.
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = true;
        b.inWater = false;
        b.velY = -0.078;
        assertTrue(RIDE.reached(b, WX, WY, WZ, null),
                "a grounded land exit is reached BY being grounded, despite the resting gravity×drag velY");
    }

    @Test
    void inWaterFloatOut_reachedWhenBuoyantVelYIsStill() {
        // The buoyant water float-out: in water, bobbing within the ±0.04 buoyancy increment (|velY| < 0.06).
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = false;
        b.inWater = true;
        b.velY = 0.04;
        assertTrue(RIDE.reached(b, WX, WY, WZ, null), "an in-water exit with a bled (still) velY is reached");
    }

    @Test
    void inWaterStillAscending_notReachedWhileEjectionMomentumRemains() {
        // In water but still being launched by the conveyor (|velY| > 0.06) — the stillness gate keeps it unsettled
        // so the next lateral move does not start mid-launch.
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = false;
        b.inWater = true;
        b.velY = 0.5;
        assertFalse(RIDE.reached(b, WX, WY, WZ, null), "a still-ejecting in-water bot has not settled");
    }

    @Test
    void notAtExitCell_neverReached() {
        // Off the exit column entirely — grounded and settled, but the wrong x/z, so not reached.
        FakeBot b = new FakeBot(WX + 2.5, WY, WZ + 0.5);
        b.grounded = true;
        b.inWater = false;
        b.velY = -0.078;
        assertFalse(RIDE.reached(b, WX, WY, WZ, null), "not at the exit cell → never reached");
    }

    @Test
    void airborneAtExitCell_notReached() {
        // At the exit x/z but neither grounded nor in water (mid-fall) → not settled in either medium.
        FakeBot b = new FakeBot(WX + 0.5, WY, WZ + 0.5);
        b.grounded = false;
        b.inWater = false;
        b.velY = -0.02; // even a small velY: no medium to settle in
        assertFalse(RIDE.reached(b, WX, WY, WZ, null), "airborne at the exit cell is not settled in any medium");
    }

    /** Minimal {@link BotSteering} double: settable pose/velocity/medium, a settable set of live up-column
     *  (x,z) cells, and recorded steering outputs (faceDx/faceDz/forward/sprinting) so the ENTER-targeting
     *  tests can assert which way a drive steered. All other seam methods are inert stubs. */
    private static final class FakeBot implements BotSteering {
        double x, y, z;
        double velY;
        boolean grounded, inWater;
        final java.util.List<int[]> columns = new java.util.ArrayList<>(); // live up-columns as (x,z), any y
        double faceDx, faceDz;   // last faceHorizontally
        float forward;           // last setForward
        boolean sprinting;

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
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public boolean prone() { return false; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) {
            for (int[] c : columns) {
                if (c[0] == x && c[1] == z) return true;
            }
            return false;
        }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }
}
