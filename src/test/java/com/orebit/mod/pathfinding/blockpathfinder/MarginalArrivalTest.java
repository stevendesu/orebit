package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The <b>delivery invariant</b> ({@link Movement#deliverable}, folded into the default
 * {@link Movement#entryReady} — owner-ratified 2026-08-20): a grounded arrival only tees up its
 * successor when a ONE-TICK velocity projection ({@code x + velX}, {@code z + velZ}) still lands in the
 * waypoint cell. Owner philosophy — no envelope margins: "if the bot needs to be in a particular
 * position, the prior move should have delivered the bot to that position."
 *
 * <p><b>The run-2 conviction</b> (ReplanCourse forensics, 2026-08-20): a Traverse completed at
 * {@code z=516.007} with {@code velZ=-0.015} — block-exact, grounded, so the old cell-only
 * {@code entryReady} accepted it and the cursor advanced. Physics then carried the bot to
 * {@code z=515.992} on the very next tick — the FIRST tick the successor's {@code failWhen} evaluated —
 * and the successor was executing from outside the frame its whole plan was built on: the envelope
 * fail→HOLD one tick after a "clean" arrival. Exactly one tick of projection is therefore the right
 * horizon — a longer GROUND_COAST horizon would refuse ~any half-cell arrival (1.20 × 0.216 ≈ 0.26 of
 * coast); the full-coast question belongs to {@code stepOffGate}/{@code arrestCarryFrom}, which the
 * delivery invariant buys its one tick.
 *
 * <p>The double is {@code CarryArrestGateTest}'s CarryBot (pose AND velocity settable, foot cells via
 * {@code Math.floor}), extended with an in-water flag for the medium-exemption case.
 */
class MarginalArrivalTest {

    /** The run-2 waypoint: the completed Traverse's stand cell (feet frame). */
    private static final int WX = 100, WY = 64, WZ = 516;

    /** Pose/velocity-settable {@link BotSteering} double (the {@code CarryArrestGateTest} pattern). */
    private static final class CarryBot implements BotSteering {
        double x, y, z, vx, vz;
        boolean grounded = true;
        boolean water;

        CarryBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        CarryBot vel(double vx, double vz) { this.vx = vx; this.vz = vz; return this; }
        CarryBot swimming() { this.grounded = false; this.water = true; return this; }

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
        @Override public boolean inWater() { return water; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean sprinting) { }
        @Override public void setJumping(boolean jumping) { }
        @Override public void setSneak(boolean sneaking) { }
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

    /**
     * A marginal arrival: as deep into the cell as the horizontal band allows, carrying enough speed that
     * the one-tick projection still leaves the cell.
     *
     * <p>RE-BASED 2026-08-25. The original fixture was the witnessed run-2 pose verbatim — {@code z=516.007}
     * with {@code velZ=-0.015}, a bot whose CENTRE had barely crossed the cell boundary. That pose can no
     * longer be an arrival at all: {@link Movement#atWaypoint} now requires the whole 0.6-wide box inside
     * the cell, so a move cannot complete before {@code z >= 516.3}. Left as it was, these tests would have
     * been asserting the delivery invariant on a pose the containment band already refuses — green for the
     * wrong reason, and blind to the invariant actually regressing.
     *
     * <p>The invariant itself is NOT subsumed and still needs pinning: containment bounds POSITION, not
     * speed. From the band edge at cruise ({@code SERVO_GROUND_CRUISE} 0.35) the one-tick projection is
     * {@code 516.32 - 0.35 = 515.97} — still out of the cell, still a delivery the successor cannot use.
     * Same conviction, on a pose that can now occur.
     */
    private static CarryBot marginal() {
        return new CarryBot().at(WX + 0.5, WY, WZ + 0.32).vel(0, -0.35);
    }

    @Test
    void theRunTwoMarginalArrivalNoLongerTeesUpItsSuccessor() {
        // z=516.007, velZ=-0.015: the projection (515.992) leaves the cell on the successor's very
        // first failWhen tick. Under the pre-invariant cell-only entryReady this pose was accepted
        // (reached == true) and the envelope convicted the successor one tick later — the recorded
        // fails-before case.
        assertFalse(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ, MovementRegistry.DESCEND),
                "a one-tick projection out of the cell (z 516.007 + velZ -0.015 -> 515.992) is not a"
                        + " delivered entry — accepting it hands the Descend a frame physics vacates"
                        + " before its first failWhen tick (the run-2 conviction)");
    }

    @Test
    void theMirrorCarryTowardTheCellCentreStaysDelivered() {
        // Same magnitude, opposite sign: the projection (516.67) stays inside the cell — refusing
        // this would be a false refusal of a perfectly delivered arrival.
        CarryBot bot = new CarryBot().at(WX + 0.5, WY, WZ + 0.32).vel(0, +0.35);
        assertTrue(MovementRegistry.TRAVERSE.reached(bot, WX, WY, WZ, MovementRegistry.DESCEND),
                "the +velZ mirror projects INTO the cell (516.67) and must stay an accepted delivery");
    }

    @Test
    void anOrdinaryMidCellArrivalIsUntouched() {
        // THE regression guard: the overwhelmingly common case — cursor advances mid-cell at cruise —
        // must be byte-identical. Neither a residual drift nor full walk cruise can project a
        // mid-cell bot out of its cell in one tick.
        CarryBot drift = new CarryBot().at(WX + 0.5, WY, WZ + 0.5).vel(0, -0.015);
        assertTrue(MovementRegistry.TRAVERSE.reached(drift, WX, WY, WZ, MovementRegistry.DESCEND),
                "a centred arrival with residual drift projects to 516.485 — still delivered");

        CarryBot cruise = new CarryBot().at(WX + 0.5, WY, WZ + 0.5).vel(0, -0.2);
        assertTrue(MovementRegistry.TRAVERSE.reached(cruise, WX, WY, WZ, MovementRegistry.DESCEND),
                "a centred arrival at walk cruise projects to 516.3 — still delivered; anything else"
                        + " would stall every ordinary mid-cell advance");
    }

    @Test
    void onlyDefaultEntrySuccessorsBindTheDeliveryInvariant() {
        // Successor scoping: the six permissive entryReady overrides (the swim family + the bubble
        // ride) establish their own entry by servo and must stay exempt; every land move inherits the
        // strengthened default and binds.
        assertTrue(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ, MovementRegistry.SWIM),
                "a fluid successor (Swim) declares itself permissive — the marginal pose still tees up");
        assertTrue(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ,
                        MovementRegistry.RIDE_BUBBLE_COLUMN),
                "the bubble ride boards by servo — the marginal pose still tees up");
        assertFalse(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ, MovementRegistry.ASCEND),
                "an Ascend successor inherits the default entryReady and must refuse the marginal pose");
        assertFalse(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ, MovementRegistry.PILLAR),
                "a Pillar successor inherits the default entryReady and must refuse the marginal pose");
        assertFalse(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ, MovementRegistry.PARKOUR),
                "a Parkour successor inherits the default entryReady and must refuse the marginal pose");
    }

    @Test
    void theEndOfThePlanHasNothingToTeeUp() {
        // teedUp short-circuits on a null successor: the terminal waypoint's arrival stands on the
        // current move's own test alone, marginal carry or not.
        assertTrue(MovementRegistry.TRAVERSE.reached(marginal(), WX, WY, WZ, null),
                "no successor means no entry to deliver — the marginal pose completes the plan");
    }

    @Test
    void aFloatingBotIsExemptTheMediumOwnsItsEntry() {
        // The grounded() gate, mirroring atWaypoint's own medium exemption: a floating bot ALWAYS
        // carries velocity (buoyant bob), and Surface/DiagonalSprintSwim are fluid moves with NO
        // permissive override — an ungated projection clause would newly bind them.
        CarryBot bot = marginal().swimming();
        assertTrue(MovementRegistry.TRAVERSE.reached(bot, WX, WY, WZ, MovementRegistry.DESCEND),
                "in-water the medium owns the entry — the delivery projection must not bind a bobbing bot");
    }
}
