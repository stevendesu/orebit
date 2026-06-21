package com.orebit.mod.platform;

import net.minecraft.world.level.Level;

/**
 * Version-selected accessor for a level's vertical bounds.
 *
 * <p>1.21 renamed {@code Level.getMinBuildHeight()} to {@code getMinY()}. The overlay
 * mechanism compiles exactly one flavor of this class per MC version, so callers issue a
 * plain static call (monomorphic, JIT-inlinable — no per-call dispatch on the hot path).
 *
 * <p>This is the MC 1.20.1 era flavor.
 */
public final class LevelBounds {
    private LevelBounds() {}

    public static int minY(Level world) {
        return world.getMinBuildHeight();
    }
}
