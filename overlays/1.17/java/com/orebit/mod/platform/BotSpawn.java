package com.orebit.mod.platform;

import com.orebit.mod.FakeClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected bot spawn (MC 1.17.1–1.20.1): pre-config-phase {@code placeNewPlayer} took no
 * login cookie. Later eras add the {@code CommonListenerCookie} argument — see
 * {@code overlays/1.20.2} (1-arg createInitial) and {@code overlays/1.20.5} (2-arg).
 *
 * <p>On this range {@code placeNewPlayer} loads the player's saved .dat itself (javap-verified
 * on 1.17.1/1.18.2/1.19.4/1.20.1: the method body calls {@code load(ServerPlayer):CompoundTag}),
 * so a returning bot's inventory/XP are restored by this one call. That stays true through
 * 1.21.8; 1.21.9 moved the load into the vanilla login flow — see {@code overlays/1.21.9}.
 */
public final class BotSpawn {
    private BotSpawn() {}

    public static void place(MinecraftServer server, ServerPlayer bot) {
        server.getPlayerList().placeNewPlayer(new FakeClientConnection(), bot);
    }
}
