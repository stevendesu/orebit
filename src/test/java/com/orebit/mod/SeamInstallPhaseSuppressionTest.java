package com.orebit.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * The reached-scan suppression predicate ({@link BotNavigator#phaseOwnsCompletion}) across a window-swap
 * install (DESIGN-replan-handoff.md §5/R3; the 2026-08-19 run-5 forensic). A move that owns a phase plan
 * is the sole authority on its own completion (owner ruling 2026-08-03) — while its plan is active and
 * not done the waypoint cursor may not advance. Correct WITHIN one plan's life; across an install it was
 * the wedge's third fact: the run-5 FAST_FORWARD install never cleared the outgoing step's still-active
 * phase, so the STALE phase suppressed the NEW plan's reached-scan while the re-framed Pillar step 0
 * entombed the bot. The install seed therefore clears the runner ({@code InstallSeed.clearPhase()});
 * this pins the predicate's three states the seed's clear relies on: mid-phase suppression holds (B1),
 * {@link PhaseRunner#clear()} releases it (B2), and a completion the follower already observed
 * ({@code lastPhaseDone}) never suppresses (B3).
 *
 * <p>Fixture: a REAL {@link PhaseRunner} genuinely mid-phase — a begun {@link Pillar} plan (3 phases:
 * jump → place → land) with the bot airborne over the column and the footing not yet solid, so neither
 * the jump advance nor the terminal done can fire.
 */
class SeamInstallPhaseSuppressionTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /** Pillar frame: floor (10,64,10) → (10,65,10), feet 65 → 66. */
    private static final int FX = 10, FY = 64, FZ = 10;

    /** A stateful {@link BotSteering} double (the {@code PhaseRunnerGateTest} recording archetype,
     *  trimmed): airborne mid-jump over the pillar column, footing cell not solid. */
    private static final class FakeBot implements BotSteering {
        double y = 65.5;          // airborne inside the jump — above the start feet, below the fy+2 gate
        boolean grounded = false; // mid-jump: the pillar's place/land phases cannot complete

        @Override public double x() { return FX + 0.5; }
        @Override public double y() { return y; }
        @Override public double z() { return FZ + 0.5; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0.3; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return FX; }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return FZ; }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; } // footing NOT placed yet
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

    /** A begun-and-driven Pillar runner, genuinely mid-phase (jump 1/3, advance gate unmet). */
    private static PhaseRunner midPhase(FakeBot bot) {
        MovePlan plan = new Pillar().plan(FX, FY, FZ, FX, FY + 1, FZ, FY + 1, FY + 2);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan); // settle gate deliberately unarmed — the suppression predicate is under test
        runner.run(bot, new View());
        return runner;
    }

    @Test
    void aMidPhaseStepOwnsItsCompletion() {
        // B1: active plan, not done, completion not yet observed — the reached-scan must be suppressed.
        FakeBot bot = new FakeBot();
        PhaseRunner runner = midPhase(bot);
        assertTrue(BotNavigator.phaseOwnsCompletion(runner, false, bot),
                "a genuinely mid-phase step is the sole authority on its own completion");
    }

    @Test
    void clearingTheRunnerReleasesTheSuppression() {
        // B2: the install seed's clearPhase — once the outgoing plan is dropped, the stale phase must
        // not suppress the NEW plan's reached-scan (the run-5 wedge's third fact).
        FakeBot bot = new FakeBot();
        PhaseRunner runner = midPhase(bot);
        runner.clear();
        assertFalse(BotNavigator.phaseOwnsCompletion(runner, false, bot),
                "a cleared runner owns nothing — the reached-scan is free to advance");
    }

    @Test
    void anObservedCompletionNeverSuppresses() {
        // B3: lastPhaseDone true — the follower already saw this step complete; suppression must yield.
        FakeBot bot = new FakeBot();
        PhaseRunner runner = midPhase(bot);
        assertFalse(BotNavigator.phaseOwnsCompletion(runner, true, bot),
                "a completed step has no completion left to own");
    }

    /** A trivial non-degenerate segment over the pillar column (drives recenter during the fixture tick). */
    private static final class View implements SteerView {
        @Override public double sx() { return FX + 0.5; }
        @Override public double sy() { return FY + 1; }
        @Override public double sz() { return FZ + 0.5; }
        @Override public double tx() { return FX + 0.5; }
        @Override public double ty() { return FY + 2; }
        @Override public double tz() { return FZ + 0.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }
}
