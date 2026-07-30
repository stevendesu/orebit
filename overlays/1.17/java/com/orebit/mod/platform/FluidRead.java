package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/**
 * Version-selected fluid-state read for the farming hydration test (DESIGN-bot-abilities.md §4):
 * whether the cell at {@code pos} holds ANY water fluid — source, flowing, or a waterlogged
 * block (vanilla farmland's own {@code isNearWater} sweep is exactly this test over its 9×9×2
 * box; the box loop stays in core, only the read is version-selected).
 *
 * <p><b>This 1.17 baseline</b> (MC 1.17.1 → 1.21.11): {@code getFluidState(pos).is(FluidTags.WATER)}.
 * The 1.18.2 tag refactor changed the parameter type ({@code Tag} → {@code TagKey}) IN LOCKSTEP
 * with the {@code FluidTags.WATER} constant, so this one source file compiles on both sides.
 * Overridden at <b>26</b> (26.x removed {@code FluidState#is(TagKey)} — the tag test moved to the
 * fluid's holder).
 */
public final class FluidRead {

    private FluidRead() {}

    /** Whether {@code pos} holds any water fluid (source/flowing/waterlogged). */
    public static boolean isWater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }
}
