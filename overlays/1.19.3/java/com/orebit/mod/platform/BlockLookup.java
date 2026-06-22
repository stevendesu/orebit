package com.orebit.mod.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Version overlay (MC 1.19.3+): the 1.19.3 registry refactor moved the static registry
 * holders from {@code net.minecraft.core.Registry} to
 * {@code net.minecraft.core.registries.BuiltInRegistries}. This overrides the baseline
 * {@code Registry.BLOCK} flavor ({@code overlays/1.17}) for 1.19.3 and up, and is itself
 * overridden at {@code overlays/1.21.11} (the {@code ResourceLocation} → {@code Identifier}
 * rename). See {@link com.orebit.mod.platform.BlockLookup} baseline for the contract.
 */
public final class BlockLookup {
    private BlockLookup() {}

    /** The block registered under {@code id} ("namespace:path"), or null if malformed or absent on this version. */
    public static Block byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
    }

    /** Iterate every registered block (in registry order). */
    public static void forEachBlock(java.util.function.Consumer<Block> action) {
        BuiltInRegistries.BLOCK.forEach(action);
    }

    /** Number of registered blocks. */
    public static int blockCount() {
        return BuiltInRegistries.BLOCK.size();
    }
}
