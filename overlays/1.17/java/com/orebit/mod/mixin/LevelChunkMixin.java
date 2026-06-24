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
 * OLDEST baseline (MC 1.17.1+): the project's first and only mixin — the block-change chokepoint.
 *
 * <p>No loader exposes a universal "any block changed" event, so we intercept the one method every
 * block change in a chunk flows through — {@code LevelChunk.setBlockState(BlockPos, BlockState, boolean)},
 * which returns the <b>previous</b> {@link BlockState} (or {@code null} when nothing changed) — and
 * forward to the common {@link BlockChangeEvents} seam. All real logic (the nav-grid patcher) registers
 * there in common code; this class stays dumb on purpose so the version-fragile coremod surface is one
 * tiny {@code @Inject} per signature era. It lives in {@code overlays/<era>/…/mixin/} because the target
 * signature drifts and a mixin <i>compiles regardless of whether its target is right</i> (it resolves at
 * class-load), so only a per-version source check + runtime verification can catch drift.
 *
 * <p><b>Signature era (verified against the intermediary mappings):</b> this baseline targets the
 * {@code (BlockPos, BlockState, boolean isMoving)} form, which holds for MC <b>1.17.1 → 1.21.4</b>. At
 * <b>1.21.5</b> the third parameter became an {@code int} flags field (and a 2-arg overload appeared,
 * making a name-only selector ambiguous) — so {@code overlays/1.21.5} overrides this with an {@code int}
 * flavor. The {@code method} selector is the <b>full descriptor</b> (not name-only) precisely to stay
 * unambiguous across that overload split; loom's mixin remapper rewrites it to runtime names.
 *
 * <p>No server-side guard here: {@code setBlockState} runs for both client and server levels (and
 * off-thread during worldgen). {@link com.orebit.mod.worldmodel.pathing.NavGridUpdater} already filters
 * to {@code ServerLevel}, and {@link BlockChangeEvents}'s listener list is copy-on-write — so the mixin
 * forwards every change unconditionally and lets the listener decide.
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"))
    private void orebit$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                        CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        if (oldState == null) return; // null return == no change (identical state); nothing to patch
        BlockChangeEvents.fire(((LevelChunk) (Object) this).getLevel(), pos, oldState, state);
    }
}
