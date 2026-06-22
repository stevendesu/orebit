package com.orebit.mod.platform;

import com.orebit.mod.FakeClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/**
 * Version-selected bot spawn (MC 1.20.5+): {@code CommonListenerCookie.createInitial} gained
 * a {@code boolean transferred} argument in 1.20.5. This flavor is correct through 1.21.4.
 * Earlier eras: {@code overlays/1.20.2} (1-arg createInitial), {@code overlays/1.20.1}
 * (no cookie).
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
