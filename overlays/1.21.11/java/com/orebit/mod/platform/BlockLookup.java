package com.orebit.mod.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * Version overlay (MC 1.21.11+): the {@code Identifier} flavor of {@link BlockLookup}.
 *
 * <p>Mojang renamed {@code net.minecraft.resources.ResourceLocation} to
 * {@code net.minecraft.resources.Identifier} in <b>1.21.11</b> (the deobfuscation pass);
 * {@code tryParse} and {@code Registry.getOptional} are otherwise identical. Overrides the
 * baseline {@code ResourceLocation} flavor ({@code overlays/1.20.1}) for 1.21.11 and up.
 */
public final class BlockLookup {
    private BlockLookup() {}

    /** The block registered under {@code id} ("namespace:path"), or null if malformed or absent on this version. */
    public static Block byId(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
    }
}
