package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SteerControl#holdClimbableStance} must press SNEAK on a climbable only when there is genuinely
 * nothing underneath to arrest a step-off — convicted on the 2026-08-02 jungle-vine wedge at
 * {@code (60.289, 171.022, 255.500)}.
 *
 * <h2>The witnessed geometry (jungle vine curtain over a leaf canopy)</h2>
 * <pre>
 *   y=171   VINE            &lt;- the bot's FEET cell (60,171,255); botY = 171.022
 *   y=170   vine            &lt;- climbableBelow == true (a SECOND vine, not a floor)
 *   y=169   jungle_leaves   &lt;- the first STANDABLE: top face y=170.0, a 1.02 block step-off
 * </pre>
 * The plan's {@code Descend} targets floor {@code (60,170,255)}, i.e. target FEET y = {@code 171.00} and
 * target column centre {@code x=60.5}.
 *
 * <h2>The measured stall (per tick, from the in-game capture)</h2>
 * <pre>
 *   botY=171.172  sneak=false  hcol=false  dm=(0.0747,-0.2254,0.0120)   &lt;- free climbable slide
 *   botY=171.022  sneak=TRUE   hcol=false  dm=(0.0793,-0.2254,0.0072)   &lt;- sneak engages
 *   x=60.289      sneak=true   hcol=false  dm=(0.0568,-0.0784,0.0000)   &lt;- frozen forever
 * </pre>
 * {@code hcol=false} on EVERY tick: there was no horizontal collision anywhere, so nothing was being
 * pressed against — yet a non-zero {@code deltaMovement} was commanded every tick and produced ZERO
 * displacement. That signature is vanilla's SNEAK ledge edge-guard ({@code
 * Entity/Player.maybeBackOffFromEdge}), not a wall press. The bot's box west edge sat at {@code 59.989},
 * still overhanging the unsupported column {@code x=59}; release needs {@code x >= 60.300} and it was
 * pinned {@code 0.011} short of it, forever.
 *
 * <p><b>Why sneak engaged.</b> The bot was {@code 0.022} ABOVE its target feet height {@code 171.00} —
 * inside the gate's {@code 0.05} {@code descending} margin — so {@code descending} read false, the feet
 * were inside a climbable, and sneak was pressed. Sneak is two effects at once: it zeroes the
 * {@code −0.15}/t climbable slide (wanted) and it arms the edge-guard (fatal here). One input, both
 * symptoms.
 *
 * <p><b>What these tests pin</b> (owner ruling 2026-08-02) — the rule is about WHAT IS UNDERNEATH:
 * lateral travel on a climbable with NO standable below still sneaks (arrest our own fall via the
 * climbable, unchanged); with a standable below it must NOT sneak (nothing to arrest — just walk off).
 * The narrowing only ever REMOVES a sneak press, never adds one, so it cannot reintroduce the opposite
 * regression that {@link #groundedVineOverSolidGroundStillNeverSneaks} guards (flagship best 58.43
 * &rarr; 212.55 when the {@code !grounded()} conjunct was lost).
 */
class ClimbableWalkOffStanceTest {

    private static final int FOOT_X = 60, FOOT_Y = 171, FOOT_Z = 255;
    /** The Descend's target floor cell (60,170,255) — itself a vine. */
    private static final int TARGET_FLOOR_Y = 170;
    /** Target FEET y = floor + 1 = 171.00 — only 0.022 BELOW the witnessed botY, inside the 0.05 margin. */
    private static final double TARGET_FEET_Y = TARGET_FLOOR_Y + 1;

    /** The witnessed pose: pinned 0.211 short of the target column centre x=60.5. */
    private static final double WEDGED_X = 60.289, WEDGED_Y = 171.022;

    /**
     * A pose-settable steering double exposing the three seams this gate reads
     * ({@link BotSteering#grounded}, {@link BotSteering#onClimbable}, {@link BotSteering#standableBelow})
     * plus {@link BotSteering#climbableBelow} — true in the convicted geometry, so it also pins that the
     * two stance branches stay mutually exclusive.
     */
    private static final class VineBot implements BotSteering {
        double x = WEDGED_X, y = WEDGED_Y, z = FOOT_Z + 0.5;
        /** Suspended on the vine: no floor under the feet, and hcol=false — nothing pressed against. */
        boolean grounded = false;
        /** The feet cell (60,171,255) IS a vine. */
        boolean climbable = true;
        /** (60,170,255) is a SECOND vine — the cell below is climbable, not a floor. */
        boolean climbBelow = true;
        /** (60,169,255) = jungle_leaves: the first real floor, top face 1.02 blocks under the feet. */
        boolean standable = true;

