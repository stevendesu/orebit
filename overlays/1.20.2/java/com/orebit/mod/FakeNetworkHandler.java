package com.orebit.mod;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Version overlay: MC 1.20.2+ era (post login refactor).
 *
 * <p>1.20.2 added the {@link CommonListenerCookie} parameter to the packet-listener
 * constructor. The pre-1.20.2 flavor lives in {@code src/main/overlays/1.20.1/java}.
 */
public class FakeNetworkHandler extends ServerGamePacketListenerImpl {

    public FakeNetworkHandler(MinecraftServer server, ServerPlayer player) {
        super(
                server,
                new FakeClientConnection(),
                player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)
        );
    }

    @Override
    public void send(Packet<?> packet) {
        // Do nothing
    }
}
