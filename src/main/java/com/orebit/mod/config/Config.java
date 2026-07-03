package com.orebit.mod.config;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The validated, immutable snapshot of every owner-facing capability knob loaded from {@code
 * config/orebit.properties} (PRD §10 Phase 1a / AGENCY-LAYER-PLAN "Capability config"). One {@code
 * Config} is the in-memory source of truth the rest of the agency layer reads: the pathfinder gets a
 * {@link BotCaps} via {@link #toBotCaps()}, the follower gets its conjured-block + survival flags via the
 * accessors below. {@link ConfigLoader} produces it (parse → validate); {@link ConfigValidator}
 * guarantees every field here is already in range, so consumers never re-check.
 *
 * <h2>Why a record of primitives, not a property bag</h2>
 * Reading raw strings out of {@link java.util.Properties} on every pathfind would be wasteful and
 * un-typed. We parse + validate ONCE at load into this flat, typed, immutable object (favour-cpu-over-ram:
 * the parse cost is paid at startup, never per search). The fields mirror {@link ConfigKeys} 1:1 and are
 * grouped the same way the file is laid out (survival / placement / mining / pathing).
 *
 * <h2>The conjured block</h2>
 * {@link #conjuredBlock} is stored as a resolved {@link Block} (not an id string), because the validator
 * already proved it resolves on this version — so {@link #conjuredBlockState()} is a field read, no
 * registry lookup per placement. The follower uses it as its throwaway {@code PLACE_BLOCK}.
 *
 * <h2>Mapping to {@link BotCaps}</h2>
 * {@link #toBotCaps()} folds the placement/mining/pathing knobs into the capability gate the block-tier
 * A* already threads per search:
 * <ul>
 *   <li>{@code canBreak  = mining.canMine}, {@code maxBreakHardness = mining.maxHardness};</li>
 *   <li>{@code canPlace  = placement.canPlace};</li>
 *   <li>{@code maxNodes  = pathing.maxNodes}, {@code greedyWeight = pathing.greedyWeight};</li>
 *   <li>{@code costPerHitpoint = pathing.costPerHitpoint} — the ONE damage-pricing knob: ticks the
 *       planner considers 1 HP worth (hazard-cell transit + fall damage past the safe window are both
 *       priced in it; see {@link BotCaps#costPerHitpoint}).</li>
 * </ul>
 * {@code jumpHeight}/{@code safeFallDistance} stay at the Tier 1 defaults (1 / 3) — they are not yet owner
 * knobs (they arrive with the move-completeness arc). {@code survival.takesDamage} rides into {@code
 * BotCaps.takesDamage} because the planner prices damage-as-cost (the fall window and the pass-through
 * hazard surcharge are caps-honest: an immune bot pays neither); the remaining survival flags + the
 * consume/tick model drive the follower's body setup and the (future) tick-cost model, not move
 * generation, so they live on {@code Config} and are read directly by their consumers.
 */
public record Config(
        // ---- survival ----
        boolean takesDamage,
        boolean hunger,
        boolean needsBreath,
        // ---- placement ----
        boolean canPlace,
        boolean consumesBlocks,
        Block conjuredBlock,
        float removalCostWeight,
        float placeBaseCost,
        // ---- mining ----
        boolean canMine,
        boolean consumesTools,
        int maxHardness,
        boolean ticksByHardness,
        int ticksToMineFlat,
        // ---- pathing ----
        int maxNodes,
        float greedyWeight,
        float costPerHitpoint) {

    /**
     * The all-defaults configuration — reproduces TODAY's hardcoded follower behaviour exactly (break +
     * place on, insta-mine anything below hardness 255, infinite cobblestone, 10k-node cap, greedy weight
     * 2.0, invulnerable / no hunger / no breath). {@link ConfigLoader} writes this out as the generated
     * default file, and falls back to it when the file is missing or unreadable, so nothing changes until
     * the owner edits the config. The pathing/break/place defaults line up with {@link BotCaps#BREAK_PLACE}.
     */
    public static final Config DEFAULT = new Config(
            /* survival   */ false, false, false,
            /* placement  */ true, false, Blocks.COBBLESTONE, 1.0f, MovementContext.PLACE_BASE_COST,
            /* mining     */ true, false, BotCaps.UNBREAKABLE, true, 0,
            /* pathing    */ BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT,
                             BotCaps.DEFAULT_COST_PER_HITPOINT);

    /**
     * The capability gate the block-tier A* reads, derived from the placement / mining / pathing knobs
     * (see the class doc for the field mapping). Built once per loaded config (and re-derived only on a
     * {@code /bot config reload}); the pathfinder reads its fields into search-start locals, so this is
     * never on the hot path.
     */
    public BotCaps toBotCaps() {
        // takesDamage == false ⇒ an invulnerable bot: every drop is free at any depth, so safeFall == maxFall ==
        // a world-height cap (no damage penalty, no rejection). A mortal bot uses the conservative defaults
        // (free to 3, damage-costed up to 16). (Future: a health-aware maxFall for a mortal bot at low health.)
        final int safeFall = takesDamage ? BotCaps.DEFAULT_SAFE_FALL : BotCaps.IMMUNE_FALL;
        final int maxFall = takesDamage ? BotCaps.DEFAULT_MAX_FALL : BotCaps.IMMUNE_FALL;
        return new BotCaps(
                /* jumpHeight       */ 1,
                /* safeFallDistance */ safeFall,
                /* maxFallDistance  */ maxFall,
                /* takesDamage      */ takesDamage,
                /* costPerHitpoint  */ costPerHitpoint,
                /* canBreak         */ canMine,
                /* canPlace         */ canPlace,
                /* maxBreakHardness */ maxHardness,
                /* maxNodes         */ maxNodes,
                /* greedyWeight     */ greedyWeight);
    }

    /** The default {@link BlockState} the follower places when bridging/pillaring (the conjured block). */
    public BlockState conjuredBlockState() {
        return conjuredBlock.defaultBlockState();
    }

    // {@link #removalCostWeight} (placement group) is the record's auto-generated accessor: how strongly the
    // planner disfavours placing hard-to-remove blocks (mine-out ticks × weight; 0 disables). Read once per
    // pathfind into the inventory feasibility snapshot — never on the hot path.

    // {@link #costPerHitpoint} (pathing group, auto-generated accessor) is the unified damage price: ticks
    // the planner considers 1 HP of damage to be worth (>= 0, default 100). Rides into
    // BotCaps.costPerHitpoint and is read by every damage-as-cost term (hazard-cell transit in
    // MovementContext.cellTransitCost, fall damage past the safe window in Fall/Parkour). Break-even: 1 HP
    // buys costPerHitpoint / 4.633 walk-blocks of detour. Only meaningful with survival.takesDamage=true.

    // {@link #placeBaseCost} (placement group, auto-generated accessor) is the flat per-placement base cost
    // (ticks) — a behavioral "reluctance to place" penalty, NOT a physical place time (see {@link
    // MovementContext#PLACE_BASE_COST}). Read once per pathfind into the inventory feasibility snapshot (so a
    // live bot's g-cost place base is the configured value); headless/test searches with no snapshot fall back
    // to the static default. Default {@code 6.0}, which equals the static default so the all-defaults config is
    // unchanged. Not in {@link BotCaps} — it rides the inventory snapshot like the removal premium, never the
    // hot path.
}
