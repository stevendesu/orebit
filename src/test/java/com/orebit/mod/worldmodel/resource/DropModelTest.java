package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import com.orebit.mod.worldmodel.resource.DropModel.Output;
import com.orebit.mod.worldmodel.resource.DropModel.ToolCondition;

/**
 * Validates the Phase-2 {@link DropModel} Y↔X front door: output-name → source resource + tool condition +
 * counted item ids, with the flagship stone/cobblestone opposite-tool pair. Needs the block registry (the
 * source ids resolve through {@link ResourceClasses}, which registers against real blocks), so it bootstraps
 * Minecraft first like {@link ResourceClassesTest} — the first {@link DropModel} reference must happen AFTER
 * {@code Bootstrap.bootStrap()}.
 */
public class DropModelTest {

    private static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void flagshipStoneVsCobblestone() {
        boot();
        final int stoneSource = ResourceClasses.resourceForName("stone");
        assertTrue(stoneSource >= 0, "stone must be a registered source resource");

        Output stone = DropModel.resolve("stone");
        assertNotNull(stone, "'stone' resolves");
        assertEquals(stoneSource, stone.sourceResourceId(), "stone output's source is the stone resource");
        assertEquals(ToolCondition.SILK_REQUIRED, stone.condition(), "stone needs SILK");
        assertTrue(stone.counts("minecraft:stone"), "stone counts the stone item");
        assertFalse(stone.counts("minecraft:cobblestone"), "stone does NOT count cobblestone");

        Output cobble = DropModel.resolve("cobblestone");
        assertNotNull(cobble, "'cobblestone' resolves");
        assertEquals(stoneSource, cobble.sourceResourceId(), "cobblestone shares stone's SOURCE resource");
        assertEquals(ToolCondition.NO_SILK, cobble.condition(), "cobblestone needs a NON-silk pickaxe");
        assertTrue(cobble.counts("minecraft:cobblestone"), "cobblestone counts cobblestone");
        assertFalse(cobble.counts("minecraft:stone"), "cobblestone does NOT count the stone item");
    }

    @Test
    void oresMapToTheirMinedItem() {
        boot();
        Output iron = DropModel.resolve("iron");
        assertNotNull(iron, "'iron' resolves");
        assertEquals(ResourceClasses.resourceForName("iron"), iron.sourceResourceId(),
                "iron output's source is the iron ore resource");
        assertEquals(ToolCondition.NO_SILK, iron.condition(), "iron ore mined normally (silk gives the ore block)");
        assertTrue(iron.counts("minecraft:raw_iron"), "iron counts raw_iron (the mined item, not the ore block)");
        assertFalse(iron.counts("minecraft:iron_ore"), "iron does NOT count the ore block");

        // The friendly-name alias resolves to the same output instance.
        assertEquals(iron, DropModel.resolve("raw_iron"), "alias raw_iron -> iron output");

        Output coal = DropModel.resolve("coal");
        assertNotNull(coal, "'coal' resolves");
        assertEquals(ToolCondition.NO_SILK, coal.condition());
        assertTrue(coal.counts("minecraft:coal"));
    }

    @Test
    void woodIsEitherAndCountsLogs() {
        boot();
        Output wood = DropModel.resolve("wood");
        assertNotNull(wood, "'wood' resolves");
        assertEquals(ResourceClasses.resourceForName("wood"), wood.sourceResourceId());
        assertEquals(ToolCondition.EITHER, wood.condition(), "a log drops itself, either tool");
        assertTrue(wood.counts("minecraft:oak_log"), "wood counts oak_log");
        assertTrue(wood.counts("minecraft:crimson_stem"), "wood counts crimson_stem");
        assertFalse(wood.counts("minecraft:oak_planks"), "wood does NOT count planks (a crafted item, not a drop)");
        assertEquals(wood, DropModel.resolve("logs"), "alias logs -> wood");
    }

    @Test
    void decorativeSelfDropIsEither() {
        boot();
        Output diorite = DropModel.resolve("diorite");
        assertNotNull(diorite, "'diorite' resolves");
        assertEquals(ResourceClasses.resourceForName("diorite"), diorite.sourceResourceId());
        assertEquals(ToolCondition.EITHER, diorite.condition(), "diorite drops itself with a correct tool");
        assertTrue(diorite.counts("minecraft:diorite"), "diorite counts itself (X==Y)");
    }

    @Test
    void outputNamesContainFlagshipsAndAliasesAndReverseIndexRoundTrips() {
        boot();
        List<String> names = DropModel.outputNames();
        assertTrue(names.contains("stone"), "outputNames() contains stone");
        assertTrue(names.contains("cobblestone"), "outputNames() contains cobblestone");
        assertTrue(names.contains("iron"), "outputNames() contains iron");
        assertTrue(names.contains("wood"), "outputNames() contains wood");
        assertTrue(names.contains("cobble"), "outputNames() contains the cobble alias");

        // Every listed name resolves, and its output's source resource is a real registered resource.
        for (String n : names) {
            Output o = DropModel.resolve(n);
            assertNotNull(o, "listed name '" + n + "' resolves");
            assertTrue(o.sourceResourceId() >= 0, "'" + n + "' has a real source resource");
        }
        // Unknown name → null.
        assertNull(DropModel.resolve("not_a_real_output"), "unknown name resolves to null");
        assertNull(DropModel.resolve(null), "null name resolves to null");
    }

    @Test
    void sourceDropsModelsBothForStone() {
        boot();
        String[] drops = DropModel.sourceDrops(ResourceClasses.resourceForName("stone"));
        assertNotNull(drops, "stone source models both drops");
        assertEquals("minecraft:cobblestone", drops[0], "stone's normal drop is cobblestone");
        assertEquals("minecraft:stone", drops[1], "stone's silk drop is the stone item");
    }
}
