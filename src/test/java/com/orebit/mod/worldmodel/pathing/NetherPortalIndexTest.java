package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Headless proof of the nether-portal discovery layer (portal-follow DESIGN 3, parts a/b): the
 * {@code NavBlock} PORTAL bit splits {@code NETHER_PORTAL} into its own navtype; the classify pass
 * collects portal cells <b>only</b> when the palette actually contains one (the fast-path gate);
 * {@code patchCell} keeps the resident navtype readable as portal/not-portal (the exact read
 * {@code NavGridUpdater} bases its incremental add/remove on); and the per-level index bookkeeping
 * (record / add / removeCell / remove / nearest) behaves. The {@code ServerLevel}-keyed facade and the
 * live {@code NavGridUpdater}/follower wiring need a live server and are in-game-verified instead.
 */
class NetherPortalIndexTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    // ---- (a) classification: the PORTAL descriptor bit ----------------------------------------

    @Test
    void portalBitSplitsNetherPortalIntoItsOwnNavtype() {
        long portal = NavBlock.descriptorFor(Blocks.NETHER_PORTAL.defaultBlockState());
        assertTrue(NavBlock.isPortal(portal), "NETHER_PORTAL must carry the portal bit");
        assertFalse(NavBlock.isPortal(NavBlock.descriptorFor(Blocks.AIR.defaultBlockState())));
        assertFalse(NavBlock.isPortal(NavBlock.descriptorFor(Blocks.LADDER.defaultBlockState())));
        assertFalse(NavBlock.isPortal(NavBlock.descriptorFor(Blocks.STONE.defaultBlockState())));
        // v1 documented behaviour: portal cells stay passable to walkers (empty collision shape).
        assertTrue(NavBlock.isPassable(portal), "portal cells remain passable in v1");
        // The bit must split the portal out of the generic intangible navtype (distinct from air's).
        assertNotEquals(NavBlock.AIR, NavBlock.navtypeFor(Blocks.NETHER_PORTAL.defaultBlockState()));
    }

    // ---- (b) full-section classify: palette-gated collection ----------------------------------

    @Test
    void classifyCollectsExactlyThePortalCells() {
        PalettedContainer<BlockState> states = solidStoneSection();
        states.set(3, 4, 5, Blocks.NETHER_PORTAL.defaultBlockState());
        states.set(3, 5, 5, Blocks.NETHER_PORTAL.defaultBlockState());

        List<Integer> collected = new ArrayList<>();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid(), collected::add);

        assertEquals(2, collected.size(), "exactly the two portal cells");
        assertTrue(collected.contains((4 << 8) | (5 << 4) | 3), "cell index is (y<<8)|(z<<4)|x");
        assertTrue(collected.contains((5 << 8) | (5 << 4) | 3));
    }

    @Test
    void collectorNeverInvokedWhenPaletteHasNoPortal() {
        PalettedContainer<BlockState> states = solidStoneSection();
        states.set(1, 1, 1, Blocks.AIR.defaultBlockState());
        states.set(2, 1, 1, Blocks.WATER.defaultBlockState());

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid(),
                cell -> fail("collector must not run for a portal-free palette (fast-path gate)"));
    }

    @Test
    void airOnlySectionSkipsTheCollectorEntirely() {
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, section.getTraversalGrid(),
                cell -> fail("collector must not run on the all-air bypass"));
    }

    // ---- (b) incremental: the patch-time portal read NavGridUpdater relies on -----------------

    @Test
    void patchedCellNavtypeReadsBackAsPortal() {
        PalettedContainer<BlockState> states = solidStoneSection();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());

        // Portal patched IN: the resident navtype must now read as portal (the updater's "was" probe).
        NavSectionBuilder.patchCell(section, 7, 8, 9, Blocks.NETHER_PORTAL.defaultBlockState());
        assertTrue(NavBlock.isPortal(
                NavBlock.descriptor((short) section.getTraversalGrid().navtype(7, 8, 9))));

        // Portal patched OUT again.
        NavSectionBuilder.patchCell(section, 7, 8, 9, Blocks.STONE.defaultBlockState());
        assertFalse(NavBlock.isPortal(
                NavBlock.descriptor((short) section.getTraversalGrid().navtype(7, 8, 9))));
    }

    // ---- the index proper (LevelIndex — exercised directly; ServerLevel needs a live server) ---

    @Test
    void packUnpackRoundTripsIncludingNegatives() {
        int[][] cells = { { 0, 0, 0 }, { 123, 64, -456 }, { -30000000, -2047, 30000000 }, { 15, 319, -16 } };
        for (int[] c : cells) {
            long p = NetherPortalIndex.pack(c[0], c[1], c[2]);
            assertEquals(c[0], NetherPortalIndex.unpackX(p));
            assertEquals(c[1], NetherPortalIndex.unpackY(p));
            assertEquals(c[2], NetherPortalIndex.unpackZ(p));
        }
    }

    @Test
    void levelIndexRecordNearestAddRemove() {
        NetherPortalIndex.LevelIndex idx = new NetherPortalIndex.LevelIndex();
        assertNull(idx.nearestPacked(0, 64, 0), "empty index has no nearest");

        // Record a chunk with one 2-tall portal column at (5, 64..65, 8) (chunk 0,0).
        idx.record(NavStore.key(0, 0), new long[] {
                NetherPortalIndex.pack(5, 64, 8), NetherPortalIndex.pack(5, 65, 8) });
        Long near = idx.nearestPacked(4, 64, 8);
        assertNotNull(near);
        assertEquals(5, NetherPortalIndex.unpackX(near));
        assertEquals(64, NetherPortalIndex.unpackY(near));

        // Incremental add of a closer portal (patched in) wins nearest; idempotent re-add doesn't dupe.
        idx.add(3, 64, 8);
        idx.add(3, 64, 8);
        near = idx.nearestPacked(2, 64, 8);
        assertEquals(3, NetherPortalIndex.unpackX(near));

        // Patched out again: nearest falls back to the recorded column.
        idx.removeCell(3, 64, 8);
        near = idx.nearestPacked(2, 64, 8);
        assertEquals(5, NetherPortalIndex.unpackX(near));

        // Wholesale re-record with no portals (rebuild of a chunk whose portal broke) self-cleans...
        idx.record(NavStore.key(0, 0), null);
        assertNull(idx.nearestPacked(4, 64, 8));

        // ...and chunk-unload eviction drops everything too.
        idx.record(NavStore.key(0, 0), new long[] { NetherPortalIndex.pack(5, 64, 8) });
        idx.remove(NavStore.key(0, 0));
        assertNull(idx.nearestPacked(4, 64, 8));
    }

    @Test
    void cellBufferGrowsAndRightSizes() {
        NetherPortalIndex.CellBuffer buf = new NetherPortalIndex.CellBuffer();
        assertNull(buf.toArray(), "empty buffer reports null (record() then evicts the entry)");
        for (int i = 0; i < 9; i++) buf.accept(NetherPortalIndex.pack(i, 64, 0)); // forces one growth
        long[] out = buf.toArray();
        assertEquals(9, out.length);
        for (int i = 0; i < 9; i++) assertEquals(i, NetherPortalIndex.unpackX(out[i]));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static PalettedContainer<BlockState> solidStoneSection() {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }
}
