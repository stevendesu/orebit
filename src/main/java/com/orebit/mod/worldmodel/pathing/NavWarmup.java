package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.Debug;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Boot-time JIT warm-up for the block-tier pathfinder.
 *
 * <p><b>Why:</b> the first real search after boot runs the whole pathfinder class graph interpreted /
 * C1-cold (~16 ms for a 2-node search) and the first <i>big</i> search
 * is still ~61% JIT warm-up — and because the bot spawns on player join in FOLLOW, that cold cost
 * lands on a live player tick. Only a real execution of the real code warms it, so this runs ~500 synthetic
 * searches over a private, hand-built section map at {@code SERVER_STARTED}, before any player can join.
 *
 * <p><b>Thread + timing discipline (load-bearing):</b> runs SYNCHRONOUSLY on the server thread inside the
 * {@code onServerStarted} hook. JIT warmth is JVM-global, but the {@code Nodes}/{@code EditPool} search
 * scratch is ThreadLocal and {@link NavSectionPool} is an unsynchronized, tick-thread-confined pool — a
 * background warm-up would warm the wrong thread's scratch and race live chunk builds. Registered AFTER
 * {@code ConfigLoader.load} (reads the warmup keys, uses the owner's real {@link BotCaps}) and AFTER
 * {@code MiningModel.buildTable} (movements price breaks from its table; {@link #run} refuses to run
 * before it's baked).
 *
 * <p><b>Fixture:</b> built directly via {@link TraversalGrid#set} + {@link NavSectionBuilder#computeFlags}
 * — the same public flag pass the live pipeline uses — NOT the benchmark's {@code PalettedContainer} path
 * (which is 1.18+-shaped, test-source-only, and would drag the most version-fragile code in the project
 * into a new cross-version call path). ~6 pooled sections (&lt;50 KB), shared across an 81-chunk span so
 * every cell a search can probe is inside the built map (the {@link NavGridView#overSections} no-live-level
 * contract, same discipline as the HPA {@code LeafCostComputer} and the JMH fixtures). The depth nibbles
 * ARE swept ({@link NavSectionBuilder#computeDepth} per column, exactly like the live {@code
 * ChunkNavBuilder} pass 3): live grids are depth-maintained, so the warm-up must exercise the nibble fast
 * paths (Fall's exact-landing branch, the extractor's run-chains) or the warmed branch profile would
 * mis-train production's hottest reads.
 *
 * <p><b>Scenario mix</b> (branch-profile coverage, not speed measurement — see the design doc §3.3):
 * <ul>
 *   <li><b>W-SHORT</b> ×~50/round — 28-block flat walk, fresh {@link NavGridView} + cuboid cap per search
 *       (production replan shape: warms per-search construction, probe, reconstruct);</li>
 *   <li><b>W-FLOOD</b> ×2/round — goal far above the built ceiling, no corridor: a budget-exhausted
 *       ~10k-pop pillar-cone flood (deep per-node warm, OSR-compiles the A* loop, grows the ThreadLocal
 *       scratch to its high-water organically, and — reaching the built-map ceiling — takes the UNBUILT
 *       edge branches, the folded W-EDGE);</li>
 *   <li><b>W-WALL</b> ×6/round — up-and-over across a thin wall (cuboid fragmentation, Parkour/Climb
 *       bodies, corridor confinement);</li>
 *   <li><b>W-WATER</b> ×6/round — 2-deep pond crossing from a PRONE start (the swim family bodies +
 *       the prone-mode branches, otherwise profiled never-taken).</li>
 * </ul>
 *
 * <p><b>Termination:</b> rounds run until the last round's mean W-SHORT time is within 15% of the best
 * previous round (the plateau — what waits out the asynchronous C2 queue without guessing compile
 * latency), with a minimum of {@link #MIN_ROUNDS} rounds (so the once-per-search code clears the ~200
 * C1 invocation threshold), a maximum of {@link #MAX_ROUNDS}, and a hard wall-clock cap
 * ({@code pathing.warmupBudgetMs}) checked between searches. A repeat start in the same JVM (integrated
 * server world re-open) runs a single shrunk round — the JIT is already warm; the pass just touches the
 * new server thread's ThreadLocal scratch.
 *
 * <p><b>Invariants</b> (design doc §6): zero hot-path code change (additive init-only); zero live-state
 * contact ({@code NavStore}/{@code RegionGrid}/{@code BotManager} never touched; pooled sections recycled
 * on exit); {@code LOG_TIMING}/{@code Debug.ENABLED} saved and restored; refuses to run under
 * {@code TRACE}; any throw is caught and WARNed — a warm-up bug must never break a server boot.
 */
public final class NavWarmup {

    private NavWarmup() {}

    // ---- round structure (design §3.3: ~400 shorts / ~16 floods / ~50 walls / ~50 waters at max) ----
    private static final int MAX_ROUNDS = 8;
    /** Rounds that must complete before the plateau check may exit (~200 W-SHORTs ≈ the C1 threshold). */
    private static final int MIN_ROUNDS = 4;
    private static final int FLOODS_PER_ROUND = 2;
    private static final int WALLS_PER_ROUND = 6;
    private static final int WATERS_PER_ROUND = 6;
    private static final int SHORTS_PER_ROUND = 50;
    /** Plateau: last round's mean W-SHORT within this factor of the best previous round's mean. */
    private static final double PLATEAU_FACTOR = 1.15;

    // ---- fixture geometry (world coords; minY = 0, columns of 4 sections, y 0..63, chunks -4..4) ----
    // W-SHORT: flat 28-block walk on chunk-row cz=2 (z=40), clear of the wall (cz 0..1) and pond (cz=-2).
    private static final BlockPos SHORT_START = new BlockPos(8, 0, 40);
    private static final BlockPos SHORT_GOAL = new BlockPos(36, 0, 40);
    /** The production window-search shape: UNCONFINED, with a cuboid growth cap around the walk. */
    private static final RegionBound SHORT_CUBOID_CAP = new RegionBound(0, 44, 0, 8, 32, 48);
    // W-FLOOD: goal far above the built ceiling (y=63) — unreachable, budget-exhausted, no corridor.
    private static final BlockPos FLOOD_START = new BlockPos(8, 0, 8);
    private static final BlockPos FLOOD_GOAL = new BlockPos(8, 100, 8);
    // W-WALL: the UPOVER_WALL shape — wall plane x=15 (y 1..7, z 0..31), corridor-confined up-and-over.
    private static final BlockPos WALL_START = new BlockPos(8, 0, 8);
    private static final BlockPos WALL_GOAL = new BlockPos(23, 30, 23);
    private static final RegionBound WALL_CORRIDOR = new RegionBound(2, 29, 0, 33, 2, 29);
    private static final int WALL_X = 15, WALL_TOP_Y = 7;
    // W-WATER: pond chunk (cx=2, cz=-2) = x 32..47, z -32..-17, water at y 1..2. Start submerged (PRONE).
    private static final BlockPos WATER_START = new BlockPos(36, 0, -24);
    private static final BlockPos WATER_GOAL = new BlockPos(52, 0, -24);

    /** One-shot summary of a warm-up pass (consumed by the cold-start harness + the boot log line). */
    public record Result(int rounds, int searches, long wallMs, boolean plateau,
                         double firstShortMeanUs, double lastShortMeanUs) {}

    /** Repeat-start shrink (design §3.6): once this JVM has warmed, a re-open runs a single small round. */
    private static boolean warmedThisJvm = false;

    /**
     * Run the warm-up under a hard wall-clock cap of {@code budgetMs} (≤ 0 = disabled). Never throws.
     * Returns the pass summary, or {@code null} when it refused to run (disabled / not ready / TRACE on).
     */
    public static Result run(int budgetMs) {
        if (budgetMs <= 0) return null;
        if (!MiningModel.ready()) {
            OrebitCommon.LOGGER.warn("[Orebit] NavWarmup skipped: MiningModel not baked yet (hook order bug?)");
            return null;
        }
        if (BlockPathfinder.TRACE) {
            OrebitCommon.LOGGER.warn("[Orebit] NavWarmup skipped: BlockPathfinder.TRACE is on");
            return null;
        }
        final boolean savedLog = BlockPathfinder.LOG_TIMING;
        final boolean savedDebug = Debug.ENABLED;
        BlockPathfinder.LOG_TIMING = false; // ~500 INFO lines at boot otherwise
        Debug.ENABLED = false;              // explainFailure would fire per flood otherwise
        NavSection[] distinct = null;
        try {
            final long t0 = System.nanoTime();
            final ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
            distinct = buildFixture(chunks);
            final BotCaps caps = ConfigLoader.botCaps(); // the owner's REAL caps — warm what will run

            final int maxRounds = warmedThisJvm ? 1 : MAX_ROUNDS;
            double bestShortMeanNs = Double.MAX_VALUE;
            double firstShortMeanNs = -1, lastShortMeanNs = -1;
            int rounds = 0, searches = 0;
            boolean plateau = false;

            outer:
            for (int r = 0; r < maxRounds; r++) {
                for (int i = 0; i < FLOODS_PER_ROUND; i++) {
                    if (overBudget(t0, budgetMs)) break outer;
                    BlockPathfinder.findPath(view(chunks), FLOOD_START, FLOOD_GOAL, caps, null);
                    searches++;
                }
                for (int i = 0; i < WALLS_PER_ROUND; i++) {
                    if (overBudget(t0, budgetMs)) break outer;
                    BlockPathfinder.findPath(view(chunks), WALL_START, WALL_GOAL, caps, WALL_CORRIDOR);
                    searches++;
                }
                for (int i = 0; i < WATERS_PER_ROUND; i++) {
                    if (overBudget(t0, budgetMs)) break outer;
                    BlockPathfinder.findPath(view(chunks), WATER_START, WATER_GOAL, caps, null);
                    searches++;
                }
                long shortTotal = 0;
                for (int i = 0; i < SHORTS_PER_ROUND; i++) {
                    if (overBudget(t0, budgetMs)) break outer;
                    long s0 = System.nanoTime();
                    // The production replan shape: unconfined, cuboid growth cap, no inventory snapshot.
                    BlockPathfinder.findPath(view(chunks), SHORT_START, SHORT_GOAL, caps,
                            null, SHORT_CUBOID_CAP, null);
                    shortTotal += System.nanoTime() - s0;
                    searches++;
                }
                rounds++;
                double mean = shortTotal / (double) SHORTS_PER_ROUND;
                if (firstShortMeanNs < 0) firstShortMeanNs = mean;
                lastShortMeanNs = mean;
                // Plateau = the round STOPPED IMPROVING on the best previous round by more than the
                // factor (times no longer falling ⇒ the C2 queue has drained). A round still >15%
                // faster than anything before it means compilation is still landing — keep going.
                if (rounds >= MIN_ROUNDS && mean * PLATEAU_FACTOR >= bestShortMeanNs) {
                    plateau = true;
                    break;
                }
                if (mean < bestShortMeanNs) bestShortMeanNs = mean;
            }

            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            Result res = new Result(rounds, searches, wallMs, plateau,
                    firstShortMeanNs / 1000.0, lastShortMeanNs / 1000.0);
            OrebitCommon.LOGGER.info(
                    "[Orebit] NavWarmup: {} searches in {} ms over {} round(s); plateau={}; "
                            + "shortMean first={}us last={}us",
                    res.searches(), res.wallMs(), res.rounds(), res.plateau(),
                    String.format("%.1f", res.firstShortMeanUs()),
                    String.format("%.1f", res.lastShortMeanUs()));
            return res;
        } catch (Throwable t) {
            // Degrade-don't-crash: a warm-up bug costs the warm-up, never the boot (design §8 risk 7).
            OrebitCommon.LOGGER.warn("[Orebit] NavWarmup failed — continuing boot", t);
            return null;
        } finally {
            warmedThisJvm = true;
            BlockPathfinder.LOG_TIMING = savedLog;
            Debug.ENABLED = savedDebug;
            if (distinct != null) {
                for (NavSection s : distinct) {
                    if (s != null) s.recycle(); // reset(origin)-on-reuse wipes them; no stale-grid leak
                }
            }
        }
    }

    /** A fresh per-search view — the same per-replan construction cost production pays (W-SHORT warms it). */
    private static NavGridView view(ConcurrentHashMap<Long, NavSection[]> chunks) {
        return NavGridView.overSections(0, chunks);
    }

    private static boolean overBudget(long t0, int budgetMs) {
        return (System.nanoTime() - t0) / 1_000_000 >= budgetMs;
    }

    /**
     * Build the fixture into {@code chunks} and return the distinct pooled sections (for recycling):
     * flat stone ground at y=0 across chunks -4..4, the W-WALL plane in chunk columns (0,0)/(0,1), and
     * the W-WATER pond column at (2,-2). Columns share section instances (the grid is read-only during
     * a search), so the whole 81-chunk span is 6 classified sections.
     */
    private static NavSection[] buildFixture(ConcurrentHashMap<Long, NavSection[]> chunks) {
        final int stone = NavBlock.navtypeFor(Blocks.STONE.defaultBlockState()) & 0xFFFF;
        final int water = NavBlock.navtypeFor(Blocks.WATER.defaultBlockState()) & 0xFFFF;
        final int air = NavBlock.AIR & 0xFFFF;

        // All-air sections: computeFlags's uniform-air fast path fills navtype + flags itself. Two
        // instances (mid + top) mirror the live column discipline (the top slot borders unbuilt).
        NavSection airMid = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.computeFlags(airMid.getTraversalGrid(), true, null);
        NavSection airTop = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.computeFlags(airTop.getTraversalGrid(), true, null);

        // Ground: stone plane at local y=0, air above. (Above is uniform air ⇒ null overscan is exact.)
        NavSection ground = NavSection.create(BlockPos.ZERO);
        fillPlaneAndAir(ground.getTraversalGrid(), stone, air);
        NavSectionBuilder.computeFlags(ground.getTraversalGrid(), false, null);

        // Wall: ground + a one-block-thick stone wall at local x=15, y 1..7 (spans the section in z).
        NavSection wallLow = NavSection.create(BlockPos.ZERO);
        TraversalGrid wallGrid = wallLow.getTraversalGrid();
        fillPlaneAndAir(wallGrid, stone, air);
        for (int z = 0; z < 16; z++) {
            for (int y = 1; y <= WALL_TOP_Y; y++) {
                wallGrid.set(WALL_X & 15, y, z, stone, 0);
            }
        }
        NavSectionBuilder.computeFlags(wallGrid, false, null);

        // Pond: ground + 2-deep water (y 1..2) across the whole section.
        NavSection pondLow = NavSection.create(BlockPos.ZERO);
        TraversalGrid pondGrid = pondLow.getTraversalGrid();
        fillPlaneAndAir(pondGrid, stone, air);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                pondGrid.set(x, 1, z, water, 0);
                pondGrid.set(x, 2, z, water, 0);
            }
        }
        NavSectionBuilder.computeFlags(pondGrid, false, null);

        // The wall column needs its OWN slot-1 air section: the wall top (y=7) is a standable floor, so
        // the floorGap of the air cells y 16..21 above it (x=15) differs from the flat column's SAT —
        // instance-shared cells must hold identical depth values (same rule as the JMH fixtures).
        NavSection wallAirMid = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.computeFlags(wallAirMid.getTraversalGrid(), true, null);

        NavSection[] flatColumn = { ground, airMid, airMid, airTop };
        NavSection[] wallColumn = { wallLow, wallAirMid, airMid, airTop };
        NavSection[] pondColumn = { pondLow, airMid, airMid, airTop };
        // Depth sweep per column, matching the live ChunkNavBuilder pass 3 (see the class doc). The pond
        // column's shared air slots re-derive the flat column's exact values (water is not standable, so
        // its gaps saturate identically); the wall's divergent slot is the distinct wallAirMid above.
        NavSectionBuilder.computeDepth(flatColumn);
        NavSectionBuilder.computeDepth(wallColumn);
        NavSectionBuilder.computeDepth(pondColumn);
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                chunks.put(NavStore.key(cx, cz), flatColumn);
            }
        }
        chunks.put(NavStore.key(0, 0), wallColumn); // wall plane x=15 spans z 0..31 → chunk rows 0..1
        chunks.put(NavStore.key(0, 1), wallColumn);
        chunks.put(NavStore.key(2, -2), pondColumn); // the pond chunk (x 32..47, z -32..-17)

        return new NavSection[] { ground, airMid, airTop, wallLow, wallAirMid, pondLow };
    }

    /** Stone plane at local y=0, air navtypes above (flags 0 — {@code computeFlags} recomputes them). */
    private static void fillPlaneAndAir(TraversalGrid grid, int stone, int air) {
        for (int y = 0; y < 16; y++) {
            int nav = y == 0 ? stone : air;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    grid.set(x, y, z, nav, 0);
                }
            }
        }
    }
}
