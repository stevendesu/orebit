package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

import org.junit.jupiter.api.Test;

/**
 * {@link Descend}'s terminal guard must fire on a <b>climbable landing</b> (owner ruling 2026-08-03).
 *
 * <p><b>Specimen — the {@code (58,171,254)} vine wedge.</b> The flagship route descends a jungle trunk whose
 * destination cell holds a vine. The bot arrived exactly where it was asked to: {@code botFoot=(58,171,254)},
 * {@code botY=171.022} (dead centre of the {@code [171.00, 171.20]} settle band), {@code reached=true}. But
 * STEP's {@code done} read {@code b.grounded()}, and a bot hanging on a vine is never grounded — so the
 * predicate was <i>unsatisfiable</i> for this whole class of destination. The move never completed, the
 * cursor never advanced, STEP kept driving full-forward, and vanilla's involuntary climb
 * ({@code (horizontalCollision || jumping) && onClimbable -> vy = +0.2}) ratcheted the bot a full block back
 * UP out of the cell until its head jammed against the leaf ceiling at {@code botY=172.200}. Captured live at
 * {@code 21:34:29} with {@code dm.y=+0.1176} while {@code jump=false} — the climb, not a jump.
 *
 * <p>The fix is {@link BotSteering#settled()}, the existing "supported in this medium" predicate that {@link
 * com.orebit.mod.pathfinding.blockpathfinder.Movement#reached} and {@code Fall}'s terminal guard already use.
 *
 * <p><b>What must NOT regress.</b> Widening grounded &rarr; settled must not let a merely-TRANSITING bot
 * claim the cell. {@code settled()} admits a climbable only when the bot is actually <i>held</i> by it
 * ({@code hangingOnClimbable}: sneak held, or vertical velocity above the {@code CLIMBABLE_ARREST_VY}
 * arrest threshold), so a bot free-falling through the column, or sliding down the vine on the {@code -0.15}
 * clamp, still reads unsettled. Both are pinned below — they are the reason this is a widening and not a
 * removal.
 */
class DescendVineLandingTest {

    /** The witnessed frame: Descend from floor (58,171,255) to floor (58,170,254) — a -z step down. */
    private static final int FX = 58, FY = 171, FZ = 255;
    private static final int TX = 58, TY = 170, TZ = 254;
    private static final int FROM_FOOT_Y = 172, TO_FOOT_Y = 171;

    /** A pose-settable bot that reports all plan geometry already established, so CLEAR never holds. */
    private static final class VineBot implements BotSteering {
        double x, y, z, vy;
        boolean grounded, climbable, climbBelow, sneaking, hcol;
        float forward;

        VineBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
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
        @Override public boolean horizontalCollision() { return hcol; }
        @Override public boolean sneakHeld() { return sneaking; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { }
        // Geometry: everything the plan asks for is already established (the real cells were vine + air).
        /** When true the step-down FOOTING reads missing, so the runner HOLDS and issues place() — the
         *  stop-and-fix state the (55,173,256) bot was in while its cobble was being placed. */
        boolean footingMissing;
        @Override public boolean solidAt(int x, int y, int z) { return !footingMissing; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return true; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** The step's segment in the feet frame: (58.5,172,255.5) -> (58.5,171,254.5). */
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

    /**
     * Run the real plan through the real runner and report whether the move completed. Two ticks: the first
     * advances the need-free CLEAR phase (its {@code advanceWhen} is unconditional), the second evaluates
     * STEP's terminal {@code done} against the pose under test.
     */
    private static boolean completes(VineBot bot) {
        // The ARRIVAL tests inject a DESTINATION pose, so the implicit start gate is deliberately NOT armed
        // — arming it would (correctly) refuse to run a plan whose start cell the bot is nowhere near, and
        // these cases are about the terminal guard, not entry. The ENTRY tests use run()/phaseAfter(),
        // which do arm it exactly as the follower does.
        MovePlan plan = new Descend().plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        SteerView view = new Seg();
        boolean done = false;
        for (int i = 0; i < 3; i++) done = runner.run(bot, view);
        return done;
    }

    /** Tick the real plan through the real runner {@code ticks} times; report whether the move completed. */
    private static boolean run(VineBot bot, int ticks) {
        MovePlan plan = new Descend().plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan, FROM_FOOT_Y);   // arm the implicit settle gate, as the follower does
        SteerView view = new Seg();
        boolean done = false;
        for (int i = 0; i < ticks; i++) done = runner.run(bot, view);   // settle gate -> CLEAR -> STEP(done)
        return done;
    }

    /** The phase cursor after {@code ticks} ticks — 0 CLEAR, 1 STEP (the settle gate precedes phase 0). */
    private static int phaseAfter(VineBot bot, int ticks) {
        MovePlan plan = new Descend().plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan, FROM_FOOT_Y);   // arm the implicit settle gate, as the follower does
        SteerView view = new Seg();
        for (int i = 0; i < ticks; i++) runner.run(bot, view);
        return runner.phase();
    }

