package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;

/**
 * One subcommand of {@code /bot}, as a Strategy (MOVEMENT-DESIGN-style registry — see {@link
 * com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry}). Each command is a small stateless
 * class that contributes its own Brigadier subtree to the shared {@code /bot} root; adding a command is
 * adding a class plus one line in {@link OrebitCommands}, with no edits to the others. Built on vanilla
 * Brigadier ({@link CommandSourceStack}) so it's fully loader- and version-agnostic — the only thing the
 * loaders translate is <i>when</i> registration fires (the {@code onRegisterCommands} seam hook).
 */
public interface BotCommand {

    /** Attach this command's literal/argument subtree to the {@code /bot} root builder. */
    void contribute(LiteralArgumentBuilder<CommandSourceStack> bot);
}
