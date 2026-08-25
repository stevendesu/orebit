package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link PhaseRunner}'s <b>"stop and fix the geometry" hold</b> must actually stop
 * ({@link SteerControl#stationKeep}) — convicted on the 2026-08-01 flagship wedge at {@code (58,133,189)}.
 *
 * <h2>The witnessed geometry (frozen master world, verified against the region file)</h2>
 * <pre>
 *        x=57      x=58      x=59             x=60
 *  y133  air       air       vine             jungle_leaves
 *  y132  air       VINE      jungle_leaves    jungle_leaves
 *  y131  air       vine      air              air
 *  y130  air       vine      air              air
 *  y129  air       vine      air              air
 *  y128  leaves    leaves    vine             air
 * </pre>
 * The bot climbs the {@code x=58} curtain to feet <b>132</b> — its TOP cell, anchored on the leaves at
 * {@code (59,132,189)}. The plan then steps laterally east at the SAME height: {@code Traverse} from floor
 * {@code (58,131,189)} to floor {@code (59,131,189)}, PLACING the floor (that cell is air) and MINING the
 * jungle leaves at {@code (59,132,189)} that occupy the destination's feet cell.
 *
 * <h2>The bug</h2>
 * The runner held for the unmet {@code Need.AIR} and called {@link SteerControl#recenterOnTarget} — the
 * <i>target</i> column being {@code x=59}, that drove the bot at full forward into the very leaf block it
 * was mining. Feet inside a curtain, a horizontal press is vanilla's involuntary climb
 * ({@code (horizontalCollision || jumping) && onClimbable → vy=+0.2}): +0.2/tick, a full block every 5
 * ticks, and nothing is mined that fast. The bot ratcheted 132.007 → 133.026, left the curtain, and
 * grounded ON the leaves — one cell above the frame its plan was built from, a permanent fail→HOLD
 * ({@code expectTakeoffFoot.y=132}, {@code botFoot=(58,133,189)}).
 *
 * <p>These tests pin the two halves of the stance the hold must keep: <b>no press</b> (so the ratchet can
 * never fire) and <b>sneak</b> (so the −0.15 climbable slide can't drop it off the frame either).
 */
class StationKeepHoldTest {

    private static final int FROM_X = 58, FLOOR_Y = 131, Z = 189;
    private static final int TO_X = 59;
    private static final int FOOT_Y = 132;              // feet inside the curtain's TOP cell
    private static final int OBSTRUCTION_X = 59, OBSTRUCTION_Y = 132; // the jungle leaves to mine

    /** A bot whose destination feet cell is genuinely obstructed, so the runner's needs loop always holds. */
    private static final class HoldBot implements BotSteering {
        double x = FROM_X + 0.5, y = FOOT_Y, z = Z + 0.5;
        boolean grounded, climbable = true, climbBelow;
        boolean jumping, sneaking, sprinting;
        float forward = Float.NaN;
        double faceDx = Double.NaN, faceDz = Double.NaN;
        int minedX = Integer.MIN_VALUE, minedY, minedZ;

        HoldBot at(double x) { this.x = x; return this; }

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
        @Override public boolean climbableBelow() { return climbBelow; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { sprinting = s; }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int bx, int by, int bz) { return true; }
        @Override public boolean airAt(int bx, int by, int bz) { return true; }
        /** Only the jungle leaves in the destination's feet cell obstruct — the live geometry that holds. */
        @Override public boolean movementBlockedAt(int bx, int by, int bz, int dx, int dz) {
            return bx == OBSTRUCTION_X && by == OBSTRUCTION_Y && bz == Z;
        }
        @Override public void mine(int bx, int by, int bz) { minedX = bx; minedY = by; minedZ = bz; }
        @Override public void place(int bx, int by, int bz) { }
        @Override public void setDoorOpen(int bx, int by, int bz, boolean open) { }
        @Override public boolean doorOpenAt(int bx, int by, int bz) { return true; }
        @Override public boolean swimHazardAt(int bx, int by, int bz) { return false; }
        @Override public boolean bubbleUpAt(int bx, int by, int bz) { return false; }
        @Override public double slipperinessAt(int bx, int by, int bz) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int bx, int by, int bz) { return false; }
    }

    /** The lateral step's segment, feet frame: (58.5,132,189.5) → (59.5,132,189.5). */
    private static final class LateralSeg implements SteerView {
        @Override public double sx() { return FROM_X + 0.5; }
        @Override public double sy() { return FOOT_Y; }
        @Override public double sz() { return Z + 0.5; }
        @Override public double tx() { return TO_X + 0.5; }
        @Override public double ty() { return FOOT_Y; }
        @Override public double tz() { return Z + 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** An in-place move's segment (Pillar / MineDown): the target column IS the bot's own column. */
    private static final class InPlaceSeg implements SteerView {
        @Override public double sx() { return FROM_X + 0.5; }
        @Override public double sy() { return FOOT_Y; }
        @Override public double sz() { return Z + 0.5; }
        @Override public double tx() { return FROM_X + 0.5; }
        @Override public double ty() { return FOOT_Y + 1; }
        @Override public double tz() { return Z + 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** The Traverse's shape: one phase needing the destination's two body cells clear. */
    private static MovePlan lateralPlan() {
        MovePlan plan = new MovePlan().moveDir(1, 0);
        // Plan-carried breaks (owner ruling 2026-08-25) — see PhaseRunnerDoorTest for the contract.
        plan.requireBreak(TO_X, FLOOR_Y + 1, Z);
        plan.requireBreak(TO_X, FLOOR_Y + 2, Z);
        plan.phase("walk")
                .need(MovePlan.Need.AIR, TO_X, FLOOR_Y + 1, Z)
                .need(MovePlan.Need.AIR, TO_X, FLOOR_Y + 2, Z);
        return plan;
    }

    private static PhaseRunner runnerOn(MovePlan plan) {
        PhaseRunner r = new PhaseRunner();
        r.begin(plan);
        return r;
    }

    // ---- the fix: the hold does not press into its own obstruction ------------------------------------

    @Test
    void curtainHoldDoesNotPressIntoTheBlockItIsMining() {
        HoldBot b = new HoldBot();
        runnerOn(lateralPlan()).run(b, new LateralSeg());

        assertEquals(OBSTRUCTION_X, b.minedX, "the hold must still mine the obstruction");
        assertEquals(0.0f, b.forward, 1e-6f,
                "centred on its own column, the hold drives EXACTLY zero — a press into the leaves is the "
                        + "wall-press climb (+0.2/tick) that ratchets the bot out of the curtain");
    }

    @Test
    void curtainHoldSneaksToKeepItsHeight() {
        HoldBot b = new HoldBot();
        runnerOn(lateralPlan()).run(b, new LateralSeg());

        assertTrue(b.sneaking,
                "feet inside a curtain: only sneak holds height. Without it the bot slides the -0.15 "
                        + "climbable clamp for the whole mine and leaves the plan's frame downward");
        assertFalse(b.jumping, "jump would CLIMB from inside the curtain, not hold");
    }

    @Test
    void driftedCurtainHoldPullsBackToItsOwnColumnNotTowardTheObstruction() {
        HoldBot b = new HoldBot().at(58.94); // the witnessed drift: bbox already overlapping column 59
        runnerOn(lateralPlan()).run(b, new LateralSeg());

        assertTrue(b.forward > 0, "0.44 off centre is outside the deadband — the servo walks back");
        assertTrue(b.faceDx < 0,
                "back toward its OWN column (west), never further into the leaves it is mining — contact "
                        + "with that face is exactly what triggers the involuntary climb");
    }

    // ---- the guards: everything else keeps its old behaviour ------------------------------------------

    @Test
    void airborneHoldStillHomesOnTheLandingColumn() {
        HoldBot b = new HoldBot();
        b.grounded = false;
        b.climbable = false; // airborne: not grounded, not in water/lava, not on a climbable -> !settled()
        runnerOn(lateralPlan()).run(b, new LateralSeg());

        assertTrue(b.forward > 0 && b.faceDx > 0,
                "a bot in free flight cannot stop and fix — it keeps homing on the landing column, or an "
                        + "input cut mid-flight drops it short of a gap it is already committed across");
    }

    @Test
    void groundedHoldNeverSneaks() {
        HoldBot b = new HoldBot();
        b.grounded = true; // standing on a real block that merely has vine in its feet cell (jungle floor)
        runnerOn(lateralPlan()).run(b, new LateralSeg());

        assertFalse(b.sneaking,
                "sneak's ledge edge-guard would trap a grounded bot on the block it is leaving — the "
                        + "!grounded() conjunct is load-bearing (flagship 58.43 -> 212.55 without it)");
    }

    // ---- the curtain TOP-OUT must be a reachable stance ------------------------------------------

    /**
     * The 2026-08-01 flagship livelock at {@code (55,~140,207)}. A top-out waypoint satisfies none of
     * {@code grounded / inWater / inLava / onClimbable} — the climbable is UNDER the feet, not in them —
     * so {@link Movement#reached} could never fire and the climb bounced across the boundary forever
     * (20+ ticks alternating {@code footY} 139/140, no envelope failure because nothing failed).
     */
    @Test
    void toppedOutOnACurtainIsReachableByAClimbOnly() {
        HoldBot b = new HoldBot();
        b.grounded = false;
        b.climbable = false;   // feet are ABOVE the curtain, not inside it
        b.climbBelow = true;
        b.x = 3.5; b.y = 10; b.z = 4.5;

        assertTrue(MovementRegistry.CLIMB.reached(b, 3, 10, 4, null),
                "a top-out waypoint must be reachable, or the climb livelocks bouncing on the boundary");

        assertFalse(b.settled(),
                "but settled() must STAY strict: it is shared with the plan-anchor rule, and climbableBelow "
                        + "is also true for a bot in ballistic flight OVER a vine — widening it fired reached "
                        + "mid-parkour-arc at botY=113.905 and advanced the waypoint in mid-air");
        assertFalse(MovementRegistry.TRAVERSE.reached(b, 3, 10, 4, null),
                "every other movement keeps the strict ballistic test");
    }

    @Test
    void inPlaceHoldIsUnchanged() {
        HoldBot b = new HoldBot();
        b.grounded = true;
        b.climbable = false;
        runnerOn(lateralPlan()).run(b, new InPlaceSeg());

        assertEquals(0.0f, b.forward, 1e-6f,
                "Pillar / MineDown hold on their own column, so target-centred and self-centred agree — "
                        + "the change is a no-op for every vertical move");
    }
}
