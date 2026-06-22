package com.orebit.mod;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;

/**
 * OLDEST baseline (MC 1.17.1+): the bot's socketless stand-in connection.
 *
 * <p>The bot is spawned via {@code PlayerList.placeNewPlayer}, which builds a real
 * {@code ServerGamePacketListenerImpl} over this connection. We give it a dummy
 * {@link EmbeddedChannel} (MC touches {@code connection.channel} in {@code tick()} /
 * {@code isConnected()}) and no-op {@code setListener} (it reads a channel protocol
 * attribute a socketless connection never has; {@code player.connection = this} is assigned
 * by the listener constructor regardless, so the bot is fully wired).
 *
 * <p><b>Forge:</b> on legacy-Forge (1.17–1.19, {@code fmllegacy}) {@code placeNewPlayer} runs a
 * network filter that calls {@code NetworkHooks.getConnectionType}, which reads the
 * {@code "fml:netversion"} channel attribute and NPEs when it is null — a socketless bot never did
 * the FML handshake. We set that attribute to {@code "NONE"} (the value does not start with the
 * {@code "FML"} modded marker, so Forge treats the bot as a vanilla connection and skips the NPE).
 * It is set by attribute NAME (no Forge import) and is simply an ignored attribute on Fabric.
 *
 * <p>Note: unlike 1.20.2+ ({@code overlays/1.20.2}) this era does not override {@code send}
 * — its signature differs here, and the EmbeddedChannel simply buffers the (unread) outbound
 * packets. Newer eras discard them. The ProtocolInfo stack (1.20.5+) is {@code overlays/1.20.5}.
 */
public class FakeClientConnection extends Connection {
    public FakeClientConnection() {
        super(PacketFlow.SERVERBOUND);
        // Name the connection handler "packet_handler" (MC's pipeline convention) via an
        // initializer so it is added while the channel is registering — BEFORE channelActive, so
        // Connection.channelActive still fires and sets Connection.channel. Forge's vanilla network
        // filter injects itself with pipeline.addBefore("packet_handler", ...); a socketless bot's
        // EmbeddedChannel otherwise has no such handler → NoSuchElementException during placeNewPlayer.
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast("packet_handler", FakeClientConnection.this);
            }
        });
        channel.attr(AttributeKey.<String>valueOf("fml:netversion")).set("NONE");
    }

    @Override
    public void setListener(PacketListener handler) {
        // no-op: skip the protocol-attribute check a socketless connection can't satisfy.
    }
}
