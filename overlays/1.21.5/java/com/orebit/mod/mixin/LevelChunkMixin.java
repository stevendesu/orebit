package com.orebit.mod.mixin;

import com.orebit.mod.platform.BlockChangeEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MC <b>1.21.5+</b> flavor of the block-change hook (overrides {@code overlays/1.17}).
 *
 * <p>Identical in intent to the baseline — an {@code @Inject} at RETURN of {@code LevelChunk.setBlockState}
 * forwarding the pre/post {@link BlockState} to {@link BlockChangeEvents} — but the target signature
 * drifted at 1.21.5: the third parameter changed from {@code boolean isMoving} to an {@code int flags}
 * field, and a 2-arg {@code setBlockState(BlockPos, BlockState)} overload was added. The {@code method}
 * selector is therefore the <b>full {@code int} descriptor</b>, which both disambiguates from the new
 * overload and matches the real method. Verified against the intermediary mappings (1.21.5 → 1.21.11:
 * {@code method_12010 = (BlockPos, BlockState, int) -> BlockState}). See the baseline
 * {@code overlays/1.17/.../mixin/LevelChunkMixin} for the full design rationale.
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"))
    private void orebit$onSetBlockState(BlockPos pos, BlockState state, int flags,
                                        CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        if (oldState == null) return; // null return == no change (identical state); nothing to patch
        BlockChangeEvents.fire(((LevelChunk) (Object) this).getLevel(), pos, oldState, state);
    }
}
