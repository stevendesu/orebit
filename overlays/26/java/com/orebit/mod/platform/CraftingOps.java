package com.orebit.mod.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.orebit.mod.crafting.IngredientSlot;
import com.orebit.mod.crafting.KnownRecipe;
import com.orebit.mod.OrebitCommon;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * {@link CraftingOps} flavor for MC 26.x: identical to the 1.21.4 flavor except
 * {@code Recipe#assemble(T)} dropped its {@code HolderLookup.Provider} parameter (26.1 primer —
 * recipe results are {@code ItemStackTemplate}-backed, no registry lookup needed at assemble
 * time), so no registry access is threaded anywhere. See the 1.17 baseline for the contract +
 * full override chain.
 */
public final class CraftingOps {

    private CraftingOps() {}

    public static List<KnownRecipe> listCrafting(MinecraftServer server) {
        final List<KnownRecipe> out = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            try {
                final KnownRecipe k = describe(holder);
                if (k != null) out.add(k);
            } catch (RuntimeException e) {
                OrebitCommon.LOGGER.warn("[Orebit] skipping unreadable recipe {}: {}", holder.id(), e.toString());
            }
        }
        return out;
    }

    public static boolean matches(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).matches(CraftingInput.of(w, h, grid), level);
    }

    public static ItemStack assemble(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).assemble(CraftingInput.of(w, h, grid));
    }

    public static List<ItemStack> remainders(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).getRemainingItems(CraftingInput.of(w, h, grid));
    }

    private static KnownRecipe describe(RecipeHolder<?> holder) {
        final var id = holder.id().identifier();
        if (holder.value() instanceof ShapedRecipe s) {
            final ItemStack result = s.assemble(CraftingInput.EMPTY);
            if (result == null || result.isEmpty()) return null;
            final List<Optional<Ingredient>> ing = s.getIngredients();
            final IngredientSlot[] slots = slotsOfOptionals(ing, s.getWidth() * s.getHeight());
            if (slots == null) return null;
            return new KnownRecipe(id.toString(), ItemLookup.idOf(result.getItem()), result.getCount(),
                    true, s.getWidth(), s.getHeight(), slots, s);
        }
        if (holder.value() instanceof ShapelessRecipe s) {
            if (s.placementInfo().isImpossibleToPlace()) return null;
            final ItemStack result = s.assemble(CraftingInput.EMPTY);
            if (result == null || result.isEmpty()) return null;
            final List<Ingredient> ing = s.placementInfo().ingredients();
            if (ing.isEmpty()) return null;
            final IngredientSlot[] slots = new IngredientSlot[ing.size()];
            for (int i = 0; i < ing.size(); i++) {
                final IngredientSlot slot = slotOf(ing.get(i));
                if (slot == null) return null;
                slots[i] = slot;
            }
            return new KnownRecipe(id.toString(), ItemLookup.idOf(result.getItem()), result.getCount(),
                    false, ing.size(), 1, slots, s);
        }
        return null; // special/dynamic/non-crafting recipe — excluded by design
    }

    /** Optional-wrapped shaped ingredient list → slots ({@code Optional.empty()} = hole); null poisons. */
    private static IngredientSlot[] slotsOfOptionals(List<Optional<Ingredient>> ingredients, int size) {
        final IngredientSlot[] slots = new IngredientSlot[size];
        for (int i = 0; i < ingredients.size() && i < size; i++) {
            final Optional<Ingredient> ing = ingredients.get(i);
            if (ing.isEmpty()) continue;
            final IngredientSlot slot = slotOf(ing.get());
            if (slot == null) return null;
            slots[i] = slot;
        }
        return slots;
    }

    /** One ingredient → slot, or null when it resolves to no items (poisons the recipe). */
    private static IngredientSlot slotOf(Ingredient ing) {
        final var first = ing.items().findFirst();
        if (first.isEmpty()) return null;
        return new IngredientSlot(ing, ItemLookup.idOf(first.get().value()));
    }
}
