package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Version overlay (MC 26+): a player's movement-based damage is driven by the move packet the client sends,
 * processed in {@code ServerGamePacketListenerImpl.handleMovePlayer} → {@code setKnownMovement(...)} +
 * {@code doCheckFallDamage(...)}. Our bot has no client and moves server-side via {@code aiStep}, so it never
 * runs that handler: {@code getKnownMovement()} (a packet-set field on {@code Player}) stays zero and the
 * player fall path never fires — so fall, sweet-berry, cactus, magma, powder-snow, etc. all do no damage.
 *
 * <p>We forge the same report from the bot's <i>actual</i> per-tick movement, after it has moved (exactly where
 * the packet handler runs it): {@code setKnownMovement} feeds {@code getKnownMovement()} (the movement-based
 * block checks), and {@code doCheckFallDamage} applies fall damage the way the move packet would.
 */
public final class MoveReport {
    private MoveReport() {}

    public static void after(ServerPlayer bot, double dx, double dy, double dz, boolean onGround) {
        bot.setKnownMovement(new Vec3(dx, dy, dz));
        bot.doCheckFallDamage(dx, dy, dz, onGround);
    }
}
