package com.orebit.mod.platform;

import java.util.function.Predicate;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version overlay (MC 1.21.11+, incl. 26.x): the {@code Identifier} flavor of {@link TagLookup} —
 * Mojang renamed {@code net.minecraft.resources.ResourceLocation} to
 * {@code net.minecraft.resources.Identifier} in 1.21.11 (the deobfuscation pass); {@code TagKey.create}
 * and {@code BlockState.is(TagKey)} are otherwise identical to the {@code overlays/1.19.3} flavor it
 * overrides. See the baseline {@link TagLookup} for the contract.
 */
public final class TagLookup {
    private TagLookup() {}

    /**
     * A predicate testing membership of the block tag registered under {@code id} ("namespace:path"),
     * or {@code null} if the id is malformed. A well-formed id naming a tag that does not exist on this
     * server matches nothing.
     */
    public static Predicate<BlockState> blockTagMatcher(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return null;
        TagKey<Block> key = TagKey.create(Registries.BLOCK, rl);
        return state -> state.is(key);
    }
}
