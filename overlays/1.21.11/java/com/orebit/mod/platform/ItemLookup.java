package com.orebit.mod.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Version overlay (MC 1.21.11+, incl. the 26.x era): the {@code Identifier} flavor of
 * {@link ItemLookup} — 1.21.11's mojmap rename ({@code ResourceLocation} → {@code Identifier})
 * forces a flavor on {@link #byId}, which must construct the key type (the same reason
 * {@code BlockLookup} and {@code TagLookup} carry 1.21.11 flavors). {@code idOf} is unchanged from
 * the 1.19.3 flavor.
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
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }
}
