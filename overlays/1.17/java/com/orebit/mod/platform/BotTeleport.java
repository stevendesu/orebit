package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected cross-dimension bot teleport (baseline flavor, MC 1.17.1 → 1.21.1): the classic
 * six-argument {@code ServerPlayer.teleportTo(level, x, y, z, yaw, pitch)} — the {@code /tp}
 * implementation, which handles a real dimension change as well as a same-level move. This is the
 * only correct way to move the bot across levels: {@code setPos} is same-level by construction and
 * a bare {@code setServerLevel} pointer flip leaves a ghost entity (see the CLAUDE.md gotcha).
 *
 * <p>MC <b>1.21.2</b> removed this overload in favor of the {@code Set<Relative>} form — that
 * flavor lives in {@code overlays/1.21.2} and overrides this one for every build ≥ 1.21.2
 * (the overlay eras compose, highest ≤ active wins; it also serves 26.x by composition).
 */
public final class BotTeleport {
    private BotTeleport() {}

    /** Teleport the bot to {@code (x, y, z)} in {@code level} (which may be a different dimension). */
    public static void to(ServerPlayer bot, ServerLevel level,
                          double x, double y, double z, float yaw, float pitch) {
        bot.teleportTo(level, x, y, z, yaw, pitch);
    }
}
