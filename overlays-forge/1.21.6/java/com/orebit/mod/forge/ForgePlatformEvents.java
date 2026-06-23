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
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

/**
 * Bridges Forge game-bus events to the loader-agnostic {@link PlatformEvents} seam.
 *
 * <p>Version overlay ({@code overlays-forge/1.21.6}) for Forge on MC <b>1.21.6+</b>. Forge 1.21.6
 * migrated to <b>EventBus 7</b>: the single {@code MinecraftForge.EVENT_BUS.addListener(...)} bus
 * became a per-event {@code Event.BUS} ({@code net.minecraftforge.eventbus.api.bus.EventBus<T>}),
 * registered with {@code EventBus.addListener(Consumer<T>)}. The phase-based
 * {@code TickEvent.LevelTickEvent} also gained nested {@code Pre}/{@code Post} subtypes (each its
 * own bus), so end-of-tick is {@code LevelTickEvent.Post} — no phase check needed. Overrides the
 * legacy {@code overlays-forge/1.20.1} flavor (active 1.20.1–1.21.5).
 *
 * <p>{@code MinecraftForge.EVENT_BUS} survives only as an {@code EventBusMigrationHelper} (no
 * {@code addListener}), which is why the legacy flavor fails to compile on 1.21.6+.
 */
public final class ForgePlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        ServerStartedEvent.BUS.addListener(event -> callback.accept(event.getServer()));
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> callback) {
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                callback.accept(player);
            }
        });
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> callback) {
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                callback.accept(player);
            }
        });
    }

    @Override
    public void onChunkLoad(BiConsumer<ServerLevel, ChunkAccess> callback) {
        ChunkEvent.Load.BUS.addListener(event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                callback.accept(level, event.getChunk());
            }
        });
    }

    @Override
    public void onWorldTickEnd(Consumer<ServerLevel> callback) {
        // LevelTickEvent.Post == end of tick (the old TickEvent.Phase.END); `level` is the
        // inherited public field on LevelTickEvent.
        TickEvent.LevelTickEvent.Post.BUS.addListener(event -> {
            if (event.level instanceof ServerLevel level) {
                callback.accept(level);
            }
        });
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
        RegisterCommandsEvent.BUS.addListener(event -> callback.accept(event.getDispatcher()));
    }
}
