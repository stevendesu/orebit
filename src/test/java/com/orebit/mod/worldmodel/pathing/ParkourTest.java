package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the {@code Parkour} gap jump: a sealed stone section with a 1-wide corridor at
 * {@code z=8}, two platforms at floor {@code y=5} separated by a {@code g}-wide BOTTOMLESS chasm (air to
 * {@code y=0}; below the grid is unbuilt, so {@code Fall} finds no landing and the jump is the only
 * route for a no-place bot). Positives: {@link BotCaps#DEFAULT} crosses {@code g=1}, {@code g=2} and —
 * at the default envelope (flat cap 3, the owner-verified maximum) — {@code g=3}, each as exactly ONE
 * Parkour waypoint (the multi-cell edge must not be re-expanded); {@link BotCaps#BREAK_PLACE} still
 * picks Parkour over bridging for {@code g=2} (18.6 vs ≈32 ticks). Negatives: a ceiling block over the
 * gap at takeoff-arc height ({@code y+3}), a fence in the gap at node level (SHAPE_OTHER pokes into the
 * transit space), {@code g=3} with the cap lowered to 2 ({@code Parkour.PARKOUR_MAX_GAP} still honored),
 * and a flat {@code g=4} under ANY flags (no flat 4 row exists — the 4-range belongs to the falling
 * arcs) all yield no route. Not testable headless: jump kinematics, takeoff-edge tuning, sprint
 * attainment, missed-jump recovery (in-game pass).
 */
class ParkourTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);
    private static final BlockPos START = new BlockPos(2, 5, 8);

    @Test
    void jumpsAOneWideGapWithNoPlaceCap() {
        NavGridView grid = buildCourse(1, null, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(1), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a no-place bot should parkour the 1-wide gap");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "one jump should be exactly one Parkour waypoint");
    }

    @Test
    void jumpsATwoWideGapWithNoPlaceCap() {
        NavGridView grid = buildCourse(2, null, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(2), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a no-place bot should parkour the 2-wide gap");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "one jump should be exactly one Parkour waypoint");
    }

    @Test
    void placingBotStillPrefersTheJumpOverBridging() {
        NavGridView grid = buildCourse(2, null, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(2), BotCaps.BREAK_PLACE, CORRIDOR);

        assertNotNull(plan, "a placing bot should certainly cross the 2-wide gap");
        assertTrue(count(plan, MovementRegistry.PARKOUR) >= 1,
                "the 18.6-tick jump should beat the ~32-tick 2-place bridge");
    }

    @Test
    void ceilingOverTheGapKillsTheJump() {
        // A block over the first gap column at source-head-jump height (y+3 = 8) — the arc clips it.
        NavGridView grid = buildCourse(1, null, new BlockPos(5, 8, 8));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(1), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a blocked transit prism must not be jumped through (and no other route exists)");
    }

    @Test
    void fenceInTheGapKillsTheJump() {
        // A fence at node level in the gap: SHAPE_OTHER collision pokes up into the transit space.
        NavGridView grid = buildCourse(1, new BlockPos(5, 5, 8), null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(1), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a fence in the gap must not be overflown (and no other route exists)");
    }

    @Test
    void threeWideGapIsOpenAtTheDefaultCapAndTheCapIsStillHonored() {
        // Envelope flip: the flat row's default cap is 3, the owner-verified maximum (was 2).
        NavGridView grid = buildCourse(3, null, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(3), BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "g=3 should be offered at the new default flat cap of 3");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "the g=3 jump should still be a single Parkour waypoint");

        // The static knob keeps its exact v1 semantics: lowering it re-closes the 3-gap.
        int saved = Parkour.PARKOUR_MAX_GAP;
        Parkour.PARKOUR_MAX_GAP = 2;
        try {
            assertNull(BlockPathfinder.findPath(grid, START, goal(3), BotCaps.DEFAULT, CORRIDOR),
                    "lowering the cap to 2 must gate the g=3 jump again");
        } finally {
            Parkour.PARKOUR_MAX_GAP = saved;
        }
    }

    @Test
    void flatFourWideGapIsNeverOffered() {
        // There is NO flat 4 row in the envelope — the ~3.4-block flat sprint-jump reach doesn't cover
        // it (the 4-range belongs to the falling arcs, which need a lower landing; this course's landing
        // is at takeoff level over a bottomless chasm). (s52: one unconditional envelope, no flag.)
        NavGridView grid = buildCourse(4, null, null);
        assertNull(BlockPathfinder.findPath(grid, START, goal(4), BotCaps.DEFAULT, CORRIDOR),
                "a flat 4-gap must never be offered");
    }

    /** Goal floor: one cell onto the landing platform (landing column is {@code 5+g}). */
    private static BlockPos goal(int g) {
        return new BlockPos(5 + g + 1, 5, 8);
    }

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    /**
     * One sealed stone section: a corridor carved at {@code z=8} with platform floors at {@code y=5} and
     * open body/jump space {@code y=6..9} along {@code x=1..14}; gap columns {@code x=5..4+g} are air from
     * {@code y=0} up (bottomless — below the grid is unbuilt, so {@code Fall} never lands). {@code fence}
     * (nullable) puts an oak fence at that cell; {@code ceiling} (nullable) puts stone at that cell.
     */
    private static NavGridView buildCourse(int g, BlockPos fence, BlockPos ceiling) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }

        final int z = 8;
        for (int x = 1; x <= 14; x++) {         // the corridor: body + jump headroom over the platforms
            for (int y = 6; y <= 9; y++) {
                s.set(x, y, z, air);
            }
        }
        for (int x = 5; x <= 4 + g; x++) {      // the chasm: air to the section floor (bottomless)
            for (int y = 0; y <= 5; y++) {
                s.set(x, y, z, air);
            }
        }
        if (fence != null) s.set(fence.getX(), fence.getY(), fence.getZ(), Blocks.OAK_FENCE.defaultBlockState());
        if (ceiling != null) s.set(ceiling.getX(), ceiling.getY(), ceiling.getZ(), stone);

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
