package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.pathfinding.blockpathfinder.ReadCensus;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot census dump|reset|status} — the control surface for {@link ReadCensus}, the per-pop NavGrid
 * read counter behind the read-reduction arc ({@code internal_docs/INVENTORY-per-move-cell-reads.md}).
 *
 * <p><b>The instrument is not armed by this command.</b> {@link ReadCensus#ENABLED} is a {@code static
 * final} read from a system property at class-init, precisely so the JIT can erase every hook when it is
 * off — a runtime toggle would mean a load + branch on every grid read, which is the cost class this
 * project has repeatedly measured as a real regression. Arm it for the whole JVM:
 *
 * <pre>  JAVA_TOOL_OPTIONS=-Dorebit.readcensus=true</pre>
 *
 * <p>then drive the scenario (a flagship run, {@code /bot come} across hard terrain, an autotest) and
 * {@code /bot census dump} to write {@code <run dir>/orebit-read-census.txt}. {@code reset} starts a fresh
 * measurement window, so a single session can measure several scenarios separately — reset immediately
 * before the run you care about, since counters otherwise accumulate from server start.
 *
 * <p>Counts only: with the census armed every read pays bucket arithmetic and several array increments, so
 * ns/node from a census session means nothing. Timing stays with JMH under the paired-interleaved A/B
 * protocol. Server-wide rather than per-bot, so like {@link ConfigCommand} it does not route through
 * {@link OrebitCommands#act}.
 */
public final class CensusCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("census")
                .then(Commands.literal("dump").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    if (!ReadCensus.ENABLED) {
                        CommandFeedback.send(source, disarmed());
                        return 0;
                    }
                    String summary = ReadCensus.dump(new java.io.File("orebit-read-census.txt"));
                    CommandFeedback.send(source, "Read census: " + summary);
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    if (!ReadCensus.ENABLED) {
                        CommandFeedback.send(source, disarmed());
                        return 0;
                    }
                    ReadCensus.reset();
                    CommandFeedback.send(source, "Read census reset — counters start from here.");
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    CommandFeedback.send(source, ReadCensus.ENABLED
                            ? "Read census ARMED — " + ReadCensus.pops() + " pops recorded."
                            : disarmed());
                    return 1;
                })));
    }

    private static String disarmed() {
        return "Read census is not armed. Restart with -Dorebit.readcensus=true "
                + "(e.g. JAVA_TOOL_OPTIONS) — it is a static final gate so the hooks compile out when off.";
    }
}
