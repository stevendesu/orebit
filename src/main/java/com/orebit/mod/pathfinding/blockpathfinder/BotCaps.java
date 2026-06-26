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
 * {@link #maxNodes} and {@link #greedyWeight} are A* search knobs, not movement capabilities, but they
 * ride on {@code BotCaps} because {@code caps} is already the one object threaded into {@link
 * BlockPathfinder#findPath} per search. The hot loop reads them into <i>locals</i> at search start (see
 * {@link BlockPathfinder}), so the per-node code still reads a local, never a field — no hot-path cost
 * for carrying them here (HOT-PATH-NO-ALLOC / favour-cpu-over-ram).
 */
public record BotCaps(
        /** Max blocks a single jump gains (Ascend / step-up). */
        int jumpHeight,
        /** Max blocks the bot may drop without harm (Fall) before the move is rejected. */
        int safeFallDistance,
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
        float greedyWeight) {

    /** Quantized-hardness sentinel for "unbreakable / mine anything" — the historical insta-mine cap. */
    public static final int UNBREAKABLE = 255;

    /** Default node-expansion ceiling — the historical {@code BlockPathfinder.MAX_EXPANSIONS}. */
    public static final int DEFAULT_MAX_NODES = 10000;

    /** Default heuristic weight — the historical {@code BlockPathfinder.H_WEIGHT} (greedy). */
    public static final float DEFAULT_GREEDY_WEIGHT = 2.0f;

    /**
     * Walk + jump 1, no break/place, conservative 3-block safe fall — the Tier 1 default. Carries the
     * default search params (10k nodes, weight 2.0) and an unbreakable cap (moot with {@code canBreak}
     * false).
     */
    public static final BotCaps DEFAULT = new BotCaps(
            1, 3, false, false, UNBREAKABLE, DEFAULT_MAX_NODES, DEFAULT_GREEDY_WEIGHT);

    /**
     * The Tier 1 default plus break + place enabled — the test/headless config that proves break/place
     * pathfinding (mine through leaves, bridge a gap). Insta-mines any breakable block ({@link
     * #UNBREAKABLE} cap) and places an unlimited supply of throwaway blocks; carries the default search
     * params. This reproduces the follower's historical hardcoded {@code CAPS} exactly (the live in-game
     * caps now come from {@link com.orebit.mod.config.Config}).
     */
    public static final BotCaps BREAK_PLACE = new BotCaps(
            1, 3, true, true, UNBREAKABLE, DEFAULT_MAX_NODES, DEFAULT_GREEDY_WEIGHT);
}
