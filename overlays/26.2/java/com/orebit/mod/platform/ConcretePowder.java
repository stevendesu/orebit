package com.orebit.mod.platform;

import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 26.2+ flavor of {@link ConcretePowder}: MC <b>26.2</b> (NOT 26.1 — verified against the jars: 26.1
 * still ships the 16 individual {@code *_CONCRETE_POWDER} blocks) collapsed the dyed concrete-powder
 * blocks into a single {@code Blocks.CONCRETE_POWDER} {@code ColorCollection<Block>} (the same color
 * refactor that produced {@code Blocks.CONCRETE}). {@code asList()} yields all 16 in color order. The
 * era dir is therefore {@code overlays/26.2}, so 26.0/26.1 fall back to the 1.17 baseline (16 constants)
 * and only 26.2+ takes this flavor. See the 1.17 baseline for the full overlay-composition note.
 */
public final class ConcretePowder {
    private ConcretePowder() {}

    public static List<Block> all() {
        return Blocks.CONCRETE_POWDER.asList();
    }
}
