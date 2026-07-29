package com.orebit.mod.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Version overlay (MC 1.19.3+): the {@code BuiltInRegistries} flavor of {@link ItemLookup}.
 *
 * <p>The 1.19.3 registry refactor moved the static registry holders to
 * {@code net.minecraft.core.registries.BuiltInRegistries}, so the item registry is now
 * {@code BuiltInRegistries.ITEM}. {@code getKey} is otherwise identical. Overrides the baseline
 * {@code net.minecraft.core.Registry.ITEM} flavor ({@code overlays/1.17}) for 1.19.3 and up.
 *
 * <p>{@code idOf} dodges the 1.21.11 {@code ResourceLocation} → {@code Identifier} rename via
 * {@code var} + {@code toString()}, but {@link #byId} must CONSTRUCT the key type — so this flavor is
 * overridden at {@code overlays/1.21.11} (the {@code Identifier.tryParse} twin, valid through 26.x).
 */
public final class ItemLookup {
    private ItemLookup() {}

    /** The registry id ("namespace:path") of {@code item}, or {@code ""} if it isn't registered. */
    public static String idOf(Item item) {
        var rl = BuiltInRegistries.ITEM.getKey(item);
        return rl == null ? "" : rl.toString();
    }

    /** The registered {@link Item} for id {@code "namespace:path"}, or {@code null} (the item-side mirror
     *  of {@link BlockLookup#byId} — same shape, same flavors). */
    public static Item byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }
}
