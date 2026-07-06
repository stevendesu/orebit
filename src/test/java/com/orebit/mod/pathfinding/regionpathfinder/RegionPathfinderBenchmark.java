package com.orebit.mod.pathfinding.regionpathfinder;

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

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Drives {@link RegionPathfinder#plan}/{@link RegionPathfinder#planWithin} over the SYNTHETIC headless region
 * grids from {@link RegionScenarios} — no live {@code ServerLevel}, so the whole region-tier search runs as
 * pure CPU and can be measured/profiled headlessly. The region-tier analog of
 * {@link com.orebit.mod.worldmodel.pathing.PathfinderBenchmark} (which drives the block tier over a synthetic
 * {@code NavGridView}); this bench is the perf guard for PERF-DESIGN-region-dig-through.md (§7, §8 step 2) —
 * the pre-rework baseline captured here is what the entry→exit walk cost + dig-through connectivity change
 * (§3/§4) is measured against.
 *
 * <p>Scenarios (each a {@link RegionScenarios.Scenario}; see that class's Javadoc for the terrain shapes):
 * <ul>
 *   <li><b>OPEN_CAVERN</b> — a lateral crossing of five mostly-air floor regions (the §1b "walk edges cost ~1"
 *       shape). The cheap-lateral-flood control: the current cost model makes every crossing near-free.
 *   <li><b>SEALED_DIG</b> — the {@code /bot gather} repro (§1): a buried {@code SOLID} goal region directly
 *       below the start with a cheaper-looking air-shaft detour. Pre-fix the A* must take the winding down→over
 *       route (the §1a connectivity hole); this is the dig-heavy shape the rework targets.
 *   <li><b>MULTI_FRAGMENT</b> — one region flooded to three vertically-separated tunnels, sealed in rock; the
 *       route runs through intra-region mine edges (the multi-fragment-per-region expansion path).
 *   <li><b>LONG_CASCADE</b> — a 13-region corridor long enough that {@link RegionPathfinder#chooseCapSafeLevel}
 *       forces a coarse pyramid level, so the measured call is {@link RegionPathfinder#planWithin} at that
 *       level over rolled-up fragments (the cascade / coarse-level read path).
 *   <li><b>ZERO_CAP</b> — a no-break/no-place bot ({@code BotCaps.DEFAULT}) walled off its direct route,
 *       forcing an all-walkable L detour. The no-cap control: the §3 dig-through pass is {@code canBreak}-gated,
 *       so this scenario must see ZERO fan-out change from the rework.
 * </ul>
 *
 * <p>Allocation discipline (CLAUDE.md perf model): the fixture (grid + start/goal + caps) is built ONCE per
 * param in {@link #setup} (outside the timed method), exactly as {@code PathfinderBenchmark} prebuilds its
 * {@code NavGridView}; the measured op is just the {@link RegionScenarios.Fixture#plan()} call, so the numbers
 * reflect the search alone. The region A* inner loop is itself allocation-free (reuses the {@code wa/wb/wc}
 * scratch + ThreadLocal {@code Nodes}), and driving the same immutable fixture every invocation adds no
 * per-op heap.
 *
 * <p>Run: {@code ./gradlew "Set active project to 1.21.4"} then
 * {@code ./gradlew :1.21.4:jmh -Pbench=RegionPathfinderBenchmark} (JDK 21 on the active 1.21.x node; JMH runs
 * only on the mc-1.21 era). {@code -Pprof=gc,stack} attaches the allocation-rate + method-hot-spot profilers;
 * pin one scenario with {@code -Pscenario=SEALED_DIG}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class RegionPathfinderBenchmark {

    @Param({"OPEN_CAVERN", "SEALED_DIG", "MULTI_FRAGMENT", "LONG_CASCADE", "ZERO_CAP"})
    private String scenario;

    /** The synthetic region-tier fixture, built ONCE per param at trial setup (a smart object that runs its
     *  own plan through the right entry — direct level-0 {@code plan} or coarse {@code planWithin}). */
    private RegionScenarios.Fixture fixture;

    /**
     * {@code RegionScenarios} builds only pure-core structures (real {@code FragmentBuilder} flood over
     * hand-authored masks; no {@code NavSection}/registry read), so unlike the block bench it needs no MC
     * bootstrap. But this bench shares the bootstrapped-MC Knot JVM ({@code forks=0} via
     * {@code BenchmarkRunnerTest}), and the guard is idempotent + cheap — kept so the class is safe to run
     * standalone and stays symmetric with {@code PathfinderBenchmark}.
     */
    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        // Any per-search region-tier trace/console spew would dominate timing — silence it.
        RegionPathfinder.TRACE = false;
        Debug.ENABLED = false;

        fixture = RegionScenarios.build(RegionScenarios.Scenario.valueOf(scenario));
        sanityDryRun();
    }

    /**
     * Setup-time (NOT measured) shape check: the search must actually produce a plan that REACHES the goal
     * region — if a scenario silently stops reaching (a fixture regression, or a future cost change that makes
     * the goal unreachable), the timing measures a degenerate flood instead of the intended shape. Prints the
     * hop count + level so the run log records the scenario shape, mirroring the block bench's dry runs.
     */
    private void sanityDryRun() {
        RegionPathPlan plan = fixture.plan();
        boolean reached = plan != null && plan.reachedGoalRegion();
        System.out.println("[RegionPathfinderBenchmark] " + scenario + " sanity: hops="
                + (plan == null ? 0 : plan.size()) + " level=" + (plan == null ? -1 : plan.level())
                + " reachedGoalRegion=" + reached);
        if (!reached) {
            throw new IllegalStateException(scenario + " did not reach the goal region — fixture broken");
        }
    }

    @Benchmark
    public void plan(Blackhole bh) {
        // The whole op is the region-tier search over the prebuilt fixture; the fixture picks the right entry
        // (direct level-0 plan, or coarse planWithin for LONG_CASCADE) and uses the scenario's own caps.
        bh.consume(fixture.plan());
    }
}
