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
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * Bridges Forge game-bus events to the loader-agnostic {@link PlatformEvents} seam.
 *
 * <p>Version overlay ({@code overlays-forge/1.21.9}) for Forge on MC <b>1.21.9+</b>. Same
 * EventBus 7 per-event {@code Event.BUS.addListener(...)} model as the {@code overlays-forge/1.21.6}
 * flavor, with ONE change: in 1.21.9 {@code TickEvent.LevelTickEvent} became an interface/record,
 * so its level is read via the accessor {@code event.level()} rather than the public
 * {@code event.level} field that existed in 1.21.6–1.21.8. Overrides the 1.21.6 flavor for
 * 1.21.9 and up.
 */
public final class ForgePlatformEvents implements PlatformEvents {

    @Override
    public void onServerStarted(Consumer<MinecraftServer> callback) {
        ServerStartedEvent.BUS.addListener(event -> callback.accept(event.getServer()));
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> callback) {
        ServerStoppingEvent.BUS.addListener(event -> callback.accept(event.getServer()));
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
        // LevelTickEvent.Post == end of tick. As of 1.21.9 LevelTickEvent is an interface/record,
        // so the level is read via the accessor level() (was the public `level` field pre-1.21.9).
        TickEvent.LevelTickEvent.Post.BUS.addListener(event -> {
            if (event.level() instanceof ServerLevel level) {
                callback.accept(level);
            }
        });
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
        RegisterCommandsEvent.BUS.addListener(event -> callback.accept(event.getDispatcher()));
    }
}

