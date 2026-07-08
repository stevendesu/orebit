package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.pathing.FullSearchScenarios;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Pins the <b>exact</b> {@link RegionGrid#goalDigSeeds} report sequence — every
 * {@code (rx,ry,rz,frag,digCells)} tuple, in sink-call order — over the two headless fixtures
 * ({@link FullSearchScenarios#fieldWorld()} and the GOAL_IN_WINDOW hill), for all three goal kinds
 * (buried, solid-surface-floor, exposed). This is the output-identity gate for any mechanical rework
 * of the dig-flood (the s53 label-slab + de-boxed-BFS rewrite shipped against the cap-12 capture of
 * these pins, unchanged): the flood must reproduce the same pockets, the same kept fragment ids, the
 * same dig counts, and the same order — byte for byte. A change here is a BEHAVIOR change (the region
 * field's multi-source seeds move) and must be treated as one.
 *
 * <p>The dig budget is pinned at the production cap ({@link RegionGrid#MAX_GOAL_DIG_CELLS}); a cap
 * change legitimately shrinks the diamond and the expectations must be re-captured in the same commit
 * that changes the cap. Current capture: <b>cap 9</b> (the owner-ratified s53 12 → 9 shrink — the
 * buried field goal drops from 3 pockets to 1, the surface goal from 5 to 3, the hill goal from 5 to
 * 4; the lost pockets were the marginal r=10..12 edge touches).
 */
class DigSeedPinTest {

    private static final int MAX_CELLS = RegionGrid.MAX_GOAL_DIG_CELLS;

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /** All goalDigSeeds reports for {@code goal}, one {@code rx,ry,rz,frag,digCells} line per sink call. */
    private static String collect(RegionGrid grid, BlockPos goal) {
        StringJoiner sj = new StringJoiner("\n");
        grid.goalDigSeeds(goal.getX(), goal.getY(), goal.getZ(), MAX_CELLS,
                (rx, ry, rz, frag, digCells) ->
                        sj.add(rx + "," + ry + "," + rz + "," + frag + "," + digCells));
        return sj.toString();
    }

    /** BURIED field goal (136,77,136): 3 blocks inside the underground — the full dig-BFS shape. */
    @Test
    void fieldWorldBuriedGoal() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        assertEquals(FIELD_BURIED, collect(w.grid, w.buriedGoalFloor));
    }

    /** SURFACE field goal (136,80,136): the solid floor-plane cell — also a dig-BFS (feet pocket at d=1). */
    @Test
    void fieldWorldSurfaceGoal() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        assertEquals(FIELD_SURFACE, collect(w.grid, w.surfaceGoalFloor));
    }

    /** EXPOSED goal (136,81,136): a passable feet cell — the single zero-dig seed branch. */
    @Test
    void fieldWorldExposedGoal() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        assertEquals(FIELD_EXPOSED, collect(w.grid, w.surfaceGoalFloor.above()));
    }

    /** GOAL_IN_WINDOW hill goal (36,3,8): buried mid-hill near region seams — multi-region pocket reports. */
    @Test
    void hillBuriedGoal() {
        FullSearchScenarios.Fixture f = FullSearchScenarios.build(FullSearchScenarios.Scenario.GOAL_IN_WINDOW);
        assertEquals(HILL_BURIED, collect(f.grid, f.goalFloor));
    }

    // ================================================================================================
    // Captured expectations (cap 9) — do not hand-edit; re-capture on a ratified behavior change.
    // ================================================================================================

    private static final String FIELD_BURIED = """
            8,5,8,0,4
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            """.stripTrailing();

    private static final String FIELD_SURFACE = """
            8,5,8,0,1
            8,5,8,0,2
            8,5,8,0,2
            8,5,8,0,2
            8,5,8,0,2
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,3
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,4
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,5
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,6
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,7
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            8,5,8,0,8
            9,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,8,0,9
            8,5,9,0,9
            8,5,8,0,9
            """.stripTrailing();

    private static final String FIELD_EXPOSED = """
            8,5,8,0,0
            """.stripTrailing();

    private static final String HILL_BURIED = """
            2,0,0,0,4
            2,0,0,0,5
            1,0,0,0,5
            2,0,0,0,5
            2,0,0,0,5
            2,0,0,0,5
            2,0,0,0,6
            2,0,0,0,6
            2,0,0,0,6
            1,0,0,0,6
            1,0,0,0,6
            1,0,0,0,6
            1,0,0,0,6
            2,0,0,0,6
            2,0,0,0,6
            2,0,0,0,6
            2,0,0,0,6
            2,0,0,0,6
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            1,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,7
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            1,0,0,0,8
            2,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            1,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,0,0,8
            2,0,1,0,8
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,1,0,9
            1,0,0,0,9
            2,0,0,0,9
            1,0,0,0,9
            2,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            1,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,1,0,9
            2,0,0,0,9
            2,0,0,0,9
            2,0,1,0,9
            2,0,1,0,9
            2,0,-1,0,9
            """.stripTrailing();
}
