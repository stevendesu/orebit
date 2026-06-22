package com.orebit.mod.platform;

import net.minecraft.world.level.Level;

/**
 * Version-selected accessor for a level's vertical bounds.
 *
 * <p><b>1.21.2</b> (walk-back-pinned) renamed {@code Level.getMinBuildHeight()} to
 * {@code getMinY()}. The overlay mechanism compiles exactly one flavor of this class per
 * MC version, so callers issue a plain static call (monomorphic, JIT-inlinable — no
 * per-call dispatch on the hot path).
 *
 * <p>This is the {@code getMinY} flavor introduced at <b>1.21.2</b> — a version overlay
 * delta that overrides the baseline {@code getMinBuildHeight} {@code overlays/1.20.1}
 * flavor. The overlay eras compose (build.gradle.kts); this file is active for 1.21.2 and up.
 */
public final class LevelBounds {
    private LevelBounds() {}

    public static int minY(Level world) {
        return world.getMinY();
    }
}
