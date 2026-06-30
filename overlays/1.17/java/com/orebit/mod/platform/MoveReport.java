package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected "forge the move report" hook — the server-side stand-in for the move packet a real client
 * sends each tick.
 *
 * <p><b>Baseline (pre-26): NO-OP.</b> On these versions a player's movement-based damage reads the entity's
 * real movement directly, so a server-driven fake player (which moves via {@code aiStep}) needs nothing
 * forged.
 *
 * <p>Overridden at {@code overlays/26}: modern MC routes a player's movement-based damage through the data the
 * client reports in its move packet — fall damage via {@code Entity.doCheckFallDamage} and sweet-berry / cactus
 * / magma / powder-snow via {@code Player.getKnownMovement()} (which returns a <i>packet-set</i> field, not the
 * real velocity). A packet-less bot never feeds that, so {@code getKnownMovement()} reads zero and the player
 * fall path never fires. The override forges the report from the bot's actual per-tick movement.
 */
public final class MoveReport {
    private MoveReport() {}

    /** Forge the per-tick movement report after the bot has moved (no-op pre-26). */
    public static void after(ServerPlayer bot, double dx, double dy, double dz, boolean onGround) {
        // Pre-26: movement-based damage uses the entity's real movement; nothing to forge.
    }
}
