package com.orebit.mod.platform;

import java.util.function.Predicate;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version-selected resolver from a block-<b>tag</b> id string ("namespace:path", no leading {@code #})
 * to a membership predicate over {@link BlockState} — the tag counterpart to {@link BlockLookup#byId}.
 * Used by the {@code mining.protectedBlocks} config list's {@code #tag} entries; strictly COLD (config
 * parse, one-shot navtype re-classification, execution-side guards) — never on the A* hot path, which
 * reads tag-derived facts as a precomputed descriptor bit.
 *
 * <p>This is the OLDEST baseline flavor (MC 1.17 → 1.18.1): named tags are looked up through the
 * {@code TagCollection} registry ({@code BlockTags.getAllTags().getTag(id)}). The lookup happens
 * <b>inside</b> the returned predicate (per call, not captured at parse time) because tag collections are
 * rebound on datapack (re)load — capturing the {@code Tag<Block>} object would go stale. The overlay eras
 * compose (build.gradle.kts), so this is supplied through 1.18.1 and then OVERRIDDEN:
 * <ul>
 *   <li>{@code overlays/1.18.2} — the {@code TagKey} refactor ({@code TagCollection} deleted; membership
 *       is {@code BlockState.is(TagKey)}, which resolves through the always-current holder set),</li>
 *   <li>{@code overlays/1.19.3} — the registry-holder move to {@code Registries.BLOCK},</li>
 *   <li>{@code overlays/1.21.11} — {@code ResourceLocation} renamed to {@code Identifier}.</li>
 * </ul>
 */
public final class TagLookup {
    private TagLookup() {}

    /**
     * A predicate testing membership of the block tag registered under {@code id} ("namespace:path"),
     * or {@code null} if the id is malformed. A well-formed id naming a tag that does not exist on this
     * server matches nothing (tags are datapack data — existence can't be validated at parse time).
     */
    public static Predicate<BlockState> blockTagMatcher(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return state -> {
            Tag<Block> tag = BlockTags.getAllTags().getTag(rl);
            return tag != null && state.is(tag);
        };
    }
}
