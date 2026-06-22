package com.orebit.mod.platform;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Version-selected aliases for block constants whose registry name changed across versions.
 *
 * <p>Scope is deliberately narrow: ONLY blocks we reference whose {@code Blocks.X} constant
 * was renamed. Resolved once to a {@code static final Block}, so per-cell identity checks
 * (e.g. {@code block == VersionedBlocks.SHORT_GRASS}) cost a constant load + reference
 * compare — no method dispatch.
 *
 * <p>{@code Blocks.GRASS} was renamed to {@code SHORT_GRASS} in <b>1.20.3</b>
 * (walk-back-pinned). This is the baseline pre-rename flavor (aliases to {@code Blocks.GRASS}).
 * The overlay eras compose (build.gradle.kts), so it is supplied to 1.20.1 / 1.20.2 builds
 * and then OVERRIDDEN by the renamed flavor in {@code overlays/1.20.3}.
 */
public final class VersionedBlocks {
    private VersionedBlocks() {}

    public static final Block SHORT_GRASS = Blocks.GRASS;
}
