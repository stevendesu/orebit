package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Fence gates, executor side (DESIGN-fence-gates.md §4/§7): the {@link PhaseRunner} openable pre-pass over
 * GATE reqs, headless — the gate sibling of {@link PhaseRunnerTrapdoorTest} (same MC-free {@link FakeBot}
 * pattern: settable solid/blocked/open state, records the executor verbs; {@code setGateOpen} mutates the
 * fake's open state so the shared {@code doorOpenAt} readback reflects the just-issued toggle, exactly as
 * the live level does). Pins:
 *
 * <ul>
 *   <li>a SET-governed CLOSED gate is TOGGLED — via the GATE verb ({@link BotSteering#setGateOpen}, the
 *       3-way kind-dispatch), never the door or trapdoor verb — and never mined, even though its plate
 *       genuinely obstructs ({@code movementBlockedAt} true);</li>
 *   <li>the readback CLEARS the need: once {@link BotSteering#doorOpenAt} reports the gate open, the next
 *       tick issues no further verb and the phase drives instead of holding — the property the
 *       {@code doorOpenAt} gate-kind coverage exists for (a gate-blind readback re-issues forever and the
 *       hold never clears);</li>
 *   <li>an already-open gate req issues NOTHING (the {@code doorOpenAt} gate, before any verb);</li>
 *   <li>verb routing is per-req kind: door, trapdoor and gate reqs on ONE plan each dispatch exactly their
 *       own steering verb, the same tick, with no cross-talk.</li>
 * </ul>
 *
 * The world mutation itself ({@code WorldEdits.setGateOpen}) and the follower drive are only verifiable
 * in-game — this pins the runner's dispatch/skip logic, the part that decides WHICH verb (if any) to issue.
 */
class PhaseRunnerGateTest {

    private static long key(int x, int y, int z) { return BlockPos.asLong(x, y, z); }

    /** A {@link BotSteering} double: settable solid/blocked/open state, records all three toggle verbs + mine. */
    private static final class FakeBot implements BotSteering {
        final Set<Long> solid = new HashSet<>();
        /** Cells whose live geometry genuinely obstructs the corridor — {@code movementBlockedAt}'s answer,
         *  kept SEPARATE from {@link #solid} so an open-but-solid openable stays representable. */
        final Set<Long> blocked = new HashSet<>();
        final Map<Long, Boolean> open = new HashMap<>();
        final List<long[]> setGateCalls = new ArrayList<>();     // {cell, open?1:0}
        final List<long[]> setTrapdoorCalls = new ArrayList<>(); // {cell, open?1:0}
        final List<long[]> setDoorCalls = new ArrayList<>();     // {cell, open?1:0}
        final List<Long> mineCalls = new ArrayList<>();

        @Override public boolean solidAt(int x, int y, int z) { return solid.contains(key(x, y, z)); }
        @Override public boolean airAt(int x, int y, int z) { return !solidAt(x, y, z); }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) {
            return blocked.contains(key(x, y, z));
        }
        @Override public void mine(int x, int y, int z) { mineCalls.add(key(x, y, z)); }
        @Override public void place(int x, int y, int z) { }
        @Override public boolean doorOpenAt(int x, int y, int z) {
            return open.getOrDefault(key(x, y, z), false); // the shared OPEN read (doors, trapdoors AND gates)
        }
        @Override public void setGateOpen(int x, int y, int z, boolean o) {
            setGateCalls.add(new long[] { key(x, y, z), o ? 1 : 0 });
            open.put(key(x, y, z), o); // the world now reflects the toggle (an open gate has no collision)
        }
        @Override public void setTrapdoorOpen(int x, int y, int z, boolean o) {
            setTrapdoorCalls.add(new long[] { key(x, y, z), o ? 1 : 0 });
            open.put(key(x, y, z), o);
        }
        @Override public void setDoorOpen(int x, int y, int z, boolean o) {
            setDoorCalls.add(new long[] { key(x, y, z), o ? 1 : 0 });
            open.put(key(x, y, z), o);
        }

        int setGateCount(int x, int y, int z, boolean o) {
            int n = 0;
            for (long[] c : setGateCalls) if (c[0] == key(x, y, z) && (c[1] == 1) == o) n++;
            return n;
        }
        int setTrapdoorCount(int x, int y, int z, boolean o) {
            int n = 0;
            for (long[] c : setTrapdoorCalls) if (c[0] == key(x, y, z) && (c[1] == 1) == o) n++;
            return n;
        }
        int setDoorCount(int x, int y, int z, boolean o) {
            int n = 0;
            for (long[] c : setDoorCalls) if (c[0] == key(x, y, z) && (c[1] == 1) == o) n++;
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
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** A trivial non-degenerate segment so a HOLD's stationKeep/recenter has a column to pull toward. */
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

    private static final int GX = 6, GY = 1, GZ = 6; // the gate cell

    @Test
    void closedGateIsToggledViaGateVerbNotMined() {
        FakeBot bot = new FakeBot();
        // A CLOSED gate: solid AND genuinely corridor-blocking (the centered plate crosses every approach).
        bot.solid.add(key(GX, GY, GZ));
        bot.blocked.add(key(GX, GY, GZ));
        bot.open.put(key(GX, GY, GZ), false);

        MovePlan mp = new MovePlan();
        mp.requireGate(GX, GY, GZ, true); // the folded SET_OPEN, injected as a gate req
        mp.phase("cross")
                .need(MovePlan.Need.AIR, GX, GY, GZ) // the gate's body cell — must NOT be mined
                .drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        boolean done = runner.run(bot, new View());

        assertFalse(done, "the move holds while the gate is being opened, not complete");
        assertEquals(MovePlan.Need.OPEN, runner.holdNeed(), "the runner holds on the OPEN req, not an AIR mine");
        assertEquals(1, bot.setGateCount(GX, GY, GZ, true), "the closed gate is opened via the GATE verb");
        assertTrue(bot.setDoorCalls.isEmpty(), "kind dispatch: a gate req never drives the DOOR verb");
        assertTrue(bot.setTrapdoorCalls.isEmpty(), "kind dispatch: a gate req never drives the TRAPDOOR verb");
        assertFalse(bot.mined(GX, GY, GZ), "the gate is TOGGLED, never mined, despite genuinely obstructing");
    }

    @Test
    void readbackClearsTheNeedWithoutReissue() {
        FakeBot bot = new FakeBot();
        bot.solid.add(key(GX, GY, GZ));
        bot.open.put(key(GX, GY, GZ), false);

        MovePlan mp = new MovePlan();
        mp.requireGate(GX, GY, GZ, true);
        mp.phase("cross").drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());
        assertEquals(1, bot.setGateCount(GX, GY, GZ, true), "tick 1 issues the toggle");
        assertEquals(MovePlan.Need.OPEN, runner.holdNeed(), "…and holds while establishing");

        // The fake's setGateOpen mutated its open state — the shared doorOpenAt readback now reports OPEN.
        // A gate-blind readback (doorOpenAt not covering FenceGateBlock) would mismatch forever and
        // re-issue every tick: the permanent livelock the readback's gate coverage exists to prevent.
        runner.run(bot, new View());
        assertEquals(1, bot.setGateCount(GX, GY, GZ, true), "the satisfied req is NOT re-issued");
        assertNull(runner.holdNeed(), "the need cleared — the phase drives instead of holding");
        assertTrue(bot.mineCalls.isEmpty(), "SET-governed cells are never mined");
    }

    @Test
    void alreadyOpenGateIssuesNoVerb() {
        FakeBot bot = new FakeBot();
        // An OPEN gate along the corridor, governed by a req already at its target state.
        bot.open.put(key(GX, GY, GZ), true);

        MovePlan mp = new MovePlan();
        mp.requireGate(GX, GY, GZ, true);
        mp.phase("cross").drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());

        assertTrue(bot.setGateCalls.isEmpty(), "an already-correct gate is not toggled (doorOpenAt gate)");
        assertTrue(bot.setDoorCalls.isEmpty() && bot.setTrapdoorCalls.isEmpty(),
                "no other verb is issued either");
        assertNull(runner.holdNeed(), "nothing to establish — the phase drives instead of holding");
        assertTrue(bot.mineCalls.isEmpty(), "nothing is mined");
    }

    @Test
    void threeWayVerbRoutingDispatchesEachKind() {
        // One plan, three openable reqs of DISTINCT kinds, all mismatched: the pre-pass iterates every req
        // per tick, so all three verbs are issued the same tick — each through its OWN dispatch arm.
        final int DX = 4, DY = 1, DZ = 6; // the door cell
        final int TX = 5, TY = 2, TZ = 6; // the trapdoor cell
        FakeBot bot = new FakeBot();
        bot.open.put(key(DX, DY, DZ), false);
        bot.open.put(key(TX, TY, TZ), false);
        bot.open.put(key(GX, GY, GZ), false);

        MovePlan mp = new MovePlan();
        mp.requireDoor(DX, DY, DZ, true);
        mp.requireTrapdoor(TX, TY, TZ, true);
        mp.requireGate(GX, GY, GZ, true);
        mp.phase("cross").drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());

        assertEquals(1, bot.setDoorCount(DX, DY, DZ, true), "the door req drives the DOOR verb at its cell");
        assertEquals(1, bot.setTrapdoorCount(TX, TY, TZ, true), "the trapdoor req drives the TRAPDOOR verb at its cell");
        assertEquals(1, bot.setGateCount(GX, GY, GZ, true), "the gate req drives the GATE verb at its cell");
        assertEquals(1, bot.setDoorCalls.size(), "exactly one door verb — no cross-talk onto other kinds' cells");
        assertEquals(1, bot.setTrapdoorCalls.size(), "exactly one trapdoor verb");
        assertEquals(1, bot.setGateCalls.size(), "exactly one gate verb");
        assertEquals(MovePlan.Need.OPEN, runner.holdNeed(), "the runner holds on the OPEN reqs while establishing");

        // All three readbacks now report the target — the next tick issues nothing further.
        runner.run(bot, new View());
        assertEquals(1, bot.setDoorCalls.size(), "satisfied reqs are not re-issued");
        assertEquals(1, bot.setTrapdoorCalls.size(), "…for any kind");
        assertEquals(1, bot.setGateCalls.size(), "…including the gate");
        assertTrue(bot.mineCalls.isEmpty(), "SET-governed cells are never mined");
    }
}
