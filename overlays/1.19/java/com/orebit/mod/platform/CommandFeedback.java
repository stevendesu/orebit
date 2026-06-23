package com.orebit.mod.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Command reply helper — flavor for MC <b>1.19–1.19.4</b>. The 1.19 component rework replaced {@code
 * new TextComponent(...)} with the {@code Component.literal(...)} factory, but {@link
 * CommandSourceStack#sendSuccess} still takes the {@code Component} directly (the {@code
 * Supplier<Component>} signature arrives in 1.20). Overrides the {@code overlays/1.17} baseline; the
 * {@code overlays/1.20} flavor overrides this in turn.
 */
public final class CommandFeedback {

    private CommandFeedback() {}

    public static void send(CommandSourceStack source, String message) {
        source.sendSuccess(Component.literal(message), false);
    }
}
