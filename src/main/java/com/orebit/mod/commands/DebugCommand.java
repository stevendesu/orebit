package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.Debug;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot debug on|off} (or bare {@code /bot debug} to toggle) — flip {@link Debug#ENABLED}, the single
 * switch behind the bot's runtime path/region/window logs, particle overlay, and chat progress. Server-wide
 * (the flag is static), so it doesn't route through {@link OrebitCommands#act} — no bot needed to turn the
 * diagnostics on. A Strategy {@link BotCommand} like the rest.
 */
public final class DebugCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("debug")
                .then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)))
                .executes(ctx -> set(ctx.getSource(), !Debug.ENABLED)));
    }

    private static int set(CommandSourceStack source, boolean on) {
        Debug.ENABLED = on;
        CommandFeedback.send(source, "Bot debug " + (on ? "ON" : "OFF") + ".");
        return 1;
    }
}
