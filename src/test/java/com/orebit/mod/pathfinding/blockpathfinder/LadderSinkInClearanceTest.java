package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The LADDER SINK-IN, both halves (owner-ratified 2026-08-27; ShaftCourse {@code control-plain-topdown}
 * and {@code topdown-open}/{@code topdown-closed}).
 *
 * <h2>The geometry</h2>
 * A ladder is not a decoration: vanilla's {@code LadderBlock} shape is {@code boxZ(16,13,16)} — a 3/16
 * plate, FULL cell height, against its support wall, with no {@code getCollisionShape} override, so that
 * plate is real collision and its top face is a real shelf. For an EAST-facing ladder in cell {@code x=14}
 * the plate occupies {@code x ∈ [14.0, 14.1875]}.
 *
 * <pre>
 *   y=151   air              &lt;- bot stands HERE, on the plate's top face; feet cell is NOT climbable
 *   y=150   ladder (plate)   &lt;- the sink-in target: feet enter this cell
 *   y=149   ladder           &lt;- ...and the climb-down owns it from there
 * </pre>
 *
 * <h2>Half one — the servo must aim at the AIR, not at the cell</h2>
 * The 0.6-wide body centred at {@code 14.5} spans {@code [14.2, 14.8]}, so the cell centre DOES clear the
 * plate — by {@code 0.0125}. That is the whole problem: the margin is a twelfth of
 * {@link SteerControl#COLUMN_DEADBAND} (0.15), and inside that deadband the re-centre writes exact-zero
 * forward and stops driving. So the servo is entitled to quit anywhere in {@code [14.35, 14.65]}, and the
 * lower half of that window leaves the body ON the shelf. Measured: {@code grounded=true},
 * {@code fwd=0.00}, {@code src=recenter:dead}, x settled at {@code 14.417} (west face {@code 14.117}, a
 * full {@code 0.07} onto the plate), 600-tick timeout. The fix aims at the centre of the free span and
 * derives its tolerance from the same geometry, so both numbers are consequences of the block rather than
 * tuning:
 * <ul>
 *   <li>free span {@code [14.1875, 15.0]} &rarr; anchor {@code 14.59375}</li>
 *   <li>tolerance {@code halfSpan − BODY_RADIUS = 0.40625 − 0.3 = 0.10625} — "the whole box is in the air"</li>
 * </ul>
 * Note the tolerance is TIGHTER than {@link SteerControl#COLUMN_DEADBAND} (0.15): leaving the general
 * deadband in place would still park a bot approaching from the plate's own side with the box on the shelf.
 *
 * <h2>Half two — the arrest must let go once it is inert</h2>
 * At the BOTTOM of the same shaft the bot stops 0.086 above the floor, inside the settle band, held by the
 * climbable sneak-arrest. There the arrest buys nothing (stone is directly below) and costs everything: no
 * {@code grounded()}, so the navigator's arrival test never fires, so the drive falls to WAIT, which presses
 * {@code clingHold} — sneak — which is the arrest. A closed loop, and the two 600-tick FAILs.
 *
 * <p>The release keys on {@link BotSteering#seatedFloorBelow} (exactly one cell) and NOT on
 * {@link BotSteering#standableBelow} (two cells by design): the difference is a whole cell of altitude, and
 * using the wider answer here would drop every lateral vine transfer below its own waypoint — the canopy
 * regression {@link ClimbableWalkOffStanceTest#climbLateralTransferKeepsItsHoldOverACanopy} pins.
 */
class LadderSinkInClearanceTest {

    /** An EAST-facing ladder's plate in cell x=14: {@code [14.0, 14.1875]}, i.e. 3/16 of the cell. */
    private static final double PLATE_MAX_X = 14.0 + 3.0 / 16.0;
    private static final int CELL_X = 14, CELL_Y = 150, CELL_Z = 94;

    /** A pose-settable double that also answers the two new geometry seams. */
    private static final class LadderBot implements BotSteering {
        double x = CELL_X + 0.5, y = 151.0, z = CELL_Z + 0.5;
        boolean grounded = true, climbable, standable, seated, scaffolding;
        /** {@code null} = the default whole-cell span (no partial collision). */
        double[] span;

        boolean jumping, sneaking, sprinting;
        float forward = Float.NaN;
        double faceDx = Double.NaN, faceDz = Double.NaN;

        LadderBot at(double x) { this.x = x; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public boolean onClimbable() { return climbable; }
        @Override public boolean standableBelow() { return standable; }
        @Override public boolean seatedFloorBelow() { return seated; }
        @Override public boolean scaffoldingBelow() { return scaffolding; }
        @Override public void clearSpan(int cx, int cy, int cz, double[] out) {
            if (span == null) {
                BotSteering.super.clearSpan(cx, cy, cz, out);
                return;
            }
            System.arraycopy(span, 0, out, 0, 4);
        }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setStrafe(float xxa) { }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public boolean sneakHeld() { return sneaking; }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int bx, int by, int bz) { return false; }
        @Override public boolean airAt(int bx, int by, int bz) { return true; }
        @Override public boolean movementBlockedAt(int bx, int by, int bz, int dx, int dz) { return false; }
        @Override public void mine(int bx, int by, int bz) { }
        @Override public void place(int bx, int by, int bz) { }
        @Override public void setDoorOpen(int bx, int by, int bz, boolean open) { }
        @Override public boolean doorOpenAt(int bx, int by, int bz) { return false; }
        @Override public boolean swimHazardAt(int bx, int by, int bz) { return false; }
        @Override public boolean bubbleUpAt(int bx, int by, int bz) { return false; }
        @Override public double slipperinessAt(int bx, int by, int bz) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int bx, int by, int bz) { return false; }
    }

    /** The ladder's free span: everything east of the 3/16 plate, full width in Z. */
    private static double[] ladderSpan() {
        return new double[] { PLATE_MAX_X, CELL_X + 1.0, CELL_Z, CELL_Z + 1.0 };
    }

    private static LadderBot onThePlate(double x) {
        LadderBot b = new LadderBot().at(x);
        b.span = ladderSpan();
        return b;
    }

    // ---- Half one: the anchor -----------------------------------------------------------------------

    @Test
    void theCellCentreClearsThePlateByFarLessThanTheDeadbandAllows() {
        // The premise the whole fix rests on, asserted rather than assumed — and it is NOT "the centre
        // overhangs" (it does not). The centre clears, by a margin so much smaller than the servo's own
        // deadband that the servo is free to stop somewhere that does not.
        double marginAtCentre = ((CELL_X + 0.5) - BotSteering.BODY_RADIUS) - PLATE_MAX_X;
        assertEquals(0.0125, marginAtCentre, 1.0E-9,
                "a 0.6-wide body centred in the cell reaches back to x=14.2, clearing the plate edge at "
                        + "14.1875 by 0.0125 — the same knife-edge MovementContext.hangable names for the "
                        + "ballistic case");
        assertTrue(marginAtCentre < SteerControl.COLUMN_DEADBAND,
                "and the re-centre writes EXACT ZERO inside COLUMN_DEADBAND (0.15), twelve times that "
                        + "margin — so 'aim at the cell centre' is satisfied by poses that are still on the "
                        + "shelf, and the bot stops driving at one of them");

        double stalled = 14.417;    // the measured pose
        assertTrue(Math.abs(stalled - (CELL_X + 0.5)) < SteerControl.COLUMN_DEADBAND,
                "the measured stall was INSIDE the deadband — the servo had declared itself finished");
        assertTrue(stalled - BotSteering.BODY_RADIUS < PLATE_MAX_X,
                "...while the body's west face was 0.07 onto the plate, which is why it never fell");
    }

    @Test
    void theSinkInDrivesTowardTheAirNotTheCellCentre() {
        LadderBot b = onThePlate(14.417);   // the measured stall pose
        assertFalse(SteerControl.recenterClearOf(b, CELL_X, CELL_Y, CELL_Z),
                "0.083 from the cell centre is INSIDE the old deadband but still on the plate — the servo "
                        + "must report 'not there yet' rather than fall silent");
        assertTrue(b.forward > 0.0f, "and it must actually press, which the old path did not (fwd=0.00)");
        assertTrue(b.faceDx > 0.0,
                "facing must be AWAY from the plate (+x): the two directions along this axis are not "
                        + "interchangeable, which is exactly what a symmetric cell-centre deadband cannot express");
    }

    @Test
    void theSinkInStopsOnlyWhenTheWholeBodyIsOffThePlate() {
        // The derived tolerance IS "the box is in the free span" — so the servo's own stop condition and the
        // physical requirement are the same statement, not two numbers that have to be kept in agreement.
        double anchor = 0.5 * (PLATE_MAX_X + CELL_X + 1.0);
        double tol = 0.5 * ((CELL_X + 1.0) - PLATE_MAX_X) - BotSteering.BODY_RADIUS;
        assertEquals(14.59375, anchor, 1.0E-9, "free-span centre for a 3/16 plate");
        assertEquals(0.10625, tol, 1.0E-9, "halfSpan - BODY_RADIUS");
        assertTrue(tol < SteerControl.COLUMN_DEADBAND,
                "and it is TIGHTER than the general deadband (0.15), which is why leaving that in place "
                        + "would still park a bot approaching from the plate's own side on the shelf");

        LadderBot cleared = onThePlate(anchor);
        assertTrue(SteerControl.recenterClearOf(cleared, CELL_X, CELL_Y, CELL_Z),
                "at the anchor the body is wholly in the air and the servo is done");
        assertEquals(0.0f, cleared.forward, 1.0E-6f, "done means EXACT zero input, not an eased push");

        LadderBot edge = onThePlate(anchor - tol);
        assertTrue(edge.x - BotSteering.BODY_RADIUS >= PLATE_MAX_X - 1.0E-9,
                "the worst pose the tolerance admits still has the body's west face at or beyond the plate "
                        + "edge — the tolerance is the clearance requirement, restated");
    }

    @Test
    void aClimbableWithNoPartialCollisionIsUntouched() {
        // The vine family (empty shape) and a scaffold deck (FULL footprint) both hand back the whole cell.
        // Both must behave exactly as recenterOnTarget does today, or this change is not the no-op it claims
        // to be everywhere except the ladder.
        LadderBot vine = new LadderBot().at(CELL_X + 0.5);   // span == null -> the default whole cell
        assertTrue(SteerControl.recenterClearOf(vine, CELL_X, CELL_Y, CELL_Z),
                "dead-centre in an unobstructed cell is arrival, exactly as before");
        assertEquals(0.0f, vine.forward, 1.0E-6f, "and it writes the same exact zero");

        LadderBot offCentre = new LadderBot().at(CELL_X + 0.5 + SteerControl.COLUMN_DEADBAND + 0.01);
        assertFalse(SteerControl.recenterClearOf(offCentre, CELL_X, CELL_Y, CELL_Z),
                "and just outside COLUMN_DEADBAND it still drives — the degenerate tolerance is the "
                        + "general deadband, capped, so no unobstructed cell sees a behaviour change");
    }

    // ---- Half two: the arrest release ---------------------------------------------------------------

    /** A minimal HOLD-branch segment: target feet floor F, bot inside the band above it. */
    private static SteerView bandSeg(double floorY) {
        return new SteerView() {
            @Override public double sx() { return CELL_X + 0.5; }
            @Override public double sy() { return floorY; }
            @Override public double sz() { return CELL_Z + 0.5; }
            @Override public double tx() { return CELL_X + 0.5; }
            @Override public double ty() { return floorY; }
            @Override public double tz() { return CELL_Z + 0.5; }
            @Override public boolean hasNext() { return false; }
            @Override public double nx() { return 0; }
            @Override public double ny() { return 0; }
            @Override public double nz() { return 0; }
        };
    }

    @Test
    void theArrestLetsGoWhenItIsSeatedOnItsOwnFloor() {
        LadderBot b = new LadderBot();
        b.grounded = false;
        b.climbable = true;     // feet in the bottom ladder cell
        b.seated = true;        // stone at footY-1: releasing seats the feet at the cell's own base
        b.standable = true;
        b.y = 141.086;          // the measured wedge pose, inside the band above floorY=141

        SteerControl.holdClimbableStance(b, bandSeg(141.0));

        assertFalse(b.sneaking,
                "the arrest is inert here — the feet are already over their own floor, so holding buys no "
                        + "height and costs the grounded() the arrival test waits for. Measured: 0.086 above "
                        + "the shaft floor, sneak held forever, 600-tick timeout");
    }

    @Test
    void theArrestIsKeptOverACanopyWhoseCatchIsACellLower() {
        // The SAME branch, the same band, the difference is one cell of drop. seatedFloorBelow is false
        // because the catch is at footY-2; standableBelow is true because a 2.0 step-off is safe. If the
        // release keyed on the latter, every lateral vine transfer would sink below its own waypoint.
        LadderBot b = new LadderBot();
        b.grounded = false;
        b.climbable = true;
        b.seated = false;
        b.standable = true;
        b.y = 171.022;

        SteerControl.holdClimbableStance(b, bandSeg(171.0));

        assertTrue(b.sneaking,
                "leaves two cells down are not an invitation to drop onto them: the plan holds height F, and "
                        + "releasing here lands the bot a full cell under the frame its plan was built from");
    }

    @Test
    void aGenuineHangOverNothingStillArrests() {
        LadderBot b = new LadderBot();
        b.grounded = false;
        b.climbable = true;     // nothing below at all — the case the arrest exists for
        b.y = 171.022;

        SteerControl.holdClimbableStance(b, bandSeg(171.0));

        assertTrue(b.sneaking, "no floor, no release — unchanged, and the whole reason the hold exists");
    }
}
