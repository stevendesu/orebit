package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot rtrace} — the REGION-tier counterpart of {@code /bot trace}. Stops the bot and runs a single
 * direct level-0 region A* from the bot to the caller with {@code RegionPathfinder.TRACE} on, dumping every
 * expansion + candidate edge (kind, cost, crossing cell, accept/reject) to {@code <run dir>/orebit-region-
 * trace.txt}, plus the live cascade skeleton as a cross-check. For OFFLINE analysis of WHY the region tier
 * builds the skeleton it does — the down→over→up cavern-drop investigation (the region counterpart of the
 * block-tier open-air-pillar trace).
 *
 * <p>Run it with the bot where a failing {@code /bot come}/{@code /bot gather} left it, then read
 * {@code orebit-region-trace.txt}: the {@code C} lines under each {@code E} expansion show every edge the
 * region A* weighed and its cost, so a nonsensical detour (e.g. a cheap {@code air-fall} drop beating a
 * {@code walk} across) is legible in the numbers.
 */
public final class RegionTraceCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("rtrace").executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
            // goalFloor = the block the caller stands ON (feet cell .below()) — the same goal /bot come uses.
            String path = b.regionTraceTo(player.blockPosition().below());
            CommandFeedback.send(source, "Region A* trace written to " + path);
        })));
    }
}
