package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;

/**
 * Version-selected accessor for a dimension's id string (e.g. {@code "minecraft:overworld"}) — the seam the
 * world-model persistence layer ({@link com.orebit.mod.worldmodel.persistence.RegionPersistence}) uses to name a
 * dimension's on-disk directory.
 *
 * <p><b>OLDEST baseline flavor (MC 1.17 → 1.21.10):</b> {@code level.dimension()} is a
 * {@code ResourceKey<Level>} whose {@code location()} returns the registry id. Mojang renamed
 * {@code ResourceLocation} to {@code Identifier} — and {@code ResourceKey.location()} to
 * {@code ResourceKey.identifier()} — in the <b>1.21.11</b> deobfuscation pass (same boundary as
 * {@link BlockLookup}), so this flavor is overridden by {@code overlays/1.21.11}. This baseline calls only
 * {@code .toString()} on the id, so it never names the {@code ResourceLocation}/{@code Identifier} type and
 * stays compilable across the whole ≤1.21.10 range. Callers issue a plain static call (JIT-inlinable).
 */
public final class DimensionId {
    private DimensionId() {}

    /** The dimension's registry id as a string, e.g. {@code "minecraft:overworld"}. */
    public static String of(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
