package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;

/**
 * {@code /bot trace} — a one-shot diagnostic. Stops the bot (so it isn't replanning every tick), builds the
 * <b>full two-tier plan exactly as {@code /bot come} does</b>, and then re-runs that plan's <b>first window's
 * block-A*</b> — with its HPA*-derived corridor, so cuboids, macro-ops and the goal-forced-cost premium are
 * all ACTIVE — with full step-by-step tracing dumped to a file ({@code <run dir>/orebit-trace.txt}), then
 * reports the path. A raw cornerless whole-path search is the FALLBACK, used only when HPA* yields no window
 * (clearly labelled in the dump header). For OFFLINE analysis (scripts/tools) of why a search explores what
 * it does — the open-air-pillar investigation — without the console flood a failing {@code /bot come}
 * produces (it replans, and logs the failure, every tick). See {@link com.orebit.mod.AllyBotEntity#traceTo}.
 *
 * <p>Run it with the bot where the failing {@code /bot come} left it (with partial-path off it doesn't move
 * on failure, so it sits at the start), then read {@code orebit-trace.txt}: the header records the start, the
 * goal, and — when there IS a window — that window's {@code target} + {@code corridor}; each {@code E} line is
 * one node expansion in order, the indented {@code C} lines are the candidates it emitted. Grep/awk the
 * {@code E} lines to see the expansion order (e.g. how many ground cells it floods before it commits to
 * pillaring).
 */
public final class TraceCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        // Three forms, nested so Brigadier disambiguates by ARITY (owner request 2026-08-04):
        //   /bot trace              bot's floor -> caller's floor   (the original one-shot)
        //   /bot trace <to>         bot's floor -> <to>             (aim it without teleporting yourself)
        //   /bot trace <from> <to>  <from>      -> <to>             (seed a node the bot cannot be held in)
        // With ONE position that argument is the GOAL; with two, the first is the START. Same argument type
        // GotoCommand already uses, so relative (~) and tab-completed coords work identically.
        bot.then(Commands.literal("trace")
                .executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
                    // goalFloor = the block the caller stands ON (feet cell .below()) — /bot come's goal.
                    String path = b.traceTo(player.blockPosition().below());
                    CommandFeedback.send(source, "A* trace written to " + path);
                }))
                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            BlockPos to = goalFloorOf(ctx, "pos1");
                            return OrebitCommands.act(ctx, (b, player, source) ->
                                    CommandFeedback.send(source, "A* trace (start=bot, goal=" + compact(to)
                                            + ") written to " + b.traceTo(to)));
                        })
                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos from = pos(ctx, "pos1");   // FLOOR cell, taken literally
                                    BlockPos to = goalFloorOf(ctx, "pos2");
                                    return OrebitCommands.act(ctx, (b, player, source) ->
                                            CommandFeedback.send(source, "A* trace (start=" + compact(from)
                                                    + ", goal=" + compact(to) + ") written to "
                                                    + b.traceTo(from, to)));
                                }))));
    }

    /**
     * Resolve a position argument. Uses {@code Coordinates.getBlockPos} — the same call {@link GotoCommand}
     * makes, and deliberately NOT {@code BlockPosArgument.getLoadedBlockPos}: the latter requires the target
     * chunk to be LOADED, which a distant goal (the flagship's {@code 201 -28 90}, ~200 blocks out) is not.
     * Resolved in the {@code executes} body because it throws a checked {@code CommandSyntaxException} that
     * {@code Command.run} declares but the {@link BotCommand} callback does not.
     */
    private static BlockPos pos(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        return ctx.getArgument(name, Coordinates.class).getBlockPos(ctx.getSource());
    }

    /**
     * The GOAL argument, converted to the FLOOR cell the planner actually targets.
     *
     * <p><b>Why {@code .below()} (2026-08-04).</b> Every other way of naming this goal means "the block to
     * STAND ON": the no-arg form uses {@code player.blockPosition().below()}, and {@code /bot goto <pos>}
     * hands {@code pos} to {@code comeTo}, which derives the floor the same way — so {@code /bot goto
     * 201 -28 90} plans to floor {@code (201,-29,90)}. Taking the coordinates literally here made
     * {@code /bot trace … 201 -28 90} aim one block HIGHER than the identical-looking goto, which silently
     * traced a different search than the one under investigation and cost a full debugging round-trip.
     * Typing the same numbers must now produce the same goal.
     *
     * <p>The START argument is NOT converted: it is taken as a floor cell verbatim, because its natural
     * source is the log, which prints floor cells ({@code block plan … FULL from (55,177,255)},
     * {@code PLAN … from-floor=…}). Each end matches where its coordinates get copied from, and the command
     * echoes both resolved cells so the convention is never in doubt.
     */
    private static BlockPos goalFloorOf(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        return pos(ctx, name).below();
    }

    private static String compact(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
