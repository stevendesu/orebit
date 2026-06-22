package com.orebit.mod.platform;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Version overlay (MC 1.20+): the {@code Material} system was removed and replaced by the
 * no-arg {@code BlockState.canBeReplaced()}. Overrides the baseline {@code Material} flavor
 * ({@code overlays/1.14.4}) for 1.20 and up. See {@link com.orebit.mod.platform.Replaceable}
 * baseline for the contract.
 */
public final class Replaceable {
    private Replaceable() {}

    public static boolean isReplaceable(BlockState state) {
        return state.canBeReplaced();
    }
}
