package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * DiagonalParkour — a running gap jump along a DIAGONAL, to a same-level landing across {@code g} open
 * diagonal columns. Mirrors the {@link Traverse}/{@link Diagonal} split: {@link Parkour} owns the four
 * cardinals, this move owns the four diagonals, and neither edits terrain (edit-free by rule, the
 * {@link Fall} precedent — you cannot mine or place mid-jump). <b>v1 is FLAT only</b>; rising/falling
 * diagonal landings are deferred (the cardinal move covers most real terrain, and the diagonal corner
 * geometry composed with a height change needs its own in-game verification first).
 *
 * <h2>Envelope (DERIVED — {@link ParkourEnvelope#DIAG})</h2>
 * Diagonal gap {@code g} = open diagonal cells between takeoff and landing; the landing sits {@code g+1}
 * diagonal steps out, so the jump displacement is {@code (g+1)·√2} and the air span itself {@code g·√2}
 * blocks. The cap is no longer a constant — it is the {@code diag} column of the per-takeoff-condition
 * {@link ParkourEnvelope#MAX_GAP} row (keyed on surface height, soul-sand floor, berry body — same as the
 * cardinal {@link Parkour}). The BASE cap (full block, normal floor, no slow body) is <b>2</b>: the
 * owner-verified 2-gap ({@code 2·√2 ≈ 2.83}-block air span) is inside the flat sprint-jump reach, but the
 * 3-gap ({@code 4.24}-block displacement √20 &gt; the flat reach) is beyond the physics and is NO LONGER
 * offered — the old hardcoded {@code MAX_GAP = 3} over-offered a jump the bot attempted and fell (the
 * corner-cut off a 90° turn). Sprint for gaps 2+.
 *
 * <h2>Clearance geometry — the transit prism + the corner pairs</h2>
 * Like cardinal {@link Parkour}, every gap cell needs its node-level cell open (the SHAPE_OTHER fence
 * exclusion) and the body prism {@code y+1..y+3} passable, and the first standable node-level cell ends
 * the scan (never overfly a ledge; at {@code c == 1} that ledge is plain {@link Diagonal}'s job). ON TOP
 * of that, a diagonal transit clips <b>both corner columns of every cell-to-cell transition</b> — the
 * {@link Diagonal} corner rule extended along the whole jump: for the transition from diagonal cell
 * {@code t} to {@code t+1} the two columns {@code (x+dx·(t+1), z+dz·t)} and {@code (x+dx·t, z+dz·(t+1))}
 * are swept by the 0.6-wide hitbox. Because the sweep happens mid-arc (feet up to {@code y+1.25}, head to
 * {@code y+3.05}), each corner column must be clear over the full jump body {@code y+1..y+3} — one row
 * MORE than walking {@link Diagonal}'s {@code y+1..y+2} (a deliberate conservative widening). The corner
 * NODE-level cell {@code y} is accepted when it is passable <b>or</b> its collision top is at most a full
 * block ({@code topY ≤ 16}): a solid floor corner is arced over exactly like flat ground (and rejecting
 * it would kill the common "cut the corner of a walkable ledge" jump), while a fence/wall there
 * ({@code topY 24}) pokes into the feet path and rejects. Landing body is {@code y+1..y+2} (flags
 * fast-path, v1 pattern).
 *
 * <h2>Scan-cost discipline — transit-LAZY, landing-first (cardinal {@link Parkour}'s inversion)</h2>
 * Runs on every standing node expansion, so the forward walk reads ONLY the node-level cell per diagonal
 * column (1 read — on open ground column 1 is standable and the direction ends right there, matching
 * {@link Diagonal}'s terrain at 1 extra read per diagonal; the same A/B bench that caught the cardinal
 * eager prism at +17–27% on air-heavy scenarios motivates deferring the diagonal's even dearer 3-read
 * prism + 8-read corner pair). The transit prisms and swept corner pairs are verified and priced
 * <b>backwards over the arc</b> only when the terminal landing is actually found — and since a diagonal
 * landing always ENDS the direction (flat-only, never-overfly), there is at most ONE landing per
 * direction, so no cell is ever read twice and no prefix cursor is needed. Candidate-set equivalence
 * with the eager scan (byte-identical — same cells verified, same prices, only deferred): a blocked
 * prism/corner no longer ends the forward walk early, but the walk is bounded by {@code maxGap+1}
 * node-level reads and the backwards verification rejects the landing whose arc crosses the blocked
 * cell — exactly the candidates the eager termination produced; UNBUILT stays exactly as strict for
 * every cell actually consulted (an unbuilt node-level cell ends the walk; an unbuilt prism/corner cell
 * consulted during verification rejects the landing). Every descriptor is read once and reused for both
 * the passability gate and the hazard/through-slow pricing, in the eager column-ascending order (prism
 * then corner pair per transition), so surcharge sums are unchanged.
 *
 * <h2>Cost model (derivation — distance-equivalent interpolation of the cardinal table)</h2>
 * Air time scales with horizontal distance, so {@link #AIR_COST} interpolates {@link Parkour}'s flat
 * table (8/11/14/16 ticks at displacements 2/3/4/5) at the diagonal displacements {@code (g+1)·√2}:
 * {@code g=1} → 2.83 → ≈10.5; {@code g=2} → 4.24 → ≈14.5; {@code g=3} → 5.66 → ≈19 (extrapolated past
 * the table at the same ~3.6 t/block sprint ruler; offered unconditionally — the whole gap range is part
 * of the ONE parkour envelope since the AGGRESSIVE flag was deleted). Runup is
 * one DIAGONAL walk step ({@link Diagonal#COST} ≈ 6.55) and {@link Parkour#COMMIT_PENALTY} is shared →
 * totals ≈ 20.1 / 24.1 / 28.6. Per-edge cost is well above the octile heuristic between the endpoints
 * ({@code 4.633·√2·(g+1)}: 13.1 / 19.7 / 26.2) — admissible with margin. Transit pricing: gap prisms and
 * corner body cells are priced per cell off the read-once descriptors (a corner brush is charged the full
 * per-cell rate — {@link Diagonal}'s conservative rule); corner node-level cells are geometry-only,
 * unpriced (the cardinal move's node-level proxy rule).
 *
 * <h2>Execution — the takeoff-edge trigger under a diagonal unit vector (the chosen math)</h2>
 * Reuses cardinal {@link Parkour}'s RUNUP → TAKEOFF → AIRBORNE → LAND phase shape, including the
 * airborne-ARMED {@code resetWhen} balk guard (see Parkour's plan Javadoc — the arming derivation is
 * identical). The one diagonal-specific piece is the takeoff trigger: with {@code ux,uz = ±1} BOTH
 * nonzero, the raw progress projection {@code ux·(x−cx) + uz·(z−cz)} of a point {@code t} blocks along
 * the diagonal line equals {@code t·√2} — it over-reads along-line distance by √2. Rather than pay a
 * per-tick normalization, the threshold itself is expressed in raw units: {@link #TAKEOFF_EDGE_RAW}
 * {@code = TAKEOFF_EDGE_ALONG · √2}. The along-line budget also differs from the cardinal 0.35 (whose
 * convention is "centre ~0.15 short of the cell boundary at +0.5"): the diagonal boundary is the cell
 * CORNER at {@code 0.5·√2 ≈ 0.71} along-line, so the same margin would allow ~0.55 — but near the corner
 * the 0.6-wide box's overlap with the takeoff block shrinks to a sliver (a corner is a point of support,
 * not an edge), so {@link #TAKEOFF_EDGE_ALONG} = 0.40 keeps a doubled ~0.3 margin instead. Jump as late
 * as is safely supported; tune in-game like the cardinal knob.
 */
public final class DiagonalParkour implements Movement {

    /**
     * Ticks in the air by diagonal gap ({@code [g]}, index 0 unused) — the cardinal flat table
     * interpolated at displacement {@code (g+1)·√2} (derivation in the class Javadoc).
     */
    private static final float[] AIR_COST = {0f, 10.5f, 14.5f, 19f};

    /** Ticks for the approach step onto the takeoff corner — one diagonal walk step. */
    public static final float RUNUP_COST = Diagonal.COST;

    /**
     * How far past the takeoff cell's centre, in blocks ALONG the diagonal line, the bot runs before
     * jumping (see the trigger-math derivation in the class Javadoc). Tune 0.35–0.42 in-game.
     */
    public static final double TAKEOFF_EDGE_ALONG = 0.40;

    /** The same threshold in RAW {@code ux·Δx + uz·Δz} projection units ({@code = ALONG · √2}). */
    public static final double TAKEOFF_EDGE_RAW = TAKEOFF_EDGE_ALONG * 1.41421356;

    /** Collision-top bound (sixteenths) a corner NODE-level cell may have: a full block (16) is arced
     *  over; anything taller (fence/wall ≈ 24) clips the feet path. */
    private static final int CORNER_MAX_TOP_Y = 16;

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a running jump — only while upright
        if (ctx.caps().jumpHeight() < 1) return;
        if (ctx.reducesJump(x, y, z)) return; // honey-block floor: the reduced jump apex clears no gap
        if (ctx.noJumpFromBody(x, y, z)) return; // cobweb body cell: the stuck multiplier kills take-off velocity

        // Takeoff head-clearance (source y+3) — direction-independent, proven once (cardinal Parkour's
        // exact check; no break folding).
        int srcFlags = ctx.flagsAt(x, y, z);
        if (!ctx.headroomProves(srcFlags, y, MovementContext.HEADROOM_JUMP)) {
            int p3 = ctx.packedAt(x, y + 3, z);
            if (p3 == MovementContext.UNBUILT
                    || !ctx.passable(ctx.descriptorOf(x, y + 3, z, p3))) {
                return;
            }
        }

        // The diagonal gap cap is DERIVED per takeoff condition (ParkourEnvelope#DIAG), exactly like the
        // cardinal move's rows: soul-sand floor and berry body tighten it, and a lower takeoff surface
        // (slab / stair edge) folds into the effective Δy. The buckets are direction-independent (hoist);
        // the surface height is directional only for a stair (its high half faces one edge).
        final long floorDesc = ctx.descriptorAt(x, y, z);
        final int gsfBucket = ctx.isSlow(floorDesc) ? 1 : 0;
        final int occBucket = ctx.bodyTransitLight(x, y, z) ? 1 : 0;
        final boolean stair = ctx.isStair(floorDesc);
        final int uniformDiag = stair ? -1
                : ParkourEnvelope.MAX_GAP[ParkourEnvelope.index(ctx.floorSurface(x, y, z))]
                        [gsfBucket][occBucket][ParkourEnvelope.DIAG];

        for (int[] d : DIAGONALS) {
            int maxGap = stair
                    ? ParkourEnvelope.MAX_GAP[ParkourEnvelope.index(
                            ctx.directionalTopY(floorDesc, d[0], d[1]))][gsfBucket][occBucket]
                            [ParkourEnvelope.DIAG]
                    : uniformDiag;
            if (maxGap < 1) continue; // this takeoff condition offers no diagonal jump
            scanDirection(ctx, x, y, z, d[0], d[1], out, maxGap);
        }
    }

    /**
     * One diagonal direction, transit-LAZY (class Javadoc): walk diagonal cells {@code c = 1..maxGap+1}
     * reading only the node-level cell; a standable node-level cell is the landing (or, at
     * {@code c == 1}, plain {@link Diagonal}'s ledge) and ends the scan. Only when the landing is found
     * are its gap prisms and swept corner pairs verified backwards (transitions 1..g in column order,
     * then the final transition into the landing — the eager read/price order, so surcharges are
     * unchanged). Primitives only, zero allocation; a negative return from a corner helper is its
     * blocked sentinel (no boxing).
     */
    private static void scanDirection(MovementContext ctx, int x, int y, int z, int dx, int dz,
            CandidateSink out, int maxGap) {
        for (int c = 1; c <= maxGap + 1; c++) {
            int cx = x + dx * c;
            int cz = z + dz * c;
            int g = c - 1;

            int p = ctx.packedAt(cx, y, cz);
            if (p == MovementContext.UNBUILT) return; // unknown cell — don't jump into/over it
            long fd = ctx.descriptorOf(cx, y, cz, p);

            if (ctx.standable(fd)) {
                // A landing (c >= 2) or an ordinary Diagonal ledge (c == 1): never overfly it.
                if (g >= 1 && g <= maxGap) {
                    int flags = MovementContext.flagsOf(p);
                    boolean clear = ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK);
                    if (!clear) {
                        int p1 = ctx.packedAt(cx, y + 1, cz);
                        int p2 = ctx.packedAt(cx, y + 2, cz);
                        clear = p1 != MovementContext.UNBUILT && p2 != MovementContext.UNBUILT
                                && ctx.passable(ctx.descriptorOf(cx, y + 1, cz, p1))
                                && ctx.passable(ctx.descriptorOf(cx, y + 2, cz, p2));
                    }
                    if (clear) {
                        // Backwards arc verification (the lazy inversion): the gap prisms + the corner
                        // pair of each crossed transition, in column order — deferred from the forward
                        // walk to here, where a landing actually exists. NaN = a blocked/unbuilt cell
                        // (UNBUILT is as strict as the eager walk for every consulted cell).
                        float transit = verifyArc(ctx, x, y, z, dx, dz, g);
                        if (!Float.isNaN(transit)) {
                            // The corner pair of the FINAL transition (into the landing) — checked last,
                            // it's the most expensive gate (8 reads).
                            float corner = cornerPairCost(ctx, x + dx * c, z + dz * (c - 1),
                                    x + dx * (c - 1), z + dz * c, y);
                            if (corner >= 0f) {
                                out.accept(cx, y, cz, RUNUP_COST + AIR_COST[g] + Parkour.COMMIT_PENALTY
                                        + transit + corner + ctx.bodyTransitCost(flags, cx, y, cz));
                            }
                        }
                    }
                }
                return; // standable at node level always ends this direction's scan
            }
            if (!ctx.passable(fd)) return; // fence/fluid at node level — blocked diagonal
            if (c > maxGap) return;        // gap cell past the last possible landing — nothing farther
        }
    }

    /**
     * The lazy backwards arc verification: prove every gap column {@code k = 1..g}'s transit prism
     * ({@code y+1..y+3}) passable and its crossed transition's corner pair clear, accruing the
     * pass-through surcharge per column in the eager order ({@code corner + prism} per transition).
     * Returns the summed surcharge, or {@code NaN} when any consulted cell is blocked or unbuilt (a
     * float sentinel — no allocation, no boxing — checked via {@code Float.isNaN} since a surcharge sum
     * can never itself be NaN). The final transition into the landing stays at the call site (the eager
     * read order: gap transitions ascending, landing transition last).
     */
    private static float verifyArc(MovementContext ctx, int x, int y, int z, int dx, int dz, int g) {
        float transit = 0f;
        for (int k = 1; k <= g; k++) {
            int kx = x + dx * k;
            int kz = z + dz * k;
            // Transit prism (y+1..y+3) of the gap cell — read once, priced off the same descriptors.
            int p1 = ctx.packedAt(kx, y + 1, kz);
            if (p1 == MovementContext.UNBUILT) return Float.NaN;
            long d1 = ctx.descriptorOf(kx, y + 1, kz, p1);
            if (!ctx.passable(d1)) return Float.NaN;
            int p2 = ctx.packedAt(kx, y + 2, kz);
            if (p2 == MovementContext.UNBUILT) return Float.NaN;
            long d2 = ctx.descriptorOf(kx, y + 2, kz, p2);
            if (!ctx.passable(d2)) return Float.NaN;
            int p3 = ctx.packedAt(kx, y + 3, kz);
            if (p3 == MovementContext.UNBUILT) return Float.NaN;
            long d3 = ctx.descriptorOf(kx, y + 3, kz, p3);
            if (!ctx.passable(d3)) return Float.NaN;

            // The corner pair of the transition just crossed (cell k-1 → k).
            float corner = cornerPairCost(ctx, x + dx * k, z + dz * (k - 1),
                    x + dx * (k - 1), z + dz * k, y);
            if (corner < 0f) return Float.NaN;

            transit += corner
                    + ctx.cellTransitCost(d1) + ctx.cellTransitCost(d2) + ctx.cellTransitCost(d3);
        }
        return transit;
    }

    /**
     * Both corner columns of one diagonal transition: each needs its node-level cell arc-safe (passable,
     * or solid no taller than a full block — see the class Javadoc) and its body {@code y+1..y+3}
     * passable. Returns the summed pass-through surcharge of the six body cells, or {@code -1} when
     * blocked (a float sentinel — no allocation, no boxing).
     */
    private static float cornerPairCost(MovementContext ctx, int ax, int az, int bx, int bz, int y) {
        float a = cornerColumnCost(ctx, ax, y, az);
        if (a < 0f) return -1f;
        float b = cornerColumnCost(ctx, bx, y, bz);
        if (b < 0f) return -1f;
        return a + b;
    }

    /** One corner column (see {@link #cornerPairCost}); {@code -1} = blocked. */
    private static float cornerColumnCost(MovementContext ctx, int x, int y, int z) {
        int p = ctx.packedAt(x, y, z);
        if (p == MovementContext.UNBUILT) return -1f;
        long d = ctx.descriptorOf(x, y, z, p);
        // Node level: open, or solid no taller than a full block (arced over like flat ground). A fence/
        // wall (topY ≈ 24) pokes into the feet path and rejects. Geometry-only — unpriced.
        if (!ctx.passable(d) && ctx.topYOf(d) > CORNER_MAX_TOP_Y) return -1f;
        float t = 0f;
        for (int k = 1; k <= 3; k++) {
            int pk = ctx.packedAt(x, y + k, z);
            if (pk == MovementContext.UNBUILT) return -1f;
            long dk = ctx.descriptorOf(x, y + k, z, pk);
            if (!ctx.passable(dk)) return -1f;
            t += ctx.cellTransitCost(dk); // the hitbox brushes the corner — full per-cell rate (Diagonal)
        }
        return t;
    }

    /**
     * Cardinal {@link Parkour}'s four phases verbatim (RUNUP → TAKEOFF → AIRBORNE → LAND, with the
     * airborne-armed balk guard) — only the takeoff trigger differs: the raw diagonal projection
     * {@code ux·Δx + uz·Δz} over-reads along-line distance by √2, so it is compared against
     * {@link #TAKEOFF_EDGE_RAW} (the along-line edge pre-multiplied by √2; derivation on the class
     * Javadoc). Sprint for {@code g >= 2} ({@code |Δx| == |Δz| == g+1} on a diagonal step).
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final int ux = Integer.signum(tx - fx);
        final int uz = Integer.signum(tz - fz);
        final boolean sprint = Math.abs(tx - fx) - 1 >= 2; // diagonal: |Δx| == |Δz| == g+1
        final boolean[] airborneOnce = new boolean[1];
        MovePlan plan = new MovePlan();
        plan.resetWhen(b -> airborneOnce[0] && b.grounded()
                && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
        plan.phase("runup")
                .drive((b, v) -> {
                    airborneOnce[0] = false; // re-attempt begins → disarm until the next arc is live
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                })
                .advanceWhen(b -> b.grounded()
                        && ux * (b.x() - (fx + 0.5)) + uz * (b.z() - (fz + 0.5)) >= TAKEOFF_EDGE_RAW);
        plan.phase("takeoff")
                .drive((b, v) -> {
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                    b.setJumping(true);
                })
                .advanceWhen(b -> !b.grounded());
        plan.phase("airborne")
                .drive((b, v) -> {
                    airborneOnce[0] = true; // arc is live → a grounded return to the start cell is a balk
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                })
                .advanceWhen(b -> b.grounded()); // hold the arc inputs until touchdown
        plan.phase("land")
                .drive(SteerControl::recenterOnTarget)
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == ty + 1 && b.footZ() == tz);
        return plan;
    }
}
