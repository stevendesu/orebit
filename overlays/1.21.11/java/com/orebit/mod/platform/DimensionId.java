package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;

/**
 * Version overlay (MC 1.21.11+): the {@code identifier()} flavor of {@link DimensionId}.
 *
 * <p>Mojang's 1.21.11 deobfuscation renamed {@code ResourceKey.location()} to
 * {@code ResourceKey.identifier()} (alongside {@code ResourceLocation} → {@code Identifier}; cf.
 * {@link BlockLookup}), so the baseline {@code location()} flavor ({@code overlays/1.17}) fails to compile here.
 * This flavor overrides it for 1.21.11 and up (incl. the 26.x Fabric era). Still only calls {@code .toString()}
 * on the id, so the {@code Identifier} type is never named. See the {@code overlays/1.17} baseline for the contract.
 */
public final class DimensionId {
    private DimensionId() {}

    /** The dimension's registry id as a string, e.g. {@code "minecraft:overworld"}. */
    public static String of(ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
