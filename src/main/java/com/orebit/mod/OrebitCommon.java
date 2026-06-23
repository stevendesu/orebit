package com.orebit.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orebit.mod.commands.OrebitCommands;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.pathing.ChunkNavLoader;

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
        // World-model pipeline (PRD Phase 1): recompute a per-chunk nav grid on load and store it
        // (NavStore), recycling on unload. NavBlock is now a short-indexed packed-long table (the
        // old byte index overflowed) and the section reader is the self-degrading SectionPalette, so
        // this is safe across loaders/versions. Builds are deferred to the tick thread and budgeted
        // (ChunkNavLoader.MAX_BUILDS_PER_TICK) so idle players take no measurable hit. Nothing
        // consumes the grid yet — the pathfinder is the next milestone.
        ChunkNavLoader.register(events);

        // Deterministic /bot come|stay|follow|here command surface (no LLM). The common command tree
        // builds on vanilla Brigadier; the loader seam only translates WHEN registration fires.
        OrebitCommands.register(events);

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
            ((ServerLevel) Worlds.of(player)).getServer().execute(() -> BotManager.spawnBotFor(player));
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
