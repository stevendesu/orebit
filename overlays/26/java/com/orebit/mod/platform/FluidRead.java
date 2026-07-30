package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/**
 * {@link FluidRead} flavor for MC 26.x: {@code FluidState#is(TagKey)} is gone (javap-pinned) —
 * the tag membership test rides the fluid's {@code Holder} ({@code typeHolder().is(...)}).
 * See the 1.17 baseline for the contract.
 */
public final class FluidRead {

    private FluidRead() {}

    /** Whether {@code pos} holds any water fluid (source/flowing/waterlogged). */
    public static boolean isWater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).typeHolder().is(FluidTags.WATER);
    }
}
