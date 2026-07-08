package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Phase-4 correctness: the resource tally that rides {@link NavSectionBuilder#classifyNavtypes} (the gated
 * palette pass). Builds a real {@link PalettedContainer} with a known ore mix and asserts the per-column raw
 * counts land in the right indexed columns (deepslate variants folding under their stone-tier ore), that
 * stone/air contribute nothing, and that a resource-free section leaves the tally all-zero (→ {@code null} on
 * the NavSection). Blocks are resolved via {@link BlockLookup} to stay version-agnostic.
 */
public class ResourceTallyTest {

    private static PalettedContainer<BlockState> newSection() {
        return new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static BlockState state(String id) {
        Block b = BlockLookup.byId("minecraft:" + id);
        return b.defaultBlockState();
    }

    @Test
    void tallyCountsResourcesByColumn() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final int diamondCol = ResourceClasses.columnForName("diamond");
        final int ironCol = ResourceClasses.columnForName("iron");
        assertTrue(diamondCol >= 0 && ironCol >= 0, "diamond + iron must be indexed");

        PalettedContainer<BlockState> c = newSection();
        BlockState diamond = state("diamond_ore");
        BlockState deepIron = state("deepslate_iron_ore"); // must fold under the iron column
        BlockState stone = state("stone");                 // registry-only → contributes nothing

        // 5 diamond_ore.
        for (int i = 0; i < 5; i++) c.getAndSet(0, i, 0, diamond);
        // 3 deepslate_iron_ore.
        for (int i = 0; i < 3; i++) c.getAndSet(1, i, 0, deepIron);
        // A stone floor plane at y=8 (256 cells) + air everywhere else — neither is indexed.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.getAndSet(x, 8, z, stone);
            }
        }

        int[] tally = new int[ResourceClasses.COLUMN_COUNT];
        TraversalGrid grid = new TraversalGrid();
        NavSectionBuilder.classifyNavtypes(c, false, grid, null, tally);

        assertEquals(5, tally[diamondCol], "5 diamond_ore counted in the diamond column");
        assertEquals(3, tally[ironCol], "3 deepslate_iron_ore counted under the iron column");
        // Every other column (incl. any that stone/air might have touched) must be zero.
        for (int col = 0; col < ResourceClasses.COLUMN_COUNT; col++) {
            if (col == diamondCol || col == ironCol) continue;
            assertEquals(0, tally[col], "column " + col + " must be untouched (stone/air are not indexed)");
        }
    }

    @Test
    void resourceFreeSectionLeavesTallyZero() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        PalettedContainer<BlockState> c = newSection();
        BlockState stone = state("stone");
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.getAndSet(x, 4, z, stone); // stone floor, air above — no indexed resource
            }
        }

        int[] tally = new int[ResourceClasses.COLUMN_COUNT];
        TraversalGrid grid = new TraversalGrid();
        NavSectionBuilder.classifyNavtypes(c, false, grid, null, tally);

        // All-zero ⇒ ChunkNavBuilder.attachResourceTally derives a null NavSection.resourceTally (sparsity).
        for (int col = 0; col < ResourceClasses.COLUMN_COUNT; col++) {
            assertEquals(0, tally[col], "resource-free section: column " + col + " stays zero");
        }
    }
}
