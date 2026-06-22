package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.platform.BotSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class BotManager {
    private static final Map<UUID, AllyBotEntity> botsByOwner = new HashMap<>();

    public static void spawnBotFor(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();
        // Get the server via the level, not Entity.getServer() — the latter was removed
        // from Entity in MC 1.21.9. ServerLevel.getServer() exists on every supported version.
        MinecraftServer server = world.getServer();
        String baseName = player.getName().getString();
        if (baseName.length() > 12) {
            baseName = baseName.substring(0, 12);
        }
        GameProfile profile = new GameProfile(UUID.randomUUID(), baseName + "_bot");

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

        // Spawn the bot the vanilla way. PlayerList.placeNewPlayer runs the real join
        // sequence — it broadcasts the bot's player-info in the correct order, then sets up
        // entity tracking and adds it to the world — so the client always has the player-info
        // before the spawn packet (no "add player prior to sending player info" race) and the
        // bot renders. This replaces the old hand-rolled broadcast + addFreshEntity. The
        // version-specific cookie / placeNewPlayer signature lives in the BotSpawn overlay.
        BotSpawn.place(server, bot);

        botsByOwner.put(player.getUUID(), bot);
        OrebitCommon.LOGGER.info("[Orebit] Spawned bot for {}", player.getName().getString());
    }

    public static void removeBotFor(ServerPlayer player) {
        AllyBotEntity bot = botsByOwner.remove(player.getUUID());
        if (bot != null) {
            removeBot(bot);
            OrebitCommon.LOGGER.info("[Orebit] Removed bot for {}", player.getName().getString());
        }
    }

    public static void removeAllBots() {
        for (AllyBotEntity bot : botsByOwner.values()) {
            if (bot != null) {
                removeBot(bot);
            }
        }
        botsByOwner.clear();
    }

    // The bot is a real PlayerList member (placeNewPlayer), so remove it the vanilla way —
    // this drops it from the list, broadcasts its removal, and despawns the entity. kill() +
    // discard() would despawn the entity but leave a ghost player in the list.
    private static void removeBot(AllyBotEntity bot) {
        // Via the level, not Entity.getServer() (removed in MC 1.21.9).
        MinecraftServer server = ((ServerLevel) bot.level()).getServer();
        if (server != null) {
            server.getPlayerList().remove(bot);
        }
    }
}
