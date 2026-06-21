package com.orebit.mod;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Version overlay: MC 1.20.1 era (pre-1.20.2 login refactor).
 *
 * <p>Before 1.20.2 the packet listener took a 3-arg constructor; 1.20.2 added the
 * {@link net.minecraft.server.network.CommonListenerCookie} parameter. The 1.20.2+
 * flavor lives in {@code src/main/overlays/1.20.2/java}.
 */
public class FakeNetworkHandler extends ServerGamePacketListenerImpl {

    public FakeNetworkHandler(MinecraftServer server, ServerPlayer player) {
        super(server, new FakeClientConnection(), player);
    }

    @Override
    public void send(Packet<?> packet) {
        // Do nothing
    }
}
