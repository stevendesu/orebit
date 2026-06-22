package com.orebit.mod.platform;

import net.minecraft.world.entity.Entity;

/**
 * Version overlay (MC 1.20+): {@code Entity.onGround} became the {@code onGround()} accessor
 * method. Overrides the baseline public-field flavor ({@code overlays/1.14.4}) for 1.20 and up.
 * See {@link com.orebit.mod.platform.EntityState} baseline for the contract.
 */
public final class EntityState {
    private EntityState() {}

    public static boolean onGround(Entity e) {
        return e.onGround();
    }
}
