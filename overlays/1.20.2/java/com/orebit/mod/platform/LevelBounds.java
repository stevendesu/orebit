package com.orebit.mod.platform;

import net.minecraft.world.level.Level;

/**
 * Version-selected accessor for a level's vertical bounds.
 *
 * <p>1.21 renamed {@code Level.getMinBuildHeight()} to {@code getMinY()}. The overlay
 * mechanism compiles exactly one flavor of this class per MC version, so callers issue a
 * plain static call (monomorphic, JIT-inlinable — no per-call dispatch on the hot path).
 *
 * <p>This is the MC 1.20.2+ era flavor. (The exact rename boundary — 1.21.0 — will get
 * its own overlay era when the version walk-back pins it; this era currently targets
 * 1.21.4.)
 */
public final class LevelBounds {
    private LevelBounds() {}

    public static int minY(Level world) {
        return world.getMinY();
    }
}
