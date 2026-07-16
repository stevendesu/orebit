package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalSprintSwim;
import com.orebit.mod.pathfinding.blockpathfinder.movements.SprintSwim;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of {@code DiagonalSprintSwim} — the same-Y horizontal diagonal (NE/NW/SE/SW) for prone
 * sprint-swim. Two levels:
 *
 * <ol>
 *   <li><b>Candidate-level</b> (the precise arm): construct a {@link MovementContext} over a synthetic water
 *       plane, pin the node's mode with {@link MovementContext#setMode}, and call
 *       {@code DiagonalSprintSwim.candidates(...)} directly with a capturing {@link CandidateSink}. This
 *       asserts the EXACT emitted set and the exact {@code √2·}{@link SprintSwim#COST} cost, and — the
 *       load-bearing rule — that a diagonal whose orthogonal CORNER feet cell is non-water (solid OR air) is
 *       rejected by Minecraft's per-axis collision (never a swept diagonal). Also proves the self-gate on
 *       {@link MovementContext#MODE_PRONE} (nothing for a standing node).</li>
 *   <li><b>findPath-level</b> (integration): a sealed water channel where a diagonal step is geometrically
 *       offered; with both corners water the search PREFERS the diagonal ({@code √2·COST < 2·COST}), and with
 *       one corner made solid the identical geometry routes the long way around with two cardinal
 *       {@code SprintSwim}s and NO {@code DiagonalSprintSwim} (no corner-cut) — the same A/B the candidate
 *       arm proves, end to end through the real search.</li>
 * </ol>
 *
 * <p>Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor (the
 * {@link StatefulSwimTest}/{@link DiagonalParkourTest} precedent). Water cells are placed at the FEET layer
 * ({@code y+1}) — the only layer this prone (0.6-tall) move reads; there is no head-clearance requirement.
 */
class DiagonalSprintSwimTest {

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

    // ==================================================================== candidate-level (precise)

    // Node under test: (8,4,8) → feet layer y=5. The 5x5 water plane spans x,z in [6..10] at y=5, so all four
    // diagonal destinations {(7..9, 4, 7..9)} and their corner feet cells are water unless a test tweaks one.
    private static final int NX = 8, NY = 4, NZ = 8;   // the PRONE node the move expands
    private static final int FY = NY + 1;              // feet layer (=5)

    @Test
    void emitsFourSameYDiagonalsAtRootTwoCostWhenAllCornersWater() {
        MovementContext ctx = proneCtx(null);
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        assertEquals(4, cap.cells.size(), "an open water plane should offer all four same-Y diagonals");
        for (int[] d : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int dx = NX + d[0], dz = NZ + d[1];
            assertTrue(cap.has(dx, NY, dz), "diagonal to (" + dx + "," + NY + "," + dz + ") should be emitted");
            assertEquals(DiagonalSprintSwim.COST, cap.costOf(dx, NY, dz), 1e-4f,
                    "diagonal cost should be SprintSwim.COST * sqrt(2)");
        }
        // The √2 derivation itself (rule 1's cost), independent of any emitted candidate.
        assertEquals((float) (SprintSwim.COST * Math.sqrt(2)), DiagonalSprintSwim.COST, 1e-3f,
                "DiagonalSprintSwim.COST must be SprintSwim.COST * sqrt(2)");
    }

    @Test
    void rejectsBothDiagonalsSharingASolidCorner() {
        // Make the shared +x corner (9,5,8) SOLID. It is the corner feet cell of the NE (+1,+1) and
        // SE (+1,-1) diagonals, so per-axis collision clips BOTH; the two -x diagonals survive.
        MovementContext ctx = proneCtx(s -> s.set(9, FY, 8, Blocks.STONE.defaultBlockState()));
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        assertFalse(cap.has(9, NY, 9), "NE diagonal must be rejected — its corner (9,5,8) is solid");
        assertFalse(cap.has(9, NY, 7), "SE diagonal must be rejected — its corner (9,5,8) is solid");
        assertTrue(cap.has(7, NY, 9), "NW diagonal (corners all water) should still be emitted");
        assertTrue(cap.has(7, NY, 7), "SW diagonal (corners all water) should still be emitted");
        assertEquals(2, cap.cells.size(), "exactly the two corner-clear diagonals should remain");
    }

    @Test
    void rejectsDiagonalsSharingAnAirCorner() {
        // Same as above but the corner is AIR, not solid — rule 2 is "non-water", covering solid OR air
        // (an air corner would breach the prone swim; the v1 corner-water rule requires water throughout).
        MovementContext ctx = proneCtx(s -> s.set(9, FY, 8, Blocks.AIR.defaultBlockState()));
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        assertFalse(cap.has(9, NY, 9), "NE diagonal must be rejected — its corner (9,5,8) is air, not water");
        assertFalse(cap.has(9, NY, 7), "SE diagonal must be rejected — its corner (9,5,8) is air, not water");
        assertEquals(2, cap.cells.size(), "an air corner clips exactly as a solid one does");
    }

    @Test
    void rejectsOnlyTheDiagonalWhoseDestinationIsNonWater() {
        // Block the NE DESTINATION feet cell (9,5,9), leaving every corner water: only NE is rejected — this
        // isolates the destination-water gate from the corner gate (both are required, independently).
        MovementContext ctx = proneCtx(s -> s.set(9, FY, 9, Blocks.STONE.defaultBlockState()));
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        assertFalse(cap.has(9, NY, 9), "NE diagonal must be rejected — its destination (9,5,9) is not water");
        assertTrue(cap.has(9, NY, 7), "SE diagonal (dest + corners water) should be emitted");
        assertTrue(cap.has(7, NY, 9), "NW diagonal should be emitted");
        assertTrue(cap.has(7, NY, 7), "SW diagonal should be emitted");
        assertEquals(3, cap.cells.size(), "only the one non-water-destination diagonal drops out");
    }

    @Test
    void emitsNothingForAStandingNode() {
        // Rule 3: self-gate on MODE_PRONE. A standing/dry node (or any non-prone pose) yields no swim diagonal.
        MovementContext ctx = proneCtx(null);
        Capture cap = expand(ctx, MovementContext.MODE_STANDING);

        assertEquals(0, cap.cells.size(), "DiagonalSprintSwim must emit nothing from a STANDING node");
    }

    // ==================================================================== pass-2: vertical-diagonal + corner

    private static final int UY = FY + 1;   // feet layer one above the source feet (=6)
    private static final int DY = FY - 1;   // feet layer one below the source feet (=4)

    @Test
    void emitsAllEightVerticalDiagonalEdgesAtRootTwoCostInAnOpenVolume() {
        // Each of the 4 cardinals combined with +Y or -Y — the 8 vertical-diagonal EDGES. In an open 3-D water
        // volume all swept feet cells are water, so every one is emitted at the √2 edge cost.
        MovementContext ctx = proneVolumeCtx(null);
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        for (int[] d : new int[][] {
                { 1, 1, 0 }, { 1, -1, 0 }, { -1, 1, 0 }, { -1, -1, 0 },   // ±X + ±Y
                { 0, 1, 1 }, { 0, -1, 1 }, { 0, 1, -1 }, { 0, -1, -1 } }) { // ±Z + ±Y
            int dx = NX + d[0], dy = NY + d[1], dz = NZ + d[2];
            assertTrue(cap.has(dx, dy, dz),
                    "vertical-diagonal to (" + dx + "," + dy + "," + dz + ") should be emitted");
            assertEquals(DiagonalSprintSwim.COST, cap.costOf(dx, dy, dz), 1e-4f,
                    "vertical-diagonal edge cost should be SprintSwim.COST * sqrt(2)");
        }
        assertEquals((float) (SprintSwim.COST * Math.sqrt(2)), DiagonalSprintSwim.COST, 1e-3f,
                "edge COST must be SprintSwim.COST * sqrt(2)");
    }

    @Test
    void emitsAllEightCornersAtRootThreeCostInAnOpenVolume() {
        // Each of the 4 horizontal diagonals combined with +Y or -Y — the 8 CORNERS (3-axis). All swept feet
        // cells (3 single-axis + 3 pair-axis + destination) are water in the open volume, so each is emitted at
        // the √3 corner cost.
        MovementContext ctx = proneVolumeCtx(null);
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        for (int sx : new int[] { -1, 1 }) {
            for (int sy : new int[] { -1, 1 }) {
                for (int sz : new int[] { -1, 1 }) {
                    int dx = NX + sx, dy = NY + sy, dz = NZ + sz;
                    assertTrue(cap.has(dx, dy, dz),
                            "corner to (" + dx + "," + dy + "," + dz + ") should be emitted");
                    assertEquals(DiagonalSprintSwim.CORNER_COST, cap.costOf(dx, dy, dz), 1e-4f,
                            "corner cost should be SprintSwim.COST * sqrt(3)");
                }
            }
        }
        assertEquals((float) (SprintSwim.COST * Math.sqrt(3)), DiagonalSprintSwim.CORNER_COST, 1e-3f,
                "CORNER_COST must be SprintSwim.COST * sqrt(3)");
    }

    @Test
    void emitsAllTwentyMultiAxisMovesInAnOpenVolume() {
        // The full 26-connectivity minus the 6 faces (SprintSwim's job): 4 horizontal diagonals + 8
        // vertical-diagonals + 8 corners = 20, all offered when the whole neighbourhood is water.
        MovementContext ctx = proneVolumeCtx(null);
        Capture cap = expand(ctx, MovementContext.MODE_PRONE);

        assertEquals(20, cap.cells.size(),
                "an open 3-D water volume should offer all 20 multi-axis swim steps (4 horiz + 8 vert + 8 corner)");
    }

    @Test
    void verticalDiagonalRejectedWhenAnySweptCellIsNonWater() {
        // +X+Y edge (offset 1,1,0), destination node (9,5,8). Its distinct swept feet cells are the X-only
        // intermediate (9,5,8), the Y-only intermediate (8,6,8), and the destination feet (9,6,8). Blocking ANY
        // one — solid OR air (both are "non-water") — must clip the prone hitbox and drop the move. Every one of
        // the three clearance reads is exercised.
        int destX = NX + 1, destY = NY + 1, destZ = NZ;   // (9,5,8)
        int[][] swept = { { 9, FY, 8 }, { 8, UY, 8 }, { 9, UY, 8 } }; // X-inter, Y-inter, dest-feet
        for (int[] cell : swept) {
            for (BlockState blocker : new BlockState[] {
                    Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState() }) {
                MovementContext ctx = proneVolumeCtx(s -> s.set(cell[0], cell[1], cell[2], blocker));
                Capture cap = expand(ctx, MovementContext.MODE_PRONE);
                assertFalse(cap.has(destX, destY, destZ),
                        "+X+Y vertical-diagonal must be rejected when swept cell (" + cell[0] + "," + cell[1]
                                + "," + cell[2] + ") is " + blocker);
            }
        }
    }

    @Test
    void cornerRejectedWhenAnySweptCellIsBlocked() {
        // +X+Y+Z corner (offset 1,1,1), destination node (9,5,9). The 7 distinct swept feet cells: 3 single-axis
        // {X}(9,5,8) {Y}(8,6,8) {Z}(8,5,9), 3 pair-axis {XY}(9,6,8) {XZ}(9,5,9) {YZ}(8,6,9), and the destination
        // feet {XYZ}(9,6,9). Blocking ANY ONE must drop the corner — the load-bearing per-axis-clearance proof,
        // every check exercised.
        int destX = NX + 1, destY = NY + 1, destZ = NZ + 1;  // (9,5,9)
        int[][] swept = {
                { 9, FY, 8 },   // {X}   single
                { 8, UY, 8 },   // {Y}   single
                { 8, FY, 9 },   // {Z}   single
                { 9, UY, 8 },   // {XY}  pair
                { 9, FY, 9 },   // {XZ}  pair
                { 8, UY, 9 },   // {YZ}  pair
                { 9, UY, 9 },   // {XYZ} destination feet
        };
        for (int[] cell : swept) {
            MovementContext ctx = proneVolumeCtx(s -> s.set(cell[0], cell[1], cell[2], Blocks.STONE.defaultBlockState()));
            Capture cap = expand(ctx, MovementContext.MODE_PRONE);
            assertFalse(cap.has(destX, destY, destZ),
                    "+X+Y+Z corner must be rejected when swept cell (" + cell[0] + "," + cell[1] + "," + cell[2]
                            + ") is solid");
        }
    }

    @Test
    void emitsNothingForAStandingNodeInAVolume() {
        // The MODE_PRONE self-gate holds for the pass-2 offsets too: a STANDING node in an open water volume
        // still yields no swim step (vertical-diagonal or corner included).
        MovementContext ctx = proneVolumeCtx(null);
        Capture cap = expand(ctx, MovementContext.MODE_STANDING);

        assertEquals(0, cap.cells.size(), "no swim step (incl. vertical/corner) from a STANDING node");
    }

    // ==================================================================== findPath-level (integration)

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);
    // Start submerged (2-deep) at (2,0,2) → the search seeds MODE_PRONE from geometry; goal two cells away
    // on Z so the start is outside the ±1 arrival tolerance and real movement is forced.
    private static final BlockPos CHANNEL_START = new BlockPos(2, 0, 2);
    private static final BlockPos CHANNEL_GOAL = new BlockPos(3, 0, 4);

    @Test
    void prefersTheDiagonalWhenBothCornersAreWater() {
        NavGridView grid = buildChannel(true);   // corner (3,1,2) is water
        BlockPathPlan plan = BlockPathfinder.findPath(grid, CHANNEL_START, CHANNEL_GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a prone bot should reach the goal through the water channel");
        assertTrue(contains(plan, MovementRegistry.DIAGONAL_SPRINT_SWIM),
                "with both corners water the √2 diagonal is cheaper than the 2-step cardinal detour and should be used");
    }

    @Test
    void routesAroundASolidCornerWithoutCuttingIt() {
        NavGridView grid = buildChannel(false);  // corner (3,1,2) is solid stone
        BlockPathPlan plan = BlockPathfinder.findPath(grid, CHANNEL_START, CHANNEL_GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the bot should still reach the goal via the cardinal go-around");
        assertFalse(contains(plan, MovementRegistry.DIAGONAL_SPRINT_SWIM),
                "a solid corner must clip the diagonal — the bot routes around, never cutting the corner");
        assertTrue(contains(plan, MovementRegistry.SPRINT_SWIM),
                "the go-around is made of cardinal sprint-swim steps");
    }

    @Test
    void beelinesAnOpenPoolDiagonally() {
        // A 7x7 open water pool: the 8-connected diagonal beeline covers the corner-to-corner run with far
        // fewer, cheaper steps than a 4-connected Manhattan zig-zag would.
        NavGridView grid = buildOpenPool();
        BlockPos start = new BlockPos(2, 0, 2);
        BlockPos goal = new BlockPos(7, 0, 7);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the bot should swim across the open pool");
        assertTrue(count(plan, MovementRegistry.DIAGONAL_SPRINT_SWIM) >= 3,
                "a corner-to-corner pool crossing should be dominated by diagonal swim steps, was "
                        + count(plan, MovementRegistry.DIAGONAL_SPRINT_SWIM));
    }

    @Test
    void beelinesA3DWaterVolumeThroughDiagonalSwim() {
        // A solid 3-D water CUBE: the cheapest route from a bottom corner to the opposite top corner runs
        // through vertical-diagonal / corner swim steps (a corner covers +1 in all three axes for √3·COST ≈ 6.17,
        // beating a separate up (3.56) + horizontal diagonal (5.04) = 8.60 for the same displacement). So the
        // search MUST use DiagonalSprintSwim to climb-and-cross in one, proving the pass-2 geometry end to end.
        NavGridView grid = buildWaterCube();
        BlockPos start = new BlockPos(2, 0, 2);   // 2-deep at the cube floor → PRONE seed
        BlockPos goal = new BlockPos(6, 6, 6);    // opposite top corner (up 6, across +4,+4)
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a prone bot should swim through the 3-D water cube to the raised opposite corner");
        assertTrue(contains(plan, MovementRegistry.DIAGONAL_SPRINT_SWIM),
                "a diagonal-through-3D water crossing should use DiagonalSprintSwim (climbing corners/edges), not "
                        + "a face-only Manhattan staircase");
    }

    /**
     * A sealed stone section with a solid water CUBE: water at x in [2..7], y in [1..8], z in [2..7]. The floor
     * corner (2,·,2) is 2-deep (feet y=1 + head y=2) so the search seeds {@link MovementContext#MODE_PRONE}; the
     * whole cube interior is swimmable, so an 8/26-connected search can climb-and-cross diagonally from
     * (2,0,2) toward the raised (6,6,6).
     */
    private static NavGridView buildWaterCube() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        for (int x = 2; x <= 7; x++) {
            for (int y = 1; y <= 8; y++) {
                for (int z = 2; z <= 7; z++) {
                    s.set(x, y, z, water);
                }
            }
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

    // ---------------------------------------------------------------- candidate-level helpers

    /** A MovementContext over a fresh water plane (see class doc), optionally tweaked before classification. */
    private static MovementContext proneCtx(Consumer<PalettedContainer<BlockState>> tweak) {
        return new MovementContext(buildWaterPlane(tweak), BotCaps.DEFAULT);
    }

    /** Set the node's mode and expand {@code DiagonalSprintSwim} at (NX,NY,NZ), capturing its candidates. */
    private static Capture expand(MovementContext ctx, int mode) {
        ctx.setMode(mode);
        Capture cap = new Capture();
        MovementRegistry.DIAGONAL_SPRINT_SWIM.candidates(ctx, NX, NY, NZ, cap);
        return cap;
    }

    /**
     * One classified section (chunk 0,0) with a 5x5 swimmable-water plane at the feet layer y=5, x,z in
     * [6..10], everything else air. {@code tweak} may overwrite a cell (e.g. a corner) before classification.
     * No depth sweep is needed — {@code candidates()} reads only water/built, never the depth nibbles.
     */
    private static NavGridView buildWaterPlane(Consumer<PalettedContainer<BlockState>> tweak) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                s.set(x, FY, z, water);
            }
        }
        if (tweak != null) tweak.accept(s);

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

    /** A MovementContext over a fresh 3-D water volume (see {@link #buildWaterVolume}), optionally tweaked. */
    private static MovementContext proneVolumeCtx(Consumer<PalettedContainer<BlockState>> tweak) {
        return new MovementContext(buildWaterVolume(tweak), BotCaps.DEFAULT);
    }

    /**
     * One classified section (chunk 0,0) with a solid 5x3x5 swimmable-water CUBE around the node under test —
     * x,z in [6..10], the three feet layers y in [{@link #DY}..{@link #UY}] (=4..6) — everything else air. This
     * covers every feet cell the 20 multi-axis moves read from (8,4,8): the source feet (8,5,8), the up/down
     * feet layers, and all diagonal/corner sub-cells. {@code tweak} may overwrite one cell (a swept sub-cell)
     * before classification to exercise a clearance rejection.
     */
    private static NavGridView buildWaterVolume(Consumer<PalettedContainer<BlockState>> tweak) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 6; x <= 10; x++) {
            for (int y = DY; y <= UY; y++) {
                for (int z = 6; z <= 10; z++) {
                    s.set(x, y, z, water);
                }
            }
        }
        if (tweak != null) tweak.accept(s);

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

    /** A capturing {@link CandidateSink}: records every accepted (cell, cost) so a test can assert the set. */
    private static final class Capture implements CandidateSink {
        final List<int[]> cells = new ArrayList<>();
        final List<Float> costs = new ArrayList<>();

        @Override
        public void accept(int x, int y, int z, float cost, EditScratch edits) {
            cells.add(new int[] { x, y, z });
            costs.add(cost);
        }

        boolean has(int x, int y, int z) {
            for (int[] c : cells) {
                if (c[0] == x && c[1] == y && c[2] == z) return true;
            }
            return false;
        }

        float costOf(int x, int y, int z) {
            for (int i = 0; i < cells.size(); i++) {
                int[] c = cells.get(i);
                if (c[0] == x && c[1] == y && c[2] == z) return costs.get(i);
            }
            return Float.NaN;
        }
    }

    // ---------------------------------------------------------------- findPath-level helpers

    private static boolean contains(BlockPathPlan plan, Object move) {
        return count(plan, move) > 0;
    }

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    /**
     * A sealed stone section (chunk 0,0) with a water channel carved along low Z. The START cell (2,·,2) is
     * 2-deep water (feet + head) so the search seeds {@link MovementContext#MODE_PRONE}; the rest of the
     * channel — (2,·,3), (3,·,3), (3,·,4=goal) — is 1-deep (feet water, head air), through which the prone
     * pose is retained. The diagonal corner cell (3,·,2) is 1-deep water when {@code cornerOpen}, else it
     * stays solid stone. Everything else is solid rock and the bot has no break/place, so the water is the
     * only route: with the corner open the diagonal (2,0,2)->(3,0,3) is legal and cheapest; with it solid the
     * per-axis clip forces the cardinal go-around (2,0,2)->(2,0,3)->(3,0,3).
     */
    private static NavGridView buildChannel(boolean cornerOpen) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone); // solid rock; the channel is carved out below
                }
            }
        }

        // Start cell (2,·,2): 2-deep water (feet y=1 + head y=2) → the bot is submerged/PRONE at the start.
        s.set(2, 1, 2, water);
        s.set(2, 2, 2, water);
        // 1-deep channel: feet water, head air (prone-retained, no upward swim option).
        oneDeep(s, air, water, 2, 3);   // go-around cell
        oneDeep(s, air, water, 3, 3);   // diagonal destination
        oneDeep(s, air, water, 3, 4);   // goal cell
        if (cornerOpen) {
            oneDeep(s, air, water, 3, 2); // the swept corner — water only in the open variant
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

    /** Carve a 1-deep swim cell at (x,·,z): water at the feet layer (y=1), air above (y=2). */
    private static void oneDeep(PalettedContainer<BlockState> s, BlockState air, BlockState water, int x, int z) {
        s.set(x, 1, z, water);
        s.set(x, 2, z, air);
    }

    /**
     * A sealed stone section with a 7x7 open water pool (x,z in [2..8]). The corner cell (2,·,2) is 2-deep
     * (PRONE seed); the rest of the pool is 1-deep water. The whole interior is swimmable, so an 8-connected
     * search beelines the diagonal from (2,0,2) toward (7,0,7).
     */
    private static NavGridView buildOpenPool() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) {
                oneDeep(s, air, water, x, z);
            }
        }
        s.set(2, 2, 2, water); // deepen the start cell to 2-deep so the search seeds PRONE

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
