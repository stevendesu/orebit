package com.orebit.mod.platform;

import net.minecraft.world.level.block.BambooBlock;
import net.minecraft.world.level.block.Block;

/**
 * Version-selected block-class identity checks whose {@code Block} subclass was renamed.
 *
 * <p>The bamboo stalk block class was {@code BambooBlock} until <b>1.19.4</b> renamed it to
 * {@code BambooStalkBlock} (when the bamboo wood set was added). This is the OLDEST baseline
 * flavor (MC 1.17 era): {@code BambooBlock}. The overlay eras compose (build.gradle.kts), so
 * this is supplied to every build through 1.19.2 and then OVERRIDDEN by the
 * {@code BambooStalkBlock} flavor in {@code overlays/1.19.4}. Callers use a plain static call
 * (monomorphic, JIT-inlinable) instead of naming the version-specific class.
 */
public final class BlockKinds {
    private BlockKinds() {}

    public static boolean isBambooStalk(Block block) {
        return block instanceof BambooBlock;
    }
}
