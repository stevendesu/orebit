package com.orebit.mod;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Version overlay (MC 1.20.5+): the bot's socketless stand-in connection, for the
 * ProtocolInfo networking stack.
 *
 * <p>1.20.5 replaced the {@code ConnectionProtocol} enum + {@code setListener} protocol
 * wiring (the 1.20.1–1.20.4 {@code overlays/1.20.1} flavor) with ProtocolInfo +
 * {@code setupInboundProtocol}. As with the older flavor we give it a dummy
 * {@link EmbeddedChannel} (MC touches {@code connection.channel}) and no-op the protocol
 * setup — {@code placeNewPlayer} assigns {@code player.connection = this} in the listener
 * constructor before calling {@code setupInboundProtocol}, so the bot is fully wired.
 */
public class FakeClientConnection extends Connection {
    public FakeClientConnection() {
        super(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(this);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
        // no-op: the bot has no real inbound pipeline to configure (would write to the channel).
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        // no-op: discard outbound packets — the bot has no client to receive them.
    }
}
