package com.orebit.mod.farming;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bootstrap-tier facts check for the {@link CropKinds} table (DESIGN-bot-abilities.md §4): every
 * kind must resolve against the REAL registries, and — the load-bearing cross-check — each kind's
 * hand-stated {@code maxAge} must equal the real crop block's {@code age} property maximum, so a
 * version bump that changes a crop's age track fails loudly here instead of silently never
 * harvesting (or harvesting immature).
 */
class CropKindsTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        CropKinds.bake();
    }

    @Test
    void everyKindResolves() {
        for (CropKind kind : CropKinds.all()) {
            assertNotNull(kind.block(), kind.cropBlockId() + " block must resolve");
            assertNotNull(kind.seedItem(), kind.seedItemId() + " item must resolve");
            assertNotNull(kind.matureState(), kind.cropBlockId() + " must have a mature state");
        }
    }

    @Test
    void maxAgeMatchesTheRealProperty() {
        for (CropKind kind : CropKinds.all()) {
            final BlockState def = kind.block().defaultBlockState();
            Integer realMax = null;
            for (Property<?> p : def.getProperties()) {
                if ("age".equals(p.getName())) {
                    for (Object v : p.getPossibleValues()) {
                        final int i = (Integer) v;
                        if (realMax == null || i > realMax) realMax = i;
                    }
                }
            }
            assertNotNull(realMax, kind.cropBlockId() + " must carry an age property");
            assertEquals(realMax.intValue(), kind.maxAge(),
                    kind.cropBlockId() + " maxAge must match the real property maximum");
        }
    }

    @Test
    void maturityReadsTheState() {
        for (CropKind kind : CropKinds.all()) {
            assertFalse(kind.isMature(kind.block().defaultBlockState()),
                    kind.cropBlockId() + " age 0 must not read mature");
            assertTrue(kind.isMature(kind.matureState()),
                    kind.cropBlockId() + " matureState must read mature");
            assertEquals(0, CropKind.ageOf(kind.block().defaultBlockState()));
        }
    }

    @Test
    void seedClassification() {
        assertTrue(CropKinds.isSeedItem(Items.WHEAT_SEEDS));
        assertTrue(CropKinds.isSeedItem(Items.CARROT));
        assertTrue(CropKinds.isSeedItem(Items.POTATO));
        assertTrue(CropKinds.isSeedItem(Items.BEETROOT_SEEDS));
        assertFalse(CropKinds.isSeedItem(Items.DIAMOND));
        assertFalse(CropKinds.isSeedItem(Items.WHEAT)); // the harvest product is NOT a seed
        assertFalse(CropKinds.isSeedItem(null));
    }

    @Test
    void deterministicReplantOrder() {
        // Registration order is the replant preference — wheat first, beetroots last.
        assertEquals("minecraft:wheat", CropKinds.all().get(0).cropBlockId());
        assertEquals("minecraft:beetroots", CropKinds.all().get(3).cropBlockId());
    }
}
