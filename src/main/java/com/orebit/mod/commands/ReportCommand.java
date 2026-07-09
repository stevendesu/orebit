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
 * "compass"): for every tracked resource it has ever seen, an at-a-glance abundance table across a few
 * distance scales around the player, out to a whole-world total. The numeric complement to {@link
 * FindCommand}: where {@code /bot find} localises ONE resource to nearby 16³ regions to go mine, this shows
 * ALL resources and how much sits near / far / everywhere.
 *
 * <h2>What the numbers mean</h2>
 * The data plane is the per-dimension {@link ResourcePyramid} — a log₂ histogram ({@link Log2Codec}) of how
 * many of each indexed resource ({@link ResourceClasses} column) each region of the fixed-grid octree
 * ({@link RegionAddress}) holds, rolled up so a parent's count is the log₂-sum of its children (to
 * {@link ResourcePyramid#RESOURCE_TOP_LEVEL} — true-global — unlike the region A* tier which caps at
 * {@link RegionAddress#MAX_COARSE_LEVEL}).
 *
 * <p>The three near/mid/far columns are <b>player-centered box sums</b> ({@link ResourceQuery#windowLog2}):
 * each folds every cell overlapping a box of the given radius centered on the bot, over the <b>full vertical
 * column</b> (so ores far below still count). Centering the <i>query</i> on the player — rather than reading
 * the single grid cell the bot sits in — is what keeps the numbers stable near the world origin: a fixed grid
 * always jumps at cell boundaries (most visibly at 0,0, where standing on {@code 1,1} vs {@code −1,−1} would
 * otherwise land in different cells), and a centered quadtree cannot fix that (a cell centered on 0 must split
 * at 0), so we center the box instead. The last column is the whole-world total:
 * <ul>
 *   <li>{@code near} — within ~{@value #NEAR_RADIUS} blocks (a starter base);</li>
 *   <li>{@code mid}  — within ~{@value #MID_RADIUS} blocks (an end-game base);</li>
 *   <li>{@code far}  — within ~{@value #FAR_RADIUS} blocks (about as far as most players ever explore);</li>
 *   <li>{@code global} — {@link ResourceQuery#globalLog2}: everything the bot has explored <b>this session,
 *       anywhere</b> (a fold over the top-level rows). <b>Not saved across a server restart yet</b> — the
 *       compass forgets on shutdown until the persistence arc lands.</li>
 * </ul>
 * Counts are approximate (log₂ buckets; the box is also snapped to the cell grid). Values over 1024 are shown
 * as {@code ~2^n} to stay compact. Only resources with a nonzero global tally are listed.
 */
public final class ReportCommand implements BotCommand {

    // Scale radii (blocks) and the pyramid level whose cells the box-sum reads. Radius = 2 × cell side, so each
    // box is ~4 cells wide → a boundary crossing swaps only a fraction of the coverage (stable-ish). Tunable.
    private static final int NEAR_RADIUS = 64;    // level 1 (32-block cells) — starter base
    private static final int MID_RADIUS  = 512;   // level 4 (256-block cells) — end-game base
    private static final int FAR_RADIUS  = 8192;  // level 8 (4096-block cells) — a lifetime of exploration

    /** Per-scale (level, radius), coarsest last; parallel to the near/mid/far headers. */
    private static final int[] SCALE_LEVEL  = { 1, 4, 8 };
    private static final int[] SCALE_RADIUS = { NEAR_RADIUS, MID_RADIUS, FAR_RADIUS };
    /** Column headers: the three player-centered scales + the whole-world fold. */
    private static final String[] HEADS = { "near", "mid", "far", "global" };
    /** Index of the global byte within each gathered {@code e[]} row ({@code col} at 0, then the scales). */
    private static final int GLOBAL_IDX = SCALE_LEVEL.length + 1;

    private static final String ROW_FMT = "%-15s %6s %6s %6s %7s";

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("report").executes(ctx -> OrebitCommands.act(ctx, (b, player, src) -> {
            final ServerLevel level = (ServerLevel) Worlds.of(b);
            final ResourcePyramid pyramid = RegionGrid.of(level).resourcePyramid();
            final BlockPos at = b.blockPosition();
            final int px = at.getX(), pz = at.getZ();

            // Gather every resource the bot knows about (nonzero global tally), with its per-scale window sums.
            final List<int[]> rows = new ArrayList<>(); // {col, near, mid, far, global}
            for (int col = 0; col < ResourceClasses.COLUMN_COUNT; col++) {
                final int global = ResourceQuery.globalLog2(pyramid, col) & 0xFF;
                if (global == 0) continue; // resource never seen anywhere → omit
                final int[] e = new int[SCALE_LEVEL.length + 2];
                e[0] = col;
                for (int i = 0; i < SCALE_LEVEL.length; i++) {
                    e[i + 1] = ResourceQuery.windowLog2(pyramid, px, pz, SCALE_LEVEL[i], SCALE_RADIUS[i], col) & 0xFF;
                }
                e[GLOBAL_IDX] = global;
                rows.add(e);
            }

            if (rows.isEmpty()) {
                CommandFeedback.send(src, "No resources mapped yet — the compass only knows loaded/explored "
                        + "chunks. Move around (or wait for chunks to load) and try again.");
                return;
            }

            // Most-abundant first (by the global tally; larger log₂ byte == larger count).
            rows.sort((a, c) -> Integer.compare(c[GLOBAL_IDX], a[GLOBAL_IDX]));

            CommandFeedback.send(src, "Resource knowledge around you — near/mid/far = within ~" + NEAR_RADIUS
                    + "/" + MID_RADIUS + "/" + FAR_RADIUS + " blocks (full depth); global = everything explored "
                    + "this session anywhere (not saved across restart yet). Counts approximate.");
            CommandFeedback.send(src, String.format(ROW_FMT, "resource", HEADS[0], HEADS[1], HEADS[2], HEADS[3]));
            for (int[] e : rows) {
                CommandFeedback.send(src, String.format(ROW_FMT,
                        ResourceClasses.nameOfColumn(e[0]),
                        fmt(e[1]), fmt(e[2]), fmt(e[3]), fmt(e[GLOBAL_IDX])));
            }
        })));
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
