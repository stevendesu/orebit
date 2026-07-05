package com.orebit.mod.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.worldmodel.resource.ResourceClasses;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot gather <resource> [count]} — the find→mine→return milestone (find-mine-resources design §7).
 * The bot drills the resource pyramid for the nearest known {@code <resource>}, paths there via the two-tier
 * nav, mines it in survival (real drops picked up into its real inventory), and repeats until it has gathered
 * {@code count} items, then walks back to where the command was issued. Where {@link FindCommand} is a cold,
 * read-only diagnostic that only prints candidate regions, this one DRIVES the bot: it hands off to
 * {@link com.orebit.mod.AllyBotEntity#startGather}, which runs a hand-coded {@code GATHER}-mode state machine
 * reusing the reactive executors (query → {@code driveToward} → {@code BotMining}).
 *
 * <p>{@code <resource>} is a column name ({@link ResourceClasses#columnForName} — "diamond", "iron", "gold",
 * "andesite", …); an unknown name replies with a hint. Optional {@code [count]} (default 1) is the target
 * number of picked-up items (owner-ratified quota semantics — robust to fortune/variant drop counts).
 */
public final class GatherCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("gather")
                .then(Commands.argument("resource", StringArgumentType.word())
                        .executes(ctx -> run(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        final String resource = StringArgumentType.getString(ctx, "resource");
        final int column = ResourceClasses.columnForName(resource);
        if (column < 0) {
            CommandFeedback.send(ctx.getSource(),
                    "unknown resource '" + resource + "' (try: diamond, iron, gold, coal, andesite, diorite, ...)");
            return 0;
        }
        return OrebitCommands.act(ctx, (b, player, src) -> {
            b.startGather(column, count);
            CommandFeedback.send(src, "gathering " + resource + " ×" + count + "…");
        });
    }
}
