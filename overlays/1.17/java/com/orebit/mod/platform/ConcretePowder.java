package com.orebit.mod.platform;

import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Version-selected enumeration of the 16 dyed concrete-powder blocks (the gravity-affected ones).
 *
 * <p>Confines a 26.x block-registry refactor to one tiny class so callers
 * ({@code TraversalAnalyzerMutable}) keep their block sets in common core. Resolved once into an
 * immutable {@code List} at construction; the caller folds it into its {@code Set} — no per-cell cost.
 *
 * <p>This is the baseline flavor (through MC 1.21.11): the 16 colors are individual
 * {@code Blocks.<COLOR>_CONCRETE_POWDER} constants. The overlay eras compose (build.gradle.kts),
 * so this is supplied to every build through 1.21.11 and then OVERRIDDEN in {@code overlays/26}:
 * MC 26.1 collapsed the dyed variants into a single {@code Blocks.CONCRETE_POWDER}
 * {@code ColorCollection}, so the 26 flavor returns {@code Blocks.CONCRETE_POWDER.asList()}.
 */
public final class ConcretePowder {
    private ConcretePowder() {}

    public static List<Block> all() {
        return List.of(
            Blocks.BLACK_CONCRETE_POWDER, Blocks.BLUE_CONCRETE_POWDER, Blocks.BROWN_CONCRETE_POWDER,
            Blocks.CYAN_CONCRETE_POWDER, Blocks.GRAY_CONCRETE_POWDER, Blocks.GREEN_CONCRETE_POWDER,
            Blocks.LIGHT_BLUE_CONCRETE_POWDER, Blocks.LIGHT_GRAY_CONCRETE_POWDER, Blocks.LIME_CONCRETE_POWDER,
            Blocks.MAGENTA_CONCRETE_POWDER, Blocks.ORANGE_CONCRETE_POWDER, Blocks.PINK_CONCRETE_POWDER,
            Blocks.PURPLE_CONCRETE_POWDER, Blocks.RED_CONCRETE_POWDER, Blocks.WHITE_CONCRETE_POWDER,
            Blocks.YELLOW_CONCRETE_POWDER
        );
    }
}
