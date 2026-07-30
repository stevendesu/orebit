package com.orebit.mod.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.building.Schematic;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.ConfigDir;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;

/**
 * {@code /bot build <name> <x y z>} — execute a Litematica schematic at an origin
 * (DESIGN-bot-abilities.md §6). Schematic files are {@code .litematic}s the server owner drops
 * into {@code <server dir>/orebit-schematics/} (created on first use so the drop spot is
 * discoverable); {@code <name>} is the file basename, tab-completed from the directory. The
 * origin is the world position every region's min corner offsets from; parsing failures
 * (pre-1.13.2 versions, malformed files) surface as chat. Hands off to
 * {@link com.orebit.mod.AllyBotEntity#startBuild}, the {@code BUILD}-mode state machine.
 */
public final class BuildCommand implements BotCommand {

    /** The drop directory, under the server run dir (the ConfigDir anchor every other owner file
     *  uses). Created lazily by {@link #schematicsDir}. */
    private static final String DIR_NAME = "orebit-schematics";

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("build")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                availableNames(ctx.getSource()), b))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(BuildCommand::run))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final String name = StringArgumentType.getString(ctx, "name");
        final BlockPos origin = ctx.getArgument("pos", Coordinates.class).getBlockPos(ctx.getSource());
        final Path dir = schematicsDir(ctx.getSource());
        final Path file = dir.resolve(name.endsWith(".litematic") ? name : name + ".litematic");
        if (!Files.isRegularFile(file)) {
            CommandFeedback.send(ctx.getSource(), "no schematic '" + name + "' in " + dir
                    + " (drop .litematic files there)");
            return 0;
        }
        final Schematic schematic;
        try {
            schematic = Schematic.load(file);
        } catch (IOException e) {
            CommandFeedback.send(ctx.getSource(), "couldn't read '" + name + "': " + e.getMessage());
            return 0;
        }
        final int blocks = schematic.totalBlocks();
        return OrebitCommands.act(ctx, (b, player, src) -> {
            b.startBuild(schematic, origin);
            CommandFeedback.send(src, "building '" + schematic.name() + "' (" + blocks
                    + " blocks) at " + origin.getX() + "," + origin.getY() + "," + origin.getZ() + "…");
        });
    }

    /** The drop directory (created on first use). */
    private static Path schematicsDir(CommandSourceStack source) {
        final Path dir = ConfigDir.serverDir(source.getServer()).resolve(DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // suggestion/run paths handle absence gracefully
        }
        return dir;
    }

    /** The tab-completion list: .litematic basenames in the drop directory. */
    private static List<String> availableNames(CommandSourceStack source) {
        final List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(schematicsDir(source))) {
            files.forEach(p -> {
                final String f = p.getFileName().toString();
                if (f.endsWith(".litematic")) {
                    names.add(f.substring(0, f.length() - ".litematic".length()));
                }
            });
        } catch (IOException ignored) {
            // empty list — the run path reports the directory
        }
        return names;
    }
}
