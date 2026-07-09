package com.orebit.mod.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.worldmodel.resource.ItemClasses;
import com.orebit.mod.worldmodel.resource.ResourceClasses;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /bot drop <what>} — the bot tosses part of its inventory to the ground for the owner to collect
 * (the first half of a give-items handoff; a directed give-to-player comes with the relationships arc). The
 * dropped items get vanilla's self-pickup delay, so the bot won't immediately re-vacuum them.
 *
 * <p>{@code <what>} is one of the category keywords or a specific resource name; it tab-completes:
 * <ul>
 *   <li>{@code all} — every carried stack;</li>
 *   <li>{@code resources} — every tracked resource (ores/ingots/gems/logs — see {@link ItemClasses});</li>
 *   <li>{@code tools} — pickaxes/axes/shovels/hoes/swords, shears, bow, ...;</li>
 *   <li>{@code trash} — everything that is NOT a resource, tool, or armor piece (junk blocks, drops the
 *       bot doesn't need) — the inventory cleaner;</li>
 *   <li>a {@link ResourceClasses} column name ({@code iron}, {@code diamond}, {@code gold}, {@code wood},
 *       ...) — just that resource.</li>
 * </ul>
 * Armor is deliberately kept (it is neither dropped by {@code trash} nor a keyword yet) until the combat
 * arc gives the bot a reason to manage it.
 */
public final class DropCommand implements BotCommand {

    private static final String ALL = "all";
    private static final String RESOURCES = "resources";
    private static final String TOOLS = "tools";
    private static final String TRASH = "trash";

    /** Tab-completion source: the category keywords followed by every resource column name. */
    private static final List<String> SUGGESTIONS = buildSuggestions();

    private static List<String> buildSuggestions() {
        List<String> s = new ArrayList<>(List.of(ALL, RESOURCES, TOOLS, TRASH));
        s.addAll(ResourceClasses.columnNames());
        return List.copyOf(s);
    }

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("drop")
                .then(Commands.argument("what", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(SUGGESTIONS, b))
                        .executes(DropCommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final String what = ItemClasses.normalizeToken(StringArgumentType.getString(ctx, "what"));
        final Predicate<ItemStack> filter = filterFor(what);
        if (filter == null) {
            CommandFeedback.send(ctx.getSource(), "drop what? try: all, resources, tools, trash, "
                    + "or a resource name (iron, diamond, gold, wood, ...)");
            return 0;
        }
        return OrebitCommands.act(ctx, (b, player, src) -> {
            int dropped = new BotInventory(b).dropMatching(filter);
            CommandFeedback.send(src, dropped == 0
                    ? "nothing to drop (" + what + ")"
                    : "dropped " + dropped + " item" + (dropped == 1 ? "" : "s") + " (" + what + ")");
        });
    }

    /** The stack filter for a {@code <what>} token, or {@code null} if the token is unrecognised. */
    private static Predicate<ItemStack> filterFor(String what) {
        switch (what) {
            case ALL:       return s -> true;
            case RESOURCES: return s -> ItemClasses.resourceColumn(s.getItem()) >= 0;
            case TOOLS:     return s -> ItemClasses.isTool(s.getItem());
            case TRASH:     return s -> ItemClasses.isTrash(s.getItem());
            default:
                int column = ResourceClasses.columnForName(what);
                if (column < 0) return null;
                return s -> ItemClasses.resourceColumn(s.getItem()) == column;
        }
    }
}
