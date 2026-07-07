package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Guards the {@link RegionGrid#headless(int, java.util.concurrent.ConcurrentHashMap) NavSection-backed headless
 * region grid} enabler — the seam the {@link FullSearchBenchmark} relies on to engage the live region-informed
 * path (goal dig-flood + start-fragment flood) with no {@code ServerLevel}. The record-only headless grid
 * ({@link RegionGrid#headless(int)}) can't: its three section resolvers early-out, so the dig-flood reports
 * nothing. This test proves the section routing makes the dig-flood engage, and that the whole three-stage
 * pipeline runs end-to-end over {@link FullSearchScenarios}.
 */
class FullSearchHeadlessTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    /** The dig-flood must report at least one dig-reachable pocket for a buried goal over a sections-backed grid. */
    @Test
    void digFloodEngagesOnSectionsBackedGrid() {
        FullSearchScenarios.Fixture f = FullSearchScenarios.build(FullSearchScenarios.Scenario.GOAL_IN_WINDOW);
        int[] pockets = { 0 };
        f.grid.goalDigSeeds(f.goalFloor.getX(), f.goalFloor.getY(), f.goalFloor.getZ(), 12,
                (rx, ry, rz, frag, digCells) -> pockets[0]++);
        assertTrue(pockets[0] > 0,
                "buried goal should dig-flood to >=1 pocket over the NavSection-backed headless grid");
    }

    /**
     * Negative control: the SAME buried-goal query over a record-only headless grid (no sections map) reports
     * nothing — so the pockets above come from the new section routing, not from anything pre-seeded. This is
     * exactly why the record-only {@code RegionScenarios} bench can't see a live-path regression.
     */
    @Test
    void digFloodIsInertOnRecordOnlyGrid() {
        RegionGrid recordOnly = RegionGrid.headless(FullSearchScenarios.MINY);
        int[] pockets = { 0 };
        recordOnly.goalDigSeeds(36, 3, 8, 12, (rx, ry, rz, frag, digCells) -> pockets[0]++);
        assertEquals(0, pockets[0], "record-only headless grid has no sections → the dig-flood must early-out");
    }

    /** The whole three-stage pipeline (field + skeleton + region-informed window block A*) runs and returns a plan. */
    @Test
    void fullPipelineRunsHeadless() {
        for (FullSearchScenarios.Scenario s : FullSearchScenarios.Scenario.values()) {
            FullSearchScenarios.Fixture f = FullSearchScenarios.build(s);
            BlockPathPlan plan = f.search();
            assertNotNull(plan, s + " should produce a block plan end-to-end headless");
        }
    }
}