        // recorded inputs (NaN/false = "never written")
        boolean jumping, sneaking, sprinting;
        float forward = Float.NaN;
        double faceDx = Double.NaN, faceDz = Double.NaN;

        VineBot at(double x, double y) { this.x = x; this.y = y; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        /** Vertical velocity — the hang-vs-slide discriminator {@link BotSteering#hangingOnClimbable} reads. */
        double vy = 0;
        @Override public double velY() { return vy; }
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
        @Override public boolean standableBelow() { return standable; }
        @Override public void faceHorizontally(double dx, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void faceTowards(double dx, double dy, double dz) { faceDx = dx; faceDz = dz; }
        @Override public void setForward(float zza) { forward = zza; }
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

    /**
     * A segment in the feet-target world frame. The load-bearing number is {@link #ty()}: the wedge exists
     * because {@code ty=171.00} is only {@code 0.022} below {@code botY=171.022}, inside the gate's
     * {@code 0.05} {@code descending} margin.
     */
    private static final class Seg implements SteerView {
        final double tx, ty, tz;
        /**
         * The step's ORIGIN height, in the SAME "feet-cell + 1.0" frame {@code BotNavigator.SegmentCursor}
         * builds — i.e. a step standing at feet cell 169 has {@code sy == 170.0}. The servo reads vertical
         * INTENT as the frame-free delta {@code ty - sy}, and drops back to the bot's frame ({@code ty - 1.0})
         * for any absolute comparison. Getting that wrong is off-by-exactly-one-block and was the measured
         * 2026-08-02 bug, so these fixtures state the frame rather than leaving it implied.
         */
        double sy = FOOT_Y;
        Seg(double tx, double ty, double tz) { this.tx = tx; this.ty = ty; this.tz = tz; }
        Seg from(double sy) { this.sy = sy; return this; }
        @Override public double sx() { return FOOT_X + 0.5; }
        @Override public double sy() { return sy; }
        @Override public double sz() { return FOOT_Z + 0.5; }
        @Override public double tx() { return tx; }
        @Override public double ty() { return ty; }
        @Override public double tz() { return tz; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** The witnessed step: lateral travel to the target column centre x=60.5, target feet y=171.00. */
    private static Seg lateralSeg() {
        return new Seg(FOOT_X + 0.5, TARGET_FEET_Y, FOOT_Z + 0.5);
    }

    // ---- CASE A: the witnessed wedge --------------------------------------------------------------

    @Test
    void climbableWithAStandableUnderneathDoesNotSneak() {
        VineBot b = new VineBot();          // the exact witnessed pose: (60.289, 171.022, 255.5)
        SteerControl.drive(b, lateralSeg()); // via the LOCOMOTION path — the wedge happened under a drive()

        assertFalse(b.sneaking,
                "the jungle_leaves at (60,169,255) hold a floor 1.02 blocks under the feet — there is no "
                        + "fall to arrest, so sneak buys no height and costs everything: its ledge edge-guard "
                        + "deletes the horizontal motion that would carry the box past the unsupported x=59 "
                        + "lip, freezing the bot at x=60.289 when it needs x>=60.300 to reach the column "
                        + "centre 60.5 (measured: hcol=false, non-zero deltaMovement, ZERO displacement, "
                        + "forever). With a floor below, just walk off");
    }

    // ---- The SCOPE refutations (adversarial review, 2026-08-02) ------------------------------------
    // The owner's rule is scoped to "moving LATERALLY". A first implementation put the relaxation inside
    // holdClimbableStance itself, which is shared by the locomotion drive() AND the runner's stationKeep()
    // mine/place hold — silently applying "just walk off" to a bot that is deliberately standing still.
    // These pin the scope so the relaxation can never leak back onto the stationary path.

    @Test
    void stationKeepOnAHangStillSneaksEvenWithAFloorBelow() {
        VineBot b = new VineBot();          // the convicted column itself: leaves 1.02 blocks under the feet
        SteerControl.stationKeep(b, lateralSeg());

        assertTrue(b.sneaking,
                "stationKeep is the NOT-moving case (PhaseRunner's mine/place hold): it re-centres on the "
                        + "bot's OWN column and emits exact zero forward inside COLUMN_DEADBAND, so the ledge "
                        + "edge-guard costs nothing here while the slide-suppression is the entire value. "
                        + "Dropping the hold would slide the bot -0.15/t for the whole mine (>=5 ticks ~ a "
                        + "full block) and ground it one cell BELOW the frame its plan was built from — the "
                        + "already-convicted (58,133,189) bug, re-entered from the opposite direction");
    }

    @Test
    void theBareHoldNeverRelaxes_onlyTheDrivingPathDoes() {
        VineBot hold = new VineBot();
        SteerControl.holdClimbableStance(hold, lateralSeg());   // 2-arg = the pure hold
        assertTrue(hold.sneaking, "the bare hold must keep the stance regardless of what is underneath — "
                + "the relaxation is opt-in for callers that are actually translating the bot");

        VineBot driven = new VineBot();
        SteerControl.holdClimbableStance(driven, lateralSeg(), true);
        assertFalse(driven.sneaking, "…and the translating overload is the one that walks off");
    }

    // ---- settled(): being IN a climbable is not being HELD by one ----------------------------------
    // The vine-cascade root cause. A falling bot is onClimbable() every tick of its descent, so folding that
    // straight into settled() made Movement.reached()'s ballistic fly-through guard useless inside a curtain:
    // three consecutive Descends fired reached mid-fall (botY 171.922 -> 170.922 -> 169.922, grounded=false
    // throughout), each ABANDONED mid-phase, each handing the next step an airborne pose, until the bot sat
    // three blocks below its plan in the vines.

    @Test
    void aBotSlidingDownAVineIsNotSettled() {
        VineBot b = new VineBot();
        b.grounded = false;
        b.climbable = true;
        b.vy = -0.15;                       // the vanilla climbable slide clamp: falling, not hanging
        assertFalse(b.settled(),
                "a bot SLIDING down a vine is ballistic, not supported — if settled() admits it, "
                        + "Movement.reached() fires mid-fall and the cursor advances onto a step the bot "
                        + "never stood at (the measured 3-step Descend cascade)");
    }

    @Test
    void aHeldHangIsSettledEvenAtFallVelocity() {
        // The case velocity cannot see: the stored deltaMovement of a HELD hang (-0.0784) is identical to the
        // first tick of a genuine fall, so the conservative threshold alone reads it as sliding and the cursor
        // never advances off a step the bot has actually completed. The sneak INPUT is the arrest itself, so
        // asking it separates the two without loosening the threshold (measured: loosening to -0.12 let a
        // starting fall count as settled and dropped the bot out of the vine column to y=152).
        VineBot held = new VineBot();
        held.grounded = false;
        held.climbable = true;
        held.vy = -0.0784;
        held.sneaking = true;
        assertTrue(held.settled(), "sneak held on a climbable IS the arrest — that is a settled hang");

        VineBot falling = new VineBot();
        falling.grounded = false;
        falling.climbable = true;
        falling.vy = -0.0784;               // same number…
        falling.sneaking = false;           // …but nothing is arresting it
        assertFalse(falling.settled(), "the identical velocity with no arrest input is a fall, not a hang");
    }

    @Test
    void aBotHangingOnAVineIsSettled() {
        VineBot b = new VineBot();
        b.grounded = false;
        b.climbable = true;
        // MEASURED value of a held hang, not an idealised 0: velY() reports the stored deltaMovement, which
        // runs one tick of gravity ahead of vanilla's climbable clamp. A bot whose y was provably frozen at
        // 168.022 read -0.0784 every tick, while a real slide reads the -0.15 clamp.
        b.vy = 0.0;
        assertTrue(b.settled(),
                "an ARRESTED hang is a legitimate anchor — Fall may land in one, and the follower is "
                        + "allowed to plan from it");
    }

    @Test
    void reachedDoesNotFireWhileFallingThroughAVine() {
        VineBot b = new VineBot();
        b.grounded = false;
        b.climbable = true;
        b.vy = -0.15;
        // Feet cell exactly ON the waypoint — the fly-through: position matches, support does not.
        assertFalse(MovementRegistry.TRAVERSE.reached(b, b.footX(), b.footY(), b.footZ()),
                "position alone must never advance the cursor: the falling bot's feet block merely TRANSITS "
                        + "this stand cell");

        b.vy = 0.0;                     // the measured held-hang reading (see aBotHangingOnAVineIsSettled)
        assertTrue(MovementRegistry.TRAVERSE.reached(b, b.footX(), b.footY(), b.footZ()),
                "…and the same cell DOES count once the hang has arrested — otherwise the cursor never "
                        + "advances off a step the bot has actually completed, and it holds there forever");
    }

    // ---- SETTLED IS A BAND, AND THE SERVO ANTICIPATES (owner ruling, 2026-08-03) -------------------
    // "Settled on the floor of a cell" is [X.00, X.20] inclusive — the bot is 1.8 tall, so at X.20 its head
    // still occupies the headroom cells the planner assumed. A POINT test was a knife-edge no descent could
    // hit: measured 173.875 -> 173.425 -> 172.975 at 0.45 b/t, straight over a 0.1-wide window, out of the
    // vine, and 2.5 blocks past the landing.

    @Test
    void aBotAnywhereInTheSettleBandCountsAsArrived() {
        // Target feet cell 173 => ty = 174.0 in the +1 frame. Band is [173.00, 173.20].
        for (double y : new double[] { 173.00, 173.10, 173.20 }) {
            VineBot b = new VineBot().at(61.5, y);
            b.standable = false;                    // a hang: nothing under the feet to catch it
            SteerControl.drive(b, new Seg(61.5, 174.00, 253.5).from(175.00)); // intent: descend
            assertTrue(b.sneaking,
                    "y=" + y + " is settled on cell 173's floor — the servo must hold, not keep descending");
        }
    }

    @Test
    void aBotStillAboveTheBandKeepsDescending() {
        VineBot b = new VineBot().at(61.5, 173.425);  // the measured overshoot tick
        b.standable = false;
        b.vy = -0.15;                                  // clamped rate: next tick 173.275, still above band
        SteerControl.drive(b, new Seg(61.5, 174.00, 253.5).from(175.00));
        assertFalse(b.sneaking,
                "0.425 above the floor is NOT settled — holding here strands the bot high, which is the "
                        + "Descend bad-frame bug we just fixed coming back the other way");
    }

    @Test
    void theServoTapsSneakRatherThanOvershootTheFloor() {
        // The measured killer tick: 173.425 falling at 0.45/t would land at 172.975 — BELOW cell 173's
        // floor, feet out of the vine with nothing left to grab. The servo must arrest this tick.
        VineBot b = new VineBot().at(61.5, 173.425);
        b.standable = false;
        b.vy = -0.45;
        SteerControl.drive(b, new Seg(61.5, 174.00, 253.5).from(175.00));

        assertTrue(b.sneaking,
                "a tick that would carry the feet below the target cell's floor must tap sneak NOW — "
                        + "reacting only once inside the band is too late at 0.45 b/t, and overshooting "
                        + "drops the bot out of the vine entirely (measured: rode to 170.5, 2.5 blocks low)");
    }

    @Test
    void noAnticipationInFreeAirWhereSneakCannotArrest() {
        VineBot b = new VineBot().at(61.5, 173.425);
        b.climbable = false;                           // free fall — sneak arrests nothing
        b.climbBelow = false;
        b.standable = false;
        b.vy = -0.45;
        SteerControl.drive(b, new Seg(61.5, 174.00, 253.5).from(175.00));
        assertFalse(b.sneaking, "off a climbable there is nothing to arrest against; the drop is the plan's "
                + "own business and pressing sneak would only arm the ledge edge-guard");
    }

    // ---- The Y-axis stance TABLE (owner's servo model, 2026-08-02) ---------------------------------
    // The step's vertical intent has THREE cases, not two, and each is re-evaluated per tick from live state:
    //   +1 rise      -> hold JUMP (climbs on a climbable, jumps off one — same press)
    //    0 hold      -> in a climbable: SNEAK iff nothing standable below; above a curtain: JUMP
    //   -1 descend   -> hold NOTHING (the -0.15/t slide IS the climb-down; off a climbable it is the fall)
    // A single `descending` flag collapsed +1 into 0 and pressed SNEAK at a bot trying to climb — measured on
    // the flagship as a Climb to (61,169,252), ty=170.00 vs botY=169.055 (Δy=+0.945), frozen at z=253.700.

    @Test
    void risingOnAClimbableHoldsJumpNotSneak() {
        VineBot b = new VineBot().at(61.5, 169.055);
        b.standable = false;                            // (61,168,253) is a vine — nothing to catch it
        // feet cell 169 -> 170, in the +1 frame: sy=170, ty=171. Δy intent = +1, and the bot at 169.055 is
        // genuinely a block short of it ((171-1) - 169.055 = +0.945).
        SteerControl.drive(b, new Seg(61.5, 171.00, 252.5).from(170.00));

        assertTrue(b.jumping, "a step whose feet target is a block ABOVE the bot is a CLIMB: jump is the "
                + "climb input on a climbable. This is the measured (61,169,253) wedge — the old single "
                + "`descending` flag read false and pressed sneak, pinning the bot at its height forever");
        assertFalse(b.sneaking, "…and sneak must NOT be pressed: it holds the bot at exactly the height it is "
                + "trying to leave, and arms the edge-guard that kills the lateral half of the same step");
    }

    @Test
    void risingOffAClimbableAlsoHoldsJump() {
        VineBot b = new VineBot().at(61.5, 169.055);
        b.climbable = false;
        b.climbBelow = false;
        b.standable = true;
        SteerControl.drive(b, new Seg(61.5, 171.00, 252.5).from(170.00));

        assertTrue(b.jumping, "off a climbable the same +1 intent is an ordinary jump — one press covers both "
                + "media, so the rise case needs no medium test at all");
    }

    @Test
    void descendingHoldsNothingRegardlessOfMedium() {
        for (boolean climbable : new boolean[] { true, false }) {
            VineBot b = new VineBot().at(61.5, 171.022);
            b.climbable = climbable;
            SteerControl.drive(b, new Seg(61.5, FOOT_Y - 3, 253.5).from(FOOT_Y)); // step intent: a DESCEND

            assertFalse(b.sneaking, "a descend must hold nothing: on a climbable the -0.15/t slide IS the "
                    + "climb-down (climbable=" + climbable + ")");
            assertFalse(b.jumping, "…and certainly not jump, which would climb back up (climbable="
                    + climbable + ")");
        }
    }

    @Test
    void theStanceIsReEvaluatedPerTick_sneakEngagesOnlyOnceTheBoxClearsTheLip() {
        // The servo point: nothing latches. The SAME step, same target, differs only in what is under the
        // bot's box RIGHT NOW — and that is exactly what flips as the box crosses a lip mid-step.
        Seg step = lateralSeg();

        VineBot overhanging = new VineBot();            // still over the supporting column -> floor below
        overhanging.standable = true;
        SteerControl.drive(overhanging, step);
        assertFalse(overhanging.sneaking, "while the box still overhangs a floor, sneak stays OFF so the "
                + "edge-guard cannot block the crossing");

        VineBot cleared = new VineBot();                // box now fully in the unsupported column
        cleared.standable = false;
        SteerControl.drive(cleared, step);
        assertTrue(cleared.sneaking, "the instant the box clears into a column with nothing below, the hold "
                + "engages by itself — half the movement unsneaked, half sneaked, with no state and no timers");
    }

    @Test
    void climbLateralTransferKeepsItsHoldOverACanopy() {
        // Climb's lateral regime calls holdClimbableStance DIRECTLY (not through drive), because a lateral
        // vine transfer wants to HOLD its planned height, not descend onto whatever is underneath. Jungle
        // canopy always has leaves a couple of cells down, so a relaxation that keyed only on standableBelow
        // would sink every such transfer a full cell below its waypoint — and Climb has no failWhen envelope
        // to catch it (it is one of the three remaining steer-only moves), so it would latch there.
        VineBot b = new VineBot();
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertTrue(b.sneaking,
                "a Climb lateral transfer over a canopy must keep the slide-suppression: its plan holds "
                        + "height F, and leaves two cells down are not an invitation to drop onto them");
    }

    @Test
    void climbableWithAStandableUnderneathDoesNotJumpEither() {
        VineBot b = new VineBot();          // climbBelow is ALSO true here: (60,170,255) is a second vine
        SteerControl.drive(b, lateralSeg());

        assertFalse(b.jumping,
                "the two stance branches must stay mutually exclusive: feet INSIDE a climbable, refused "
                        + "sneak, must not fall through to the topped-out JUMP branch — in this very geometry "
                        + "climbableBelow is true (a second vine), and jumping would climb the bot back UP "
                        + "its own curtain instead of stepping off");
    }

    @Test
    void theWedgeIsInsideTheDescendingMarginSoTheOldGuardCannotSaveIt() {
        VineBot b = new VineBot();
        Seg seg = lateralSeg();

        assertTrue(seg.ty() >= b.y() - 0.05,
                "the pre-existing 'descending' release is 0.05 wide and the bot was only 0.022 above its "
                        + "target feet height (171.022 vs 171.00) — it reads NOT-descending, which is exactly "
                        + "why sneak engaged. This pins the fixture ON the bug: widening that margin is not "
                        + "the fix under test, standableBelow is");
    }

    // ---- CASE B: the fall-arrest stance is preserved ----------------------------------------------

    @Test
    void climbableOverOpenAirStillSneaks() {
        VineBot b = new VineBot();
        b.standable = false;                // a genuine hang: nothing but air under the curtain
        b.climbBelow = false;
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertTrue(b.sneaking,
                "nothing underneath to catch us, so the fall is ours to arrest and the climbable is the "
                        + "only thing that can arrest it — sneak is the sole stance-hold here (jump CLIMBS "
                        + "from inside a curtain, it does not hold). Unchanged behaviour: the narrowing only "
                        + "ever removes a sneak press, never adds one");
        assertFalse(b.jumping, "jump would climb out of the curtain, not hold the hang");
    }

    @Test
    void climbableOverASecondClimbableWithNoFloorStillSneaks() {
        VineBot b = new VineBot();
        b.standable = false;                // deep curtain: vine below vine below vine, no floor in range
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertTrue(b.sneaking,
                "a vine under a vine is not a floor — climbableBelow must not be mistaken for standable, "
                        + "or a bot mid-curtain would lose its arrest stance and slide the whole descent");
    }

    // ---- CASE C: the ubiquitous jungle ground-vine (the 58.43 -> 212.55 guard) --------------------

    @Test
    void groundedVineOverSolidGroundStillNeverSneaks() {
        VineBot b = new VineBot();
        b.grounded = true;                  // standing on real ground that merely has vine in its feet cell
        b.climbBelow = false;
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertFalse(b.sneaking,
                "ground-level vine is everywhere in jungle; sneak's ledge edge-guard would trap a grounded "
                        + "bot on the block it is leaving. The !grounded() conjunct is load-bearing (flagship "
                        + "best regressed 58.43 -> 212.55 without it) and the standableBelow narrowing must "
                        + "not be allowed to substitute for it");
        assertFalse(b.jumping, "a grounded bot needs no stance-hold at all");
    }

    @Test
    void groundedInAVineCurtainWithNothingBelowStillNeverSneaks() {
        VineBot b = new VineBot();
        b.grounded = true;
        b.standable = false;                // the standableBelow test alone would say "sneak" — !grounded wins
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertFalse(b.sneaking,
                "the two guards are ANDed, not ORed: !grounded() must still veto on its own, so no "
                        + "standableBelow reading can ever re-arm the edge-guard on a grounded bot");
    }

    // ---- the untouched branches -------------------------------------------------------------------

    @Test
    void anHonestDescentStillReleasesTheStance() {
        VineBot b = new VineBot();
        b.standable = false;                // would otherwise sneak — the descending guard must still win
        SteerControl.holdClimbableStance(b, new Seg(FOOT_X + 0.5, FOOT_Y - 3, FOOT_Z + 0.5));

        assertFalse(b.sneaking,
                "the segment genuinely wants to go DOWN, the one case where sinking IS the intent — the "
                        + "pre-existing 'descending' release is untouched by this fix");
    }

    @Test
    void toppedOutOnACurtainStillHoldsJump() {
        VineBot b = new VineBot();
        b.climbable = false;                // feet ABOVE the curtain, not inside it
        b.climbBelow = true;
        b.standable = false;
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertTrue(b.jumping,
                "the top-out branch is untouched: a vine has no collision, so the bot sinks in and vanilla "
                        + "re-lifts it — only a held jump out-runs the sink at the surface. Sneak is wrong "
                        + "here for the same edge-guard reason (it would forbid stepping off the curtain top)");
        assertFalse(b.sneaking, "sneak would trap the topped-out bot on the curtain");
    }

    @Test
    void aToppedOutBotOverAFloorAlsoStillHoldsJump() {
        VineBot b = new VineBot();
        b.climbable = false;
        b.climbBelow = true;
        b.standable = true;                 // the new seam reads true, but it gates the SNEAK branch only
        SteerControl.holdClimbableStance(b, lateralSeg());

        assertTrue(b.jumping,
                "standableBelow gates the feet-INSIDE sneak, nothing else — the topped-out jump hold must "
                        + "not acquire a new precondition it never had");
    }
}
