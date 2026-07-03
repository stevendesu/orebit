package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.AllyBotEntity;
import com.orebit.mod.BotManager;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bot spawn} — respawn the caller's bot after it died (or went missing). Nothing respawns a
 * dead bot automatically: vanilla player respawn is client-driven (the death screen sends the respawn
 * request) and the bot has no client, so until now the only recovery was disconnect + rejoin. This
 * command is that same recovery without the rejoin: tear down whatever entity is registered (the
 * proven disconnect path — a dead bot is still a PlayerList member, and {@code PlayerList.remove} of a
 * dead player is exactly the routine disconnect-on-death-screen case) and run the join-time spawn
 * fresh. Inventory is lost just as with rejoin; death drops follow vanilla rules.
 *
 * <p>Deliberately refuses while the bot is alive — today's product is one bot per owner, and a
 * healthy bot being silently replaced (dropping its inventory and plan) would be surprising. The V2
 * multi-bot vision may later grow this into "spawn an ADDITIONAL bot"; that changes the refusal, not
 * the spawn path.
 *
 * <p>Doesn't route through {@link OrebitCommands#act} — that helper reports "no bot" and bails, which
 * is precisely the state this command exists to fix.
 */
public final class SpawnCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("spawn").executes(ctx -> {
            CommandSourceStack source = ctx.getSource();
            ServerPlayer player = source.getPlayerOrException();
            AllyBotEntity existing = BotManager.botFor(player);
            if (existing != null && existing.isAlive()) {
                CommandFeedback.send(source, "Your bot is alive and well.");
                return 0;
            }
            BotManager.removeBotFor(player); // no-op if none registered; clears the corpse otherwise
            BotManager.spawnBotFor(player);
            CommandFeedback.send(source, "Bot respawned.");
            return 1;
        }));
    }
}
