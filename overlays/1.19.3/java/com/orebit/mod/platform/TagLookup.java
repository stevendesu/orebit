package com.orebit.mod.platform;

import java.util.function.Predicate;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version overlay (MC 1.19.3+): the 1.19.3 registry refactor moved the registry keys to
 * {@code net.minecraft.core.registries.Registries}, so the block-tag {@link TagKey} is created against
 * {@code Registries.BLOCK}. Overrides the {@code Registry.BLOCK_REGISTRY} flavor
 * ({@code overlays/1.18.2}); itself overridden at {@code overlays/1.21.11} (the
 * {@code ResourceLocation} → {@code Identifier} rename). See the baseline {@link TagLookup} for the
 * contract.
 */
public final class TagLookup {
    private TagLookup() {}

    /**
     * A predicate testing membership of the block tag registered under {@code id} ("namespace:path"),
     * or {@code null} if the id is malformed. A well-formed id naming a tag that does not exist on this
     * server matches nothing.
     */
    public static Predicate<BlockState> blockTagMatcher(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        TagKey<Block> key = TagKey.create(Registries.BLOCK, rl);
        return state -> state.is(key);
    }
}
