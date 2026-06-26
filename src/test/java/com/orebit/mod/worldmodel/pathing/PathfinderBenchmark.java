package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;
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

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Drives {@link BlockPathfinder#findPath} over a SYNTHETIC in-memory nav grid — no live {@code ServerLevel},
 * so the whole search runs as pure CPU and can be measured/profiled headlessly. Lives in the
 * {@code worldmodel.pathing} package to reach {@link NavGridView}'s package-private synthetic constructor.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li><b>TOWER</b> — owner hovering 30 blocks straight up over flat ground. The pathological case: the
 *       search floods horizontally hunting a cheaper ramp that doesn't exist and burns the whole
 *       expansion budget (~10k nodes), every flood node trying edit-bearing Pillar/MineDown. This is the
 *       ~1150 ns/node search we want a method breakdown of.
 *   <li><b>OPEN</b> — a reachable cross-field walk on flat ground (no edits). The healthy contrast: the
 *       tie-break beelines and it returns a path in far fewer nodes. Shows the no-edit per-node cost.
 * </ul>
 *
 * <p>Run: {@code ./gradlew :<ver>:jmh -Pbench=PathfinderBenchmark -Pprof=gc,stack} (JDK 21 on the active
 * 1.21.x node). {@code -prof gc} reports allocation rate (does the deferred snapshot actually cut churn?);
 * {@code -prof stack} ranks methods by share of runtime (which ones eat the ns/node).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class PathfinderBenchmark {

    @Param({"TOWER", "OPEN"})
    private String scenario;

    private NavGridView grid;
    private BlockPos start;
    private BlockPos goal;
    private RegionBound corridor; // non-null for TOWER → exercises the macro path (cuboid collapse)

    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        // The per-search INFO logging would dominate timing and flood the run — silence it.
        BlockPathfinder.LOG_TIMING = false;
        BlockPathfinder.DEBUG = false;

        grid = buildFlatWorld();
        start = new BlockPos(8, 0, 8);
        goal = scenario.equals("TOWER")
                ? new BlockPos(8, 30, 8)    // 30 straight up, open air (forced pillaring)
                : new BlockPos(50, 0, 50);  // reachable flat walk, no edits
        // TOWER runs the corridor-bounded macro path (the in-game pillar config); OPEN stays unbounded micro.
        corridor = scenario.equals("TOWER") ? new RegionBound(-1, 17, 0, 33, -1, 17) : null;
    }

    /**
     * Stone floor at world y=0, air above, spanning chunks (-4..4) so the horizontal flood (~radius 32)
     * and the vertical pillaring both stay inside built terrain — the {@code level==null} synthetic view's
     * live-block fallback then never fires. The grid is read-only during search, so all 81 chunks SHARE one
     * ground + one air {@link NavSection}; the fixture is two classified sections, not 324.
     */
    private static NavGridView buildFlatWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Ground section: stone plane at local y=0, air above.
        PalettedContainer<BlockState> groundStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                groundStates.set(x, 0, z, stone);
            }
        }
        NavSection ground = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(groundStates, false, ground.getTraversalGrid());

        // All-air section (the onlyAir shortcut path).
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        // One shared 4-section column (y 0..63): ground, then three air sections — headroom for a 30-up
        // goal plus the pillar takeoff probes above it.
        NavSection[] column = { ground, airSection, airSection, airSection };

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), column);
            }
        }
        return new NavGridView(0, chunks); // minY=0, synthetic (no live level)
    }

    @Benchmark
    public void findPath(Blackhole bh) {
        // BREAK_PLACE: the tower is only solvable by placing (pillaring) — DEFAULT can't and would trivially
        // fail. Matches the in-game test bot's capability.
        bh.consume(BlockPathfinder.findPath(grid, start, goal, BotCaps.BREAK_PLACE, corridor));
    }
}
