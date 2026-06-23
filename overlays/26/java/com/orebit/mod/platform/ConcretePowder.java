package com.orebit.mod.platform;

import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 26.x flavor of {@link ConcretePowder}: MC 26.1 collapsed the 16 dyed concrete-powder blocks into
 * a single {@code Blocks.CONCRETE_POWDER} {@code ColorCollection<Block>} (the same color refactor
 * that produced {@code Blocks.CONCRETE}). {@code asList()} yields all 16 in color order. See the
 * 1.17 baseline for the full overlay-composition note.
 */
public final class ConcretePowder {
    private ConcretePowder() {}

    public static List<Block> all() {
        return Blocks.CONCRETE_POWDER.asList();
    }
}
