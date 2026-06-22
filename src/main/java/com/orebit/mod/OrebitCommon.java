package com.orebit.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.server.level.ServerLevel;

/**
 * Loader-agnostic entry point. Each platform module (fabric, forge, neoforge) calls
 * {@link #init(PlatformEvents)} from its native initializer, passing an implementation
 * that bridges that loader's events to ours.
 */
public final class OrebitCommon {
    public static final String MOD_ID = "orebit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private OrebitCommon() {}

    public static void init(PlatformEvents events) {
        // NOTE: the world-model pipeline (the NavBlock table + per-chunk NavSection build)
        // is intentionally NOT engaged at runtime yet. It is prototype code that currently
        // produces no stored data (ChunkNavLoader discards its output, PRD §2) and is
        // version-fragile: NavBlock's byte index overflows on several MC versions ("Too many
        // blocks registered for mode 3"), and NavSectionBuilder reflects into
        // PalettedContainer internals. Engaging it only added cross-version crash surface
        // with no functional benefit. It will be wired up in Phase 1 (short-index NavBlock +
        // a version-correct section reader). Until then we run only the bot lifecycle, so the
        // bot works on every supported loader×version.

        events.onPlayerJoin(player -> {
            // CRITICAL: placeNewPlayer makes the bot a real PlayerList member, so the join
            // event fires for the bot too. Without this guard, spawning a bot spawns a bot
            // for the bot, ad infinitum (OOM). Never spawn a bot for one of our own bots.
            if (player instanceof FakePlayerEntity) {
                return;
            }
            LOGGER.info("[Orebit] Player {} connected.", player.getName().getString());
            // Defer the spawn to the next server tick. Since 1.20.2 the join handshake gained
            // a configuration phase, so JOIN fires while the client is still entering the PLAY
            // phase; spawning immediately races that transition.
            // Server via the level — Entity.getServer() was removed in MC 1.21.9.
            ((ServerLevel) player.level()).getServer().execute(() -> BotManager.spawnBotFor(player));
        });

        events.onPlayerDisconnect(player -> {
            if (player instanceof FakePlayerEntity) {
                return; // our own bot leaving — nothing to clean up for it
            }
            LOGGER.info("[Orebit] Player {} disconnected.", player.getName().getString());
            BotManager.removeBotFor(player);
        });
    }
}
