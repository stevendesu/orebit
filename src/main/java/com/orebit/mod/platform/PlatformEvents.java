package com.orebit.mod.platform;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The single loader-specific seam (internal_docs/PRD.md §9 / PORTABILITY-AUDIT.md). All Fabric/
 * NeoForge event wiring is hidden behind this interface; everything else in the
 * mod is platform-agnostic and lives in {@code common}. Each loader module
 * supplies an implementation that forwards its native events to these callbacks.
 *
 * <p>These are all COLD (once-per-event) hooks, so interface dispatch is noise —
 * the hot per-block paths never go through here.
 */
public interface PlatformEvents {
    void onServerStarted(Consumer<MinecraftServer> callback);

    void onPlayerJoin(Consumer<ServerPlayer> callback);

    void onPlayerDisconnect(Consumer<ServerPlayer> callback);

    void onChunkLoad(BiConsumer<ServerLevel, ChunkAccess> callback);

    /**
     * Fires when a chunk is unloaded. Default no-op so loaders that don't yet wire it (and the
     * 26.x era's own impl) still compile; loaders override it to recycle nav data on unload.
     */
    default void onChunkUnload(BiConsumer<ServerLevel, ChunkAccess> callback) {}

    void onWorldTickEnd(Consumer<ServerLevel> callback);
}
