package com.orebit.mod.platform;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Version overlay (MC 1.20+): {@code Entity.level} became the {@code level()} accessor method.
 * Overrides the baseline public-field flavor ({@code overlays/1.14.4}) for 1.20 and up.
 * See {@link com.orebit.mod.platform.Worlds} baseline for the contract.
 */
public final class Worlds {
    private Worlds() {}

    public static Level of(Entity e) {
        return e.level();
    }
}
