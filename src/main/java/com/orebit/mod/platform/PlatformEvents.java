package com.orebit.mod.platform;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The single loader-specific seam (internal_docs/PRD.md §9). All Fabric/
 * NeoForge event wiring is hidden behind this interface; everything else in the
 * mod is platform-agnostic and lives in {@code common}. Each loader module
 * supplies an implementation that forwards its native events to these callbacks.
 *
 * <p>These are all COLD (once-per-event) hooks, so interface dispatch is noise —
 * the hot per-block paths never go through here.
 */
public interface PlatformEvents {
    void onServerStarted(Consumer<MinecraftServer> callback);

    /**
     * Fires when the server is shutting down (a graceful stop — Fabric {@code SERVER_STOPPING},
     * Forge/NeoForge {@code ServerStoppingEvent}), on the server thread after the tick loop has
     * halted, so a listener can flush per-level state to disk with no concurrent writer. Default
     * no-op so a loader/era not yet wired (and any impl relying on another flush trigger, e.g. the
     * periodic {@code onWorldTickEnd} flush) still compiles — mirroring {@link #onChunkUnload} /
     * {@link #onRegisterCommands}. Used by {@link com.orebit.mod.worldmodel.persistence.RegionPersistence}
     * for the authoritative world-model flush (the primary trigger for the idle-auto-stop restart case).
     */
    default void onServerStopping(Consumer<MinecraftServer> callback) {}

    void onPlayerJoin(Consumer<ServerPlayer> callback);

    void onPlayerDisconnect(Consumer<ServerPlayer> callback);

    void onChunkLoad(BiConsumer<ServerLevel, ChunkAccess> callback);

    /**
     * Fires when a chunk is unloaded. Default no-op so loaders that don't yet wire it (and the
     * 26.x era's own impl) still compile; loaders override it to recycle nav data on unload.
     */
    default void onChunkUnload(BiConsumer<ServerLevel, ChunkAccess> callback) {}

    void onWorldTickEnd(Consumer<ServerLevel> callback);

    /**
     * Fires when the server builds its command tree, handing the common code the Brigadier
     * {@link CommandDispatcher} to register slash commands on (so they dispatch server-side and sync
     * to clients for tab-completion). The dispatcher + {@link CommandSourceStack} are vanilla types
     * stable across the range, so this signature needs no overlay — only each loader's <i>wiring</i>
     * to its native registration event drifts (Fabric command-api v1→v2 at 1.19; Forge's EventBus 7
     * at 1.21.6), which the loader impls/overlays absorb. Default no-op so a loader/era not yet wired
     * (and the 26.x era's own impl) still compiles, mirroring {@link #onChunkUnload}.
     */
    default void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {}
}
