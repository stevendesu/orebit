package com.orebit.mod.commands;

import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.orebit.mod.AllyBotEntity;
import com.orebit.mod.BotManager;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers Orebit's {@code /bot} command tree onto the server dispatcher via the {@link PlatformEvents}
 * seam. This is the one place the common command logic meets the loader (mirrors {@link
 * com.orebit.mod.worldmodel.pathing.ChunkNavLoader#register}): the loader fires {@code onRegisterCommands}
 * with the Brigadier dispatcher, and here we build the {@code /bot} root and let each registered {@link
 * BotCommand} contribute its subtree. New commands plug into {@link #COMMANDS}; nothing else changes.
 */
public final class OrebitCommands {

    private OrebitCommands() {}

    /** The registered subcommands of {@code /bot}. Add a class + a line here to grow the surface. */
    private static final List<BotCommand> COMMANDS = List.of(
            new SpawnCommand(),
            new FollowCommand(),
            new StayCommand(),
            new ComeCommand(),
            new GotoCommand(),
            new MineCommand(),
            new FindCommand(),
            new GatherCommand(),
            new HereCommand(),
            new TraceCommand(),
            new RegionTraceCommand(),
            new ProbeCommand(),
            new ConfigCommand(),
            new DebugCommand());

    /** Subscribe to the seam's command-registration hook; called from {@code OrebitCommon.init}. */
    public static void register(PlatformEvents events) {
        events.onRegisterCommands(dispatcher -> {
            LiteralArgumentBuilder<CommandSourceStack> bot = Commands.literal("bot");
            for (BotCommand command : COMMANDS) {
                command.contribute(bot);
            }
            dispatcher.register(bot);
        });
    }

    /**
     * Shared executor body for the simple "act on the caller's bot" commands: resolve the calling
     * player and their bot, report if they have none, otherwise run {@code action}. Returns the
     * Brigadier success count (1 = ran, 0 = no bot). Commands that need no extra arguments route their
     * {@code .executes(...)} through here so each command class is just its literal + the action.
     */
    static int act(CommandContext<CommandSourceStack> ctx, BotAction action) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException(); // commands are player-scoped (their own bot)
        AllyBotEntity bot = BotManager.botFor(player);
        if (bot == null) {
            CommandFeedback.send(source, "You have no bot right now.");
            return 0;
        }
        action.run(bot, player, source);
        return 1;
    }

    /** What a simple {@code /bot} subcommand does once its caller's bot is resolved. */
    @FunctionalInterface
    interface BotAction {
        void run(AllyBotEntity bot, ServerPlayer player, CommandSourceStack source);
    }
}
