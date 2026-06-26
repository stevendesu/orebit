package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot config reload} — re-read {@code config/orebit.properties} from disk and install it as the
 * active config without restarting the server (PRD §10 Phase 1a). The new {@link Config} (and its derived
 * {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps}) take effect on the bot's next plan, since the
 * follower reads the live {@link ConfigLoader} cache per replan. Off any hot path — a file read on the
 * command thread.
 *
 * <p>Unlike the simple per-bot commands ({@link FollowCommand} etc.) this acts on the server-wide config,
 * not the caller's bot, so it doesn't route through {@link OrebitCommands#act}; it resolves the {@link
 * net.minecraft.server.MinecraftServer} from the command source and reloads directly. A Strategy {@link
 * BotCommand} like the rest — adding it is this class plus one line in {@link OrebitCommands}.
 */
public final class ConfigCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("config")
                .then(Commands.literal("reload").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    Config c = ConfigLoader.reload(source.getServer());
                    CommandFeedback.send(source, "Orebit config reloaded "
                            + "(maxNodes=" + c.maxNodes() + ", greedyWeight=" + c.greedyWeight()
                            + ", canMine=" + c.canMine() + ", canPlace=" + c.canPlace() + ").");
                    return 1;
                })));
    }
}
