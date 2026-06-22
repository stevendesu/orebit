package com.orebit.mod.platform;

import com.orebit.mod.FakeClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/**
 * Version-selected bot spawn (MC 1.20.2–1.20.4): {@code placeNewPlayer} gained the
 * {@code CommonListenerCookie} login argument in 1.20.2; {@code createInitial} takes the
 * {@code GameProfile} alone until 1.20.5 adds a {@code boolean} (see {@code overlays/1.20.5}).
 */
public final class BotSpawn {
    private BotSpawn() {}

    public static void place(MinecraftServer server, ServerPlayer bot) {
        server.getPlayerList().placeNewPlayer(
            new FakeClientConnection(),
            bot,
            CommonListenerCookie.createInitial(bot.getGameProfile())
        );
    }
}
