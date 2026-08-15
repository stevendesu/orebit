package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link Climb#steer}'s Δy discriminator — the three input regimes vanilla climbable physics needs
 * ({@code jump} climbs at +0.2/t, {@code sneak} zeroes the −0.15/t slide, and NO input descends at the
 * clamp). The regression this guards (the flagship-GOTO vine freeze): the follower fed the steer a
 * MIXED-frame segment on step 0 (floor-cell start, feet-cell target), whose −1 cancelled — a climb-DOWN
 * read as Δy == 0, the lateral branch held SNEAK, and the vanilla sneak edge-guard pinned the bot on the
 * lip of its own placed block at the head of the descent, forever. The follower now feeds a uniform
 * feet-cell frame; these tests pin what each honest Δy must produce, so a frame regression on either
 * side surfaces as "descent sneaks" here.
 */
class ClimbSteerTest {

    /** Minimal {@link BotSteering} double: fixed pose, records the inputs the steer writes. */
    private static final class FakeBot implements BotSteering {
        final double x, y, z;
        float forward = Float.NaN;
        boolean jumping, sneaking;
        boolean onClimbable, scaffoldBelow; // seam-read states the sink-in branch discriminates on
        boolean grounded = true;            // lateral branch discriminates hang vs ledge (the edge-guard)

