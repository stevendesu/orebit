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
import com.orebit.mod.pathfinding.blockpathfinder.movements.Fall;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

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
 *   <li><b>SETUP</b> — the DIRECT per-search-setup probe: a fresh
 *       {@link NavGridView} per op searching to a goal already inside {@code isGoal}'s arrival tolerance
 *       (start {@code (8,0,8)} → goal {@code (9,0,9)}: within 1 horizontally), so the FIRST pop terminates
 *       the search — {@code lastExpansions() == 0}, zero relaxations, empty plan. What remains inside the
 *       op is EXACTLY the per-search fixed cost SHORT can only bundle with its ~30-60-pop walk: view
 *       construction (the two 512-slot chunk-cache arrays), Nodes/EditPool reset, MovementContext +
 *       Relaxer construction, start intern/push/pop, reconstruct. No corridor — like SHORT, this arm
 *       deliberately EXCLUDES the macro context (see SETUP_MACRO). Derivations: SHORT − SETUP ≈ the pure
 *       micro-walk cost of a trivial search; a per-search-setup regression moves SETUP directly instead of
 *       being inferred from FLOOD-minus-SHORT arithmetic.
 *   <li><b>SETUP_MACRO</b> — the PRODUCTION-SHAPED setup probe: identical to SETUP but passing a cuboid
 *       growth cap ({@code confineBound == null}, {@code cuboidBound != null} — the live window replan's
 *       exact parameter shape), so the op ALSO pays {@code NavGridCuboidsView} construction and the
 *       one-shot {@code GoalForcedCost.probe} (goal-face cuboid extractions — the 38-45%-of-small-searches
 *       item in the perf profile). SHORT passes {@code bound == null}, so the probe early-outs and
 *       NO existing scenario measured this. SETUP_MACRO − SETUP ≈ the macro-context + goal-probe bill.
 *   <li><b>FLOOD</b> — the WARM EDIT-HEAVY guard (the perf profile's S3 shape, which no other scenario
 *       reproduces since macro collapse shrank TOWER to ~28 pops): an unreachable goal above the built
 *       ceiling with NO corridor, so no cuboids/premium exist and the search runs the classic pillar-cone
 *       flood to budget exhaustion (10001 pops), nearly every pop's chain carrying Pillar edits.
 *       Sanity-checked at setup to still be budget-exhausted.
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

    @Param({"TOWER", "OPEN", "UPOVER_OPEN", "UPOVER_WALL", "SHORT", "MULTI", "FLOOD", "CLIFFS",
            "BRIDGE", "SPIRAL", "SETUP", "SETUP_MACRO"})
    private String scenario;

    private NavGridView grid;
    private BlockPos start;
    private BlockPos goal;
    private RegionBound corridor; // non-null for the macro scenarios → exercises the cuboid-collapse path
    /** Per-scenario caps: BREAK_PLACE everywhere except CLIFFS, which is walk-only (see its setup note). */
    private BotCaps caps = BotCaps.BREAK_PLACE;

    /** Dispatch kind precomputed at setup so the measured op branches on an int, not a String switch. */
    private static final int KIND_PREBUILT = 0, KIND_SHORT = 1, KIND_MULTI = 2, KIND_SETUP = 3,
            KIND_SETUP_MACRO = 4;
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
            case "SETUP":
                kind = KIND_SETUP;
                freshChunks = buildFlatChunks();
                setupSanityDryRun(null);
                break;
            case "SETUP_MACRO":
                kind = KIND_SETUP_MACRO;
                freshChunks = buildFlatChunks();
                setupSanityDryRun(SETUP_CORRIDOR);
                break;
            case "FLOOD":
                // The WARM EDIT-HEAVY guard (the perf profile's "WARM FLOOD" probe, promoted): an
                // UNREACHABLE goal far above the fixture's built ceiling (the shared column ends at y=63;
                // cells above are unbuilt, so no candidate is ever relaxed there), with NO corridor — no
                // cuboid view ⇒ no macro collapse, no goal-forced-cost premium. Every column's pillar climb
                // stalls at the ceiling and the search floods a cone of partial pillars to budget exhaustion
                // (10001 pops), nearly every pop's chain carrying Pillar place edits — the S3 shape (in-game
                // warm pillaring) whose per-node cost is dominated by the per-read PathEdits.kindAt tax.
                // The scenario the per-pop edit gate targets; without it the suite can't see that shape.
                // (The plain 30-up no-corridor tower is NOT a flood anymore — the current cost model finds
                // it in ~700 pops — hence the ceiling-unreachable goal + the budget-exhaustion sanity check.)
                grid = buildFlatWorld();
                start = new BlockPos(8, 0, 8);
                goal = new BlockPos(8, 100, 8);
                corridor = null;
                floodSanityDryRun();
                break;
            case "CLIFFS":
                // The Fall-heavy guard (docs/Optimizations/09_depth_nibbles.md): descending terraced open
                // terrain — chunk-aligned 12-block cliffs stepping down toward the goal — so standing pops
                // ride terrace edges whose cardinals are deep open columns and Fall's per-cardinal down-scan
                // is the dominant read volume (TOWER macro-collapses to ~28 pops and OPEN is flat, so no
                // other scenario exercises it). No corridor: pure micro search, no cuboids/premium.
                // Walk-only + damage-immune caps — see CLIFFS_CAPS for why both matter here.
                grid = buildCliffsWorld();
                start = CLIFFS_START;
                goal = CLIFFS_GOAL;
                corridor = null;
                caps = CLIFFS_CAPS;
                cliffsSanityDryRun();
                break;
            case "BRIDGE":
                // The EDITS-THEN-WALK-AWAY guard (the realistic mixed shape the pure pillar/flood
                // scenarios never produce): a bottomless 5-wide ravine crosses the whole world a few
                // blocks past the start, so the search must fold a handful of PLACE edits (bridge it)
                // and then walk a LONG edit-free stretch to the far goal. Every pop past the bridge
                // carries the bridge edits in its chain but reads nowhere near them — the envelope-
                // DISJOINT population the E1 per-pop gate needs (and which the p=0.000 finding said no
                // existing scenario has). No corridor: pure micro, no macro ray. Caps: place-capable +
                // walk, break OFF (a break-capable headless bot prices breaks ~0 and would restructure
                // the shape — the CLIFFS gotcha); the ravine is bottomless (no ground -> nothing below
                // minY) so falling in is impossible and the 5-gap exceeds flat parkour reach (3) — the
                // optimal crossing observed is 2 places + a parkour hop over the remaining 3-gap, which
                // is the mixed shape wanted (probe: 58 pops, 52 edit-bearing, 47 envelope-disjoint).
                grid = buildBridgeWorld();
                start = new BlockPos(8, 0, 8);
                goal = BRIDGE_GOAL;
                corridor = null;
                caps = PLACE_ONLY_CAPS;
                bridgeSanityDryRun();
                break;
            case "SPIRAL":
                // The CONSTRAINED-CUBOIDS guard (the runUp chain's worst case): an enclosed solid tower with a
                // helical stair channel carved inside it — the walkway turns every few cells and each
                // step has exactly 3 air above it, so the passable space is a chain of tiny pockets and
                // no large uniform air cuboid ever exists; the SOLID fill alternates 4 distinct-navtype
                // materials per Y level, so vertical same-navtype runs in the structure are length 1 and
                // the runUp nibble can never skip. Goal at the top -> primary axis Y -> the Y-macro
                // movements probe cuboid extraction at every pop, and every probe degenerates (tiny box,
                // cache miss). Place-capable (Pillar must be live for Y-cuboid extraction; pillaring is
                // useless inside the 3-high pockets), break OFF (an insta-miner would tunnel the core).
                grid = buildSpiralWorld();
                start = SPIRAL_START;
                goal = SPIRAL_GOAL;
                corridor = SPIRAL_CORRIDOR;
                caps = PLACE_ONLY_CAPS;
                spiralSanityDryRun();
                break;
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    /**
     * Setup-time (NOT measured) shape check for the fresh-view scenarios: the SHORT leg must actually FIND
     * with a small expansion count (a beeline ~30-60 pops — if it floods, the scenario no longer measures
     * cold-start and the whole guard is void), and the MULTI long leg (UPOVER_OPEN geometry) must FIND too.
     * Prints {@link BlockPathfinder#lastExpansions()} so the run log records the scenario shape.
     */
    private void sanityDryRun() {
        var shortPlan = BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                SHORT_START, SHORT_GOAL, BotCaps.BREAK_PLACE, null);
        int shortExpansions = BlockPathfinder.lastExpansions();
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
                    + (longPlan != null) + " expansions=" + BlockPathfinder.lastExpansions());
            if (longPlan == null) {
                throw new IllegalStateException("MULTI long leg did not find a path — fixture broken");
            }
        }
    }

    /**
     * Setup-time (NOT measured) shape check for SETUP/SETUP_MACRO: the search must FIND immediately
     * (start already inside the arrival tolerance) with ZERO expansions and an EMPTY plan — if any
     * expansion happens, the scenario is no longer a pure per-search-setup probe (an isGoal-tolerance or
     * geometry change would surface here, not as a silent drift in what the number means). {@code bound}
     * is the cuboid growth cap of the arm under test (null = SETUP, the corridor = SETUP_MACRO).
     */
    private void setupSanityDryRun(RegionBound bound) {
        var plan = BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                SETUP_START, SETUP_GOAL, BotCaps.BREAK_PLACE, null, bound, null);
        int expansions = BlockPathfinder.lastExpansions();
        System.out.println("[PathfinderBenchmark] " + scenario + " sanity: found=" + (plan != null)
                + " expansions=" + expansions + " planSize=" + (plan == null ? -1 : plan.size()));
        if (plan == null) {
            throw new IllegalStateException(scenario + " did not find — fixture broken");
        }
        if (expansions != 0) {
            throw new IllegalStateException(scenario + " expanded " + expansions
                    + " nodes (expected 0) — no longer a pure per-search-setup probe");
        }
        if (plan.size() != 0) {
            throw new IllegalStateException(scenario + " returned " + plan.size()
                    + " waypoints (expected an empty plan) — the start is no longer inside the goal tolerance");
        }
    }

    /**
     * Setup-time (NOT measured) shape check for CLIFFS: the search must FIND a route and that route must
     * actually descend the terraces by FALLING (several Fall steps) — if a movement/cost change reroutes it
     * (dig-down staircases, say), the scenario no longer measures Fall's down-scan volume and the guard is
     * void. Prints expansions + the Fall-step count so the run log records the shape.
     */
    private void cliffsSanityDryRun() {
        var plan = BlockPathfinder.findPath(grid, start, goal, caps, corridor);
        int expansions = BlockPathfinder.lastExpansions();
        int falls = 0;
        if (plan != null) {
            for (int i = 0; i < plan.size(); i++) {
                if (plan.movement(i) instanceof Fall) falls++;
            }
        }
        System.out.println("[PathfinderBenchmark] CLIFFS sanity: found=" + (plan != null)
                + " partial=" + BlockPathfinder.lastWasPartial()
                + " expansions=" + expansions + " fallSteps=" + falls);
        if (plan != null) {
            StringBuilder sb = new StringBuilder("[PathfinderBenchmark] CLIFFS plan:");
            for (int i = 0; i < plan.size(); i++) {
                BlockPos wp = plan.waypoint(i);
                sb.append(' ').append(plan.movement(i).getClass().getSimpleName())
                        .append('(').append(wp.getX()).append(',').append(wp.getY()).append(',')
                        .append(wp.getZ()).append(')');
            }
            System.out.println(sb);
        }
        if (plan == null) {
            throw new IllegalStateException("CLIFFS did not find a path — fixture broken");
        }
        if (falls < 3) {
            throw new IllegalStateException("CLIFFS path used only " + falls
                    + " Fall steps (expected the 4 terrace drops) — no longer the Fall-heavy guard");
        }
    }

    /**
     * Setup-time (NOT measured) shape check for FLOOD: the search must actually EXHAUST the expansion
     * budget (a budget-hit ~10001-pop flood) and run edit-bearing — if a future heuristic/movement change
     * lets it FIND (or fail early), the scenario no longer measures the warm edit-heavy shape and the
     * guard is void. Prints the expansion count so the run log records the shape.
     */
    private void floodSanityDryRun() {
        var plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.BREAK_PLACE, corridor);
        int expansions = BlockPathfinder.lastExpansions();
        System.out.println("[PathfinderBenchmark] FLOOD sanity: expansions=" + expansions
                + " partial=" + BlockPathfinder.lastWasPartial() + " found=" + (plan != null));
        if (expansions < 9500) {
            throw new IllegalStateException("FLOOD expanded only " + expansions
                    + " nodes (expected a ~10001-pop budget-exhausted flood) — no longer the warm edit-heavy guard");
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

    // --- SETUP geometry: the goal sits INSIDE isGoal's arrival tolerance of the start (within 1
    //     horizontally on both axes, 0 vertically), so the very first pop — the start node — terminates
    //     the search before a single expansion is counted or a single candidate is relaxed. The goal is
    //     deliberately OFFSET (9,0,9), not equal to the start: start==goal degenerates primaryAxis and the
    //     goal-probe's far-face exclusion (domDelta == 0 → exclude nothing), whereas the offset keeps both
    //     on their production code path (dominant axis X via the argmax X>Z>Y tie-break). The corridor
    //     (SETUP_MACRO only) mirrors a live sliding-window cuboid cap: 32x32 horizontally around the
    //     start, y 0..33 — comfortably inside the flat world's built chunks (-4..4 = blocks -64..79).
    static final BlockPos SETUP_START = new BlockPos(8, 0, 8);
    static final BlockPos SETUP_GOAL = new BlockPos(9, 0, 9);
    static final RegionBound SETUP_CORRIDOR = new RegionBound(-8, 24, 0, 33, -8, 24);

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
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                groundStates.set(x, 0, z, stone);
            }
        }
        NavSection ground = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(groundStates, false, ground.getTraversalGrid());

        // All-air sections (the onlyAir shortcut path). The middle instance is shared by column slots 1+2
        // (its depth nibbles are uniform there: floorGap saturates ≥15 above the ground and runUp saturates
        // ≥14 below the built-column top); the TOP slot needs its OWN instance because its runUp grades
        // 0..14 down from the unbuilt boundary — instance-shared cells must hold identical depth values.
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airMid = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airMid.getTraversalGrid());
        NavSection airTop = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airTop.getTraversalGrid());

        // One shared 4-section column (y 0..63): ground, then three air sections — headroom for a 30-up
        // goal plus the pillar takeoff probes above it. Depth nibbles (floorGap/runUp) swept column-form,
        // exactly like the live ChunkNavBuilder pass 3.
        NavSection[] column = { ground, airMid, airMid, airTop };
        NavSectionBuilder.computeDepth(column);

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
        return new NavGridView(0, buildWalledChunks()); // minY=0, synthetic (no live level)
    }

    /** The walled world's raw chunk map (see {@link #buildWalledWorld}) — the seam DepthIdentityTest uses
     *  to erase the depth nibbles of a built fixture. */
    static ConcurrentHashMap<Long, NavSection[]> buildWalledChunks() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Shared flat-ground column (ground at local y=0, air above) for every chunk the wall doesn't touch.
        PalettedContainer<BlockState> groundStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                groundStates.set(x, 0, z, stone);
            }
        }
        NavSection flatGround = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(groundStates, false, flatGround.getTraversalGrid());

        // Shared air sections — middle slots share one instance, the TOP slot gets its own (its runUp
        // nibble grades down from the unbuilt boundary; see buildFlatChunks).
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airMid = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airMid.getTraversalGrid());
        NavSection airTop = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airTop.getTraversalGrid());

        NavSection[] flatColumn = { flatGround, airMid, airMid, airTop }; // y 0..63
        NavSectionBuilder.computeDepth(flatColumn);

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
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
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
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            for (int z = 0; z < 16; z++) {
                for (int yl = 0; yl <= (WALL_TOP_Y - 16); yl++) {   // world y 16..WALL_TOP_Y
                    highStates.set(wallXLocal, yl, z, stone);
                }
            }
            NavSection highWall = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(highStates, false, highWall.getTraversalGrid());

            NavSection[] wallColumn = { lowWall, highWall, airMid, airTop }; // y 0..63
            NavSectionBuilder.computeDepth(wallColumn); // shared air slots re-derive identical values
            chunks.put(NavStore.key(wallChunkX, cz), wallColumn);
        }

        return chunks;
    }

    // --- CLIFFS geometry: chunk-aligned terraces stepping DOWN 12 blocks per chunk toward +X (terrace
    //     tops at y 48/36/24/12/0), flat in Z. The start stands on the highest plateau, the goal on the
    //     y=0 plain, so the route must take four 12-block Fall drops (deeper than Descend, inside the
    //     16-block maxFall window) while every terrace-edge pop's open cardinals cost Fall its full
    //     down-scan. Spans chunks -4..4 like the flat world so the search never leaves built terrain.
    static final BlockPos CLIFFS_START = new BlockPos(-24, 48, 8);
    static final BlockPos CLIFFS_GOAL = new BlockPos(40, 0, 8);

    /**
     * CLIFFS caps: walk-only (no break/place — the headless MiningModel prices breaks at ~0, so a
     * break-capable bot digs a free staircase and never Falls) and damage-IMMUNE (safe == max == the
     * unlimited fall window): a MORTAL bot prices each 12-drop at ~900 ticks of damage the octile can't
     * see, so the search floods the whole built plateau hunting a gentler way down and dies on the node
     * budget. Immune, it beelines down the terraces — every edge pop still pays Fall's full down-scan,
     * which is the read volume this scenario exists to measure.
     */
    static final BotCaps CLIFFS_CAPS = new BotCaps(1, BotCaps.IMMUNE_FALL, BotCaps.IMMUNE_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false, BotCaps.UNBREAKABLE, false,
            BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    /** Terrace top Y for chunk {@code cx}: 48 west of cx=-1, stepping 12 down per chunk to 0 at cx>=2. */
    private static int cliffTop(int cx) {
        int t = 2 - cx;              // cx=-1 -> 3, cx=0 -> 2, cx=1 -> 1, cx>=2 -> <=0
        if (t < 0) t = 0;
        if (t > 4) t = 4;            // cx<=-2 -> 4 (the 48-high plateau)
        return 12 * t;
    }

    /**
     * The CLIFFS fixture: solid stone from y=0 up to {@code cliffTop(cx)} in every column of the chunk,
     * air above, per-terrace sections shared across the chunks of that terrace. Column-form depth sweep
     * per distinct terrace column.
     */
    static NavGridView buildCliffsWorld() {
        return new NavGridView(0, buildCliffsChunks()); // minY=0, synthetic (no live level)
    }

    /** The cliffs world's raw chunk map (see {@link #buildCliffsWorld}) — the DepthIdentityTest seam. */
    static ConcurrentHashMap<Long, NavSection[]> buildCliffsChunks() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        java.util.HashMap<Integer, NavSection[]> byTop = new java.util.HashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            int top = cliffTop(cx);
            NavSection[] column = byTop.computeIfAbsent(top, t -> {
                NavSection[] col = new NavSection[4]; // y 0..63
                for (int i = 0; i < 4; i++) {
                    int baseY = i * 16;
                    boolean onlyAir = baseY > t;
                    PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
                    if (!onlyAir) {
                        for (int y = 0; y < 16 && baseY + y <= t; y++) {
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    states.set(x, y, z, stone);
                                }
                            }
                        }
                    }
                    col[i] = NavSection.create(BlockPos.ZERO);
                    NavSectionBuilder.classifyInto(states, onlyAir, col[i].getTraversalGrid());
                }
                NavSectionBuilder.computeDepth(col);
                return col;
            });
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), column);
            }
        }
        return chunks;
    }

    // --- BRIDGE geometry: flat stone world (chunks -4..4, ground y=0) with the ground REMOVED for
    //     world x 14..18 across EVERY z — a bottomless 5-wide ravine (minY=0, so the gap columns have
    //     nothing below: no landing, no way around). Start (8,0,8) is 6 blocks west of the rim; the
    //     goal is ~52 blocks past the far rim, so the post-bridge walk dominates the search. The gap
    //     width (5) exceeds Parkour's flat reach (max gap 3) and the bridge places sit at y=0, so a
    //     pop at x >= ~25 walks far clear of its own chain's edits (a genuinely edits-behind-the-path
    //     shape — kept from the retired E1/E2 edit-gate arc as a realistic mixed micro/edit scenario).
    static final BlockPos BRIDGE_GOAL = new BlockPos(70, 0, 8);
    static final int BRIDGE_GAP_MIN_X = 14, BRIDGE_GAP_MAX_X = 18;

    /**
     * BRIDGE/SPIRAL caps: place-capable walker (bridging / pillar-probing live), break OFF — the
     * headless MiningModel prices breaks at ~0, so a break-capable bot restructures both fixtures
     * (digs through the ravine rim / tunnels the spiral core) and the scenarios stop measuring what
     * they exist to measure. Mortal defaults otherwise (no hazards or forced falls in either fixture).
     */
    static final BotCaps PLACE_ONLY_CAPS = new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL,
            BotCaps.DEFAULT_MAX_FALL, true, BotCaps.DEFAULT_COST_PER_HITPOINT, false, true,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    /**
     * The BRIDGE fixture: the flat world with the ground plane removed at world x 14..18 (all z).
     * Chunk cx=0 loses local x 14..15, chunk cx=1 loses local x 0..2; every other chunk shares the
     * plain flat column. Air sections are shared exactly as in {@link #buildFlatChunks} (the gap
     * columns' floorGap saturates to proven-none just like the deep-above-ground flat cells, so the
     * shared instances hold identical depth values).
     */
    static NavGridView buildBridgeWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Shared air sections (middle slots share one instance; TOP slot its own — see buildFlatChunks).
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airMid = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airMid.getTraversalGrid());
        NavSection airTop = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airTop.getTraversalGrid());

        // Ground-section factory: stone plane at local y=0 minus the gap's local-x span for this chunk.
        java.util.function.IntFunction<NavSection> groundFor = cx -> {
            PalettedContainer<BlockState> g = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            for (int x = 0; x < 16; x++) {
                int wx = (cx << 4) + x;
                if (wx >= BRIDGE_GAP_MIN_X && wx <= BRIDGE_GAP_MAX_X) continue; // the ravine
                for (int z = 0; z < 16; z++) {
                    g.set(x, 0, z, stone);
                }
            }
            NavSection s = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(g, false, s.getTraversalGrid());
            return s;
        };

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        java.util.HashMap<Integer, NavSection[]> byCx = new java.util.HashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            NavSection[] column = byCx.computeIfAbsent(cx, c -> {
                NavSection[] col = { groundFor.apply(c), airMid, airMid, airTop }; // y 0..63
                NavSectionBuilder.computeDepth(col);
                return col;
            });
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), column);
            }
        }
        return new NavGridView(0, chunks); // minY=0, synthetic (no live level)
    }

    /**
     * Setup-time (NOT measured) shape check for BRIDGE: the search must FIND the goal, the plan must
     * actually PLACE a bridge (>= 4 place edits — the 5 gap columns), and it must not have degenerated
     * into a flood (the whole point is a realistic mixed shape, not budget exhaustion). Prints the
     * expansion + place counts so the run log records the shape.
     */
    private void bridgeSanityDryRun() {
        var plan = BlockPathfinder.findPath(grid, start, goal, caps, corridor);
        int expansions = BlockPathfinder.lastExpansions();
        int places = 0;
        if (plan != null) {
            for (int i = 0; i < plan.size(); i++) {
                var e = plan.edits(i);
                if (e != null) places += e.placeCount();
            }
        }
        System.out.println("[PathfinderBenchmark] BRIDGE sanity: found=" + (plan != null)
                + " partial=" + BlockPathfinder.lastWasPartial()
                + " expansions=" + expansions + " placeEdits=" + places);
        if (plan == null) {
            throw new IllegalStateException("BRIDGE did not find a path — fixture broken");
        }
        if (BlockPathfinder.lastWasPartial()) {
            throw new IllegalStateException("BRIDGE returned a PARTIAL — no longer the mixed-shape guard");
        }
        if (places < 2) {
            // The observed optimal crossing is place x14,x15 then PARKOUR the remaining 3-gap (2 places,
            // not 5) — still exactly the shape the scenario needs (early edits, long edit-free tail).
            throw new IllegalStateException("BRIDGE plan placed only " + places
                    + " blocks (expected >=2: the partial bridge + parkour crossing) — no longer the "
                    + "edits-then-walk-away guard");
        }
        if (expansions > 5000) {
            throw new IllegalStateException("BRIDGE expanded " + expansions
                    + " nodes — flooding, no longer the realistic mixed-shape guard");
        }
    }

    // --- SPIRAL geometry: an enclosed 11x11 solid tower (world x/z 3..13, chunk (0,0)) with a helical
    //     stair channel carved along the 24-cell perimeter ring x/z in [5..11] (walls at 4/12, solid
    //     5x5 core at 6..10). Step s stands on ring cell (s % 24) at floorY = 1 + s/2 (rise 1 per 2
    //     cells) with EXACTLY 3 carved air cells above it; 61 steps climb to floorY 31 (~2.5 loops).
    //     The solid fill alternates SPIRAL_MATS[y % 4] per Y LEVEL, so every vertical same-navtype run
    //     in the structure is length 1; the channel's air runs are capped at 3 by the carve. The goal
    //     pocket is the last step; dy=30 >> dx,dz -> the primary travel axis is Y.
    static final int SPIRAL_RING_MIN = 5, SPIRAL_RING_MAX = 11;   // ring cells: x or z == 5 or 11
    static final int SPIRAL_TOWER_MIN = 3, SPIRAL_TOWER_MAX = 13; // solid tower footprint incl. walls
    static final int SPIRAL_STEPS = 61;                            // last step floorY = 1 + 60/2 = 31
    static final int SPIRAL_TOP_Y = 35;                            // solid cap above the last pocket (34)
    static final BlockPos SPIRAL_START = new BlockPos(5, 1, 5);    // ring cell 0, floor y=1
    static final BlockPos SPIRAL_GOAL = new BlockPos(11, 31, 11);  // ring cell (60 % 24) = 12, floor y=31
    static final RegionBound SPIRAL_CORRIDOR = new RegionBound(2, 14, 0, 38, 2, 14);

    /** The alternating solid materials — MUST intern to pairwise-distinct navtypes (asserted in the
     *  sanity dry run): stone (pickaxe 1.5), dirt (shovel 0.5), oak planks (axe 2.0), sandstone
     *  (pickaxe 0.8). Adjacent Y levels then always differ, so vertical runs are length 1. A METHOD,
     *  not a static field: a {@code Blocks.*} reference in {@code <clinit>} would run before
     *  {@code Bootstrap.bootStrap()} (JMH loads the class before {@code setup()}) and blow up. */
    private static Block[] spiralMats() {
        return new Block[] { Blocks.STONE, Blocks.DIRT, Blocks.OAK_PLANKS, Blocks.SANDSTONE };
    }

    /** The 24 perimeter ring cells of the 7x7 walkway, in walk order starting at (5,5) heading +x. */
    private static int[][] spiralRing() {
        int[][] ring = new int[24][2];
        int i = 0;
        for (int x = 5; x <= 11; x++) ring[i++] = new int[] { x, 5 };   // north edge, 7 cells
        for (int z = 6; z <= 11; z++) ring[i++] = new int[] { 11, z };  // east edge, 6
        for (int x = 10; x >= 5; x--) ring[i++] = new int[] { x, 11 };  // south edge, 6
        for (int z = 10; z >= 6; z--) ring[i++] = new int[] { 5, z };   // west edge, 5
        return ring;
    }

    /**
     * The SPIRAL fixture: the flat world everywhere except chunk (0,0), which holds the tower. The
     * tower chunk's four sections are all its OWN classified instances (its air-above-tower columns
     * have a REAL floor at the tower cap, so their floorGap differs from the flat chunks' shared air
     * sections — instance sharing across different column content would corrupt the depth nibbles).
     */
    static NavGridView buildSpiralWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // The surrounding flat world (shared columns), then overwrite chunk (0,0) with the tower.
        ConcurrentHashMap<Long, NavSection[]> chunks = buildFlatChunks();

        // Dense per-cell material map for the tower chunk, world y 0..63: start from the flat chunk's
        // shape (stone ground plane at y=0), add the tower, carve the channel.
        BlockState[][][] cells = new BlockState[64][16][16]; // [y][z][x], null = air
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                cells[0][z][x] = stone; // the chunk's own ground plane
            }
        }
        Block[] mats = spiralMats();
        for (int y = 1; y <= SPIRAL_TOP_Y; y++) {
            BlockState mat = mats[y % mats.length].defaultBlockState();
            for (int z = SPIRAL_TOWER_MIN; z <= SPIRAL_TOWER_MAX; z++) {
                for (int x = SPIRAL_TOWER_MIN; x <= SPIRAL_TOWER_MAX; x++) {
                    cells[y][z][x] = mat; // solid fill, alternating navtype per Y level
                }
            }
        }
        int[][] ring = spiralRing();
        for (int s = 0; s < SPIRAL_STEPS; s++) {
            int[] cell = ring[s % 24];
            int floorY = 1 + s / 2;
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                cells[y][cell[1]][cell[0]] = null; // carve the 3-high air pocket over the step floor
            }
        }

        // Classify the tower chunk's four sections from the dense map.
        NavSection[] towerColumn = new NavSection[4];
        for (int sec = 0; sec < 4; sec++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            boolean onlyAir = true;
            for (int yl = 0; yl < 16; yl++) {
                int wy = (sec << 4) + yl;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = cells[wy][z][x];
                        if (st != null) {
                            states.set(x, yl, z, st);
                            onlyAir = false;
                        }
                    }
                }
            }
            towerColumn[sec] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(states, onlyAir, towerColumn[sec].getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(towerColumn);
        chunks.put(NavStore.key(0, 0), towerColumn);

        return new NavGridView(0, chunks); // minY=0, synthetic (no live level)
    }

    /**
     * Setup-time (NOT measured) shape checks for SPIRAL: (1) the four alternating materials MUST intern
     * to pairwise-distinct navtypes (the whole adversarial premise — if two collapse, vertical runs
     * lengthen and the fixture silently stops being the run-chain's worst case); (2) the search must FIND the top
     * (not partial); (3) the route must be climbed, not built — a plan full of place edits means the
     * pockets stopped confining Pillar and the shape drifted. Prints expansions + place count.
     */
    private void spiralSanityDryRun() {
        Block[] mats = spiralMats();
        for (int i = 0; i < mats.length; i++) {
            for (int j = i + 1; j < mats.length; j++) {
                short ni = com.orebit.mod.worldmodel.navblock.NavBlock.navtypeFor(
                        mats[i].defaultBlockState());
                short nj = com.orebit.mod.worldmodel.navblock.NavBlock.navtypeFor(
                        mats[j].defaultBlockState());
                if (ni == nj) {
                    throw new IllegalStateException("SPIRAL materials " + mats[i] + " and "
                            + mats[j] + " intern to the SAME navtype (" + ni
                            + ") — the alternation is void; pick a different material");
                }
            }
        }
        var plan = BlockPathfinder.findPath(grid, start, goal, caps, corridor);
        int expansions = BlockPathfinder.lastExpansions();
        int places = 0;
        if (plan != null) {
            for (int i = 0; i < plan.size(); i++) {
                var e = plan.edits(i);
                if (e != null) places += e.placeCount();
            }
        }
        System.out.println("[PathfinderBenchmark] SPIRAL sanity: found=" + (plan != null)
                + " partial=" + BlockPathfinder.lastWasPartial()
                + " expansions=" + expansions + " placeEdits=" + places
                + " planSize=" + (plan == null ? 0 : plan.size()));
        if (plan == null) {
            throw new IllegalStateException("SPIRAL did not find a path — fixture broken");
        }
        if (BlockPathfinder.lastWasPartial()) {
            throw new IllegalStateException("SPIRAL returned a PARTIAL — the stair channel is not climbable");
        }
        if (places > 6) {
            throw new IllegalStateException("SPIRAL plan placed " + places
                    + " blocks — it is building, not climbing; no longer the constrained-cuboids guard");
        }
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
            case KIND_SETUP:
                // Pure per-search setup: fresh view + context/scratch construction, first pop terminates
                // (goal inside the arrival tolerance). No corridor → no cuboid view, no goal probe.
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        SETUP_START, SETUP_GOAL, BotCaps.BREAK_PLACE, null));
                break;
            case KIND_SETUP_MACRO:
                // Production-shaped setup: confineBound null + cuboidBound set (the live window replan's
                // parameter shape) → ALSO pays NavGridCuboidsView construction + GoalForcedCost.probe.
                bh.consume(BlockPathfinder.findPath(new NavGridView(0, freshChunks),
                        SETUP_START, SETUP_GOAL, BotCaps.BREAK_PLACE, null, SETUP_CORRIDOR, null));
                break;
            default:
                bh.consume(BlockPathfinder.findPath(grid, start, goal, caps, corridor));
        }
    }
}
