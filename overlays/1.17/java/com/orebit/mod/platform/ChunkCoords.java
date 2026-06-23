package com.orebit.mod.platform;

import net.minecraft.world.level.ChunkPos;

/**
 * Version-selected accessors for a {@link ChunkPos}'s chunk-grid X/Z.
 *
 * <p>Confines a single MC-API change to one tiny class so callers ({@code ChunkNavBuilder},
 * {@code ChunkNavLoader}) keep their logic in common core and stay version-agnostic. Both
 * methods are {@code static} one-liners, so the JIT inlines them to the raw field/accessor —
 * no method dispatch on the chunk hot path.
 *
 * <p>This is the OLDEST baseline flavor (MC 1.17 era): {@code ChunkPos.x}/{@code .z} are PUBLIC
 * fields. The overlay eras compose (build.gradle.kts), so this is supplied to every build
 * through 1.21.11 and then OVERRIDDEN in {@code overlays/26}, where 26.1 made the fields private
 * and the public accessors are the {@code x()}/{@code z()} methods.
 */
public final class ChunkCoords {
    private ChunkCoords() {}

    public static int x(ChunkPos pos) { return pos.x; }
    public static int z(ChunkPos pos) { return pos.z; }
}
