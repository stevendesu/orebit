package com.orebit.mod;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

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
