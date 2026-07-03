package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Byte-identity harness for the floorGap/runUp depth-nibble consumers ({@code Fall}'s exact-landing fast
 * path, {@code CuboidExtractor}'s run-chain column mode + Y-travel slab skip): a search over a
 * depth-MAINTAINED grid must produce <b>exactly</b> the results of the same search over the same grid with
 * its depth erased to {@link TraversalGrid#DEPTH_UNKNOWN} (the single-section-producer regime, where every
 * consumer takes its legacy scan) — same expansion count, same found/partial status, same waypoints, same
 * per-step movements, same break/place edits, same bit-exact cost. Both nibbles are memoizations of
 * predicates the legacy scans already evaluate over the same resident data, so any divergence is a bug,
 * not a tuning question. ({@code DepthNibbleTest} proves the stored values themselves brute-force-exact;
 * this proves the CONSUMERS translate them into identical search behaviour.)
 *
 * <p>Scenarios are the JMH fixtures (including the Fall-heavy CLIFFS), reused from
 * {@link PathfinderBenchmark} so the guarded surface matches what gets timed.
 */
class DepthIdentityTest {

    private static boolean bootstrapped;
    private static boolean savedLogTiming;
    private static boolean savedDebug;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        savedLogTiming = BlockPathfinder.LOG_TIMING;
        savedDebug = Debug.ENABLED;
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    @AfterAll
    static void restore() {
        BlockPathfinder.LOG_TIMING = savedLogTiming;
        Debug.ENABLED = savedDebug;
    }

    private record Scenario(String name, Supplier<ConcurrentHashMap<Long, NavSection[]>> chunks,
                            BlockPos start, BlockPos goal, RegionBound corridor, BotCaps caps) { }

    private static Scenario sc(String name, Supplier<ConcurrentHashMap<Long, NavSection[]>> chunks,
                               BlockPos start, BlockPos goal, RegionBound corridor) {
        return new Scenario(name, chunks, start, goal, corridor, BotCaps.BREAK_PLACE);
    }

    private static List<Scenario> scenarios() {
        List<Scenario> s = new ArrayList<>();
        // CLIFFS — the targeted Fall-heavy shape: terrace-edge pops with deep open cardinals, every one
        // taking the nibble's exact-landing or proven-none branch. Walk-only + damage-immune caps,
        // matching the benchmark (see PathfinderBenchmark.CLIFFS_CAPS).
        s.add(new Scenario("CLIFFS", PathfinderBenchmark::buildCliffsChunks,
                PathfinderBenchmark.CLIFFS_START, PathfinderBenchmark.CLIFFS_GOAL, null,
                PathfinderBenchmark.CLIFFS_CAPS));
        // FLOOD — the open-air pillar cone (10001 pops): the SAT/proven-none branch en masse, plus
        // edit-bearing chains that must route Fall back to the legacy scan (the edits-disjoint gate).
        s.add(sc("FLOOD", PathfinderBenchmark::buildFlatChunks,
                new BlockPos(8, 0, 8), new BlockPos(8, 100, 8), null));
        // TOWER — corridor + macro collapse: the Y-travel stage-2 skip on the probe/Pillar extractions.
        s.add(sc("TOWER", PathfinderBenchmark::buildFlatChunks,
                new BlockPos(8, 0, 8), new BlockPos(8, 30, 8), new RegionBound(-1, 17, 0, 33, -1, 17)));
        // UPOVER_* — mixed micro/macro; UPOVER_WALL fragments the boxes so the rectUniform column mode
        // sees narrow, seam-crossing sub-boxes.
        s.add(sc("UPOVER_OPEN", PathfinderBenchmark::buildFlatChunks,
                PathfinderBenchmark.UPOVER_START, PathfinderBenchmark.UPOVER_GOAL,
                PathfinderBenchmark.UPOVER_CORRIDOR));
        s.add(sc("UPOVER_WALL", PathfinderBenchmark::buildWalledChunks,
                PathfinderBenchmark.UPOVER_START, PathfinderBenchmark.UPOVER_GOAL,
                PathfinderBenchmark.UPOVER_CORRIDOR));
        // SHORT — the flat beeline: every Fall cardinal is the fg==0 zero-read skip; must stay identical.
        s.add(sc("SHORT", PathfinderBenchmark::buildFlatChunks,
                PathfinderBenchmark.SHORT_START, PathfinderBenchmark.SHORT_GOAL, null));
        return s;
    }

    /** Erase every section's depth bytes to UNKNOWN — the "same grid, never column-swept" regime. */
    private static void eraseDepth(ConcurrentHashMap<Long, NavSection[]> chunks) {
        Set<NavSection> seen = new HashSet<>(); // columns share section instances — erase each once
        for (NavSection[] column : chunks.values()) {
            for (NavSection s : column) {
                if (s != null && seen.add(s)) {
                    java.util.Arrays.fill(s.getTraversalGrid().depthRaw(), TraversalGrid.DEPTH_UNKNOWN_BYTE);
                }
            }
        }
    }

    private record Result(int expansions, boolean partial, boolean found, int costBits, List<String> steps) { }

    private static Result run(Scenario sc, ConcurrentHashMap<Long, NavSection[]> chunks) {
        BlockPathPlan plan = BlockPathfinder.findPath(new NavGridView(0, chunks), sc.start(), sc.goal(),
                sc.caps(), sc.corridor());
        List<String> steps = new ArrayList<>();
        if (plan != null) {
            for (int i = 0; i < plan.size(); i++) {
                BlockPos wp = plan.waypoint(i);
                StringBuilder sb = new StringBuilder();
                sb.append(wp.getX()).append(',').append(wp.getY()).append(',').append(wp.getZ())
                        .append('|').append(plan.movement(i).getClass().getSimpleName());
                StepEdits e = plan.edits(i);
                if (e != null) {
                    sb.append("|bk:");
                    for (int b = 0; b < e.breakCount(); b++) sb.append(e.breakAt(b)).append(';');
                    sb.append("pl:");
                    for (int p = 0; p < e.placeCount(); p++) sb.append(e.placeAt(p)).append(';');
                }
                steps.add(sb.toString());
            }
        }
        return new Result(BlockPathfinder.lastExpansions(), BlockPathfinder.lastWasPartial(), plan != null,
                plan != null ? Float.floatToIntBits(plan.cost()) : 0, steps);
    }

    @Test
    void maintainedGridIsByteIdenticalToUnknown() {
        for (Scenario sc : scenarios()) {
            // Maintained: the fixture exactly as the live pipeline builds it (column-swept depth).
            Result maintained = run(sc, sc.chunks().get());

            // UNKNOWN: a fresh identical fixture with the depth erased — every consumer legacy-scans.
            ConcurrentHashMap<Long, NavSection[]> erased = sc.chunks().get();
            eraseDepth(erased);
            Result legacy = run(sc, erased);

            assertEquals(legacy, maintained,
                    sc.name() + ": depth-maintained search diverged from the erased-to-UNKNOWN legacy scan");
        }
    }
}
