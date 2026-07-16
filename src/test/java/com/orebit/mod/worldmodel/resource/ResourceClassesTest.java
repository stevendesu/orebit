package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.orebit.mod.platform.BlockLookup;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;

/**
 * Validates the two-tier {@link ResourceClasses} model: an unbounded LOCATABLE registry (named + nameless) and
 * the narrow PERSISTED-column subset. Needs the block registry, so it bootstraps Minecraft first (like {@code
 * NavBlockTableTest}); the first reference to {@link ResourceClasses} must happen AFTER {@code
 * Bootstrap.bootStrap()} so its static registration resolves real blocks. Blocks are resolved through
 * {@link BlockLookup} to stay version-agnostic (no {@code Blocks.X} constants).
 */
public class ResourceClassesTest {

    private static Block block(String id) {
        return BlockLookup.byId("minecraft:" + id);
    }

    @Test
    void persistedColumnsAndDeepslateFold() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // The deepslate-variant fix: the ore-in-deepslate maps to the SAME column as its stone-tier ore.
        int diamondCol = ResourceClasses.columnForBlock(block("diamond_ore"));
        assertTrue(diamondCol >= 0, "diamond_ore must be persisted");
        assertEquals(diamondCol, ResourceClasses.columnForBlock(block("deepslate_diamond_ore")),
                "deepslate_diamond_ore must share diamond_ore's column");

        // A persisted resource is BOTH a locatable and column-bound.
        assertTrue(ResourceClasses.resourceForBlock(block("diamond_ore")) >= 0, "diamond_ore is a locatable");
        int ironCol = ResourceClasses.columnForBlock(block("iron_ore"));
        assertTrue(ironCol >= 0, "iron_ore must be persisted");
        assertTrue(ResourceClasses.resourceForBlock(block("iron_ore")) >= 0, "iron_ore is a locatable");

        // andesite is a persisted builder-palette block.
        int andesiteCol = ResourceClasses.columnForBlock(block("andesite"));
        assertTrue(andesiteCol >= 0, "andesite must be persisted");

        // The plain deepslate block is a NAMELESS locatable: tracked (id >= 0) but not persisted (column -1).
        assertTrue(ResourceClasses.resourceForBlock(block("deepslate")) >= 0, "deepslate is a locatable");
        assertEquals(-1, ResourceClasses.columnForBlock(block("deepslate")), "deepslate block -> -1 (saturates)");

        // Name parsing agrees with block lookup: name -> locatable id -> column matches the block's column.
        assertEquals(diamondCol,
                ResourceClasses.columnForResource(ResourceClasses.resourceForName("diamond")),
                "resourceForName(diamond) -> its column matches diamond_ore");
        assertEquals(andesiteCol,
                ResourceClasses.columnForResource(ResourceClasses.resourceForName("andesite")),
                "resourceForName(andesite) -> its column matches andesite");
        assertEquals(ironCol,
                ResourceClasses.columnForResource(ResourceClasses.resourceForName("iron")),
                "resourceForName(iron) -> its column matches iron_ore");

        // 23 persisted columns (stone + wood are locatable-only now, so they leave the pyramid).
        assertEquals(23, ResourceClasses.columnCount(), "23 persisted columns (stone/wood are locatable-only)");
        assertEquals(23, ResourceClasses.columnNames().size(), "columnNames() has one entry per column");
        for (int c = 0; c < ResourceClasses.columnCount(); c++) {
            String name = ResourceClasses.nameOfColumn(c);
            assertNotNull(name, "column " + c + " must have a name");
            assertEquals(c, ResourceClasses.columnForResource(ResourceClasses.resourceForName(name)),
                    "columnForResource(resourceForName(nameOfColumn(" + c + ")))=" + name + " must round-trip");
        }
    }

    @Test
    void stoneAndWoodAreLocatableOnly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // stone: named + block-tracked (locatable) but NOT persisted.
        int stoneRes = ResourceClasses.resourceForBlock(block("stone"));
        assertTrue(stoneRes >= 0, "stone must be a locatable (resourceForBlock >= 0)");
        assertTrue(ResourceClasses.resourceForName("stone") >= 0, "stone must resolve by name");
        assertEquals(stoneRes, ResourceClasses.resourceForName("stone"), "name + block agree on stone's id");
        assertEquals(-1, ResourceClasses.columnForBlock(block("stone")), "stone is NOT persisted (column -1)");
        assertEquals(-1, ResourceClasses.columnForResource(stoneRes), "stone locatable -> column -1");

        // wood: same — LOG is named "wood", block-tracked, but not column-bound. Stems share the class.
        int woodRes = ResourceClasses.resourceForBlock(block("oak_log"));
        assertTrue(woodRes >= 0, "oak_log must be a locatable");
        assertTrue(ResourceClasses.resourceForName("wood") >= 0, "wood must resolve by name");
        assertEquals(woodRes, ResourceClasses.resourceForName("wood"), "name + block agree on wood's id");
        assertEquals(woodRes, ResourceClasses.resourceForBlock(block("crimson_stem")),
                "stems share the LOG locatable -> the wood id");
        assertEquals(-1, ResourceClasses.columnForBlock(block("oak_log")), "wood is NOT persisted (column -1)");
        assertEquals(-1, ResourceClasses.columnForResource(woodRes), "wood locatable -> column -1");
    }

    @Test
    void locatableNamesIsSupersetOfColumnNames() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> locatable = ResourceClasses.locatableNames();
        List<String> columns = ResourceClasses.columnNames();

        // Superset: every column name is also a locatable name.
        assertTrue(locatable.containsAll(columns), "locatableNames() must contain every columnNames() entry");
        // stone + wood are locatable but not columns.
        assertTrue(locatable.contains("stone"), "locatableNames() contains stone");
        assertTrue(locatable.contains("wood"), "locatableNames() contains wood");
        assertFalse(columns.contains("stone"), "columnNames() does NOT contain stone");
        assertFalse(columns.contains("wood"), "columnNames() does NOT contain wood");
        // The two locatable-only names are exactly the size difference (25 named locatables, 23 columns).
        assertEquals(columns.size() + 2, locatable.size(),
                "locatableNames() is columnNames() + {stone, wood}");
    }

    @Test
    void registryTracksNamelessLocatables() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Nameless, non-persisted blocks keep a stable locatable id (>= 0) with column -1 and no name.
        int gravel = ResourceClasses.resourceForBlock(block("gravel"));
        assertTrue(gravel >= 0, "gravel has a locatable id");
        assertEquals(-1, ResourceClasses.columnForBlock(block("gravel")), "gravel is not persisted (column -1)");
        assertNotNull(block("gravel"), "gravel exists on this version");
        // Nameless => not user-addressable and not in the tab-complete list.
        org.junit.jupiter.api.Assertions.assertNull(ResourceClasses.nameOfResource(gravel),
                "gravel is nameless -> nameOfResource is null");
        assertFalse(ResourceClasses.locatableNames().contains("gravel"), "gravel is not a named locatable");
        assertEquals(-1, ResourceClasses.resourceForName("gravel"), "gravel has no name to resolve");

        assertTrue(ResourceClasses.resourceForBlock(block("deepslate")) >= 0, "deepslate has a locatable id");
        assertTrue(ResourceClasses.resourceForBlock(block("oak_log")) >= 0, "oak_log has a locatable id");
        // An untracked block resolves to -1.
        assertEquals(-1, ResourceClasses.resourceForBlock(block("dirt")), "dirt is not tracked");
    }
}
