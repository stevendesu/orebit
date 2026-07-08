package com.orebit.mod.platform;

import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected cross-dimension bot teleport (MC 1.21.2 → 26.x): 1.21.2 removed the classic
 * six-argument {@code teleportTo} overload; the surviving form takes a {@code Set<Relative>} of
 * relative-movement flags plus a trailing {@code setCamera} boolean. An empty set + {@code false}
 * reproduces the absolute {@code /tp} default. Verified identical at 1.21.2 / 1.21.5 / 1.21.11 /
 * 26.2, so the 26 era inherits this flavor by composition — no {@code overlays/26} copy. The
 * baseline (1.17.1 → 1.21.1) lives in {@code overlays/1.17}.
 */
public final class BotTeleport {
    private BotTeleport() {}

    /** Teleport the bot to {@code (x, y, z)} in {@code level} (which may be a different dimension). */
    public static void to(ServerPlayer bot, ServerLevel level,
                          double x, double y, double z, float yaw, float pitch) {
        bot.teleportTo(level, x, y, z, Set.of(), yaw, pitch, false);
    }
}
