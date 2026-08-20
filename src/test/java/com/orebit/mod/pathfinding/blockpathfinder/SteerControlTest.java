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
        @Override public double slipperinessAt(int x, int y, int z) { return slip; }
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


    // ---- GROUND hazard probes: the cell walk + the planned-descent exemption -------------------------
    // Owner ruling 2026-08-20, the (340,69,481) creep-wedge conviction (flagship r8). Two independent
    // defects were fixed in the probe machinery, and these pin both:
    //   1a  pathDropsAhead's leg-alignment test (dot >= STRAIGHT_DOT) was the wrong QUESTION — it asked
    //       "is the next leg straight ahead?" and, when it fired, suppressed EVERY void probe around the
    //       waypoint. Replaced by an exact per-probe-cell test (plannedDescentCell): a cell is exempt iff
    //       it IS the next waypoint's column and the next waypoint lies below the current one.
    //   1b  the probe walk was DEGENERATE on diagonals — cells came from F.c* + round(u*k) with u
    //       EUCLIDEAN-normalised, so on a 45 deg leg round(0.70711*1) == round(0.70711*2) == 1 and both k
    //       named the same cell: HAZARD_LOOKAHEAD=2 inspected one cell twice and never read the cell
    //       genuinely two ahead. Replaced by a CHEBYSHEV cell walk (u / max(|ux|,|uz|)), one cell per k.
    // The frame under test: a waypoint FEET cell at y=69 (ty 69.0 -> F.cy 69), so the lane floor a probe
    // reads is y=68 (groundVoidColumn = !solidAt(x, F.cy-1, z)).

    /** 1a: a colinear planned descent exempts its OWN landing cell — and NOTHING else. The old whole-
     *  predicate boolean suppressed both probes, so a genuine second void two cells on was invisible. */
    @Test
    void groundOvershoot_plannedDescentExemptsOnlyItsLandingCell() {
        // +x leg to the waypoint at cell (11,10); the plan then steps DOWN into (12,10). Probes: (12,10), (13,10).
        View descendAhead = new View(10.5, 69, 10.5, 11.5, 69, 10.5, true, 12.5, 68.0, 10.5);
        // Only the planned landing cell lacks a floor -> the plan's own drop, not a hazard.
        FakeBot planned = new FakeBot(11.0, 69, 10.5);
        planned.withSolid(13, 68, 10);
        assertFalse(SteerControl.groundOvershootHazard(planned, descendAhead),
                "the cell the plan descends into is the plan's own trajectory, never a hazard");
        // The cell BEYOND it is void too — an unplanned drop the bot would coast into. Pre-fix this was
        // suppressed with the landing cell (one boolean for both probes) and the servo cruised into it.
        FakeBot beyond = new FakeBot(11.0, 69, 10.5);
        assertTrue(SteerControl.groundOvershootHazard(beyond, descendAhead),
                "a genuine void two cells off the route is still caught (the exemption is PER CELL)");
    }

    /** 1a: the exemption is gated on the next waypoint actually being BELOW — a level next leg leaves the
     *  same missing floor a hazard, which is what stops an ordinary walk-off being read as a descent. */
    @Test
    void groundOvershoot_levelNextLegLeavesTheDropAHazard() {
        View levelAhead = new View(10.5, 69, 10.5, 11.5, 69, 10.5, true, 12.5, 69.0, 10.5);
        FakeBot b = new FakeBot(11.0, 69, 10.5);
        b.withSolid(13, 68, 10);                       // only the far probe has a floor
        assertTrue(SteerControl.groundOvershootHazard(b, levelAhead),
                "no planned descent -> the missing floor at (12,10) is an off-lane walk-off");
    }

    /** 1a, the FLANK half: a descent that turns ACROSS the lane. The old alignment test read dot = 0 and
     *  declared no planned drop, so the plan's own landing column — sitting one step perpendicular to
     *  travel — was braked for as a flank void. The per-cell test exempts exactly that column. */
    @Test
    void groundFlank_plannedDescentAcrossTheLaneIsExempt() {
        // +x leg to waypoint cell (11,10); the plan then steps DOWN into (11,11) — the +z FLANK cell.
        View turnDown = new View(10.5, 69, 10.5, 11.5, 69, 10.5, true, 11.5, 68.0, 11.5);
        FakeBot b = new FakeBot(11.0, 69, 10.5);
        b.withSolid(11, 68, 9);                        // the OTHER flank is solid ground
        assertFalse(SteerControl.groundFlankHazard(b, turnDown),
                "the flank the plan descends into is the plan's own step-down, not a lane hazard");
        // The opposite flank is a real unplanned drop and must still fire.
        FakeBot otherSide = new FakeBot(11.0, 69, 10.5);
        otherSide.withSolid(11, 68, 11);
        assertTrue(SteerControl.groundFlankHazard(otherSide, turnDown),
                "an unplanned void on the OTHER flank is still a hazard");
    }

    /** 1b: on a 45 deg leg the two probes must name two DIFFERENT cells. Pre-fix both k rounded to the
     *  same cell, so a void exactly two cells along the diagonal was never read at all. */
    @Test
    void groundOvershoot_diagonalWalksTwoDistinctCells() {
        // Diagonal (+x,+z) leg to waypoint cell (11,11): Chebyshev walk visits (12,12) then (13,13).
        View diagonal = new View(10.5, 69, 10.5, 11.5, 69, 11.5);
        FakeBot b = new FakeBot(11.0, 69, 11.0);
        b.withSolid(12, 68, 12);                       // the first cell along the diagonal HAS a floor
        assertTrue(SteerControl.groundOvershootHazard(b, diagonal),
                "the cell two along the diagonal (13,13) is void — pre-fix k=2 re-read (12,12) and missed it");
        b.withSolid(13, 68, 13);                       // floor the second cell too
        assertFalse(SteerControl.groundOvershootHazard(b, diagonal),
                "both walked cells floored -> no overshoot hazard");
    }

    /** 1b: a CARDINAL leg is byte-identical under the Chebyshev step (max(|ux|,|uz|) == 1), in both
     *  travel senses — the walk is exactly the waypoint cell +1 and +2 along the axis. */
    @Test
    void groundOvershoot_cardinalWalkIsUnchanged() {
        View plusX = new View(10.5, 69, 10.5, 11.5, 69, 10.5);
        FakeBot near = new FakeBot(11.0, 69, 10.5);
        near.withSolid(13, 68, 10);
        assertTrue(SteerControl.groundOvershootHazard(near, plusX), "k=1 cell is (12,10) — void");
        FakeBot far = new FakeBot(11.0, 69, 10.5);
        far.withSolid(12, 68, 10);
        assertTrue(SteerControl.groundOvershootHazard(far, plusX), "k=2 cell is (13,10) — void");
        FakeBot floored = new FakeBot(11.0, 69, 10.5);
        floored.withSolid(12, 68, 10).withSolid(13, 68, 10);
        assertFalse(SteerControl.groundOvershootHazard(floored, plusX), "both floored — nothing to brake for");

        // −z sense: waypoint cell (10,9), walk (10,8) then (10,7).
        View minusZ = new View(10.5, 69, 10.5, 10.5, 69, 9.5);
        FakeBot back = new FakeBot(10.5, 69, 10.0);
        back.withSolid(10, 68, 8);
        assertTrue(SteerControl.groundOvershootHazard(back, minusZ), "k=2 cell is (10,7) — void");
        back.withSolid(10, 68, 7);
        assertFalse(SteerControl.groundOvershootHazard(back, minusZ), "both floored — nothing to brake for");
    }


    // ---- the LAND drive's hazard MODE SWITCH (owner ruling 2026-08-20, Phase 2) ----------------------
    // groundServo's hazard branch is retired: drive() computes the hazard verdict once and routes the whole
    // tick to arriveOnStep (position-anchored ARRIVE on the near-face point) instead of a speed schedule.

    /** The flagship-r8 creep-wedge pose, verbatim: step 2 Diagonal floor (339,68,480)→(340,68,481), bot at
     *  (339.926, 481.006) with vel (0.0963, 0.0651), grounded and ON its line (cte 0.0566), the plan turning
     *  into a Descend at (341,67,481). The retired speed schedule produced a velocity error of magnitude
     *  0.02817 — a hair over SERVO_DEADBAND — and faced THAT: yaw −178.9°, forward 0.51. A half-throttle
     *  thrust due NORTH at a target east-north-east. The position-anchored ARRIVE wants −55.2° at full
     *  forward, and that is what the drive must now command. */
    @Test
    void drive_hazardCorner_arrivesOnTheNearFace_insteadOfPirouetting() {
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
        assertTrue(SteerControl.lastDrive.endsWith("arrive:stephaz"),
                "hazard routes to the near-face ARRIVE, not servo:hazard; got " + SteerControl.lastDrive);
    }

    /** The A/B revert leg is untouched by the mode switch: a SAFE straight still runs the pursuit servo. */
    @Test
    void drive_safeStraight_stillRunsThePursuitServo() {
        View straight = new View(10.5, 69.0, 10.5, 11.5, 69.0, 10.5);
        FakeBot b = new FakeBot(11.0, 69.0, 10.5);
        b.grounded = true;
        b.withSolid(12, 68, 10).withSolid(13, 68, 10)     // floored ahead — nothing to brake for
         .withSolid(11, 68, 11).withSolid(11, 68, 9);     // …and floored on both flanks
        SteerControl.drive(b, straight);
        assertTrue(SteerControl.lastDrive.endsWith("servo:thrust"),
                "a safe corner keeps the pursuit servo; got " + SteerControl.lastDrive);
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
