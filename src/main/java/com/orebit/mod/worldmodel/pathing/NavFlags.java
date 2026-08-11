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
 *   bit 0     RISKY_EDIT         breaking/placing at/next to this cell could drop a gravity block onto the
 *                                bot or disturb LAVA — a BREAK/PLACE gate, NOT a walk gate (walking
 *                                through is fine). Merges the design's separate risksFluidFlow +
 *                                risksGravityFall: both mean "don't edit here", and the precise
 *                                cascade check happens in the fine layer (descriptorAt) at break
 *                                time, so one prefilter bit suffices. The GRAVITY term is computed here per
 *                                cell ({@link #compute}); the LAVA term is SCATTERED from each lava cell in
 *                                {@link NavSectionBuilder#computeDepth}
 *                                (PERF-DESIGN-navgrid-build §C1) and OR-ed into this bit — the two terms
 *                                compose under OR, so either may set it independently.
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
 *                                block against. COMPUTED AND MAINTAINED, BUT CURRENTLY UNREAD BY THE
 *                                SEARCH PATH (recorded 2026-08-11 — the bit is under review, not deleted).
 *                                Its only consumer in src/main is the diagnostic {@code /bot probe}
 *                                (ProbeCommand). The placement path does NOT use it:
 *                                {@code MovementContext.placeable} runs its own 6-neighbour
 *                                {@code descriptorAt} fan-out, and on a DIFFERENT predicate — the movement
 *                                layer asks "not vanilla-REPLACEABLE" (so torches/grass/dripstone support a
 *                                placement), while this bit asks "not passable AND fluid-free". The two are
 *                                not interchangeable, so swapping the fan-out for a bit test would be a
 *                                behavior change, not an optimization.
 *   bit 5     SLOW_TRANSIT       a through-slow passable block in the body space (cobweb / berry bush /
 *                                powder snow — NavBlock.transitSlow != 0): moving through costs extra
 *                                regardless of damage caps (physics slows everyone). Like
 *                                CLEARABLE_HAZARD it is a prefilter: the movement layer reads the two
 *                                body descriptors for the exact per-cell magnitude only when it's set.
 * </pre>
 *
 * <h2>RISKY_EDIT's fluid term: LAVA-ONLY, unconditional, 6-directional (owner ruling 2026-08-10)</h2>
 * The term used to be a <i>flowing-fluid</i> dilation: "a fluid cell whose own cell-below is dry is
 * flowing", scattered onto the 4 horizontal neighbours at rows {@code y-1} and {@code y-2}. Bytecode
 * analysis of vanilla {@code FlowingFluid.spread} showed that predicate is close to ANTI-correlated with
 * reality — a fluid cell that CAN flow down does not spread sideways, while impoundment (water behind a
 * wall, every cell with fluid below it) never matched yet always floods on a break. Modelling vanilla's
 * slope-distance algorithm properly needs source-vs-flowing and fluid-level bits the 2-bit
 * {@link NavBlock} fluid field does not store, so the term was re-scoped instead:
 * <ul>
 *   <li><b>WATER never sets RISKY_EDIT.</b> Breaking near water is not treated as risky at all — a flood
 *       is survivable and the bot swims. Nothing about water reaches this bit any more.</li>
 *   <li><b>LAVA sets it unconditionally</b> — no flowing test, no cell-below read, no impoundment logic.
 *       Lava is a blunt keep-away zone: {@code NavBlock.isLava(d)}, a single mask test on the
 *       already-decoded descriptor.</li>
 *   <li><b>The geometry is a 1-cell dilation over the 6 orthogonal neighbours</b> — {@code x±1},
 *       {@code y±1}, {@code z±1} (the {@link #SIX} table). Equivalently, as a GATHER:
 *       {@link #risksLavaEdit} — a cell is risky iff any of its 6 orthogonal neighbours is lava. The lava
 *       cell ITSELF is deliberately not marked by this term (a dilation of the 6-offset structuring
 *       element, centre excluded); a lava cell is not a floor the bot stands on, and its own hazards are
 *       intrinsic to its navtype ({@code NavBlock.isDamaging}).</li>
 * </ul>
 * The build SCATTER ({@link NavSectionBuilder#computeDepth}) and the patch GATHER
 * ({@code NavSectionBuilder.recomputeWindow} via {@link #risksLavaEdit}) express exactly this dilation, so
 * a patched grid stays byte-identical to a rebuild — pinned by {@code FluidScatterIdentityTest} /
 * {@code FluidPatchIdentityTest}.
 *
 * <h2>Boundary handling — vertical (upward) overscan; lateral lava folded cross-chunk (§8)</h2>
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
 * <p><b>Lateral faces — the lava term is CLOSED.</b> The scatter's {@code x±1}/{@code z±1} reads cross
 * CHUNKS at a section's side faces, and the neighbour may not be built when the scatter runs, so the
 * intra-chunk build scatter and patch re-dilation are each lateral-air-optimistic on their own.
 * {@link EdgeFluidScatter} (PERF-DESIGN-navgrid-build §C1 step 3) closes that in BOTH directions at BOTH
 * sites on the tick thread — wired from {@code ChunkNavLoader} (build) and {@code NavGridUpdater} (patch
 * drain) — so a lava cell just across a chunk face IS flagged. Proven by
 * {@code CrossChunkFluidScatterTest}.
 *
 * <p><b>The vertical SECTION seam — also closed, in BOTH directions.</b> The 6-neighbour dilation crosses a
 * section face both ways (lava in row 0 marks row 15 of the section below; lava in row 15 marks row 0 of
 * the section above), while the descriptor scratch reaches UPWARD only. The build scatter therefore writes
 * through the real neighbour grids rather than the scratch ({@code NavSectionBuilder.scatterLavaRisky}
 * takes both the below and the above grid), and the patch path mirrors it with an explicit below-grid read
 * for its row-0 cells plus an above-seam window pass for {@code ly == 15} edits — see
 * {@code NavSectionBuilder.recomputeWindow}/{@code patchCell}. Without both halves the build and the patch
 * would disagree at every 16th row.
 *
 * <p><b>Still air-optimistic / stale (recorded, deferred):</b>
 * <ul>
 *   <li><b>{@code PLACEABLE_NEIGHBOR} at lateral faces</b> — it has no cross-chunk fold
 *       ({@link EdgeFluidScatter} is fluid-only), so a placeable face just across a chunk boundary is
 *       missed. Pessimistic only, and <i>inert</i> today: no route is affected, because nothing on the
 *       search path reads the bit at all (see its entry in the bitmask table above) — the optimism would
 *       only start to cost routes if a consumer were ever wired up.</li>
 *   <li><b>The downward face</b> — {@code y-1} reads at a section's bottom row ({@code unsupported},
 *       {@code hasPlaceableNeighbor}'s down face) resolve to air. Both err safe: RISKY_EDIT over-sets
 *       (a gravity block is assumed unsupported) and PLACEABLE_NEIGHBOR under-sets. The LAVA term is the
 *       one downward read that is NOT left optimistic — see the vertical-seam note above; it is supplied
 *       out-of-band from the real below grid, because a missed lava neighbour would UNDER-set a safety
 *       gate.</li>
 *   <li><b>Path edits are NOT layered onto these bits</b> (recorded 2026-08-11).
 *       {@code MovementContext.flagsAt} is a raw grid read. Unlike {@code descriptorAt}/{@code descriptorOf}
 *       (which layer the {@code PathEdits} diff) and {@code floorGapAt} (gated by
 *       {@code editsDisjointFromColumn}), this bitmask reflects COMMITTED world state for the whole search.
 *       So a break the plan ITSELF folded does not clear the RISKY_EDIT it invalidates — breaking a gravity
 *       block does not clear the gravity term on the cell below it, which stays "risky" for every later
 *       node even though the block that would have fallen is already gone. Over-conservative only (the bit
 *       gates EDITING, so a stale SET bit forbids a legal edit and never permits an illegal one), but it
 *       costs real routes: observed as a 3-break {@code Descend} detour chosen over continued
 *       {@code MineDown} through a gravel column.</li>
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

    /**
     * The six orthogonal neighbour offsets {@code (dx,dy,dz)} — the shared structuring element for BOTH
     * 6-neighbour facts: "is there a face to place against" (PLACEABLE_NEIGHBOR, gathered in
     * {@link #compute}) and the lava RISKY_EDIT dilation ({@link #risksLavaEdit} gathers it;
     * {@code NavSectionBuilder.scatterLavaRisky} scatters it, reusing this same table so the two can never
     * drift). Package-visible and READ-ONLY — it is iterated on the build hot path, so it must never be
     * copied or allocated per cell.
     */
    static final int[][] SIX = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

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

        // RISKY_EDIT (gravity term only): an edit in the body space could drop a gravity block.
        //   - gravity above (would fall when disturbed), or a gravity block here/in-the-feet we'd undercut.
        // The LAVA term (a lava cell among the six orthogonal neighbours) is NOT gathered here: it is
        // SCATTERED from each lava cell in NavSectionBuilder.computeDepth (PERF-DESIGN-navgrid-build §C1),
        // which crosses the vertical section seam in both directions and so cannot be expressed as a read of
        // this (upward-only) scratch. RISKY_EDIT is OR-idempotent, so the two terms compose; the patch path
        // re-derives the lava term via risksLavaEdit (+ an explicit below-grid read) beside this call.
        if (NavBlock.hasGravity(a2)
                || (NavBlock.hasGravity(ground) && unsupported(desc, x, y, z))
                || (NavBlock.hasGravity(a1) && unsupported(desc, x, y + 1, z))) {
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

    /**
     * The GATHER form of the lava RISKY_EDIT dilation: one of the six orthogonal neighbours of
     * {@code (x,y,z)} is LAVA. Water is deliberately not consulted — see the class doc's lava-term section.
     *
     * <p>The exact counterpart of {@code NavSectionBuilder.scatterLavaRisky} (same {@link #SIX} table, same
     * unconditional {@link NavBlock#isLava} predicate), used by the patch path
     * ({@code NavSectionBuilder.recomputeWindow}, {@code EdgeFluidScatter.rederiveCell}) and as the oracle
     * the identity tests build their reference grids from. NOT on the build hot path: the build scatters
     * instead, so a lava-free cell costs nothing there.
     *
     * <p><b>Reads that leave the scratch resolve to AIR</b> ({@link #at}) — which for this predicate means
     * the {@code y-1} read at row 0 and all four lateral reads at a section's side faces are blind. Both
     * gaps are closed OUT OF BAND by the caller, not here: the below-section row by an explicit grid read in
     * {@code recomputeWindow}, the chunk faces by {@link EdgeFluidScatter}. The {@code y+1} read at row 15
     * needs no help — it lands in the vertical overscan rows.
     */
    static boolean risksLavaEdit(long[] desc, int x, int y, int z) {
        for (int[] o : SIX) {
            if (NavBlock.isLava(at(desc, x + o[0], y + o[1], z + o[2]))) return true;
        }
        return false;
    }

    /** Any of the six neighbours offers a solid (non-fluid) face to place a bridging block against.
     *  NOTE: this is NOT the predicate the placement path uses — {@code MovementContext.placeable} asks
     *  "not vanilla-REPLACEABLE" over its own fan-out and never reads this bit. See the class doc's
     *  {@code PLACEABLE_NEIGHBOR} entry. */
    private static boolean hasPlaceableNeighbor(long[] desc, int x, int y, int z) {
        for (int[] o : SIX) {
            long n = at(desc, x + o[0], y + o[1], z + o[2]);
            if (!NavBlock.isPassable(n) && NavBlock.fluid(n) == 0) return true;
        }
        return false;
    }
}
