package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.AllyBotEntity;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /bot follow} — (re)enable continuous auto-follow of the owner. */
public final class FollowCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("follow").executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
            b.setMode(AllyBotEntity.Mode.FOLLOW);
            CommandFeedback.send(source, "Bot is now following you.");
        })));
    }
}
