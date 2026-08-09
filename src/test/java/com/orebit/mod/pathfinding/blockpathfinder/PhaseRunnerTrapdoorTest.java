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
 * Trapdoors, executor side (DESIGN-trapdoors.md §7/§9-6): the {@link PhaseRunner} openable pre-pass over
 * TRAPDOOR reqs, headless — the trapdoor sibling of {@link PhaseRunnerDoorTest} (same MC-free {@link FakeBot}
 * pattern: settable solid/blocked/open state, records the executor verbs; {@code setTrapdoorOpen} mutates the
 * fake's open state so a re-validate reflects the just-issued toggle). Pins:
 *
 * <ul>
 *   <li>a SET-governed CLOSED trapdoor is TOGGLED — via the TRAPDOOR verb ({@link BotSteering#setTrapdoorOpen},
 *       the kind-dispatch), never the door verb — and never mined, even though its plate genuinely obstructs
 *       ({@code movementBlockedAt} true);</li>
 *   <li>an EXTERNALLY re-flipped trapdoor is simply re-issued next tick (the anti-trick property — pure
 *       per-tick state re-validation, no timers);</li>
 *   <li>an already-open hatch in the corridor passes without mining: its thin panel still reads
 *       {@link BotSteering#solidAt}, but the geometry test ({@code movementBlockedAt}) clears it, so a
 *       {@code Need.AIR} on the cell neither swings nor holds — no per-block-type gate involved.</li>
 * </ul>
 *
 * The world mutation itself ({@code WorldEdits.setTrapdoorOpen}) and the follower drive are only verifiable
 * in-game — this pins the runner's dispatch/skip logic, the part that decides WHEN to toggle vs mine.
 */
class PhaseRunnerTrapdoorTest {

    private static long key(int x, int y, int z) { return BlockPos.asLong(x, y, z); }

    /** A {@link BotSteering} double: settable solid/blocked/open state, records both toggle verbs + mine. */
    private static final class FakeBot implements BotSteering {
        final Set<Long> solid = new HashSet<>();
        /** Cells whose live geometry genuinely obstructs the corridor — {@code movementBlockedAt}'s answer,
         *  kept SEPARATE from {@link #solid} so an open hatch can be solid-but-not-blocking (the thin-panel
         *  case the geometry test exists for). */
        final Set<Long> blocked = new HashSet<>();
        final Map<Long, Boolean> open = new HashMap<>();
        final List<long[]> setTrapdoorCalls = new ArrayList<>(); // {cell, open?1:0}
        final List<long[]> setDoorCalls = new ArrayList<>();     // dispatch guard: must stay empty here
        final List<Long> mineCalls = new ArrayList<>();

        @Override public boolean solidAt(int x, int y, int z) { return solid.contains(key(x, y, z)); }
        @Override public boolean airAt(int x, int y, int z) { return !solidAt(x, y, z); }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) {
            return blocked.contains(key(x, y, z));
        }
        @Override public void mine(int x, int y, int z) { mineCalls.add(key(x, y, z)); }
        @Override public void place(int x, int y, int z) { }
        @Override public boolean doorOpenAt(int x, int y, int z) {
            return open.getOrDefault(key(x, y, z), false); // the shared OPEN read (doors AND trapdoors)
        }
        @Override public void setTrapdoorOpen(int x, int y, int z, boolean o) {
            setTrapdoorCalls.add(new long[] { key(x, y, z), o ? 1 : 0 });
            open.put(key(x, y, z), o); // the world now reflects the toggle (panel stays solidAt either way)
        }
        @Override public void setDoorOpen(int x, int y, int z, boolean o) {
            setDoorCalls.add(new long[] { key(x, y, z), o ? 1 : 0 });
            open.put(key(x, y, z), o);
        }

        int setTrapdoorCount(int x, int y, int z, boolean o) {
            int n = 0;
            for (long[] c : setTrapdoorCalls) if (c[0] == key(x, y, z) && (c[1] == 1) == o) n++;
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

    private static final int TX = 6, TY = 1, TZ = 6; // the hatch cell

    @Test
    void closedTrapdoorIsToggledNotMined() {
        FakeBot bot = new FakeBot();
        // A CLOSED hatch: solid AND genuinely corridor-blocking (a closed plate across the crossing).
        bot.solid.add(key(TX, TY, TZ));
        bot.blocked.add(key(TX, TY, TZ));
        bot.open.put(key(TX, TY, TZ), false);

        MovePlan mp = new MovePlan();
        mp.requireTrapdoor(TX, TY, TZ, true); // the folded SET_OPEN, injected as a trapdoor req
        mp.phase("cross")
                .need(MovePlan.Need.AIR, TX, TY, TZ) // the hatch's body cell — must NOT be mined
                .drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        boolean done = runner.run(bot, new View());

        assertFalse(done, "the move holds while the hatch is being opened, not complete");
        assertEquals(MovePlan.Need.OPEN, runner.holdNeed(), "the runner holds on the OPEN req, not an AIR mine");
        assertEquals(1, bot.setTrapdoorCount(TX, TY, TZ, true), "the closed hatch is opened via the TRAPDOOR verb");
        assertTrue(bot.setDoorCalls.isEmpty(), "kind dispatch: a trapdoor req never drives the DOOR verb");
        assertFalse(bot.mined(TX, TY, TZ), "the hatch is TOGGLED, never mined, despite genuinely obstructing");
    }

    @Test
    void externallyReflippedTrapdoorIsReissuedNextTick() {
        FakeBot bot = new FakeBot();
        bot.solid.add(key(TX, TY, TZ));
        bot.open.put(key(TX, TY, TZ), true); // already at target — tick 1 must leave it alone

        MovePlan mp = new MovePlan();
        mp.requireTrapdoor(TX, TY, TZ, true);
        mp.phase("cross").drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());
        assertEquals(0, bot.setTrapdoorCalls.size(), "an already-correct hatch is not re-toggled (doorOpenAt gate)");

        // EXTERNAL flip mid-crossing (player/redstone trick): the live OPEN read now mismatches the req...
        bot.open.put(key(TX, TY, TZ), false);
        runner.run(bot, new View());
        // ...and the very next tick re-establishes it — pure state re-validation, no timers, no give-up.
        assertEquals(1, bot.setTrapdoorCount(TX, TY, TZ, true), "the externally-closed hatch is re-opened next tick");
        assertFalse(bot.mined(TX, TY, TZ), "re-establishing is a toggle, never a mine");
    }

    @Test
    void twoSameColumnSetReqsBothRevalidatedAndIssued() {
        // The §9-3b sandwich, executor side: TWO SET reqs on the SAME column ((TX,1),(TX,2) — the stacked
        // pair), both mismatched — the pre-pass iterates ALL reqs per tick, so BOTH verbs are issued the
        // same tick, and per-tick re-validation covers each cell independently (an external re-flip of ONE
        // is re-issued alone; the other, already at target, is left untouched).
        FakeBot bot = new FakeBot();
        bot.solid.add(key(TX, 1, TZ));
        bot.solid.add(key(TX, 2, TZ));
        bot.open.put(key(TX, 1, TZ), false);
        bot.open.put(key(TX, 2, TZ), false);

        MovePlan mp = new MovePlan();
        mp.requireTrapdoor(TX, 1, TZ, true);
        mp.requireTrapdoor(TX, 2, TZ, true);
        mp.phase("cross").drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());
        assertEquals(1, bot.setTrapdoorCount(TX, 1, TZ, true), "the feet-cell SET is issued");
        assertEquals(1, bot.setTrapdoorCount(TX, 2, TZ, true), "…and the head-cell SET, the SAME tick");
        assertEquals(MovePlan.Need.OPEN, runner.holdNeed(), "the runner holds on the OPEN reqs while establishing");

        // Both now read the target — the next tick issues nothing.
        runner.run(bot, new View());
        assertEquals(1, bot.setTrapdoorCount(TX, 1, TZ, true), "already-correct reqs are not re-toggled");
        assertEquals(1, bot.setTrapdoorCount(TX, 2, TZ, true), "…either of them");

        // External re-flip of ONE cell (the anti-trick property, per cell): only IT is re-issued.
        bot.open.put(key(TX, 2, TZ), false);
        runner.run(bot, new View());
        assertEquals(1, bot.setTrapdoorCount(TX, 1, TZ, true), "the untouched req stays untouched");
        assertEquals(2, bot.setTrapdoorCount(TX, 2, TZ, true), "the re-flipped req is re-issued next tick");
        assertTrue(bot.mineCalls.isEmpty(), "SET-governed cells are never mined");
    }

    @Test
    void openHatchCorridorCellPassesWithoutMining() {
        FakeBot bot = new FakeBot();
        // An OPEN hatch along the corridor: its thin wall panel still has collision (solidAt true) but does
        // not intrude into the movement corridor (movementBlockedAt false). No SET req governs it — it is
        // already in the state the plan searched. The geometry test alone must let it pass.
        bot.solid.add(key(TX, TY, TZ));
        bot.open.put(key(TX, TY, TZ), true);

        MovePlan mp = new MovePlan();
        mp.phase("walk")
                .need(MovePlan.Need.AIR, TX, TY, TZ) // declared blind by the movement; geometry clears it
                .drive((b, v) -> { });

        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        runner.run(bot, new View());

        assertFalse(bot.mined(TX, TY, TZ), "an open hatch's panel is walked past, never swung at");
        assertNull(runner.holdNeed(), "nothing to establish — the phase drives instead of holding");
        assertTrue(bot.setTrapdoorCalls.isEmpty() && bot.setDoorCalls.isEmpty(),
                "no toggle is issued for a cell no req governs");
    }
}
