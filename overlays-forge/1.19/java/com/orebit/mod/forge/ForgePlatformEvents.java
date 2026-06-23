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
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

/**
 * Bridges legacy-Forge game-bus events to the loader-agnostic {@link PlatformEvents} seam.
 *
 * <p>Flavor for Forge on MC <b>1.19–1.21.5</b>: the <b>1.19</b> "world→level" rename landed
 * ({@code event.world}→{@code event.level} package, {@code ChunkEvent.getWorld}→{@code getLevel},
 * {@code TickEvent.WorldTickEvent}→{@code LevelTickEvent}/{@code event.level}), on top of the
 * 1.18 {@code event.server.ServerStartedEvent}. Overrides the {@code overlays-forge/1.18} flavor.
 * Still the classic single {@code MinecraftForge.EVENT_BUS} bus — Forge's EventBus 7 rewrite
 * (1.21.6) and the {@code LevelTickEvent} record change (1.21.9) override this again in
 * {@code overlays-forge/1.21.6} and {@code overlays-forge/1.21.9}.
 */
public final class ForgePlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> callback.accept(event.getServer()));
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
            if (event.getLevel() instanceof ServerLevel level) {
                callback.accept(level, event.getChunk());
            }
        });
    }

    @Override
    public void onWorldTickEnd(Consumer<ServerLevel> callback) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
                callback.accept(level);
            }
        });
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> callback.accept(event.getDispatcher()));
    }
}
