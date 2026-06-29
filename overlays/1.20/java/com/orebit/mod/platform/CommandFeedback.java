package com.orebit.mod.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Command reply helper — flavor for MC <b>1.20+</b> (including the 26.x era). MC 1.20 changed {@link
 * CommandSourceStack#sendSuccess} to take a {@code Supplier<Component>} (so the message is only built
 * when output is actually shown) instead of a {@code Component}. Overrides the {@code overlays/1.19}
 * flavor; {@code Component.literal} is unchanged from 1.19.
 */
public final class CommandFeedback {

    private CommandFeedback() {}

    public static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    /** Direct chat line to a player (bot progress chatter) — {@code displayClientMessage} is the era's
     *  player-message primitive (not gamerule-gated, unlike a command source's {@code sendSuccess}). 26.x
     *  drops {@code displayClientMessage} for {@code sendSystemMessage} — see {@code overlays/26}. */
    public static void sendTo(Player player, String message) {
        player.displayClientMessage(Component.literal(message), false);
    }
}
