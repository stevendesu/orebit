package com.orebit.mod.forge;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;

import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * Bridges legacy-Forge game-bus events to the loader-agnostic {@link PlatformEvents} seam.
 *
 * <p>Flavor for Forge on MC <b>1.18–1.18.2</b>: <b>1.18</b> moved the server-lifecycle events to
 * {@code net.minecraftforge.event.server.ServerStartedEvent} (1.17.1 still had the legacy
 * {@code fmlserverevents.FMLServerStartedEvent} — see {@code overlays-forge/1.17.1}), but the
 * "world→level" rename had NOT happened yet, so chunk/tick events are still
 * {@code event.world.ChunkEvent} ({@code getWorld()}) and {@code TickEvent.WorldTickEvent}
 * ({@code event.world}). Overridden at {@code overlays-forge/1.19} (the world→level rename).
 */
public final class ForgePlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> callback.accept(event.getServer()));
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> callback) {
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> callback.accept(event.getServer()));
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> callback) {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                callback.accept(player);
            }
        });
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> callback) {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                callback.accept(player);
            }
        });
    }

    @Override
    public void onChunkLoad(BiConsumer<ServerLevel, ChunkAccess> callback) {
        MinecraftForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getWorld() instanceof ServerLevel level) {
                callback.accept(level, event.getChunk());
            }
        });
    }

    @Override
    public void onWorldTickEnd(Consumer<ServerLevel> callback) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.WorldTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END && event.world instanceof ServerLevel level) {
                callback.accept(level);
            }
        });
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> callback.accept(event.getDispatcher()));
    }
}
