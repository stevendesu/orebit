package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot stats} — a cold, read-only dump of the bot's per-<b>journey</b> search-health telemetry
 * (NAVSTATS): searches / expansions total+max, partial paths, region floods, node-cap hits, skeleton
 * replans, region-hop repairs, invalidated crossings, boundary re-crossings (the thrash signal), and the
 * distance-traveled vs straight-line route efficiency — for the current journey and the last completed one.
 *
 * <p>The complement to the always-on {@code [Orebit][NAVSTATS]} journey-end log line: this shows the LIVE
 * accumulator mid-journey so a flood / boundary thrash / roundabout route is visible without tailing the
 * console or waiting for the goal to resolve. Pure observation — reads {@link
 * com.orebit.mod.AllyBotEntity#navStatsReport()}; changes no bot behaviour. A Strategy {@link BotCommand}
 * like the rest.
 */
public final class StatsCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("stats").executes(ctx -> OrebitCommands.act(ctx, (b, player, src) -> {
            for (String line : b.navStatsReport()) {
                CommandFeedback.send(src, line);
            }
        })));
    }
}
