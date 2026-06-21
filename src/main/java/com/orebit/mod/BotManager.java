package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class BotManager {
    private static final Map<UUID, AllyBotEntity> botsByOwner = new HashMap<>();

    public static void spawnBotFor(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ServerLevel world = (ServerLevel) player.level();
        String baseName = player.getName().getString();
        if (baseName.length() > 12) {
            baseName = baseName.substring(0, 12);
        }
        String botName = baseName + "_bot";
        GameProfile profile = new GameProfile(UUID.randomUUID(), botName);

        AllyBotEntity bot = new AllyBotEntity(server, world, profile, player);
        BlockPos safeSpot = BotPositioning.findSafeSpotNear(player, 3);
        if (safeSpot != null) {
            bot.setPos(safeSpot.getX() + 0.5, safeSpot.getY(), safeSpot.getZ() + 0.5);

            BotPositioning.faceEachOther(bot, player);
            BotPositioning.faceEachOther(player, bot);
        } else {
            bot.setPos(player.getX(), player.getY(), player.getZ()); // fallback
        }
        bot.setCustomName(player.getDisplayName().copy().append("'s Bot"));
        bot.setCustomNameVisible(true);

        // Register the bot in every client's player list (its GameProfile) BEFORE adding
        // the entity to the world. Pre-1.20.2 spawns players via the dedicated AddPlayer
        // packet, which the client REJECTS if no player-info arrived first ("Server
        // attempted to add player prior to sending player info"). Broadcasting up front is
        // correct on every version and for every viewer, not just the owner.
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(Action.ADD_PLAYER, bot));

        world.addFreshEntity(bot);

        botsByOwner.put(player.getUUID(), bot);

        OrebitCommon.LOGGER.info("[Orebit] Spawned bot for {}", player.getName().getString());
    }

    public static void removeBotFor(ServerPlayer player) {
        AllyBotEntity bot = botsByOwner.remove(player.getUUID());
        if (bot != null) {
            bot.removeFromWorld();
            OrebitCommon.LOGGER.info("[Orebit] Removed bot for {}", player.getName().getString());
        }
    }

    public static void removeAllBots() {
        for (AllyBotEntity bot : botsByOwner.values()) {
            if (bot != null) {
                bot.removeFromWorld();
            }
        }
        botsByOwner.clear();
    }
}
