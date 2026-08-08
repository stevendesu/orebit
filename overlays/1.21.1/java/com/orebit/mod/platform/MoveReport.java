package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Version overlay (MC <b>1.21.1+</b>, inherited by the 26.x era): a player's movement-based damage is driven by
 * the move packet the client sends, processed in {@code ServerGamePacketListenerImpl.handleMovePlayer} →
 * {@code setKnownMovement(...)} + {@code doCheckFallDamage(...)}. Our bot has no client and moves server-side
 * via {@code aiStep}, so it never runs that handler: {@code getKnownMovement()} (a packet-set field) stays zero
 * and the player fall path never fires — so fall, sweet-berry, cactus, magma and powder-snow damage all read as
 * nothing. We forge the same report from the bot's <i>actual</i> per-tick movement, at exactly the point the
 * packet handler would run it.
 *
 * <p><b>Why this sits at 1.21.1 rather than 26</b> (owner report 2026-08-08). It was originally filed at
 * {@code overlays/26} on the belief that pre-26 versions delivered fall damage through the ordinary entity path
 * ({@code Entity.move} → {@code checkFallDamage}). The owner then observed the bot taking fall damage on 26.2
 * and <b>not</b> on 1.21.11, with melee damage working on both — which isolates the difference to this seam
 * rather than to invulnerability, game mode or the abilities flags. The bytecode agrees that the entity path
 * cannot be relied on: {@code ServerPlayer.checkFallDamage} is a bare {@code return;} from 1.20.2 through
 * 1.21.3, so on those versions it provably deals no damage at all, and the packet path is the only one there is.
 * 1.21.1 is simply the earliest version where BOTH halves of the report exist to be forged
 * ({@code setKnownMovement} appears there; {@code doCheckFallDamage} is older — see the baseline flavour).
 *
 * <p><b>Why forging cannot double-count.</b> From 1.21.4 the {@code ServerPlayer.checkFallDamage} override
 * gained a body ending in {@code invokespecial Player.checkFallDamage} (a super call), so on those versions the
 * entity path may already have applied the damage before we get here. That is harmless:
 * {@code Entity.checkFallDamage} calls {@code resetFallDistance()} unconditionally whenever the entity is
 * grounded, and the damage itself is gated on {@code fallDistance > 0}. A second pass therefore sees a zeroed
 * distance and does nothing. The call is idempotent per tick by construction, not by timing.
 *
 * <p>API stability across the span: {@code setKnownMovement(Vec3)} is {@code public} on {@code ServerPlayer}
 * from 1.21.1 through 26.2; {@code doCheckFallDamage(double,double,double,boolean)} is {@code public} on
 * {@code ServerPlayer} through 1.21.3 and {@code public final} on {@code Entity} from 1.21.4 (javap-verified at
 * 1.21.1, 1.21.4, 1.21.11 and 26.2) — so the one call site below compiles unchanged across the whole range,
 * which is why the 26-era copy of this file was removed rather than duplicated.
 */
public final class MoveReport {
    private MoveReport() {}

    public static void after(ServerPlayer bot, double dx, double dy, double dz, boolean onGround) {
        bot.setKnownMovement(new Vec3(dx, dy, dz));
        bot.doCheckFallDamage(dx, dy, dz, onGround);
    }
}
