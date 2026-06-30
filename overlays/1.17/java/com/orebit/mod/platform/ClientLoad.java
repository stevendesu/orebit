package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected hook to mark a freshly-spawned fake player's client as "loaded".
 *
 * <p><b>Baseline (≤ 1.21.10): NO-OP.</b> These versions have no client-load tracking, so a fake player
 * placed via {@code PlayerList.placeNewPlayer} is immediately a full, damageable participant.
 *
 * <p>Overridden at {@code overlays/1.21.11}, where {@link ServerPlayer#isInvulnerableTo} began returning
 * {@code true} for as long as {@code connection.hasClientLoaded()} is {@code false} — a grace-period
 * invulnerability covering the window while a real player's client streams in the world. A fake player has
 * no real client and never sends the "player loaded" packet, so without intervention that flag stays false
 * and the bot is <b>permanently invulnerable</b> (takes no damage or knockback from anything). The override
 * feeds the connection that packet itself; this baseline does nothing because the gate doesn't exist yet.
 */
public final class ClientLoad {
    private ClientLoad() {}

    /** Mark {@code bot}'s connection client-loaded so vanilla stops treating it as still-loading (no-op here). */
    public static void markLoaded(ServerPlayer bot) {
        // No client-load gate on this version — nothing to do.
    }
}
