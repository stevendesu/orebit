package com.orebit.mod.platform;

import net.minecraft.world.entity.Entity;

/**
 * Version-selected accessor for transient entity movement state.
 *
 * <p><b>1.20</b> renamed the public {@code isOnGround()} accessor to {@code onGround()} (and
 * made the backing field private). This is the OLDEST baseline flavor (MC 1.17 era): the
 * {@code isOnGround()} accessor (the {@code onGround} field itself is {@code protected}, so a
 * helper in another package must go through the public getter). The overlay eras compose
 * (build.gradle.kts), so this is supplied to every build through 1.19.4 and then OVERRIDDEN by
 * the {@code onGround()} flavor in {@code overlays/1.20}.
 */
public final class EntityState {
    private EntityState() {}

    public static boolean onGround(Entity e) {
        return e.isOnGround();
    }
}
