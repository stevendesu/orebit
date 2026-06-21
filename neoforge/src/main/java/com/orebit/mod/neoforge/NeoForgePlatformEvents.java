package com.orebit.mod.neoforge;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Bridges NeoForge game-bus events to the loader-agnostic {@link PlatformEvents} seam. */
public final class NeoForgePlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> callback.accept(event.getServer()));
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> callback) {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                callback.accept(player);
            }
        });
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> callback) {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                callback.accept(player);
            }
        });
    }

    @Override
    public void onChunkLoad(BiConsumer<ServerLevel, ChunkAccess> callback) {
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                callback.accept(level, event.getChunk());
            }
        });
    }

    @Override
    public void onWorldTickEnd(Consumer<ServerLevel> callback) {
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                callback.accept(level);
            }
        });
    }
}
