package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version-selected accessor for block-shape queries that changed signature across versions.
 *
 * <p>Pre-1.20.5 {@code BlockState.isSolidRender(BlockGetter, BlockPos)} required a level +
 * position; later versions dropped them for a context-free {@code isSolidRender()}. Callers
 * always pass level + pos; the version that ignores them simply discards them. Compiled one
 * flavor per MC version, so the call stays monomorphic.
 *
 * <p>This is the MC 1.20.1 era flavor.
 */
public final class BlockShapes {
    private BlockShapes() {}

    public static boolean isSolidRender(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSolidRender(level, pos);
    }
}
