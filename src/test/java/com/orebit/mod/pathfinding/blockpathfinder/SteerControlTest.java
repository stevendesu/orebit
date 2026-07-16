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

    /** Minimal {@link BotSteering} double: settable pose/medium, records what the controller wrote. */
    private static final class FakeBot implements BotSteering {
        double x, y, z;
        boolean grounded, inWater;
        // recorded outputs
        float forward = Float.NaN;
        double faceDx, faceDz;
        boolean sprinting, jumping, sank;

        FakeBot(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

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
        @Override public boolean inWater() { return inWater; }
        @Override public boolean inLava() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public boolean prone() { return false; }
        // Records the horizontal aim like faceHorizontally (pitch ignored) so the pure-geometry assertions on
        // faceDx/faceDz hold whether SteerControl aims via faceHorizontally or the 3-D faceTowards.
        @Override public void faceTowards(double dx, double dy, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void sinkInWater() { sank = true; }
        // Reconcile seam — unused by the pure-geometry SteerControl tests, stubbed to satisfy the interface.
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
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
    void swimTowards_pureVerticalSegment_stopsPushing() {
        // A degenerate (vertical) segment has no horizontal target, so swimTowards stops pushing forward and
        // lets the follower's water rule drive straight up/down the column.
        View column = new View(3, 56, 3, 3, 60, 3);
        FakeBot b = new FakeBot(3, 56, 3);
        SteerControl.swimTowards(b, column);
        assertEquals(0.0f, b.forward, 1e-6f, "no forward on a pure vertical — the water rule handles the climb/dive");
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
}
