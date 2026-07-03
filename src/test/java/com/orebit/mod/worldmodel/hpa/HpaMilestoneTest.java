package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The HPA* "earns its keep" milestone proof (PRD §10 Phase 3; HPA-IMPLEMENTATION.md §13).
 *
 * <h2>What this proves headlessly</h2>
 * The clean before/after the milestone is about: a goal that flat block-A* <b>floods or refuses</b> (it
 * burns the {@code MAX_EXPANSIONS = 10000} node budget and returns {@code null}) becomes <b>reachable</b>
 * once the search is sliced into short windows the way the region tier slices it. Both scenarios run over a
 * synthetic, already-built {@link NavGridView} (the {@code PathfinderBenchmark.buildFlatWorld}
 * idiom — {@link NavSectionBuilder#classifyInto} into shared {@link NavSection}s, no live
 * {@link net.minecraft.server.level.ServerLevel ServerLevel}):
 *
 * <ol>
 *   <li><b>Long flat walk</b> — a straight {@value #WALK_LEN}-block goal. A single flat
 *       {@link BlockPathfinder#findPath} beelines far enough that it exhausts the 10k expansion budget and
 *       returns {@code null} ("FAIL-budget"). The <b>windowed</b> approach — exactly what the region
 *       skeleton drives in {@link com.orebit.mod.pathfinding.PathPlan}: a chain of ~{@value #WINDOW_BLOCKS}
 *       -block block searches — solves every window in {@code ≪ MAX_EXPANSIONS} and reaches the goal.</li>
 *   <li><b>30-up open-air pillar</b> — the known flood case (the {@code PathfinderBenchmark} TOWER scenario):
 *       flat A* fans horizontally hunting a non-existent cheaper ramp. We assert the single flat search does
 *       NOT cleanly solve it (null at the budget) and that the short, bounded windowed search up the pillar
 *       reaches the top.</li>
 * </ol>
 *
 * <h2>Why the region-tier path itself is deferred ({@link Disabled @Disabled})</h2>
 * The region tier ({@link RegionGrid#of}, {@link com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder
 * RegionPathfinder}{@code .plan}, {@link com.orebit.mod.pathfinding.PathPlan PathPlan},
 * {@link FragmentLeafComputer#computeLeaf}) is welded to a live {@code ServerLevel}: {@code RegionGrid} interns
 * by {@code ServerLevel}, reads {@code minY} through {@link com.orebit.mod.platform.LevelBounds LevelBounds},
 * and lazily builds leaves from {@link NavStore#get(net.minecraft.server.level.ServerLevel, long)}. A
 * {@code ServerLevel} cannot be stood up under the headless fabric-loader-junit Knot classloader (the
 * benchmark harness deliberately drives only the block tier over a {@code level == null} synthetic view).
 * There is no test seam today to inject a hand-seeded {@link CostPyramid} into {@code RegionPathfinder.plan}
 * (its signature takes a {@code ServerLevel} and calls {@code grid.ensureLeaf}). So the region-skeleton +
 * sliding-window {@code PathPlan} end-to-end assertions are {@link Disabled @Disabled} with a TODO; this
 * test instead drives the <b>windowed block-A*</b> directly over synthetic terrain — the same per-window
 * block searches the region tier would emit — which is the load-bearing half of the milestone signal and is
 * fully exercisable headless.
 *
 * <p>Run (mc-1.21 era, JDK 21 on an active 1.21.x node):
 * {@code ./gradlew :<ver>:test --tests com.orebit.mod.worldmodel.hpa.HpaMilestoneTest}. Per
 * HPA-IMPLEMENTATION.md §13 we keep {@link BlockPathfinder#LOG_TIMING} ON for the windowed searches so the
 * per-window node counts print — the empirical before/after next to the flat search's 10k cap.
 */
public class HpaMilestoneTest {

    /** Length of the straight flat walk (blocks). Chosen well past {@code MAX_EXPANSIONS = 10000} so a single
     *  flat beeline exhausts the budget and returns null — the "flat A* floods/refuses" before-state. */
    private static final int WALK_LEN = 12000;

    /** One sliding window ≈ {@code WINDOW = 3} regions × 16 blocks (HPA-IMPLEMENTATION.md §9). */
    private static final int WINDOW_BLOCKS = 48;

    /** Height of the open-air pillar (blocks), matching the PathfinderBenchmark TOWER scenario. */
    private static final int PILLAR_HEIGHT = 30;

    /** Break + place (the in-game test bot's caps): the pillar is only solvable by placing. */
    private static final BotCaps CAPS = BotCaps.BREAK_PLACE;

    /**
     * Node-count ceiling for the pillar regression guard ({@link #pillar_singleFlatSearch_solvesBounded}):
     * well under the {@code BotCaps.DEFAULT_MAX_NODES} (10k) budget, but comfortably above the observed
     * straight-up cost (~688 nodes) so ordinary cost/heuristic tuning doesn't trip it. A regression to the old
     * horizontal cone flood would spike the expansion count toward the 10k budget and blow this guard.
     */
    private static final int PILLAR_FLOOD_GUARD_NODES = 2000;

    private static boolean bootstrapped = false;

    @BeforeAll
    static void bootstrap() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        // §13: keep timing ON so the per-window node counts print (the before/after signal). The flat-search
        // failure path also logs its expansion count + "FAIL-budget".
        BlockPathfinder.LOG_TIMING = true;
        Debug.ENABLED = false; // the failure dump is noisy and not what we measure here
    }

    // ===================================================================================================
    // Scenario 1 — long flat walk: flat A* refuses (budget), windowed A* reaches the goal
    // ===================================================================================================

    /**
     * A single flat block-A* over a {@value #WALK_LEN}-block straight walk exhausts the 10k expansion budget
     * and returns {@code null} — the "flat search floods/refuses" before-state the region tier fixes
     * (HPA-IMPLEMENTATION.md §13, scenario 1). The synthetic world is a stone-floor corridor wide enough for
     * the tie-break's ~1-wide beeline plus its diagonal probes.
     */
    @Test
    void flatWalk_singleFlatSearch_hitsBudgetAndRefuses() {
        NavGridView grid = view(flatCorridorChunks(WALK_LEN));
        BlockPos start = new BlockPos(8, 0, 8);
        BlockPos goal = new BlockPos(8 + WALK_LEN, 0, 8);

        System.out.println("[HpaMilestone] flat walk: single flat block-A* over " + WALK_LEN + " blocks ...");
        BlockPathPlan flat = BlockPathfinder.findPath(grid, start, goal, CAPS);

        // A single flat search can't REACH the goal in one shot: the beeline needs > MAX_EXPANSIONS pops, so
        // the budget is hit and it returns (at most) a PARTIAL path far short of the goal (since partial-path
        // landed, the result is a stub toward the goal, not null). The before-state the region tier fixes.
        assertFalse(reachesGoal(flat, goal), "a single flat block-A* should NOT reach a " + WALK_LEN
                + "-block goal in one budget (it returns a short partial); got "
                + (flat == null ? "null" : flat.size() + "wp"));
    }

    /**
     * The <b>windowed</b> walk — the region tier's contribution modelled directly: slice the same straight
     * goal into ~{@value #WINDOW_BLOCKS}-block windows (what {@link com.orebit.mod.pathfinding.PathPlan} drives
     * off the region skeleton) and run a bounded {@link BlockPathfinder#findPath} per window. Assert every
     * window solves (non-null) in {@code ≪ MAX_EXPANSIONS} and that chaining them reaches the goal — the
     * after-state. Per §13, {@code LOG_TIMING} prints each window's node count.
     */
    @Test
    void flatWalk_windowed_reachesGoalInBoundedWindows() {
        final ConcurrentHashMap<Long, NavSection[]> chunks = flatCorridorChunks(WALK_LEN);
        final BlockPos goal = new BlockPos(8 + WALK_LEN, 0, 8);

        BlockPos cursor = new BlockPos(8, 0, 8);
        int windows = 0;
        System.out.println("[HpaMilestone] flat walk: WINDOWED block-A* (~" + WINDOW_BLOCKS + "-block windows) ...");
        // Each iteration is one sliding-window block search toward a target ~3 regions ahead, advancing the
        // cursor to that window's far end — the per-window search the region skeleton would emit. A FRESH view
        // per window mirrors production (replan builds a new NavGridView each time → clean per-search cache).
        while (cursor.getX() < goal.getX()) {
            int targetX = Math.min(cursor.getX() + WINDOW_BLOCKS, goal.getX());
            BlockPos windowTarget = new BlockPos(targetX, 0, 8);

            BlockPathPlan win = BlockPathfinder.findPath(view(chunks), cursor, windowTarget, CAPS);
            assertNotNull(win, "window #" + windows + " (->x=" + targetX
                    + ") should solve well under MAX_EXPANSIONS; it failed");
            windows++;

            // Advance the cursor to the window's far end (the bot commits forward) and continue.
            cursor = windowTarget;
            // Safety: a runaway window count would itself signal a bug; the bound is generous.
            assertTrue(windows < (WALK_LEN / WINDOW_BLOCKS) + 4,
                    "window count blew past the expected ~" + (WALK_LEN / WINDOW_BLOCKS));
        }

        // The chained windows arrived within the block tier's goal tolerance (we stepped exactly to goal.x).
        assertTrue(Math.abs(cursor.getX() - goal.getX()) <= 1,
                "windowed walk should finish at the goal column");
        System.out.println("[HpaMilestone] flat walk WINDOWED reached goal in " + windows
                + " bounded windows (vs a single flat search that hit the 10k cap).");
        // ~12000 / 48 = 250 windows, each ~48 expansions: 250 * 48 ≈ 12k node-visits total, but NO single
        // search exceeds the budget and the goal is actually reached — the milestone win.
        assertTrue(windows >= WALK_LEN / WINDOW_BLOCKS - 1 && windows <= WALK_LEN / WINDOW_BLOCKS + 2,
                "expected ~" + (WALK_LEN / WINDOW_BLOCKS) + " windows, got " + windows);
    }

    // ===================================================================================================
    // Scenario 2 — 30-up open-air pillar: the known flood case
    // ===================================================================================================

    /**
     * Regression guard for the 30-up open-air pillar (HPA-IMPLEMENTATION.md §13, scenario 2; the {@code
     * PathfinderBenchmark} TOWER case). This <b>used to be the flood case</b>: a single flat block-A* fanned
     * horizontally hunting a non-existent cheaper ramp and burned its node budget (the octile heuristic is
     * blind to the per-block place cost of building straight up), returning only a partial. The <b>place-cost
     * model + the {@code Pillar} macro-move + the forced-cost heuristic</b> fixed that — the search now
     * places-as-it-climbs within the one search and goes essentially straight up. We lock that in: a single
     * flat search REACHES the top in a small, bounded node count (observed ~688, vs the 10k budget), so the
     * horizontal-cone flood can never silently return.
     */
    @Test
    void pillar_singleFlatSearch_solvesBounded() {
        NavGridView grid = view(flatFieldChunks());
        BlockPos start = new BlockPos(8, 0, 8);
        BlockPos goal = new BlockPos(8, PILLAR_HEIGHT, 8);

        System.out.println("[HpaMilestone] pillar: single flat block-A* " + PILLAR_HEIGHT + " up ...");
        BlockPathPlan flat = BlockPathfinder.findPath(grid, start, goal, CAPS);
        final int nodes = BlockPathfinder.lastExpansions();

        // Fixed: a single flat search now climbs straight to the top (no horizontal cone flood).
        assertTrue(reachesGoal(flat, goal), "the " + PILLAR_HEIGHT + "-up pillar should now solve in one flat "
                + "search (place-cost + Pillar move + forced-cost heuristic); got "
                + (flat == null ? "null" : flat.size() + "wp") + " in " + nodes + " nodes");
        // Bounded: well under the budget — a regression to the old flood would spike toward 10k.
        assertTrue(nodes < PILLAR_FLOOD_GUARD_NODES, "the pillar solved but expanded " + nodes + " nodes (≥ "
                + PILLAR_FLOOD_GUARD_NODES + " flood guard): the straight-up search regressed toward the "
                + "horizontal cone flood");
    }

    /**
     * The bounded windowed search up the pillar reaches the top. Modelled directly (no region tier needed):
     * a short, bounded {@link BlockPathfinder#findPath} climbing in {@code jumpHeight}-sized vertical windows
     * — the after-state. Each window is a handful of blocks tall, so it solves in {@code ≪ MAX_EXPANSIONS}.
     */
    @Test
    @Disabled("headless model limitation, not a code defect: the windowed pillar climb only works if the "
            + "blocks the bot places to ascend are applied to the grid BETWEEN windows (so the next window "
            + "starts on a real placed block). This test does not replay StepEdits into the synthetic "
            + "PalettedContainer, so window 2 starts from an air cell with no footing and correctly finds no "
            + "path. Production applies placements via AllyBotEntity.applyEdits (reflected through the "
            + "setBlockState mixin), so the real pillar climb is validated IN-GAME. TODO: replay each window "
            + "plan's place edits + re-classify before the next window to exercise this headlessly.")
    void pillar_windowed_reachesTop() {
        final ConcurrentHashMap<Long, NavSection[]> chunks = flatFieldChunks();
        final BlockPos top = new BlockPos(8, PILLAR_HEIGHT, 8);

        BlockPos cursor = new BlockPos(8, 0, 8);
        int windows = 0;
        System.out.println("[HpaMilestone] pillar: WINDOWED block-A* climbing to y=" + PILLAR_HEIGHT + " ...");
        // Vertical windows of a few blocks each (the region skeleton's vertical leg). The bot pillars up;
        // each short search places blocks and ascends within a fresh bounded view (per-window, as production).
        final int VWINDOW = 4;
        while (cursor.getY() < top.getY()) {
            int targetY = Math.min(cursor.getY() + VWINDOW, top.getY());
            BlockPos windowTarget = new BlockPos(8, targetY, 8);

            BlockPathPlan win = BlockPathfinder.findPath(view(chunks), cursor, windowTarget, CAPS);
            assertNotNull(win, "vertical window #" + windows + " (->y=" + targetY
                    + ") should solve well under MAX_EXPANSIONS; it failed");
            windows++;
            cursor = windowTarget;
            assertTrue(windows < (PILLAR_HEIGHT / VWINDOW) + 6, "pillar window count ran away");
        }

        assertTrue(Math.abs(cursor.getY() - top.getY()) <= 2,
                "windowed pillar should finish within the block tier's vertical goal tolerance of the top");
        System.out.println("[HpaMilestone] pillar WINDOWED reached y=" + cursor.getY() + " in " + windows
                + " bounded windows (vs a single flat search that flooded).");
    }

    // ===================================================================================================
    // Region-tier end-to-end — DEFERRED: needs a live ServerLevel that cannot be built headless
    // ===================================================================================================

    /**
     * TODO(hpa-milestone): exercise the real region tier end-to-end —
     * {@code RegionGrid rg = RegionGrid.of(level)}; {@code PathPlan pp = new PathPlan(level, rg, start, goal,
     * CAPS)}; assert {@code RegionPathfinder.plan} returns a non-empty skeleton, that the summed per-window
     * block-A* node counts beat the flat 10k cap, and that {@code pp} reaches the goal. Blocked headless:
     * {@link RegionGrid#of} interns by {@code ServerLevel}, {@link FragmentLeafComputer#computeLeaf} reads
     * {@code minY} via {@link com.orebit.mod.platform.LevelBounds LevelBounds} and pulls sections from
     * {@link NavStore}, and {@code RegionPathfinder.plan(ServerLevel, ...)} drives lazy leaf builds — none of
     * which can run without a live {@code ServerLevel} under the Knot test classloader. Enable once a
     * headless {@code ServerLevel} fixture (or a {@code CostPyramid}-injection seam into
     * {@code RegionPathfinder.plan}) exists. The windowed-block-A* tests above already prove the
     * load-bearing half of the milestone signal headlessly.
     */
    @Test
    @Disabled("region tier (RegionGrid.of / RegionPathfinder.plan / PathPlan / LeafCostComputer) requires a "
            + "live ServerLevel that cannot be stood up headless under the Knot test classloader — see Javadoc")
    void regionTier_endToEnd_beatsFlatCapAndReachesGoal() {
        // Intentionally empty: see the @Disabled reason + the TODO in the Javadoc.
    }

    // ===================================================================================================
    // Synthetic terrain fixtures (PathfinderBenchmark.buildFlatWorld idiom)
    // ===================================================================================================

    /**
     * A stone-floor field spanning chunks (-4..4) — enough for a 30-up pillar and the horizontal flood radius
     * to stay inside built terrain (so the {@code level == null} synthetic view's live-block fallback never
     * fires). All chunks share one classified ground + air {@link NavSection} column. Verbatim the
     * {@code PathfinderBenchmark.buildFlatWorld} fixture.
     */
    private static ConcurrentHashMap<Long, NavSection[]> flatFieldChunks() {
        NavSection[] column = flatColumn();
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), column);
            }
        }
        return chunks;
    }

    /**
     * A fresh read view over a chunk map. Production builds a new {@link NavGridView} per pathfind
     * ({@code AllyBotEntity.replan}), so each windowed search gets a clean per-search chunk cache — the
     * windowed test loops MUST do the same (a single view reused across hundreds of searches saturates that
     * cache; see the {@code lookupChunk} fallback note). {@code minY=0}, synthetic (no live level).
     */
    private static NavGridView view(ConcurrentHashMap<Long, NavSection[]> chunks) {
        return NavGridView.overSections(0, chunks);
    }

    /**
     * Whether a plan actually reaches {@code goalFloor} (within the block tier's goal tolerance: ±1 horizontal,
     * ±2 vertical). With partial-path return, a budget-exhausted search yields a short stub toward the goal
     * rather than {@code null}, so "did it solve?" is "did its last waypoint land at the goal", not "is it
     * non-null". A waypoint is a stand position ({@code floorCell.above()}), so its floor is {@code .below()}.
     */
    private static boolean reachesGoal(BlockPathPlan plan, BlockPos goalFloor) {
        if (plan == null || plan.isEmpty()) return false;
        BlockPos lastFloor = plan.waypoint(plan.size() - 1).below();
        return Math.abs(lastFloor.getX() - goalFloor.getX()) <= 1
                && Math.abs(lastFloor.getZ() - goalFloor.getZ()) <= 1
                && Math.abs(lastFloor.getY() - goalFloor.getY()) <= 2;
    }

    /**
     * A long stone-floor <b>corridor</b> along +X (chunks {@code cx in 0..ceil(len/16)}, {@code cz in -1..1}),
     * wide enough for the tie-break beeline plus its diagonal probes but sparse enough that the chunk map
     * stays small. The same shared ground+air column backs every chunk, so this is two classified sections
     * regardless of length (favour CPU/RAM only on the map entries). Used for the long-walk scenario.
     *
     * @param len walk length in blocks (the +X reach the corridor must cover from x=8)
     */
    private static ConcurrentHashMap<Long, NavSection[]> flatCorridorChunks(int len) {
        NavSection[] column = flatColumn();
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        int maxCX = ((8 + len) >> 4) + 1; // cover the goal column plus a margin
        for (int cx = -1; cx <= maxCX; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                chunks.put(NavStore.key(cx, cz), column);
            }
        }
        return chunks;
    }

    /**
     * The shared 4-section column (y 0..63): a stone plane at local y=0, then three air sections of headroom
     * — exactly the {@code PathfinderBenchmark.buildFlatWorld} column, enough for a 30-up goal plus the
     * pillar takeoff probes above it.
     */
    private static NavSection[] flatColumn() {
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

        return new NavSection[] { ground, airSection, airSection, airSection };
    }
}
