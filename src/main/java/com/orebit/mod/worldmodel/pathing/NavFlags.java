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
 * neighbour-derived facts live here.) The one exception is {@code HAS_FLUID_NEIGHBOR}, which is
 * deliberately CELL-CENTRED, not floor-framed — see its entry.
 *
 * <h2>The bitmask (all 6 bits used)</h2>
 * <pre>
 *   bit 0     RISKY_EDIT         breaking/placing at/next to this cell could drop a GRAVITY block onto
 *                                the bot — a BREAK/PLACE gate, NOT a walk gate (walking through is
 *                                fine). STRICTLY the gravity term: gravity above the body space, or an
 *                                unsupported gravity block at/above the feet the edit would undercut,
 *                                computed here per cell ({@link #compute}). The LAVA term that used to
 *                                ride this bit under OR migrated to HAS_FLUID_NEIGHBOR
 *                                (DESIGN-fluid-flow-prediction.md §4.1, owner-ratified 2026-08-17); the
 *                                gain is that each fact now has ONE coherent frame — gravity is
 *                                body-space-framed, the fluid fact cell-centred — where the shared bit
 *                                made the frame mismatch unfixable.
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
 *   bit 4     HAS_FLUID_NEIGHBOR any fluid (water OR lava — {@code NavBlock.isFluid}) among the six
 *                                orthogonal neighbours of THIS cell — the cell-centred "breaking this
 *                                cell may admit fluid" prefilter (DESIGN-fluid-flow-prediction.md §4).
 *                                Clear ⇒ a break here cannot flood (the evaluation funnel's tier-0
 *                                early-out, §5); set ⇒ the funnel's tier-1/2 logic decides. SCATTER-OWNED:
 *                                the build scatter + patch gather in {@link NavSectionBuilder} maintain
 *                                it, and {@link #compute} never writes it — see the section below.
 *                                Every consumer must read it AT THE CELL IT ACTUALLY BREAKS, never at a
 *                                floor/body frame (§4's "do not create a fourth frame" rule). Repurposes
 *                                {@code PLACEABLE_NEIGHBOR}'s slot (retired 2026-08-17: maintained since
 *                                s17 but unread by the search path, and its predicate disagreed in both
 *                                directions with the "not vanilla-REPLACEABLE" fan-out the placement
 *                                path actually runs — §4).
 *   bit 5     SLOW_TRANSIT       a through-slow passable block in the body space (cobweb / berry bush /
 *                                powder snow — NavBlock.transitSlow != 0): moving through costs extra
 *                                regardless of damage caps (physics slows everyone). Like
 *                                CLEARABLE_HAZARD it is a prefilter: the movement layer reads the two
 *                                body descriptors for the exact per-cell magnitude only when it's set.
 * </pre>
 *
 * <h2>HAS_FLUID_NEIGHBOR: ANY fluid, unconditional, 6-directional (DESIGN-fluid-flow-prediction.md §4)</h2>
 * The fact is a pure adjacency DILATION of the fluid set — no flowing test, no cell-below read, no
 * impoundment logic; {@link NavBlock#isFluid}, a single mask test on the already-decoded descriptor:
 * <ul>
 *   <li><b>Water and lava both set it.</b> The bit is a FACT ("fluid touches this cell"), not a refusal:
 *       whether a break actually floods is the evaluation funnel's question (§5 — tier 1 filters by
 *       source/level/geometry, tier 2 runs the slope-distance tie test), and a predicted flood is PRICED,
 *       never forbidden (§4.2 — lava's damage cost already makes it ruinous for a mortal bot and merely
 *       slow for a fireproof one). History: the fact was born 2026-08-10 as RISKY_EDIT's lava-only
 *       keep-away term; the 2026-08-17 migration widened the predicate to any fluid and moved it here,
 *       leaving bit 0 strictly gravity (§4.1).</li>
 *   <li><b>Waterlogged states count</b> — {@code NavBlock.isFluid} is set for them — although a dry
 *       waterloggable partial cannot actually emit lateral flow (§1.2). Errs wet, the conservative
 *       direction: the funnel's tier-1 {@code genuineOpenFluidCell} test is what excludes them (§8.2),
 *       not this prefilter.</li>
 *   <li><b>The geometry is a 1-cell dilation over the 6 orthogonal neighbours</b> — {@code x±1},
 *       {@code y±1}, {@code z±1} (the {@link #SIX} table). Equivalently, as a GATHER:
 *       {@link #hasFluidNeighborGather} — a cell carries the bit iff any of its 6 orthogonal neighbours
 *       is fluid. The fluid cell ITSELF is deliberately not marked by the dilation (centre excluded); its
 *       own hazards are intrinsic to its navtype ({@code NavBlock.isDamaging},
 *       {@code NavBlock.fluid}).</li>
 * </ul>
 * The build SCATTER ({@link NavSectionBuilder#computeDepth}) and the patch GATHER
 * ({@code NavSectionBuilder.recomputeWindow} via {@link #hasFluidNeighborGather}) express exactly this
 * dilation, so a patched grid stays byte-identical to a rebuild — pinned by
 * {@code FluidScatterIdentityTest} / {@code FluidPatchIdentityTest}.
 *
 * <p><b>Why the bit is scatter-owned and {@link #compute} never writes it:</b> the descriptor scratch
 * handed to {@code compute} overscans UPWARD only, so an in-{@code compute} gather would be blind to
 * fluid below a section's row 0 and across every lateral chunk face. The build therefore scatters from
 * each fluid cell in {@code computeDepth} (which crosses the vertical section seam through the real
 * neighbour grids), and the patch path re-derives the term authoritatively beside its {@code compute}
 * call. A {@code compute}-side write would also break {@code computeDepth}'s write-into-above safety
 * argument (pass 2 must be the only flags AUTHOR before the depth sweep's OR-only scatter runs).
 *
 * <h2>Boundary handling — vertical (upward) overscan; lateral fluid folded cross-chunk (§8)</h2>
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
 * <p><b>Lateral faces — the fluid term is CLOSED.</b> The scatter's {@code x±1}/{@code z±1} reads cross
 * CHUNKS at a section's side faces, and the neighbour may not be built when the scatter runs, so the
 * intra-chunk build scatter and patch re-dilation are each lateral-air-optimistic on their own.
 * {@link EdgeFluidScatter} (PERF-DESIGN-navgrid-build §C1 step 3) closes that in BOTH directions at BOTH
 * sites on the tick thread — wired from {@code ChunkNavLoader} (build) and {@code NavGridUpdater} (patch
 * drain) — so a fluid cell just across a chunk face IS folded into the edge cell's bit. Proven by
 * {@code CrossChunkFluidScatterTest}.
 *
 * <p><b>The vertical SECTION seam — also closed, in BOTH directions.</b> The 6-neighbour dilation crosses a
 * section face both ways (fluid in row 0 marks row 15 of the section below; fluid in row 15 marks row 0 of
 * the section above), while the descriptor scratch reaches UPWARD only. The build scatter therefore writes
 * through the real neighbour grids rather than the scratch ({@code NavSectionBuilder.scatterFluidNeighbor}
 * takes both the below and the above grid), and the patch path mirrors it with an explicit below-grid read
 * for its row-0 cells plus an above-seam window pass for {@code ly == 15} edits — see
 * {@code NavSectionBuilder.recomputeWindow}/{@code patchCell}. Without both halves the build and the patch
 * would disagree at every 16th row.
 *
 * <p><b>Still air-optimistic / stale (recorded, deferred):</b>
 * <ul>
 *   <li><b>The downward face</b> — the {@code y-1} read at a section's bottom row ({@code unsupported})
 *       resolves to air, so RISKY_EDIT over-sets there (a gravity block is assumed unsupported) — errs
 *       safe. The FLUID term is the one downward read that is NOT left optimistic — see the vertical-seam
 *       note above; it is supplied out-of-band from the real below grid, because an optimistic read would
 *       make patch and rebuild disagree at every 16th row and blind the funnel's tier-0 early-out to
 *       fluid directly below the break.</li>
 *   <li><b>Path edits are NOT layered onto these bits</b> (recorded 2026-08-11; reaffirmed for the fluid
 *       bit by DESIGN-fluid-flow-prediction.md §8's table).
 *       {@code MovementContext.flagsAt} is a raw grid read. Unlike {@code descriptorAt}/{@code descriptorOf}
 *       (which layer the {@code PathEdits} diff) and {@code floorGapAt} (gated by
 *       {@code editsDisjointFromColumn}), this bitmask reflects COMMITTED world state for the whole search.
 *       Two recorded consequences: a break the plan ITSELF folded does not clear the RISKY_EDIT it
 *       invalidates (breaking a gravity block leaves the cell below "risky" for every later node —
 *       over-conservative only, but it costs real routes: observed as a 3-break {@code Descend} detour
 *       chosen over continued {@code MineDown} through a gravel column); and a folded
 *       {@code BROKEN_WATER}/{@code BROKEN_LAVA} neighbour never SETS {@code HAS_FLUID_NEIGHBOR}, so the
 *       funnel's tier-0 early-out is blind to plan-created fluid — errs dry, recoverable, KNOWN AND
 *       DEFERRED to the PathEdits-scatter workflow (§8 table row; do not fix ad-hoc here).</li>
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
    public static final int HAS_FLUID_NEIGHBOR = 1 << 4;
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
     * The six orthogonal neighbour offsets {@code (dx,dy,dz)} — the structuring element of the
     * HAS_FLUID_NEIGHBOR dilation ({@link #hasFluidNeighborGather} gathers it;
     * {@code NavSectionBuilder.scatterFluidNeighbor} scatters it, reusing this same table so the two can
     * never drift). Package-visible and READ-ONLY — it is iterated on the build hot path, so it must never
     * be copied or allocated per cell.
     */
    static final int[][] SIX = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * Compute the neighbour-property bitmask for one cell. {@code desc} is a section's packed descriptors
     * in canonical {@code (y<<8)|(z<<4)|x} order — either the bare 4096 cells (reads above the section
     * resolve to air) or a {@link #SCRATCH_SIZE}-length scratch whose rows {@code y = 16..18} hold the
     * section above's descriptors (vertical overscan; the index formula extends naturally). Reads outside
     * the scratch — lateral, below, or above the overscan — resolve to air (see boundary handling above).
     *
     * <p>Writes every bit EXCEPT {@link #HAS_FLUID_NEIGHBOR}, which is scatter-owned (see the class doc) —
     * the caller that stores this result beside live data is responsible for OR-ing the fluid term back in
     * ({@code NavSectionBuilder.recomputeWindow}, {@code EdgeFluidScatter.rederiveCell}) or scattering it
     * afterwards ({@code NavSectionBuilder.computeDepth}).
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

        // RISKY_EDIT (strictly gravity — DESIGN-fluid-flow-prediction.md §4.1): an edit in the body space
        // could drop a gravity block.
        //   - gravity above (would fall when disturbed), or a gravity block here/in-the-feet we'd undercut.
        // HAS_FLUID_NEIGHBOR (bit 4) is deliberately NOT written here: it is SCATTERED from each fluid cell
        // in NavSectionBuilder.computeDepth (PERF-DESIGN-navgrid-build §C1), which crosses the vertical
        // section seam in both directions and so cannot be expressed as a read of this (upward-only)
        // scratch. The patch path re-derives it via hasFluidNeighborGather (+ an explicit below-grid read)
        // beside this call.
        if (NavBlock.hasGravity(a2)
                || (NavBlock.hasGravity(ground) && unsupported(desc, x, y, z))
                || (NavBlock.hasGravity(a1) && unsupported(desc, x, y + 1, z))) {
            flags |= RISKY_EDIT;
        }

        return flags;
    }

    // ---- Field extraction (for consumers reading a stored flag value) ------------------------
    /** The 2-bit headroom level (one of {@link #HEADROOM_NONE}..{@link #HEADROOM_JUMP}). */
    public static int headroom(int flags)            { return (flags & HEADROOM_MASK) >>> HEADROOM_SHIFT; }
    public static boolean risksEdit(int flags)       { return (flags & RISKY_EDIT) != 0; }
    public static boolean clearableHazard(int flags) { return (flags & CLEARABLE_HAZARD) != 0; }
    public static boolean slowTransit(int flags)     { return (flags & SLOW_TRANSIT) != 0; }
    /** Any fluid among the six orthogonal neighbours of this cell — the funnel's tier-0 prefilter
     *  (DESIGN-fluid-flow-prediction.md §5). Cell-centred: read it AT the cell being broken. */
    public static boolean hasFluidNeighbor(int flags) { return (flags & HAS_FLUID_NEIGHBOR) != 0; }

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
     * The GATHER form of the HAS_FLUID_NEIGHBOR dilation: one of the six orthogonal neighbours of
     * {@code (x,y,z)} holds ANY fluid — {@link NavBlock#isFluid}, water and lava alike
     * (DESIGN-fluid-flow-prediction.md §4; see the class doc's HAS_FLUID_NEIGHBOR section).
     *
     * <p>The exact counterpart of {@code NavSectionBuilder.scatterFluidNeighbor} (same {@link #SIX} table,
     * same unconditional {@link NavBlock#isFluid} predicate), used by the patch path
     * ({@code NavSectionBuilder.recomputeWindow}, {@code EdgeFluidScatter.rederiveCell}) and as the oracle
     * the identity tests build their reference grids from. NOT on the build hot path: the build scatters
     * instead, so a fluid-free cell costs nothing there.
     *
     * <p><b>Reads that leave the scratch resolve to AIR</b> ({@link #at}) — which for this predicate means
     * the {@code y-1} read at row 0 and all four lateral reads at a section's side faces are blind. Both
     * gaps are closed OUT OF BAND by the caller, not here: the below-section row by an explicit grid read in
     * {@code recomputeWindow}, the chunk faces by {@link EdgeFluidScatter}. The {@code y+1} read at row 15
     * needs no help — it lands in the vertical overscan rows.
     */
    static boolean hasFluidNeighborGather(long[] desc, int x, int y, int z) {
        for (int[] o : SIX) {
            if (NavBlock.isFluid(at(desc, x + o[0], y + o[1], z + o[2]))) return true;
        }
        return false;
    }
}
