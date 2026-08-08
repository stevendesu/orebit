package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay (MC <b>1.20 .. 1.20.6</b>): forge the FALL half of the move report.
 *
 * <p>A player's fall damage is applied from the move packet, not from the ordinary entity path.
 * {@code ServerPlayer.checkFallDamage} is a bare {@code return;} on every version from 1.20.2 through 1.21.3
 * (javap-verified), so {@code Entity.move} → {@code checkFallDamage} provably deals a {@code ServerPlayer} no
 * damage at all there — the packet path is the only one that exists. A clientless bot never runs
 * {@code ServerGamePacketListenerImpl.handleMovePlayer}, so we call {@code doCheckFallDamage} ourselves from
 * the bot's real per-tick movement, at the point the handler would have run it.
 *
 * <p>This era exists because {@code setKnownMovement} — the other half of the report, which drives the
 * movement-based block damage (sweet berry / cactus / magma / powder snow) — does not appear until 1.21.1.
 * The {@code overlays/1.21.1} flavour adds it; everything from there through 26.2 uses that one file.
 *
 * <p><b>Lower bound is a compile fact, not a choice:</b> {@code doCheckFallDamage} exists below 1.20 too but
 * with a different parameter list ({@code chiseledCompileCommon} rejects this call on 1.17.1..1.19.4 with
 * "cannot be applied to given types"), so the baseline stays a no-op and documents that gap.
 *
 * <p>Forging cannot double-count: {@code Entity.checkFallDamage} resets {@code fallDistance} unconditionally
 * whenever the entity is grounded, and the damage is gated on {@code fallDistance > 0}. On a version whose
 * entity path already applied the damage (1.21.4+, where the override gained a super call) this second pass
 * sees a zeroed distance and does nothing.
 */
public final class MoveReport {
    private MoveReport() {}

    /** Forge the per-tick movement report after the bot has moved (fall half only — see the class doc). */
    public static void after(ServerPlayer bot, double dx, double dy, double dz, boolean onGround) {
        bot.doCheckFallDamage(dx, dy, dz, onGround);
    }
}
