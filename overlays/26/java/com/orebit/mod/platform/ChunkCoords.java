package com.orebit.mod.platform;

import net.minecraft.world.level.ChunkPos;

/**
 * 26.x flavor of {@link ChunkCoords}: MC 26.1 made {@code ChunkPos.x}/{@code .z} private; the
 * public accessors are now the {@code x()}/{@code z()} methods. Still {@code static} one-liners,
 * so the JIT inlines them — no dispatch on the chunk hot path. See the 1.17 baseline for the
 * full overlay-composition note.
 */
public final class ChunkCoords {
    private ChunkCoords() {}

    public static int x(ChunkPos pos) { return pos.x(); }
    public static int z(ChunkPos pos) { return pos.z(); }
}
