package com.orebit.mod.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.crafting.RecipeIndex;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * {@code /bot craft <item> [count]} — craft {@code count} of a result item from the bot's real
 * inventory (DESIGN-bot-abilities.md §3.5/§3.6). {@code <item>} is a result name from the baked
 * {@link RecipeIndex} (the item id's path, e.g. {@code oak_planks}, {@code crafting_table}) —
 * tab-completed from the index; the registration seam carries no {@code CommandBuildContext}, so
 * the vanilla {@code ItemArgument} is structurally unavailable and, per the house pattern, a
 * curated string list + {@link SharedSuggestionProvider} does the job. Optional {@code [count]}
 * (default 1) is the target number of RESULT items (a plank craft yields 4 toward it). Hands off
 * to {@link com.orebit.mod.AllyBotEntity#startCraft}, the {@code CRAFT}-mode state machine.
 */
public final class CraftCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("craft")
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                RecipeIndex.names(), b))
                        .executes(ctx -> run(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        final String item = StringArgumentType.getString(ctx, "item");
        if (RecipeIndex.forName(item).isEmpty()) {
            CommandFeedback.send(ctx.getSource(),
                    "unknown craftable item '" + item + "' (tab-complete lists what I can make)");
            return 0;
        }
        return OrebitCommands.act(ctx, (b, player, src) -> {
            b.startCraft(item, count);
            CommandFeedback.send(src, "crafting " + item + " ×" + count + "…");
        });
    }
}
