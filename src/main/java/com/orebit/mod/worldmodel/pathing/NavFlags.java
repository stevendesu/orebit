package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * Computes the per-cell neighbour-property bitmask stored in the high 6 bits of each
 * {@link TraversalGrid} entry (MOVEMENT-DESIGN.md §8). Replaces the dead 4-value {@code TraversalClass}:
 * nothing ever read the class value (only {@code != null} as a "section loaded" gate, now
 * {@link NavGridView#built}), so the bits are repurposed to carry the multi-cell facts the movement
 * layer would otherwise re-derive on every A* expansion — precomputed once at build / block-update and
 * read as a single masked grid access.
 *
 * <p>Reads only packed {@link NavBlock} descriptors (the {@code long[]} scratch) — no world access, no
 * hardcoded {@code Blocks.X}. Inherits the descriptor-derived facts the prior {@code NavClassifier}
 * computed; this class just emits them as separate bits instead of collapsing them into one coarse class.
 *
 * <h2>Cell convention</h2>
 * The flags at grid cell {@code (x,y,z)} describe <b>standing on the block at that cell</b>: the cell is
 * the floor; {@code (x,y+1,z)}…{@code (x,y+3,z)} are the body/clearance space above. (Same convention the
 * movement layer uses — an A* node IS a floor cell. A cell's <i>own</i> geometry — standable, slow,
 * damaging floor, fluid — stays in the navtype descriptor, read via {@code descriptorAt}; only the
 * neighbour-derived facts live here.)
 *
 * <h2>The bitmask (all 6 bits used)</h2>
 * <pre>
 *   bit 0     RISKY_EDIT         breaking/placing in this cell's body space could release a fluid or
 *                                drop a gravity block onto the bot — a BREAK/PLACE gate, NOT a walk gate
 *                                (walking through is fine). Merges the design's separate risksFluidFlow +
 *                                risksGravityFall: both mean "don't edit here", and the precise
 *                                flood/cascade check happens in the fine layer (descriptorAt) at break
 *                                time, so one prefilter bit suffices.
 *   bit 1     CLEARABLE_HAZARD   a walk-through damaging block in the body space (fire, berry bush,
 *                                powder snow) — adds cost, not blocked. (A damaging FLOOR — lava/magma/
 *                                cactus — is intrinsic to the navtype via NavBlock.isDamaging, so it needs
 *                                no bit.) Consumed by MovementContext.bodyTransitCost as the zero-read
 *                                prefilter for the per-cell damage surcharge; a mortal bot pays per
 *                                damaging body cell, an invulnerable one pays nothing.
 *   bits 2-3  HEADROOM           walkable vertical clearance above the floor: 0 none / 1 crawl / 2 walk /
 *                                3 jump. A cell counts as clear iff it's passable AND fluid-free, so the
 *                                value matches the walk-passable test the ground movements use (water in
 *                                the body space is NOT clearance for a walker — swim is a later movement).
 *   bit 4     PLACEABLE_NEIGHBOR a solid (non-fluid) face among the six neighbours to bridge a placed
 *                                block against.
 *   bit 5     SLOW_TRANSIT       a through-slow passable block in the body space (cobweb / berry bush /
 *                                powder snow — NavBlock.transitSlow != 0): moving through costs extra
 *                                regardless of damage caps (physics slows everyone). Like
 *                                CLEARABLE_HAZARD it is a prefilter: the movement layer reads the two
 *                                body descriptors for the exact per-cell magnitude only when it's set.
 * </pre>
 *
 * <h2>Boundary handling — vertical (upward) overscan; lateral faces still air-optimistic (§8)</h2>
 * The scratch handed to {@link #compute} may carry {@link #OVERSCAN_ROWS} extra rows ABOVE the section
 * ({@code y = 16..18}, indices {@code 4096..}{@link #SCRATCH_SIZE}{@code -1} — the canonical
 * {@code (y<<8)|(z<<4)|x} formula extends naturally), filled from the section directly above in the same
 * chunk column. This closes the vertical-seam blindness that made the top ~3 floor rows of every section
 * carry stale-CLEAR {@code CLEARABLE_HAZARD}/{@code SLOW_TRANSIT} bits (and under-informed
 * {@code HEADROOM}): those bits are column-local (they read only {@code y+1..y+3}), so with upward
 * overscan they are now EXACT everywhere — a berry bush at the bottom row of section {@code k} is seen by
 * the floor cells at the top of section {@code k-1}. Vertical neighbours always share a chunk, so the
 * above section's data is available at build time with no cross-chunk ordering problem; the world-top
 * section (and a legacy 4096-length scratch — {@link #at} bounds on {@code desc.length}) resolves the
 * overscan rows to air, which is correct there.
 *
 * <p><b>Still air-optimistic (recorded, deferred):</b>
 * <ul>
 *   <li><b>Lateral faces</b> — {@code RISKY_EDIT}'s fluid-flow scan and {@code PLACEABLE_NEIGHBOR} read
 *       {@code x±1}/{@code z±1}, which cross CHUNKS at a section's side faces; lateral overscan has a real
 *       cross-chunk build-ordering problem and is a deferred follow-up. Until then a fluid just across a
 *       side face is not flagged risky (the precise flood/cascade check in the fine layer at break time —
 *       and the conservative alternating-air/water fluid OOB default §8 specifies — lands with the
 *       fluid-aware break modifier; no consumer reads the bit yet), and a placeable face just across is
 *       missed (pessimistic: at worst a legal bridge is not offered).</li>
 *   <li><b>The downward face</b> — {@code y-1} reads at a section's bottom row ({@code unsupported},
 *       {@code hasPlaceableNeighbor}'s down face) resolve to air. Both err safe: RISKY_EDIT over-sets
 *       (a gravity block is assumed unsupported) and PLACEABLE_NEIGHBOR under-sets.</li>
 * </ul>
 * The movement layer still treats {@code HEADROOM} as a prefilter (re-verifying via {@code descriptorAt}
 * near faces), so any residual optimism is caught in the fine layer, not trusted blindly.
 */
public final class NavFlags {

    private NavFlags() {}

    // ---- Bit layout within the 6-bit field ---------------------------------------------------
    public static final int RISKY_EDIT         = 1 << 0;
    public static final int CLEARABLE_HAZARD   = 1 << 1;
    private static final int HEADROOM_SHIFT    = 2;
    public static final int HEADROOM_MASK      = 0x3 << HEADROOM_SHIFT; // bits 2-3
    public static final int PLACEABLE_NEIGHBOR = 1 << 4;
    public static final int SLOW_TRANSIT       = 1 << 5;

    /** Headroom levels — the value of the 2-bit HEADROOM field (not pre-shifted). */
    public static final int HEADROOM_NONE  = 0; // can't even crawl: the cell directly above is blocked
    public static final int HEADROOM_CRAWL = 1; // 1-tall gap
    public static final int HEADROOM_WALK  = 2; // 2-tall: normal standing
    public static final int HEADROOM_JUMP  = 3; // 3-tall: room to jump

    // ---- Vertical overscan (the scratch contract with NavSectionBuilder) ----------------------
    /**
     * Rows of the section ABOVE appended to the descriptor scratch ({@code y = 16..18}) so top-row flag
     * computation sees real blocks instead of optimistic air. 3 = the deepest upward read
     * ({@code compute} reads at most {@code y+3}).
     */
    public static final int OVERSCAN_ROWS = 3;
    /** Length of an overscan-carrying descriptor scratch: 4096 own cells + 3×256 overscan rows. */
    public static final int SCRATCH_SIZE = 4096 + OVERSCAN_ROWS * 256; // 4864

    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    // Horizontal neighbours (x,z) for fluid-flow risk.
    private static final int[][] HORIZONTAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    // All six neighbours (x,y,z) for "is there a face to place against".
    private static final int[][] SIX = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * Compute the neighbour-property bitmask for one cell. {@code desc} is a section's packed descriptors
     * in canonical {@code (y<<8)|(z<<4)|x} order — either the bare 4096 cells (reads above the section
     * resolve to air) or a {@link #SCRATCH_SIZE}-length scratch whose rows {@code y = 16..18} hold the
     * section above's descriptors (vertical overscan; the index formula extends naturally). Reads outside
     * the scratch — lateral, below, or above the overscan — resolve to air (see boundary handling above).
     */
    public static int compute(long[] desc, int x, int y, int z) {
        long ground = at(desc, x, y, z);
        long a1 = at(desc, x, y + 1, z);
        long a2 = at(desc, x, y + 2, z);
        long a3 = at(desc, x, y + 3, z);

        int flags = 0;

        // HEADROOM: how many body cells above the floor are clear for a WALKER (passable AND fluid-free AND
        // not a teleport portal, so the value lines up with MovementContext.passable — water is not
        // walk-clearance, and a portal cell is a no-go the walker routes around). Breaking a block in the way
        // is the break modifier's job (it consults RISKY_EDIT); headroom is the raw clearance prefilter.
        int headroom;
        if (!walkClear(a1)) headroom = HEADROOM_NONE;
        else if (!walkClear(a2)) headroom = HEADROOM_CRAWL;
        else if (!walkClear(a3)) headroom = HEADROOM_WALK;
        else headroom = HEADROOM_JUMP;
        flags |= headroom << HEADROOM_SHIFT;

        // CLEARABLE_HAZARD: a walk-through damaging block in the body space (e.g. fire, berry bush).
        if (NavBlock.isDamaging(a1) || NavBlock.isDamaging(a2)) flags |= CLEARABLE_HAZARD;

        // SLOW_TRANSIT: a through-slow passable block in the body space (cobweb / berry bush / powder snow).
        if (NavBlock.transitSlow(a1) != NavBlock.TRANSIT_NONE
                || NavBlock.transitSlow(a2) != NavBlock.TRANSIT_NONE) {
            flags |= SLOW_TRANSIT;
        }

        // RISKY_EDIT: an edit in the body space could let a fluid flow in or drop a gravity block.
        //   - gravity above (would fall when disturbed), or a gravity block here/in-the-feet we'd undercut;
        //   - a horizontal neighbour of the body space holds a fluid not already draining straight down.
        if (NavBlock.hasGravity(a2)
                || (NavBlock.hasGravity(ground) && unsupported(desc, x, y, z))
                || (NavBlock.hasGravity(a1) && unsupported(desc, x, y + 1, z))
                || risksFluidFlow(desc, x, y + 1, z)
                || risksFluidFlow(desc, x, y + 2, z)) {
            flags |= RISKY_EDIT;
        }

        // PLACEABLE_NEIGHBOR: a solid face to bridge a placed block against (used at empty floor cells).
        if (hasPlaceableNeighbor(desc, x, y, z)) flags |= PLACEABLE_NEIGHBOR;

        return flags;
    }

    // ---- Field extraction (for consumers reading a stored flag value) ------------------------
    /** The 2-bit headroom level (one of {@link #HEADROOM_NONE}..{@link #HEADROOM_JUMP}). */
    public static int headroom(int flags)            { return (flags & HEADROOM_MASK) >>> HEADROOM_SHIFT; }
    public static boolean risksEdit(int flags)       { return (flags & RISKY_EDIT) != 0; }
    public static boolean clearableHazard(int flags) { return (flags & CLEARABLE_HAZARD) != 0; }
    public static boolean slowTransit(int flags)     { return (flags & SLOW_TRANSIT) != 0; }
    public static boolean placeableNeighbor(int flags) { return (flags & PLACEABLE_NEIGHBOR) != 0; }

    // ---- Neighbour scans (carried over from the prior classifier) ----------------------------

    private static long at(long[] desc, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= 16 || z >= 16) return AIR_DESC;
        int idx = (y << 8) | (z << 4) | x;
        // Rows y >= 16 land past 4096: real overscan data in a SCRATCH_SIZE scratch, air in a bare
        // 4096 one (legacy/world-top). The deepest read is y+3 = 18 < SCRATCH_SIZE/256, so idx never
        // exceeds a full overscan scratch.
        return idx < desc.length ? desc[idx] : AIR_DESC;
    }

    /** Clear for a walking body: no collision AND no fluid (water/lava block a walker — swim is later) AND
     *  not a teleport portal (the walker routes around ALL portals). Kept byte-for-byte aligned with
     *  {@code MovementContext.passable}, whose fast path this prefilters. */
    private static boolean walkClear(long d) {
        return NavBlock.isPassable(d) && NavBlock.fluid(d) == 0 && !NavBlock.isPortal(d);
    }

    /** Nothing solid directly below — a gravity block here/above would fall. */
    private static boolean unsupported(long[] desc, int x, int y, int z) {
        return NavBlock.isPassable(at(desc, x, y - 1, z));
    }

    /** A horizontal neighbour holds a fluid that is not already draining straight down. */
    private static boolean risksFluidFlow(long[] desc, int x, int y, int z) {
        for (int[] o : HORIZONTAL) {
            int nx = x + o[0], nz = z + o[1];
            if (NavBlock.fluid(at(desc, nx, y, nz)) != 0
                    && NavBlock.fluid(at(desc, nx, y - 1, nz)) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Any of the six neighbours offers a solid (non-fluid) face to place a bridging block against. */
    private static boolean hasPlaceableNeighbor(long[] desc, int x, int y, int z) {
        for (int[] o : SIX) {
            long n = at(desc, x + o[0], y + o[1], z + o[2]);
            if (!NavBlock.isPassable(n) && NavBlock.fluid(n) == 0) return true;
        }
        return false;
    }
}
