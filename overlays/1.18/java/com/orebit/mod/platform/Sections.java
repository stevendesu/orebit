package com.orebit.mod.platform;

import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Version-selected accessors over a chunk section.
 *
 * <p>This is the {@code hasOnlyAir} flavor introduced at <b>1.18</b> (which renamed
 * {@code LevelChunkSection.isEmpty()} to {@code hasOnlyAir()}); it overrides the baseline
 * {@code isEmpty} flavor in {@code overlays/1.17}. The overlay eras compose (build.gradle.kts), so
 * this file is active for 1.18 and up. Exactly one flavor compiles per MC version, so callers issue
 * a plain static call (monomorphic, JIT-inlinable — no per-call dispatch).
 */
public final class Sections {
    private Sections() {}

    /** True if the section contains only air — drives the uniform-air recompute bypass. */
    public static boolean hasOnlyAir(LevelChunkSection section) {
        return section.hasOnlyAir();
    }
}
