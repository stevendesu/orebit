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
 * <p>This is the MC 1.20.2+ era flavor: {@code Blocks.GRASS} was renamed to
 * {@code SHORT_GRASS} in 1.20.3. (That exact boundary will get its own overlay era when
 * the version walk-back pins it; this era currently targets 1.21.4.)
 */
public final class VersionedBlocks {
    private VersionedBlocks() {}

    public static final Block SHORT_GRASS = Blocks.SHORT_GRASS;
}
