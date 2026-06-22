package com.orebit.mod.platform;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Version-selected resolver from a registry id string to a {@link Block}.
 *
 * <p>Confines the registry-identifier and registry-access types to one tiny class so callers
 * ({@code RegionBlockIndex} and any future caller) stay version-agnostic: they pass a
 * {@code "namespace:path"} string and get a {@code Block} or {@code null}.
 *
 * <p>This is the OLDEST baseline flavor (MC 1.14.4 era): the block registry is the static
 * {@code net.minecraft.core.Registry.BLOCK}. The overlay eras compose (build.gradle.kts), so
 * this is supplied to every build through 1.19.2 and then OVERRIDDEN:
 * <ul>
 *   <li>{@code overlays/1.19.3} — {@code BuiltInRegistries.BLOCK} (the 1.19.3 registry refactor
 *       moved the static holders to {@code net.minecraft.core.registries.BuiltInRegistries}),</li>
 *   <li>{@code overlays/1.21.11} — {@code ResourceLocation} renamed to {@code Identifier}.</li>
 * </ul>
 */
public final class BlockLookup {
    private BlockLookup() {}

    /** The block registered under {@code id} ("namespace:path"), or null if malformed or absent on this version. */
    public static Block byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return Registry.BLOCK.getOptional(rl).orElse(null);
    }

    /** Iterate every registered block (in registry order). */
    public static void forEachBlock(java.util.function.Consumer<Block> action) {
        Registry.BLOCK.forEach(action);
    }

    /** Number of registered blocks. ({@code Registry.size()} predates 1.18, so count keys.) */
    public static int blockCount() {
        return Registry.BLOCK.keySet().size();
    }
}
