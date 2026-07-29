package com.orebit.mod.crafting;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * One craftable vanilla recipe in version-free form — the EXECUTION-layer recipe model
 * (DESIGN-bot-abilities.md §3.1/§3.3: the server's own {@code RecipeManager} is the execution
 * truth; the stubbed {@code requirements/CraftingRecipe} hand-authored model remains the FUTURE
 * planning layer). Built once per bake by the {@code platform/CraftingOps} overlay flavors from a
 * vanilla shaped/shapeless crafting recipe; special/dynamic recipes (fireworks, dyeing, …) are
 * excluded by design (§10-D5 — they have no static ingredient list).
 *
 * <p>Named {@code KnownRecipe} (not {@code CraftingRecipe}) to avoid colliding with both the
 * vanilla interface and the {@code requirements/} spec stub — the avoid-repeated-names principle.
 *
 * <h2>Shape</h2>
 * {@link #slots} is row-major {@code width×height} for a shaped recipe with {@code null} entries
 * for the pattern's holes; a shapeless recipe is width=N, height=1, all entries non-null. The
 * synthetic grid handed back to the vanilla {@code matches}/{@code assemble} is exactly this
 * recipe-sized grid (vanilla's shaped matcher scans placements over any grid ≥ the pattern, so the
 * exact-size grid always matches at offset 0).
 */
public class KnownRecipe {

    /** The recipe's resource-location id string (e.g. {@code minecraft:crafting_table}). */
    private final String id;
    /** The result item's id string (e.g. {@code minecraft:oak_planks}). */
    private final String resultItemId;
    /** Items produced per craft operation. */
    private final int resultCount;
    /** True = shaped (the grid's SHAPE binds); false = shapeless (only the ingredient multiset). */
    private final boolean shaped;
    private final int width;
    private final int height;
    /** Row-major ingredient grid ({@code null} = pattern hole). Shapeless: N×1, all non-null. */
    private final IngredientSlot[] slots;
    /** Opaque vanilla recipe handle — only the {@code CraftingOps} flavor that made it reads it. */
    private final Object handle;

    public KnownRecipe(String id, String resultItemId, int resultCount, boolean shaped,
                       int width, int height, IngredientSlot[] slots, Object handle) {
        this.id = id;
        this.resultItemId = resultItemId;
        this.resultCount = resultCount;
        this.shaped = shaped;
        this.width = width;
        this.height = height;
        this.slots = slots;
        this.handle = handle;
    }

    public String id() {
        return id;
    }

    public String resultItemId() {
        return resultItemId;
    }

    /** The owner-facing result name — the result item id's path (what {@code /bot craft} takes). */
    public String resultName() {
        final int colon = resultItemId.indexOf(':');
        return colon >= 0 ? resultItemId.substring(colon + 1) : resultItemId;
    }

    public int resultCount() {
        return resultCount;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The opaque vanilla recipe — passed back to the {@code CraftingOps} flavor for match/assemble. */
    public Object handle() {
        return handle;
    }

    /** True when this recipe fits the 2x2 inventory grid — craftable anywhere, no table needed.
     *  A shaped recipe is bound by its pattern dimensions; a shapeless one only by its ingredient
     *  COUNT (its stored N×1 grid is a transport shape, not a placement constraint). */
    public boolean fits2x2() {
        return shaped ? (width <= 2 && height <= 2) : slots.length <= 4;
    }

    /** True when this recipe needs a 3x3 grid — the bot must be within reach of a crafting table. */
    public boolean requiresTable() {
        return !fits2x2();
    }

    /**
     * The player STORAGE slots (hotbar 0–8 + main 9–35) — the only slots crafting may draw from.
     * The full container also exposes worn armor + offhand (and 26.x moved armor behind
     * EntityEquipment), but the first 36 indices are the storage slots on every version; a player
     * can't feed the crafting grid from equipped armor, and neither may the bot.
     */
    private static final int STORAGE_SLOTS = 36;

    /**
     * Plan one craft operation from the LIVE inventory (DESIGN-bot-abilities.md §3.4): scan
     * storage slots in index order and feed each ingredient cell from the first matching stack
     * with remaining budget (count-aware — one stack may feed several cells). Deterministic, no
     * RNG. Returns {@code null} when the inventory cannot satisfy every ingredient — see
     * {@link #missingSummary} for the owner-facing reason.
     */
    public CraftAssignment planFrom(Inventory inv) {
        return planFrom(storageView(inv));
    }

    /**
     * The list-view planning seam ({@code storage.get(i)} ↔ inventory slot {@code i}) — what unit
     * tests exercise directly (constructing a vanilla {@code Inventory} headless is version-hostile;
     * the {@code NavGridView} synthetic-ctor precedent).
     */
    public CraftAssignment planFrom(java.util.List<ItemStack> storage) {
        final int n = storage.size();
        final int[] reserved = new int[n];
        final int[] chosen = new int[slots.length];
        for (int cell = 0; cell < slots.length; cell++) {
            final IngredientSlot slot = slots[cell];
            if (slot == null) { chosen[cell] = -1; continue; }
            int found = -1;
            for (int i = 0; i < n; i++) {
                final ItemStack s = storage.get(i);
                if (s.isEmpty() || s.getCount() - reserved[i] <= 0) continue;
                if (!slot.matches(s)) continue;
                found = i;
                break;
            }
            if (found < 0) return null;
            reserved[found]++;
            chosen[cell] = found;
        }
        return new CraftAssignment(this, chosen);
    }

    /**
     * An owner-facing summary of what the inventory is missing for one craft of this recipe —
     * the ingredients {@link #planFrom} could not feed, deduplicated by display name (e.g.
     * {@code "2x iron_ingot, 1x stick"}). Only meaningful when {@code planFrom} returned null;
     * runs the same deterministic assignment to find the unsatisfied cells.
     */
    public String missingSummary(Inventory inv) {
        return missingSummary(storageView(inv));
    }

    /** List-view twin of {@link #missingSummary(Inventory)} (see {@link #planFrom(java.util.List)}). */
    public String missingSummary(java.util.List<ItemStack> storage) {
        final int n = storage.size();
        final int[] reserved = new int[n];
        final java.util.LinkedHashMap<String, Integer> missing = new java.util.LinkedHashMap<>();
        for (IngredientSlot slot : slots) {
            if (slot == null) continue;
            int found = -1;
            for (int i = 0; i < n; i++) {
                final ItemStack s = storage.get(i);
                if (s.isEmpty() || s.getCount() - reserved[i] <= 0) continue;
                if (!slot.matches(s)) continue;
                found = i;
                break;
            }
            if (found >= 0) {
                reserved[found]++;
            } else {
                missing.merge(slot.displayName(), 1, Integer::sum);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : missing.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append("x ").append(e.getKey());
        }
        return sb.toString();
    }

    /** The first {@value #STORAGE_SLOTS} container slots as a list view (index ↔ inventory slot). */
    private static java.util.List<ItemStack> storageView(Inventory inv) {
        final int n = Math.min(STORAGE_SLOTS, inv.getContainerSize());
        final java.util.List<ItemStack> view = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            view.add(inv.getItem(i));
        }
        return view;
    }
}
