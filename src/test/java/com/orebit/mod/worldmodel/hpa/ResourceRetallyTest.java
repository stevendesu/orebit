package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The block-change resource re-tally (find-mine-resources design §8.5) — the fix for the "compass keeps
 * routing the bot back to a mined-out vein" loop. Covers the two new pieces:
 * <ul>
 *   <li>{@link NavSectionBuilder#tallyResources} reproduces the resource-tally subset of
 *       {@link NavSectionBuilder#classifyNavtypes} <b>exactly</b> (so the incremental path counts the same as
 *       the chunk-build path), and a fully-mined section re-tallies to all-zero;</li>
 *   <li>{@link HpaMaintenance#applyResourceRetally} + {@link HpaMaintenance#clearResourceRow} — an all-zero
 *       re-tally CLEARS a previously-populated level-0 row and the roll-up drops the ancestors (the zero-row
 *       fix), while a resource-free section that never had a row stays uninterned (sparsity);</li>
 *   <li>the {@code onBlockChanged} gate: dirt/stone/air are column −1, so a common (non-resource) block change
 *       trips neither {@code columnForBlock} into a positive column — it is a cheap no-op (no sweep, no queue).</li>
 * </ul>
 */
public class ResourceRetallyTest {

    private static PalettedContainer<BlockState> newSection() {
        return new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static BlockState state(String id) {
        return BlockLookup.byId("minecraft:" + id).defaultBlockState();
    }

    private static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tallyResourcesReproducesClassifyTallyExactly() {
        boot();

        PalettedContainer<BlockState> c = newSection();
        BlockState iron = state("iron_ore");
        BlockState deepDiamond = state("deepslate_diamond_ore");
        BlockState dirt = state("dirt");
        for (int i = 0; i < 7; i++) c.getAndSet(0, i, 0, iron);
        for (int i = 0; i < 2; i++) c.getAndSet(1, i, 0, deepDiamond);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.getAndSet(x, 8, z, dirt);

        // The chunk-build path (the tally that rides classifyNavtypes) ...
        int[] fromClassify = new int[ResourceClasses.COLUMN_COUNT];
        NavSectionBuilder.classifyNavtypes(c, false, new TraversalGrid(), null, fromClassify);
        // ... and the incremental re-tally path must produce a byte-identical raw count vector.
        int[] fromRetally = new int[ResourceClasses.COLUMN_COUNT];
        NavSectionBuilder.tallyResources(c, false, fromRetally);

        assertArrayEquals(fromClassify, fromRetally,
                "the incremental tallyResources must reproduce the classify tally exactly");
        assertTrue(fromRetally[ResourceClasses.columnForResource(ResourceClasses.resourceForName("iron"))] == 7,
                "sanity: 7 iron counted");
    }

    @Test
    void minedOutSectionClearsRowAndRollupReflectsIt() {
        boot();
        final int ironCol = ResourceClasses.columnForResource(ResourceClasses.resourceForName("iron"));
        assertTrue(ironCol >= 0, "iron must be indexed");

        // A section holding a small iron vein, tallied into a fresh pyramid at leaf (0,0,0).
        PalettedContainer<BlockState> c = newSection();
        BlockState iron = state("iron_ore");
        for (int i = 0; i < 6; i++) c.getAndSet(0, i, 0, iron);

        ResourcePyramid p = new ResourcePyramid();
        int[] raw = new int[ResourceClasses.COLUMN_COUNT];
        NavSectionBuilder.tallyResources(c, false, raw);
        assertTrue(HpaMaintenance.applyResourceRetally(p, 0, 0, 0, raw), "the vein must write a row");

        int leaf = p.rowIfPresent(0, 0, 0, 0);
        assertTrue(leaf >= 0 && (p.getLog2(0, leaf, ironCol) & 0xFF) > 0, "leaf row holds iron");
        int lvl1 = p.rowIfPresent(1, 0, 0, 0);
        assertTrue(lvl1 >= 0 && (p.getLog2(1, lvl1, ironCol) & 0xFF) > 0, "roll-up: level-1 ancestor holds iron");
        int top = p.rowIfPresent(ResourcePyramid.RESOURCE_TOP_LEVEL, 0, 0, 0);
        assertTrue(top >= 0 && (p.getLog2(ResourcePyramid.RESOURCE_TOP_LEVEL, top, ironCol) & 0xFF) > 0,
                "roll-up reaches the true-global top");

        // Mine the whole vein out (iron cells → air), re-tally → all-zero, and apply: the row must CLEAR and the
        // drop must roll all the way up (this is the fix — the old sparse write path could not clear a row).
        for (int i = 0; i < 6; i++) c.getAndSet(0, i, 0, Blocks.AIR.defaultBlockState());
        NavSectionBuilder.tallyResources(c, false, raw);
        for (int col = 0; col < ResourceClasses.COLUMN_COUNT; col++) {
            assertEquals(0, raw[col], "mined-out section re-tallies to all-zero (col " + col + ")");
        }
        assertTrue(HpaMaintenance.applyResourceRetally(p, 0, 0, 0, raw), "the emptied section must clear its row");

        assertEquals(0, p.getLog2(0, leaf, ironCol) & 0xFF, "level-0 row zeroed");
        assertEquals(0, p.getLog2(1, lvl1, ironCol) & 0xFF, "level-1 ancestor dropped to 0");
        assertEquals(0, p.getLog2(ResourcePyramid.RESOURCE_TOP_LEVEL, top, ironCol) & 0xFF,
                "the drop reaches the true-global top (no more phantom vein)");
    }

    @Test
    void emptyRetallyOnAbsentRowIsANoOp() {
        // A resource-relevant change that leaves the section with no indexed resource, in a section that never
        // had a row, must NOT intern an empty row (sparsity — clearResourceRow returns false, nothing created).
        ResourcePyramid p = new ResourcePyramid();
        int[] allZero = new int[ResourceClasses.COLUMN_COUNT];
        assertFalse(HpaMaintenance.applyResourceRetally(p, 5, 2, 7, allZero),
                "an all-zero re-tally on a never-populated leaf writes nothing");
        assertEquals(0, p.rowCount(0), "no level-0 row interned for the empty change (sparsity preserved)");
    }

    @Test
    void nonResourceBlockChangeIsAGateNoOp() {
        boot();
        // The onBlockChanged resource gate is (columnForBlock(old) >= 0 || columnForBlock(new) >= 0). Dirt, stone
        // and air are all column −1, so a dirt→stone / stone→air / any non-indexed change fails the gate and never
        // enqueues a resource re-tally — the common block change stays free (just two O(1) HashMap lookups).
        assertEquals(-1, ResourceClasses.columnForBlock(state("dirt").getBlock()), "dirt is not indexed");
        assertEquals(-1, ResourceClasses.columnForBlock(state("stone").getBlock()), "stone is not indexed");
        assertEquals(-1, ResourceClasses.columnForBlock(Blocks.AIR.defaultBlockState().getBlock()),
                "air is not indexed");
    }
}
