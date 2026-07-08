package com.orebit.mod.platform;

import com.orebit.mod.FakeClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/**
 * Version-selected bot spawn (MC 1.20.5–1.21.8): {@code CommonListenerCookie.createInitial} gained
 * a {@code boolean transferred} argument in 1.20.5.
 *
 * <p>On this range {@code placeNewPlayer} still loads the player's saved .dat itself (javap-verified:
 * 1.20.5–1.21.5 bodies call {@code load(ServerPlayer):Optional}, 1.21.6–1.21.8 call
 * {@code load(ServerPlayer, ProblemReporter):Optional}), so a returning bot's inventory/XP are
 * restored by this one call. 1.21.9 moved the load out of placeNewPlayer into the vanilla login
 * flow — see {@code overlays/1.21.9}, which replays it. Earlier eras: {@code overlays/1.20.2}
 * (1-arg createInitial), {@code overlays/1.17} (no cookie).
 */
public final class BotSpawn {
    private BotSpawn() {}

    public static void place(MinecraftServer server, ServerPlayer bot) {
        server.getPlayerList().placeNewPlayer(
            new FakeClientConnection(),
            bot,
            CommonListenerCookie.createInitial(bot.getGameProfile(), false)
        );
    }
}
