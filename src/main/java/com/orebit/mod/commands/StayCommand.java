package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.AllyBotEntity;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /bot stay} — stop and hold position until told otherwise. */
public final class StayCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("stay").executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
            b.setMode(AllyBotEntity.Mode.STAY);
            CommandFeedback.send(source, "Bot will stay here.");
        })));
    }
}
