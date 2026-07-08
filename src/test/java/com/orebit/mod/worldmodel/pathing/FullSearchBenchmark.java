package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Drives the WHOLE live-gameplay path — cost-to-goal field (with the goal dig-flood), region skeleton (with the
 * start-fragment flood + virtual goal), and the region-informed window block A* — over the synthetic
 * {@link FullSearchScenarios} worlds, with no live {@code ServerLevel}. The driver-level analog of
 * {@link PathfinderBenchmark} (block tier only) and {@code RegionPathfinderBenchmark} (region tier only, over
 * record-only grids): those two headless benches early-out of the dig-flood / start-flood (they need loaded
 * {@code NavStore} sections keyed by a non-null level), so a regression in the live region-informed path is
 * invisible to them. This bench feeds ONE hand-authored {@code NavSection} map to both tiers (via the
 * {@link com.orebit.mod.worldmodel.hpa.RegionGrid#headless(int, java.util.concurrent.ConcurrentHashMap) headless
 * NavSection-backed region grid} + the block tier's synthetic {@link NavGridView}), so the real path engages.
 *
 * <p>Lives in {@code worldmodel.pathing} to reach {@link NavGridView}'s package-private synthetic constructor
 * (the same reason {@link PathfinderBenchmark} does); everything else it touches is public API.
 *
 * <p>Scenarios (each a {@link FullSearchScenarios.Scenario}; see that class for the terrain):
 * <ul>
 *   <li><b>GOAL_IN_WINDOW</b> — a buried goal ~2 regions off; the block target IS the goal. Exercises the goal
 *       dig-flood, the multi-source field seed, virtual-goal routing, and the region-informed dig-in block
 *       search — the "come into the cave pocket" path.</li>
 *   <li><b>GOAL_NOT_IN_WINDOW</b> — an open goal ~6 regions off; the block target is a skeleton waypoint portal.
 *       Exercises the long reverse-Dijkstra field + the window-target walk.</li>
 * </ul>
 *
 * <p>Allocation discipline (CLAUDE.md perf model): the fixture (shared section map + region grid + start/goal +
 * field bbox + models) is built ONCE per param in {@link #setup}, and — like the per-dimension
 * {@code RegionGrid.of(level)} across replans — its built leaves persist across ops, so the measured op is the
 * steady-state replan cost: a fresh cost field, a fresh skeleton, and a fresh {@link NavGridView} + block search
 * every invocation (the per-search construction production pays on every replan).
 *
 * <p>Run: {@code ./gradlew "Set active project to 1.21.4"} then
 * {@code ./gradlew :1.21.4:jmh -Pbench=FullSearchBenchmark} (JDK 21 on the active 1.21.x node; JMH runs only on
 * the mc-1.21 era). {@code -Pprof=gc,stack} attaches allocation-rate + method-hot-spot profilers; pin one
 * scenario with {@code -Pscenario=GOAL_IN_WINDOW}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class FullSearchBenchmark {

    @Param({"GOAL_IN_WINDOW", "GOAL_NOT_IN_WINDOW"})
    private String scenario;

    private FullSearchScenarios.Fixture fixture;

    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap(); // NavSection classify reads the block-state registry
            bootstrapped = true;
        }
        // Per-search INFO logging / region trace would dominate timing and flood the run — silence both.
        BlockPathfinder.LOG_TIMING = false;
        RegionPathfinder.TRACE = false;
        Debug.ENABLED = false;

        fixture = FullSearchScenarios.build(FullSearchScenarios.Scenario.valueOf(scenario));
        sanityDryRun();
    }

    /**
     * Setup-time (NOT measured) shape check: the whole pipeline must run and return a block plan, and the region
     * dig-flood must actually have engaged for the buried scenario (GOAL_IN_WINDOW's goal reads SOLID, so a
     * headless grid that failed to route sections to the dig-flood would silently fall back to nearest-centroid).
     * Prints the plan shape so the run log records what was measured — mirroring the other benches' dry runs.
     */
    private void sanityDryRun() {
        BlockPathPlan plan = fixture.search();
        int expansions = BlockPathfinder.lastExpansions();
        boolean partial = BlockPathfinder.lastWasPartial();
        System.out.println("[FullSearchBenchmark] " + scenario + " sanity: blockPlan="
                + (plan == null ? "null" : plan.size() + " steps") + " partial=" + partial
                + " expansions=" + expansions);

        // The buried scenario must have SEEN a dig-reachable pocket via the (now section-routed) dig-flood — the
        // whole reason this bench exists. Assert the enabler engaged, independent of the block plan's outcome.
        if (fixture.scenario == FullSearchScenarios.Scenario.GOAL_IN_WINDOW) {
            int[] pockets = { 0 };
            fixture.grid.goalDigSeeds(fixture.goalFloor.getX(), fixture.goalFloor.getY(),
                    fixture.goalFloor.getZ(), RegionGrid.MAX_GOAL_DIG_CELLS, (rx, ry, rz, frag, digCells) -> pockets[0]++);
            System.out.println("[FullSearchBenchmark] GOAL_IN_WINDOW dig-flood pockets=" + pockets[0]);
            if (pockets[0] == 0) {
                throw new IllegalStateException("GOAL_IN_WINDOW dig-flood found no pockets headless — the "
                        + "RegionGrid.headless(sections) resolver routing is broken (the enabler this bench needs)");
            }
        }
        if (plan == null) {
            throw new IllegalStateException(scenario + " produced no block plan — fixture broken");
        }
        // Both scenarios should cleanly FIND (not budget-exhaust): GOAL_IN_WINDOW digs straight to the buried
        // goal, GOAL_NOT_IN_WINDOW walks the confined tunnel to the waypoint. A partial here means a regression
        // turned the search into a flood — the timing would then measure a degenerate node-cap flood, not the
        // intended shape, so the guard is void (mirrors the block bench's per-scenario dry-run asserts).
        if (partial) {
            throw new IllegalStateException(scenario + " returned a PARTIAL (" + expansions
                    + " expansions) — flooding, no longer the intended full-search shape");
        }
    }

    @Benchmark
    public void search(Blackhole bh) {
        // The whole op is the three-stage live path (field + skeleton + region-informed window block A*) over the
        // prebuilt fixture; the fixture assembles the pieces exactly as PathPlan does internally.
        bh.consume(fixture.search());
    }
}
