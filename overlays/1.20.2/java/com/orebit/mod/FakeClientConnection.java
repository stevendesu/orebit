package com.orebit.mod;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Version overlay (MC 1.20.2–1.20.4): the bot's socketless stand-in connection.
 *
 * <p>Same as the 1.20.1 flavor (dummy {@link EmbeddedChannel} + no-op {@code setListener}),
 * but 1.20.2 added the {@code boolean flush} parameter to {@code send}, so this era also
 * discards outbound packets via the 3-arg override. The ProtocolInfo stack (1.20.5+) swaps
 * {@code setListener} for {@code setupInboundProtocol} — see {@code overlays/1.20.5}.
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

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        // no-op: discard outbound packets — the bot has no client to receive them.
    }
}
