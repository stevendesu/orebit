package com.orebit.mod;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Version overlay (MC 1.20.1): the bot's socketless stand-in connection.
 *
 * <p>The bot is spawned via {@code PlayerList.placeNewPlayer}, which builds a real
 * {@code ServerGamePacketListenerImpl} over this connection. We give it a dummy
 * {@link EmbeddedChannel} (MC touches {@code connection.channel} in {@code tick()} /
 * {@code isConnected()}) and no-op {@code setListener} (it reads a channel protocol
 * attribute a socketless connection never has; {@code player.connection = this} is assigned
 * by the listener constructor regardless, so the bot is fully wired).
 *
 * <p>Note: unlike 1.20.2+ ({@code overlays/1.20.2}) this era does not override {@code send}
 * — its signature differs here, and the EmbeddedChannel simply buffers the (unread) outbound
 * packets. Newer eras discard them. The ProtocolInfo stack (1.20.5+) is {@code overlays/1.20.5}.
 */
public class FakeClientConnection extends Connection {
    public FakeClientConnection() {
        super(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(this);
    }

    @Override
    public void setListener(PacketListener handler) {
        // no-op: skip the protocol-attribute check a socketless connection can't satisfy.
    }
}
