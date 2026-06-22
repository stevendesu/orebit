package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version-selected accessor for block-shape queries that changed signature across versions.
 *
 * <p>{@code BlockState.isSolidRender(BlockGetter, BlockPos)} required a level + position
 * until <b>1.21.2</b> (walk-back-pinned) dropped them for a context-free
 * {@code isSolidRender()}. This is the baseline two-arg flavor. The overlay eras compose
 * (build.gradle.kts), so it is supplied to every build through 1.21.1 and then OVERRIDDEN
 * by the no-arg flavor in {@code overlays/1.21.2}. Callers always pass level + pos;
 * compiled one flavor per MC version, so the call stays monomorphic.
 */
public final class BlockShapes {
    private BlockShapes() {}

    public static boolean isSolidRender(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSolidRender(level, pos);
    }
}
