package com.orebit.mod.crafting;

import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

/**
 * One ingredient position of a {@link KnownRecipe}: a version-free wrapper over the vanilla
 * {@code Ingredient} predicate (DESIGN-bot-abilities.md §3.3). The {@code platform/CraftingOps}
 * overlay flavors build these at bake time — the vanilla {@code Ingredient} class itself churns
 * across the range (array-backed with an {@code EMPTY} sentinel ≤1.21.1, {@code HolderSet}-backed
 * behind {@code Optional} 1.21.2+), but every flavor can hand core a plain
 * {@code Predicate<ItemStack>} plus one representative item id for owner-facing messages.
 *
 * <p>Grid HOLES (the empty cells of a shaped recipe) are represented as {@code null} slots on the
 * {@link KnownRecipe}, never as an IngredientSlot — so this class always describes a real
 * ingredient.
 */
public class IngredientSlot {

    /** The vanilla ingredient test — true when {@code stack}'s item satisfies this slot. */
    private final Predicate<ItemStack> test;
    /** A representative matching item id (e.g. {@code minecraft:oak_planks}) for "missing X" chat. */
    private final String exampleItemId;

    public IngredientSlot(Predicate<ItemStack> test, String exampleItemId) {
        this.test = test;
        this.exampleItemId = exampleItemId;
    }

    /** Whether {@code stack} (a non-empty inventory stack) satisfies this ingredient. */
    public boolean matches(ItemStack stack) {
        return test.test(stack);
    }

    /** A short owner-facing name for this ingredient — the representative item id's path. */
    public String displayName() {
        final int colon = exampleItemId.indexOf(':');
        return colon >= 0 ? exampleItemId.substring(colon + 1) : exampleItemId;
    }
}
