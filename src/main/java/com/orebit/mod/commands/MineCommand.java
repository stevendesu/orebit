package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;

/**
 * {@code /bot mine <x> <y> <z>} — Stage-1 verification of the timed mining actuator ({@link
 * com.orebit.mod.BotMining}): the bot stops and digs the one block at {@code pos} the way a real player does —
 * fastest held tool equipped, arm swing, the crack overlay building over the REAL number of ticks vanilla
 * mining takes, then a survival break with proper drops (which the bot walks over and picks up). This exercises
 * the "hands" in isolation before the movement reconcile (Stage 2) drives them, so we can confirm the mechanic
 * (correct tool, correct duration, drops, overlay) with no pathfinding in the way.
 *
 * <p>Same coordinate handling as {@link GotoCommand}: space-separated with {@code ~}/{@code ^} relative support.
 */
public final class MineCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("mine")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            BlockPos target = ctx.getArgument("pos", Coordinates.class)
                                    .getBlockPos(ctx.getSource());
                            return OrebitCommands.act(ctx, (b, player, source) -> {
                                b.debugMineAt(target);
                                CommandFeedback.send(source, "Bot mining "
                                        + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
                            });
                        })));
    }
}
