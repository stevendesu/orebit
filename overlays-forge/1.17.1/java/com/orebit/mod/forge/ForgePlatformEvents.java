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
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;

/**
 * Bridges legacy-Forge game-bus events to the loader-agnostic {@link PlatformEvents} seam.
 *
 * <p>OLDEST baseline flavor for Forge on MC <b>1.17.1</b>. Two things differ from later eras:
 * server-started is the legacy {@code net.minecraftforge.fmlserverevents.FMLServerStartedEvent}
 * (Forge moved it to {@code event.server.ServerStartedEvent} at 1.18 → {@code overlays-forge/1.18}),
 * and the "world→level" rename had not happened, so chunk/tick events are
 * {@code event.world.ChunkEvent} ({@code getWorld()}) and {@code TickEvent.WorldTickEvent}
 * ({@code event.world}) — renamed at 1.19 ({@code overlays-forge/1.19}). The overlay eras compose
 * via {@code applyVersionOverlays} in {@code forge/build.gradle.kts}. {@code FMLServerStartedEvent}
 * is a forge-bus ({@code MinecraftForge.EVENT_BUS}) event despite the FML name.
 */
public final class ForgePlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        MinecraftForge.EVENT_BUS.addListener((FMLServerStartedEvent event) -> callback.accept(event.getServer()));
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
