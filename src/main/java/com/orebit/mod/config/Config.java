package com.orebit.mod.config;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The validated, immutable snapshot of every owner-facing capability knob loaded from {@code
 * config/orebit.properties} (PRD §10 Phase 1a). One {@code
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
 *   <li>{@code maxNodes  = pathing.syncSearchBudgetNodes}, {@code greedyWeight = pathing.greedyWeight};</li>
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
        float breakBaseCost,
        ProtectedBlocks protectedBlocks,
        boolean allowUnbreakable,
        int unbreakableHardness,
        // ---- pathing ----
        int maxNodes,
        float greedyWeight,
        float costPerHitpoint,
        boolean warmup,
        int warmupBudgetMs,
        boolean asyncPathing,
        int maxThreads,
        int asyncSearchBudgetMs,
        // ---- hpa (persisted region tier) ----
        int persistIntervalTicks,
        // ---- doors ----
        boolean doorToggle) {

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
            /* mining     */ true, false, BotCaps.UNBREAKABLE, true, 0, 0.0f,
                             ProtectedBlocks.EMPTY, false, MiningModel.DEFAULT_UNBREAKABLE_HARDNESS,
            /* pathing    */ BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT,
                             BotCaps.DEFAULT_COST_PER_HITPOINT, true, 1500,
                             /* async ON by default (searches run off the tick thread → no 10k-node flood);
                              * 2 planner threads; 250 ms/async-search budget. Sync (async=false) keeps the
                              * node cap = pathing.syncSearchBudgetNodes (DEFAULT_MAX_NODES). */
                             true, 2, 250,
            /* hpa        */ 6000,
            /* doors      */ true); // doors.toggle ON — the P3 executor operates doors (open before crossing,
                                     // close on the exit double-toggle); the flag stays a config kill-switch.

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
                /* allowUnbreakable */ allowUnbreakable,
                /* maxNodes         */ maxNodes,
                /* greedyWeight     */ greedyWeight,
                /* mayToggleDoors   */ doorToggle);
    }

    /**
     * The <b>execution-side</b> break policy gate — the cold backstop every live break site consults
     * before actually destroying a block ({@code AllyBotEntity.applyEdits}/{@code place}, {@code
     * BotMining}): may the bot break a block whose live state is {@code state} and whose live destroy
     * time (vanilla {@code BlockState.getDestroySpeed(level, pos)}) is {@code destroyTime}? Refuses
     * <ul>
     *   <li>any {@link #protectedBlocks mining.protectedBlocks} match — protected ALWAYS wins, and</li>
     *   <li>a vanilla-unbreakable block ({@code destroyTime < 0}: bedrock, barriers, portal frames, …)
     *       unless {@link #allowUnbreakable mining.allowUnbreakable} opted in.</li>
     * </ul>
     * This mirrors the planner's descriptor-bit gates ({@code MovementContext.breakable}/{@code
     * breakableThrough} via the PROTECTED bit + {@code BotCaps.allowUnbreakable}) — the planner/executor
     * parity rule: the planner never folds a break this would refuse, and this still catches the stale-grid
     * case (a block protected after the grid classified it). Cold — a set lookup + tag tests per actual
     * world edit, never per node or per tick.
     */
    public boolean mayBreak(BlockState state, float destroyTime) {
        if (destroyTime < 0 && !allowUnbreakable) return false;
        return protectedBlocks.isEmpty() || !protectedBlocks.matches(state);
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

    // {@link #breakBaseCost} (mining group, auto-generated accessor) is the flat per-break surcharge (ticks)
    // added to EVERY break the planner folds — the mining-side mirror of placeBaseCost: a behavioral
    // "reluctance to edit the world" penalty on top of the real mining time, letting an owner discourage
    // gratuitous digging/punch-throughs without forbidding them. Rides the inventory feasibility snapshot
    // (like placeBaseCost/removalCostWeight, never BotCaps/the hot path); default 0.0, so the all-defaults
    // config prices breaks exactly as before.

    // {@link #protectedBlocks} (mining group, auto-generated accessor) is the parsed mining.protectedBlocks
    // list — blocks the bot must NEVER break (exact ids + #tags). Two consumers: (1) planner-side it is
    // folded into the NavBlock classification fingerprint at config install (the PROTECTED descriptor bit,
    // via NavBlock.applyProtected in ConfigLoader.install) so every A* break gate refuses in one bit test;
    // (2) execution-side every live break site re-checks the LIVE state through mayBreak() — the backstop
    // that also covers stale grids. Changing the list at /bot config reload re-derives the table but
    // already-built nav grids keep old navtypes until rebuilt — a restart fully applies it.

    // {@link #allowUnbreakable} (mining group, auto-generated accessor) opts the bot into mining
    // vanilla-unbreakable blocks (negative destroy time — bedrock, barriers, end portal frames, ...) at the
    // tool-derived MiningModel.unbreakableTicks cost. Rides into BotCaps.allowUnbreakable (a
    // move-generation fact) AND is read by the executor (BotMining's stand-in grind, mayBreak). Its own
    // gate — NOT subject to mining.maxHardness (the 255 sentinel doesn't order against real hardness);
    // mining.protectedBlocks always overrides. Default false = today's behaviour.

    // {@link #unbreakableHardness} (mining group, auto-generated accessor) is the synthetic pseudo-hardness
    // the allowUnbreakable stand-in derives its cost from (vanilla-unbreakable blocks have no real destroy
    // time). Threaded into MiningModel.buildTable, where MiningModel.unbreakableTicks feeds it through the
    // normal pickaxe mining formula — so the planner cost and the executor grind rate both scale with the
    // bot's pickaxe tier (parity), a diamond pick faster than a stone one. Default 3200 ≈ 2 minutes with a
    // diamond pickaxe (matching the old fixed cost). Not in BotCaps — MiningModel holds it as a bake-time
    // static, read only on the cold break-cost paths.

    // {@link #warmup} / {@link #warmupBudgetMs} (pathing group, auto-generated accessors) gate the
    // boot-time synthetic warm-up searches (worldmodel.pathing.NavWarmup):
    // ~500 searches over a private in-memory fixture at SERVER_STARTED so the first REAL search doesn't
    // run JIT-cold (~16 ms). warmup=false is the off-switch; warmupBudgetMs is the hard wall-clock cap on
    // the pass (default 1500). Boot-only — read once in OrebitCommon's onServerStarted hook, never per
    // search or per tick.

    // {@link #placeBaseCost} (placement group, auto-generated accessor) is the flat per-placement base cost
    // (ticks) — a behavioral "reluctance to place" penalty, NOT a physical place time (see {@link
    // MovementContext#PLACE_BASE_COST}). Read once per pathfind into the inventory feasibility snapshot (so a
    // live bot's g-cost place base is the configured value); headless/test searches with no snapshot fall back
    // to the static default. Default {@code 6.0}, which equals the static default so the all-defaults config is
    // unchanged. Not in {@link BotCaps} — it rides the inventory snapshot like the removal premium, never the
    // hot path.
}
