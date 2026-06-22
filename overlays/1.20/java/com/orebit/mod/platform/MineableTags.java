package com.orebit.mod.platform;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version overlay (MC 1.20+): the {@code minecraft:sword_efficient} block tag exists, so the
 * query is the real tag membership. Overrides the baseline approximation ({@code overlays/1.14.4})
 * for 1.20 and up. See {@link com.orebit.mod.platform.MineableTags} baseline for the contract.
 */
public final class MineableTags {
    private MineableTags() {}

    public static boolean swordEfficient(BlockState state) {
        return state.is(BlockTags.SWORD_EFFICIENT);
    }
}
