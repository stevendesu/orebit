package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.platform.BlockLookup;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;

/**
 * Validates the rehomed {@link ResourceClasses} registry + indexed-column layer. Needs the block
 * registry, so it bootstraps Minecraft first (like {@code NavBlockTableTest}); the first reference
 * to {@link ResourceClasses} must happen AFTER {@code Bootstrap.bootStrap()} so its static
 * registration resolves real blocks. Blocks are resolved through {@link BlockLookup} to stay
 * version-agnostic (no {@code Blocks.X} constants).
 */
public class ResourceClassesTest {

    private static Block block(String id) {
        return BlockLookup.byId("minecraft:" + id);
    }

    @Test
    void indexedColumnsAndFixes() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // The deepslate-variant fix: the ore-in-deepslate maps to the SAME column as its stone-tier ore.
        int diamondCol = ResourceClasses.columnForBlock(block("diamond_ore"));
        assertTrue(diamondCol >= 0, "diamond_ore must be indexed");
        assertEquals(diamondCol, ResourceClasses.columnForBlock(block("deepslate_diamond_ore")),
                "deepslate_diamond_ore must share diamond_ore's column");

        // Non-indexed / non-tracked map to -1.
        assertEquals(-1, ResourceClasses.columnForBlock(block("stone")), "stone -> -1 (registry-only)");
        assertEquals(-1, ResourceClasses.columnForBlock(block("deepslate")), "deepslate block -> -1 (saturates)");
        assertEquals(-1, ResourceClasses.columnForBlock(block("oak_log")), "oak_log -> -1 (registry-only)");

        // andesite is an indexed builder-palette block.
        int andesiteCol = ResourceClasses.columnForBlock(block("andesite"));
        assertTrue(andesiteCol >= 0, "andesite must be indexed");

        // Name parsing agrees with block lookup.
        assertEquals(diamondCol, ResourceClasses.columnForName("diamond"), "columnForName(diamond) matches block");
        assertEquals(andesiteCol, ResourceClasses.columnForName("andesite"), "columnForName(andesite) matches block");
        int ironCol = ResourceClasses.columnForBlock(block("iron_ore"));
        assertTrue(ironCol >= 0, "iron_ore must be indexed");
        assertEquals(ironCol, ResourceClasses.columnForName("iron"), "columnForName(iron) matches block");

        // Column count and full name round-trip over every column.
        assertEquals(23, ResourceClasses.columnCount(), "23 indexed columns");
        for (int c = 0; c < ResourceClasses.columnCount(); c++) {
            String name = ResourceClasses.nameOfColumn(c);
            assertNotNull(name, "column " + c + " must have a name");
            assertEquals(c, ResourceClasses.columnForName(name),
                    "columnForName(nameOfColumn(" + c + "))=" + name + " must round-trip");
        }
    }

    @Test
    void registryStillTracksNonIndexedClasses() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Non-indexed blocks keep a stable class id (>= 0) even with column -1.
        assertTrue(ResourceClasses.classIdForBlock(block("stone")) >= 0, "stone has a class id");
        assertTrue(ResourceClasses.classIdForBlock(block("deepslate")) >= 0, "deepslate has a class id");
        assertTrue(ResourceClasses.classIdForBlock(block("oak_log")) >= 0, "oak_log has a class id");
        // An untracked block resolves to -1.
        assertEquals(-1, ResourceClasses.classIdForBlock(block("dirt")), "dirt is not tracked");
    }
}
