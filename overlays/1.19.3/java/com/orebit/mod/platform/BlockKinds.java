package com.orebit.mod.platform;

import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;

/**
 * Version overlay (MC 1.19.3+): the bamboo stalk block class {@code BambooBlock} was renamed to
 * {@code BambooStalkBlock} at <b>1.19.3</b> (walk-back-pinned: 1.19.2 still has {@code BambooBlock},
 * 1.19.3 already has {@code BambooStalkBlock}). Overrides the baseline {@code BambooBlock} flavor
 * ({@code overlays/1.17}) for 1.19.3 and up. See {@link com.orebit.mod.platform.BlockKinds}
 * baseline for the contract.
 */
public final class BlockKinds {
    private BlockKinds() {}

    public static boolean isBambooStalk(Block block) {
        return block instanceof BambooStalkBlock;
    }
}
