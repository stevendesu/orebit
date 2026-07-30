package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot farm} — one tending pass over the farm around the bot (DESIGN-bot-abilities.md §4):
 * harvest every fully-grown crop in {@code farming.workRadius}, replant from carried/collected
 * seeds, plant bare farmland, and (with a hoe, {@code farming.till} allowing) till hydrated
 * ground and plant that too. Reports the harvest/plant/till tally and holds when nothing
 * actionable remains. Hands off to {@link com.orebit.mod.AllyBotEntity#startFarm}, the
 * {@code FARM}-mode state machine.
 */
public final class FarmCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("farm")
                .executes(ctx -> OrebitCommands.act(ctx, (b, player, src) -> {
                    b.startFarm();
                    CommandFeedback.send(src, "farming around " + b.blockPosition().getX()
                            + "," + b.blockPosition().getY() + "," + b.blockPosition().getZ() + "…");
                })));
    }
}
