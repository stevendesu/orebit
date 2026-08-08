package com.orebit.mod.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /bot roam [radius]} — send the bot off to explore on its own: it repeatedly picks a random nearby
 * destination, preferring the ones that take it FARTHER from where roaming started, walks there, and picks
 * another. It keeps going until some other {@code /bot} command changes the mode ({@code /bot stay},
 * {@code /bot follow}, …). {@code radius} (default {@value #DEFAULT_RADIUS} blocks) bounds how far from the
 * starting cell it may get, so it explores outward and then works the frontier of that circle rather than
 * wandering off to the world border.
 *
 * <p><b>It will not walk off a cliff.</b> Roam is the one mode that plans with the {@code Fall} movement
 * disabled ({@code BotCaps.mayFall}), so no route it follows contains a deliberate drop off a ledge — at any
 * depth, and regardless of how survivable the damage model thinks the landing is. Everything else it can
 * normally do it still does: it jumps gaps, swims, climbs, bridges and steps down single blocks. A spot
 * reachable only by dropping is simply never chosen as a route, and the bot picks a different destination.
 *
 * <p>See {@code BotRoamer} for the target-selection rule (a best-of-N tournament over random headings, scored
 * by distance from the roam origin) and why the destination is always a cell in already-built nav data.
 */
public final class RoamCommand implements BotCommand {

    /** Default bound (blocks from the roam origin) when the command is given no {@code radius}. Big enough to
     *  cross several biomes; small enough that the owner can still go find the bot. */
    public static final int DEFAULT_RADIUS = 512;

    /** Smallest sensible bound — below the longest single leg the roamer draws, the wander has no room. */
    private static final int MIN_RADIUS = 64;

    /** Upper bound on the argument: far past any play session, while still keeping the squared-distance
     *  scoring comfortably inside double precision and the bot inside the vanilla world border. */
    private static final int MAX_RADIUS = 1_000_000;

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("roam")
                .executes(ctx -> start(ctx, DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                        .executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "radius")))));
    }

    private static int start(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int radius)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return OrebitCommands.act(ctx, (b, player, source) -> {
            b.startRoam(radius);
            CommandFeedback.send(source, "Bot is off exploring (within " + radius
                    + " blocks, and it won't walk off any ledges). Use /bot stay or /bot follow to call it off.");
        });
    }
}
