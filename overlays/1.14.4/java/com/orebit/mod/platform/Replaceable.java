package com.orebit.mod.platform;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Version-selected "is this block state replaceable" query.
 *
 * <p><b>1.20</b> removed the {@code Material} system and added the no-arg
 * {@code BlockState.canBeReplaced()}. This is the OLDEST baseline flavor (MC 1.14.4 era):
 * replaceability comes from the block's {@code Material}. The overlay eras compose
 * (build.gradle.kts), so this is supplied to every build through 1.19.4 and then OVERRIDDEN by
 * the {@code canBeReplaced()} flavor in {@code overlays/1.20}.
 */
public final class Replaceable {
    private Replaceable() {}

    public static boolean isReplaceable(BlockState state) {
        return state.getMaterial().isReplaceable();
    }
}
