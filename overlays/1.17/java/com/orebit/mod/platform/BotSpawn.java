package com.orebit.mod.platform;

import com.orebit.mod.FakeClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected bot spawn (MC 1.20.1): pre-config-phase {@code placeNewPlayer} took no
 * login cookie. Later eras add the {@code CommonListenerCookie} argument — see
 * {@code overlays/1.20.2} (1-arg createInitial) and {@code overlays/1.20.5} (2-arg).
 */
public final class BotSpawn {
    private BotSpawn() {}

    public static void place(MinecraftServer server, ServerPlayer bot) {
        server.getPlayerList().placeNewPlayer(new FakeClientConnection(), bot);
    }
}
