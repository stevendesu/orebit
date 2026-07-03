package com.orebit.mod.worldmodel.pathing;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * E5 cold-start harness ({@code internal_docs/PERF-DESIGN-warmup-searches.md} §7): measures the JIT-COLD
 * first {@code findPath} wall-clock in a FRESH JVM — the one number the steady-state JMH suite cannot see
 * by construction (forks=0 reuses a warmed JVM; warm-up iterations exist precisely to exclude it).
 *
 * <p><b>Fresh-JVM discipline is the whole instrument</b>: run ONLY via the dedicated {@code coldstart}
 * Gradle task (one test-worker JVM per invocation, {@code forkEvery 1}), one measured run per Gradle
 * invocation, arms interleaved across invocations. A control-arm first search in the sub-millisecond
 * range means the JVM was NOT fresh — discard the run and diagnose.
 *
 * <p><b>Arms</b> (same build, toggled by {@code -Dorebit.bench.warmup}, passed as {@code -Pwarmarm=}):
 * control = no warm-up; treatment = {@link NavWarmup#run} first (synchronously, on this thread — the same
 * thread the timed searches then run on, mirroring the tick-thread discipline).
 *
 * <p><b>What is (deliberately) inside vs. outside the timed region.</b> Untimed setup mirrors what a real
 * server has ALREADY done before the first search: MC bootstrap, {@code MiningModel.buildTable} (forces
 * NavBlock static-init — the server-started hook does exactly this), the worldmodel classify pipeline
 * (fixture build = chunk loads), and {@code BlockPathfinder}'s own {@code <clinit>} (touched by the
 * {@code LOG_TIMING} write here; in-game the class initializes at the call site before S1's internal
 * {@code t0}). The timed region is the bare {@code findPath} call — everything the search itself pulls in
 * cold (the movement set, MovementContext/Relaxer, the cuboid subsystem, PathEdits/StepEdits, Nodes)
 * is the thing being measured.
 *
 * <p><b>Measured scenario = CLIFFS</b> (terraced Fall-heavy descent, walk-only damage-immune caps): a
 * mid-size realistic search whose terrain, movement mix, AND caps the warm-up never literally ran — so
 * any JIT profile-pollution cost of warming on synthetic fixtures shows up honestly in the treatment arm
 * instead of being flattered by re-running a warmed shape. Three consecutive searches (each over its own
 * fresh {@link NavGridView}, production replan shape) give the recovery curve.
 *
 * <p>Output (grep-able): {@code COLDSTART warm=<bool> ... first=<ns> second=<ns> third=<ns>}.
 */
@EnabledIfSystemProperty(named = "coldstart", matches = "true")
public class ColdStartHarnessTest {

    @Test
    void coldFirstSearch() {
        // JVM-freshness telemetry: worker uptime at test entry (a reused/warm JVM would also betray
        // itself with a sub-ms control first search — the real proof — but uptime is a cheap tripwire).
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        // ---- Untimed harness setup (what a live server pays before any search; identical in both arms) ----
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MiningModel.buildTable(true, 0); // server-started order: bake the table (forces NavBlock <clinit>)
        BlockPathfinder.LOG_TIMING = false; // also forces BlockPathfinder <clinit>, matching S1's t0 semantics
        Debug.ENABLED = false;

        // Three independent CLIFFS worlds so searches 2/3 pay a fresh per-search view + chunk cache like
        // production replans (sharing one view would hand them a warm chunk cache the replanner never has).
        NavGridView first = PathfinderBenchmark.buildCliffsWorld();
        NavGridView second = PathfinderBenchmark.buildCliffsWorld();
        NavGridView third = PathfinderBenchmark.buildCliffsWorld();

        // ---- Arm: optional warm-up (the treatment), on THIS thread ----
        boolean warm = Boolean.getBoolean("orebit.bench.warmup");
        long warmupMs = -1;
        NavWarmup.Result res = null;
        if (warm) {
            long w0 = System.nanoTime();
            res = NavWarmup.run(1500);
            warmupMs = (System.nanoTime() - w0) / 1_000_000;
            if (res == null) {
                throw new IllegalStateException("warm-up arm requested but NavWarmup refused to run");
            }
        }

        // ---- Timed region: plain nanoTime around the bare findPath calls (no JMH; the JVM is cold by design) ----
        long t1 = timeSearch(first);
        int firstExpansions = BlockPathfinder.lastExpansions();
        long t2 = timeSearch(second);
        long t3 = timeSearch(third);

        System.out.println("COLDSTART warm=" + warm
                + " uptimeMs=" + uptimeMs
                + " warmupMs=" + warmupMs
                + (res != null
                        ? " warmupRounds=" + res.rounds() + " warmupSearches=" + res.searches()
                                + " plateau=" + res.plateau()
                                + " warmupShortFirstUs=" + String.format("%.1f", res.firstShortMeanUs())
                                + " warmupShortLastUs=" + String.format("%.1f", res.lastShortMeanUs())
                        : "")
                + " first=" + t1 + " second=" + t2 + " third=" + t3
                + " firstExpansions=" + firstExpansions);
    }

    private static long timeSearch(NavGridView grid) {
        long t0 = System.nanoTime();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, PathfinderBenchmark.CLIFFS_START,
                PathfinderBenchmark.CLIFFS_GOAL, PathfinderBenchmark.CLIFFS_CAPS, null);
        long dt = System.nanoTime() - t0;
        if (plan == null) {
            throw new IllegalStateException("CLIFFS search found no path — fixture broken, run invalid");
        }
        return dt;
    }
}
