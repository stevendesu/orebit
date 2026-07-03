package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bot probe <x> <y> <z>} — a cold, read-only diagnostic that dumps exactly what the <b>planner</b>
 * sees at one nav cell, through the same seams a live search reads ({@link NavGridView} +
 * {@link MovementContext} built from the live {@link ConfigLoader#botCaps() configured caps}). Built for the
 * "why did the bot walk through the hazard" class of bug: it discriminates in one command between
 * <ul>
 *   <li><b>stale grid data</b> — the cell's navtype isn't what the world shows (e.g. a planted bush the
 *       block-change hook never patched in reads as air);</li>
 *   <li><b>stale/wrong flags</b> — the navtype is right but the floor's precomputed
 *       {@code CLEARABLE_HAZARD}/{@code SLOW_TRANSIT} prefilter bits are clear (the zero-read fast path in
 *       {@link MovementContext#bodyTransitCost} then prices the hazard at 0 — the seam-overscan failure
 *       shape);</li>
 *   <li><b>wrong caps</b> — flags fine but the search runs with {@code takesDamage=false} or a default
 *       {@code costPerHitpoint}, so the surcharge is legitimately zero.</li>
 * </ul>
 *
 * <p>Prints, treating the given cell as a FLOOR cell (the search's node convention — body cells are
 * {@code y+1}/{@code y+2}): built-ness, navtype + descriptor facts, the raw flag bits decoded, the two body
 * cells' descriptor facts with their per-cell transit surcharge, the flags-gated
 * {@link MovementContext#bodyTransitCost} a Traverse into this column would be charged, and the caps in
 * force. Coordinates support {@code ~}/{@code ^} (vanilla style). No bot required — the probe reads the
 * caller's level. Allocates freely (strings) — this is a cold diagnostic, never on a search path.
 */
public final class ProbeCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("probe")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            BlockPos pos = ctx.getArgument("pos", Coordinates.class)
                                    .getBlockPos(ctx.getSource());
                            CommandSourceStack source = ctx.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            probe((ServerLevel) Worlds.of(player), source, pos);
                            return 1;
                        })));
    }

    /** Run the probe and chat the multi-line report to {@code source}. */
    private static void probe(ServerLevel level, CommandSourceStack source, BlockPos pos) {
        final int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        final BotCaps caps = ConfigLoader.botCaps();
        final NavGridView grid = new NavGridView(level);
        final MovementContext ctx = new MovementContext(grid, caps);

        CommandFeedback.send(source, "probe (" + x + "," + y + "," + z + ") as FLOOR cell:");

        final int packed = ctx.packedAt(x, y, z);
        if (packed == MovementContext.UNBUILT) {
            CommandFeedback.send(source, "  cell: UNBUILT (no nav data — chunk not tracked/built)");
        } else {
            final int flags = MovementContext.flagsOf(packed);
            final long d = ctx.descriptorOf(x, y, z, packed);
            CommandFeedback.send(source, "  cell: built navtype=" + TraversalGrid.navtypeOf(packed)
                    + " standable=" + NavBlock.isStandable(d)
                    + " passable=" + ctx.passable(d)
                    + " damaging=" + NavBlock.isDamaging(d)
                    + " transitSlow=" + transitName(NavBlock.transitSlow(d))
                    + " climbable=" + NavBlock.isClimbable(d)
                    + " portal=" + NavBlock.isPortal(d)
                    + " protected=" + NavBlock.isProtected(d)
                    + " hardness=" + NavBlock.hardness(d)
                    + " breakable=" + ctx.breakable(d)
                    + (NavBlock.hasCollision(d) && ctx.breakBlockedReason(d) != null
                            ? " (blocked: " + ctx.breakBlockedReason(d) + ")" : ""));
            CommandFeedback.send(source, "  flags=0x" + Integer.toHexString(flags)
                    + " headroom=" + headroomName(NavFlags.headroom(flags))
                    + " CLEARABLE_HAZARD=" + NavFlags.clearableHazard(flags)
                    + " SLOW_TRANSIT=" + NavFlags.slowTransit(flags)
                    + " RISKY_EDIT=" + NavFlags.risksEdit(flags)
                    + " PLACEABLE_NEIGHBOR=" + NavFlags.placeableNeighbor(flags));
            // The two body cells a walker transits standing on this floor, each priced individually, then
            // the flags-gated aggregate a Traverse/Diagonal into this column actually charges. If a body
            // cell's own cellTransitCost is > 0 but bodyTransitCost is 0, the floor's prefilter bits are
            // stale — the exact signature of the seam/patch staleness class of bug.
            CommandFeedback.send(source, "  body y+1: " + bodyCell(ctx, x, y + 1, z));
            CommandFeedback.send(source, "  body y+2: " + bodyCell(ctx, x, y + 2, z));
            CommandFeedback.send(source, "  bodyTransitCost(flags-gated)="
                    + ctx.bodyTransitCost(flags, x, y, z));
        }

        CommandFeedback.send(source, "  caps: takesDamage=" + caps.takesDamage()
                + " costPerHitpoint=" + caps.costPerHitpoint()
                + " canBreak=" + caps.canBreak()
                + " canPlace=" + caps.canPlace()
                + " allowUnbreakable=" + caps.allowUnbreakable()
                + " maxNodes=" + caps.maxNodes()
                + " greedyWeight=" + caps.greedyWeight());
        String protectedSpec = ConfigLoader.config().protectedBlocks().spec();
        if (!protectedSpec.isEmpty()) {
            CommandFeedback.send(source, "  protectedBlocks: " + protectedSpec);
        }
    }

    /** One body cell's facts + its per-cell pass-through surcharge (what {@code cellTransitCost} charges). */
    private static String bodyCell(MovementContext ctx, int x, int y, int z) {
        final int packed = ctx.packedAt(x, y, z);
        if (packed == MovementContext.UNBUILT) {
            return "UNBUILT";
        }
        final long d = ctx.descriptorOf(x, y, z, packed);
        // breakThrough: the "punch the bush/web instead of wading" option's price (mining ticks; the live
        // search adds mining.breakBaseCost via its inventory snapshot — this caps-only probe shows the
        // bare-hand time), or "no" when the cell isn't break-through-eligible. The planner folds the break
        // exactly when this is below cellTransitCost.
        return "navtype=" + TraversalGrid.navtypeOf(packed)
                + " passable=" + ctx.passable(d)
                + " damaging=" + NavBlock.isDamaging(d)
                + " transitSlow=" + transitName(NavBlock.transitSlow(d))
                + " cellTransitCost=" + ctx.cellTransitCost(d)
                + " breakThrough=" + (ctx.breakableThrough(d) ? String.valueOf(ctx.breakCost(d)) : "no");
    }

    private static String transitName(int t) {
        return t == NavBlock.TRANSIT_HEAVY ? "heavy" : t == NavBlock.TRANSIT_LIGHT ? "light" : "none";
    }

    private static String headroomName(int h) {
        switch (h) {
            case NavFlags.HEADROOM_NONE:  return "none";
            case NavFlags.HEADROOM_CRAWL: return "crawl";
            case NavFlags.HEADROOM_WALK:  return "walk";
            default:                      return "jump";
        }
    }
}
