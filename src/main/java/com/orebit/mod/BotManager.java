package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.platform.BotSpawn;
import com.orebit.mod.platform.BotTeleport;
import com.orebit.mod.platform.ClientLoad;
import com.orebit.mod.platform.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // player, whose UUID vanilla derives as nameUUIDFromBytes("OfflinePlayer:"+name). When the OWNER'S
        // identity itself changed (offline-mode rename, dev runClient's random "Player###" name), the
        // derived UUID orphans the old saved data — resolveBotUuid consults the on-disk bot registry and
        // adopts the world's known bot UUID in the single-bot case, so the inventory survives that too.
        GameProfile profile = new GameProfile(
                resolveBotUuid(server, player.getUUID(), player.getName().getString()),
                baseName + "_bot");

        AllyBotEntity bot = new AllyBotEntity(server, world, profile, player);
        // Position beside the owner BEFORE place so a brand-new bot's spawn packet is already at the right
        // spot. For a FRESH bot (no saved data) this position sticks; for a RETURNING bot the saved-data
        // load inside BotSpawn.place overwrites it with the logout position, and the placeNearOwner
        // re-snap after place corrects that.
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
        // BotSpawn.place also restores a RETURNING bot's saved <uuid>.dat (inventory, tools, XP,
        // logout position): on ≤1.21.8 placeNewPlayer loads it internally; on 1.21.9+ the load
        // moved into vanilla's login flow (which a fake player never runs), so the overlays/1.21.9
        // flavor replays it — loadPlayerData(nameAndId) → Entity.load(ValueInput) — before place.
        BotSpawn.place(server, bot);

        // Respawn semantics: /bot spawn exists to bring back a DEAD bot, but BotSpawn.place just restored the
        // bot's saved <uuid>.dat — which, for a bot that died, carries Health=0 (LivingEntity persists Health).
        // A clientless bot can't drive the vanilla death-screen respawn, so without a revive it comes back dead
        // and dies again on its first tick (the white "poof"). Vanilla's own respawn builds a FRESH entity and
        // calls ServerPlayer.restoreFrom(old, keepAll=false), whose death branch is literally
        // setHealth(getMaxHealth()); reviveIfDead() applies that same operation (a no-op for a live/returning
        // bot). See PlayerList.respawn.
        //
        // On 1.21.9+ (incl. 26.x) the overlays/1.21.9 BotSpawn already revived the bot BEFORE placeNewPlayer —
        // load-bearing, because placeNewPlayer snapshots the spawn metadata synchronously, so a Health=0 there
        // reaches observers as the initial value and the client renders the death animation (the intermittent
        // red/twitch). So this call is a no-op there (health already restored). It remains the revive for the
        // ≤1.21.8 flavors, whose placeNewPlayer loads the .dat INTERNALLY (no seam to revive before the spawn
        // snapshot without a mixin); on those versions the server state is correct (no poof) even though the
        // pre-1.21.9 spawn-metadata race is not addressed here.
        bot.reviveIfDead();

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

        // "Return to owner" on rejoin: the saved-data load in BotSpawn.place may have restored the bot to
        // its LOGOUT position — possibly in ANOTHER DIMENSION (≤1.21.8 only: placeNewPlayer's internal
        // load switches the level from the tag's Dimension; the 1.21.9+ flavor's Entity.load(ValueInput)
        // restores position but never changes level, so there this branch stays dormant and the re-snap
        // below is all that runs). A cross-dimension return needs a real player teleport
        // first (setPos is same-level by construction; a bare setServerLevel pointer flip = ghost entity),
        // so bridge the level gap via the version-selected BotTeleport seam, then re-snap beside the player
        // unconditionally — the restored inventory rides along either way.
        if (Worlds.of(bot) != world) {
            OrebitCommon.LOGGER.info("[Orebit] Bot for {} logged out in another dimension ({}) — teleporting it back to its owner.",
                    player.getName().getString(), Worlds.of(bot));
            BotTeleport.to(bot, world, player.getX(), player.getY(), player.getZ(), bot.getYRot(), bot.getXRot());
            // A respawn-style teleport resets connection.hasClientLoaded() on 1.21.11+, and a clientless
            // bot never re-sends the signal — re-arm it here (idempotent; the bot's own post-tick level-
            // change detection also re-arms, but this teleport runs OUTSIDE the bot tick, so don't rely
            // on ordering). Same fix as the markLoaded call after BotSpawn.place above.
            ClientLoad.markLoaded(bot);
        }
        placeNearOwner(bot, player, safeSpot);

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

    // ---- Bot UUID registry (orphan adoption) --------------------------------------------------------
    //
    // botUuidFor is a pure function of the OWNER'S UUID — which in offline mode is a pure function of
    // the owner's NAME. A dev runClient (random "Player###" per launch) or an offline-server rename
    // therefore derives a brand-new bot UUID, and placeNewPlayer loads fresh empty player data while
    // the real bot .dat (with the diamonds) sits orphaned under the old UUID. The registry is a tiny
    // sidecar properties file in the world save — one line per known bot, `<botUuid>=<ownerName>` —
    // that lets spawn ADOPT the world's existing bot when the derivation misses. All I/O here is cold
    // (once per spawn) and defensive (missing/corrupt file = empty registry).

    /**
     * The bot UUID to spawn with. Normally the deterministic {@link #botUuidFor} derivation; but when
     * that UUID has NO saved player data and the world's bot registry lists exactly ONE bot, adopt the
     * registered UUID instead — the single-bot V1 case where the owner's identity changed (dev relaunch,
     * offline rename) but the world unambiguously has one Orebit bot whose inventory should survive.
     * Several registered bots (future V2) = no guessing, fall back to the derivation. Either way the
     * chosen UUID is recorded in the registry so the NEXT identity change can adopt it.
     *
     * <p>Adoption is gated to the INTEGRATED (single-player/LAN-host) server: on a dedicated server a
     * brand-new player's first join also hits "derived UUID has no data", and with one registered bot
     * the adoption would hand them another owner's bot — inventory theft. The identity-drift bug this
     * fixes (dev runClient's random name) only exists on the integrated server anyway; a dedicated
     * offline server that renames a player keeps the derivation (bot re-earns its kit).
     */
    private static UUID resolveBotUuid(MinecraftServer server, UUID ownerUuid, String ownerName) {
        UUID chosen = botUuidFor(ownerUuid);
        Properties registry = loadBotRegistry(server);
        if (server.isSingleplayer() && !playerDataExists(server, chosen) && registry.size() == 1) {
            String key = registry.stringPropertyNames().iterator().next();
            try {
                UUID adopted = UUID.fromString(key.trim());
                if (!adopted.equals(chosen)) {
                    OrebitCommon.LOGGER.info(
                            "[Orebit] No saved data for derived bot UUID {} — adopting this world's registered bot {} "
                                    + "(created for '{}') for {}; the owner's identity changed (offline rename or dev relaunch).",
                            chosen, adopted, registry.getProperty(key), ownerName);
                    chosen = adopted;
                }
            } catch (IllegalArgumentException e) {
                OrebitCommon.LOGGER.warn("[Orebit] Ignoring corrupt bot-registry entry '{}'.", key);
            }
        }
        if (!ownerName.equals(registry.getProperty(chosen.toString()))) {
            registry.setProperty(chosen.toString(), ownerName);
            saveBotRegistry(server, registry);
        }
        return chosen;
    }

    /**
     * Whether vanilla has saved player data for {@code uuid}. LevelResource.PLAYER_DATA_DIR tracks the
     * on-disk rename itself (javap-verified: {@code "playerdata"} on 1.17.1→1.21.11, {@code "players/data"}
     * on 26.x), and the per-player file is {@code <uuid>.dat} on every supported version — so this resolve
     * is correct across the whole range with no per-era seam.
     */
    private static boolean playerDataExists(MinecraftServer server, UUID uuid) {
        return Files.isRegularFile(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"));
    }

    /** {@code <world>/orebit-bots.properties} — LevelResource.ROOT is stable across 1.17 → 26.x (mojmap-verified). */
    private static Path botRegistryPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("orebit-bots.properties");
    }

    private static Properties loadBotRegistry(MinecraftServer server) {
        Properties registry = new Properties();
        Path path = botRegistryPath(server);
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                registry.load(in);
            } catch (IOException | IllegalArgumentException e) {
                OrebitCommon.LOGGER.warn("[Orebit] Could not read bot registry {} — treating it as empty.", path, e);
                registry.clear();
            }
        }
        return registry;
    }

    private static void saveBotRegistry(MinecraftServer server, Properties registry) {
        Path path = botRegistryPath(server);
        try (OutputStream out = Files.newOutputStream(path)) {
            registry.store(out, "Orebit bot registry: <botUuid>=<owner name at creation>. Lets a respawn adopt the world's existing bot when the owner's offline UUID changes.");
        } catch (IOException e) {
            OrebitCommon.LOGGER.warn("[Orebit] Could not write bot registry {}.", path, e);
        }
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
            // Never PERSIST a dead bot. PlayerList.remove SAVES the bot's <uuid>.dat on the way out; a dead
            // corpse would write Health=0, and the NEXT spawn's synchronous metadata snapshot (inside
            // placeNewPlayer — see BotSpawn/overlays 1.21.9) would then reach observers as Health=0, driving
            // the intermittent client death-render (red/tilt/twitch). Reviving here means the .dat always
            // carries full health, which fixes the snapshot on EVERY version — including ≤1.21.8, whose
            // placeNewPlayer loads the .dat INTERNALLY (no load-time seam to revive without a mixin). No-op
            // for a live bot. The load-side reviveIfDead (spawnBotFor + the 1.21.9 overlay) stays as defence
            // for a .dat that went to disk dead by another route (e.g. an autosave during the ~1s death window
            // then a crash, so this clean-removal save never ran).
            bot.reviveIfDead();
            server.getPlayerList().remove(bot);
        }
    }
}
