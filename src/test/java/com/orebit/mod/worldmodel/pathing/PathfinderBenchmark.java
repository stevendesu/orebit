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

import com.orebit.mod.Debug;
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
 * <p>Scenarios:
 * <ul>
 *   <li><b>TOWER</b> — owner hovering 30 blocks straight up over flat ground. The pathological case: the
 *       search floods horizontally hunting a cheaper ramp that doesn't exist and burns the whole
 *       expansion budget (~10k nodes), every flood node trying edit-bearing Pillar/MineDown. This is the
 *       ~1150 ns/node search we want a method breakdown of.
 *   <li><b>OPEN</b> — a reachable cross-field walk on flat ground (no edits). The healthy contrast: the
 *       tie-break beelines and it returns a path in far fewer nodes. Shows the no-edit per-node cost.
 *   <li><b>UPOVER_OPEN</b> — an "up-and-over" goal offset BOTH horizontally and vertically over flat open
 *       stone (start {@code (8,0,8)} → goal {@code (23,30,23)}: dx=dz=15, dy=30, so the primary axis P
 *       stays Y). The path must micro-walk horizontally (the off-P Traverse legs) <i>while</i> macro-
 *       pillaring up (the on-P Pillar). Uniform air → the Y-cuboids are wide → a GOOD cuboid-cache hit
 *       rate: the control that isolates the "horizontal node explosion + edit spread" cost from any
 *       re-extraction effect.
 *   <li><b>UPOVER_WALL</b> — the same start/goal, but a tall thin stone wall (the plane {@code x=15},
 *       y 1..19, spanning the corridor in Z) sits between start and goal. The bot must climb OVER it, and
 *       the wall FRAGMENTS the air Y-columns either side of it (now bounded by the wall on X → narrow
 *       boxes), so the per-search region cache misses far more as the search weaves up and across. The
 *       realistic worst case.
 *   <li><b>SHORT</b> — the COLD-START guard. A trivially easy flat walk (start {@code (8,0,8)} → goal
 *       {@code (36,0,8)}, 28 blocks straight along X, ~30-60 expansions). Unlike every scenario above, the
 *       measured op constructs a FRESH {@link NavGridView} (over chunks prebuilt once at trial setup) and
 *       then searches — exactly the per-search setup production pays on every replan. Flooding searches
 *       amortize cold-start (ThreadLocal scratch touch, chunk-cache arrays, cuboid/goal-premium context
 *       inside {@code findPath}) down to ~400-700 ns/node; the real common-case ~30-node search pays
 *       thousands of ns/node because setup can't amortize. Any change that makes per-search
 *       construction/allocation heavier shows up HERE and nowhere else.
 *   <li><b>MULTI</b> — the PERSISTED-STRUCTURES guard. One op = FOUR consecutive searches alternating
 *       short, long, short, long (the SHORT walk and the UPOVER_OPEN up-and-over, both over the shared
 *       flat-world chunks), each search over its own fresh {@link NavGridView} — mirroring production,
 *       which replans every ~2s with a new view but keeps ThreadLocal scratch (Nodes rows / EditPool arena)
 *       and any FUTURE cross-search persistent structures warm. A future "persist base cuboids across
 *       searches" change must show its win here; a persistence bug (stale data leaking across searches)
 *       shows here as a wrong-cost/wrong-time anomaly relative to SHORT + UPOVER_OPEN measured alone.
 * </ul>
 *
 * <p>Run: {@code ./gradlew :<ver>:jmh -Pbench=PathfinderBenchmark -Pprof=gc,stack} (JDK 21 on the active
 * 1.21.x node). {@code -prof gc} reports allocation rate (does the deferred snapshot actually cut churn?);
 * {@code -prof stack} ranks methods by share of runtime (which ones eat the ns/node). Pin one scenario with
 * {@code -Pscenario=UPOVER_WALL}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class PathfinderBenchmark {

    @Param({"TOWER", "OPEN", "UPOVER_OPEN", "UPOVER_WALL", "SHORT", "MULTI"})
    private String scenario;

    private NavGridView grid;
    private BlockPos start;
    private BlockPos goal;
    private RegionBound corridor; // non-null for the macro scenarios → exercises the cuboid-collapse path

    /** Dispatch kind precomputed at setup so the measured op branches on an int, not a String switch. */
    private static final int KIND_PREBUILT = 0, KIND_SHORT = 1, KIND_MULTI = 2;
    private int kind = KIND_PREBUILT;

    /** SHORT/MULTI only: the chunk map prebuilt ONCE at trial setup; the measured op wraps it in a fresh
     *  {@link NavGridView} per search (the per-search construction cost production pays on every replan). */
    private ConcurrentHashMap<Long, NavSection[]> freshChunks;

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
        Debug.ENABLED = false;

        switch (scenario) {
            case "TOWER":
                grid = buildFlatWorld();
                start = new BlockPos(8, 0, 8);
                goal = new BlockPos(8, 30, 8);   // 30 straight up, open air (forced pillaring)
                corridor = new RegionBound(-1, 17, 0, 33, -1, 17);
                break;
            case "OPEN":
                grid = buildFlatWorld();
                start = new BlockPos(8, 0, 8);
                goal = new BlockPos(50, 0, 50);  // reachable flat walk, no edits — unbounded micro
                corridor = null;
                break;
            case "UPOVER_OPEN":
                grid = buildFlatWorld();
                start = UPOVER_START;
                goal = UPOVER_GOAL;
                corridor = UPOVER_CORRIDOR;
                break;
            case "UPOVER_WALL":
                grid = buildWalledWorld();
                start = UPOVER_START;
                goal = UPOVER_GOAL;
                corridor = UPOVER_CORRIDOR;
                break;
            case "SHORT":
                kind = KIND_SHORT;
                freshChunks = buildFlatChunks();
                sanityDryRun();
                break;
            case "MULTI":
                kind = KIND_MULTI;
                freshChunks = buildFlatChunks();
                sanityDryRun();
                break;
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    /**
     * Setup-time (NOT measured) shape check for the fresh-view scenarios: the SHORT leg must actually FIND
     * with a small expansion count (a beeline ~30-60 pops — if it floods, the scenario no longer measures
     * cold-start and the whole guard is void), and the MULTI long leg (UPOVER_OPEN geometry) must FIND too.
     * Prints {@link BlockPathfinder#LAST_EXPANSIONS} so the run log records the scenario shape.
     */
    private void sanityDryRun() {
        var shortPlan = BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                SHORT_START, SHORT_GOAL, BotCaps.BREAK_PLACE, null);
        int shortExpansions = BlockPathfinder.LAST_EXPANSIONS;
        System.out.println("[PathfinderBenchmark] " + scenario + " sanity: SHORT leg found="
                + (shortPlan != null) + " expansions=" + shortExpansions);
        if (shortPlan == null) {
            throw new IllegalStateException("SHORT leg did not find a path — fixture broken");
        }
        if (shortExpansions > 200) {
            throw new IllegalStateException("SHORT leg expanded " + shortExpansions
                    + " nodes (expected ~30-60) — no longer a cold-start guard");
        }
        if (kind == KIND_MULTI) {
            var longPlan = BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                    UPOVER_START, UPOVER_GOAL, BotCaps.BREAK_PLACE, UPOVER_CORRIDOR);
            System.out.println("[PathfinderBenchmark] MULTI sanity: LONG leg found="
                    + (longPlan != null) + " expansions=" + BlockPathfinder.LAST_EXPANSIONS);
            if (longPlan == null) {
                throw new IllegalStateException("MULTI long leg did not find a path — fixture broken");
            }
        }
    }

    // --- Up-and-over geometry, shared by UPOVER_OPEN and UPOVER_WALL and the DiagCuboidTest. The goal is
    //     offset 15 on X and Z and 30 on Y, so dy(30) > dx(15)=dz(15): the primary axis P is Y (Pillar/
    //     MineDown macro-collapse), and the two horizontal legs are off-P Traverse micro steps. The corridor
    //     hugs the L-path (the start and goal columns and the diagonal between) with a ~6-block horizontal
    //     margin and goes up to y=33 — not hugely wide (a too-wide corridor would mask the wall fragmentation).
    static final BlockPos UPOVER_START = new BlockPos(8, 0, 8);
    static final BlockPos UPOVER_GOAL = new BlockPos(23, 30, 23);
    static final RegionBound UPOVER_CORRIDOR = new RegionBound(2, 29, 0, 33, 2, 29);

    // --- SHORT geometry: a 28-block flat standable walk straight along X (dy=dz=0), well inside the
    //     flat world's built chunks (x 36 is chunk 2 of the -4..4 span). The tie-break beelines, so the
    //     search pops ~1 node per block plus takeoff probes — the trivial common-case search whose cost
    //     is DOMINATED by per-search setup, which is exactly what the scenario is for.
    static final BlockPos SHORT_START = new BlockPos(8, 0, 8);
    static final BlockPos SHORT_GOAL = new BlockPos(36, 0, 8);

    /** The X-plane the UPOVER_WALL barrier sits on (between start x=8 and goal x=23, inside the corridor). */
    static final int WALL_X = 15;
    /** The wall's top Y (inclusive): tall enough to force a real climb-over, low enough that the bounded
     *  up-and-over search still REACHES the floating goal within budget (a taller wall + floating goal makes
     *  the doubly-hard search flood past the 10k cap and FAIL — which distorts the numbers). */
    static final int WALL_TOP_Y = 7;

    /**
     * Stone floor at world y=0, air above, spanning chunks (-4..4) so the horizontal flood (~radius 32)
     * and the vertical pillaring both stay inside built terrain — the {@code level==null} synthetic view's
     * live-block fallback then never fires. The grid is read-only during search, so all 81 chunks SHARE one
     * ground + one air {@link NavSection}; the fixture is two classified sections, not 324.
     */
    static NavGridView buildFlatWorld() {
        return new NavGridView(0, buildFlatChunks()); // minY=0, synthetic (no live level)
    }

    /**
     * The flat world's raw chunk map (see {@link #buildFlatWorld}) — split out so the fresh-view scenarios
     * (SHORT/MULTI) can prebuild the chunks ONCE at trial setup and pay only the {@link NavGridView}
     * wrapper (the true per-search cost) inside the measured op.
     */
    static ConcurrentHashMap<Long, NavSection[]> buildFlatChunks() {
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
        return chunks;
    }

    /**
     * The UPOVER_WALL fixture: flat stone ground at y=0, plus a one-block-thick stone WALL on the world plane
     * {@code x=WALL_X} rising from y=1 to {@code y=WALL_TOP_Y}, spanning the corridor in Z. The bot must
     * pillar OVER the wall (it cannot pass through it horizontally at low altitude), and — the point of the
     * scenario — the wall bounds the uniform air Y-columns on either side of it on X, so the cuboid extractor
     * yields NARROW (fragmented) boxes near the wall and the per-search region cache misses much more.
     *
     * <p>The wall lives only in the two chunks the {@code x=15} plane crosses (chunk cx=0, blocks x 0..15),
     * so those chunks get their OWN classified sections; every other chunk still shares the cheap flat ground
     * + air sections. {@code WALL_X=15} is the last block of chunk 0 — the wall's section is distinct from the
     * neighbouring chunk-1 air, which is itself part of why the boxes fragment at the chunk seam.
     */
    static NavGridView buildWalledWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Shared flat-ground column (ground at local y=0, air above) for every chunk the wall doesn't touch.
        PalettedContainer<BlockState> groundStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                groundStates.set(x, 0, z, stone);
            }
        }
        NavSection flatGround = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(groundStates, false, flatGround.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] flatColumn = { flatGround, airSection, airSection, airSection }; // y 0..63

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), flatColumn);
            }
        }

        // The wall plane x=WALL_X lives in chunk cx = WALL_X >> 4. Build that chunk's own column with the wall
        // stone added: section 0 (y 0..15) carries ground + the wall's lower half; section 1 (y 16..31)
        // carries the wall's upper half. Only the chunk-rows the corridor spans in Z need it, but classifying
        // a single distinct chunk per cz that the wall crosses is enough and keeps the fixture small — the
        // wall spans the whole corridor Z (2..29), i.e. chunk-rows cz=0 and cz=1.
        int wallChunkX = WALL_X >> 4;          // chunk 0
        int wallXLocal = WALL_X & 15;          // local x 15
        for (int cz = 0; cz <= 1; cz++) {       // chunk-rows the corridor's Z span (2..29) crosses
            // Lower wall section (world y 0..15): ground plane + wall stone at local x=15, y 1..15.
            PalettedContainer<BlockState> lowStates = new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    lowStates.set(x, 0, z, stone);            // ground
                }
            }
            for (int z = 0; z < 16; z++) {
                for (int y = 1; y <= 15; y++) {              // wall lower half (world y 1..15)
                    lowStates.set(wallXLocal, y, z, stone);
                }
            }
            NavSection lowWall = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(lowStates, false, lowWall.getTraversalGrid());

            // Upper wall section (world y 16..31): wall stone at local x=15, local y 0..(WALL_TOP_Y-16).
            PalettedContainer<BlockState> highStates = new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
            for (int z = 0; z < 16; z++) {
                for (int yl = 0; yl <= (WALL_TOP_Y - 16); yl++) {   // world y 16..WALL_TOP_Y
                    highStates.set(wallXLocal, yl, z, stone);
                }
            }
            NavSection highWall = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(highStates, false, highWall.getTraversalGrid());

            NavSection[] wallColumn = { lowWall, highWall, airSection, airSection }; // y 0..63
            chunks.put(NavStore.key(wallChunkX, cz), wallColumn);
        }

        return new NavGridView(0, chunks); // minY=0, synthetic (no live level)
    }

    @Benchmark
    public void findPath(Blackhole bh) {
        // BREAK_PLACE: the up-and-over / tower goals are only solvable by placing (pillaring) — DEFAULT can't
        // and would trivially fail. Matches the in-game test bot's capability.
        switch (kind) {
            case KIND_SHORT:
                // Fresh NavGridView INSIDE the op: the per-search construction (chunk-cache arrays + the
                // cuboid/goal-premium context findPath builds internally) is the point of the measurement.
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        SHORT_START, SHORT_GOAL, BotCaps.BREAK_PLACE, null));
                break;
            case KIND_MULTI:
                // Four consecutive searches — short, long, short, long — each over its OWN fresh view
                // (production replans ~every 2s with a new view), all in one invocation so ThreadLocal
                // scratch and any future cross-search persistent structures run warm across searches.
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        SHORT_START, SHORT_GOAL, BotCaps.BREAK_PLACE, null));
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        UPOVER_START, UPOVER_GOAL, BotCaps.BREAK_PLACE, UPOVER_CORRIDOR));
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        SHORT_START, SHORT_GOAL, BotCaps.BREAK_PLACE, null));
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        UPOVER_START, UPOVER_GOAL, BotCaps.BREAK_PLACE, UPOVER_CORRIDOR));
                break;
            default:
                bh.consume(BlockPathfinder.findPath(grid, start, goal, BotCaps.BREAK_PLACE, corridor));
        }
    }
}
