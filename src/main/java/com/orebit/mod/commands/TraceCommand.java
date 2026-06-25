package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot trace} — a one-shot diagnostic. Stops the bot (so it isn't replanning every tick) and runs a
 * single <b>raw</b> block-A* from the bot to the caller with full step-by-step tracing dumped to a file
 * ({@code <run dir>/orebit-trace.txt}), then reports the path. For OFFLINE analysis (scripts/tools) of why a
 * search explores what it does — the open-air-pillar investigation — without the console flood a failing
 * {@code /bot come} produces (it replans, and logs the failure, every tick).
 *
 * <p>Run it with the bot where the failing {@code /bot come} left it (with partial-path off it doesn't move
 * on failure, so it sits at the start), then read {@code orebit-trace.txt}: each {@code E} line is one node
 * expansion in order, the indented {@code C} lines are the candidates it emitted. Grep/awk the {@code E}
 * lines to see the expansion order (e.g. how many ground cells it floods before it commits to pillaring).
 */
public final class TraceCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("trace").executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
            // goalFloor = the block the caller stands ON (feet cell .below()) — the same goal /bot come uses.
            String path = b.traceTo(player.blockPosition().below());
            CommandFeedback.send(source, "A* trace written to " + path);
        })));
    }
}
