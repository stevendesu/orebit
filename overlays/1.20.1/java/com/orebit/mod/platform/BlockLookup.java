package com.orebit.mod.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Version-selected resolver from a registry id string to a {@link Block}.
 *
 * <p>Confines the registry-identifier type to one tiny class: Mojang renamed
 * {@code net.minecraft.resources.ResourceLocation} to {@code Identifier} in <b>1.21.11</b>
 * (the deobfuscation pass). Callers pass a {@code "namespace:path"} string and get a
 * {@code Block} or {@code null}, never naming the identifier type — so
 * {@code RegionBlockIndex} (and any future caller) stays version-agnostic.
 *
 * <p>This is the baseline {@code ResourceLocation} flavor ({@code overlays/1.20.1}); the
 * {@code Identifier} flavor at {@code overlays/1.21.11} overrides it for 1.21.11 and up.
 */
public final class BlockLookup {
    private BlockLookup() {}

    /** The block registered under {@code id} ("namespace:path"), or null if malformed or absent on this version. */
    public static Block byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
    }
}
