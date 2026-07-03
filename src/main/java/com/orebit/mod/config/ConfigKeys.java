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
}
