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
 *
 * <p><b>The teardown and the fresh spawn MUST be on separate ticks</b> (the corpse removal runs now,
 * inline; the spawn is deferred one tick via {@code server.execute}). Both in one tick sends the client
 * a single burst that tears down and rebuilds the SAME player UUID at once ({@code PlayerInfoRemove} +
 * {@code RemoveEntities(oldId)} immediately followed by {@code PlayerInfoAdd} + {@code AddEntity(newId)}),
 * which it intermittently races — leaving the just-removed dead entity's death animation bound to the
 * render (owner-observed red tint + twitch after a kill→respawn; a relog rebuilds clean). Deferring the
 * spawn puts its {@code AddEntity} in the NEXT tick's ChunkMap pass (which has already run when
 * {@code runAllTasks} drains this task), so the client gets a clean "entity removed" tick then a clean
 * "entity added" tick — never a same-UUID swap in one flush. Vanilla's own respawn dodges this a
 * different way (a surgical entity-only swap with no player-info churn), but that path hard-codes
 * {@code new ServerPlayer} and would drop the bot brain. This is the same deferral {@code onPlayerJoin}
 * already uses for the exact same reason (racing a client-side transition).
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
            BotManager.removeBotFor(player); // no-op if none registered; clears the corpse otherwise NOW
            // Fresh spawn on the NEXT tick — separated from the teardown above so the client never
            // processes a same-UUID remove+add in one packet flush (see the class Javadoc).
            source.getServer().execute(() -> BotManager.spawnBotFor(player));
            CommandFeedback.send(source, "Bot respawned.");
            return 1;
        }));
    }
}
