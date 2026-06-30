package com.orebit.mod.platform;

import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay (MC <b>1.21.11+</b>, inherited by the 26.x era): {@link ServerPlayer#isInvulnerableTo}
 * now returns {@code true} while the player's client hasn't reported the world loaded
 * ({@code connection.hasClientLoaded()}) — a grace-period invulnerability for a real joining client.
 *
 * <p>A fake player has no real client to send {@link ServerboundPlayerLoadedPacket}, so that flag would
 * stay {@code false} forever and the bot would be <b>permanently invulnerable</b> (no damage, no knockback)
 * regardless of game mode or our own invulnerability flags. We complete the join ourselves by feeding the
 * connection that packet: the public {@code handleAcceptPlayerLoad} thread-checks then flips the private
 * client-loaded flag. {@code BotManager} calls this on the server thread (from the player-join path), so the
 * thread-check passes and the flag is set synchronously.
 */
public final class ClientLoad {
    private ClientLoad() {}

    public static void markLoaded(ServerPlayer bot) {
        bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }
}
