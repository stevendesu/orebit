package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;
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
import net.minecraft.world.level.chunk.Strategy;

/**
 * Guard for {@link GoalForcedCost#probe}'s <b>far-face exclusion</b>: the goal face whose stand cell lies on
 * the FAR side of the goal along the dominant start→goal axis must be excluded from BOTH the standable
 * short-circuit and the min-premium candidate set — otherwise a standable cell just past the goal zeroes the
 * whole premium and the ground flood the correction exists to kill returns.
 *
 * <p>The canonical failure is modeled directly: a goal floating in air ONE BLOCK UNDER A LEDGE (a stone
 * block at {@code goal + (0,1,0)}). Approached from below, the ledge is the far {@code (Y,−1)} face — its
 * standable stone previously short-circuited the premium to 0; with the exclusion the {@code (Y,+1)} build
 * face survives and the pillar premium is non-zero. Approached from ABOVE, the same ledge is a NEAR face,
 * the exclusion flips to {@code (Y,+1)}, and the standable short-circuit correctly still zeroes the premium
 * — proving the exclusion is start-relative, not a blanket removal of the admissibility rule. A third case
 * pins the plain open-air pillar goal (no ledge): its build premium is unchanged (no regression on the
 * original open-air-pillar fix). A fourth case pins the {@code (Y,+1)} build-face EXEMPTION from the
 * exclusion: with the goal predominantly BELOW the start, the build face is nominally the far face, but a
 * floating goal forces the pillar-up from either side (from above the bot falls past the unsupported goal),
 * so the premium must survive — excluding it would re-open the ground flood under the goal.
 *
 * <p>Probes run against the same synthetic air-over-stone view as {@link ForwardShrinkTest} (no live level);
 * the test lives in the {@code cuboid} package alongside the class under test.
 */
class GoalForcedFarFaceTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /** The goal cell — air, 20 up over the stone floor, well inside the single built chunk. */
    private static final int GX = 8, GY = 20, GZ = 8;
    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 60, 0, 15);

    @Test
    void ledgeGoalApproachedFromBelowKeepsItsBuildPremium() {
        // Ledge stone at (8,21,8): the (Y,-1) face's stand cell. Start on the floor straight below the goal
        // -> dominant axis Y, positive delta -> the far face (Y,-1) is excluded, so the standable ledge can
        // no longer zero the premium and the (Y,+1) build face must win.
        NavGridView grid = buildAirOverStone(true);
        GoalForcedCost.Forced forced = probe(grid, 8, 0, 8);

        assertTrue(forced.extent > 0,
                "the standable ledge above the goal is the FAR face from below — it must be excluded, "
                        + "leaving the build face's forced extent; got extent=" + forced.extent);
        assertEquals(Axes.AXIS_Y, forced.axis, "the surviving forced approach is the vertical build face");
        assertEquals(+1, forced.sign, "the build face approaches the goal travelling upward");
        // The build premium is the documented pillar step over the octile floor — pinned exactly so a cost
        // regression (dropping the place term, double-charging) is caught, not just "some positive number".
        assertEquals(Pillar.COST + MovementContext.PLACE_BASE_COST - Traverse.FLAT_COST,
                forced.perBlockPremium, 1e-4f,
                "per-block premium must be Pillar.COST + pillarPlaceCost - octile floor (headless fallback)");
    }

    @Test
    void sameLedgeGoalApproachedFromAboveIsStillShortCircuitedByTheStandableLedge() {
        // Same grid, start ABOVE the goal: dominant axis Y, negative delta -> now (Y,+1) is the far face and
        // the ledge's (Y,-1) face is a legitimate NEAR approach — its standable stone must still kill the
        // correction (the admissibility short-circuit survives; the exclusion is start-relative).
        NavGridView grid = buildAirOverStone(true);
        GoalForcedCost.Forced forced = probe(grid, 8, 35, 8);

        assertEquals(0, forced.extent,
                "from above, the standable ledge is a NEAR face — the cheap-approach short-circuit must "
                        + "still zero the premium; got extent=" + forced.extent);
    }

    @Test
    void openAirPillarGoalStillGetsItsBuildPremium() {
        // No ledge: the plain floating goal of the original open-air-pillar fix. The far-face exclusion
        // (which here skips the all-air (Y,-1) face — a face that contributed nothing anyway) must leave the
        // build premium untouched.
        NavGridView grid = buildAirOverStone(false);
        GoalForcedCost.Forced forced = probe(grid, 8, 0, 8);

        assertTrue(forced.extent > 0, "a goal floating in open air still forces a pillar approach");
        assertEquals(Axes.AXIS_Y, forced.axis);
        assertEquals(+1, forced.sign);
        assertEquals(Pillar.COST + MovementContext.PLACE_BASE_COST - Traverse.FLAT_COST,
                forced.perBlockPremium, 1e-4f, "the open-air build premium is unchanged (no regression)");
    }

    @Test
    void openAirGoalBelowTheStartKeepsItsBuildPremium() {
        // No ledge, start ABOVE the goal: dominant axis Y, negative delta -> (Y,+1) is nominally the far
        // face, but the build face is EXEMPT from the exclusion — a floating goal forces the pillar-up
        // from either side (a bot from above falls past the unsupported goal to the floor and must climb
        // back), so the premium must survive or the ground flood under the goal returns.
        NavGridView grid = buildAirOverStone(false);
        GoalForcedCost.Forced forced = probe(grid, 8, 35, 8);

        assertTrue(forced.extent > 0,
                "the (Y,+1) build face is exempt from the far-face exclusion — a floating goal below the "
                        + "start still forces a pillar; got extent=" + forced.extent);
        assertEquals(Axes.AXIS_Y, forced.axis);
        assertEquals(+1, forced.sign);
        assertEquals(Pillar.COST + MovementContext.PLACE_BASE_COST - Traverse.FLAT_COST,
                forced.perBlockPremium, 1e-4f,
                "the from-above build premium matches the from-below one (same forced pillar)");
    }

    /** Run the once-per-search probe exactly as {@code BlockPathfinder.findPath} wires it (headless: no
     *  inventory snapshot, so the pillar place cost is the static {@link MovementContext#PLACE_BASE_COST}). */
    private static GoalForcedCost.Forced probe(NavGridView grid, int sx, int sy, int sz) {
        NavGridCuboidsView cuboids = new NavGridCuboidsView(grid, new PathEdits(), CORRIDOR);
        GoalForcedCost.Forced forced = new GoalForcedCost.Forced();
        GoalForcedCost.probe(cuboids, sx, sy, sz, GX, GY, GZ,
                BotCaps.BREAK_PLACE, MovementContext.PLACE_BASE_COST, 0f, forced);
        return forced;
    }

    /**
     * Stone floor at y=0, air above (the {@link ForwardShrinkTest} fixture), optionally with one stone
     * "ledge" block directly above the goal at {@code (GX, GY+1, GZ)} (section 1, local y = 5).
     */
    private static NavGridView buildAirOverStone(boolean ledgeAboveGoal) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> floorStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floorStates.set(x, 0, z, stone);
            }
        }
        NavSection floor = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(floorStates, false, floor.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection second = airSection; // y 16..31
        if (ledgeAboveGoal) {
            PalettedContainer<BlockState> ledgeStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            ledgeStates.set(GX, (GY + 1) & 15, GZ, stone); // world y = GY+1 = 21 -> section-local y 5
            second = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(ledgeStates, false, second.getTraversalGrid());
        }

        NavSection[] column = { floor, second, airSection, airSection }; // y 0..63
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return NavGridView.overSections(0, chunks);
    }
}
