package com.orebit.mod.crafting;

import java.util.ArrayList;
import java.util.List;

import com.orebit.mod.platform.CraftingOps;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * A planned mapping of one craft operation's ingredient cells onto REAL inventory slots — the
 * output of {@link KnownRecipe#planFrom} (DESIGN-bot-abilities.md §3.3/§3.4). Executing it is the
 * whole craft: build the recipe-sized synthetic grid, let the vanilla recipe verify + assemble
 * through the {@link CraftingOps} seam (the Carpet/Crafter-block headless precedent — no menus, no
 * recipe book, no packets), then mutate the bot's real inventory: consume the reserved items,
 * insert the result and any container remainders (empty buckets, …), overflow dropped at the
 * bot's feet.
 */
public class CraftAssignment {

    private final KnownRecipe recipe;
    /** Inventory slot index feeding each grid cell, row-major; {@code -1} = pattern hole. */
    private final int[] invSlotPerCell;

    CraftAssignment(KnownRecipe recipe, int[] invSlotPerCell) {
        this.recipe = recipe;
        this.invSlotPerCell = invSlotPerCell;
    }

    public KnownRecipe recipe() {
        return recipe;
    }

    /**
     * Execute this one craft operation against {@code bot}'s live inventory. Returns the number of
     * result items produced (0 = the vanilla recipe refused the planned grid — a component/NBT
     * subtlety our item-level assignment can't see; the caller treats it as "can't craft this").
     * The plan must be executed on the tick it was made (it holds live slot indices).
     */
    public int execute(ServerLevel level, ServerPlayer bot) {
        final Inventory inv = bot.getInventory();
        // The recipe-sized synthetic grid: single-item copies of the reserved stacks, EMPTY holes.
        final List<ItemStack> grid = new ArrayList<>(invSlotPerCell.length);
        for (int cell = 0; cell < invSlotPerCell.length; cell++) {
            final int slot = invSlotPerCell[cell];
            if (slot < 0) {
                grid.add(ItemStack.EMPTY);
            } else {
                final ItemStack one = inv.getItem(slot).copy();
                one.setCount(1);
                grid.add(one);
            }
        }
        final int w = recipe.width();
        final int h = recipe.height();
        // Vanilla verification: our assignment matched item-by-item; let the REAL recipe confirm the
        // whole grid (shaped placement, component-sensitive ingredients) before anything mutates.
        if (!CraftingOps.matches(level, recipe.handle(), grid, w, h)) return 0;
        final ItemStack result = CraftingOps.assemble(level, recipe.handle(), grid, w, h);
        if (result.isEmpty()) return 0;
        final List<ItemStack> remainders = CraftingOps.remainders(level, recipe.handle(), grid, w, h);
        // Consume the reserved items (removeItem handles emptying the slot).
        for (final int slot : invSlotPerCell) {
            if (slot >= 0) inv.removeItem(slot, 1);
        }
        final int produced = result.getCount();
        giveOrDrop(bot, inv, result);
        for (ItemStack r : remainders) {
            if (r != null && !r.isEmpty()) giveOrDrop(bot, inv, r);
        }
        return produced;
    }

    /** Insert {@code stack} into the inventory; whatever doesn't fit is dropped at the bot's feet. */
    private static void giveOrDrop(ServerPlayer bot, Inventory inv, ItemStack stack) {
        inv.add(stack); // partial adds shrink the stack in place
        if (!stack.isEmpty()) bot.drop(stack, false);
    }
}
