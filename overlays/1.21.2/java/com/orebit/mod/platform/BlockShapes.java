package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version-selected accessor for block-shape queries that changed signature across versions.
 *
 * <p>{@code BlockState.isSolidRender(BlockGetter, BlockPos)} required a level + position
 * until <b>1.21.2</b> (walk-back-pinned) dropped them for a context-free
 * {@code isSolidRender()}. Callers always pass level + pos; this version ignores them.
 * Compiled one flavor per MC version, so the call stays monomorphic.
 *
 * <p>This is the no-arg flavor introduced at <b>1.21.2</b> — a version overlay delta that
 * overrides the baseline two-arg {@code overlays/1.20.1} flavor. The overlay eras compose
 * (build.gradle.kts); this file is active for 1.21.2 and up.
 */
public final class BlockShapes {
    private BlockShapes() {}

    public static boolean isSolidRender(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSolidRender();
    }
}
