package com.orebit.mod.config;

/**
 * The single source of truth for the flat, namespaced property keys in {@code config/orebit.properties}
 * (PRD §10 Phase 1a / AGENCY-LAYER-PLAN "Capability config"). Every key the owner can set appears here
 * exactly once, so {@link ConfigLoader} (read + default-file generation), {@link ConfigValidator} (range
 * checks), and any future doc/GUI tooling all name the same string — no stringly-typed drift between the
 * file format, the parser, and the validator.
 *
 * <h2>Layout — flat dotted namespaces, NOT nested sections</h2>
 * The on-disk format is a plain {@link java.util.Properties} file (JDK built-in, zero new deps; {@code #}
 * comments). Keys are grouped by a leading namespace that mirrors the four owner-facing capability
 * groups Steve listed (survival / placement / mining / pathing), e.g. {@code mining.consumesTools}. The
 * grouping is purely lexical (a dotted prefix), since {@code Properties} has no real sections.
 *
 * <p>Constants are grouped + ordered the same way the default file is written, so reading this class
 * top-to-bottom reads the generated {@code orebit.properties}. Pure constants — no logic, no state
 * (honouring "no Utils/Helper classes": this is a key vocabulary, not a helper).
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    // ---- survival: does the bot have a body that can be hurt / starve / drown? ---------------------
    /** {@code boolean} — bot takes damage (fall, mob, fire, …) at all. Default {@code false} (invulnerable). */
    public static final String SURVIVAL_TAKES_DAMAGE = "survival.takesDamage";
    /** {@code boolean} — bot has a hunger bar that depletes. Default {@code false}. */
    public static final String SURVIVAL_HUNGER = "survival.hunger";
    /** {@code boolean} — bot needs air underwater (drowns). Default {@code false}. */
    public static final String SURVIVAL_NEEDS_BREATH = "survival.needsBreath";

    // ---- placement: may the bot place blocks, and what does that cost / conjure? --------------------
    /** {@code boolean} — bot may place blocks (bridge a gap, pillar up). Default {@code true}. */
    public static final String PLACEMENT_CAN_PLACE = "placement.canPlace";
    /** {@code boolean} — placing consumes a real block from the bot's inventory. Default {@code false} (infinite). */
    public static final String PLACEMENT_CONSUMES_BLOCKS = "placement.consumesBlocks";
    /**
     * {@code block id} ("namespace:path") — the throwaway block the bot conjures/places when {@link
     * #PLACEMENT_CONSUMES_BLOCKS} is off. Default {@code minecraft:cobblestone} (today's hardcoded
     * {@code PLACE_BLOCK}). Must resolve to a real block on the running version or the validator rejects it.
     */
    public static final String PLACEMENT_CONJURED_BLOCK = "placement.conjuredBlock";
    /**
     * {@code float >= 0} — how strongly the bot avoids placing hard-to-remove blocks. Each placement is
     * charged extra by the placed block's mine-out time (ticks) × this weight, so a mixed inventory favours
     * dirt/cobblestone over obsidian. {@code 1.0} (default) = full mine-out cost; {@code 0} disables the
     * premium (placement cost ignores the block).
     */
    public static final String PLACEMENT_REMOVAL_COST_WEIGHT = "placement.removalCostWeight";
    /**
     * {@code float >= 0} — the flat cost (in ticks) charged per block placed. NOT a physical place time
     * (placing is ~instant in-game); it is a behavioral "reluctance to place" penalty (positioning/facing
     * overhead + a bias against needless scaffolding) that tilts the planner toward walking/digging over
     * building. Lower for a more build-happy bot; raise to discourage placing. Default {@code 6.0}.
     */
    public static final String PLACEMENT_PLACE_BASE_COST = "placement.placeBaseCost";

    // ---- mining: may the bot mine, what can it mine, how long does it take? -------------------------
    /** {@code boolean} — bot may mine (break) blocks in its way. Default {@code true}. */
    public static final String MINING_CAN_MINE = "mining.canMine";
    /** {@code boolean} — mining wears down (and consumes) the bot's tools. Default {@code false}. */
    public static final String MINING_CONSUMES_TOOLS = "mining.consumesTools";
    /**
     * {@code int} 0..255 — the hardest block (quantized {@link
     * com.orebit.mod.worldmodel.navblock.NavBlock} hardness) the bot may mine. {@code 255} = mine
     * anything breakable (today's insta-mine cap); lower values let a weak bot mine only soft blocks.
     */
    public static final String MINING_MAX_HARDNESS = "mining.maxHardness";
    /**
     * {@code boolean} — mining time scales with block hardness ({@code true}, the physically-derived
     * model) vs. a flat per-block time ({@code false}, {@link #MINING_TICKS_TO_MINE_FLAT}). Default
     * {@code true}.
     */
    public static final String MINING_TICKS_BY_HARDNESS = "mining.ticksByHardness";
    /**
     * {@code int} — ticks to mine one block when {@link #MINING_TICKS_BY_HARDNESS} is off (the flat
     * model). Ignored when ticks-by-hardness is on. Default {@code 0} (insta-mine, matching today).
     */
    public static final String MINING_TICKS_TO_MINE_FLAT = "mining.ticksToMineFlat";
    /**
     * {@code float >= 0} — a flat surcharge (in ticks) added to <b>every block break the planner folds</b>,
     * on top of the real mining time — the mining-side mirror of {@link #PLACEMENT_PLACE_BASE_COST}: a
     * behavioral "reluctance to edit the world" penalty that biases the bot toward walking/detouring over
     * digging (and over punching through berry bushes / cobwebs) when comparable routes exist. Raise it to
     * discourage gratuitous world edits. Default {@code 0.0} (breaks priced at mining time alone).
     */
    public static final String MINING_BREAK_BASE_COST = "mining.breakBaseCost";
    /**
     * Comma-separated list of block ids and {@code #}-prefixed block tags the bot must <b>NEVER</b> break
     * — nor clear/replace with a placement — (e.g. {@code minecraft:chest, #minecraft:beds,
     * minecraft:diamond_ore}). Default empty (nothing protected). Enforced BOTH planner-side (folded into
     * the NavBlock classification fingerprint as the
     * PROTECTED descriptor bit, so routes are planned around protected blocks) and execution-side (every
     * live break re-checks the list — the stale-grid backstop). Malformed entries warn and are skipped.
     * <b>Changing this list requires a server restart</b> (or waiting for chunks to rebuild) to fully
     * apply: nav-grid data classified before the change still carries the old fingerprints; the
     * execution-side refusal applies immediately.
     */
    public static final String MINING_PROTECTED_BLOCKS = "mining.protectedBlocks";
    /**
     * {@code boolean} — bot may mine <b>vanilla-unbreakable</b> blocks (negative destroy time: bedrock,
     * barriers, end portal frames, …) at a fixed, very large stand-in time cost. Its own gate (a separate
     * axis from {@link #MINING_MAX_HARDNESS}, which only ranges over breakable blocks — the unbreakable
     * sentinel doesn't order against real hardness). {@link #MINING_PROTECTED_BLOCKS} always overrides.
     * Default {@code false}.
     */
    public static final String MINING_ALLOW_UNBREAKABLE = "mining.allowUnbreakable";

    // ---- pathing: the A* search knobs (carried on BotCaps into BlockPathfinder) ---------------------
    /** {@code int > 0} — A* node-expansion ceiling per search. Default {@code 10000}. */
    public static final String PATHING_MAX_NODES = "pathing.maxNodes";
    /**
     * {@code float >= 1.0} — heuristic greediness weight. {@code 1.0} = admissible/optimal/slow; higher
     * beelines (far fewer nodes, paths no longer guaranteed optimal). Default {@code 2.0}.
     */
    public static final String PATHING_GREEDY_WEIGHT = "pathing.greedyWeight";
    /**
     * {@code float >= 0} — ticks the bot considers <b>1 HP of damage</b> to be worth: the ONE knob every
     * damage-as-cost planner term is priced in (hazard-cell transit — fire / berry bush / powder snow —
     * and fall damage past the safe window). One HP buys {@code costPerHitpoint / 4.633} walk-blocks of
     * detour (≈ 21.6 at the default {@code 100.0}); raise it for a more self-preserving bot. Only
     * meaningful when {@code survival.takesDamage} is on (an immune bot's damage terms are zero).
     */
    public static final String PATHING_COST_PER_HITPOINT = "pathing.costPerHitpoint";
    /**
     * {@code boolean} — run a short synthetic pathfinder warm-up at server start (~0.3–1.5 s, before any
     * player can join) so the first REAL search doesn't run JIT-cold (a one-time ~16 ms tick stall on the
     * first search after boot otherwise). Costs startup wall-clock only — zero effect on any search after
     * boot. Default {@code true}.
     */
    public static final String PATHING_WARMUP = "pathing.warmup";
    /**
     * {@code int >= 0} — hard wall-clock cap (milliseconds) on that warm-up pass; it usually finishes
     * earlier (it stops once search times plateau). {@code 0} disables the warm-up entirely (same as
     * {@code pathing.warmup=false}). Default {@code 1500}.
     */
    public static final String PATHING_WARMUP_BUDGET_MS = "pathing.warmupBudgetMs";
    /**
     * {@code boolean} — run path searches on background planner threads instead of the server tick thread
     * (DESIGN-background-pathfinding.md). The tick thread submits a search and adopts the result at the
     * same settled boundary plans already swap at; searches stop costing tick time entirely, and the
     * wall-clock budget below replaces the node cap as the effective search limit. Plans arrive 1–3 ticks
     * after they're requested (the bot keeps walking its current plan meanwhile). Default {@code false}
     * (today's synchronous behaviour, byte-identical). Changing it requires a server restart.
     */
    public static final String PATHING_ASYNC = "pathing.async";
    /**
     * {@code int >= 1} — background planner thread count when {@link #PATHING_ASYNC} is on (clamped to
     * {@code [1, cores − 2]} at start). Bots share the pool; raise it on a multi-tenant server (many bots)
     * to cut search tail latency, lower it to 1 on a constrained host. Like view-distance, this trades bot
     * responsiveness against server CPU headroom. Default {@code 2}. Requires a server restart to change.
     */
    public static final String PATHING_MAX_THREADS = "pathing.maxThreads";
    /**
     * {@code int >= 1} — wall-clock budget (milliseconds) per background path search when
     * {@link #PATHING_ASYNC} is on: the time-based cap that replaces {@code pathing.maxNodes} as the
     * effective limit (the node cap remains as a memory backstop). A search that exhausts the budget
     * returns its best partial path — the bot moves that way and replans, converging on far goals.
     * Bigger budgets escape bigger dead-ends at the cost of longer worst-case plan latency (the tick
     * itself is never stalled either way). Default {@code 40} (~4–10× the node cap's reach, warm).
     */
    public static final String PATHING_SEARCH_BUDGET_MS = "pathing.searchBudgetMs";
}
