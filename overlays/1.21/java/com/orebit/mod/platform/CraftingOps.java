package com.orebit.mod.platform;

import java.util.ArrayList;
import java.util.List;

import com.orebit.mod.crafting.IngredientSlot;
import com.orebit.mod.crafting.KnownRecipe;
import com.orebit.mod.OrebitCommon;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * {@link CraftingOps} flavor for MC 1.21–1.21.1: {@code Recipe<T extends RecipeInput>} — the
 * synthetic grid is a {@link CraftingInput} ({@code CraftingInput.of(w, h, items)}), so the
 * container + no-op-menu machinery of the older flavors disappears entirely. Everything else
 * matches the 1.20.5 flavor (see the 1.17 baseline for the contract + full override chain).
 * Overridden at 1.21.2.
 */
public final class CraftingOps {

    private CraftingOps() {}

    public static List<KnownRecipe> listCrafting(MinecraftServer server) {
        final List<KnownRecipe> out = new ArrayList<>();
        final HolderLookup.Provider access = server.registryAccess();
        for (RecipeHolder<CraftingRecipe> holder
                : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                final KnownRecipe k = describe(holder, access);
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
        return ((CraftingRecipe) handle).assemble(CraftingInput.of(w, h, grid), level.registryAccess());
    }

    public static List<ItemStack> remainders(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).getRemainingItems(CraftingInput.of(w, h, grid));
    }

    private static KnownRecipe describe(RecipeHolder<CraftingRecipe> holder, HolderLookup.Provider access) {
        final CraftingRecipe r = holder.value();
        final ItemStack result = r.getResultItem(access);
        if (result == null || result.isEmpty()) return null;
        final var id = holder.id();
        final String resultId = ItemLookup.idOf(result.getItem());
        if (r instanceof ShapedRecipe s) {
            final IngredientSlot[] slots = slotsOf(s.getIngredients(), s.getWidth() * s.getHeight());
            if (slots == null) return null;
            return new KnownRecipe(id.toString(), resultId, result.getCount(),
                    true, s.getWidth(), s.getHeight(), slots, r);
        }
        if (r instanceof ShapelessRecipe s) {
            final List<Ingredient> ing = s.getIngredients();
            if (ing.isEmpty()) return null;
            final IngredientSlot[] slots = slotsOf(ing, ing.size());
            if (slots == null) return null;
            return new KnownRecipe(id.toString(), resultId, result.getCount(), false, ing.size(), 1, slots, r);
        }
        return null;
    }

    private static IngredientSlot[] slotsOf(List<Ingredient> ingredients, int size) {
        final IngredientSlot[] slots = new IngredientSlot[size];
        for (int i = 0; i < ingredients.size() && i < size; i++) {
            final Ingredient ing = ingredients.get(i);
            if (ing == null || ing.isEmpty()) continue;
            final ItemStack[] options = ing.getItems();
            if (options.length == 0 || options[0].isEmpty()) return null;
            slots[i] = new IngredientSlot(ing, ItemLookup.idOf(options[0].getItem()));
        }
        return slots;
    }
}
