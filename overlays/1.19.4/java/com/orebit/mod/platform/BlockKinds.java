package com.orebit.mod.platform;

import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;

/**
 * Version overlay (MC 1.19.4+): the bamboo stalk block class {@code BambooBlock} was renamed
 * to {@code BambooStalkBlock} (bamboo wood set). Overrides the baseline {@code BambooBlock}
 * flavor ({@code overlays/1.17}) for 1.19.4 and up. See {@link com.orebit.mod.platform.BlockKinds}
 * baseline for the contract.
 */
public final class BlockKinds {
    private BlockKinds() {}

    public static boolean isBambooStalk(Block block) {
        return block instanceof BambooStalkBlock;
    }
}
