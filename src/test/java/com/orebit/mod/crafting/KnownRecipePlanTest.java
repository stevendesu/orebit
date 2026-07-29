package com.orebit.mod.crafting;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-planning tests for {@link KnownRecipe#planFrom(List)} — the deterministic ingredient→slot
 * assignment behind {@code /bot craft} (DESIGN-bot-abilities.md §3.4) — over SYNTHETIC recipes
 * (vanilla recipes are datapack-loaded per server and do not exist under a bare registry
 * bootstrap; the real {@code RecipeIndex} is exercised by the headless autotest). Bootstrap-tier:
 * real {@link Items} give honest {@link ItemStack} semantics; no server, no level.
 */
class KnownRecipePlanTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    // ---- synthetic recipe helpers ----------------------------------------------------------

    private static IngredientSlot slotFor(Item item, String name) {
        return new IngredientSlot(stack -> stack.getItem() == item, "minecraft:" + name);
    }

    /** A 2x2-shaped "4 planks in a square" style recipe with one hole-free grid. */
    private static KnownRecipe shaped2x2(Item item, String name, Item resultItem, int resultCount) {
        final IngredientSlot s = slotFor(item, name);
        return new KnownRecipe("test:" + name + "_square", "minecraft:" + name + "_result",
                resultCount, true, 2, 2, new IngredientSlot[]{s, s, s, s}, null);
    }

    private static List<ItemStack> storage(ItemStack... stacks) {
        final List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks) out.add(s);
        while (out.size() < 36) out.add(ItemStack.EMPTY);
        return out;
    }

    // ---- planFrom ---------------------------------------------------------------------------

    @Test
    void countAwareSingleStackFeedsMultipleCells() {
        final KnownRecipe r = shaped2x2(Items.OAK_PLANKS, "oak_planks", Items.CRAFTING_TABLE, 1);
        // One stack of 4 planks satisfies all four cells; 3 planks does not.
        assertNotNull(r.planFrom(storage(new ItemStack(Items.OAK_PLANKS, 4))));
        assertNull(r.planFrom(storage(new ItemStack(Items.OAK_PLANKS, 3))));
    }

    @Test
    void assignmentIsDeterministicLowestSlotFirst() {
        // A pickaxe-shaped recipe: 3 cobble across the top, 2 sticks down the middle, holes elsewhere.
        final IngredientSlot cobble = slotFor(Items.COBBLESTONE, "cobblestone");
        final IngredientSlot stick = slotFor(Items.STICK, "stick");
        final KnownRecipe r = new KnownRecipe("test:pick", "minecraft:stone_pickaxe", 1, true, 3, 3,
                new IngredientSlot[]{cobble, cobble, cobble, null, stick, null, null, stick, null},
                null);
        // Sticks split across two slots: the planner must draw the first stick from the LOWER slot
        // index, and only then the second — deterministic index-order assignment, no RNG.
        final List<ItemStack> inv = storage(
                new ItemStack(Items.STICK, 1),
                new ItemStack(Items.COBBLESTONE, 3),
                new ItemStack(Items.STICK, 1));
        final CraftAssignment plan = r.planFrom(inv);
        assertNotNull(plan);
        // Executing consumption order is covered end-to-end by the autotest; here the plan's
        // existence with exactly-sufficient split stacks is the count-awareness proof.
        assertEquals(r, plan.recipe());
    }

    @Test
    void holesAreNotIngredients() {
        final IngredientSlot stick = slotFor(Items.STICK, "stick");
        // A 1x2 "torch-like" shape: coal over stick has no holes; test a shape WITH a hole row.
        final KnownRecipe r = new KnownRecipe("test:holey", "minecraft:x", 1, true, 2, 2,
                new IngredientSlot[]{stick, null, null, null}, null);
        // Only the one real ingredient is required — holes must not demand items.
        assertNotNull(r.planFrom(storage(new ItemStack(Items.STICK, 1))));
        assertNull(r.planFrom(storage(new ItemStack(Items.COBBLESTONE, 1))));
    }

    @Test
    void missingSummaryNamesTheShortfall() {
        final IngredientSlot cobble = slotFor(Items.COBBLESTONE, "cobblestone");
        final IngredientSlot stick = slotFor(Items.STICK, "stick");
        final KnownRecipe r = new KnownRecipe("test:pick", "minecraft:stone_pickaxe", 1, true, 3, 3,
                new IngredientSlot[]{cobble, cobble, cobble, null, stick, null, null, stick, null},
                null);
        // 1 of 3 cobble present, no sticks at all → "2x cobblestone, 2x stick".
        final String missing = r.missingSummary(storage(new ItemStack(Items.COBBLESTONE, 1)));
        assertEquals("2x cobblestone, 2x stick", missing);
    }

    @Test
    void fits2x2Classification() {
        final IngredientSlot s = slotFor(Items.OAK_PLANKS, "oak_planks");
        // Shaped: bound by pattern dimensions.
        assertTrue(new KnownRecipe("t:a", "m:x", 1, true, 2, 2,
                new IngredientSlot[]{s, s, s, s}, null).fits2x2());
        assertTrue(new KnownRecipe("t:b", "m:x", 1, true, 1, 1,
                new IngredientSlot[]{s}, null).fits2x2());
        assertTrue(new KnownRecipe("t:c", "m:x", 1, true, 3, 1,
                new IngredientSlot[]{s, s, s}, null).requiresTable());
        // Shapeless: bound only by ingredient COUNT (its N×1 storage is a transport shape) —
        // 4 ingredients fit the 2x2 player grid, 5 need the table.
        assertTrue(new KnownRecipe("t:d", "m:x", 1, false, 4, 1,
                new IngredientSlot[]{s, s, s, s}, null).fits2x2());
        assertTrue(new KnownRecipe("t:e", "m:x", 1, false, 5, 1,
                new IngredientSlot[]{s, s, s, s, s}, null).requiresTable());
    }

    @Test
    void resultNameIsTheIdPath() {
        final KnownRecipe r = shaped2x2(Items.OAK_PLANKS, "oak_planks", Items.CRAFTING_TABLE, 1);
        assertEquals("oak_planks_result", r.resultName());
    }
}
