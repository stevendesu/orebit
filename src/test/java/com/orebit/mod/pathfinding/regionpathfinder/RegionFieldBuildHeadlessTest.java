package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.FullSearchScenarios;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Guards the {@link RegionFieldBuildBenchmark} fixture outside JMH (the {@code FullSearchHeadlessTest} →
 * {@code FullSearchBenchmark} pattern): the {@link FullSearchScenarios#fieldWorld() field-bench flat world}
 * must be fully RESIDENT under the largest (10³-region) box for BOTH goal kinds — an unresident region inside
 * the box would silently flip the reverse Dijkstra onto the optimistic-AIR unbuilt default and the bench would
 * measure a fake-air flood — and every measured (scenario × boxSize) combination must build a field whose flood
 * actually engaged (the bench's own dry-run probe, validated here as a plain test so a fixture regression fails
 * {@code ./gradlew test}, not just a benchmark run).
 */
class RegionFieldBuildHeadlessTest {

    private static final int[] BOX_SIZES = { 3, 5, 7, 10 };

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        RegionPathfinder.TRACE = false;
        Debug.ENABLED = false;
    }

    /** Every region of the largest (10³) box around EITHER goal region maps to a resident authored section —
     *  the residency precondition that keeps the measured flood on real terrain (no optimistic-AIR default). */
    @Test
    void largestBoxIsFullyResident() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        for (BlockPos goal : new BlockPos[] { w.surfaceGoalFloor, w.buriedGoalFloor }) {
            RegionPathfinder.RegionBox box = boxAround(w, goal, 10);
            for (int rx = box.minRx; rx <= box.maxRx; rx++) {
                for (int rz = box.minRz; rz <= box.maxRz; rz++) {
                    NavSection[] col = w.sections.get(NavStore.key(rx, rz));
                    assertNotNull(col, "chunk (" + rx + "," + rz + ") unresident inside the 10^3 box");
                    for (int ry = box.minRy; ry <= box.maxRy; ry++) {
                        assertTrue(ry >= 0 && ry < col.length && col[ry] != null,
                                "section ry=" + ry + " of chunk (" + rx + "," + rz + ") unresident inside the 10^3 box");
                    }
                }
            }
        }
    }

    /** Every measured (goal kind × box size) combination builds a field that REACHED the adjacent surface
     *  region — the flood engages over real fragments at every point of the sweep. */
    @Test
    void fieldReachesAdjacentSurfaceAcrossAllSizes() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        BotCaps caps = BotCaps.BREAK_PLACE;
        for (BlockPos goal : new BlockPos[] { w.surfaceGoalFloor, w.buriedGoalFloor }) {
            for (int n : BOX_SIZES) {
                RegionCostField field = RegionPathfinder.costToGoalField(w.grid, w.minY, goal,
                        caps.canBreak(), caps.canPlace(), caps.safeFallDistance(),
                        RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, boxAround(w, goal, n));
                assertNotNull(field, "null field for goal " + goal + " box " + n);
                // The surface region one region +x of the goal column — inside even the 3^3 box (region offset
                // (+1,0..+1,0) from the goal region) for both goal kinds. rawCost (not costAt): since the s53
                // frontier-floor costAt never returns UNREACHED, so only the raw settled slot proves the flood
                // actually engaged.
                float probe = field.rawCost(RegionAddress.regionX(goal.getX(), 0) + 1, 5,
                        RegionAddress.regionZ(goal.getZ(), 0), 0);
                assertTrue(probe < RegionCostField.UNREACHED,
                        "field flood never reached the adjacent surface region (goal " + goal + " box " + n + ")");
            }
        }
    }

    /** The buried goal engages the dig-flood seed path (≥1 dig-reachable pocket) — the §2-item-2 BFS the
     *  BURIED scenario exists to include in the measured op. */
    @Test
    void buriedGoalDigFloodEngages() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        int[] pockets = { 0 };
        w.grid.goalDigSeeds(w.buriedGoalFloor.getX(), w.buriedGoalFloor.getY(), w.buriedGoalFloor.getZ(), RegionGrid.MAX_GOAL_DIG_CELLS,
                (rx, ry, rz, frag, digCells) -> pockets[0]++);
        assertTrue(pockets[0] > 0, "buried goal should dig-flood to >=1 pocket");
    }

    /** The box the bench measures for edge {@code n}, anchored at {@code goal}'s region — the SAME math as
     *  {@link RegionFieldBuildBenchmark#boxFor}, exercised through it so the guard validates the bench's own
     *  box construction (odd n = symmetric pad; even n = pad + one-region diagonal offset). */
    private static RegionPathfinder.RegionBox boxAround(FullSearchScenarios.FieldWorld w, BlockPos goal, int n) {
        return RegionFieldBuildBenchmark.boxFor(
                RegionAddress.regionX(goal.getX(), 0),
                RegionAddress.regionY(goal.getY(), 0, w.minY),
                RegionAddress.regionZ(goal.getZ(), 0), n);
    }
}
