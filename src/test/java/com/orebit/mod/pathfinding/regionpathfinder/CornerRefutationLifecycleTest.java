package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;

/**
 * The §6-item-8 refutation lifecycle, HEADLESS (review 2026-08-29, dim5 F2 / dim3 F2): a durable diagonal
 * record driven through the REAL {@code HierarchicalRegionPlan.onBlocked} on a {@code RegionGrid.headless}
 * grid must (a) not NPE — the R30 corrective's persistence marks are level-guarded, since a headless grid
 * has no {@code ServerLevel} and {@code RegionPersistence}'s dirty maps reject a null key — and (b) evict
 * every L1+ crossing row touching the corner's endpoints while the L0 rows — INCLUDING the gating diagonal
 * refutation itself — survive (§4.10: the L0 row is what gates re-emission and what the merge's consult
 * reads; evicting it would re-open the refuted corner).
 *
 * <p>Substrate: the {@link CornerCrossingSearchTest} corner fixture (two diagonal TYPE_S cavern regions,
 * pure-air intermediates, walled in SOLID) with a recording-capable plan (real
 * {@link MovementContext.InventoryView} — the {@link HierarchicalCascadeTest} idiom) and the blame keys the
 * block tier's corner-run collapse would emit: the DIAGONAL {@code (A,fragA) → (D,fragD)} pair.
 */
class CornerRefutationLifecycleTest {

    private static final int G = RegionAddress.LEAF_SIZE;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;

    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static void seed(RegionGrid grid, int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += 8; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, null, G, passCount, standCount, 0,
                hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private static void seedSolid(RegionGrid grid, int rx, int ry, int rz) {
        seed(grid, rx, ry, rz, new boolean[CELLS], new boolean[CELLS]);
    }

    private static void seedAir(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        seed(grid, rx, ry, rz, passable, new boolean[CELLS]);
    }

    private static void seedCavernFloor(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(grid, rx, ry, rz, passable, standable);
    }

    private static RegionGrid cornerGrid() {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = -1; rx <= 2; rx++)
            for (int ry = 0; ry <= 2; ry++)
                for (int rz = -1; rz <= 2; rz++) seedSolid(grid, rx, ry, rz);
        seedCavernFloor(grid, 0, 1, 0);   // A
        seedCavernFloor(grid, 1, 1, 1);   // D
        seedAir(grid, 1, 1, 0);           // the chain's B
        seedAir(grid, 0, 1, 1);           // the other orthogonal intermediate
        return grid;
    }

    /** A recording-capable inventory (the HierarchicalCascadeTest idiom — recordToMemory needs one). */
    private static MovementContext.InventoryView inventory() {
        int[] tiers = new int[NavBlock.Tool.values().length];
        return new MovementContext.InventoryView(MiningModel.snapshot(tiers, 255, true), true, 0, 0f, 0f, 0f, 0);
    }

    @Test
    void headlessDurableCornerRefutation_noNpe_l0RowSurvives_l1RowsEvict() {
        RegionGrid grid = cornerGrid();
        BlockPos start = new BlockPos(8, 17, 8);   // feet over A's floor
        BlockPos goal = new BlockPos(24, 17, 24);  // feet over D's floor
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, start, goal,
                BotCaps.DEFAULT, RegionMineModel.DEFAULT, inventory());
        RegionPathPlan l0 = h.l0Skeleton();
        assertNotNull(l0, "the corner chain routes the fixture (skeleton-NONE is the pre-arc bug)");
        int corner = -1;
        for (int i = 0; i < l0.size(); i++) {
            if (RegionPathfinder.isCornerCut(l0.fragmentId(i))) corner = i;
        }
        assertTrue(corner > 0 && corner < l0.size() - 1, "the skeleton carries a mid-chain corner step");
        // The diagonal pair the block tier's corner-run collapse (PathPlan.collapseCornerRun) would blame.
        final long[] hop = new long[2];
        l0.collapsedHopKeys(corner - 1, hop);

        // A pre-existing L1 row touching A's containing coarse cell — the eviction's oracle.
        grid.crossingMemory().record(1,
                RegionPathfinder.fragmentNodeKey(0, 0, 0, 0), RegionPathfinder.fragmentNodeKey(1, 0, 0, 0),
                BotCaps.DEFAULT.realizabilitySig(), com.orebit.mod.worldmodel.hpa.RegionCrossingMemory.PROV_PROOF,
                BotCaps::sigDominates);
        assertEquals(1, grid.crossingMemory().count(1));

        // Durable record: searchStartFloor is D-side, so the FROM (= A) is NOT the search's start region
        // and startScoped does not withhold the row. Must not throw on the headless grid (the fix).
        h.onBlocked(hop[0], hop[1], start, goal);

        boolean diagonalRowPresent = false;
        for (int i = 0; i < grid.crossingMemory().count(0); i++) {
            if (grid.crossingMemory().fromAt(0, i) == hop[0] && grid.crossingMemory().toAt(0, i) == hop[1]) {
                diagonalRowPresent = true;
            }
        }
        assertTrue(diagonalRowPresent, "the gating L0 diagonal row SURVIVES the R30 corrective (§4.10)");
        assertEquals(0, grid.crossingMemory().count(1),
                "every L1+ row touching the endpoints is evicted (keys renumber under the re-merge)");
    }
}
