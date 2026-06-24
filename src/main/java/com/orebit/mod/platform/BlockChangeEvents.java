package com.orebit.mod.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block-change seam. No loader supplies a universal "any block changed" event, so the one chokepoint
 * every change flows through — {@code LevelChunk.setBlockState} — is intercepted by a <b>mixin</b>. That
 * mixin is the single version-fragile, drift-prone piece, so it lives in {@code overlays/<era>/…/mixin/}
 * and does nothing but call {@link #fire}; all the real logic registers here as a {@link Listener} in
 * common code. This keeps the coremod surface to one tiny forwarding {@code @Inject} per signature era.
 *
 * <p>{@code setBlockState} can run off the main thread (world generation), so the listener list is
 * copy-on-write and listeners must be thread-safe about what they touch.
 */
public final class BlockChangeEvents {
    private BlockChangeEvents() {}

    /** A consumer of block changes. {@code oldState}/{@code newState} are the pre/post states at {@code pos}. */
    @FunctionalInterface
    public interface Listener {
        void onChange(Level level, BlockPos pos, BlockState oldState, BlockState newState);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** Register a listener (once, at init). */
    public static void register(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Invoked by the {@code setBlockState} mixin (overlay) for every block change. */
    public static void fire(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (LISTENERS.isEmpty()) return;
        for (Listener l : LISTENERS) {
            l.onChange(level, pos, oldState, newState);
        }
    }
}
