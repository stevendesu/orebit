package com.orebit.mod.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Version overlay (MC 1.19.3+): the {@code BuiltInRegistries} flavor of {@link ItemLookup}.
 *
 * <p>The 1.19.3 registry refactor moved the static registry holders to
 * {@code net.minecraft.core.registries.BuiltInRegistries}, so the item registry is now
 * {@code BuiltInRegistries.ITEM}. {@code getKey} is otherwise identical. Overrides the baseline
 * {@code net.minecraft.core.Registry.ITEM} flavor ({@code overlays/1.17}) for 1.19.3 and up.
 *
 * <p>The key's type ({@code ResourceLocation}, renamed to {@code Identifier} in 1.21.11) never appears
 * here — {@code var} + {@code toString()} keep this one file valid all the way through the 26.x era.
 */
public final class ItemLookup {
    private ItemLookup() {}

    /** The registry id ("namespace:path") of {@code item}, or {@code ""} if it isn't registered. */
    public static String idOf(Item item) {
        var rl = BuiltInRegistries.ITEM.getKey(item);
        return rl == null ? "" : rl.toString();
    }
}
