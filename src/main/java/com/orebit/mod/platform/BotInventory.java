package com.orebit.mod.platform;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A clean, version-stable view over the ally bot's <b>real</b> vanilla inventory (PRD §10 Phase 1b/1c,
 * AGENCY-LAYER-PLAN "Inventory" + "Tool use"). The bot is a {@link
 * com.orebit.mod.FakePlayerEntity} extends {@link ServerPlayer}, i.e. a genuine {@code ServerPlayer} with a
 * real {@link Inventory} that vanilla {@code ItemEntity} pickup already fills (the bot literally picks up
 * dropped items as it walks over them — observed in-game). So there is <b>no synthetic inventory</b>: this
 * adapter reads and mutates the live {@code player.getInventory()}.
 *
 * <h2>Why this is core (not an overlay), and where the one overlay lives</h2>
 * The whole carried-item surface this needs is API-stable across the supported 1.17 → 26.x range: {@code
 * player.getInventory()}, the {@link net.minecraft.world.Container} {@code getContainerSize()}/{@code
 * getItem(int)} pair, and {@link ItemStack}'s {@code getItem()}/{@code getCount()}/{@code isEmpty()}/{@code
 * getDestroySpeed(BlockState)}/{@code isCorrectToolForDrops(BlockState)}/{@code getMaxDamage()}/{@code
 * getDamageValue()}/{@code isDamageableItem()}, plus {@code BlockItem.getBlock()} and {@code
 * getMainHandItem()}/{@code setItemInHand}/{@code drop}. Crucially, <b>{@link
 * ItemStack#getDestroySpeed(BlockState)} already folds in the tool's tier <i>and</i> its Efficiency
 * enchantment</b> the vanilla way, so the mining-speed model never has to decode the (heavily version-
 * divergent) enchantment / tool-material registries by hand — the single worst drift is sidestepped. The
 * one method that did move signature across versions — applying durability damage ({@code
 * ItemStack.hurtAndBreak}) — is isolated behind the {@link ItemDamage} overlay seam; everything here stays
 * version-agnostic and in core.
 *
 * <h2>The two distinct consumers (kept separate on purpose)</h2>
 * <ol>
 *   <li><b>The per-pathfind feasibility snapshot</b> (the decided cheap Baritone-style cap): {@link
 *       #snapshot} scans the live inventory ONCE, before the A* loop, into plain primitives on an {@link
 *       InventorySnapshot} — total placeable-block count + the best mining capability. The hot path reads
 *       those primitives, NEVER this object or the live {@code Inventory} (HOT-PATH-NO-ALLOC). It is a
 *       precheck/cap, not a per-node depleting budget.</li>
 *   <li><b>The live follower actions</b>: as the bot actually places/mines along the committed path it
 *       deducts from the real inventory through {@link #consumeOnePlaceable} / {@link #damageBestTool},
 *       which mutate {@code player.getInventory()} directly (a rare mid-path exhaustion is netted by the
 *       partial-path + replan loop, so the snapshot need not be perfectly conservative).</li>
 * </ol>
 *
 * <p>A smart object around the bot, not a static helper bag: one {@code BotInventory} wraps one bot's
 * {@link ServerPlayer} and exposes the inventory verbs the agency layer needs.
 */
public final class BotInventory {

    private final ServerPlayer bot;
    private final Inventory inv;

    public BotInventory(ServerPlayer bot) {
        this.bot = bot;
        this.inv = bot.getInventory();
    }

    // ---- Placeable blocks (placement-from-inventory feasibility) -----------------------------------

    /**
     * Total count of carried items that are {@link BlockItem placeable blocks} — the scalar "throwaway
     * budget" the placement feasibility cap reads (Baritone tracks the same number). Sums {@link
     * ItemStack#getCount()} over every main-inventory slot whose item is a {@code BlockItem}. Read once per
     * pathfind into the snapshot; never on the hot path.
     */
    public int placeableBlockCount() {
        int total = 0;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) total += s.getCount();
        }
        return total;
    }

    /**
     * Count of carried {@link BlockItem}s whose placed block equals {@code block} — the per-type count a
     * future resource arc / a "place this specific block" mode needs. {@link #placeableBlockCount()} is the
     * cheaper any-block total the feasibility cap uses today.
     */
    public int countOfPlaceable(Block block) {
        int total = 0;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                total += s.getCount();
            }
        }
        return total;
    }

    /**
     * Total number of items the bot is carrying (summed {@link ItemStack#getCount()} over every slot) — the
     * scalar the {@code /bot gather} loop diffs across standing-mine ticks to count PICKED-UP resource items
     * for its quota (find-mine-resources design §7). Version-agnostic: uses only the stable {@code
     * getContainerSize()}/{@code getItem()}/{@code getCount()} verbs, so it needs no per-resource item table
     * (mined ore drops a different item than the block, and item registry ids drift across versions). Cold —
     * called at tick rate for one bot, never on a hot path.
     */
    public int totalItemCount() {
        int total = 0;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) total += s.getCount();
        }
        return total;
    }

    // ---- Mining capability (tool selection) --------------------------------------------------------

    /**
     * The best (fastest) destroy speed any carried item achieves against {@code state} — the input the
     * mining-tick model needs. Scans every slot and returns the max {@link ItemStack#getDestroySpeed
     * getDestroySpeed(state)}; vanilla's {@code getDestroySpeed} already accounts for the item's tool tier
     * AND its Efficiency enchantment, so this is the true in-game mining speed with no enchant decoding.
     * Bare hands give {@code 1.0}, so the result is always {@code >= 1.0} (an empty inventory still mines by
     * hand). Read once per pathfind into the snapshot.
     */
    public float bestDestroySpeed(BlockState state) {
        float best = 1.0f; // bare-hand baseline
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            float sp = s.getDestroySpeed(state);
            if (sp > best) best = sp;
        }
        return best;
    }

    /**
     * Whether the bot carries a tool that is "correct for drops" on {@code state} — i.e. for a block that
     * {@code requiresCorrectToolForDrops} (obsidian, ores, ...), whether the bot can mine it at the harvest
     * speed rather than the 100×-slow penalty. A block that doesn't require a correct tool returns {@code
     * true} (bare hands harvest it). Used by the mining model to pick the harvest vs. no-harvest tick
     * multiplier.
     */
    public boolean hasCorrectTool(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    /**
     * The slot index of the best mining tool for {@code state} (the one with the highest destroy speed), or
     * {@code -1} if nothing beats bare hands. The live follower equips this in the main hand before mining
     * so the swing speed and the durability damage land on the right item.
     */
    public int bestToolSlot(BlockState state) {
        int bestSlot = -1;
        float best = 1.0f;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            float sp = s.getDestroySpeed(state);
            if (sp > best) { best = sp; bestSlot = i; }
        }
        return bestSlot;
    }

    /**
     * Put the fastest HOTBAR tool for {@code state} into the bot's main hand — the "equip your pickaxe before you
     * dig" step the live mining actuator ({@link com.orebit.mod.BotMining}) runs each tick before reading destroy
     * progress, so the bot mines with (and visibly holds) the right tool at the right speed. Scans the hotbar
     * (slots 0..8 — the ones a player can hold) and, only when a slot is <i>strictly</i> faster than what's
     * already held (no thrash on ties), <b>swaps</b> that tool into the hand and the held item into the tool's
     * old slot. The swap uses only the cross-version-stable item verbs ({@code getMainHandItem} /
     * {@code setItemInHand} / {@code getItem} / {@code setItem}) rather than the {@code Inventory.selected} field,
     * which went private in 26.x — so this stays in core with no overlay, at the cost of rearranging two hotbar
     * slots instead of scrolling the selection (invisible for a bot; it settles after the first swap of a given
     * block type). A better tool sitting in the backpack is left there — a future refinement (the planner's
     * feasibility cap already scans all slots for cost, so at worst the real dig is a touch slower than planned
     * and the reactive loop just spends the extra ticks).
     */
    public void selectBestHotbarTool(BlockState state) {
        ItemStack held = bot.getMainHandItem();
        float bestSpeed = held.getDestroySpeed(state);
        int bestSlot = -1;
        for (int i = 0; i <= 8; i++) {
            float sp = inv.getItem(i).getDestroySpeed(state);
            if (sp > bestSpeed) { bestSpeed = sp; bestSlot = i; }
        }
        if (bestSlot < 0) return; // nothing in the hotbar beats what's already held
        ItemStack tool = inv.getItem(bestSlot);
        bot.setItemInHand(InteractionHand.MAIN_HAND, tool); // hold the faster tool
        inv.setItem(bestSlot, held);                        // and leave what we held in the tool's old slot
    }

    // ---- Per-pathfind feasibility snapshot (the cheap Baritone-style cap) --------------------------

    /**
     * A canonical full block per {@link NavBlock.Tool} category — the representative the tool-tier
     * classification probes with {@link #bestDestroySpeed} (so a netherite/efficiency pickaxe reads as a
     * faster tier than a stone one). Indexed by {@link NavBlock.Tool} ordinal (NONE..SHEARS). NONE has no
     * representative (the bot's best "tool" for a hands-only block is bare hands), so its slot is left to
     * resolve to {@link MiningModel.Tier#BARE}. These are stable {@code Blocks.*} constants across the range.
     */
    private static BlockState canonical(int toolOrdinal) {
        // Ordinals follow NavBlock.Tool: NONE, PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS.
        return switch (toolOrdinal) {
            case 1 -> Blocks.STONE.defaultBlockState();        // PICKAXE
            case 2 -> Blocks.OAK_LOG.defaultBlockState();      // AXE
            case 3 -> Blocks.DIRT.defaultBlockState();         // SHOVEL
            case 4 -> Blocks.HAY_BLOCK.defaultBlockState();    // HOE
            case 5 -> Blocks.COBWEB.defaultBlockState();       // SWORD (cobweb is sword-efficient)
            case 6 -> Blocks.COBWEB.defaultBlockState();       // SHEARS (cobweb / vines)
            default -> null;                                    // NONE → bare hands
        };
    }

    /**
     * Build the per-pathfind inventory feasibility snapshot the planner reads (PRD §10 Phase 1b/1c) — the
     * decided cheap cap, taken ONCE here (cold) and then read on the hot path as plain primitives + a
     * resident-table index (never the live {@link Inventory}). It:
     * <ul>
     *   <li>classifies the bot's best carried tool per {@link NavBlock.Tool} category into a {@link
     *       MiningModel.Tier} ordinal (by probing {@link #bestDestroySpeed} against a {@link
     *       #canonical(int)} block of that category — vanilla destroy-speed already includes tool tier +
     *       Efficiency), yielding the {@link MiningModel.Snapshot} the {@link MovementContext#breakable}
     *       tool-feasibility gate + the stage-1d tick lookup read;</li>
     *   <li>reads the carried placeable-block count once ({@link #placeableBlockCount()}) as the placement
     *       cap's scalar throwaway budget.</li>
     * </ul>
     * {@code caps} supplies the mining-hardness cap + canBreak; {@code consumesBlocks} is the {@code
     * placement.consumesBlocks} config flag (when off, the placeable count is irrelevant — infinite conjured
     * supply); {@code placeBaseCost} is the configured {@code placement.placeBaseCost} flat per-placement base
     * (ticks) put on the snapshot for {@link MovementContext#placeCost} to read; {@code breakBaseCost} is its
     * mining-side mirror ({@code mining.breakBaseCost}), the flat per-break surcharge {@link
     * MovementContext#breakCost} adds to every folded break. Returns {@code null} when the
     * {@link MiningModel} table isn't built yet (degrades the planner to its historical caps-only mode rather
     * than risk an NPE).
     *
     * <p><b>Removal premium</b> (the {@code placement.removalCostWeight} knob): the placement cost the planner
     * charges is bumped by the placed block's mine-out time × {@code removalWeight} — "the cost of potentially
     * having to mine this block out later" — so a mixed inventory favours dirt/cobblestone over obsidian. The
     * representative placed block is the configured {@code conjured} block when placement does not consume
     * inventory, else the SOFTEST (cheapest-to-mine-out) placeable block the bot actually carries (which is the
     * one {@link #consumeOnePlaceable} will place); a bot carrying no placeable block can't place at all, so its
     * premium is 0. Computed ONCE here (cold) into the snapshot scalar {@link MovementContext#placeCost} reads.
     */
    public MovementContext.InventoryView feasibility(BotCaps caps, boolean consumesBlocks,
            BlockState conjured, float removalWeight, float placeBaseCost, float breakBaseCost) {
        if (!MiningModel.ready()) return null;

        int categories = NavBlock.Tool.values().length;
        int[] bestTierPerCategory = new int[categories];
        for (int cat = 0; cat < categories; cat++) {
            BlockState probe = canonical(cat);
            if (probe == null) {
                bestTierPerCategory[cat] = MiningModel.Tier.BARE.ordinal();
                continue;
            }
            bestTierPerCategory[cat] = MiningModel.classifyTier(bestDestroySpeed(probe));
        }

        MiningModel.Snapshot mining = MiningModel.snapshot(
                bestTierPerCategory, caps.maxBreakHardness(), caps.canBreak());
        float premium = placeRemovalPremium(consumesBlocks, conjured, removalWeight);
        return new MovementContext.InventoryView(
                mining, consumesBlocks, placeableBlockCount(), premium, placeBaseCost, breakBaseCost);
    }

    /**
     * The precomputed removal premium (ticks) for the representative placed block — the placement-cost bump
     * {@code placement.removalCostWeight} buys (see {@link #feasibility}). The representative descriptor is the
     * configured {@code conjured} block when placement does not consume inventory, else the SOFTEST carried
     * placeable block (the one {@link #consumeOnePlaceable} places); if the bot carries none while consuming,
     * the premium is 0 (it can't place anyway). The premium is {@link MiningModel#fastestTicks} (the mine-out
     * time with the best tool — same tick units as every other cost, tracking hardness) × {@code weight}, with
     * a safety clamp to 0 for a degenerate (non-positive / unmineable) table read. Cold — once per pathfind.
     */
    private float placeRemovalPremium(boolean consumesBlocks, BlockState conjured, float weight) {
        if (weight <= 0f) return 0f;
        long desc;
        if (consumesBlocks) {
            long softest = softestPlaceableDescriptor();
            if (softest == NO_PLACEABLE) return 0f; // carries nothing to place → can't place → no premium
            desc = softest;
        } else {
            desc = NavBlock.descriptorFor(conjured);
        }
        int t = MiningModel.fastestTicks(desc);
        if (t <= 0 || t >= MiningModel.UNMINEABLE) return 0f; // safety clamp (placeable blocks are breakable)
        return weight * t;
    }

    /** Sentinel returned by {@link #softestPlaceableDescriptor} when the bot carries no placeable block. */
    private static final long NO_PLACEABLE = Long.MIN_VALUE;

    /**
     * The descriptor of the SOFTEST (lowest {@link MiningModel#fastestTicks}) placeable block the bot carries
     * — the one the removal premium assumes and {@link #consumeOnePlaceable} places — or {@link #NO_PLACEABLE}
     * when it carries none. Cold (called once per pathfind by {@link #placeRemovalPremium}).
     */
    private long softestPlaceableDescriptor() {
        long bestDesc = NO_PLACEABLE;
        int bestTicks = Integer.MAX_VALUE;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            long desc = NavBlock.descriptorFor(bi.getBlock().defaultBlockState());
            int t = MiningModel.fastestTicks(desc);
            if (t < bestTicks) { bestTicks = t; bestDesc = desc; }
        }
        return bestDesc;
    }

    // ---- Live follower mutations (deduct from the REAL inventory as the bot acts) -------------------

    /**
     * Consume one carried placeable block (decrement its stack by one) — called by the live follower the
     * moment it actually places a footing when {@code placement.consumesBlocks} is on. Picks the SOFTEST
     * (cheapest-to-mine-out) carried placeable block, the same one the planner's removal premium assumed (so
     * the bot places dirt over obsidian — see {@link #placeRemovalPremium}). Returns the {@link Block} that
     * was consumed (so the follower places that exact block), or {@code null} if the bot carried no placeable
     * block (the caller then skips the placement and lets the replan handle it). When {@code consumesBlocks}
     * is off the follower uses the configured conjured block with infinite supply and never calls this. Cold
     * (follower action, not the A* loop).
     */
    public Block consumeOnePlaceable() {
        int bestSlot = -1;
        int bestTicks = Integer.MAX_VALUE;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            int t = MiningModel.fastestTicks(NavBlock.descriptorFor(bi.getBlock().defaultBlockState()));
            if (t < bestTicks) { bestTicks = t; bestSlot = i; }
        }
        if (bestSlot < 0) return null;
        ItemStack s = inv.getItem(bestSlot);
        Block block = ((BlockItem) s.getItem()).getBlock();
        s.shrink(1);
        return block;
    }

    /**
     * Apply one use of durability to the bot's best tool for {@code state} (equipping it in the main hand
     * first), through the {@link ItemDamage} overlay seam — called by the live follower after a real mine
     * when {@code mining.consumesTools} is on. A no-op when the best "tool" is bare hands ({@link
     * #bestToolSlot} returns {@code -1}) or the stack isn't damageable. Whether to call this at all is the
     * {@code mining.consumesTools} config gate (the caller checks it); the planner's feasibility cap, not
     * this, governs whether a near-broken tool may still be planned with.
     */
    public void damageBestTool(BlockState state) {
        int slot = bestToolSlot(state);
        if (slot < 0) return;
        ItemStack tool = inv.getItem(slot);
        if (tool.isEmpty() || !tool.isDamageableItem()) return;
        bot.setItemInHand(InteractionHand.MAIN_HAND, tool); // mine with it in hand (correct slot for damage)
        ItemDamage.damageMainHand(bot, tool);
    }

    /**
     * Drop {@code stack} into the world from the bot (a thin pass-through to the vanilla {@code
     * ServerPlayer.drop}) — minimal, for the future resource arc (e.g. depositing mined yield). Kept here so
     * the inventory verbs all live behind one adapter; not used by the pathfinder.
     */
    public void drop(ItemStack stack) {
        bot.drop(stack, false);
    }

    /**
     * Toss every carried stack the {@code filter} accepts into the world (the {@code /bot drop} verb) and
     * return the total item COUNT dropped. Each matching slot is emptied and its whole stack thrown via the
     * vanilla {@code ServerPlayer.drop} (which gives the thrown item a self-pickup delay, so the bot won't
     * instantly vacuum it back and the owner can grab it). Uses only the version-stable container verbs
     * ({@code getContainerSize}/{@code getItem}/{@code setItem}) + {@code drop}, so it stays in core with no
     * overlay. Cold — a command action over one bot's inventory, never a hot path.
     */
    public int dropMatching(java.util.function.Predicate<ItemStack> filter) {
        int dropped = 0;
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !filter.test(s)) continue;
            inv.setItem(i, ItemStack.EMPTY); // empty the slot first, then throw the captured stack
            dropped += s.getCount();
            bot.drop(s, false);
        }
        return dropped;
    }

    /** The wrapped bot (for callers that need the raw {@link ServerPlayer}, e.g. to equip before mining). */
    public ServerPlayer bot() {
        return bot;
    }
}
