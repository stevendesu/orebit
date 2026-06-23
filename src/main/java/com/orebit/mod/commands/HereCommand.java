package com.orebit.mod.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.orebit.mod.AllyBotEntity;
import com.orebit.mod.BotPositioning;
import com.orebit.mod.platform.CommandFeedback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;

/**
 * {@code /bot here} — teleport the bot to the caller and resume follow. The escape hatch for when
 * pathfinding can't reach (a sealed area, a gap it can't yet bridge). Snaps to a safe spot near the
 * caller (same placement used at spawn) so it never lands inside a block.
 */
public final class HereCommand implements BotCommand {

    @Override
    public void contribute(LiteralArgumentBuilder<CommandSourceStack> bot) {
        bot.then(Commands.literal("here").executes(ctx -> OrebitCommands.act(ctx, (b, player, source) -> {
            BlockPos spot = BotPositioning.findSafeSpotNear(player, 3);
            if (spot != null) {
                b.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            } else {
                b.setPos(player.getX(), player.getY(), player.getZ());
            }
            b.setMode(AllyBotEntity.Mode.FOLLOW);
            CommandFeedback.send(source, "Bot teleported to you.");
        })));
    }
}
