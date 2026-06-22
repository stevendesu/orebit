package com.orebit.mod;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Version overlay (MC 1.21.6+): same socketless stand-in connection as the 1.20.5 flavor,
 * but {@code Connection.send}'s second parameter changed from {@code PacketSendListener} to
 * {@link io.netty.channel.ChannelFutureListener} in <b>1.21.6</b> (walk-back-pinned), so the
 * {@code send} override must restate the new signature.
 *
 * <p>Everything else is unchanged from {@code overlays/1.20.5}: a dummy {@link EmbeddedChannel}
 * (MC touches {@code connection.channel}) and a no-op {@code setupInboundProtocol}
 * ({@code placeNewPlayer} assigns {@code player.connection = this} in the listener constructor
 * before calling it, so the bot is fully wired). The overlay eras compose (build.gradle.kts);
 * this file overrides {@code overlays/1.20.5} for 1.21.6 and up.
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
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        // no-op: discard outbound packets — the bot has no client to receive them.
    }
}
