package com.orebit.mod.platform;

import java.nio.file.Path;

import net.minecraft.server.MinecraftServer;

/**
 * MC <b>1.21+</b> flavor of {@link ConfigDir}: {@link MinecraftServer#getServerDirectory()} now returns a
 * {@code java.nio.file.Path} directly (it returned a {@code java.io.File} through 1.20.6 — the baseline
 * {@code overlays/1.17} flavor), so this returns it as-is. The 26.x era inherits this same flavor (it is
 * also ≥ 1.21). See the baseline for the loader-agnostic rationale.
 */
public final class ConfigDir {
    private ConfigDir() {}

    /** The server's run directory as a {@link Path} (1.21+: the accessor already returns a {@code Path}). */
    public static Path serverDir(MinecraftServer server) {
        return server.getServerDirectory();
    }
}
