package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.platform.BotSpawn;
import com.orebit.mod.platform.ClientLoad;
import com.orebit.mod.platform.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class BotManager {
    private static final Map<UUID, AllyBotEntity> botsByOwner = new HashMap<>();

    public static void spawnBotFor(ServerPlayer player) {
        ServerLevel world = (ServerLevel) Worlds.of(player);
        // Get the server via the level, not Entity.getServer() — the latter was removed
        // from Entity in MC 1.21.9. ServerLevel.getServer() exists on every supported version.
        MinecraftServer server = world.getServer();
        String baseName = player.getName().getString();
        if (baseName.length() > 12) {
            baseName = baseName.substring(0, 12);
        }
        // STABLE per-owner identity (not random): the bot is a real PlayerList member, so vanilla
        // saves/loads its data under this UUID. A deterministic UUID makes the bot's saved player data
        // (inventory, tools, XP) round-trip across sessions — mine diamonds, log out, log back in, they're
        // still on the bot. Salted with a bot-specific prefix so it can never collide with a real (offline)
        // player, whose UUID vanilla derives as nameUUIDFromBytes("OfflinePlayer:"+name).
        GameProfile profile = new GameProfile(botUuidFor(player.getUUID()), baseName + "_bot");

        AllyBotEntity bot = new AllyBotEntity(server, world, profile, player);
        // Position beside the owner BEFORE place so a brand-new bot's spawn packet is already at the right
        // spot (a fresh bot has no saved data, so this position sticks).
        BlockPos safeSpot = BotPositioning.findSafeSpotNear(player, 3);
        placeNearOwner(bot, player, safeSpot);
        bot.setCustomName(player.getDisplayName().copy().append("'s Bot"));
        bot.setCustomNameVisible(true);

        // Spawn the bot the vanilla way. PlayerList.placeNewPlayer runs the real join
        // sequence — it broadcasts the bot's player-info in the correct order, then sets up
        // entity tracking and adds it to the world — so the client always has the player-info
        // before the spawn packet (no "add player prior to sending player info" race) and the
        // bot renders. This replaces the old hand-rolled broadcast + addFreshEntity. The
        // version-specific cookie / placeNewPlayer signature lives in the BotSpawn overlay.
        // placeNewPlayer also runs vanilla's load(<uuid>.dat): a RETURNING bot's saved inventory,
        // tools and XP (and its logout position/dimension) are restored right here.
        BotSpawn.place(server, bot);

        // Complete the join the way a real client would: mark the bot's connection "client-loaded". As of
        // 1.21.11, ServerPlayer.isInvulnerableTo() returns true while connection.hasClientLoaded() is false
        // (the world-streaming grace period) — and a clientless fake player never sends that signal, so
        // without this it stays PERMANENTLY invulnerable (no damage / knockback from anything). No-op on
        // versions with no such gate (≤ 1.21.10). Runs on the server thread, so the packet's thread-check
        // passes and the flag flips synchronously.
        ClientLoad.markLoaded(bot);

        // Always a SURVIVAL player. placeNewPlayer inherits the world's default game mode, so in a creative
        // world the bot would be creative — and creative's abilities.invulnerable makes it immune to ALL
        // damage regardless of our per-tick setInvulnerable(!takesDamage), so survival.takesDamage would do
        // nothing. Forcing survival makes the bot a real mortal player whose damage is then governed solely by
        // our entity-level invulnerability flag (and matches the "feels like a survival player" goal: no fly,
        // no instant-break, takes fall/lava/drown damage when takesDamage is on).
        bot.setGameMode(GameType.SURVIVAL);

        // "Return to owner" on rejoin: the load in placeNewPlayer may have restored the bot to its LOGOUT
        // position — re-snap it beside the player so a returning bot comes back to you, keeping the restored
        // inventory. A cross-dimension restore (bot logged out in another dimension) can't be snapped without
        // a teleport, so leave it there; the owner can /bot come (portal-seek) to retrieve it. Same-dimension
        // is the common case.
        if (Worlds.of(bot) == world) {
            placeNearOwner(bot, player, safeSpot);
        } else {
            OrebitCommon.LOGGER.info("[Orebit] Restored bot for {} in a different dimension ({}) — /bot come to retrieve it.",
                    player.getName().getString(), Worlds.of(bot));
        }

        botsByOwner.put(player.getUUID(), bot);
        OrebitCommon.LOGGER.info("[Orebit] Spawned bot for {}", player.getName().getString());
    }

    /** Place the bot beside its owner (a safe spot near the player, else the player's own cell), facing them. */
    private static void placeNearOwner(AllyBotEntity bot, ServerPlayer player, BlockPos safeSpot) {
        if (safeSpot != null) {
            bot.setPos(safeSpot.getX() + 0.5, safeSpot.getY(), safeSpot.getZ() + 0.5);
            BotPositioning.faceEachOther(bot, player);
            BotPositioning.faceEachOther(player, bot);
        } else {
            bot.setPos(player.getX(), player.getY(), player.getZ()); // fallback
        }
    }

    /**
     * The deterministic per-owner bot UUID so the bot's saved player data (inventory, tools, XP) persists
     * across sessions. Salted with {@code "OrebitBot:"} so it can never collide with a real offline player
     * UUID (vanilla derives those as {@code nameUUIDFromBytes("OfflinePlayer:"+name)}).
     */
    private static UUID botUuidFor(UUID ownerUuid) {
        return UUID.nameUUIDFromBytes(("OrebitBot:" + ownerUuid).getBytes(StandardCharsets.UTF_8));
    }

    /** The bot owned by {@code player}, or {@code null} if they have none (used by the /bot commands). */
    public static AllyBotEntity botFor(ServerPlayer player) {
        return botsByOwner.get(player.getUUID());
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
        MinecraftServer server = ((ServerLevel) Worlds.of(bot)).getServer();
        if (server != null) {
            server.getPlayerList().remove(bot);
        }
    }
}
