package com.orebit.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.ChunkNavLoader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;


public class Orebit implements ModInitializer {
	public static final String MOD_ID = "orebit";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	volatile byte sink;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register((server) -> {
			// Accessing a member of NavBlock to cause the class to initialize
			sink = NavBlock.AIR;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			LOGGER.info("[Orebit] Player {} connected.", player.getName());
			BotManager.spawnBotFor(player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			LOGGER.info("[Orebit] Player {} disconnected.", player.getName());
			BotManager.removeBotFor(player);
		});

		ChunkNavLoader.register();
	}
}
