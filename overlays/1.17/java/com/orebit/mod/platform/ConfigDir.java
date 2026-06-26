package com.orebit.mod.platform;

import java.nio.file.Path;

import net.minecraft.server.MinecraftServer;

/**
 * Version-selected accessor for the server's run directory — the anchor the common {@link
 * com.orebit.mod.config.ConfigLoader} resolves {@code config/orebit.properties} under. Loader-agnostic by
 * construction: it reads the directory from the {@link MinecraftServer} itself (the same object every
 * loader hands us through {@code PlatformEvents.onServerStarted}), so Fabric, Forge and NeoForge all get
 * the right place with no loader-specific config-dir API — a dedicated client/server install and an
 * integrated single-player world each resolve to their own run dir.
 *
 * <p><b>Baseline flavor (MC 1.17 → 1.20.6):</b> {@link MinecraftServer#getServerDirectory()} returns a
 * {@code java.io.File}, so we adapt it to a {@link Path}. MC <b>1.21</b> changed the return type to
 * {@code Path} directly — that flavor lives in {@code overlays/1.21} and overrides this one for every
 * build ≥ 1.21 (the overlay eras compose, highest ≤ active wins). Callers see a stable {@code Path}
 * return either way, so {@code ConfigLoader} stays vanilla-API-clean.
 */
public final class ConfigDir {
    private ConfigDir() {}

    /** The server's run directory as a {@link Path} (baseline: adapt the {@code File} accessor). */
    public static Path serverDir(MinecraftServer server) {
        return server.getServerDirectory().toPath();
    }
}
