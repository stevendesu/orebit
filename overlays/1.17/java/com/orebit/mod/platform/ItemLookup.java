package com.orebit.mod.platform;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Version-selected resolver from an {@link Item} to its registry id string — the item-side mirror of
 * {@link BlockLookup} (which resolves the other direction, id → {@link net.minecraft.world.level.block.Block}).
 *
 * <p>Confines the item-registry access type to one tiny class so callers ({@code ItemClasses} and any
 * future inventory feature) stay version-agnostic: they hand over an {@code Item} and get back its
 * {@code "namespace:path"} string (e.g. {@code "minecraft:iron_ingot"}), or {@code ""} if unregistered.
 * Returning the STRING (rather than an {@code Item}) means the classifier keys on a plain id set with no
 * per-version registry lookups, and modded items resolve to their own namespace harmlessly.
 *
 * <p>This is the OLDEST baseline flavor (MC 1.17 era): the item registry is the static
 * {@code net.minecraft.core.Registry.ITEM}. The overlay eras compose (build.gradle.kts), so this is
 * supplied to every build through 1.19.2 and then OVERRIDDEN by {@code overlays/1.19.3} (the 1.19.3
 * registry refactor moved the static holders to {@code net.minecraft.core.registries.BuiltInRegistries}).
 * No 1.21.11 override is needed: unlike {@code BlockLookup#byId} this never parses a {@code ResourceLocation}
 * / {@code Identifier}, it only calls {@code toString()} on the key, so the 1.21.11 rename is invisible here.
 */
public final class ItemLookup {
    private ItemLookup() {}

    /** The registry id ("namespace:path") of {@code item}, or {@code ""} if it isn't registered. */
    public static String idOf(Item item) {
        ResourceLocation rl = Registry.ITEM.getKey(item);
        return rl == null ? "" : rl.toString();
    }
}
