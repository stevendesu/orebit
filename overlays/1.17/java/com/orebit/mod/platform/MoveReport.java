package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected "forge the move report" hook — the server-side stand-in for the move packet a real client
 * sends each tick. A clientless bot never runs {@code ServerGamePacketListenerImpl.handleMovePlayer}, so any
 * damage vanilla drives from that handler has to be forged here or it never happens at all.
 *
 * <p><b>Baseline (pre-1.20): forge the FALL half through the OLD two-argument signature.</b> Fall damage for a
 * player has always come from the move packet rather than from the ordinary entity path, so a packetless bot
 * takes none without this. The owner's version sweep (Fabric, 2026-08-08) measured exactly that: no fall damage
 * on 1.17.1 / 1.18.1 / 1.19.1, working on 1.20.1 / 1.21.1 once the {@code overlays/1.20} flavour forged it.
 *
 * <p>The signature is the only thing that differs down here, and it was recovered from the compiler rather than
 * guessed: pointing the four-argument call at 1.19.4 yields {@code required: double,boolean / found:
 * double,double,double,boolean}. Pre-1.20 the method takes the <b>Y delta and {@code onGround}</b> — the
 * horizontal components arrived later, when the report grew to carry the full movement vector for the
 * movement-based block checks. So the same fact is forged here, just with the arguments the era accepts.
 *
 * <p>{@code setKnownMovement} — the other half of the report, which drives sweet-berry / cactus / magma /
 * powder-snow damage — does not exist until 1.21.1 and is added by that flavour. Between the two,
 * {@code overlays/1.20} carries the four-argument fall call. Everything from 1.21.1 through 26.2 shares one
 * file; there is deliberately no separate 26 copy.
 *
 * <p>Forging cannot double-count: {@code Entity.checkFallDamage} resets {@code fallDistance} unconditionally
 * whenever the entity is grounded and the damage is gated on {@code fallDistance > 0}, so a version whose
 * entity path already applied the damage sees a zeroed distance here and does nothing.
 */
public final class MoveReport {
    private MoveReport() {}

    /**
     * Forge the per-tick movement report after the bot has moved. Pre-1.20 that is the fall check alone, and it
     * takes only the vertical component — {@code dx}/{@code dz} are accepted for a stable seam signature across
     * every era and deliberately unused here.
     */
    public static void after(ServerPlayer bot, double dx, double dy, double dz, boolean onGround) {
        bot.doCheckFallDamage(dy, onGround);
    }
}
