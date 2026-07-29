package com.orebit.mod;

import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.crafting.CraftAssignment;
import com.orebit.mod.crafting.KnownRecipe;
import com.orebit.mod.crafting.RecipeIndex;
import com.orebit.mod.platform.BlockShapes;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.ItemLookup;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceScan;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The {@code /bot craft} machine, owned by {@link AllyBotEntity} (the {@link BotGatherer} component
 * pattern — DESIGN-bot-abilities.md §2.1/§3.5): craft {@code count} of a result item from the
 * bot's REAL inventory. A 2x2 recipe crafts anywhere; a 3x3 recipe needs the bot within reach of a
 * crafting table — it seeks the nearest one ({@code crafting.tableSearchRadius}), and failing
 * that may place a temporary table from inventory (crafting itself one, 2x2, when it only carries
 * planks — {@code crafting.placeTable}) and take it back afterwards
 * ({@code crafting.reclaimTable}, the §10-D3 narrowly-waived reclaim break).
 *
 * <p>Crafting is headless and server-side (the vanilla-Crafter/Carpet precedent, via the
 * {@code platform/CraftingOps} seam): no menus, no recipe book, no packets — one craft operation
 * per tick (a cadence, not a timer), each op re-planned against the LIVE inventory so every
 * transition is state-driven. Movement rides {@link BotNavigator#driveToward} + the
 * {@code navGaveUp} protocol; the reclaim break rides {@link BotMining#requestReclaim}.
 */
final class BotCrafter {

    private final AllyBotEntity bot;

    /** Phases of a {@code /bot craft} run. Stepped one phase per tick by {@link #craftLoopTick}. */
    private enum CraftPhase { PLAN, SEEK_TABLE, PLACE_TABLE, CRAFT, RECLAIM }

    /** Player interaction reach (blocks, eye→block-centre) for "near enough to a crafting table"
     *  — the same bound {@link BotGatherer} uses for mining reach. */
    private static final double CRAFT_REACH = 4.5;
    /** Tight drive-arrival tolerance while approaching a table/drop (see BotGatherer's rationale:
     *  looser arrival would stop short and never satisfy the real reach gate). */
    private static final double CRAFT_ARRIVE_DIST = 0.6;
    private static final double CRAFT_ARRIVE_Y = 0.6;

    private CraftPhase craftPhase;      // current phase (null = inactive)
    private String craftName;           // requested result name (item id path, e.g. "oak_planks")
    private int craftTarget;            // requested count of RESULT items
    private int produced;               // result items produced so far this run
    private BlockPos tablePos;          // the crafting table in use (found or bot-placed)
    private boolean botPlacedTable;     // tablePos was placed by the bot this run (→ reclaim)
    private boolean tableUnreachable;   // the nearest table was proven unreachable → treat as none
    /** RECLAIM: the dropped crafting-table item being chased (lifecycle-tracked, no timers). */
    private ItemEntity reclaimDrop;
    /** The 8 horizontal neighbour offsets, nearest-first, for the temporary-table placement spot. */
    private static final int[][] RING_OFFSETS =
            {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    BotCrafter(AllyBotEntity bot) {
        this.bot = bot;
    }

    // ---- Read-only observation seams (headless harness only; NO logic change) -------------------

    /** Current craft phase name for the harness ({@code "IDLE"} when no run is active). Read-only. */
    String phaseName() {
        return craftPhase == null ? "IDLE" : craftPhase.name();
    }

    /** Result items produced so far this run. Read-only. */
    int craftedCount() {
        return produced;
    }

    /**
     * {@code /bot craft <item> [count]} entry point (mode switch/plan reset live on
     * {@link AllyBotEntity#startCraft}). {@code name} is a result name from
     * {@link RecipeIndex#names()}; {@code count} is the target number of RESULT items (one craft
     * op can produce several, e.g. 4 planks).
     */
    void startCraft(String name, int count) {
        this.craftName = name;
        this.craftTarget = Math.max(1, count);
        this.produced = 0;
        this.tablePos = null;
        this.botPlacedTable = false;
        this.tableUnreachable = false;
        this.reclaimDrop = null;
        bot.navigator().clearNavGaveUp();
        this.craftPhase = CraftPhase.PLAN;
    }

    /** One tick of the {@code /bot craft} state machine — dispatch to the current phase. */
    void craftLoopTick() {
        if (craftPhase == null) { bot.setMode(AllyBotEntity.Mode.STAY); return; } // defensive
        ServerLevel level = (ServerLevel) Worlds.of(bot);
        switch (craftPhase) {
            case PLAN -> craftPlan(level);
            case SEEK_TABLE -> craftSeekTable(level);
            case PLACE_TABLE -> craftPlaceTable(level);
            case CRAFT -> craftCraft(level);
            case RECLAIM -> craftReclaim(level);
        }
    }

    /**
     * PLAN: triage the request against the live inventory (§3.4 selection: a feasible 2x2 recipe
     * beats a feasible table recipe beats nothing). Routes straight to CRAFT (2x2), to SEEK_TABLE
     * (3x3), or refuses honestly with the missing-ingredient summary.
     */
    private void craftPlan(ServerLevel level) {
        bot.setForward(0.0f);
        bot.lookAtPlayer(bot.owner());
        if (!RecipeIndex.ready() || RecipeIndex.forName(craftName).isEmpty()) {
            finish("I don't know how to make " + craftName + ".");
            return;
        }
        final CraftAssignment pick = pickOp(bot.getInventory());
        if (pick == null) {
            finish("I can't craft " + craftName + " — missing: "
                    + RecipeIndex.forName(craftName).get(0).missingSummary(bot.getInventory()) + ".");
            return;
        }
        craftPhase = pick.recipe().requiresTable() ? CraftPhase.SEEK_TABLE : CraftPhase.CRAFT;
    }

    /**
     * SEEK_TABLE: have a crafting table within reach. Finds the nearest one via the resource
     * layer's live section scan (crafting_table is a locatable), drives to it, and when none is
     * near (or the nearest proved unreachable) falls into the temporary-table affordance:
     * place a carried table, crafting one first (2x2, from planks) if need be — the owner's
     * "conditionally place-then-break a crafting table" note. Every dead end refuses honestly.
     */
    private void craftSeekTable(ServerLevel level) {
        // A known table that stopped being a table (broken/replaced) is forgotten.
        if (tablePos != null && !level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            tablePos = null;
            botPlacedTable = false;
        }
        final int radius = ConfigLoader.config().tableSearchRadius();
        if (tablePos == null && !tableUnreachable && radius > 0) {
            final BlockPos found = ResourceScan.nearestLoadedCell(
                    level, bot.blockPosition(), ResourceClasses.CRAFTING_TABLE);
            if (found != null) {
                final double dx = found.getX() + 0.5 - bot.getX();
                final double dy = found.getY() + 0.5 - bot.getY();
                final double dz = found.getZ() + 0.5 - bot.getZ();
                if (dx * dx + dy * dy + dz * dz <= (double) radius * radius) tablePos = found;
            }
        }
        if (tablePos == null) {
            bot.setForward(0.0f);
            if (!ConfigLoader.config().placeTable()) {
                finish("I need a crafting table for " + craftName + " and there's none nearby.");
                return;
            }
            final Inventory inv = bot.getInventory();
            if (findTableItemSlot(inv) >= 0) {
                craftPhase = CraftPhase.PLACE_TABLE;
                return;
            }
            // One level of recursion: a crafting table is itself a 2x2 craft (4 planks).
            for (KnownRecipe r : RecipeIndex.forName("crafting_table")) {
                if (!r.fits2x2()) continue;
                final CraftAssignment plan = r.planFrom(inv);
                if (plan == null) continue;
                if (plan.execute(level, bot) <= 0) {
                    finish("the crafting_table recipe refused my ingredients.");
                    return;
                }
                bot.swing(InteractionHand.MAIN_HAND);
                bot.chat("[bot] made a crafting table first.");
                craftPhase = CraftPhase.PLACE_TABLE;
                return;
            }
            finish("I need a crafting table for " + craftName
                    + " (no table nearby and I can't make one).");
            return;
        }
        if (withinReach(tablePos)) {
            bot.navigator().clearPlan();
            craftPhase = CraftPhase.CRAFT;
            return;
        }
        // Tight arrival so the drive keeps closing until the real reach gate passes (BotGatherer's rule).
        bot.navigator().driveToward(tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5,
                tablePos, CRAFT_ARRIVE_DIST, CRAFT_ARRIVE_Y);
        if (bot.navigator().navGaveUp()) {
            bot.navigator().clearNavGaveUp();
            bot.navigator().clearPlan();
            tableUnreachable = true; // fall into the place-a-table affordance next tick
            tablePos = null;
        }
    }

    /**
     * PLACE_TABLE: put the carried crafting table on a free neighbouring cell (air with a solid
     * floor, nearest-first around the bot's feet) and remember it as bot-placed for the reclaim.
     */
    private void craftPlaceTable(ServerLevel level) {
        bot.setForward(0.0f);
        final BlockPos spot = findPlacementSpot(level);
        if (spot == null) {
            finish("no room to place a crafting table here.");
            return;
        }
        final Inventory inv = bot.getInventory();
        final int slot = findTableItemSlot(inv);
        if (slot < 0) { // defensive: the item vanished between ticks
            finish("I lost the crafting table I meant to place.");
            return;
        }
        inv.removeItem(slot, 1);
        bot.lookAtCell(spot.getX(), spot.getY(), spot.getZ());
        WorldEdits.placeBlock(level, spot, Blocks.CRAFTING_TABLE.defaultBlockState());
        bot.swing(InteractionHand.MAIN_HAND);
        tablePos = spot.immutable();
        botPlacedTable = true;
        craftPhase = CraftPhase.CRAFT;
    }

    /**
     * CRAFT: one craft operation per tick, each re-planned against the LIVE inventory (§3.4 —
     * milestone = the inventory mutation itself; a 2x2-feasible op never needs the table even if
     * one is set). Quota met / ingredients exhausted / recipe refusal all end the run honestly.
     */
    private void craftCraft(ServerLevel level) {
        bot.setForward(0.0f);
        final Inventory inv = bot.getInventory();
        final CraftAssignment pick = pickOp(inv);
        if (pick == null) {
            if (produced > 0) {
                finish("crafted " + produced + " " + craftName + " — out of ingredients.");
            } else {
                finish("I can't craft " + craftName + " — missing: "
                        + RecipeIndex.forName(craftName).get(0).missingSummary(inv) + ".");
            }
            return;
        }
        if (pick.recipe().requiresTable()) {
            // The table must still be there and within reach for a 3x3 op.
            if (tablePos == null || !level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)
                    || !withinReach(tablePos)) {
                craftPhase = CraftPhase.SEEK_TABLE;
                return;
            }
            bot.lookAtCell(tablePos.getX(), tablePos.getY(), tablePos.getZ());
        } else {
            bot.lookAtPlayer(bot.owner());
        }
        final int got = pick.execute(level, bot);
        if (got <= 0) { // vanilla refused the planned grid (component-sensitive ingredient) — be honest
            finish("the " + craftName + " recipe refused my ingredients.");
            return;
        }
        bot.swing(InteractionHand.MAIN_HAND);
        produced += got;
        if (produced >= craftTarget) {
            finish("crafted " + produced + " " + craftName + ".");
        }
    }

    /**
     * RECLAIM: take the temporary table back — timed-break the exact bot-placed cell (the §10-D3
     * waived break), then chase its dropped item by LIFECYCLE exactly like gather-COLLECT
     * (removed → done; airborne → wait; grounded → drive to its live position; unreachable →
     * abandon honestly). Runs after the final craft chat; ends in STAY.
     */
    private void craftReclaim(ServerLevel level) {
        if (reclaimDrop != null) {
            if (reclaimDrop.isRemoved()) { // picked up (or gone) — either way the run is over
                bot.setMode(AllyBotEntity.Mode.STAY);
                return;
            }
            if (!EntityState.onGround(reclaimDrop) && !reclaimDrop.isInWater()) {
                bot.setForward(0.0f); // mid-air: it lands within ticks
                return;
            }
            bot.navigator().driveToward(reclaimDrop.getX(), reclaimDrop.getY(), reclaimDrop.getZ(),
                    reclaimDrop.blockPosition().below(), CRAFT_ARRIVE_DIST, CRAFT_ARRIVE_Y, 0, 0);
            if (bot.navigator().navGaveUp()) {
                bot.navigator().clearNavGaveUp();
                bot.navigator().clearPlan();
                bot.setMode(AllyBotEntity.Mode.STAY);
            }
            return;
        }
        if (tablePos == null) {
            bot.setMode(AllyBotEntity.Mode.STAY);
            return;
        }
        final BlockState s = level.getBlockState(tablePos);
        if (s.is(Blocks.CRAFTING_TABLE)) {
            if (withinReach(tablePos)) {
                bot.setForward(0.0f);
                bot.mining().requestReclaim(tablePos);
            } else {
                bot.navigator().driveToward(tablePos.getX() + 0.5, tablePos.getY() + 0.5,
                        tablePos.getZ() + 0.5, tablePos, CRAFT_ARRIVE_DIST, CRAFT_ARRIVE_Y);
                if (bot.navigator().navGaveUp()) {
                    bot.navigator().clearNavGaveUp();
                    bot.navigator().clearPlan();
                    bot.chat("left the crafting table at " + AllyBotEntity.compact(tablePos) + ".");
                    bot.setMode(AllyBotEntity.Mode.STAY);
                }
            }
            return;
        }
        // The table cell broke (our break landed, or someone else's) — chase the dropped item.
        ItemEntity nearest = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(tablePos).inflate(2.0), ItemEntity::isAlive)) {
            if (!"minecraft:crafting_table".equals(ItemLookup.idOf(e.getItem().getItem()))) continue;
            final double d = e.distanceToSqr(
                    tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
            if (d < bestD) { bestD = d; nearest = e; }
        }
        if (nearest == null) { // no drop to chase (burned/despawned/taken) — done
            bot.setMode(AllyBotEntity.Mode.STAY);
            return;
        }
        reclaimDrop = nearest;
        bot.navigator().clearPlan();
    }

    /**
     * Pick THIS operation's recipe + slot assignment from the live inventory (§3.4): the first
     * (id-sorted) FEASIBLE 2x2 recipe wins outright; otherwise the first feasible table recipe;
     * {@code null} when nothing is feasible.
     */
    private CraftAssignment pickOp(Inventory inv) {
        CraftAssignment tabled = null;
        for (KnownRecipe r : RecipeIndex.forName(craftName)) {
            final CraftAssignment plan = r.planFrom(inv);
            if (plan == null) continue;
            if (r.fits2x2()) return plan;
            if (tabled == null) tabled = plan;
        }
        return tabled;
    }

    /** End the run: chat {@code message}, then reclaim a bot-placed table (config-gated) or STAY. */
    private void finish(String message) {
        bot.chat(message);
        if (botPlacedTable && tablePos != null && ConfigLoader.config().reclaimTable()) {
            bot.navigator().clearPlan();
            craftPhase = CraftPhase.RECLAIM;
            return;
        }
        if (botPlacedTable && tablePos != null) {
            bot.chat("left the crafting table at " + AllyBotEntity.compact(tablePos) + ".");
        }
        bot.setMode(AllyBotEntity.Mode.STAY);
    }

    /** The first storage slot holding a crafting-table item, or -1. */
    private static int findTableItemSlot(Inventory inv) {
        final int n = Math.min(36, inv.getContainerSize()); // storage slots only (see KnownRecipe)
        for (int i = 0; i < n; i++) {
            final ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if ("minecraft:crafting_table".equals(ItemLookup.idOf(s.getItem()))) return i;
        }
        return -1;
    }

    /** A free cell for the temporary table: a horizontal neighbour of the bot's feet that is air
     *  over a solid floor (nearest-first; the bot can reach any of the 8 from where it stands). */
    private BlockPos findPlacementSpot(ServerLevel level) {
        final BlockPos feet = bot.blockPosition();
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int[] o : RING_OFFSETS) {
            m.set(feet.getX() + o[0], feet.getY(), feet.getZ() + o[1]);
            if (!level.getBlockState(m).isAir()) continue;
            m.move(0, -1, 0);
            final BlockState below = level.getBlockState(m);
            m.move(0, 1, 0);
            if (!BlockShapes.isSolidRender(below, level, m.below())) continue;
            return m.immutable();
        }
        return null;
    }

    /** True when {@code cell}'s centre is within {@link #CRAFT_REACH} of the bot's eyes. */
    private boolean withinReach(BlockPos cell) {
        final double dx = cell.getX() + 0.5 - bot.getX();
        final double dy = cell.getY() + 0.5 - bot.getEyeY();
        final double dz = cell.getZ() + 0.5 - bot.getZ();
        return dx * dx + dy * dy + dz * dz <= CRAFT_REACH * CRAFT_REACH;
    }
}