        FakeBot(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

        @Override public boolean onClimbable() { return onClimbable; }
        @Override public boolean scaffoldingBelow() { return scaffoldBelow; }

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
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** A segment in the cursor's world frame (feet-cell + 1, block-centre xz) — the follower's output. */
    private static final class View implements SteerView {
        final double sx, sy, sz, tx, ty, tz;
        View(double sx, double sy, double sz, double tx, double ty, double tz) {
            this.sx = sx; this.sy = sy; this.sz = sz; this.tx = tx; this.ty = ty; this.tz = tz;
        }
        @Override public double sx() { return sx; }
        @Override public double sy() { return sy; }
        @Override public double sz() { return sz; }
        @Override public double tx() { return tx; }
        @Override public double ty() { return ty; }
        @Override public double tz() { return tz; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /** The flagship-GOTO frame: feet 177 on the lip, descending the vine to feet 176 (Δy = −1). */
    @Test
    void climbDownGivesNoSneakAndNoJump() {
        // Segment: start feet-cell 177 → target feet-cell 176 (cursor frame: +1 on each).
        View seg = new View(55.5, 177.0, 256.5, 55.5, 176.0, 256.5);
        FakeBot bot = new FakeBot(55.5, 177.0, 256.29); // perched on the lip, slightly off-centre
        MovementRegistry.CLIMB.steer(bot, seg);
        assertFalse(bot.sneaking, "a descent must NOT sneak — the sneak edge-guard pins the bot on any lip");
        assertFalse(bot.jumping, "a descent must not jump (jumping climbs at +0.2/t)");
        assertTrue(bot.forward > 0.0f, "off-centre re-centre must keep driving the bot into the column");
    }

    /** The HANGING lateral cling — the stance the sneak exists for (it suppresses the −0.15/t slide). */
    @Test
    void lateralClingHoldsSneak() {
        View seg = new View(55.5, 177.0, 256.5, 56.5, 177.0, 256.5); // Δy == 0: ease across the surface
        FakeBot bot = new FakeBot(55.5, 177.2, 256.5); // sagging below the feet target (the vine sag)
        bot.grounded = false;   // mid-gap hang — nothing under the box
        bot.onClimbable = true;
        MovementRegistry.CLIMB.steer(bot, seg);
        assertTrue(bot.sneaking, "a hanging lateral cling must sneak — it suppresses the −0.15/t slide");
        assertFalse(bot.jumping, "a lateral cling must not jump (jumping ratchets UP the vine)");
    }

    /** The ledge→vine ENTRY lateral (the latvine-card convictions, 2026-07-31): a GROUNDED bot at the
     *  lip must JUMP-grab, never sneak. Sneak arms the vanilla edge-guard, which forbids the walk-off
     *  the transfer needs (measured: frozen at the lip forever, box edge 0.001 past the ledge, input
     *  pressing, spd 0.0000) — and a plain walk-off exits a feet-level vine row out its BOTTOM on the
     *  first airborne tick (measured: fell). The jump arc's falling re-entry passes the vine cell with
     *  sneak already engaged (airborne), so the slide suppression arrests in-cell. */
    @Test
    void lateralEntryFromLedgeJumpGrabs() {
        View seg = new View(55.5, 177.0, 256.5, 56.5, 177.0, 256.5); // Δy == 0: the entry grab
        FakeBot bot = new FakeBot(55.5, 177.0, 256.5); // standing on the takeoff ledge at the lip
        MovementRegistry.CLIMB.steer(bot, seg);        // grounded (default), feet cell not climbable
        assertFalse(bot.sneaking, "a grounded entry lateral must NOT sneak — the edge-guard pins the lip");
        assertTrue(bot.jumping, "the entry must JUMP — the arc is the only grab that arrests in the row");
        assertTrue(bot.forward > 0.0f, "the entry must keep driving off the lip toward the vine column");
    }

    /** A grounded lateral ON the climbable itself (the ladder-plate crossing): plain walk — no sneak
     *  (the same edge-guard), no jump (an in-climbable grounded jump is a truncated 0.2 hop). */
    @Test
    void lateralOnLadderPlateWalksPlain() {
        View seg = new View(55.5, 177.0, 256.5, 56.5, 177.0, 256.5); // Δy == 0: plate to plate
        FakeBot bot = new FakeBot(55.5, 177.0, 256.5); // standing ON the 3/16 plate
        bot.onClimbable = true;                        // feet cell IS the ladder
        MovementRegistry.CLIMB.steer(bot, seg);
        assertFalse(bot.sneaking, "a plate lateral must not sneak — the edge-guard would pin the plate");
        assertFalse(bot.jumping, "a plate lateral must not jump — collision carries the crossing");
        assertTrue(bot.forward > 0.0f, "the plate lateral keeps walking toward the next column");
    }

    @Test
    void climbUpHoldsJump() {
        View seg = new View(55.5, 176.0, 256.5, 55.5, 177.0, 256.5); // Δy = +1
        FakeBot bot = new FakeBot(55.5, 176.0, 256.5);
        MovementRegistry.CLIMB.steer(bot, seg);
        assertTrue(bot.jumping, "an ascent holds jump — vanilla climbs at +0.2/t while jumping");
        assertFalse(bot.sneaking, "an ascent must not sneak (sneak zeroes the climb)");
    }

    /** §3.5 sink-in through a scaffold DECK: grounded atop scaffolding, descending — sneak drops the
     *  stand-on-top shape. (NOTES-movement-physics.md §5.) */
    @Test
    void sinkThroughScaffoldDeckSneaks() {
        View seg = new View(55.5, 177.0, 256.5, 55.5, 176.0, 256.5); // Δy = −1
        FakeBot bot = new FakeBot(55.5, 177.0, 256.5);
        bot.scaffoldBelow = true; // grounded on the deck, feet cell open
        MovementRegistry.CLIMB.steer(bot, seg);
        assertTrue(bot.sneaking, "the scaffold sink-in must sneak — sneak removes the deck's top shape");
        assertFalse(bot.jumping, "the sink must not jump");
    }

    /** §3.5 sink-in off a LADDER plate: grounded, descending, NOT scaffolding — sneak would
     *  edge-guard-pin the bot ON the 3/16 plate; the recenter + gravity perform the sink instead. */
    @Test
    void ladderPlateSinkDoesNotSneak() {
        View seg = new View(55.5, 177.0, 256.5, 55.5, 176.0, 256.5); // Δy = −1
        // Standing ON the 3/16 plate strip means the bot's centre sits near the cell edge — well outside
        // the column deadband — so the re-centre must still push it off the strip toward the centre.
        FakeBot bot = new FakeBot(55.5, 177.0, 256.2);
        MovementRegistry.CLIMB.steer(bot, seg);
        assertFalse(bot.sneaking, "a ladder-plate sink must NOT sneak — the edge-guard would pin the bot");
        assertFalse(bot.jumping, "the sink must not jump");
        assertTrue(bot.forward > 0.0f, "the re-centre must walk the bot off the plate strip");
    }

    /** The vine-bounce fix's load-bearing detail (owner ruling 2026-07-31): within the column deadband
     *  the re-centre outputs EXACTLY zero forward — no input → no wall-press → no involuntary +0.2
     *  climb ratchet beside a trunk vine. */
    @Test
    void alignedDescendGivesExactZeroForward() {
        View seg = new View(55.5, 177.0, 256.5, 55.5, 176.0, 256.5); // Δy = −1
        FakeBot bot = new FakeBot(55.5, 177.5, 256.4); // 0.1 off centre — inside the 0.15 deadband
        MovementRegistry.CLIMB.steer(bot, seg);
        assertTrue(bot.forward == 0.0f, "aligned: exact zero forward, not an eased push; was " + bot.forward);
        assertFalse(bot.sneaking, "descending: no sneak");
        assertFalse(bot.jumping, "descending: no jump");
    }

    /** A mid-column hang descending (onClimbable): the sink-in branch must not fire — no inputs, the
     *  −0.15 clamp carries the descent (the pre-arc regime, unchanged). */
    @Test
    void hangDescendStaysInputFree() {
        View seg = new View(55.5, 177.0, 256.5, 55.5, 176.0, 256.5); // Δy = −1
        FakeBot bot = new FakeBot(55.5, 177.5, 256.5);
        bot.onClimbable = true;
        bot.scaffoldBelow = true; // even with scaffolding below: in-column descent needs NO sneak
        MovementRegistry.CLIMB.steer(bot, seg);
        assertFalse(bot.sneaking, "an in-column descent must not sneak (it would stop a ladder/vine)");
        assertFalse(bot.jumping, "an in-column descent must not jump (it would climb)");
    }
}
