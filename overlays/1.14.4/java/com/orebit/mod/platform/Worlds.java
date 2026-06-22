package com.orebit.mod.platform;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Version-selected accessor for the {@link Level} an entity is in.
 *
 * <p><b>1.20</b> turned the public {@code Entity.level} field into a {@code level()} accessor
 * method (and made the field private). This is the OLDEST baseline flavor (MC 1.14.4 era):
 * the public {@code level} field. The overlay eras compose (build.gradle.kts), so this is
 * supplied to every build through 1.19.4 and then OVERRIDDEN by the {@code level()} flavor in
 * {@code overlays/1.20}. Callers issue a plain static call (monomorphic, JIT-inlinable).
 */
public final class Worlds {
    private Worlds() {}

    public static Level of(Entity e) {
        return e.level;
    }
}
