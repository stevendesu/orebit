package com.orebit.mod.fabric;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.orebit.mod.platform.PlatformEvents;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Bridges Fabric API events to the loader-agnostic {@link PlatformEvents} seam. */
public final class FabricPlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        ServerLifecycleEvents.SERVER_STARTED.register(callback::accept);
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> callback) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> callback.accept(handler.getPlayer()));
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> callback) {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> callback.accept(handler.getPlayer()));
    }

    @Override
    public void onChunkLoad(BiConsumer<ServerLevel, ChunkAccess> callback) {
        // MC 26.1: ServerChunkEvents.Load gained a third arg (newlyGenerated boolean).
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newlyGenerated) -> callback.accept(world, chunk));
    }

    @Override
    public void onChunkUnload(BiConsumer<ServerLevel, ChunkAccess> callback) {
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> callback.accept(world, chunk));
    }

    @Override
    public void onWorldTickEnd(Consumer<ServerLevel> callback) {
        // MC 26.1: END_WORLD_TICK was renamed END_LEVEL_TICK (the world->level rename).
        ServerTickEvents.END_LEVEL_TICK.register(callback::accept);
    }
}
