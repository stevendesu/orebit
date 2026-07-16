package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * DOORS P3 (executor side): the {@link PhaseRunner} door-open path, headless. The runner drives the bot only
 * through the MC-free {@link BotSteering} seam, so this needs <b>no Minecraft</b> — a {@link FakeBot} records
 * {@link BotSteering#setDoorOpen} / {@link BotSteering#mine} calls and answers {@link BotSteering#doorOpenAt} /
 * {@link BotSteering#solidAt} from settable in-memory state (mutated by {@code setDoorOpen}, so a re-validate
 * reflects the just-issued toggle). Verifies the invariants the CRITICAL note demands:
 *
 * <ul>
 *   <li>a closed door governed by a {@link MovePlan#requireDoor} is OPENED ({@code setDoorOpen(target)}), and
 *       its body cell is NEVER mined even though an open door still reads {@link BotSteering#solidAt};</li>
 *   <li>an already-correct door is left alone (no redundant toggle) — the gate reads {@code doorOpenAt}, not
 *       {@code solidAt};</li>
 *   <li>a NON-door solid body cell is still mined (the door skip is specific to door cells);</li>
 *   <li>the exit double-toggle fires a CLOSE.</li>
 * </ul>
 *
 * The world-mutation itself (vanilla {@code DoorBlock.setOpen}) and the follower drive are only verifiable
 * in-game — this pins the runner's gate/skip logic, the part that decides WHEN to toggle vs mine.
 */
class PhaseRunnerDoorTest {

    private static long key(int x, int y, int z) { return BlockPos.asLong(x, y, z); }

    /** A {@link BotSteering} double: settable solid/door state, records setDoorOpen + mine calls. */
    private static final class FakeBot implements BotSteering {
        final Set<Long> solid = new HashSet<>();
        final Map<Long, Boolean> doorOpen = new HashMap<>();
        final List<long[]> setDoorCalls = new ArrayList<>();   // {cell, open?1:0}
        final List<Long> mineCalls = new ArrayList<>();

        @Override public boolean solidAt(int x, int y, int z) { return solid.contains(key(x, y, z)); }
        @Override public boolean airAt(int x, int y, int z) { return !solidAt(x, y, z); }
        @Override public void mine(int x, int y, int z) { mineCalls.add(key(x, y, z)); }
        @Override public void place(int x, int y, int z) { }
        @Override public boolean doorOpenAt(int x, int y, int z) {
            return doorOpen.getOrDefault(key(x, y, z), false);
        }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) {
            setDoorCalls.add(new long[] { key(x, y, z), open ? 1 : 0 });
            doorOpen.put(key(x, y, z), open); // the world now reflects the toggle (a closed door stays solidAt)
        }

        int setDoorCount(int x, int y, int z, boolean open) {
            int n = 0;
            for (long[] c : setDoorCalls) if (c[0] == key(x, y, z) && (c[1] == 1) == open) n++;
            return n;
        }
        boolean mined(int x, int y, int z) { return mineCalls.contains(key(x, y, z)); }

        // ---- unused pose/velocity/medium seam (no locomotion under test) ----
        @Override public double x() { return 0; }
        @Override public double y() { return 0; }
        @Override public double z() { return 0; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return 0; }
        @Override public int footY() { return 0; }
        @Override public int footZ() { return 0; }
        @Override public boolean grounded() { return true; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public boolean prone() { return false; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void sinkInWater() { }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** A trivial non-degenerate segment so a HOLD's recenterOnTarget has a column to pull toward. */
    private static final class View implements SteerView {
        @Override public double sx() { return 6.5; }
        @Override public double sy() { return 1.0; }
        @Override public double sz() { return 6.5; }
        @Override public double tx() { return 6.5; }
        @Override public double ty() { return 1.0; }
        @Override public double tz() { return 6.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    private static final int DX = 6, DY = 1, DZ = 6; // the door's feet-body cell

    @Test
    void closedDoorIsOpenedAndNeverMined() {
        FakeBot bot = new FakeBot();
        // A CLOSED door: both body halves are solid (an open door is solid too — that is the whole point).
        bot.solid.add(key(DX, DY, DZ));
        bot.solid.add(key(DX, DY + 1, DZ));
        bot.doorOpen.put(key(DX, DY, DZ), false);
        bot.doorOpen.put(key(DX, DY + 1, DZ), false);

        MovePlan mp = new MovePlan();
        mp.requireDoor(DX, DY, DZ, true);
        mp.requireDoor(DX, DY + 1, DZ, true);
        mp.phase("walk")
                .need(MovePlan.Need.AIR, DX, DY, DZ)       // the door's feet body cell — must NOT be mined
                .need(MovePlan.Need.AIR, DX, DY + 1, DZ)   // the door's head body cell
                .drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        boolean done = runner.run(bot, new View());

        assertFalse(done, "the move holds while the door is being opened, not complete");
        assertEquals(MovePlan.Need.OPEN, runner.holdNeed(), "the runner holds on the door OPEN, not on an AIR mine");
        assertEquals(1, bot.setDoorCount(DX, DY, DZ, true), "the closed feet-half door is opened (SET_OPEN)");
        assertEquals(1, bot.setDoorCount(DX, DY + 1, DZ, true), "the closed head-half door is opened (SET_OPEN)");
        assertFalse(bot.mined(DX, DY, DZ), "the door is OPENED, never mined, despite reading solidAt");
        assertFalse(bot.mined(DX, DY + 1, DZ), "neither door half is mined");
    }

    @Test
    void alreadyOpenDoorIsNotToggledAndNonDoorWallIsStillMined() {
        FakeBot bot = new FakeBot();
        // The door is already OPEN (but still solid — thin collision box); a separate non-door wall is solid.
        bot.solid.add(key(DX, DY, DZ));
        bot.solid.add(key(DX + 1, DY, DZ)); // a plain wall cell, NOT a door
        bot.doorOpen.put(key(DX, DY, DZ), true);

        MovePlan mp = new MovePlan();
        mp.requireDoor(DX, DY, DZ, true);
        mp.phase("walk")
                .need(MovePlan.Need.AIR, DX, DY, DZ)        // door cell — skipped
                .need(MovePlan.Need.AIR, DX + 1, DY, DZ)    // non-door wall — mined
                .drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());

        assertEquals(0, bot.setDoorCount(DX, DY, DZ, true), "an already-open door is NOT re-toggled (doorOpenAt gate)");
        assertFalse(bot.mined(DX, DY, DZ), "the open door cell is never mined (governed by the door req)");
        assertTrue(bot.mined(DX + 1, DY, DZ), "a NON-door solid body cell is still mined — the skip is door-specific");
    }

    @Test
    void exitDoubleToggleClosesTheDoor() {
        FakeBot bot = new FakeBot();
        // The exit segment: the door is currently OPEN (opened on entry) and must be CLOSED before driving away.
        bot.solid.add(key(DX, DY, DZ));
        bot.doorOpen.put(key(DX, DY, DZ), true);

        MovePlan mp = new MovePlan();
        mp.requireDoor(DX, DY, DZ, false); // SET_CLOSED — the exit half of the double-toggle
        mp.phase("walkOut")
                .need(MovePlan.Need.AIR, DX + 1, DY, DZ)   // the east exit cell (not the door)
                .drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());

        assertEquals(1, bot.setDoorCount(DX, DY, DZ, false), "the exit turn CLOSES the door (SET_CLOSED)");
        assertFalse(bot.mined(DX, DY, DZ), "closing is a toggle, never a mine");
    }

    @Test
    void alreadyClosedForAnExitCloseIsNotRedundantlyToggled() {
        FakeBot bot = new FakeBot();
        bot.solid.add(key(DX, DY, DZ));
        bot.doorOpen.put(key(DX, DY, DZ), false); // already closed

        MovePlan mp = new MovePlan();
        mp.requireDoor(DX, DY, DZ, false);
        mp.phase("walkOut").drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());

        assertEquals(0, bot.setDoorCalls.size(), "a door already in the target state is left alone (no redundant toggle)");
    }
}
