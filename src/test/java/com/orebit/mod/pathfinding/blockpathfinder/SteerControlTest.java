package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { sank = true; }
        // Reconcile seam — unused by the pure-geometry SteerControl tests, stubbed to satisfy the interface.
        @Override public boolean solidAt(int x, int y, int z) { return false; }
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
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
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
    void swimTowards_isHorizontalOnly_facesAlongTheLineAtFullForward() {
        // swimTowards is HORIZONTAL-only: it faces the look-ahead pursuit point and holds forward. Vertical
        // (dive/climb) is NOT here — it's the follower's cross-cutting water rule (hold jump to rise / sink to
        // descend toward the planned depth). So the controller aims along the line at full forward regardless of
        // any depth difference between the bot and the target.
        View seg = new View(0, 56, 0, 10, 56, 0);
        FakeBot above = new FakeBot(5, 60, 0); // above the planned depth — swimTowards ignores the vertical gap
        SteerControl.swimTowards(above, seg);
        assertTrue(above.faceDx > 0 && Math.abs(above.faceDz) < EPS, "aims forward along the line");
        assertEquals(1.0f, above.forward, 1e-6f, "full forward — the bot swims where it faces");

        FakeBot below = new FakeBot(5, 52, 0); // below the planned depth — same horizontal drive, no vertical
        SteerControl.swimTowards(below, seg);
        assertTrue(below.faceDx > 0 && Math.abs(below.faceDz) < EPS, "still aims forward along the line");
        assertEquals(1.0f, below.forward, 1e-6f, "full forward regardless of depth");
    }

    @Test
    void swimTowards_pureVerticalSegment_stopsPushingWhenCentred() {
        // A degenerate (vertical) segment re-centres on the column; a bot already ON the column gets no
        // forward shove, and holdDepth alone drives the climb/dive.
        View column = new View(3, 56, 3, 3, 60, 3);
        FakeBot b = new FakeBot(3, 56, 3);
        SteerControl.swimTowards(b, column);
        assertEquals(0.0f, b.forward, 1e-6f, "no forward on a centred pure vertical — holdDepth handles the climb/dive");
    }

    @Test
    void swimTowards_pureVerticalSegment_offColumn_recentersOnTheColumn() {
        // Off-column on a vertical segment: carried momentum has drifted the bot off the swim column, where
        // the exact-cell reach (footX/footZ match) can never fire — the drive must pull it back, exactly as
        // steerTowards' degenerate branch does.
        View column = new View(3, 56, 3, 3, 60, 3);
        FakeBot b = new FakeBot(3.7, 57, 3);
        SteerControl.swimTowards(b, column);
        assertTrue(b.faceDx < 0, "faces back toward the column (−x)");
        assertEquals(0.7f, b.forward, 1e-6f, "forward ≈ horizontal offset (recenterOnTarget's proportional pull)");
    }

    @Test
    void swimPitched_pureVerticalSegment_offColumn_recentersWhileKeepingTheDepthPitch() {
        // The prone drive's degenerate branch station-keeps over the column (swimPitchedCentered's law):
        // yaw toward the column centre, forward proportional to the offset. Centred behaviour (pure depth
        // pitch, no push) is unchanged.
        View column = new View(3.5, 56, 3.5, 3.5, 60, 3.5);
        FakeBot drifted = new FakeBot(4.6, 57, 3.5);
        SteerControl.swimPitched(drifted, column, SteerControl.SUBMERGE_BIAS);
        assertTrue(drifted.faceDx < 0, "faces back toward the column (−x)");
        assertEquals(1.0f, drifted.forward, 1e-6f, "full proportional pull while a whole block off");

        FakeBot centred = new FakeBot(3.5, 57, 3.5);
        SteerControl.swimPitched(centred, column, SteerControl.SUBMERGE_BIAS);
        assertEquals(0.0f, centred.forward, 1e-6f, "centred: pure depth pitch, no horizontal push");
    }

    @Test
    void swimServo_pureVerticalSegment_offColumn_stationKeepsOverTheColumn() {
        View column = new View(3.5, 56, 3.5, 3.5, 60, 3.5);
        FakeBot b = new FakeBot(4.1, 57, 3.5);
        b.inWater = true;
        SteerControl.swimServo(b, column, SteerControl.SUBMERGE_BIAS);
        assertTrue(b.faceDx < 0, "faces back toward the column (−x)");
        assertEquals(0.6f, b.forward, 1e-6f, "forward ≈ horizontal offset (recenterOnTarget's proportional pull)");
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
            b.velX = (b.velX + ax) * 0.8;                      // vanilla water tick: thrust, then drag
            b.velZ = (b.velZ + az) * 0.8;
            b.x += b.velX;
            b.z += b.velZ;
        }
        assertEquals(3, b.footX(), "converged back onto the column cell (pre-fix: stranded at footX 4 forever)");
        assertEquals(3, b.footZ(), "never left the column's z lane");
        assertTrue(Math.abs(b.x - 3.5) < 0.3, "holds near the column centre (damped, not orbiting): off by " + Math.abs(b.x - 3.5));
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
        SteerControl.steerViaGate(b, seg, 0.7, 1.3);
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
        SteerControl.steerViaGate(b, seg, 0.7, 1.3);
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
        SteerControl.steerViaGate(shortOf, seg, 0.7, 1.3);
        assertTrue(shortOf.faceDx < 0 && shortOf.faceDz > 0,
                "short of the gate the drive leaves the centerline for the gate (the hug's whole point)");
        // ON the line just past it (along 0.66 ≥ the deadbanded threshold): the target owns the aim — dead
        // ahead along the diagonal, equal components.
        FakeBot past = new FakeBot(0.5 + 0.66 * ux, 1, 0.5 + 0.66 * uz);
        SteerControl.steerViaGate(past, seg, 0.7, 1.3);
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
        SteerControl.steerViaGate(b, seg, 0.7, 1.3);
        assertTrue(b.faceDx < 0, "faces against the lateral momentum (bleeds it)");
        assertTrue(b.faceDz > 0, "while still thrusting toward the gate");
        assertEquals(1.0f, b.forward, 1e-6f, "error well past the servo deadband → saturated forward");
    }
}
