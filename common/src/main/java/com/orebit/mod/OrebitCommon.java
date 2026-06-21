package com.orebit.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.ChunkNavLoader;

/**
 * Loader-agnostic entry point. Each platform module (fabric, neoforge) calls
 * {@link #init(PlatformEvents)} from its native initializer, passing an
 * implementation that bridges that loader's events to ours. All actual behavior
 * lives here and in the rest of {@code common}.
 */
public final class OrebitCommon {
    public static final String MOD_ID = "orebit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile byte sink;

    private OrebitCommon() {}

    public static void init(PlatformEvents events) {
        events.onServerStarted(server -> {
            // Touch NavBlock to trigger its static initializer (builds the block table).
            sink = NavBlock.AIR;
        });

        events.onPlayerJoin(player -> {
            LOGGER.info("[Orebit] Player {} connected.", player.getName().getString());
            BotManager.spawnBotFor(player);
        });

        events.onPlayerDisconnect(player -> {
            LOGGER.info("[Orebit] Player {} disconnected.", player.getName().getString());
            BotManager.removeBotFor(player);
        });

        ChunkNavLoader.register(events);
    }
}
