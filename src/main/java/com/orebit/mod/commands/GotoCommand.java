package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;

/**
 * {@code /bot goto <x> <y> <z>} — path once to an arbitrary world cell, then hold there: exactly {@code come},
 * but to fixed coordinates instead of the caller's position ({@link com.orebit.mod.AllyBotEntity#comeTo}).
 *
 * <p>The point is <b>following a long route</b>: the path debug overlay only renders near a player, so to watch
 * the bot on a multi-thousand-block journey you leave your body parked and roam with a spectator/freecam — but
 * {@code come} drags the goal to wherever you are. {@code goto} fixes the goal so you can fly off and observe.
 * Space-separated coords (vanilla {@code /tp} / {@code /setblock} style) with {@code ~}/{@code ^} relative
 * support; the target chunk need not be loaded (the bot loads terrain as it advances).
 */
public final class GotoCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("goto")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            // Resolve here (the executes lambda may throw); getBlockPos applies ~/^ relative to
                            // the source and does NOT require the target chunk to be loaded (unlike
                            // getLoadedBlockPos) — a far goto must be allowed before the bot has walked there.
                            BlockPos target = ctx.getArgument("pos", Coordinates.class)
                                    .getBlockPos(ctx.getSource());
                            return OrebitCommands.act(ctx, (b, player, source) -> {
                                b.comeTo(target, 0.75, 0.75, 0);   // exact: reach the precise block
                                CommandFeedback.send(source, "Bot heading to "
                                        + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
                            });
                        })));
    }
}
