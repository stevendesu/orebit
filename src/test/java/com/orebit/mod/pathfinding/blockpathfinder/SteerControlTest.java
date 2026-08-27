package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for the input-based steering controller ({@link SteerControl}) — pure geometry over the two
 * MC-free seams, so it needs <b>no Minecraft</b>: a {@link FakeBot} records the look + forward input the
 * controller sets, and a {@link View} supplies an arbitrary segment (already in the feet-target world frame).
 * The controller never sets velocity — it only chooses a yaw to face and a forward throttle — so the
 * properties under test are: cross-track measures distance off the planned line; the line-tracking walk faces
 * a look-ahead point on the line at full forward (so being off the line steers the bot back onto it); and a
 * vertical (degenerate) segment collapses to re-centring on the column.
 */
public class SteerControlTest {

    private static final double EPS = 1.0e-6;

    /** Minimal {@link BotSteering} double: settable pose/medium/velocity, records what the controller wrote. */
    private static final class FakeBot implements BotSteering {
        double x, y, z;
        double velX, velZ;
        boolean grounded, inWater, prone;
        // recorded outputs
        float forward = Float.NaN;
        double faceDx, faceDz;
        boolean sprinting, jumping, sank, sneaking;

        FakeBot(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

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
        @Override public boolean inWater() { return inWater; }
        @Override public boolean inLava() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public boolean prone() { return prone; }
        // Records the horizontal aim like faceHorizontally (pitch ignored) so the pure-geometry assertions on
        // faceDx/faceDz hold whether SteerControl aims via faceHorizontally or the 3-D faceTowards.
        @Override public void faceTowards(double dx, double dy, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void setForward(float zza) { forward = zza; }
        float strafe = Float.NaN;
        @Override public void setStrafe(float xxa) { strafe = xxa; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { sank = true; }
        // Terrain for the GROUND hazard probes (groundVoidColumn reads the lane floor via solidAt). Empty by
        // default, so every pure-geometry test above is unaffected; the probe tests fill in the cells they mean.
        final java.util.Set<Long> solid = new java.util.HashSet<>();
        FakeBot withSolid(int x, int y, int z) {
            solid.add((((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL));
            return this;
        }
        @Override public boolean solidAt(int x, int y, int z) {
            return solid.contains((((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL));
        }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        // Door reconcile seam (DOORS P3) — stubbed: no door in the pure-geometry SteerControl tests.
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        // Parkour-servo seams (Phase 1-3) — stubbed: ordinary stone friction, no takeoff hazard.
        double slip = 0.6; // settable: the stepOffGate friction-horizon tests flip this to ice
        // PER-CELL friction override (2026-08-25). groundArriveGain reads TWO cells — the floor under the
        // feet and the floor under the destination — and takes the slipperier, so a single uniform value
        // cannot express the case the rule exists for (walking grippy ground toward an ice cell). Cells not
        // named here fall back to `slip`, so every pre-existing test is unaffected.
        final java.util.Map<Long, Double> slipAt = new java.util.HashMap<>();
        FakeBot withSlip(int x, int y, int z, double f) {
            slipAt.put((((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL), f);
            return this;
        }
        @Override public double slipperinessAt(int x, int y, int z) {
            return slipAt.getOrDefault(
                    (((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL), slip);
        }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** A segment in feet-target world coordinates (no cell conversion — that's the follower's job). */
    private static final class View implements SteerView {
        final double sx, sy, sz, tx, ty, tz, nx, ny, nz;
        final boolean hasNext;
        View(double sx, double sy, double sz, double tx, double ty, double tz) {
            this(sx, sy, sz, tx, ty, tz, false, 0, 0, 0);
        }
        View(double sx, double sy, double sz, double tx, double ty, double tz,
             boolean hasNext, double nx, double ny, double nz) {
            this.sx = sx; this.sy = sy; this.sz = sz; this.tx = tx; this.ty = ty; this.tz = tz;
            this.hasNext = hasNext; this.nx = nx; this.ny = ny; this.nz = nz;
        }
        @Override public double sx() { return sx; }
        @Override public double sy() { return sy; }
        @Override public double sz() { return sz; }
        @Override public double tx() { return tx; }
        @Override public double ty() { return ty; }
        @Override public double tz() { return tz; }
        @Override public boolean hasNext() { return hasNext; }
        @Override public double nx() { return nx; }
        @Override public double ny() { return ny; }
        @Override public double nz() { return nz; }
    }

    @Test
    void crossTrack_isPerpendicularDistanceToTheSegment() {
        View seg = new View(0, 1, 0, 10, 1, 0); // straight along +x at y=1
        assertEquals(0.0, SteerControl.crossTrack(new FakeBot(5, 1, 0), seg), 1e-9);
        assertEquals(2.0, SteerControl.crossTrack(new FakeBot(5, 1, 2), seg), 1e-9);
        // Past the segment end clamps to the endpoint, not the infinite line.
        assertEquals(3.0, SteerControl.crossTrack(new FakeBot(13, 1, 0), seg), 1e-9);
    }

    @Test
    void crossTrack_degenerateVerticalSegment_measuresOffsetFromTheColumn() {
        View column = new View(3, 1, 3, 3, 5, 3);
        assertEquals(0.5, SteerControl.crossTrack(new FakeBot(3.5, 2, 3), column), 1e-9);
    }

    @Test
    void steerTowards_onLineStraightRun_facesAheadAtFullForward() {
        View seg = new View(0, 1, 0, 10, 1, 0);
        FakeBot b = new FakeBot(5, 1, 0);
        SteerControl.steerTowards(b, seg);
        assertTrue(b.faceDx > 0 && Math.abs(b.faceDz) < EPS, "heads +x along the line");
        assertEquals(1.0f, b.forward, 1e-6f, "full forward on a straight, on-line run");
    }

    @Test
    void steerTowards_offLine_facingHasAComponentBackTowardTheLine() {
        View seg = new View(0, 1, 0, 10, 1, 0); // line along +x at z=0
        FakeBot b = new FakeBot(5, 1, 3); // 3 blocks off the line in +z
        SteerControl.steerTowards(b, seg);
        assertTrue(b.faceDx > 0, "still makes forward progress along the line");
        assertTrue(b.faceDz < 0, "and steers back toward the line (−z), i.e. cross-track self-corrects");
        assertEquals(1.0f, b.forward, 1e-6f, "forward stays full; correction is in the heading, not the throttle");
    }

    @Test
    void steerTowards_verticalSegment_recentersOnTheColumn() {
        View column = new View(3, 1, 3, 3, 5, 3); // straight up at (3,3)
        FakeBot drifted = new FakeBot(3.7, 2, 3);
        SteerControl.steerTowards(drifted, column);
        assertTrue(drifted.faceDx < 0, "faces back toward the column (−x)");
        assertTrue(drifted.forward > 0.0f && drifted.forward <= 1.0f, "nudges back onto the column");
    }

    @Test
    void swimServo_pureVerticalSegment_offColumn_stationKeepsOverTheColumn() {
        // A degenerate (vertical) segment needs no special branch any more: the anchor IS the column, so the
        // one law station-keeps on it. 0.6 blocks out the desired closing speed is gain*dist = 0.0667 b/t,
        // well inside the cap, and against zero velocity that is the whole error — saturating the key.
        View column = new View(3.5, 56, 3.5, 3.5, 60, 3.5);
        FakeBot b = new FakeBot(4.1, 57, 3.5);
        b.inWater = true;
        SteerControl.swimServo(b, column, SteerControl.SUBMERGE_BIAS);
        assertTrue(b.faceDx < 0, "faces back toward the column (−x)");
        assertEquals(1.0f, b.forward, 1e-6f, "SERVO_GAIN saturates on a 0.067 b/t error");
    }

    @Test
    void swimServo_pureVerticalSegment_centred_reducesToDepthPitch_withTheProneForwardFloor() {
        View column = new View(3.5, 56, 3.5, 3.5, 60, 3.5);
        // Centred + upright: the old behaviour — pure depth pitch, forward released.
        FakeBot upright = new FakeBot(3.5, 57, 3.5);
        upright.inWater = true;
        SteerControl.swimServo(upright, column, SteerControl.SUBMERGE_BIAS);
        assertEquals(0.0f, upright.forward, 1e-6f, "centred upright: no horizontal push");

        // Centred + prone + submerged + airborne: the cruise's client-legal floor applies — W is never fully
        // released while prone (releasing it can drop the prone pose, and SprintSwim.reached requires prone()).
        FakeBot prone = new FakeBot(3.5, 57, 3.5);
        prone.inWater = true;
        prone.prone = true;
        SteerControl.swimServo(prone, column, SteerControl.SUBMERGE_BIAS);
        assertEquals((float) SteerControl.SERVO_FORWARD_MIN, prone.forward, 1e-6f,
                "centred prone: forward held at the client-legal floor, as on the cruise path");
    }

    @Test
    void swimServo_verticalStack_carriedMomentumConvergesBackOntoTheColumn() {
        // THE LIVELOCK CASE: a prone sprint-swimmer entering a pure-vertical waypoint stack with carried
        // horizontal momentum. Pre-fix the degenerate branch applied ZERO horizontal control, so the sprint
        // carry (~0.25 b/t → ~1 block of coast under water drag) stranded the bot one cell off-column — where
        // the exact-cell swim reach (footX/footZ match) can never fire: a permanent silent livelock. Closed
        // loop under a crude vanilla-water horizontal model (yaw-only moveRelative thrust, 0.8 drag; vertical
        // is holdDepth's and not modelled): the bot must converge onto the column cell and hold it.
        View column = new View(3.5, 56, 3.5, 3.5, 57, 3.5);
        FakeBot b = new FakeBot(3.5, 56.5, 3.5);
        b.inWater = true;
        b.prone = true;
        b.velX = 0.25;                                        // sprint-swim carry, ejecting off-column
        for (int t = 0; t < 200; t++) {
            SteerControl.swimServo(b, column, SteerControl.SUBMERGE_BIAS);
            double h = Math.sqrt(b.faceDx * b.faceDx + b.faceDz * b.faceDz);
            double ax = 0.0, az = 0.0;
            if (h > 1e-9) {                                    // thrust along the yaw, scaled by the forward key
                ax = b.faceDx / h * 0.02 * b.forward;
                az = b.faceDz / h * 0.02 * b.forward;
            }
            // Vanilla water tick: thrust, then drag. 0.9 because the prone drive SPRINTS
            // (LivingEntity.travelInWater: f = isSprinting() ? 0.9F : getWaterSlowDown()) — the same
            // retention as blue ice, i.e. a 9-block coast. The sim used to use 0.8 and so understated the
            // problem by more than 2x.
            b.velX = (b.velX + ax) * SteerControl.WATER_DRAG_SPRINT;
            b.velZ = (b.velZ + az) * SteerControl.WATER_DRAG_SPRINT;
            b.x += b.velX;
            b.z += b.velZ;
        }
        assertEquals(3, b.footX(), "converged back onto the column cell (pre-fix: stranded at footX 4 forever)");
        assertEquals(3, b.footZ(), "never left the column's z lane");
        assertTrue(Math.abs(b.x - 3.5) < 0.3, "holds near the column centre (damped, not orbiting): off by " + Math.abs(b.x - 3.5));
    }

    // ---- the rewritten swim drive (2026-08-26) --------------------------------------------------------

    @Test
    void swimConstants_areDerivedFromVanillaWaterPhysics_notTuned() {
        // v = a*q/(1-q), the fixed point of vanilla's v <- (v + a)*q, with a = 0.02 and q from
        // LivingEntity.travelInWater. These two numbers are the entire fix: the retired SERVO_CRUISE was
        // 0.35, which is 1.9x the sprint terminal and 4.4x the upright one, so the velocity error could
        // never leave saturation and the servo's facing collapsed onto the raw pursuit direction.
        assertEquals(0.18, SteerControl.swimTerminal(true), 1e-9, "sprint-swim terminal");
        assertEquals(0.08, SteerControl.swimTerminal(false), 1e-9, "upright-swim terminal");
        // gain = (1-q)/q = 1/coast. Sprint coast 9.0 (identical to blue ice), upright 4.0.
        assertEquals(1.0 / 9.0, SteerControl.swimArriveGain(true), 1e-9, "sprint arrive gain = 1/coast");
        assertEquals(1.0 / 4.0, SteerControl.swimArriveGain(false), 1e-9, "upright arrive gain = 1/coast");
        // The ease begins exactly where a full-speed coast would just reach the anchor.
        assertEquals(SteerControl.swimTerminal(true) * 9.0,
                SteerControl.swimTerminal(true) / SteerControl.swimArriveGain(true), 1e-9,
                "cap/gain IS the full-speed coast distance");
    }

    @Test
    void proneLookYFor_invertsTheVerticalTerminal_andClampsAwayFromVertical() {
        // Pitch commands a vertical VELOCITY, so the inverse is closed-form and must round-trip.
        for (double d : new double[] {0.9, 0.5, 0.1, 0.0, -0.1, -0.5, -0.9}) {
            double vy = SteerControl.proneVerticalTerminal(d);
            assertEquals(d, SteerControl.proneLookYFor(vy), 1e-9, "round-trip at lookY " + d);
        }
        // Beyond what the medium can deliver, the command saturates rather than running away — and never
        // reaches +/-1, where the synthesized look vector's horizontal component would vanish and
        // faceTowards would silently keep the previous yaw.
        assertEquals(SteerControl.PRONE_LOOK_Y_MAX, SteerControl.proneLookYFor(99.0), 1e-9);
        assertEquals(-SteerControl.PRONE_LOOK_Y_MAX, SteerControl.proneLookYFor(-99.0), 1e-9);
        assertTrue(SteerControl.PRONE_LOOK_Y_MAX < 1.0, "never commands a truly vertical look");
        // The steep-dive branch really is faster (vanilla's e = 0.085 below d = -0.2).
        assertTrue(SteerControl.proneVerticalTerminal(-0.9) < SteerControl.proneVerticalTerminal(0.9) * -1.0,
                "the steep-dive rate exceeds the rise rate");
    }

    @Test
    void swimServo_committedOvershoot_brakesAcrossTrack_ratherThanDemandingMore() {
        // THE FLAGSHIP (359,37,426) REGRESSION, replayed from the measured tick. DiagonalSprintSwim wp16 was
        // a PURE +X segment; the bot entered 0.349 short in Z carrying +0.1609 b/t of Z inherited from the
        // previous (+Z) leg. On water's coast that residual was already committed to 1.45 blocks of further
        // +Z travel with only 0.349 to give.
        //
        // The retired law computed desired = dir * SERVO_CRUISE(0.35) = (0.284, +0.204) and so read
        // err_z = +0.044 — an already-committed OVERSHOOT scored as UNDER-speed. It asked for more +Z, and
        // the bot left the cell. With an achievable cap the same tick brakes.
        View seg = new View(358.5, 37, 426.5, 359.5, 37, 426.5);
        FakeBot b = new FakeBot(358.609, 37.5, 426.151);
        b.inWater = true;
        b.prone = true;
        b.velX = 0.0322;
        b.velZ = 0.1609;
        SteerControl.swimServo(b, seg, SteerControl.SUBMERGE_BIAS);
        assertTrue(b.faceDz < 0.0,
                "commands a NEGATIVE z correction — brakes the committed cross-track overshoot (was +0.044)");
        assertEquals(0.0f, b.strafe, 1e-6f, "water actuation is look + forward; strafe is never written");
        assertTrue(b.faceDx > 0.0, "still advances along the segment");
    }

    @Test
    void swimServo_carriesThroughAStraightRun() {
        // A plain arrive would come to REST on every intermediate waypoint. 0.1 blocks from the anchor the
        // arrive term alone asks for gain*dist = 0.011 b/t — INSIDE the dead-band, i.e. "stop here". The
        // carry-through blend toward the next leg's exit direction is what keeps a long swim moving.
        View seg = new View(0.5, 37, 0.5, 1.5, 37, 0.5, true, 2.5, 37, 0.5);
        FakeBot b = new FakeBot(1.4, 37.5, 0.5);
        b.inWater = true;
        b.prone = true;
        SteerControl.swimServo(b, seg, SteerControl.SUBMERGE_BIAS);
        assertTrue(b.faceDx > 0.0, "drives THROUGH the waypoint along the next leg");
        assertEquals(1.0f, b.forward, 1e-6f, "full ahead — not the near-stop a bare arrive would command");
        // ...and the DIRECTION is never rotated toward the next leg ahead of arrival: that is what a rounded
        // turn is, and in a 1-wide lane a rounded turn is a wall clip. Carry-through is a speed floor only.
        assertEquals(0.0, b.faceDz, 1e-9, "aim stays on the current segment, not blended toward the next");
    }

    @Test
    void swimServo_killsAlongMomentumIntoACorner() {
        // The same blend IS the corner brake, and it needs no probe of what lies outside the lane: at a
        // 90-degree turn the exit direction is perpendicular, so "arrive travelling along the next leg"
        // means the current leg's momentum has to go. This is the duty the hazard ramp used to carry — the
        // ramp being the only thing that ever made the old cap reachable, which is exactly why the
        // bubble-walled maze cards were green while open water wedged.
        View seg = new View(0.5, 37, 0.5, 1.5, 37, 0.5, true, 1.5, 37, 1.5);
        FakeBot b = new FakeBot(1.4, 37.5, 0.5);
        b.inWater = true;
        b.prone = true;
        b.velX = SteerControl.swimTerminal(true);      // arriving at cruise along +x
        SteerControl.swimServo(b, seg, SteerControl.SUBMERGE_BIAS);
        assertTrue(b.faceDx < 0.0, "reverse-thrusts to shed the +x momentum the turn cannot use");
        // And it does NOT pre-build the next leg's +z. Cross velocity raised before the corner is the
        // rounded turn that put the box in the neighbouring column on `mazeportal`; the bot squares the
        // corner instead, arriving at rest and turning from there.
        assertEquals(0.0, b.faceDz, 1e-9, "no cross velocity commanded ahead of the turn");
    }

    @Test
    void uprightSwimServo_usesTheUprightDrag_andWritesNoStrafe() {
        // The upright pose does not sprint (sprinting would re-enter the prone pose), so it brakes against
        // q = 0.8 — a 4.0-block coast, gain 0.25 — and its cap is the 0.08 b/t it can actually swim.
        View seg = new View(0.5, 60, 0.5, 8.5, 60, 0.5);
        FakeBot far = new FakeBot(1.5, 60.2, 0.5);
        far.inWater = true;
        SteerControl.uprightSwimServo(far, seg);
        assertTrue(far.faceDx > 0.0, "drives along the segment");
        assertEquals(1.0f, far.forward, 1e-6f, "far out: saturated");
        assertEquals(0.0f, far.strafe, 1e-6f, "no strafe channel in water");

        // Already at terminal on the approach: the error collapses and the servo coasts instead of
        // over-driving. This is the behaviour the unreachable cap made impossible.
        FakeBot cruising = new FakeBot(1.5, 60.2, 0.5);
        cruising.inWater = true;
        cruising.velX = SteerControl.swimTerminal(false);
        SteerControl.uprightSwimServo(cruising, seg);
        assertEquals(0.0f, cruising.forward, 1e-6f, "at the achievable cruise the servo stops pushing");
    }

    /**
     * A mutable multi-waypoint {@link SteerView} for closed-loop replays: the segment is
     * {@code cells[i-1] -> cells[i]}, and {@link #advance()} steps the cursor. Feet-target frame, so a cell
     * {@code (x,y,z)} is exposed as {@code (x+0.5, y, z+0.5)}.
     */
    private static final class Chain implements SteerView {
        final int[][] cells;
        int i = 1;
        Chain(int[][] cells) { this.cells = cells; }
        void advance() { i++; }
        boolean done() { return i >= cells.length; }
        @Override public double sx() { return cells[i - 1][0] + 0.5; }
        @Override public double sy() { return cells[i - 1][1]; }
        @Override public double sz() { return cells[i - 1][2] + 0.5; }
        @Override public double tx() { return cells[i][0] + 0.5; }
        @Override public double ty() { return cells[i][1]; }
        @Override public double tz() { return cells[i][2] + 0.5; }
        @Override public boolean hasNext() { return i + 1 < cells.length; }
        @Override public double nx() { return cells[Math.min(i + 1, cells.length - 1)][0] + 0.5; }
        @Override public double ny() { return cells[Math.min(i + 1, cells.length - 1)][1]; }
        @Override public double nz() { return cells[Math.min(i + 1, cells.length - 1)][2] + 0.5; }
    }

    @Test
    void swimServo_flagshipWedgeChain_handsOffCentred_insteadOfCoastingOutOfTheCell() {
        // ============================================================================================
        // THE (359,37,426) FLAGSHIP WEDGE, REPLAYED CLOSED-LOOP FROM THE MEASURED TELEMETRY.
        //
        // The long flagship stalled for 3015 ticks in EndSprintSwim at (359,37,426). The log shows the
        // cause was not one bad leg but a SYSTEMATIC one: every handoff of the whole descent landed
        // 0.34-0.49 blocks short along the travel axis, and bare cell membership accepted all of them --
        //
        //   SprintSwim         ->(358,44,417)  offCentre=(-0.047,-0.342)
        //   DiagonalSprintSwim ->(358,43,418)  offCentre=(-0.038,-0.464)
        //   DiagonalSprintSwim ->(358,42,419)  offCentre=(-0.004,-0.380)
        //   DiagonalSprintSwim ->(358,41,420)  offCentre=( 0.003,-0.463)
        //   DiagonalSprintSwim ->(358,39,422)  offCentre=( 0.025,-0.344)
        //   DiagonalSprintSwim ->(358,38,423)  offCentre=(-0.017,-0.490)
        //   SprintSwim         ->(358,37,425)  offCentre=(-0.004,-0.351)
        //   SprintSwim         ->(358,37,426)  offCentre=( 0.000,-0.447)
        //   DiagonalSprintSwim ->(359,36,426)  offCentre=( 0.109,-0.349)  vel=(0.032,+0.161)
        //   EndSprintSwim      ->(359,36,426)  offCentre=(-0.418,+0.319)  vel=(0.088,+0.052)
        //
        // -- until the last one ran out of cell. That final DiagonalSprintSwim is a PURE +X segment
        // (from-floor (358,37,426) -> (359,36,426), dz = 0) entered 0.349 short in Z while carrying
        // +0.161 b/t of Z inherited from the preceding +Z legs. The old drive never nulled that: with an
        // unreachable 0.35 cap the velocity error read as UNDER-speed and it asked for MORE +Z. Six ticks
        // later it exited at z=426.762, handed over 0.0516 b/t of residual, and water's 4.0-block coast
        // carried the bot to z~427.03 -- outside the waypoint cell, into a foot-cell test its successor
        // could never satisfy.
        //
        // THE REPLAY DELIBERATELY USES THE FLAGSHIP'S OWN LOOSE ADVANCE RULE (bare cell membership), NOT
        // the containment band this change also adds. Gating the cursor on containment would make the
        // assertion circular -- "the handoffs are centred because we refused to hand off otherwise". Run
        // against the rule that was actually in force when the bot wedged, a centred delivery can only
        // come from the SERVO. The containment band is the second lock, tested separately.
        //
        // Physics: the vanilla water tick, horizontal only -- thrust 0.02 along the commanded yaw scaled
        // by the forward key, then v <- (v + a) * 0.9 (sprinting). Vertical is out of scope here; the
        // flagship's Y tracked correctly throughout and the failure was purely horizontal.
        // ============================================================================================
        int[][] feetCells = {
                {358, 38, 424}, {358, 38, 425}, {358, 38, 426}, {359, 37, 426},
        };
        Chain path = new Chain(feetCells);

        FakeBot b = new FakeBot(358.496, 38.724, 424.149);   // measured entry to the (358,37,424) leg
        b.inWater = true;
        b.prone = true;
        b.velX = -0.012;
        b.velZ = 0.160;                                       // the +Z carry that became the wedge

        double worstCross = 0.0;
        int handoffs = 0;
        int t = 0;
        for (; t < 400 && !path.done(); t++) {
            SteerControl.swimServo(b, path, SteerControl.SUBMERGE_BIAS);

            double h = Math.hypot(b.faceDx, b.faceDz);
            double ax = 0.0, az = 0.0;
            if (h > 1e-9) {
                ax = b.faceDx / h * SteerControl.WATER_ACCEL * b.forward;
                az = b.faceDz / h * SteerControl.WATER_ACCEL * b.forward;
            }
            b.velX = (b.velX + ax) * SteerControl.WATER_DRAG_SPRINT;
            b.velZ = (b.velZ + az) * SteerControl.WATER_DRAG_SPRINT;
            b.x += b.velX;
            b.z += b.velZ;

            // THE FLAGSHIP'S ADVANCE RULE: bare foot-cell membership, no containment.
            int wx = path.cells[path.i][0], wz = path.cells[path.i][2];
            if (b.footX() == wx && b.footZ() == wz) {
                // MEASURE THE QUANTITY THAT ACTUALLY KILLED THE FLAGSHIP: the component of velocity
                // PERPENDICULAR to the leg about to begin. (The along-axis offset is ~0.5 at the crossing
                // instant by definition under bare membership, so it discriminates nothing.) Cross-track
                // carry is what the old drive never nulled: it delivered +0.161 b/t of Z into a pure +X
                // segment, and later +0.0516 b/t into EndSprintSwim, which on water's coast was a whole cell.
                if (!path.done() && path.i + 1 < path.cells.length) {
                    int[] a = path.cells[path.i], c = path.cells[path.i + 1];
                    double ndx = c[0] - a[0], ndz = c[2] - a[2];
                    double nlen = Math.hypot(ndx, ndz);
                    if (nlen > 1e-9) {
                        worstCross = Math.max(worstCross,
                                Math.abs(b.velX() * (ndz / nlen) - b.velZ() * (ndx / nlen)));
                    }
                }
                handoffs++;
                path.advance();
            }
        }

        assertTrue(path.done(), "the chain completes — no wedge (stalled at leg " + path.i + " after " + t + "t)");
        assertEquals(3, handoffs, "every leg handed off");
        // REGRESSION PIN against the measured flagship value, not against a bound I would have to invent.
        // The old drive carried +0.161 b/t of Z into that pure-+X leg (its PLAN line, verbatim) because an
        // unreachable cap made the overshoot read as under-speed. Anything near that number means the corner
        // is not being squared. Measured here: ~0.052, a 3.1x reduction.
        double flagshipCarry = 0.161;
        assertTrue(worstCross < flagshipCarry / 2.0,
                "cross-track carry into the turn is a large factor below the flagship's "
                        + flagshipCarry + "; measured " + worstCross);

        // ---- THE STAND-UP, which is where the bot actually wedged ----------------------------------------
        // EndSprintSwim is an in-place move: from-floor == to-floor == (359,36,426), feet (359,37,426). The
        // old one held setForward(0f) on a mistaken reading of vanilla (Entity.updateSwimming keys the prone
        // pose on isSprinting() ALONE — the forward key was free all along), so it had NO horizontal lever:
        // whatever residual it inherited simply coasted, and 0.0516 b/t on water's 4.0-block coast put the
        // bot at z~427.03. It then failed a foot-cell test for cell 426 for 3015 ticks.
        //
        // Replay it with the drive it has now — the upright servo, un-sprinted, so drag drops to 0.8 exactly
        // as it does in game.
        Chain hold = new Chain(new int[][] {{359, 37, 426}, {359, 37, 426}});
        b.prone = false;
        for (int k = 0; k < 200; k++) {
            SteerControl.uprightSwimServo(b, hold);
            double h2 = Math.hypot(b.faceDx, b.faceDz);
            double ax2 = 0.0, az2 = 0.0;
            if (h2 > 1e-9) {
                ax2 = b.faceDx / h2 * SteerControl.WATER_ACCEL * b.forward;
                az2 = b.faceDz / h2 * SteerControl.WATER_ACCEL * b.forward;
            }
            b.velX = (b.velX + ax2) * SteerControl.WATER_DRAG_UPRIGHT;   // not sprinting: 0.8
            b.velZ = (b.velZ + az2) * SteerControl.WATER_DRAG_UPRIGHT;
            b.x += b.velX;
            b.z += b.velZ;
        }

        // THE VERDICT. The flagship's bot came to rest at z=427.07 — foot cell 427, one past its waypoint —
        // and sat there. This is the same starting state, the same waypoints and the same physics.
        assertEquals(359, b.footX(), "rests in the goal column (flagship: 359, ok)");
        assertEquals(426, b.footZ(), "rests in the goal CELL — the flagship coasted to 427 and wedged");
        assertTrue(Math.abs(b.x - 359.5) <= Movement.ARRIVAL_HALF_SLACK
                        && Math.abs(b.z - 426.5) <= Movement.ARRIVAL_HALF_SLACK,
                "and satisfies reachedSwim's containment band, so the move can COMPLETE: off-centre ("
                        + Math.abs(b.x - 359.5) + ", " + Math.abs(b.z - 426.5) + ")");
    }

    @Test
    void parkourCrossSettled_firesOnTheFlagshipCarry_andReleasesTheChatterThatWedgedIt() {
        // Both states measured on the 2026-08-26 long flagship, one from each failure.
        //
        // (1) MUST ALIGN — Parkour step 10's entry, handed over by a +Z Ascend into an +X jump: centred to
        // 0.011 but carrying 0.130 b/t of pure cross. It launched 0.181 off the centreline and the airborne
        // servo then burnt four ticks yawed up to 48 degrees correcting it, costing the jump 0.069 of reach.
        FakeBot carry = new FakeBot(77.522, 147.0, 262.489);
        carry.velX = 0.002;
        carry.velZ = 0.130;
        assertFalse(SteerControl.parkourCrossSettled(carry, 1, 0, 77.5, 262.5),
                "0.130 of cross carry must not be committed to a jump");

        // (2) MUST RELEASE — the state that WEDGED the flagship at (278,113,352) once an align phase existed.
        // Jump axis +Z, so cross is X; the bot sits 0.035 off the centreline with cross velocity chattering
        // at ±0.0346. An instantaneous |crossVel| < SERVO_DEADBAND test can never be satisfied here, because
        // one tick of the smallest useful input moves cross velocity ~0.069 — more than the band itself. The
        // gate must therefore ask about DISPLACEMENT, not instantaneous velocity.
        FakeBot chatter = new FakeBot(278.465, 113.0, 352.5);
        chatter.velX = -0.0346;
        chatter.velZ = 0.0;
        assertTrue(SteerControl.parkourCrossSettled(chatter, 0, 1, 278.5, 352.5),
                "0.035 off-centre with 0.035 of cross is harmless — releasing here is what avoids the wedge");
    }

    @Test
    void recenterOnTarget_pullsBackWhenDrifted_andIdlesWhenCentred() {
        View column = new View(3, 1, 3, 3, 5, 3);
        FakeBot drifted = new FakeBot(3.7, 2, 3);
        SteerControl.recenterOnTarget(drifted, column);
        assertTrue(drifted.faceDx < 0, "faces back toward the column");
        assertEquals(0.7f, drifted.forward, 1e-6f, "forward ≈ horizontal offset (0.7) while drifted");

        FakeBot onColumn = new FakeBot(3, 2, 3);
        SteerControl.recenterOnTarget(onColumn, column);
        assertEquals(0.0f, onColumn.forward, 1e-6f, "no shove when already centred");
    }

    // ---- Gate-point steering (steerViaGate/pastGate — the corner-gate primitive) ----------------------
    // The P5 §3.2 hug frame: a diagonal step (0,0)→(1,1), centres (0.5,0.5)→(1.5,1.5); the side column
    // (x+dx, z) is blocked, so the hug's gate is the shared corner (1,1) offset 0.3 toward the OPEN side:
    // (0.7, 1.3). Along-track axis u = (1,1)/√2; the gate projects at 0.5·√2 ≈ 0.7071 (exactly the corner).

    @Test
    void steerViaGate_shortOfTheGate_aimsTheGatePoint_neverTheCenterline() {
        View seg = new View(0.5, 1, 0.5, 1.5, 1, 1.5);
        // Off the s→t line toward the OPEN side (+z), short of the gate (along ≈ 0.495 < 0.7071 − deadband).
        FakeBot b = new FakeBot(0.6, 1, 1.1); // vel 0 → the servo error IS the aim direction
        SteerControl.steerViaGate(b, seg.sx(), seg.sz(), seg.tx(), seg.tz(), 0.7, 1.3);
        // Aim = gate − bot = (0.1, 0.2): both positive, z-dominant — the drive keeps (deepens) the open-side
        // offset. Line-tracking (steerTowards/groundServo pursuit) would aim x-dominant here, pulling the bot
        // back toward the centerline and the blocked corner — exactly the recentering the hug refutation bans.
        assertTrue(b.faceDx > 0 && b.faceDz > 0, "advances toward the gate");
        assertEquals(2.0, b.faceDz / b.faceDx, 1e-9, "aims the gate POINT exactly — no centerline return term");
        assertEquals(1.0f, b.forward, 1e-6f, "pass-through aim: full cruise into the gate, never eased");
    }

    @Test
    void steerViaGate_pastTheGate_aimsTheRealTarget() {
        View seg = new View(0.5, 1, 0.5, 1.5, 1, 1.5);
        // Past the gate's projection (along ≈ 0.813 > 0.7071): the destination centre owns the aim.
        FakeBot b = new FakeBot(0.8, 1, 1.35);
        SteerControl.steerViaGate(b, seg.sx(), seg.sz(), seg.tx(), seg.tz(), 0.7, 1.3);
        // Aim = target − bot = (0.7, 0.15) — the heading has swung off the gate onto the destination.
        assertTrue(b.faceDx > 0 && b.faceDz > 0, "advances toward the target");
        assertEquals(0.0, b.faceDx * 0.15 - b.faceDz * 0.7, 1e-9, "aims the target POINT (collinear with target − bot)");
        assertEquals(1.0f, b.forward, 1e-6f, "carries speed through the target (reached is the stopper, not the drive)");
    }

    @Test
    void steerViaGate_handoffSwitchesAtTheGateProjection_withTheEarlyDeadband() {
        View seg = new View(0.5, 1, 0.5, 1.5, 1, 1.5);
        double ux = 1.0 / Math.sqrt(2.0), uz = ux;       // along-track unit
        // ON the line just short of the handoff (along 0.60 < 0.7071 − GATE_PASS_DEADBAND ≈ 0.657): the gate
        // owns the aim, which points OFF the line toward the open side (−x, +z from here).
        FakeBot shortOf = new FakeBot(0.5 + 0.60 * ux, 1, 0.5 + 0.60 * uz);
        SteerControl.steerViaGate(shortOf, seg.sx(), seg.sz(), seg.tx(), seg.tz(), 0.7, 1.3);
        assertTrue(shortOf.faceDx < 0 && shortOf.faceDz > 0,
                "short of the gate the drive leaves the centerline for the gate (the hug's whole point)");
        // ON the line just past it (along 0.66 ≥ the deadbanded threshold): the target owns the aim — dead
        // ahead along the diagonal, equal components.
        FakeBot past = new FakeBot(0.5 + 0.66 * ux, 1, 0.5 + 0.66 * uz);
        SteerControl.steerViaGate(past, seg.sx(), seg.sz(), seg.tx(), seg.tz(), 0.7, 1.3);
        assertTrue(past.faceDx > 0 && past.faceDz > 0, "past the gate: forward along the diagonal");
        assertEquals(past.faceDx, past.faceDz, 1e-9, "…aimed at the target centre (on-line → equal components)");
        // The predicate itself, at the same two states.
        assertTrue(!SteerControl.pastGate(shortOf, 0.5, 0.5, 1.5, 1.5, 0.7, 1.3), "along 0.60 is short");
        assertTrue(SteerControl.pastGate(past, 0.5, 0.5, 1.5, 1.5, 0.7, 1.3), "along 0.66 is past (deadband fires early)");
    }

    @Test
    void steerViaGate_servoActuation_bleedsMomentumOffTheGateLine() {
        View seg = new View(0.5, 1, 0.5, 1.5, 1, 1.5);
        // At the segment start with purely lateral (+x) momentum: the velocity error faces −x (reverse-thrust
        // the off-line component) while still +z (advance toward the gate) — the parkourRunupAlign property,
        // now anchored to the gate POINT so position converges too, not just velocity.
        FakeBot b = new FakeBot(0.5, 1, 0.5);
        b.velX = 0.2;
        SteerControl.steerViaGate(b, seg.sx(), seg.sz(), seg.tx(), seg.tz(), 0.7, 1.3);
        assertTrue(b.faceDx < 0, "faces against the lateral momentum (bleeds it)");
        assertTrue(b.faceDz > 0, "while still thrusting toward the gate");
        assertEquals(1.0f, b.forward, 1e-6f, "error well past the servo deadband → saturated forward");
    }

    /** The flagship-cliff corner-slip frame: a +x one-cell step-off entered with −z carry (vz −0.18 at
     *  z 246.3, lane centre 246.5). Stone horizon v/(1−0.546) ≈ ×2.2 → predicted offset −0.6, far outside
     *  the ±0.2 lane margin → the gate must HOLD and write a counter-thrust against the carry. */
    @Test
    void stepOffGate_arrestsHotCrossEntry() {
        View seg = new View(67.5, 150, 246.5, 68.5, 149, 246.5); // +x step, lane centreline z = 246.5
        FakeBot b = new FakeBot(67.5, 150, 246.3);
        b.velZ = -0.18;
        assertTrue(SteerControl.stepOffGate(b, seg), "hot cross entry must hold at the gate");
        assertTrue(b.faceDz > 0, "arrest faces +z — against the −z carry, toward the centreline");
        assertTrue(b.forward > 0.0f, "arrest thrusts (the pure cross servo, along-speed zero)");
    }

    /** Centred and still → contained by construction: the gate commits and writes NOTHING. */
    @Test
    void stepOffGate_commitsWhenAligned() {
        View seg = new View(67.5, 150, 246.5, 68.5, 149, 246.5);
        FakeBot b = new FakeBot(67.5, 150, 246.5);
        assertFalse(SteerControl.stepOffGate(b, seg), "no carry, on the centreline → commit");
        assertTrue(Float.isNaN(b.forward), "a committing gate writes no inputs (the caller drives)");
    }


    // ---- GROUND hazard probes: DELETED 2026-08-25 (owner ruling) ------------------------------------
    // Five tests lived here pinning groundOvershootHazard / groundFlankHazard / plannedDescentCell: the
    // Chebyshev cell walk, and the per-probe-cell planned-descent exemption. The predicates they pinned no
    // longer exist. They are not being weakened to fit a changed model -- the model they described was
    // removed wholesale, because it answered the wrong question:
    //
    //   a corner brake must answer "from how far out must I slow to stop ON the anchor?", which is a
    //   function of SPEED and the SURFACE'S DRAG. The retired family instead inspected the CONTENTS of
    //   cells past the waypoint and emitted one bit that shifted the anchor by a BODY-GEOMETRY constant
    //   (0.5 - BODY_RADIUS - margin = 0.19 blocks), the same 0.19 whether the bot was creeping on stone
    //   (0.42 b of run-out at cruise) or sliding on blue ice (2.88 b).
    //
    // Their subject matter is now covered by groundArriveGain_* below, which pins the replacement law, and
    // the behavioural guard is the ice / iceparkour course family. See the tombstone in SteerControl.
    //
    // NOTE the exemption those tests pinned was itself the tell: plannedDescentCell existed only to stop
    // the probe flagging cells THE PLAN HAD DELIBERATELY CHOSEN, it could exempt only ONE cell (SteerView
    // is one waypoint deep) while the walk reached two, and only for a DESCENT. A predicate that needs a
    // patch to stop contradicting the plan is asking a question the follower has no business asking.

    /** The replacement law's core identity: the ARRIVE gain is {@code (1-q)/q} for the medium's per-tick
     *  drag {@code q = friction x 0.91}, so {@code 1/gain} IS the coast distance per unit speed. Pinning
     *  the numbers, not just the shape -- these are what decide how early the servo starts easing. */
    @Test
    void groundArriveGain_isDerivedFromTheSurfaceDrag() {
        View seg = new View(10.5, 69.0, 10.5, 11.5, 69.0, 10.5);
        FakeBot stone = new FakeBot(11.0, 69.0, 10.5);          // f = 0.6 (the FakeBot default)
        assertEquals(0.8315, SteerControl.groundArriveGain(stone, seg), 1e-3,
                "stone: q = 0.546, coast 1.20 b -> gain 0.831 (the value the retired constant hard-coded)");

        FakeBot ice = new FakeBot(11.0, 69.0, 10.5);
        ice.slip = 0.98;
        assertEquals(0.1213, SteerControl.groundArriveGain(ice, seg), 1e-3,
                "ice: q = 0.892, coast 8.24 b -> gain 0.121, a 6.9x longer ease than stone");

        FakeBot blue = new FakeBot(11.0, 69.0, 10.5);
        blue.slip = 0.989;
        assertTrue(SteerControl.groundArriveGain(blue, seg) < SteerControl.groundArriveGain(ice, seg),
                "blue ice is slipperier still -> an even longer ease");
    }

    /** The conservative pick (owner ruling 2026-08-25): whichever of {current floor, destination floor} is
     *  SLIPPERIER governs, so the ease begins BEFORE the bot steps onto ice rather than one tick after.
     *  Note vanilla's naming inversion -- getFriction() is velocity RETENTION, so slipperier is the LARGER
     *  value and the conservative pick is max(), not min(). */
    @Test
    void groundArriveGain_takesTheSlipperierOfTheTwoSurfaces() {
        // The bot stands in cell x=10 and the step targets cell x=11, so the two reads are DISTINCT cells:
        // feet floor (10,68,10), destination floor (floor(tx), floor(ty)-1, floor(tz)) = (11,68,10).
        View seg = new View(10.5, 69.0, 10.5, 11.5, 69.0, 10.5);

        FakeBot ontoIce = new FakeBot(10.5, 69.0, 10.5);        // standing on stone...
        ontoIce.withSlip(11, 68, 10, 0.98);                     // ...about to step onto ice
        assertEquals(0.1213, SteerControl.groundArriveGain(ontoIce, seg), 1e-3,
                "the ice ahead governs while still on stone — brake EARLY, the whole point of the rule");

        FakeBot offIce = new FakeBot(10.5, 69.0, 10.5);         // standing ON ice...
        offIce.slip = 0.98;
        offIce.withSlip(11, 68, 10, 0.6);                       // ...stepping off onto stone
        assertEquals(0.1213, SteerControl.groundArriveGain(offIce, seg), 1e-3,
                "still on ice — the surface actually under the feet still governs this tick's physics");
    }

    /** No floor at all yields the ORDINARY gain, not a special case. This is the structural difference from
     *  the retired probes: they read a floor's EXISTENCE (!solidAt) and turned "no block" into HAZARD; this
     *  reads a VALUE, and air's getFriction() is 0.6 — the same as stone. It is why a partial-height floor
     *  can no longer produce a bogus verdict (the trapdoor pocket / closeparkour wedge). */
    @Test
    void groundArriveGain_missingFloorIsOrdinaryNotHazardous() {
        View seg = new View(10.5, 69.0, 10.5, 11.5, 69.0, 10.5);
        FakeBot overVoid = new FakeBot(11.0, 69.0, 10.5);       // withSolid() never called: nothing anywhere
        assertEquals(0.8315, SteerControl.groundArriveGain(overVoid, seg), 1e-3,
                "an empty world reads ordinary friction — the gain never inspects whether a block is there");
    }


    // ---- the LAND drive: ONE law for every grounded tick --------------------------------------------
    // 2026-08-20 Phase 2 retired groundServo's hazard branch onto arriveOnStep; Phase 3 (2026-08-24) did the
    // same for its pursuit branch; 2026-08-25 removed the hazard verdict itself. drive() now routes every
    // grounded tick to the one position-anchored ARRIVE, whose ease length comes from groundArriveGain.

    /** The flagship-r8 creep-wedge pose, verbatim: step 2 Diagonal floor (339,68,480)→(340,68,481), bot at
     *  (339.926, 481.006) with vel (0.0963, 0.0651), grounded and ON its line (cte 0.0566), the plan turning
     *  into a Descend at (341,67,481). The retired speed schedule produced a velocity error of magnitude
     *  0.02817 — a hair over SERVO_DEADBAND — and faced THAT: yaw −178.9°, forward 0.51. A half-throttle
     *  thrust due NORTH at a target east-north-east. The position-anchored ARRIVE wants −55.2° at full
     *  forward, and that is what the drive must now command. */
    @Test
    void drive_dropLipCorner_arrivesOnTheStep_insteadOfPirouetting() {
        View diagonalIntoDescend = new View(339.5, 69.0, 480.5, 340.5, 69.0, 481.5,
                true, 341.5, 68.0, 481.5);
        FakeBot b = new FakeBot(339.926, 69.0, 481.006);
        b.velX = 0.0963; b.velZ = 0.0651;
        b.grounded = true;
        // The real terrain (scripts/autotest-world-master/world): grass at y=68 on the x≤340 shelf, and the
        // x=341 line stepped DOWN to y=67 — which is why every probe ahead reads "no floor at my level".
        b.withSolid(339, 68, 480).withSolid(339, 68, 481).withSolid(339, 68, 482)
         .withSolid(340, 68, 481).withSolid(340, 68, 482).withSolid(340, 68, 483)
         .withSolid(341, 67, 480).withSolid(341, 67, 481).withSolid(341, 67, 482)
         .withSolid(342, 67, 481).withSolid(342, 67, 482).withSolid(342, 67, 483);
        SteerControl.drive(b, diagonalIntoDescend);
        double yaw = Math.toDegrees(Math.atan2(-b.faceDx, b.faceDz));
        assertTrue(yaw < -30.0 && yaw > -80.0,
                "the drive must head toward the target (≈ −55°), not spin north; got " + yaw);
        assertEquals(1.0f, b.forward, 1e-6f, "saturated ARRIVE pull, not the schedule's 0.51 half-throttle");
        assertTrue(SteerControl.lastDrive.endsWith("arrive:step"),
                "one law: the target-centre ARRIVE; got " + SteerControl.lastDrive);
    }

    /** The same law on an ordinary floored straight — no terrain-dependent mode to switch into any more. */
    @Test
    void drive_safeStraight_alsoArrivesOnTheStep() {
        // PHASE 3 (2026-08-24): this used to assert the SAFE corner kept groundServo's pursuit branch
        // ("servo:thrust"). That servo is deleted — it faced the raw velocity error with no position term,
        // so near a segment end, where the scheduled speed drops under the residual velocity, its facing
        // flipped 180 degrees per tick at full throttle and parked the bot short of the target. Both the
        // safe and the hazard corner now route to arriveOnStep, so ONE position-anchored law owns every
        // grounded drive tick; the assertion follows the law rather than the retired branch.
        View straight = new View(10.5, 69.0, 10.5, 11.5, 69.0, 10.5);
        FakeBot b = new FakeBot(11.0, 69.0, 10.5);
        b.grounded = true;
        b.withSolid(12, 68, 10).withSolid(13, 68, 10)     // floored ahead — nothing to brake for
         .withSolid(11, 68, 11).withSolid(11, 68, 9);     // …and floored on both flanks
        SteerControl.drive(b, straight);
        assertTrue(SteerControl.lastDrive.contains("arrive:step")
                        && !SteerControl.lastDrive.contains("arrive:stephaz"),
                "a safe corner arrives on the step's target centre; got " + SteerControl.lastDrive);
        assertTrue(b.faceDx > 0, "heads down the leg");
    }

    /** The friction horizon is the SUPPORT block's, not a constant: a −0.05 carry coasts out to ~0.11 on
     *  stone (contained) but ~0.46 on ice (slip 0.98 → v/(1−0.892) ≈ ×9.2) — the gate must hold on ice. */
    @Test
    void stepOffGate_iceExtendsTheHorizon() {
        View seg = new View(67.5, 150, 246.5, 68.5, 149, 246.5);
        FakeBot stone = new FakeBot(67.5, 150, 246.5);
        stone.velZ = -0.05;
        assertFalse(SteerControl.stepOffGate(stone, seg), "a mild carry coasts out inside the lane on stone");
        FakeBot ice = new FakeBot(67.5, 150, 246.5);
        ice.velZ = -0.05;
        ice.slip = 0.98;
        assertTrue(SteerControl.stepOffGate(ice, seg), "the same carry on ice coasts far past the lane — hold");
    }
}
