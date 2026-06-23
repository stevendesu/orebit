package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The capability gate for one bot (MOVEMENT-DESIGN.md §5, PRD §7.3): what <i>this</i> bot is allowed
 * and able to do. Movements filter their candidates on it and parameterise their costs on it, so the
 * same nav grid yields different paths for a fragile walk-only helper vs. a fully-equipped one.
 *
 * <p>Immutable; seeded from server/bot config. The defaults are deliberately the narrowest useful set
 * — <b>walk + jump 1, no break, no place, mortal, drowns</b> — i.e. only the Tier&nbsp;1 ground
 * movements are enabled. Widen as later movement tiers (break/place/swim/climb) land; the unused
 * fields are carried now so the contract doesn't churn when their consumers arrive.
 */
public record BotCaps(
        /** Max blocks a single jump gains (Ascend / step-up). */
        int jumpHeight,
        /** Max blocks the bot may drop without harm (Fall) before the move is rejected. */
        int safeFallDistance,
        /** May mine soft blocks in the way (BreakThrough — Tier 3). */
        boolean canBreak,
        /** May place throwaway blocks (Pillar / Bridge — Tier 3). */
        boolean canPlace) {

    /** Walk + jump 1, no break/place, conservative 3-block safe fall — the Tier 1 default. */
    public static final BotCaps DEFAULT = new BotCaps(1, 3, false, false);
}
