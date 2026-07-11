package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * Headless proof of {@code DiagonalParkour} (the diagonal gap jump): one sealed stone section with a
 * 1-wide DIAGONAL corridor along {@code z == x} — travel cells {@code (t, t), t = 2..12} with floors at
 * {@code y=5} and body air {@code y=6..9}, plus the corner columns {@code (t+1, t)} / {@code (t, t+1)}
 * every diagonal walk and jump sweeps. Corner columns are carved BOTTOMLESS (air {@code y=0..9}) so they
 * are transit-clear but never standable — that seals every cardinal zig-zag detour (a cardinal
 * {@code Parkour} landing needs a standable column; walking needs a floor) and leaves the diagonal jump
 * as the only route across the chasm. The one exception is corner {@code (6,5)}, which keeps its solid
 * stone floor: the jump's first transition must arc OVER a full solid block there (the {@code topY ≤ 16}
 * corner rule), so every positive below also proves the solid-corner acceptance. The chasm is {@code g}
 * bottomless diagonal cells {@code (6,6)..(5+g,5+g)} (below the grid is unbuilt, so {@code Fall} never
 * lands); takeoff {@code (5,5)}, landing {@code (6+g,6+g)}.
 *
 * <p>Positives ({@link BotCaps#DEFAULT}, no break/place): the 1-gap ({@code √2·2 ≈ 2.83}-block
 * displacement) and the owner-verified 2-gap each cross as exactly ONE DiagonalParkour waypoint at the
 * STAND position ({@code floor.above()}). Negatives: an oak fence on the floored corner ({@code topY ≈
 * 24} pokes into the arc's feet path) clips the jump — the very cell a plain solid floor passes; the
 * 3-gap ({@code 4.24}-block air span) is EXCLUDED — the derived {@code ParkourEnvelope} caps the diagonal
 * at 2 from a full-block takeoff (the old hardcoded MAX_GAP=3 offered an unmakeable jump). Not
 * testable headless: the √2 takeoff-trigger projection, corner-support kinematics (in-game pass).
 */
class DiagonalParkourTest {

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
    private static final BlockPos START = new BlockPos(2, 5, 2);

    /** Goal floor: two diagonal walk steps past the landing cell {@code (6+g, 6+g)}. */
    private static BlockPos goal(int g) {
        return new BlockPos(8 + g, 5, 8 + g);
    }

    @Test
    void jumpsAOneCellDiagonalGapOverTheChasm() {
        NavGridView grid = buildCourse(1, false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(1), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a no-place bot should diagonal-jump the 1-cell gap (arcing over the solid corner)");
        assertEquals(1, count(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "one diagonal jump should be exactly one DiagonalParkour waypoint");
        // Waypoints are STAND positions: landing floor (7,5,7) -> feet at (7,6,7).
        assertEquals(new BlockPos(7, 6, 7), waypointOf(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the jump's waypoint should be the stand position above the landing floor");
    }

    @Test
    void jumpsATwoCellDiagonalGap() {
        NavGridView grid = buildCourse(2, false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(2), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the owner-verified 2-cell diagonal gap should be jumpable by default");
        assertEquals(1, count(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "one diagonal jump should be exactly one DiagonalParkour waypoint");
        assertEquals(new BlockPos(8, 6, 8), waypointOf(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the jump's waypoint should be the stand position above the landing floor");
    }

    @Test
    void fenceAtACornerColumnClipsTheArc() {
        // The floored corner (6,5) passes as a plain solid block (topY = 16, arced over); a fence there
        // (topY ≈ 24) pokes into the arc's feet path and must kill the jump — and with it the only route.
        NavGridView grid = buildCourse(1, true);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(1), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a fence on a swept corner column must clip the diagonal jump");
    }

    @Test
    void threeCellDiagonalGapIsNotOffered() {
        // The 3-gap (4.24-block air span, √20 ≈ 4.47 displacement) is beyond the flat sprint-jump reach:
        // the DERIVED envelope caps the diagonal at 2 from a full-block takeoff (ParkourEnvelope BASE row:
        // diag 2). The old hardcoded MAX_GAP=3 OVER-offered it (the real-play 90°-corner-cut the bot fell
        // on); with it excluded and no other route across the diagonal chasm, the search finds none.
        NavGridView grid = buildCourse(3, false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal(3), BotCaps.DEFAULT, CORRIDOR);
        assertNull(plan, "the 3-cell diagonal gap must NOT be offered (derived envelope caps diagonal at 2)");
    }

    // ---------------------------------------------------------------- helpers

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    /** The stand-position waypoint of the first step using {@code move} (null if none). */
    private static BlockPos waypointOf(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return plan.waypoint(i);
        }
        return null;
    }

    /**
     * The sealed diagonal course (class Javadoc): travel cells {@code (t,t), t=2..12} floored at y=5 with
     * body air y=6..9; corner columns {@code (t+1,t)}/{@code (t,t+1), t=2..11} bottomless (air y=0..9)
     * EXCEPT {@code (6,5)}, which keeps its stone floor (body-only carve) to exercise the solid-corner
     * arc-over; gap cells {@code (6,6)..(5+g,5+g)} bottomless. {@code fenceCorner} swaps the floored
     * corner's surface block {@code (6,5,5)} for an oak fence.
     */
    private static NavGridView buildCourse(int g, boolean fenceCorner) {
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

        for (int t = 2; t <= 12; t++) {             // travel cells: floor y=5, body air
            for (int y = 6; y <= 9; y++) {
                s.set(t, y, t, air);
            }
        }
        for (int t = 2; t <= 11; t++) {             // swept corner columns
            carveCorner(s, air, t + 1, t);
            carveCorner(s, air, t, t + 1);
        }
        for (int t = 6; t <= 5 + g; t++) {          // the diagonal chasm: bottomless
            for (int y = 0; y <= 9; y++) {
                s.set(t, y, t, air);
            }
        }
        if (fenceCorner) {
            s.set(6, 5, 5, Blocks.OAK_FENCE.defaultBlockState());
        }

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

    /**
     * One corner column: bottomless air (transit-clear, never standable — seals cardinal zig-zag
     * detours), except {@code (6,5)} which keeps its floor (the solid block the arc must clear).
     */
    private static void carveCorner(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        int yFrom = (x == 6 && z == 5) ? 6 : 0;
        for (int y = yFrom; y <= 9; y++) {
            s.set(x, y, z, air);
        }
    }
}
