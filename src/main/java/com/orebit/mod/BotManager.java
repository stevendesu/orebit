package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
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
        ClientInformation options = player.clientInformation();
        GameProfile profile = new GameProfile(UUID.randomUUID(), botName);

        AllyBotEntity bot = new AllyBotEntity(server, world, profile, options, player);
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

        world.addFreshEntity(bot);

        // Make visible to player
        player.connection.send(new ClientboundPlayerInfoUpdatePacket(Action.ADD_PLAYER, bot));

        botsByOwner.put(player.getUUID(), bot);

        OrebitCommon.LOGGER.info("[Orebit] Spawned bot for {}", player.getName().getString());
    }

    public static void removeBotFor(ServerPlayer player) {
        AllyBotEntity bot = botsByOwner.remove(player.getUUID());
        if (bot != null && bot.isAlive()) {
            bot.kill((ServerLevel) bot.level());
            bot.discard();
            OrebitCommon.LOGGER.info("[Orebit] Removed bot for {}", player.getName().getString());
        }
    }

    public static void removeAllBots() {
        for (AllyBotEntity bot : botsByOwner.values()) {
            if (bot != null && bot.isAlive()) {
                bot.kill((ServerLevel) bot.level());
                bot.discard();
            }
        }
        botsByOwner.clear();
    }
}
