package com.orebit.mod.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Command reply helper — flavor for the <b>26.x</b> era. {@link #send} is unchanged from {@code overlays/1.20}
 * (the {@code Supplier<Component>} {@code sendSuccess} still applies), but 26.x <b>removed</b>
 * {@code Player.displayClientMessage} — the player-message primitive is now {@code sendSystemMessage(Component)}.
 * That one method difference is why this era overrides the 1.20 flavor.
 */
public final class CommandFeedback {

    private CommandFeedback() {}

    public static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    /** Direct chat line to a player (bot progress chatter) — 26.x's player-message primitive (not
     *  gamerule-gated, unlike a command source's {@code sendSuccess}). */
    public static void sendTo(Player player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
