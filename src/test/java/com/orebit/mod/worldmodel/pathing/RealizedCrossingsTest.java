package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the <b>realized region-crossing collection</b> (Fix A, the block-tier half): a FAILED
 * search reports — via {@link BlockPathfinder#lastRealizedCrossings()} — every directed 16³-cell crossing its
 * surviving cameFrom forest realized, in raw {@code cell>>4} {@link RegionAddress#packLevelKey} pairs. A
 * multi-boundary edge (a Fall) is staircase-decomposed into every intermediate crossing; FOUND and start-dead
 * searches leave the seam empty (the scan runs only on the null-return path with real exploration).
 */
class RealizedCrossingsTest {

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

    private static final BotCaps WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 2.0f);

    @Test
    void chunkCrossingCorridorReportsTheRealizedCrossing() {
        // Corridor z=6 from x=2 across the x=16 chunk boundary to x=20, then walled; goal beyond the wall.
        // The no-break search exhausts (null) having realized exactly the (0,0,0)→(1,0,0) crossing.
        assertNull(BlockPathfinder.findPath(twoChunkCorridor(20), new BlockPos(2, 0, 6), new BlockPos(28, 0, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        long[] realized = BlockPathfinder.lastRealizedCrossings();
        assertEquals(2, realized.length, "exactly one directed crossing (2 longs)");
        assertTrue(containsPair(realized,
                RegionAddress.packLevelKey(0, 0, 0), RegionAddress.packLevelKey(1, 0, 0)),
                "the x=15→16 corridor edge must realize (0,0,0)→(1,0,0)");
    }

    @Test
    void corridorWalledBeforeTheBoundaryRealizesNothing() {
        // Same corridor walled at x=14: the search never leaves chunk 0 → no crossing realized.
        assertNull(BlockPathfinder.findPath(twoChunkCorridor(13), new BlockPos(2, 0, 6), new BlockPos(28, 0, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        assertEquals(0, BlockPathfinder.lastRealizedCrossings().length);
    }

    @Test
    void multiBoundaryFallEdgeIsStaircaseDecomposed() {
        // One Fall edge (15,18,6)→(16,10,6) crosses the X boundary AND the y=16 section boundary in a single
        // A* edge; the staircase decomposition must realize BOTH intermediate crossings (X first, then Y).
        assertNull(BlockPathfinder.findPath(cliffAcrossChunks(), new BlockPos(2, 18, 6), new BlockPos(28, 10, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        long[] realized = BlockPathfinder.lastRealizedCrossings();
        assertEquals(4, realized.length, "the fall realizes exactly two crossings (4 longs)");
        assertTrue(containsPair(realized,
                RegionAddress.packLevelKey(0, 1, 0), RegionAddress.packLevelKey(1, 1, 0)),
                "X crossing of the macro fall edge");
        assertTrue(containsPair(realized,
                RegionAddress.packLevelKey(1, 1, 0), RegionAddress.packLevelKey(1, 0, 0)),
                "Y crossing of the macro fall edge");
    }

    @Test
    void foundSearchLeavesTheSeamEmpty() {
        // Fill the seam with a failing cross-chunk search, then a FOUND run: the entry reset + the
        // no-scan-on-success path must leave the accessor empty.
        assertNull(BlockPathfinder.findPath(twoChunkCorridor(20), new BlockPos(2, 0, 6), new BlockPos(28, 0, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        assertTrue(BlockPathfinder.lastRealizedCrossings().length > 0);
        assertNotNull(BlockPathfinder.findPath(twoChunkCorridor(20), new BlockPos(2, 0, 6), new BlockPos(18, 0, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        assertEquals(0, BlockPathfinder.lastRealizedCrossings().length);
    }

    @Test
    void startDeadSearchLeavesTheSeamEmpty() {
        // Fill the seam, then run a search from a fully-entombed start (≤1 expansion): the scan is skipped
        // and the entry reset leaves the accessor empty — start-dead proves nothing about any crossing.
        assertNull(BlockPathfinder.findPath(twoChunkCorridor(20), new BlockPos(2, 0, 6), new BlockPos(28, 0, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        assertTrue(BlockPathfinder.lastRealizedCrossings().length > 0);
        assertNull(BlockPathfinder.findPath(sealedStone(), new BlockPos(2, 0, 6), new BlockPos(28, 0, 6),
                WALK, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        assertEquals(0, BlockPathfinder.lastRealizedCrossings().length);
    }

    private static boolean containsPair(long[] realized, long from, long to) {
        for (int i = 0; i < realized.length; i += 2) {
            if (realized[i] == from && realized[i + 1] == to) return true;
        }
        return false;
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    /** Chunks (0,0)+(1,0): corridor z=6, y=1..2, x=2..{@code lastOpenX}; everything else sealed stone. */
    private static NavGridView twoChunkCorridor(int lastOpenX) {
        PalettedContainer<BlockState> c0 = filledStone();
        PalettedContainer<BlockState> c1 = filledStone();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 2; x <= lastOpenX; x++) {
            PalettedContainer<BlockState> s = x < 16 ? c0 : c1;
            s.set(x & 15, 1, 6, air);
            s.set(x & 15, 2, 6, air);
        }
        return twoChunks(c0, c1);
    }

    /**
     * A high ledge in chunk 0 (z=6, x=2..15, floor y=18) ending at the boundary; chunk 1 holds a low
     * corridor (x=16..20, floor y=10) under open air, walled beyond — so the only inter-cell edge is the
     * single Fall (15,18,6)→(16,10,6) and the search then exhausts.
     */
    private static NavGridView cliffAcrossChunks() {
        BlockState air = Blocks.AIR.defaultBlockState();
        // Chunk 0: [stone, ledge section, air, air] — the ledge floor is world y=18 (section-1 local y=2).
        PalettedContainer<BlockState> ledge = filledStone();
        for (int x = 2; x <= 15; x++) {
            for (int y = 3; y <= 15; y++) { // world y 19..31
                ledge.set(x, y, 6, air);
            }
        }
        NavSection c0s0 = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(filledStone(), false, c0s0.getTraversalGrid());
        NavSection c0s1 = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(ledge, false, c0s1.getTraversalGrid());

        // Chunk 1: [low-corridor stone, air, air, air] — corridor x=16..20 (local 0..4), floor world y=10.
        PalettedContainer<BlockState> low = filledStone();
        for (int x = 0; x <= 4; x++) {
            for (int y = 11; y <= 15; y++) {
                low.set(x, y, 6, air);
            }
        }
        NavSection c1s0 = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(low, false, c1s0.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { c0s0, c0s1, airSection, airSection });
        chunks.put(NavStore.key(1, 0), new NavSection[] { c1s0, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }

    /** One fully-solid chunk: the start floor is built but the bot's body cells are stone → start-dead. */
    private static NavGridView sealedStone() {
        PalettedContainer<BlockState> c0 = filledStone();
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(c0, false, section.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { section, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }

    private static PalettedContainer<BlockState> filledStone() {
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
        return s;
    }

    private static NavGridView twoChunks(PalettedContainer<BlockState> c0, PalettedContainer<BlockState> c1) {
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection s0 = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(c0, false, s0.getTraversalGrid());
        NavSection s1 = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(c1, false, s1.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { s0, airSection, airSection, airSection });
        chunks.put(NavStore.key(1, 0), new NavSection[] { s1, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }
}
