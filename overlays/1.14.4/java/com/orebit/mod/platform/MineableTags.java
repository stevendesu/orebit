package com.orebit.mod.platform;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version-selected block-tag queries whose tags were introduced across versions.
 *
 * <p>The {@code minecraft:sword_efficient} tag (blocks a sword mines quickly) was added in
 * <b>1.20</b>. This is the baseline pre-tag flavor: before the tag existed, swords mined
 * cobweb and bamboo quickly (hardcoded), so we approximate the tag with a block check. The
 * overlay eras compose (build.gradle.kts), so this is supplied to every build through 1.19.4
 * and then OVERRIDDEN by the real-tag flavor in {@code overlays/1.20}.
 *
 * <p>Note: {@code BlockState.is(Block)} was added in 1.17, so this baseline flavor is itself
 * re-baselined when support extends below 1.17 (see overlay re-baseline procedure).
 */
public final class MineableTags {
    private MineableTags() {}

    public static boolean swordEfficient(BlockState state) {
        return state.is(Blocks.COBWEB) || state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING);
    }
}
