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
 * neighbour-derived facts live here.) TWO bits are exceptions — {@code HAS_FLUID_NEIGHBOR} and
 * {@code RISKS_GRAVITY} — and both are deliberately CELL-CENTRED, not floor-framed: they answer "what
 * happens if I EDIT this cell", a question about the cell itself, and the frame of the mover is
 * irrelevant to it. {@code RISKS_GRAVITY} is additionally SPLIT between two owners — {@link #compute}
 * writes the half that is a pure upward column read, the build/patch scatter owns the half that reads
 * laterally and two rows downward — see the section below.
 *
 * <h2>The bitmask (all 6 bits used)</h2>
 * <pre>
 *   bit 0     RISKS_GRAVITY      breaking or placing at THIS CELL will drop a GRAVITY block — a
 *                                BREAK/PLACE gate, NOT a walk gate (walking through is fine).
 *                                Owner-ratified 2026-08-21, two rules:
 *                                  (a) this cell is DIRECTLY BELOW a gravity block that is currently
 *                                      SUPPORTED — this cell IS that block's support, so breaking it
 *                                      drops the block (the UNDERMINING hazard: the shape that killed
 *                                      the 2026-08-21 flagship at (968,56,905));
 *                                  (b) this cell is ORTHOGONALLY ADJACENT to a gravity block that is
 *                                      currently UNSUPPORTED — a suspended column vanilla only drops
 *                                      when a neighbouring block update pokes it, and editing this cell
 *                                      IS that update (the classic CAVE-IN; worldgen makes suspended
 *                                      gravel ceilings constantly).
 *                                READ IT AT THE CELL BEING EDITED, never at a floor/body frame; the ONE
 *                                gate site is {@code EditScratch}'s break/place fold. SPLIT OWNERSHIP:
 *                                {@link #compute} writes HALF A ({@code hasGravity(above)}), the
 *                                {@code NavSectionBuilder} scatter/gather pair writes HALF B — see the
 *                                section below. Formerly RISKY_EDIT (floor-framed); the LAVA term that
 *                                once rode this bit under OR migrated to HAS_FLUID_NEIGHBOR
 *                                (DESIGN-fluid-flow-prediction.md §4.1, owner-ratified 2026-08-17).
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
 * <h2>RISKS_GRAVITY: two halves, two owners (owner ruling 2026-08-21)</h2>
 * The predicate, written out over the six orthogonal offsets {@link #SIX}:
 * <pre>
 *   P(C) = hasGravity(above(C))                                  … HALF A
 *       OR ∃N ∈ SIX(C): hasGravity(N) &amp;&amp; isPassable(below(N))    … HALF B
 * </pre>
 * <ul>
 *   <li><b>Half A is the whole of rule (a) AND the below-neighbour case of rule (b).</b> For any gravity
 *       block {@code G}, the cell directly under it takes the bit under (a) when {@code G} is supported and
 *       under (b) when it is not — and those two cases are exact COMPLEMENTS, because
 *       {@code unsupported(G) ≡ isPassable(below(G)) ≡ isPassable(C)}. So their union collapses to the bare
 *       unconditional test {@code hasGravity(above(C))}: a single upward column read, exactly the {@code a1}
 *       slot {@link #compute} already has and the vertical overscan already serves. Half A therefore needs
 *       NO scatter, no seam work and no chunk-face work.</li>
 *   <li><b>Half B — the remaining five offsets — is genuinely lateral and downward-reading</b>
 *       ({@code N} at {@code y±1}/{@code x±1}/{@code z±1}, and {@code N}'s own support one row below that),
 *       so it cannot be expressed as a read of the upward-only scratch. It is SCATTER-OWNED, mirroring
 *       {@code HAS_FLUID_NEIGHBOR} exactly: {@code NavSectionBuilder.scatterGravityNeighbor} scatters it out
 *       of every UNSUPPORTED gravity cell during the build's depth sweep (crossing the vertical section seam
 *       in both directions through the real neighbour grids), {@link #risksGravityNeighborGather} gathers it
 *       on the patch path, and {@link EdgeScatter} closes the lateral chunk faces. Build-scatter ≡
 *       patch-gather is a hard requirement, pinned by the scatter/patch identity tests.</li>
 *   <li><b>The centre is deliberately excluded from half B</b> — breaking a floating gravel block drops
 *       nothing onto anyone; and if a stack rests on it, half A marks the cell under it. The {@code -y}
 *       offset IS kept in the scatter's table even though half A already marks that cell: scatter and gather
 *       must express the IDENTICAL set, the bit is OR-composed, so the redundancy is free while an
 *       asymmetric table is a drift hazard.</li>
 * </ul>
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
 * {@code ScatterIdentityTest} / {@code ScatterPatchIdentityTest}.
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
 * {@link EdgeScatter} (PERF-DESIGN-navgrid-build §C1 step 3) closes that in BOTH directions at BOTH
 * sites on the tick thread — wired from {@code ChunkNavLoader} (build) and {@code NavGridUpdater} (patch
 * drain) — so a fluid cell just across a chunk face IS folded into the edge cell's bit. The SAME face walk
 * carries {@code RISKS_GRAVITY}'s half B (one class, one walk, two bits — so the two dilations cannot drift
 * apart). Proven by {@code CrossChunkScatterTest}.
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
 *   <li><b>The downward face is now CLOSED for both scatter-owned terms.</b> The old floor-framed
 *       {@code unsupported()} helper read {@code y-1} through the upward-only scratch, so it resolved to air
 *       at a section's bottom row and over-set the bit there; that helper is DELETED. Half B's support read
 *       is served by the real below grid on both sides — the build scatter reads the below section's row 15
 *       directly ({@code NavSectionBuilder.unsupportedInColumn}) and the patch gather takes an explicit
 *       {@code belowGrid} for its two-deep reads ({@link #risksGravityNeighborGather}) — exactly as the
 *       FLUID term's downward read already was. The remaining optimism for both terms is the LATERAL chunk
 *       face, and only until {@link EdgeScatter} runs (build hook + patch drain hook, same tick).</li>
 *   <li><b>Path edits are NOT layered onto these bits</b> (recorded 2026-08-11; reaffirmed for the fluid
 *       bit by DESIGN-fluid-flow-prediction.md §8's table).
 *       {@code MovementContext.flagsAt} is a raw grid read. Unlike {@code descriptorAt}/{@code descriptorOf}
 *       (which layer the {@code PathEdits} diff) and {@code floorGapAt} (gated by
 *       {@code editsDisjointFromColumn}), this bitmask reflects COMMITTED world state for the whole search.
 *       Two recorded consequences. (1) A break the plan ITSELF folded does not clear the
 *       {@code RISKS_GRAVITY} it invalidates, so <b>a shaft cannot be planned more than ONE level into a
 *       gravity column</b>: at level 2 of a {@code MineDown} the cell being broken still reads "gravity
 *       directly above" even though the plan broke that gravity at level 1, and the fold-sited gate refuses.
 *       Over-conservative only, and ACCEPTED as ratified (2026-08-21) — it supersedes the older recorded
 *       shape ("a 3-break {@code Descend} detour chosen over continued {@code MineDown}"). There is a clean
 *       fix that needs an owner ruling rather than an ad-hoc patch: half A is exactly
 *       {@code hasGravity(descriptorAt(x, y+1, z))} and {@code descriptorAt} DOES layer the diff, so half A
 *       could be evaluated live in the gate and the stored bit reduced to half B alone — a pure mirror of
 *       {@code HAS_FLUID_NEIGHBOR}. That changes the bit's ratified meaning and what {@code /bot probe}
 *       reports, so it is deliberately NOT done here. (2) A folded
 *       {@code BROKEN_WATER}/{@code BROKEN_LAVA} neighbour never SETS {@code HAS_FLUID_NEIGHBOR}, so the
 *       funnel's tier-0 early-out is blind to plan-created fluid — errs dry, recoverable, KNOWN AND
 *       DEFERRED to the PathEdits-scatter workflow (§8 table row; do not fix ad-hoc here).</li>
 *   <li><b>{@code classifyInto}-built grids carry half A but never half B</b> — a single-section producer
 *       (the headless test/bench path) skips {@code computeDepth}, hence the scatter. The same
 *       "correctness by fallback" posture {@code HAS_FLUID_NEIGHBOR} already has; live
 *       {@code ChunkNavBuilder} columns always run pass 3, so it never affects the game.</li>
 * </ul>
 * The movement layer still treats {@code HEADROOM} as a prefilter (re-verifying via {@code descriptorAt}
 * near faces), so any residual optimism is caught in the fine layer, not trusted blindly.
 */
public final class NavFlags {

    private NavFlags() {}

    // ---- Bit layout within the 6-bit field ---------------------------------------------------
    public static final int RISKS_GRAVITY      = 1 << 0;
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
     * The six orthogonal neighbour offsets {@code (dx,dy,dz)} — the shared structuring element of BOTH
     * scatter-owned dilations: HAS_FLUID_NEIGHBOR ({@link #hasFluidNeighborGather} gathers it,
     * {@code NavSectionBuilder.scatterFluidNeighbor} scatters it) and RISKS_GRAVITY's half B
     * ({@link #risksGravityNeighborGather} / {@code NavSectionBuilder.scatterGravityNeighbor}). All four
     * reuse this one table so no pair can drift. Package-visible and READ-ONLY — it is iterated on the
     * build hot path, so it must never be copied or allocated per cell.
     */
    static final int[][] SIX = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * Compute the neighbour-property bitmask for one cell. {@code desc} is a section's packed descriptors
     * in canonical {@code (y<<8)|(z<<4)|x} order — either the bare 4096 cells (reads above the section
     * resolve to air) or a {@link #SCRATCH_SIZE}-length scratch whose rows {@code y = 16..18} hold the
     * section above's descriptors (vertical overscan; the index formula extends naturally). Reads outside
     * the scratch — lateral, below, or above the overscan — resolve to air (see boundary handling above).
     *
     * <p>Writes every bit EXCEPT {@link #HAS_FLUID_NEIGHBOR} (wholly scatter-owned) and
     * {@link #RISKS_GRAVITY}'s HALF B (the lateral/two-deep half; half A IS written here) — see the class
     * doc. The caller that stores this result beside live data is responsible for OR-ing both scattered
     * terms back in ({@code NavSectionBuilder.recomputeWindow}, {@code EdgeScatter.rederiveCell}) or
     * scattering them afterwards ({@code NavSectionBuilder.computeDepth}).
     */
    public static int compute(long[] desc, int x, int y, int z) {
        long a1 = at(desc, x, y + 1, z);
        long a2 = at(desc, x, y + 2, z);
        long a3 = at(desc, x, y + 3, z);

        int flags = 0;

        // HEADROOM: how many body cells above the floor are clear for a WALKER (passable AND fluid-free AND
        // not a teleport portal, so the value lines up with MovementContext.passable — water is not
        // walk-clearance, and a portal cell is a no-go the walker routes around). Breaking a block in the way
        // is the break modifier's job (whose gate is RISKS_GRAVITY, read at the cell it actually breaks);
        // headroom is the raw clearance prefilter.
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

        // RISKS_GRAVITY (bit 0) — HALF A of the cell-centred predicate: "a gravity block rests DIRECTLY ON
        // me, so an edit here drops it." Frame-agnostic: a1 is the cell above THIS cell under any
        // convention. It subsumes both ratified rules for the below-neighbour: a SUPPORTED gravity block's
        // support is this cell (rule a); an UNSUPPORTED one has this cell among its six orthogonal
        // neighbours (rule b). The two cases are exact complements (unsupported(G) == isPassable(ground)),
        // so the union is the bare test.
        // HALF B — "this cell is orthogonally adjacent to an UNSUPPORTED gravity block", the classic
        // cave-in — is SCATTER-OWNED (NavSectionBuilder.computeDepth / scatterGravityNeighbor, gathered
        // here by risksGravityNeighborGather), for the same reason HAS_FLUID_NEIGHBOR (bit 4) is: it reads
        // laterally and TWO rows downward, and this scratch overscans UPWARD only. The patch path
        // re-derives both scattered terms beside this call.
        // The DELETED terms and why: hasGravity(a2) was pure floor-framing (the cell BETWEEN now carries the
        // bit itself), and the ground-unsupported term said "I am a floating gravity block" — breaking that
        // drops nothing onto anyone, and if a stack sits on it, half A marks it.
        if (NavBlock.hasGravity(a1)) flags |= RISKS_GRAVITY;

        return flags;
    }

    // ---- Field extraction (for consumers reading a stored flag value) ------------------------
    /** The 2-bit headroom level (one of {@link #HEADROOM_NONE}..{@link #HEADROOM_JUMP}). */
    public static int headroom(int flags)            { return (flags & HEADROOM_MASK) >>> HEADROOM_SHIFT; }
    /** Whether EDITING this cell — breaking OR placing — drops a gravity block (the two ratified rules; see
     *  the class doc). Cell-centred: read it AT the cell being edited, never at a floor/body frame. */
    public static boolean risksGravity(int flags)    { return (flags & RISKS_GRAVITY) != 0; }
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

    /**
     * The GATHER form of {@link #RISKS_GRAVITY}'s HALF B: one of the six orthogonal neighbours of
     * {@code (x,y,z)} is a gravity block that is currently UNSUPPORTED (nothing solid under it), so any
     * block update at this cell — a break OR a place — is what vanilla needs to drop it. The exact
     * counterpart of {@code NavSectionBuilder.scatterGravityNeighbor} (same {@link #SIX} table, same
     * {@code hasGravity && isPassable(below)} predicate) so the two can never drift. Half A
     * ({@code hasGravity(above)}) is NOT gathered here — {@link #compute} writes it authoritatively.
     *
     * <p><b>It reads TWO rows below its cell</b> ({@code N = below(C)} at {@code y-1}, and {@code N}'s own
     * support at {@code y-2}), one deeper than {@link #hasFluidNeighborGather}. The upward-only scratch
     * cannot serve either row at a section's bottom face, so {@code belowGrid} is threaded in explicitly and
     * consulted for every {@code ny < 0} read (rows {@code -1}/{@code -2} → the below section's rows
     * 15/14). A null {@code belowGrid} ⇒ AIR, which matches the build scatter's own air-optimism at the
     * world floor / a column hole, so build and patch agree. The four LATERAL reads at a section side face
     * still resolve to AIR here and are closed out of band by {@link EdgeScatter}, exactly as the fluid
     * gather's are.
     *
     * <p>Allocation-free (iterating the shared {@code int[][]} table allocates nothing). NOT on the build
     * hot path: the build scatters instead, so a gravity-free cell costs nothing there.
     */
    static boolean risksGravityNeighborGather(long[] desc, TraversalGrid belowGrid, int x, int y, int z) {
        for (int[] o : SIX) {
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            long n = descOrBelow(desc, belowGrid, nx, ny, nz);
            if (NavBlock.hasGravity(n)
                    && NavBlock.isPassable(descOrBelow(desc, belowGrid, nx, ny - 1, nz))) {
                return true;
            }
        }
        return false;
    }

    /** {@link #at} with an explicit fall-through to the section BELOW for {@code y < 0} (rows -1/-2 →
     *  15/14) — the two reads the upward-only scratch cannot serve. Air when {@code belowGrid} is null or
     *  the read leaves the 16×16 lateral footprint (the chunk-face blindness {@link EdgeScatter} closes). */
    private static long descOrBelow(long[] desc, TraversalGrid belowGrid, int x, int y, int z) {
        if (y >= 0) return at(desc, x, y, z);
        if (belowGrid == null || y < -NavSection.SIZE || x < 0 || x > 15 || z < 0 || z > 15) return AIR_DESC;
        return NavBlock.descriptor((short) belowGrid.navtype(x, NavSection.SIZE + y, z));
    }

    /**
     * The GATHER form of the HAS_FLUID_NEIGHBOR dilation: one of the six orthogonal neighbours of
     * {@code (x,y,z)} holds ANY fluid — {@link NavBlock#isFluid}, water and lava alike
     * (DESIGN-fluid-flow-prediction.md §4; see the class doc's HAS_FLUID_NEIGHBOR section).
     *
     * <p>The exact counterpart of {@code NavSectionBuilder.scatterFluidNeighbor} (same {@link #SIX} table,
     * same unconditional {@link NavBlock#isFluid} predicate), used by the patch path
     * ({@code NavSectionBuilder.recomputeWindow}, {@code EdgeScatter.rederiveCell}) and as the oracle
     * the identity tests build their reference grids from. NOT on the build hot path: the build scatters
     * instead, so a fluid-free cell costs nothing there.
     *
     * <p><b>Reads that leave the scratch resolve to AIR</b> ({@link #at}) — which for this predicate means
     * the {@code y-1} read at row 0 and all four lateral reads at a section's side faces are blind. Both
     * gaps are closed OUT OF BAND by the caller, not here: the below-section row by an explicit grid read in
     * {@code recomputeWindow}, the chunk faces by {@link EdgeScatter}. The {@code y+1} read at row 15
     * needs no help — it lands in the vertical overscan rows.
     */
    static boolean hasFluidNeighborGather(long[] desc, int x, int y, int z) {
        for (int[] o : SIX) {
            if (NavBlock.isFluid(at(desc, x + o[0], y + o[1], z + o[2]))) return true;
        }
        return false;
    }
}
