package com.orebit.mod.platform;

import java.util.ArrayList;
import java.util.List;

import com.orebit.mod.crafting.IngredientSlot;
import com.orebit.mod.crafting.KnownRecipe;
import com.orebit.mod.OrebitCommon;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Version seam for headless server-side crafting (DESIGN-bot-abilities.md §3.2): enumerate the
 * server's shaped+shapeless crafting recipes into version-free {@link KnownRecipe}s, and run one
 * recipe's {@code matches}/{@code assemble}/{@code getRemainingItems} against a synthetic
 * recipe-sized grid — the vanilla-Crafter-block / Carpet-autoCraftingTable precedent (no player, no
 * menu, no recipe book). Anchored on {@code MinecraftServer#getRecipeManager()}, the one accessor
 * that is byte-stable across the whole 1.17.1→26.2 range. Tick-thread only (the shared menu stub
 * and the per-call containers are not thread-safe); cold — a handful of calls per craft operation
 * and one enumeration per bake, never a hot path.
 *
 * <p><b>This 1.17 baseline</b> (MC 1.17.1 → 1.19.3): {@code Recipe<C extends Container>} —
 * {@code getAllRecipesFor} returns raw recipes with {@code getId()}, the grid is a
 * {@code new CraftingContainer(menu, w, h)} (a CLASS here; the null-menu NPE trap is dodged with a
 * no-op menu stub), {@code assemble(C)} and {@code getResultItem()} take no registry argument.
 * Overridden at <b>1.19.4</b> ({@code assemble}/{@code getResultItem} + {@code RegistryAccess}),
 * <b>1.20</b> ({@code CraftingContainer} → interface, {@code TransientCraftingContainer}),
 * <b>1.20.2</b> ({@code RecipeHolder}), <b>1.20.5</b> ({@code HolderLookup.Provider}),
 * <b>1.21</b> ({@code CraftingInput}), <b>1.21.2</b> (recipe rework: {@code getRecipes()}
 * enumeration, {@code Optional} ingredient lists, no {@code getResultItem}),
 * <b>1.21.4</b> ({@code Ingredient#items()} → Stream), and <b>26</b> ({@code assemble(T)} one-arg).
 */
public final class CraftingOps {

    private CraftingOps() {}

    /**
     * The no-op menu the synthetic {@link CraftingContainer} reports slot changes to —
     * {@code CraftingContainer.setItem} calls {@code menu.slotsChanged}, so a {@code null} menu
     * NPEs (the trap Carpet's autoCraftingTable needed a mixin for). With no registered slots or
     * listeners every callback is a no-op. (A null {@code MenuType} is the vanilla
     * {@code InventoryMenu} precedent.)
     */
    private static final AbstractContainerMenu NO_MENU = new AbstractContainerMenu(null, 0) {
        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }
    };

    /**
     * Enumerate the server's crafting recipes as {@link KnownRecipe}s — shaped + shapeless only
     * (special/dynamic recipes have no static ingredient list, §10-D5). A recipe that fails to
     * describe (unresolvable ingredient, modded subclass throwing) is skipped, never fatal.
     */
    public static List<KnownRecipe> listCrafting(MinecraftServer server) {
        final List<KnownRecipe> out = new ArrayList<>();
        for (CraftingRecipe r : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                final KnownRecipe k = describe(r);
                if (k != null) out.add(k);
            } catch (RuntimeException e) {
                OrebitCommon.LOGGER.warn("[Orebit] skipping unreadable recipe {}: {}", r.getId(), e.toString());
            }
        }
        return out;
    }

    /** Whether the planned {@code grid} (row-major {@code w×h}, EMPTY holes) satisfies {@code handle}. */
    public static boolean matches(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).matches(fill(grid, w, h), level);
    }

    /** The result stack of crafting {@code handle} from {@code grid} (a fresh stack). */
    public static ItemStack assemble(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).assemble(fill(grid, w, h));
    }

    /** The per-cell container remainders (empty buckets, …) of crafting {@code handle} from {@code grid}. */
    public static List<ItemStack> remainders(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h) {
        return ((CraftingRecipe) handle).getRemainingItems(fill(grid, w, h));
    }

    private static CraftingContainer fill(List<ItemStack> grid, int w, int h) {
        final CraftingContainer c = new CraftingContainer(NO_MENU, w, h);
        for (int i = 0; i < grid.size(); i++) {
            c.setItem(i, grid.get(i));
        }
        return c;
    }

    private static KnownRecipe describe(CraftingRecipe r) {
        final ItemStack result = r.getResultItem();
        if (result == null || result.isEmpty()) return null;
        final var id = r.getId(); // var: the id type is renamed at 1.21.11 (ResourceLocation → Identifier)
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
        return null; // special/dynamic recipe — excluded by design
    }

    /**
     * Wrap a vanilla ingredient list as {@link IngredientSlot}s (row-major; EMPTY ingredients →
     * {@code null} holes). Returns {@code null} — poisoning the whole recipe — when a NON-empty
     * ingredient resolves to zero items (an unresolvable tag): treating it as a hole would make the
     * recipe look craftable without that ingredient.
     */
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
