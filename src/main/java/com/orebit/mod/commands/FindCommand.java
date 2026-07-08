package com.orebit.mod.commands;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceQuery;
import com.orebit.mod.worldmodel.resource.ResourceScan;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /bot find <resource> [minCount]} — a cold, read-only diagnostic that eyeballs the resource data
 * plane: it runs the {@link ResourceQuery} drill-down from the bot's position and chats back the nearest
 * candidate regions (no bot movement). This is the in-game window onto the whole find-mine-resources data
 * layer (the tally-on-classify pyramid, phases 1-4) before the {@code /bot gather} task loop (phase 6) drives
 * the bot to them. Mirrors {@link ProbeCommand} in spirit: a strategy-pattern {@link BotCommand} that reads
 * the world model and prints, never touching a hot path.
 *
 * <p>{@code <resource>} is a column name ({@link ResourceClasses#columnForName} — "diamond", "iron", "gold",
 * "andesite", …); an unknown name replies with a hint. Optional {@code [minCount]} (default 1) is the
 * per-region quantity threshold. Reports the top 5 hits nearest-first, each as
 * {@code <resource> x~<approxCount> at <cx> <cy> <cz> (<dist>m)}, or "no &lt;resource&gt; found nearby".
 */
public final class FindCommand implements BotCommand {

    /** How many candidate regions to report. */
    private static final int MAX_RESULTS = 5;

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("find")
                .then(Commands.argument("resource", StringArgumentType.word())
                        // Tab-complete the valid column names, same provider as /bot gather (s52).
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ResourceClasses.columnNames(), b))
                        .executes(ctx -> run(ctx, 1))
                        .then(Commands.argument("minCount", IntegerArgumentType.integer(1))
                                .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "minCount"))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, int minCount) throws CommandSyntaxException {
        final CommandSourceStack source = ctx.getSource();
        final String resource = StringArgumentType.getString(ctx, "resource");
        final int column = ResourceClasses.columnForName(resource);
        if (column < 0) {
            CommandFeedback.send(source,
                    "unknown resource '" + resource + "' (try: diamond, iron, gold, coal, andesite, diorite, ...)");
            return 0;
        }
        return OrebitCommands.act(ctx, (b, player, src) -> {
            final ServerLevel level = (ServerLevel) Worlds.of(b);
            final BlockPos anchor = b.blockPosition();
            final List<ResourceQuery.ResourceHit> hits =
                    ResourceQuery.find(level, column, anchor, minCount, MAX_RESULTS);
            if (hits.isEmpty()) {
                CommandFeedback.send(src, "no " + resource + " found nearby"
                        + (minCount > 1 ? " (>=" + minCount + " per spot)" : "") + ".");
                return;
            }
            CommandFeedback.send(src, "nearest " + resource + " (" + hits.size()
                    + ") — coords are the 16³ region CENTRE, not the ore cell:");
            for (ResourceQuery.ResourceHit h : hits) {
                final BlockPos c = h.center();
                final long dx = c.getX() - anchor.getX();
                final long dy = c.getY() - anchor.getY();
                final long dz = c.getZ() - anchor.getZ();
                final long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                CommandFeedback.send(src, "  " + resource + " x~" + h.approxCount()
                        + " in region @ " + c.getX() + " " + c.getY() + " " + c.getZ()
                        + " (" + dist + "m)" + liveScan(level, h, column, anchor));
            }
        });
    }

    /**
     * Live-scan the hit's 16³ level-0 region (the pyramid only localizes to a section, not a block — design
     * §6/§7) via {@link ResourceScan#exactCells} and report the EXACT matching cells: how many the live world
     * actually holds and the nearest one to the anchor. This is the "why did it think there was iron here?"
     * diagnostic — it distinguishes the normal case (the ore is a real block somewhere in the cube, just not
     * at the centre) from a genuine phantom ({@code LIVE: 0}, a stale load-populated tally or a real tally/coord
     * bug worth instrumenting the write path for). A region no longer resident can't be verified (its chunk
     * unloaded since the tally was written); {@code exactCells} returns {@code null} there and we say so.
     */
    private static String liveScan(ServerLevel level, ResourceQuery.ResourceHit h, int column, BlockPos anchor) {
        final List<BlockPos> cells = ResourceScan.exactCells(level, h.rx(), h.ry(), h.rz(), column);
        if (cells == null) return "  [region not loaded — can't verify exact cells]";
        if (cells.isEmpty()) return "  [LIVE: 0 exact in the 16³ region — stale tally or a bug, NOT a real vein]";
        BlockPos nearest = null;
        long best = Long.MAX_VALUE;
        for (BlockPos p : cells) {
            final long ddx = p.getX() - anchor.getX();
            final long ddy = p.getY() - anchor.getY();
            final long ddz = p.getZ() - anchor.getZ();
            final long d = ddx * ddx + ddy * ddy + ddz * ddz;
            if (d < best) { best = d; nearest = p; }
        }
        return "  [LIVE: " + cells.size() + " exact, nearest " + nearest.getX() + " " + nearest.getY()
                + " " + nearest.getZ() + "]";
    }
}
