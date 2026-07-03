package com.orebit.mod.platform;

import java.util.function.Predicate;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version overlay (MC 1.18.2+): the {@code TagKey} refactor deleted {@code TagCollection}; a named tag
 * is now an immutable {@link TagKey} created against the block registry key ({@code
 * Registry.BLOCK_REGISTRY} in this era) and membership is {@code BlockState.is(TagKey)}, which resolves
 * through the holder's always-current bound tag set (so datapack reloads are picked up for free — no
 * per-call registry lookup needed). Overrides the baseline {@code TagCollection} flavor
 * ({@code overlays/1.17}) for 1.18.2 → 1.19.2; itself overridden at {@code overlays/1.19.3} (registry
 * holders moved to {@code Registries.BLOCK}). See the baseline {@link TagLookup} for the contract.
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
        TagKey<Block> key = TagKey.create(Registry.BLOCK_REGISTRY, rl);
        return state -> state.is(key);
    }
}
