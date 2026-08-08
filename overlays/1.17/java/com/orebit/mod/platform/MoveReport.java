package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected "forge the move report" hook — the server-side stand-in for the move packet a real client
 * sends each tick. A clientless bot never runs {@code ServerGamePacketListenerImpl.handleMovePlayer}, so any
 * damage vanilla drives from that handler has to be forged here or it simply never happens.
 *
 * <p><b>Baseline (pre-1.20): NO-OP, and this is a KNOWN GAP rather than a statement that nothing is needed.</b>
 * The overlays at {@code 1.20} (fall damage) and {@code 1.21.1} (fall damage + {@code setKnownMovement})
 * forge the report on every version from 1.20 up. Below that, {@code ServerPlayer.doCheckFallDamage} exists but
 * takes DIFFERENT parameter types — {@code chiseledCompileCommon} rejects the 4-double/boolean call on
 * 1.17.1..1.19.4 with "cannot be applied to given types", which is how the boundary was located, since the
 * cached Mojang-mapped jars only reach back to 1.20.2.
 *
 * <p>So a bot on 1.17.1..1.19.4 very probably takes <b>no fall damage</b>, for the same reason it did not on
 * 1.21.11 before the {@code 1.20} flavour was added. That is deliberately left unfixed rather than guessed at:
 * closing it means finding the older signature and adding a flavour here that matches it, and no jar available
 * to this workspace can confirm that signature. It has never been observed in-game on those versions either —
 * the owner's report was 1.21.11 (broken) vs 26.2 (working).
 *
 * <p>Do NOT "fix" this by widening the call until someone can verify the older signature and, ideally, watch a
 * bot take fall damage on one of those versions.
 */
public final class MoveReport {
    private MoveReport() {}

    /** Forge the per-tick movement report after the bot has moved (no-op pre-1.20 — see the class doc). */
    public static void after(ServerPlayer bot, double dx, double dy, double dz, boolean onGround) {
        // Pre-1.20: doCheckFallDamage's parameter list differs and is unverified here. See the class doc.
    }
}
