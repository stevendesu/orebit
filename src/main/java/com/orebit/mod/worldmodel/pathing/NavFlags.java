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
 * <h2>The bitmask (5 of 6 bits used; bit 5 reserved)</h2>
 * <pre>
 *   bit 0     RISKY_EDIT         breaking/placing in this cell's body space could release a fluid or
 *                                drop a gravity block onto the bot — a BREAK/PLACE gate, NOT a walk gate
 *                                (walking through is fine). Merges the design's separate risksFluidFlow +
 *                                risksGravityFall: both mean "don't edit here", and the precise
 *                                flood/cascade check happens in the fine layer (descriptorAt) at break
 *                                time, so one prefilter bit suffices.
 *   bit 1     CLEARABLE_HAZARD   a walk-through damaging block in the body space (fire) — adds cost, not
 *                                blocked. (A damaging FLOOR — lava/magma/cactus — is intrinsic to the
 *                                navtype via NavBlock.isDamaging, so it needs no bit.)
 *   bits 2-3  HEADROOM           walkable vertical clearance above the floor: 0 none / 1 crawl / 2 walk /
 *                                3 jump. A cell counts as clear iff it's passable AND fluid-free, so the
 *                                value matches the walk-passable test the ground movements use (water in
 *                                the body space is NOT clearance for a walker — swim is a later movement).
 *   bit 4     PLACEABLE_NEIGHBOR a solid (non-fluid) face among the six neighbours to bridge a placed
 *                                block against.
 *   bit 5     (reserved)
 * </pre>
 *
 * <h2>Boundary handling — always within-section, no overscan (§8)</h2>
 * Out-of-section reads resolve to air via {@link #at}, so a block update recomputes flags inside one
 * section only — never reaching into a neighbouring nav grid. The bitmask is therefore exact in a
 * section's interior and air-optimistic within one cell of a face. That is by design: the movement layer
 * treats {@code HEADROOM} as a prefilter (re-verifying via {@code descriptorAt} near faces), and the
 * precise edit-hazard check for {@code RISKY_EDIT} likewise lives in the fine layer at break time — so an
 * optimistic boundary is caught there, not trusted blindly. (The conservative alternating-air/water fluid
 * OOB default §8 specifies lands with the fluid-aware break modifier that consumes the bit; no consumer
 * reads it yet.)
 */
public final class NavFlags {

    private NavFlags() {}

    // ---- Bit layout within the 6-bit field ---------------------------------------------------
    public static final int RISKY_EDIT         = 1 << 0;
    public static final int CLEARABLE_HAZARD   = 1 << 1;
    private static final int HEADROOM_SHIFT    = 2;
    public static final int HEADROOM_MASK      = 0x3 << HEADROOM_SHIFT; // bits 2-3
    public static final int PLACEABLE_NEIGHBOR = 1 << 4;
    // bit 5 reserved

    /** Headroom levels — the value of the 2-bit HEADROOM field (not pre-shifted). */
    public static final int HEADROOM_NONE  = 0; // can't even crawl: the cell directly above is blocked
    public static final int HEADROOM_CRAWL = 1; // 1-tall gap
    public static final int HEADROOM_WALK  = 2; // 2-tall: normal standing
    public static final int HEADROOM_JUMP  = 3; // 3-tall: room to jump

    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    // Horizontal neighbours (x,z) for fluid-flow risk.
    private static final int[][] HORIZONTAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    // All six neighbours (x,y,z) for "is there a face to place against".
    private static final int[][] SIX = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * Compute the neighbour-property bitmask for one cell. {@code desc} is a section's 4096 packed
     * descriptors in canonical {@code (y<<8)|(z<<4)|x} order; out-of-section reads resolve to air
     * (air-optimistic — see boundary handling above).
     */
    public static int compute(long[] desc, int x, int y, int z) {
        long ground = at(desc, x, y, z);
        long a1 = at(desc, x, y + 1, z);
        long a2 = at(desc, x, y + 2, z);
        long a3 = at(desc, x, y + 3, z);

        int flags = 0;

        // HEADROOM: how many body cells above the floor are clear for a WALKER (passable AND fluid-free,
        // so the value lines up with MovementContext.passable — water is not walk-clearance). Breaking a
        // block in the way is the break modifier's job (it consults RISKY_EDIT); headroom is the raw
        // clearance prefilter.
        int headroom;
        if (!walkClear(a1)) headroom = HEADROOM_NONE;
        else if (!walkClear(a2)) headroom = HEADROOM_CRAWL;
        else if (!walkClear(a3)) headroom = HEADROOM_WALK;
        else headroom = HEADROOM_JUMP;
        flags |= headroom << HEADROOM_SHIFT;

        // CLEARABLE_HAZARD: a walk-through damaging block in the body space (e.g. fire).
        if (NavBlock.isDamaging(a1) || NavBlock.isDamaging(a2)) flags |= CLEARABLE_HAZARD;

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
    public static boolean placeableNeighbor(int flags) { return (flags & PLACEABLE_NEIGHBOR) != 0; }

    // ---- Neighbour scans (carried over from the prior classifier) ----------------------------

    private static long at(long[] desc, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= 16 || y >= 16 || z >= 16) return AIR_DESC;
        return desc[(y << 8) | (z << 4) | x];
    }

    /** Clear for a walking body: no collision AND no fluid (water/lava block a walker — swim is later). */
    private static boolean walkClear(long d) {
        return NavBlock.isPassable(d) && NavBlock.fluid(d) == 0;
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