    /** THE REGRESSION. Hanging on the destination vine, dead centre of the settle band: the move is DONE. */
    @Test
    void hangingOnTheDestinationVineCompletesTheDescend() {
        VineBot bot = new VineBot().at(58.508, 171.022, 254.5);
        bot.grounded = false;
        bot.climbable = true;   // the vine at (58,171,254)
        bot.vy = 0.0;           // held, not sliding
        assertTrue(completes(bot),
                "a bot HELD by the destination vine at the waypoint cell has reached it; gating on "
                        + "grounded() made this unsatisfiable and wedged the flagship at (58,171,254)");
    }

    /** The ordinary terrain landing must be unchanged by the widening. */
    @Test
    void groundedLandingStillCompletes() {
        VineBot bot = new VineBot().at(58.5, 171.0, 254.5);
        bot.grounded = true;
        assertTrue(completes(bot), "a plain grounded landing on the destination floor still completes");
    }

    /** A bot FREE-FALLING through the destination cell is transiting, not arriving. */
    @Test
    void ballisticTransitOfTheCellDoesNotComplete() {
        VineBot bot = new VineBot().at(58.5, 171.5, 254.5);
        bot.grounded = false;
        bot.climbable = false;
        bot.vy = -0.55;
        assertFalse(completes(bot),
                "settled() must not admit a ballistic transit — the bot is passing THROUGH the cell");
    }

    /**
     * A bot SLIDING down the vine past the cell is also not arrived. This is the discriminator that makes
     * settled() a widening rather than a removal: same cell, same climbable, but the {@code -0.15} clamp
     * velocity is below {@code CLIMBABLE_ARREST_VY}, so it is descending through, not held.
     */
    @Test
    void slidingDownTheVineDoesNotComplete() {
        VineBot bot = new VineBot().at(58.5, 171.5, 254.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = -0.15;         // the vanilla climbable slide clamp
        assertFalse(completes(bot),
                "a bot sliding down the vine on the -0.15 clamp is not HELD by it and has not arrived");
    }

    // ---- The SETTLE gate (owner ruling 2026-08-04) — see the phase comment in Descend.plan -------------

    /**
     * THE (58,169,253) SPECIMEN. A replan re-framed this step from an airborne bot hanging on a trunk vine
     * at {@code botY=170.896}, a full 0.9 above the {@code [170.00, 170.20]} band the plan assumes. SETTLE
     * must hold the step there: committing would drive the bot's real body (which reaches into cell
     * {@code fromFootY+2}) into a wall CLEAR never clears, and the forward press feeds the involuntary-climb
     * limit cycle. Frame-shifted onto this test's own step: fromFootY 172, so 172.896 is the same pose.
     */
    @Test
    void doesNotCommitWhileHangingAboveTheStartBand() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.896, 255.5);
        bot.grounded = false;
        bot.climbable = true;   // held by the trunk vine, well above the start block
        bot.vy = 0.0;
        assertEquals(0, phaseAfter(bot, 4),
                "SETTLE must hold a bot hanging above its own start block — the plan's geometry is framed "
                        + "from a resting pose and is wrong for a body one cell higher");
        assertFalse(run(bot, 4), "and the move certainly must not report complete");
    }

