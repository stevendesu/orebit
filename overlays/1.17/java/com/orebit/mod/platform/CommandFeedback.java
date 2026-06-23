package com.orebit.mod.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.TextComponent;

/**
 * Sends a one-line reply to a command source — the thin version-divergent primitive behind the common
 * {@code /bot} commands, so the command classes stay vanilla-API-clean.
 *
 * <p>Baseline flavor (MC <b>1.17–1.18.2</b>): the literal text component is {@code new TextComponent},
 * and {@link CommandSourceStack#sendSuccess} takes the {@code Component} directly. The 1.19 rename
 * ({@code Component.literal}) and the 1.20 {@code Supplier<Component>} signature override this in
 * {@code overlays/1.19} and {@code overlays/1.20}.
 */
public final class CommandFeedback {

    private CommandFeedback() {}

    public static void send(CommandSourceStack source, String message) {
        source.sendSuccess(new TextComponent(message), false);
    }
}
