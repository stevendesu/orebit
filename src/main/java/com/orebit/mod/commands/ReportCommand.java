package com.orebit.mod.commands;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.resource.Log2Codec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;
import com.orebit.mod.worldmodel.resource.ResourceQuery;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /bot report} — a cold, read-only diagnostic that dumps the bot's <b>resource knowledge</b> (the
 * "compass"): for every tracked resource it has seen, how much sits in the nested region the bot occupies
 * at a few pyramid levels, from the immediate neighbourhood out to the coarsest ("global") tally. The
 * numeric complement to {@link FindCommand}: where {@code /bot find} localises ONE resource to nearby 16³
 * regions, this gives the at-a-glance abundance table across ALL resources and distance scales.
 *
 * <h2>What the numbers mean</h2>
 * The resource data plane is the per-dimension {@link ResourcePyramid} on the fixed-grid implicit octree
 * ({@link RegionAddress}): a log₂ histogram of how many of each indexed resource ({@link ResourceClasses}
 * column) a region holds, rolled up so a parent's count is the log₂-sum of its children (all the way to
 * {@link ResourcePyramid#RESOURCE_TOP_LEVEL} — true-global — unlike the region A* tier which caps at
 * {@link RegionAddress#MAX_COARSE_LEVEL}). The first three columns read the count for the <b>region containing
 * the bot</b> at one level — and because those regions nest (a level-2 cell contains the bot's level-1 cell,
 * etc.), the counts grow left-to-right; the last column is the whole-world fold:
 * <ul>
 *   <li>{@code l1} — the {@value #NEAR_SIDE}-block region ({@link #LEVELS}[0]) around the bot: what's right here;</li>
 *   <li>{@code l2} / {@code l3} — medium / far (64 / 128 blocks);</li>
 *   <li>{@code global} — {@link ResourceQuery#globalLog2}: everything the bot has explored <b>this session,
 *       anywhere</b> (a fold over the top-level rows, not the bot's containing cell). This is <b>not saved
 *       across a server restart yet</b> — the compass forgets on shutdown until the persistence arc lands.</li>
 * </ul>
 * Counts are approximate (log₂ buckets — {@link Log2Codec}); values over 1024 are shown as {@code ~2^n} to
 * stay compact. Only resources with a nonzero global tally are listed (the rest are simply unmapped so far —
 * the compass only knows what has been loaded/classified).
 */
public final class ReportCommand implements BotCommand {

    /** The nested containing-cell levels shown (near/med/far). Sizes: 16&lt;&lt;level blocks/side. The last
     *  report column is NOT a pyramid level — it is the true-global fold ({@link ResourceQuery#globalLog2}). */
    private static final int[] LEVELS = { 1, 2, 3 };
    /** Column headers: the three nested levels + the whole-world "global" fold. */
    private static final String[] HEADS = { "l1", "l2", "l3", "global" };
    /** The finest shown region's side in blocks ({@code 16 << LEVELS[0]}) — for the doc/legend. */
    private static final int NEAR_SIDE = RegionAddress.LEAF_SIZE << 1;
    /** Index of the global byte within each {@code e[]} row ({@code col} at 0, then the nested levels). */
    private static final int GLOBAL_IDX = LEVELS.length + 1;

    private static final String ROW_FMT = "%-15s %6s %6s %6s %6s";

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("report").executes(ctx -> OrebitCommands.act(ctx, (b, player, src) -> {
            final ServerLevel level = (ServerLevel) Worlds.of(b);
            final RegionGrid grid = RegionGrid.of(level);
            final ResourcePyramid pyramid = grid.resourcePyramid();
            final int minY = grid.minY();
            final BlockPos at = b.blockPosition();

            // Gather every resource the bot knows about (nonzero global tally), with its per-level bytes.
            final List<int[]> rows = new ArrayList<>(); // {col, e[l1], e[l2], e[l3], e[global]}
            for (int col = 0; col < ResourceClasses.COLUMN_COUNT; col++) {
                final int[] e = new int[LEVELS.length + 2];
                e[0] = col;
                for (int i = 0; i < LEVELS.length; i++) {
                    e[i + 1] = countByte(pyramid, minY, at, LEVELS[i], col) & 0xFF;
                }
                // The last column is the whole-world fold, not the bot's containing cell (true-global).
                e[GLOBAL_IDX] = ResourceQuery.globalLog2(pyramid, col) & 0xFF;
                if (e[GLOBAL_IDX] > 0) rows.add(e); // only list resources the compass has actually seen
            }

            if (rows.isEmpty()) {
                CommandFeedback.send(src, "No resources mapped yet — the compass only knows loaded/explored "
                        + "chunks. Move around (or wait for chunks to load) and try again.");
                return;
            }

            // Most-abundant first (by the global tally; larger log₂ byte == larger count).
            rows.sort((a, c) -> Integer.compare(c[GLOBAL_IDX], a[GLOBAL_IDX]));

            CommandFeedback.send(src, "Resource knowledge — l1/l2/l3 = the " + NEAR_SIDE
                    + "/64/128-block region you're in, global = everything explored this session anywhere "
                    + "(not saved across restart yet). Counts approximate.");
            CommandFeedback.send(src, String.format(ROW_FMT, "resource", HEADS[0], HEADS[1], HEADS[2], HEADS[3]));
            for (int[] e : rows) {
                CommandFeedback.send(src, String.format(ROW_FMT,
                        ResourceClasses.nameOfColumn(e[0]),
                        fmt(e[1]), fmt(e[2]), fmt(e[3]), fmt(e[4])));
            }
        })));
    }

    /** The raw log₂ byte of {@code col} in the {@code level}-region containing {@code at}, or 0 if unmapped. */
    private static byte countByte(ResourcePyramid p, int minY, BlockPos at, int level, int col) {
        final int rx = RegionAddress.regionX(at.getX(), level);
        final int rz = RegionAddress.regionZ(at.getZ(), level);
        final int ry = RegionAddress.regionY(at.getY(), level, minY);
        final int row = p.rowIfPresent(level, rx, ry, rz);
        return row < 0 ? 0 : p.getLog2(level, row, col);
    }

    /**
     * Render a log₂ bucket byte {@code e} as a compact approximate count: {@code 0} → {@code "0"}, a bucket
     * whose representative count ({@code 2^(e-1)}, {@link Log2Codec#decode}) is ≤ 1024 → {@code "~<n>"}, and
     * anything larger → {@code "~2^<n>"} to stay short (e.g. {@code ~2^17}).
     */
    private static String fmt(int e) {
        if (e <= 0) return "0";
        if (e <= 11) return "~" + (1 << (e - 1)); // decode() ≤ 1024 (e == 11 → 1024)
        return "~2^" + (e - 1);
    }
}