    /**
     * SETTLE must not pass a bot that is merely FALLING PAST the band (2026-08-05, the second
     * (55,173,256) miss). Height says where the bot is; it never says anything is holding it there.
     * Measured: the bot entered CLEAR at {@code botY=173.122} — inside {@code [173.00, 173.20]}, so a
     * height-only gate opened — while in free fall at {@code dm.y=-0.0784}. CLEAR and STEP then both ran
     * against a bot that was already leaving, and it fell out of the vine cell to 170.5.
     */
    @Test
    void doesNotCommitWhileFallingThroughTheStartBand() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.122, 255.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = -0.0784;       // in the band, but dropping — not held by anything
        assertEquals(0, phaseAfter(bot, 1),
                "in-band but falling is not settled — SETTLE must hold until something arrests the drop");
    }

    /** And SETTLE must ARREST that drop, or the gate above could never open on a hang. */
    @Test
    void settleArrestsTheDropAtTheStartFloor() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.05, 255.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = -0.15;         // next tick would carry the feet below fromFootY
        run(bot, 1);
        assertTrue(bot.sneaking,
                "SETTLE must tap sneak the tick before the feet leave the start cell; without it the gate "
                        + "is unsatisfiable on a hang and the bot sinks straight through the band");
    }

    /**
     * THE RUNNER'S HOLD must not release the vine stance while it is placing (2026-08-05, the third
     * (55,173,256) miss). While CLEAR's FOOTING is unmet the runner stops the bot and issues {@code place()}
     * every tick — but it delegated the stance to {@code holdClimbableStance}, whose descend branch consults
     * the MOVE's vertical intent, and every Descend's intent is "go down". So the servo released the stance
     * while the runner was still placing the block. Measured: sneak held one tick at {@code botY=173.043},
     * released the next with the cobble still unplaced, feet out of the vine at {@code 172.965}, free-fall
     * to 170.5. A hold is a hold, vertically too.
     */
    @Test
    void runnerHoldKeepsTheVineStanceWhilePlacing() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y, 255.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.footingMissing = true;   // CLEAR's step-down floor is absent → the runner holds and places
        bot.vy = 0.0;
        run(bot, 3);
        assertTrue(bot.sneaking,
                "the stop-and-fix hold must keep the bot on the vine while the step-down floor is placed; "
                        + "the move's descend intent is irrelevant while the runner has stopped it");
    }

    /** SETTLE must not press forward while it waits — the press is what feeds the climb ratchet. */
    @Test
    void settleDrivesNoForwardInput() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.896, 255.5);
        bot.grounded = false;
        bot.climbable = true;
        bot.forward = Float.NaN;                 // poisoned: a drive that never writes forward is a failure
        run(bot, 4);
        assertEquals(0.0f, bot.forward, 1.0e-6f,
                "SETTLE holds the bot's OWN column at zero forward; any press trips horizontalCollision and "
                        + "vanilla's (hcol && onClimbable -> vy=+0.2) ratchets the bot back up");
    }

    /** SETTLE must also not SNEAK — sneak pins the bot at the height it is trying to leave. */
    @Test
    void settleDoesNotSneakHoldOnTheVine() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.896, 255.5);
        bot.grounded = false;
        bot.climbable = true;
        run(bot, 4);
        assertFalse(bot.sneaking,
                "the settle gate is stationKeep MINUS the vertical hold; sneaking would hang it "
                        + "forever at the height it exists to leave");
    }

    /** Once settled onto the start block, the gate opens and the step proceeds. */
    @Test
    void settlingOntoTheStartBlockOpensTheGate() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y, 255.5);
        bot.grounded = true;
        assertTrue(phaseAfter(bot, 1) > 0, "a bot resting on its start block passes SETTLE on tick one");
    }

    /** The common case must stay free: a grounded walker never pays a tick for the gate. */
    @Test
    void groundedBotPassesTheGateOnTheFirstTick() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.05, 255.5);
        bot.grounded = true;
        assertTrue(phaseAfter(bot, 1) > 0, "SETTLE costs a grounded bot nothing — it advances same tick");
    }

    // ---- The CLEAR -> STEP hand-off tick (owner ruling 2026-08-04) --------------------------------------

    /**
     * THE (55,173,256) SPECIMEN. While CLEAR's geometry is unmet the RUNNER holds the bot (stationKeep →
     * sneak on a climbable). On the tick the geometry finally holds, the runner stops holding and calls
     * CLEAR's own drive — which used to only clear a boolean, pressing nothing. On solid ground that tick is
     * invisible; on a vine hang it is a 0.15 slide, which carried the feet out of the single supporting cell
     * and dropped the bot two blocks into the gap its own placed cobble had just walled off.
     *
     * <p>Measured: sneak held at {@code botY=173.043} through the place, released at the hand-off, feet left
     * cell 173 at {@code 172.965}, free-fall to 171.4. CLEAR must hold the stance on that tick.
     */
    @Test
    void clearHoldsTheClimbableStanceOnTheHandOffTick() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y + 0.043, 255.5);
        bot.grounded = false;
        bot.climbable = true;   // hanging on the vine, geometry already established (needs all met)
        bot.vy = 0.0;
        // Two ticks: SETTLE passes (in band), then CLEAR drives and advances the same tick.
        MovePlan plan = new Descend().plan(FX, FY, FZ, TX, TY, TZ, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan, FROM_FOOT_Y);   // arm the implicit settle gate, as the follower does
        SteerView view = new Seg();
        runner.run(bot, view);              // SETTLE -> CLEAR
        bot.sneaking = false;               // clear any prior press; CLEAR's own drive must re-assert it
        runner.run(bot, view);              // CLEAR drives + advances — the hand-off tick
        assertTrue(bot.sneaking,
                "CLEAR's drive must hold the climbable stance on the hand-off tick; a drive that presses "
                        + "nothing costs one un-sneaked tick, which slides the feet out of the vine cell");
    }

    /**
     * THE RELEASE-DROP GUARD. {@code Fall} §3.2 lets go of the bottom of a climbable deliberately — the
     * {@code cost=7.00} move the flagship trace offers at {@code (55,172,256)}. A 2026-08-04 attempt to fix
     * the vine drop-off by arresting "descending on a climbable with nothing beneath" broke exactly this:
     * {@code standableBelow()} reads ONE cell, so over any multi-block drop both terms are false and the
     * arrest fires, stranding the bot on the vine it was told to leave. The suite was green because nothing
     * covered it. The hold is column-gated in {@code Descend} now, and this pins the servo itself.
     */
    @Test
    void servoDoesNotArrestADeliberateReleaseDropOverAVoid() {
        VineBot bot = new VineBot().at(58.5, FROM_FOOT_Y, 255.5);
        bot.grounded = false;
        bot.climbable = true;    // on the last vine cell
        bot.climbBelow = false;  // nothing below — and nothing standable either
        bot.vy = -0.15;
        // A multi-block descent: the target floor is far below, so letting go IS the plan.
        SteerControl.holdClimbableStance(bot, new SteerView() {
            @Override public double sx() { return 58.5; }
            @Override public double sy() { return FROM_FOOT_Y; }
            @Override public double sz() { return 255.5; }
            @Override public double tx() { return 58.5; }
            @Override public double ty() { return FROM_FOOT_Y - 6; }
            @Override public double tz() { return 255.5; }
            @Override public boolean hasNext() { return false; }
            @Override public double nx() { return 0; }
            @Override public double ny() { return 0; }
            @Override public double nz() { return 0; }
        }, false);
        assertFalse(bot.sneaking,
                "a deliberate release-drop must let go; arresting it strands the bot on the climbable "
                        + "(Fall §3.2 — the 7.00 move the planner offers at the canopy vine)");
    }

    /**
     * ARRIVAL REQUIRES THE BAND, not merely the cell (owner ruling 2026-08-05). A move's arrival pose is the
     * PRECONDITION of the next move: plans are framed from "feet resting at wy", so a move that finishes
     * high in the cell hands its successor a frame up to a full block off — at which point the 1.8-tall box
     * spans THREE cells and fouls geometry nobody cleared.
     *
     * <p>Measured on the canopy vine: Descend completed at {@code botY=172.965} while hanging, instead of
     * resting on the cobble at 172.0; the following Diagonal was framed for feet at 172, executed with its
     * body reaching into cell 174, and the blocked press ratcheted it to the ceiling.
     */
    @Test
    void hangingHighInTheDestinationCellIsNotArrival() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 0.965, 254.5);
        bot.grounded = false;
        bot.climbable = true;   // held, and in the right CELL — but 0.965 up it, not resting in the band
        bot.vy = 0.0;
        assertFalse(completes(bot),
                "a hang high in the cell is not a resting pose; the next move's plan is framed from "
                        + "[wy.00, wy.20] and cannot be built on a bot a full block above that");
    }

    /** A GROUNDED bot is exempt — it is physically resting, so a partial floor seating it mid-cell is fine. */
    @Test
    void groundedMidCellStillCountsAsArrival() {
        VineBot bot = new VineBot().at(58.5, TO_FOOT_Y + 0.5, 254.5);
        bot.grounded = true;    // e.g. stood on a bottom slab / snow — feetYOf is topY-aware, y is correct
        assertTrue(completes(bot),
                "grounded means physically at rest on whatever surface was found; the band must not "
                        + "reject a legitimate partial-floor stance");
    }

    /**
     * A GROUNDED bot in a vine cell must NOT be sneak-held (2026-08-05). On a jungle trunk a vine commonly
     * shares its cell with solid footing, so {@code onClimbable()} is true while the bot is standing on a
     * block. Holding there buys nothing — the bot is not sliding — and sneak arms vanilla's
     * {@code maybeBackOffFromEdge}, which deletes exactly the horizontal motion a step-off needs. Measured
     * at {@code (58,171,254)}: sneak engaged the tick the bot entered the vine cell and {@code dm.x} decayed
     * {@code 0.078 -> 0.036} as the edge guard ate the walk-off, stalling the Descend on the lip.
     */
    @Test
    void groundedInAVineCellIsNotSneakHeld() {
        VineBot bot = new VineBot().at(58.069, FROM_FOOT_Y, 254.487);
        bot.grounded = true;    // standing on a block …
        bot.climbable = true;   // … whose cell also contains a vine
        SteerControl.holdUntilOverTargetColumn(bot, new Seg());
        assertFalse(bot.sneaking,
                "support underneath means nothing to hold; sneak here only arms the ledge edge-guard and "
                        + "stalls the step-off");
    }


    /**
     * THE RATCHET, at its root. Vanilla converts {@code (horizontalCollision || jumping) && onClimbable}
     * into {@code vy = +0.2}, so a press that cannot move the bot horizontally becomes pure altitude.
     * Measured three times: {@code (58,171,254)} to a leaf ceiling, {@code (57,172,255)} to 175.0, and
     * {@code (58,170,253)} where the bot was ALREADY in its target column and only needed to drop — every
     * one with {@code fwd=1.00}, {@code hcol=true} and horizontal {@code dm} of exactly 0.0000. The drive
     * must release the press rather than feed the climb.
     */
    @Test
    void blockedPressOnAClimbableIsReleased() {
        VineBot bot = new VineBot().at(58.62, TO_FOOT_Y + 1.08, 253.30);
        bot.grounded = false;
        bot.climbable = true;
        bot.hcol = true;          // pressing into geometry, going nowhere
        bot.forward = Float.NaN;  // poisoned: a drive that never writes forward is itself a failure
        SteerControl.drive(bot, new Seg());
        assertEquals(0.0f, bot.forward, 1.0e-6f,
                "a blocked press on a climbable is converted to +0.2 climb by vanilla; it must be released, "
                        + "not held");
    }

    /** Off a climbable the same collision is harmless — moves lean on it to slide along geometry. */
    @Test
    void blockedPressOffAClimbableIsNotReleased() {
        VineBot bot = new VineBot().at(58.62, TO_FOOT_Y, 253.30);
        bot.grounded = true;
        bot.climbable = false;
        bot.hcol = true;
        bot.forward = 0.0f;
        SteerControl.drive(bot, new Seg());
        assertTrue(bot.forward > 0.0f,
                "ordinary walking into a wall must be untouched; releasing there would break every move "
                        + "that slides along geometry");
    }

    /** Hanging on a vine at the WRONG cell is not arrival either — position still gates. */
    @Test
    void hangingAtTheWrongCellDoesNotComplete() {
        VineBot bot = new VineBot().at(58.5, 172.022, 255.5);   // still up on the FROM column
        bot.grounded = false;
        bot.climbable = true;
        bot.vy = 0.0;
        assertFalse(completes(bot), "settled() alone is not arrival — the waypoint cell must match");
    }
}
