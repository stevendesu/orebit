package com.orebit.mod.platform;

import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Version-selected accessors over a chunk section.
 *
 * <p><b>1.18</b> renamed {@code LevelChunkSection.isEmpty()} to {@code hasOnlyAir()}. This is the
 * baseline {@code isEmpty} flavor (MC 1.17 era); the overlay eras compose (build.gradle.kts), so it
 * is supplied to every build through 1.17.x and then OVERRIDDEN by the {@code hasOnlyAir} flavor in
 * {@code overlays/1.18}. Exactly one flavor compiles per MC version, so callers issue a plain static
 * call (monomorphic, JIT-inlinable — no per-call dispatch).
 */
public final class Sections {
    private Sections() {}

    /** True if the section contains only air — drives the uniform-air recompute bypass. */
    public static boolean hasOnlyAir(LevelChunkSection section) {
        return section.isEmpty();
    }
}
