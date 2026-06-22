package com.orebit.mod.platform;

import net.minecraft.world.level.Level;

/**
 * Version-selected accessor for a level's vertical bounds.
 *
 * <p><b>1.21.2</b> (walk-back-pinned) renamed {@code Level.getMinBuildHeight()} to
 * {@code getMinY()}. This is the baseline {@code getMinBuildHeight} flavor. The overlay
 * eras compose (build.gradle.kts), so it is supplied to every build through 1.21.1 and
 * then OVERRIDDEN by the {@code getMinY} flavor in {@code overlays/1.21.2}. The mechanism
 * compiles exactly one flavor per MC version, so callers issue a plain static call
 * (monomorphic, JIT-inlinable — no per-call dispatch on the hot path).
 */
public final class LevelBounds {
    private LevelBounds() {}

    public static int minY(Level world) {
        return world.getMinBuildHeight();
    }
}
