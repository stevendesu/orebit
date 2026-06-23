package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot come} — path once to the caller's current cell, then hold there ({@link
 * com.orebit.mod.AllyBotEntity.Mode#COME} settles into {@code STAY} on arrival). Distinct from
 * {@code follow}, which keeps chasing — this is "fetch the bot to me and have it wait."
 */
public final class ComeCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("come").executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
            b.comeTo(player.blockPosition());
            CommandFeedback.send(source, "Bot is coming to you.");
        })));
    }
}
