package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The capability gate for one bot (MOVEMENT-DESIGN.md §5, PRD §7.3): what <i>this</i> bot is allowed
 * and able to do. Movements filter their candidates on it and parameterise their costs on it, so the
 * same nav grid yields different paths for a fragile walk-only helper vs. a fully-equipped one.
 *
 * <p>Immutable; seeded from server/bot config ({@link com.orebit.mod.config.Config}, mapped by {@code
 * Config.toBotCaps()}). The two pre-baked constants below are the <b>test/headless</b> configs (used by
 * the JMH benchmarks, HPA* leaf-cost computer, and unit tests) — live in-game caps are built from the
 * loaded config so the owner's {@code orebit.properties} governs them. {@link #BREAK_PLACE} reproduces
 * the historical hardcoded follower behaviour (break + place on, insta-mine anything below hardness
 * {@link #UNBREAKABLE}, greedy weight 2.0, 10k-node cap) so nothing changes until the owner edits config.
 *
 * <h2>The search parameters live here too</h2>
 * {@link #maxNodes}, {@link #greedyWeight}, and {@link #boxedInScanRadius} are search knobs, not movement
 * capabilities, but they ride on {@code BotCaps} because {@code caps} is already the one config-derived
 * object threaded into {@link BlockPathfinder#findPath} per search (and held by {@code PathPlan}). The hot loop reads them into <i>locals</i> at search start (see
 * {@link BlockPathfinder}), so the per-node code still reads a local, never a field — no hot-path cost
 * for carrying them here (HOT-PATH-NO-ALLOC / favour-cpu-over-ram).
 */
public record BotCaps(
        /** Max blocks a single jump gains (Ascend / step-up). */
        int jumpHeight,
        /** Max blocks the bot may drop with <b>no</b> harm (Fall) — the free-fall threshold (vanilla 3). */
        int safeFallDistance,
        /**
         * The deepest drop the bot will take <b>at all</b> ({@code ≥ safeFallDistance}). Between
         * {@code safeFallDistance} and this, {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall
         * Fall} still emits the candidate but charges a <b>damage penalty</b> — fall damage is a COST, not a
         * blocker: the bot takes a hurtful drop when the alternative is a long detour, but prefers a damage-free
         * route. A drop deeper than this is rejected (it would deal unacceptable / lethal damage). A
         * damage-immune bot (no health system, feather-falling) sets {@code safeFallDistance == maxFallDistance}
         * (both large) so there is no penalty zone and it falls freely.
         */
        int maxFallDistance,
        /**
         * Whether the bot can be hurt at all ({@code survival.takesDamage}) — the caps-honest damage signal
         * the cost layer reads. When {@code false} every damage-as-cost term is zero: the {@link
         * com.orebit.mod.pathfinding.blockpathfinder.MovementContext#bodyTransitCost pass-through hazard
         * surcharge} (fire / berry bush / powder snow in the body path) is not charged, mirroring how an
         * immune bot's fall window ({@code safeFallDistance == maxFallDistance == }{@link #IMMUNE_FALL})
         * already zeroes the {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} damage
         * penalty. SLOW costs are NOT gated on this — physics slows an immune bot just the same.
         */
        boolean takesDamage,
        /**
         * Ticks the planner considers <b>1 HP of damage</b> to be worth ({@code >= 0}) — the ONE currency
         * every damage-as-cost term is priced in ({@code pathing.costPerHitpoint}). Two consumers today:
         * the {@link com.orebit.mod.pathfinding.blockpathfinder.MovementContext#cellTransitCost pass-through
         * hazard surcharge} (1 HP per damaging body cell transited) and the {@link
         * com.orebit.mod.pathfinding.blockpathfinder.movements.Fall}/{@link
         * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour} fall-damage penalty (1 HP per block
         * dropped past {@link #safeFallDistance}). Meaningful only when {@link #takesDamage} is true (an
         * immune bot's damage terms are zero regardless).
         *
         * <p><b>Break-even intuition:</b> one HP buys {@code costPerHitpoint /}
         * {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse#FLAT_COST} (≈ 4.633) walk-blocks
         * of detour — ≈ 21.6 blocks at the default {@code 100}. The old hardcoded 40-ticks-per-hazard-cell
         * bought only ~9 blocks per cell, so a berry-bush MAZE was rationally plowed through lethally (each
         * cell's detour share exceeded its 9-block allowance; death itself was never priced). Server admins
         * raise this for a more self-preserving bot. Successor (ratified, NOT yet built): a cumulative
         * health-aware damage BUDGET — a per-path HP ledger against remaining hearts — replaces this scalar
         * conversion; until then this knob is the unified short-term fix.
         */
        float costPerHitpoint,
        /** May mine soft blocks in the way (BreakThrough — Tier 3). */
        boolean canBreak,
        /** May place throwaway blocks (Pillar / Bridge — Tier 3). */
        boolean canPlace,
        /**
         * The hardest block (quantized {@link com.orebit.mod.worldmodel.navblock.NavBlock} hardness,
         * 0..255) the bot may mine. A breakable cell harder than this is treated as un-minable by {@link
         * MovementContext#breakable}, so the planner routes around it. {@link #UNBREAKABLE} (255) means
         * "mine anything that is breakable at all" — the historical insta-mine behaviour. Meaningful only
         * when {@link #canBreak} is true.
         */
        int maxBreakHardness,
        /**
         * May mine <b>vanilla-unbreakable</b> blocks (negative destroy time — bedrock, barriers, end
         * portal frames, …) at the tool-derived {@link MiningModel#unbreakableTicks} stand-in cost
         * ({@code mining.allowUnbreakable}, default {@code false}). Its OWN gate, deliberately NOT
         * subject to {@link #maxBreakHardness}: the quantized 255 sentinel encodes "destroy time &lt; 0",
         * which doesn't order against real hardness values, so capping it there would be meaningless.
         * Protected blocks ({@code mining.protectedBlocks}) always win — protected-unbreakable stays
         * unmineable even with this on. Meaningful only when {@link #canBreak} is true.
         */
        boolean allowUnbreakable,
        /**
         * A* node-expansion ceiling for a single search ({@code > 0}) — the per-call cost backstop so a
         * long/blocked goal can't stall the tick. Was {@code BlockPathfinder.MAX_EXPANSIONS} (10000).
         */
        int maxNodes,
        /**
         * Heuristic inflation weight ({@code >= 1.0}) — the greediness of the weighted 3D-octile search.
         * {@code 1.0} is admissible (optimal but slow); higher beelines at the goal, trading guaranteed-
         * optimal paths (fine for a follow-bot) for far fewer expanded nodes. Was {@code
         * BlockPathfinder.H_WEIGHT} (2.0).
         */
        float greedyWeight,
        /**
         * May OPEN/CLOSE hand-toggleable doors (wood/copper) by right-clicking, instead of smashing or routing
         * around them ({@code doors.toggle}). The P3 executor operates doors for real (open before crossing,
         * close on the exit double-toggle), so the live config default ({@link
         * com.orebit.mod.config.Config#DEFAULT}) is now {@code true}. With this off the planner is byte-identical
         * to P1 (an already-open door is passable, a closed door is mined); with it on, {@link
         * MovementContext#doorSetClears}/{@code canToggleExitDoor} fold a cheap {@code SET_OPEN}/{@code
         * SET_CLOSED} the search prefers over a break. Iron doors are never hand-toggleable regardless (they lack
         * {@link com.orebit.mod.worldmodel.navblock.NavBlock#doorToggleable}). NOTE: the back-compat constructor
         * below still defaults this OFF for legacy {@code new BotCaps(...)} call sites (presets/benchmarks/tests).
         */
        boolean mayToggleDoors,
        /**
         * Half-extent (in region cells, applied at every pyramid level) of the PROACTIVE boxed-in seal scan
         * ({@code pathing.boxedInScanRadius}) — the {@code (2·R+1)³} goal-centred region box {@link
         * com.orebit.mod.pathfinding.PathPlan#maybeProactiveBoxedIn} floods per level to prove a walled-off goal
         * before the bot wanders. Like {@link #maxNodes} / {@link #greedyWeight} this is a SEARCH knob, not a
         * movement capability, but it rides on {@code BotCaps} because {@code caps} is the config-derived object
         * {@code PathPlan} already holds in that scope. A plan-entry cost (a wider box scans more region cells),
         * never a per-node one — so, like the other search knobs, it is deliberately EXCLUDED from the
         * realizability signature below. The back-compat constructors default it to {@link
         * #DEFAULT_BOXED_IN_SCAN_RADIUS}. Default {@code 3}.
         */
        int boxedInScanRadius) {

    /**
     * Full back-compat constructor for the pre-boxed-in-radius component list (through {@code mayToggleDoors}),
     * defaulting {@code boxedInScanRadius} to {@link #DEFAULT_BOXED_IN_SCAN_RADIUS}. Keeps the existing
     * canonical-shape call sites (door tests, presets) compiling unchanged; the live config path ({@link
     * com.orebit.mod.config.Config#toBotCaps}) uses the full constructor.
     */
    public BotCaps(int jumpHeight, int safeFallDistance, int maxFallDistance, boolean takesDamage,
                   float costPerHitpoint, boolean canBreak, boolean canPlace, int maxBreakHardness,
                   boolean allowUnbreakable, int maxNodes, float greedyWeight, boolean mayToggleDoors) {
        this(jumpHeight, safeFallDistance, maxFallDistance, takesDamage, costPerHitpoint, canBreak, canPlace,
             maxBreakHardness, allowUnbreakable, maxNodes, greedyWeight, mayToggleDoors,
             DEFAULT_BOXED_IN_SCAN_RADIUS);
    }

    /**
     * Back-compat constructor — the pre-DOORS-P2 component list, with {@code mayToggleDoors} defaulted OFF. Keeps
     * the many existing {@code new BotCaps(...)} call sites (presets, benchmarks, tests) compiling unchanged; the
     * live config path ({@link com.orebit.mod.config.Config#toBotCaps}) uses the full canonical constructor.
     */
    public BotCaps(int jumpHeight, int safeFallDistance, int maxFallDistance, boolean takesDamage,
                   float costPerHitpoint, boolean canBreak, boolean canPlace, int maxBreakHardness,
                   boolean allowUnbreakable, int maxNodes, float greedyWeight) {
        this(jumpHeight, safeFallDistance, maxFallDistance, takesDamage, costPerHitpoint, canBreak, canPlace,
             maxBreakHardness, allowUnbreakable, maxNodes, greedyWeight, false);
    }

    /** Quantized-hardness sentinel for "unbreakable / mine anything" — the historical insta-mine cap. */
    public static final int UNBREAKABLE = 255;

    /** Default node-expansion ceiling — the historical {@code BlockPathfinder.MAX_EXPANSIONS}. */
    public static final int DEFAULT_MAX_NODES = 10000;

    /**
     * Default proactive boxed-in seal-scan box half-extent (= the {@code pathing.boxedInScanRadius} config
     * default): a {@code 7}-region box per axis at each level (the historical hardcoded {@code
     * PathPlan.BOXED_IN_BOX_RADIUS}). The back-compat constructors + both presets use this.
     */
    public static final int DEFAULT_BOXED_IN_SCAN_RADIUS = 3;

    /**
     * Default deepest drop the bot will take (with a damage penalty above {@link #DEFAULT_SAFE_FALL}). 16 keeps
     * a full-health bot alive (fall damage ≈ depth−3 ⇒ ~13 at 16, survivable) while letting it descend a cave
     * whose only route has 4–6 block drops. Tunable; a future health-aware cap can shrink it when the bot is hurt.
     */
    public static final int DEFAULT_MAX_FALL = 16;

    /** Default free-fall (no-damage) threshold — vanilla's 3 blocks. */
    public static final int DEFAULT_SAFE_FALL = 3;

    /**
     * Fall distance for a <b>damage-immune</b> bot ({@code takesDamage == false}): set BOTH
     * {@link #safeFallDistance} and {@link #maxFallDistance} to this so every drop is free and none is capped
     * (no damage penalty, no rejection — it falls to the first landing however deep). Sized to the tallest
     * world column so the {@code Fall} scan still terminates (it also breaks at the first unbuilt cell).
     */
    public static final int IMMUNE_FALL = 4096;

    /** Default heuristic weight — the historical {@code BlockPathfinder.H_WEIGHT} (greedy). */
    public static final float DEFAULT_GREEDY_WEIGHT = 2.0f;

    /**
     * Default {@link #costPerHitpoint} (= the {@code pathing.costPerHitpoint} config default): 100 ticks
     * per HP ⇒ 1 HP buys ≈ 21.6 walk-blocks of detour ({@code 100 / 4.633}). Deliberately well above the
     * old implicit rates (40 ticks per hazard cell, 10 ticks per excess fall block) so a mortal bot at
     * defaults is meaningfully self-preserving; an immune bot is unaffected (its damage terms are zero).
     */
    public static final float DEFAULT_COST_PER_HITPOINT = 100.0f;

    /**
     * Walk + jump 1, no break/place, conservative 3-block safe fall — the Tier 1 default. Carries the
     * default search params (10k nodes, weight 2.0) and an unbreakable cap (moot with {@code canBreak}
     * false). {@code takesDamage} is {@code true} for consistency with the mortal fall window this preset
     * already carries (safe 3 / max 16 charges the Fall damage penalty), so both presets price damage as
     * cost; an immune profile comes from config ({@code survival.takesDamage=false}), not these constants.
     * Damage is priced at {@link #DEFAULT_COST_PER_HITPOINT} (= the config default) — note this makes the
     * presets MORE damage-averse than the pre-unification hardcodes (100 vs 40/hazard-cell and 10/excess
     * fall block); headless tests/benchmarks that assert damage-priced costs read the caps value.
     */
    public static final BotCaps DEFAULT = new BotCaps(
            1, DEFAULT_SAFE_FALL, DEFAULT_MAX_FALL, true, DEFAULT_COST_PER_HITPOINT, false, false,
            UNBREAKABLE, false, DEFAULT_MAX_NODES, DEFAULT_GREEDY_WEIGHT);

    /**
     * The Tier 1 default plus break + place enabled — the test/headless config that proves break/place
     * pathfinding (mine through leaves, bridge a gap). Insta-mines any breakable block ({@link
     * #UNBREAKABLE} cap) and places an unlimited supply of throwaway blocks; carries the default search
     * params. This reproduces the follower's historical hardcoded {@code CAPS} exactly (the live in-game
     * caps now come from {@link com.orebit.mod.config.Config}).
     */
    public static final BotCaps BREAK_PLACE = new BotCaps(
            1, DEFAULT_SAFE_FALL, DEFAULT_MAX_FALL, true, DEFAULT_COST_PER_HITPOINT, true, true,
            UNBREAKABLE, false, DEFAULT_MAX_NODES, DEFAULT_GREEDY_WEIGHT);

    // ---- realizability signature (#5 RegionCrossingMemory caps-dominance tag) --------------------------
    //
    // Packs the axes that determine whether a region crossing is PHYSICALLY REALIZABLE into one long, so
    // a persisted invalidation can be tagged with the conditions that failed it and a later bot can decide by
    // DOMINANCE whether that failure binds it. Deliberately EXCLUDES the search knobs (maxNodes/greedyWeight)
    // and the cost knob (costPerHitpoint — a damage-priced-but-passable hop is still realizable; the
    // realizability boundary is takesDamage + the fall window, not the price). Each field occupies a disjoint
    // bit range, encoded so a LARGER field value == MORE capable: takesDamage is stored INVERTED as an
    // "invuln" bit (an immune bot is strictly more capable across hazards), and maxBreakHardness + the tool
    // tiers are zeroed when !canBreak so a can't-break bot's stale mining state can't fake extra capability.
    //
    // Bit layout (DESIGN-persisted-invalidation-memory.md §3):
    //   bit  0        canBreak
    //   bit  1        EFFECTIVE canPlace — caps.canPlace AND, when an InventoryView is supplied with
    //                 consumesBlocks on, at least one carried placeable block (the search's own placement
    //                 gate, MovementContext.placeable — existence, not count). Null inv = the raw caps bit.
    //   bit  2        allowUnbreakable
    //   bit  3        mayToggleDoors
    //   bit  4        invuln (= !takesDamage)
    //   bits 5..12    maxBreakHardness (8 bits, 0..255; zeroed when !canBreak)
    //   bits 13..16   jumpHeight (4 bits)
    //   bits 17..29   safeFallDistance (13 bits)
    //   bits 30..42   maxFallDistance (13 bits)
    //   bit  43       RESERVED: hasBreath. NOT set, NOT read — a sig bit for an unmodeled constraint would be
    //                 a false hypothesis on every recorded theorem; claim it only when the search actually
    //                 models submersion budgets (§2).
    //   bits 44..61   six 3-bit tool-tier fields, one per real NavBlock.Tool category (ordinals 1..6 =
    //                 PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS; category c at shift 44 + 3·(c−1)). Value =
    //                 the MiningModel.Snapshot's best carried Tier ordinal for that category (monotone in
    //                 destroy speed, BARE = 0). NONE (ordinal 0) is deliberately excluded: its tier is inert
    //                 in the mining formula (a NONE-category block mines at speed 1 for every tier). Zeroed
    //                 when !canBreak (mirrors the maxBreakHardness zeroing) and when no InventoryView was
    //                 supplied — a null-inv search prices breaks at bare hand (MiningModel.bareHandTicks),
    //                 so BARE(0) is exactly faithful there.
    //   bits 62..63   free
    private static final int  SIG_INVULN         = 4;                              // bit 4 (= !takesDamage)
    private static final long SIG_BOOL_MASK      = 0b11111L;                       // bits 0..4 (the 5 bools)
    private static final int  SIG_MBH_SHIFT      = 5;                              // bits 5..12  (8 bits 0..255)
    private static final long SIG_MBH_MASK       = 0xFFL   << SIG_MBH_SHIFT;
    private static final int  SIG_JUMP_SHIFT     = 13;                             // bits 13..16 (4 bits)
    private static final long SIG_JUMP_MASK      = 0xFL    << SIG_JUMP_SHIFT;
    private static final int  SIG_SAFEFALL_SHIFT = 17;                             // bits 17..29 (13 bits)
    private static final long SIG_SAFEFALL_MASK  = 0x1FFFL << SIG_SAFEFALL_SHIFT;
    private static final int  SIG_MAXFALL_SHIFT  = 30;                             // bits 30..42 (13 bits)
    private static final long SIG_MAXFALL_MASK   = 0x1FFFL << SIG_MAXFALL_SHIFT;
    // bit 43 RESERVED (hasBreath — see the layout note above; deliberately no constant sets or reads it)
    private static final int  SIG_TIER_SHIFT      = 44;                            // bits 44..61 (6 × 3 bits)
    private static final int  SIG_TIER_BITS       = 3;
    /** Tool categories carried in the sig: {@code NavBlock.Tool} ordinals 1..6 (NONE excluded — inert). */
    private static final int  SIG_TIER_FIELDS     = 6;
    private static final long SIG_TIER_FIELD_MASK = 0x7L;

    /**
     * Pack the realizability-relevant caps into one long — the caps-dominance tag a {@code RegionCrossingMemory}
     * (#5) stores with each remembered dead crossing. Caps-only form: equivalent to {@link
     * #realizabilitySig(MovementContext.InventoryView) realizabilitySig(null)} — raw {@code canPlace}, tool
     * tiers at BARE — for callers with no inventory model (headless / trace / tests). See the field-layout
     * note above.
     */
    public long realizabilitySig() {
        return realizabilitySig(null);
    }

    /**
     * The EFFECTIVE realizability signature of one search: these caps under the given per-search {@link
     * MovementContext.InventoryView} (DESIGN-persisted-invalidation-memory.md §3). The sig must record the
     * conditions the failing search actually proved under, so two axes come from the inventory:
     * <ul>
     *   <li><b>effective place</b> (bit 1): the search's own placement gate ({@link MovementContext#placeable})
     *       refuses every placement when {@code consumesBlocks} is on and the bot carries no placeable block, so
     *       the sig's place bit clears in exactly that case;</li>
     *   <li><b>tool tiers</b> (bits 44..61): the {@link MiningModel.Snapshot}'s best carried tier per real tool
     *       category — the tiers the search priced every break at.</li>
     * </ul>
     * {@code null} inv = the caps-only semantics (raw place bit, BARE tiers — matching the null-inv search's own
     * bare-hand break pricing). See the field-layout note above for the full bit map.
     */
    public long realizabilitySig(MovementContext.InventoryView inv) {
        long s = 0L;
        // Effective place: the raw capability AND the search's placement-existence gate (see the layout note).
        boolean effectivePlace = canPlace && !(inv != null && inv.consumesBlocks() && inv.placeableBlocks() <= 0);
        if (canBreak)         s |= 1L << 0;
        if (effectivePlace)   s |= 1L << 1;
        if (allowUnbreakable) s |= 1L << 2;
        if (mayToggleDoors)   s |= 1L << 3;
        if (!takesDamage)     s |= 1L << SIG_INVULN;
        s |= (canBreak ? clampField(maxBreakHardness, 0xFF) : 0L) << SIG_MBH_SHIFT;
        s |= clampField(jumpHeight, 0xF)        << SIG_JUMP_SHIFT;
        s |= clampField(safeFallDistance, 0x1FFF) << SIG_SAFEFALL_SHIFT;
        s |= clampField(maxFallDistance, 0x1FFF)  << SIG_MAXFALL_SHIFT;
        if (canBreak && inv != null && inv.mining() != null) {
            MiningModel.Snapshot mining = inv.mining();
            for (int cat = 1; cat <= SIG_TIER_FIELDS; cat++) {
                s |= clampField(mining.bestTierOrdinal(cat), (int) SIG_TIER_FIELD_MASK)
                        << (SIG_TIER_SHIFT + (cat - 1) * SIG_TIER_BITS);
            }
        }
        return s;
    }

    private static long clampField(int v, int max) {
        return v < 0 ? 0L : Math.min(v, max);
    }

    /**
     * Whether caps signature {@code a} is at least as capable as {@code b} on EVERY realizability axis (a
     * DOMINATES b). An invalidation recorded by caps R binds a later bot with caps C iff {@code
     * sigDominates(R, C)} — R was no less capable, so if R couldn't realize the crossing, C can't either.
     * Equal caps dominate each other (an invalidation binds an identical bot); a strictly stronger bot is NOT
     * dominated and rightly ignores the weaker bot's negative. Cheap: disjoint-field bit compares, cold path.
     */
    public static boolean sigDominates(long a, long b) {
        // every capability bool set in b must also be set in a (else b has a capability a lacks → a ⊉ b)
        if ((b & SIG_BOOL_MASK & ~a) != 0L) return false;
        // each numeric field lives in a disjoint bit range → a masked compare orders that field alone
        if ((a & SIG_MBH_MASK)      < (b & SIG_MBH_MASK)
         || (a & SIG_JUMP_MASK)     < (b & SIG_JUMP_MASK)
         || (a & SIG_SAFEFALL_MASK) < (b & SIG_SAFEFALL_MASK)
         || (a & SIG_MAXFALL_MASK)  < (b & SIG_MAXFALL_MASK)) return false;
        // the per-category tool-tier fields (bits 44..61) — the same masked-field rule, one per category
        for (int i = 0; i < SIG_TIER_FIELDS; i++) {
            long m = SIG_TIER_FIELD_MASK << (SIG_TIER_SHIFT + i * SIG_TIER_BITS);
            if ((a & m) < (b & m)) return false;
        }
        return true;
    }
}
