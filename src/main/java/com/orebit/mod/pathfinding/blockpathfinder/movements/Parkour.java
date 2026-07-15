package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * Parkour — a running gap jump across {@code g} open columns (MOVEMENT-DESIGN Tier 1 parkour), now with
 * three landing classes found by ONE shared per-cardinal scan:
 *
 * <ul>
 *   <li><b>flat</b> — same-level landing (the v1 move, unchanged geometry);</li>
 *   <li><b>rising (+1)</b> — landing one block higher (the jump that clears a gap AND a ledge — the gap
 *       counterpart to {@link Ascend}, which owns the adjacent {@code g == 0} case);</li>
 *   <li><b>falling (−1 …)</b> — landing below node level (the gap counterpart to {@link Fall}/{@link
 *       Descend}, which own the adjacent walk-off; a parkour drop clears open columns first).</li>
 * </ul>
 * plus a fourth, <b>fallback-only</b> class the shared scan arms per direction: the <b>(c,±1) OFFSET
 * jump</b> — a knight's-move landing one cell off the cardinal line, probed only when the aligned pass
 * saw a genuine gap but emitted nothing (its own section below).
 *
 * <h2>The single directional pass — transit-LAZY, landing-first (scan-cost discipline)</h2>
 * The scan runs on EVERY standing node expansion, and the A/B bench measured the v1.1 <i>eager</i>
 * per-column transit-prism verification at +17–27% total search time on air-heavy scenarios: in open air
 * every direction paid 3 prism reads per column for landings that don't exist. The pass is therefore
 * inverted to <b>landing-first</b>. The forward walk reads, per column {@code c = 1..maxGapAll+1}:
 * <ul>
 *   <li>the <b>node-level cell</b> — decides landing / gap / blocked exactly as before (standable ⇒ a
 *       terminal landing column — flat when its body is clear, rising when the y+1 "body blocker" is
 *       itself a standable floor, the common raised ledge — and the scan ENDS; never overfly a ledge, v1
 *       rule; non-passable ⇒ the whole direction ends);</li>
 *   <li>at most ONE extra cell, {@code y+1}, and only when a rising landing is still in envelope AND the
 *       gap cell's resident CRAWL headroom bit cannot already prove {@code y+1} clear (in open air it
 *       proves it for free) — the floating-ledge rising DETECTION read. A consulted {@code y+1} that is
 *       blocked or unbuilt ends the direction, exactly as the eager prism did;</li>
 *   <li>the envelope-capped <b>down-cells</b> for falling landing detection (first standable floor wins,
 *       exactly {@link Fall}'s model), only while some drop row's envelope still offers the current
 *       gap.</li>
 * </ul>
 * The transit prisms ({@code y+1..y+3} per gap column) are verified and priced <b>backwards over the
 * arc</b> only when a landing is actually found — the same lazy pattern the rising arc's {@code y+4} row
 * used from the start. The CANDIDATE SET is byte-identical to the eager scan (same cells verified, same
 * prices — only deferred):
 * <ul>
 *   <li><b>Blocked prisms no longer terminate the walk eagerly.</b> The lazy walk scans node-level cells
 *       past a blocked prism (bounded by {@code maxGapAll}), and backwards verification rejects every
 *       landing whose arc crosses the blocked column — the same candidates the eager termination
 *       produced. Once one demanded prism fails, the direction returns outright: every later landing's
 *       arc contains the failed column, so nothing farther can ever emit.</li>
 *   <li><b>Multiple landings per direction never re-read prism cells.</b> Falling landings don't end the
 *       scan, so one direction can demand verification several times (at non-decreasing depths — landing
 *       columns only grow). A monotone prefix cursor (two scalar locals: columns-verified +
 *       accumulated surcharge, zero allocation) resumes verification where the last demand stopped, so
 *       each prism cell is read at most once per direction and the surcharge keeps the eager
 *       column-ascending summation order.</li>
 *   <li><b>UNBUILT stays exactly as strict for every cell actually consulted:</b> an unbuilt node-level
 *       cell ends the direction, a consulted {@code y+1} / down-cell behaves as in v1.1, and an unbuilt
 *       prism cell consulted during backwards verification rejects the landing (and, per the first
 *       bullet, the direction). Cells the lazy walk never consults (prisms with no landing behind them)
 *       are simply never read — that is the entire saving.</li>
 * </ul>
 * On open flat ground the whole move is still ONE read per direction (column 1 standable → break),
 * byte-identical to v1.
 *
 * <h2>The gap envelope — DERIVED, takeoff-condition-parameterized ({@link ParkourEnvelope})</h2>
 * The per-class gap caps are no longer hand-tuned constants; they are computed from closed-form MC
 * ballistics in {@link ParkourEnvelope} (the model validated in {@code parkour_envelope_params.py}, which
 * supersedes the prose {@code DESIGN-parkour-envelope.md} table). Each expansion reads ONE row
 * {@code ParkourEnvelope.MAX_GAP[startTopY][gsfBucket][occBucket]} = {@code {flat, rise, fall1, fall2,
 * fall3, diag}}, keyed on the takeoff conditions: the takeoff SURFACE height ({@link
 * MovementContext#floorSurface}, or {@link MovementContext#directionalTopY} toward the jump for a stair),
 * the slow-floor bucket (soul sand — {@link MovementContext#isSlow}), and the LIGHT through-slow body
 * bucket (berry / powder snow — {@link MovementContext#bodyTransitLight}). Honey floors and cobweb body
 * cells never reach the table — the {@code reducesJump}/{@code noJumpFromBody} gates above refuse them.
 *
 * <p><b>The BASE row</b> (full block, normal floor, no slow body — {@code MAX_GAP[16][0][0]}): flat 1–3,
 * rising 1–2, falling drops −1..−3 gaps to 4, diagonal (in {@link DiagonalParkour}) 1–2. This is the fix
 * for the old hardcoded envelope, which OVER-offered rising-3, flat-4 and diagonal-3 — jumps the physics
 * cannot make (the bot attempted and fell). The derived cap of 2 for rising / diagonal, and the absence
 * of any flat-4 row, come straight from the reach budget vs the {@link ParkourEnvelope#MAX_CLEARED_AIR}
 * policy cap.
 *
 * <pre>
 *   condition (startTopY / gsf / occ)   flat  rise  fall−1/−2/−3  diag   why tighter
 *   full 16 / normal / none  (BASE)      3     2     4 /4 /4       2      the reference envelope
 *   slab  8 / normal / none              2     0     3 /4 /4       2      +0.5 effΔy eats the flat/rise reach
 *   full 16 / soul   / none              2     1     2 /2 /3       1      0.4 speed factor cuts the H budget
 *   full 16 / normal / berry             2     0     2 /3 /3       1      berry stuck-mult scales the whole arc
 * </pre>
 *
 * <p>Physics recap (full derivation in {@link ParkourEnvelope}): vanilla jump {@code vy₀ = 0.42}, per-tick
 * {@code vy ← (vy − 0.08)·0.98} ⇒ apex {@code y(6) ≈ 1.25} (= {@link MovementContext#JUMP_RISE}/16), feet
 * return to 0 at ~t11, cross −1/−2/−3 at ~t13/t14/t16; sprint-jump horizontal reach follows the geometric
 * sum {@code X(T)}. A lower takeoff surface folds into an effective Δy that shrinks every reach; a slow
 * floor / slow body cell only ever REDUCES reach (never fabricates it — {@link ParkourEnvelope}'s clamp).
 * Drops deeper than −3 are left to {@link Fall} off the near edge. {@link #PARKOUR_MAX_GAP} still NARROWS
 * the derived flat cap (default 3 = the base maximum; lower it to 2 for a more conservative flat row).
 *
 * <h2>Clearance cell sets (derivations)</h2>
 * <b>Flat</b> (v1): takeoff head {@code y+3}; per gap column the node-level cell open (the SHAPE_OTHER
 * fence exclusion — a fence at {@code y} pokes to {@code y+1.5}, into the transit space) + body prism
 * {@code y+1..y+3}; landing body {@code y+1..y+2}. <b>Rising</b>: the whole arc rides up to one block
 * higher (apex feet {@code y+2.25} ⇒ head top {@code y+4.05}), and the early arc still sweeps the low
 * prism (at gap column 1 the feet are only ~{@code y+1.4..1.8}), so the rising transit set is the UNION:
 * the full flat prism per gap column <b>plus</b> {@code y+4} over the takeoff column, every gap column,
 * and the landing column (grazed on entry); landing body {@code y+2..y+3}. Both the gap prisms and the
 * {@code y+4} row are lazily verified (backwards, on landing discovery). <b>Falling</b>: the arc never
 * rises above the flat arc, so gap columns need exactly the flat prism; the landing column additionally
 * needs every descended cell below node level down to the landing floor passable ({@link Fall}'s column
 * rule — the landing body cells are inside that span, read once during detection). Requiring the landing
 * column's full {@code y+1..y+3} prism too is a deliberate conservative simplification (a short falling
 * jump enters it near apex), retained verbatim by the lazy pass: the falling backwards verification spans
 * columns {@code 1..c} — the landing column's own prism included — exactly the set the eager scan had
 * proven before it could reach the down-scan.
 *
 * <h2>No edit folding — a hard validity rule (v1, unchanged)</h2>
 * You cannot mine or place mid-jump, so every landing class uses the plain edit-free {@code accept} (the
 * {@link Fall} precedent): a cell that would need a break simply kills the candidate.
 *
 * <h2>Cost model (derivations — physically derived from arc time per (gap, drop))</h2>
 * All landing classes share {@link #RUNUP_COST} (one walk step onto the takeoff edge) and
 * {@link #COMMIT_PENALTY} (3 ticks, the all-or-nothing premium). Air time:
 * <ul>
 *   <li><b>flat</b>: {@link #AIR_COST}{@code [g]} = 8 / 11 / 14 ticks for g = 1/2/3 (v1 — the ~12-tick
 *       full arc, shorter hops jump later/land earlier, g=3 adds sprint windup), extended with
 *       {@code [4] = 16} (~3.6 t per horizontal block at sprint) used only by the aggressive falling
 *       4-gap;</li>
 *   <li><b>rising</b>: {@code AIR_COST[g] − }{@link #RISE_EARLY_TICKS}: the +1 floor intercepts the
 *       descending arc at ~t8–9 vs the same-level return at ~t12 — ~3 ticks earlier; only 2 are credited
 *       (the conservative, dearer direction);</li>
 *   <li><b>falling</b>: {@code AIR_COST[g] + }{@link #FALL_EXTRA}{@code [drop]}, the marginal descent
 *       ticks from the parabola table (ticks to −1/−2/−3 minus ticks to 0: 14/15/17 − 12 = 2/3/5 —
 *       consistent with {@link Fall#PER_BLOCK}'s ≈2.5 t/block average), plus {@link
 *       com.orebit.mod.pathfinding.blockpathfinder.BotCaps#costPerHitpoint} per block past {@link
 *       com.orebit.mod.pathfinding.blockpathfinder.BotCaps#safeFallDistance} (the damage-as-cost model,
 *       ≈1 HP per excess block in the planner's unified ticks-per-HP currency — the same term {@link
 *       Fall} charges, so a parkour drop and a walk-off drop price damage identically); a drop beyond
 *       {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#maxFallDistance} is never emitted.</li>
 * </ul>
 * Flat totals 15.6 / 18.6 / 21.6 (v1 unchanged). Per-block cost stays ≥ the octile ruler (4.633)
 * everywhere except the rising discount's worst case, which the greedy weight already tolerates
 * (SprintSwim's 3.56 precedent).
 *
 * <h2>Hazard / through-slow pricing (v1 pattern, extended; unchanged VALUES under the lazy pass)</h2>
 * Gap-column body cells ({@code y+1..y+3}) are priced per cell as they are read — now during the
 * backwards verification, off the same descriptors in the same column-ascending order, so every landing
 * carries the identical surcharge the eager scan computed. (The one bookkeeping difference: a falling
 * landing's descended-cell surcharges are accumulated during detection and added as their own subtotal,
 * so the final sum's float association can differ from the eager left-fold by an ulp when several
 * transited cells carry nonzero surcharges — immaterial, and exact in the overwhelmingly common all-zero
 * case.) A rising landing additionally prices the {@code y+4} cells over the gap
 * columns (read in the lazy sweep) and its landing body ({@code y+2..y+3}, descriptors in hand); the
 * takeoff and landing columns' {@code y+4} are clearance-only, unpriced (the {@link Ascend} source-cell
 * precedent). A falling landing prices the landing column's prism + node-level cell + every descended
 * cell ({@link Fall}'s column pricing — the landing body rides inside that span, so nothing is
 * double-charged and no flags-gated {@code bodyTransitCost} call is added on top). The flat landing keeps
 * v1's flags-gated {@link MovementContext#bodyTransitCost}.
 *
 * <h2>The (c,±1) OFFSET-jump fallback tier — knight-ish landings for ragged rims</h2>
 * Real cliff rims are ragged: a direction can stare down a genuine gap whose only viable landing sits one
 * cell OFF the cardinal line. The offset tier probes flat landings at {@code (c, ±1)} — {@code c} blocks
 * along the cardinal, one block lateral — as a <b>FALLBACK ONLY</b>: for a given direction it runs exactly
 * when the aligned pass saw at least one OPEN gap column yet emitted no landing of any class (the
 * per-direction flag {@link #scanDirection} returns). The aligned candidate set is therefore byte-identical
 * with the tier present, and terrain where every direction either lands or has no gap — ordinary ground,
 * walls, a floor at column 1 — pays zero extra reads.
 *
 * <p><b>Cost honesty — the armed case is NOT rare in air-heavy floods.</b> "A gap with no aligned
 * landing" is the signature of a standing node in OPEN AIR: from a pillar top every cardinal is an open
 * gap that never lands, so in the pillar-cone / TOWER floods (the performance model's canonical
 * pathology, where ~99% of expansions are off-column open air) the probe arms on ALL FOUR directions of
 * essentially EVERY expansion. It stays bounded by construction — one floor read per shape, ≤ 2 shapes
 * per side, 2 sides ⇒ ≤ 4 reads per direction, ~+16 reads/node on top of the aligned lazy walk's
 * ~9/direction (~+44% of this move's flood reads; grounded terrain stays 1 read/direction, byte-identical
 * to v1) — but "bounded" is not "measured": the tier ships only after the mandatory paired interleaved
 * JMH A/B (TOWER/OPEN and SHORT included) shows no scenario regressing beyond noise.
 * {@link #OFFSET_FALLBACK} (default ON) is that bench's runtime lever and the field escape hatch —
 * flipping it OFF restores the aligned-only scan bit-for-bit. A cheaper prefilter derived from the
 * aligned pass's own reads (e.g. "skip when the down-scan saw no floor in this direction") was considered
 * and REJECTED: an aligned line staring over a chasm with the only floor one cell off-line is exactly the
 * ragged rim the tier exists for, so any such gate deletes offset candidates precisely where they matter.
 *
 * <p><b>Envelope (derivation — center-to-center displacement, the cardinal table's own ruler).</b> The
 * cardinal flat row was derived on center-to-center displacement (a flat {@code g}-gap lands {@code g+1}
 * blocks out), so the flat envelope's REACH is {@code flatMax+1} blocks (default 3+1 = 4.0). An offset
 * shape's displacement is {@code sqrt(c²+1)}; it is envelope-legal when that displacement is within the
 * flat reach: (2,±1) = √5 ≈ 2.24 (easier than the flat 2-gap's 3.0), (3,±1) = √10 ≈ 3.16 — both ≤ 4.0;
 * (4,±1) = √17 ≈ 4.12 exceeds it, consistent with the deliberate absence of a flat 4 row, so the shipped
 * shape set is statically {@code {(2,±1),(3,±1)}} ({@link #OFFSET_C_LIMIT}) and the runtime legality test
 * is the exact integer form {@code c² ≤ flatMax·(flatMax+2)} (⇔ {@code sqrt(c²+1) ≤ flatMax+1}): lowering
 * {@link #PARKOUR_MAX_GAP} to 2 shrinks the reach to 3.0 and leaves only (2,±1); 1 disables the tier.
 * <b>v1 is FLAT (dy 0) ONLY</b> — rising/falling offset landings are deferred until the compound geometry
 * (a skewed arc AND a height change) gets its own in-game verification; the aligned rows cover those.
 *
 * <p><b>Swept-arc clearance (derivation of the static supercover tables).</b> The hitbox is an
 * axis-aligned 0.6×0.6 square, so the volume swept along the straight takeoff-center→landing-center line
 * is the segment's Minkowski sum with the half-width-0.3 square: column {@code (a,l)} (direction-local:
 * {@code a} along the cardinal, {@code l} lateral) is swept iff the segment enters the column inflated by
 * 0.3 per axis ({@code [a−0.8,a+0.8] × [l−0.8,l+0.8]}). With the line's slope {@code 1/c} that solves to
 * {@code (k,0)} for {@code k = 1..c} plus {@code (k,1)} for {@code k = 0..c−1} — point-symmetric about the
 * arc midpoint; the boundary corner-grazes ((0,1) and (c,0) at the extremes) are included conservatively.
 * Precomputed per shape in {@link #OFFSET_COVER} (static {@code (a,l)} pair lists — zero per-node math),
 * mirrored onto either side via the lateral unit vector. Per swept column: the FLOOR cell must be
 * <i>arc-safe</i> — passable OR collision top ≤ a full block ({@link DiagonalParkour}'s corner rule: a
 * flat jump's feet never dip below the takeoff floor top, so a full block under the line is flat ground
 * arced over — rejecting it would kill the everyday "hop from a wide platform's edge cell", where the
 * lateral takeoff neighbour (0,1) IS the platform — while a fence's topY ≈ 24 pokes into the feet path
 * and rejects; geometry-only, unpriced, the node-level proxy rule) — and the transit prism
 * {@code y+1..y+3} must be passable, priced per cell (the aligned gap-column rule). The landing needs
 * standable + body clear {@code y+1..y+2} + the flags-gated {@code bodyTransitCost} — exactly the flat
 * landing's checks. All of it is verified <b>backwards on emit</b> (landing-first, the tier-wide lazy
 * pattern): a probe pays ONE floor read per shape until a standable landing appears. Per side the NEAREST
 * shape that meets a standable/blocked/unbuilt landing cell ends the side (never overfly: that column sits
 * in every farther shape's cover — where a clear-bodied ledge would re-emerge as the landing and a blocked
 * one rejects the arc — so ending early is both consistent and cheaper; covers are nested,
 * {@code cover(c) ⊂ cover(c+1)}).
 *
 * <p><b>Cost (the {@link DiagonalParkour} interpolation precedent).</b> Air time scales with horizontal
 * displacement, so the offset air cost interpolates the cardinal flat table (8/11/14/16 ticks at
 * displacements 2/3/4/5) at {@code sqrt(c²+1)}: (2,±1) → 2.236 → ≈8.71 ticks; (3,±1) → 3.162 → ≈11.49.
 * Totals with {@link #RUNUP_COST} + {@link #COMMIT_PENALTY}: ≈16.34 / 19.12 — both above the octile ruler
 * between the endpoints (≈11.19 / 15.82), admissible with margin. Sprint follows displacement ≥ the flat
 * 2-gap's 3.0 (the cardinal {@code g ≥ 2} rule, which the shapes interpolate): (2,±1) walks, (3,±1)
 * sprints — {@link #plan} tests the exact integer {@code dx²+dz² ≥ 9}.
 *
 * <h2>Irreversibility</h2>
 * Flat jumps are symmetric (v1). A <b>falling</b> parkour edge carries its full {@code dy} on the single
 * emitted edge (the landing cell is {@code drop} below the source node), so the partial-path guard's
 * {@code lastReversibleRow} — which inspects per-edge Y drops {@code > jumpHeight} — sees it correctly and
 * automatically: a no-place bot's partial never commits past a parkour drop deeper than it can jump back.
 * KNOWN APPROXIMATION (noted, not fixed here): the guard's Y-only model treats a {@code drop ≤ jumpHeight}
 * falling jump as reversible, but the RETURN jump is a rising(+1) jump whose gap envelope is narrower
 * (rising caps at 2 by default vs falling's 4) — a falling(−1) 3- or 4-gap is one-way for a no-place bot
 * yet passes the guard. Accepted for now: the guard was designed as a Y-only heuristic and the window is
 * small.
 *
 * <h2>Execution — the phase framework (v1 shape; falling adds a drop-control handoff)</h2>
 * {@link #plan} is RUNUP → TAKEOFF → AIRBORNE → LAND for every landing class: all predicates are
 * positional and the landing Y is already taken from the plan's to-cell ({@code done} tests {@code footY
 * == ty + 1}), so a RISING arc needs no new phase logic — it simply touches down higher and
 * {@code advanceWhen(grounded)} fires there. The along-line progress projections (the takeoff trigger and
 * the falling handoff) use the NORMALIZED takeoff→landing direction, so the SAME phases also run the
 * {@code (c,±1)} OFFSET shapes, whose {@code (Δx,Δz)} is not a unit axis: one {@code sqrt} at plan BUILD
 * time (cold — a MovePlan is built once per waypoint step, the Pillar precedent) keeps every per-tick
 * predicate a multiply-add, and for a cardinal shape the unit vector degenerates to the old signum axes
 * byte-for-byte; {@code resetWhen}/{@code done} are cell-equality predicates (the exact start / landing
 * cell), so they hold unchanged for an offset landing. A FALLING arc gets ONE new piece: the airborne drive holds
 * full forward only until the bot's centre clears the last gap column, then hands off to {@link Fall}'s
 * airborne drop-control (recenter on the landing column) for the remaining descent — a jump landing below
 * takeoff level otherwise carries its sprint momentum to touchdown and past a narrow landing cell (the
 * momentum-overshoot the fall-not-vertical work resolved for {@link Fall}; the follower drives converted
 * moves via the PhaseRunner, so Fall's own {@code steer} never applies here). Derivation on the method.
 * The airborne-ARMED {@code resetWhen} guard (armed by the airborne drive, disarmed by the runup drive)
 * is preserved exactly — see the v1 derivation on the method. Sprint is REACH-AWARE: held for
 * center-to-center displacement ≥ 3.0 (the cardinal {@code g ≥ 2} rule, which the offset shapes
 * interpolate) and for every rising jump (the +1 landing eats ~½ block of range, so even g=1 wants the
 * sprint reach) — EXCEPT a FALLING jump, which sprints only at {@code gap ≥ 3}: the drop buys airtime so a
 * walk clears the shorter gaps, and the sprint-jump takeoff boost would otherwise carry the bot past a
 * narrow landing (the fall2 overshoot; see {@link #plan}). Landing IN the gap (a missed falling jump) is
 * the follower's grounded-stall recovery arm, as in v1.
 */
public final class Parkour implements Movement {

    /**
     * The largest FLAT gap (open columns) the move offers — the v1 knob, honored with its exact v1
     * semantics (default 3, the owner-verified flat maximum; lower to 2 for the old conservative row,
     * values above 3 clamp to the cost table — there is deliberately no flat 4). The rising/falling rows
     * have their own envelope (see the class Javadoc table).
     */
    public static int PARKOUR_MAX_GAP = 3;

    /**
     * The deepest DROP the falling class offers ({@code drop 1..3}); deeper landings are left to
     * {@link Fall} off the near edge. The per-drop gap CAPS are no longer a hand-tuned constant — they are
     * read per takeoff condition from {@link ParkourEnvelope#MAX_GAP} ({@link ParkourEnvelope#FALL1}…
     * {@link ParkourEnvelope#FALL3}). This is only the scan depth (how many rows below to probe).
     */
    private static final int FALL_DEPTH = 3;

    /** Ticks for the approach step onto the takeoff edge — one walk step ({@link Traverse#FLAT_COST}). */
    public static final float RUNUP_COST = Traverse.FLAT_COST;

    /**
     * Ticks in the air by gap size ({@code [g]}, index 0 unused): the ~12-tick full same-level arc, with
     * shorter jumps landing earlier (g=1: 8, g=2: 11), g=3 adding sprint windup (14), and g=4 (reachable
     * only by the aggressive falling arc) extrapolated at the ~3.6 t/block sprint ruler (16).
     */
    private static final float[] AIR_COST = {0f, 8f, 11f, 14f, 16f};

    /**
     * Air ticks CREDITED back on a rising jump: the +1 floor intercepts the descending arc at ~t8–9
     * against the ~t12 same-level return (parabola table in the class Javadoc) — ~3 ticks earlier, of
     * which only 2 are credited (under-crediting is the conservative, dearer direction).
     */
    private static final float RISE_EARLY_TICKS = 2f;

    /**
     * Extra air ticks per block of drop ({@code [drop]}): the marginal descent time from the parabola —
     * feet cross −1/−2/−3 at ~t14/t15/t17 vs 0 at ~t12, so 2/3/5 (matching {@link Fall#PER_BLOCK}'s
     * ≈2.5 t/block average over the same window).
     */
    private static final float[] FALL_EXTRA = {0f, 2f, 3f, 5f};

    /** Behavioral premium (ticks) for an all-or-nothing move — a jump can't be abandoned halfway. */
    public static final float COMMIT_PENALTY = 3f;

    /** Total FLAT edge cost by gap size: {@code RUNUP + AIR[g] + COMMIT} → 15.6 / 18.6 / 21.6 ticks. */
    private static final float[] COST = {
            0f,
            RUNUP_COST + AIR_COST[1] + COMMIT_PENALTY,
            RUNUP_COST + AIR_COST[2] + COMMIT_PENALTY,
            RUNUP_COST + AIR_COST[3] + COMMIT_PENALTY,
    };

    // ---- The (c,±1) offset fallback tier (class Javadoc section) -----------------------------------

    /** {@link #scanDirection} result bit: the direction holds at least one OPEN gap column. */
    private static final int DIR_SAW_GAP = 1;
    /** {@link #scanDirection} result bit: the direction emitted at least one landing (any class). */
    private static final int DIR_EMITTED = 2;

    /**
     * Runtime kill switch / A/B lever for the (c,±1) offset fallback tier — default ON (the documented
     * candidate set); OFF restores the aligned-only scan bit-for-bit (no probe reads, no offset
     * candidates). Exists because the armed case is per-node in air-heavy floods (the class Javadoc's
     * cost-honesty paragraph): the mandated paired interleaved JMH A/B on TOWER/OPEN + SHORT flips this
     * at runtime without a rebuild, and it remains the escape hatch should a flood regression ever
     * reproduce in the field. Consulted only on the armed (gap-without-landing) path — grounded terrain
     * never reads it.
     */
    public static boolean OFFSET_FALLBACK = true;

    /**
     * Largest along-axis distance {@code c} any offset shape ships: (4,±1)'s √17 ≈ 4.12 displacement
     * exceeds the flat row's maximum reach 4.0 (the class Javadoc envelope derivation), so {@code c = 4}
     * derives OUT statically — consistent with the deliberate absence of a flat 4 row — and only the
     * (2,±1)/(3,±1) tables exist.
     */
    private static final int OFFSET_C_LIMIT = 3;

    /**
     * The static supercover tables — per shape {@code c}, the direction-local {@code (a, l)} pairs of
     * every swept gap column (class Javadoc derivation: {@code (k,0)} for {@code k = 1..c} plus
     * {@code (k,1)} for {@code k = 0..c−1}; the takeoff (0,0) and the landing (c,1) are excluded — each
     * has its own checks). Flat pair lists in {@code a}-ascending, then {@code l}-ascending order (a
     * fixed verify/price order); indexed by {@code c}, entries 0/1 unused. Zero per-node math — the
     * probe just walks the pairs.
     */
    private static final int[][] OFFSET_COVER = {
            null, null,
            {0, 1, 1, 0, 1, 1, 2, 0},                    // (2,±1): displacement √5 ≈ 2.24
            {0, 1, 1, 0, 1, 1, 2, 0, 2, 1, 3, 0},        // (3,±1): displacement √10 ≈ 3.16
    };

    /**
     * Total offset edge cost by shape ({@code [c]}, entries 0/1 unused): {@link #RUNUP_COST} + the
     * cardinal flat {@link #AIR_COST} table interpolated at the shape's center-to-center displacement
     * {@code sqrt(c²+1)} (the {@link DiagonalParkour} precedent; derivation in the class Javadoc) +
     * {@link #COMMIT_PENALTY} → ≈16.34 / 19.12 for (2,±1) / (3,±1). Computed once at class init.
     */
    private static final float[] OFFSET_COST = {
            0f, 0f,
            RUNUP_COST + interpolateAir(Math.sqrt(2 * 2 + 1)) + COMMIT_PENALTY,
            RUNUP_COST + interpolateAir(Math.sqrt(3 * 3 + 1)) + COMMIT_PENALTY,
    };

    /**
     * The cardinal flat {@link #AIR_COST} table linearly interpolated at a fractional center-to-center
     * displacement {@code d} (row {@code g} sits at displacement {@code g+1}, i.e. 2/3/4/5). Class-init
     * only (fills {@link #OFFSET_COST}) — never on the search path.
     */
    private static float interpolateAir(double d) {
        int g0 = (int) d - 1;                        // the row at or below d (row g ⇔ displacement g+1)
        float f = (float) (d - (g0 + 1));
        return AIR_COST[g0] + f * (AIR_COST[g0 + 1] - AIR_COST[g0]);
    }

    /** The total edge cost of the {@code (c,±1)} offset shape (tests/tuning; see {@link #OFFSET_COST}). */
    public static float offsetCost(int c) {
        return OFFSET_COST[c];
    }

    /**
     * How far past the takeoff cell's centre (blocks, along the jump axis) the bot runs before jumping —
     * leaves ~0.15 block behind the 0.3-half-width hitbox trailing edge, i.e. jump as late as possible
     * without stepping off. Tune 0.30–0.40 in-game. (The Phase-4 sweep proved a UNIFORM earlier takeoff can't
     * replace this + Fix 3 without breaking the max-reach + rest-start tiers — see takeoff-timing-sweep.md.)
     */
    public static final double TAKEOFF_EDGE = 0.35;

    /**
     * Fix 3 — HAZARDOUS gap-floor early-takeoff threshold (along-axis blocks). When the FIRST gap-floor cell
     * just past the takeoff lip is a takeoff hazard ({@link com.orebit.mod.pathfinding.blockpathfinder.BotSteering#gapFloorHazardAt}
     * — magma/lava damaging-on-contact, or honey jump-suppressing), the jump must fire BEFORE the bot's
     * horizontal CENTER crosses the lip (along-proj {@code 0.5}) onto that block, because magma damage is
     * center-based + needs {@code onGround} and honey's jump factor is read from the block under the center
     * at launch. The runup trigger becomes PREDICTIVE — {@code proj + 3·vAlong ≥ this}. The {@code 3·vAlong}
     * lookahead covers the MEASURED latency from the trigger crossing to the last grounded tick: TWO grounded
     * ticks of runup→takeoff-drive→liftoff, plus one tick of trigger overshoot (the trace showed the jump
     * input landing the bot airborne 3 ticks after the crossing — a hazard-trial run at {@code 2·v} still left
     * the last grounded center at proj ≈ 0.58, one block over the magma). At {@code 3·v} the last grounded
     * center lands at {@code ~[this−v, this]} &lt; {@code 0.5} for both walk and sprint (measured: the normal
     * edge 0.35 leaves it at proj ≈ 0.82, well past the lip → the damage/suppress window). {@code 0.35} keeps
     * a ≥0.15-block margin inside the lip while jumping as LATE as is safe (minimal reach cost — the servo
     * recovers the rest). Non-hazard jumps are UNCHANGED ({@link #TAKEOFF_EDGE}), so every normal trial is
     * byte-identical. */
    public static final double HAZARD_TAKEOFF_LOOKAHEAD = 0.35;

    /** Fix 3 predictive lookahead multiplier on the along-axis velocity (see {@link #HAZARD_TAKEOFF_LOOKAHEAD}
     *  — the measured trigger→last-grounded latency is ~3 ticks). */
    public static final double HAZARD_TAKEOFF_TICKS = 3.0;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a running jump — only while upright
        if (ctx.caps().jumpHeight() < 1) return;
        if (ctx.reducesJump(x, y, z)) return; // honey-block floor: the reduced jump apex clears no gap
        if (ctx.noJumpFromBody(x, y, z)) return; // cobweb body cell: the stuck multiplier kills take-off velocity
        if (!ctx.solidFooting(x, y, z)) return; // a jump launches only from solid ground — a vine/ladder/
        // scaffolding takeoff cell is a CLIMBING state (no 0.42 horizontal launch; input only ejects), not a
        // ground jump. A climb node is MODE_STANDING and floorSurface reports the full-block sentinel, so
        // WITHOUT this gate parkour is silently offered off a vine/ladder (the jungle vine→treetop pathology).

        // Takeoff head-clearance (source y+3) — direction-independent, proven once. The bot stands here so
        // its feet/head are clear; HEADROOM == JUMP iff y+3 is also clear. No break folding (you cannot
        // mine mid-jump): an unproven bit falls back to reading the real cell, and a blocked cell rejects.
        int srcFlags = ctx.flagsAt(x, y, z);
        if (!ctx.headroomProves(srcFlags, y, MovementContext.HEADROOM_JUMP)) {
            int p3 = ctx.packedAt(x, y + 3, z);
            if (p3 == MovementContext.UNBUILT
                    || !ctx.passable(ctx.descriptorOf(x, y + 3, z, p3))) {
                return;
            }
        }

        // Takeoff-condition key for the DERIVED envelope (ParkourEnvelope): the slow-floor bucket (soul
        // sand — honey is already refused above) and the LIGHT through-slow body bucket (berry / powder
        // snow — cobweb is already refused above). Both are direction-INDEPENDENT — hoist them. The takeoff
        // SURFACE height is direction-dependent only for a stair (its high 16/16 half faces one edge), so a
        // uniform (non-stair) floor hoists ONE table lookup; a stair recomputes it per cardinal.
        final long floorDesc = ctx.descriptorAt(x, y, z);
        final int gsfBucket = ctx.isSlow(floorDesc) ? 1 : 0;
        final int occBucket = ctx.bodyTransitLight(x, y, z) ? 1 : 0;
        final boolean stair = ctx.isStair(floorDesc);
        final int[] uniformEnv = stair ? null
                : ParkourEnvelope.MAX_GAP[ParkourEnvelope.index(ctx.floorSurface(x, y, z))]
                        [gsfBucket][occBucket];

        final int safeFall = ctx.caps().safeFallDistance();
        // Deepest drop actually probed: the envelope's fall depth capped by the bot's max survivable fall.
        final int capsDrop = Math.min(FALL_DEPTH, ctx.caps().maxFallDistance());

        for (int[] d : CARDINALS) {
            // The direction's envelope row: a stair takeoff reads the surface toward THIS jump (its high
            // half faces one edge), so soul-sand/berry-flat rows can differ per direction; a flat floor
            // reuses the hoisted row (zero extra lookups).
            int[] env = stair
                    ? ParkourEnvelope.MAX_GAP[ParkourEnvelope.index(
                            ctx.directionalTopY(floorDesc, d[0], d[1]))][gsfBucket][occBucket]
                    : uniformEnv;
            // The flat knob NARROWS the derived flat cap (and the cost table bounds it too); rise + fall +
            // diag come straight from the table. Zero per-node math beyond the index — the row load folds.
            int flatMax = Math.min(PARKOUR_MAX_GAP, Math.min(env[ParkourEnvelope.FLAT], COST.length - 1));
            int riseMax = env[ParkourEnvelope.RISE];
            // The widest gap any still-capable falling row offers (generic max, so a table edit is honored).
            int fallGapCap = 0;
            for (int dr = 1; dr <= capsDrop; dr++) {
                int m = env[ParkourEnvelope.FALL1 + dr - 1];
                if (m > fallGapCap) fallGapCap = m;
            }
            // Scan horizon for THIS direction: the last column any landing class can still use.
            int maxGapAll = Math.max(flatMax, Math.max(riseMax, fallGapCap));
            if (maxGapAll < 1) continue; // this takeoff condition offers no jump in this direction

            int found = scanDirection(ctx, x, y, z, d[0], d[1], out,
                    flatMax, riseMax, env, capsDrop, safeFall, fallGapCap, maxGapAll);
            if (found == DIR_SAW_GAP && OFFSET_FALLBACK) {
                // A genuine gap with NO aligned landing of any class — arm the (c,±1) offset fallback
                // tier for this direction only (class Javadoc). Directions that landed, or that have no
                // open gap at all (walls, plain floor at column 1), pay nothing; an open-air standing
                // node arms every direction — bounded but per-node in floods (class Javadoc cost note).
                probeOffsets(ctx, x, y, z, d[0], d[1], out, flatMax, maxGapAll);
            }
        }
    }

    /**
     * The shared single directional pass, transit-LAZY (class Javadoc): walk columns
     * {@code c = 1..maxGapAll+1} reading only the node-level cell (plus the CRAWL-gated {@code y+1}
     * rising-detection read and the envelope-capped falling down-cells); when a landing is actually
     * found, verify+price its gap prisms BACKWARDS via {@link #verifyPrisms} through the monotone prefix
     * cursor ({@code verified}/{@code verifiedTransit} — demands only ever deepen, so no prism cell is
     * read twice even when one direction emits several falling landings). A failed demand ends the whole
     * direction: every later landing's arc contains the failed column. Stop at the first standable
     * node-level cell (never overfly a ledge, v1) or blocked/unbuilt consulted cell. Primitives only,
     * zero allocation.
     *
     * @return the {@link #DIR_SAW_GAP} | {@link #DIR_EMITTED} bits — the per-direction offset-fallback
     *         flag (class Javadoc): exactly {@code DIR_SAW_GAP} (a gap with no landing of any class)
     *         arms {@link #probeOffsets} in the caller.
     */
    private static int scanDirection(MovementContext ctx, int x, int y, int z, int dx, int dz,
            CandidateSink out, int flatMax, int riseMax, int[] env, int capsDrop, int safeFall,
            int fallGapCap, int maxGapAll) {
        // The lazy-verification prefix cursor: gap columns 1..verified have proven transit prisms, whose
        // pass-through surcharge (summed column-ascending — the eager order) is verifiedTransit.
        int verified = 0;
        float verifiedTransit = 0f;
        int found = 0; // DIR_SAW_GAP | DIR_EMITTED accumulator, returned from every exit
        for (int c = 1; c <= maxGapAll + 1; c++) {
            int cx = x + dx * c;
            int cz = z + dz * c;
            int g = c - 1; // open columns overflown to land AT column c

            // The column's node-level cell decides landing-vs-gap-vs-blocked — read its slot once.
            int p = ctx.packedAt(cx, y, cz);
            if (p == MovementContext.UNBUILT) return found; // unknown column — don't jump into/over it
            long fd = ctx.descriptorOf(cx, y, cz, p);

            if (ctx.standable(fd)) {
                // A standable node-level cell normally ENDS the direction (never overfly a ledge, v1) — it
                // can still be a landing: FLAT when its body is clear, or RISING when the "body blocker" at
                // y+1 is itself a standable floor (the common raised ledge: a platform floor at y+1 ON solid
                // ground — the floating-ledge form is the y+1 branch below).
                //
                // ISSUE-3 jump-over trigger: an obstacle worth flying OVER (rather than dead-ending the scan)
                // is treated as an OVERFLYABLE gap column so a landing BEYOND it is found — magma/campfire
                // jumped clean, snow's +1 rising landing reached. Two terms:
                //   • DAMAGING (caps-gated) — a hazard floor (magma, campfire).
                //   • topY < 12 — a SUNK block that TRAPS the walker: max ascend is JUMP_RISE = 20/16 = 1.25,
                //     so stepping onto a cell whose top is T/16 drops you to T/16 and climbing out onto a +1
                //     obstacle beyond (top 2.0) costs 2.0 − T/16, which is ≤ 1.25 exactly when T ≥ 12. So
                //     topY ≥ 12 is always escapable by a plain Ascend (no jump-over owed — and this EXCLUDES
                //     farmland / dirt-path (15) and soul sand's own height (14), avoiding discarded
                //     candidates); topY < 12 (snow layer 2, low slab) traps → trigger.
                // A SLOW floor is DELIBERATELY NOT overflown (owner decision): flying OVER a slow block (honey /
                // soul sand) is a scope reduction removed here — the honey wall-slide (HoneyBlock.doSlideMovement)
                // steals ~88% of horizontal momentum on a fast descent beside honey and drops the bot into the
                // void, and special-casing it is exactly the bandaid class the model avoids. A slow block in the
                // gap-line is now a non-overflyable obstacle: the scan terminates on it (soul sand/honey is
                // full-block-standable so it dead-ends the direction here), so A* re-routes to jump FROM the slow
                // block instead (Traverse onto it, then a reduced-envelope Parkour off it) or refuses. A crossing
                // move (WalkOff) covers the honey case separately.
                // A plain full non-damaging block (topY 16) — including a slow floor now — triggers NOTHING → the
                // scan terminates, byte-identical to v1 for ordinary terrain. The walk-ONTO-this-cell candidate
                // below is still emitted (priced with the landing floor's own hazard via floorHazardCost), so A*
                // gets the walk (and, for a slow block, the jump-FROM-it) and picks the cheaper.
                boolean trigger = (NavBlock.isDamaging(fd) && ctx.caps().takesDamage())
                        || NavBlock.topY(fd) < 12;
                // Overfly ONLY when the obstacle's body above is clear enough to fly through — the flat and
                // g==0 sub-cases. A raised ledge (y+1 a standable floor) or otherwise blocked body cannot be
                // overflown (you climb it or stop), so it keeps the v1 terminate even when triggering.
                boolean overfly = false;
                if (g >= 1) {
                    int flags = MovementContext.flagsOf(p);
                    if (ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK)) {
                        // Body proven clear in one bit test — a flat landing (rising is impossible: a
                        // standable y+1 would have zeroed the HEADROOM bits). Arc verified lazily here.
                        overfly = trigger; // clear body ⇒ a triggering obstacle can be flown over
                        if (g <= flatMax) {
                            long vs = verifyPrisms(ctx, x, y, z, dx, dz, verified, verifiedTransit, g);
                            if (vs != PRISM_BLOCKED) {
                                out.accept(cx, y, cz, COST[g] + Float.intBitsToFloat((int) vs)
                                        + ctx.bodyTransitCost(flags, cx, y, cz)
                                        + ctx.floorHazardCost(fd));
                                found |= DIR_EMITTED;
                            }
                        }
                    } else {
                        // Verify the real feet cell — again with no break folding.
                        int p1 = ctx.packedAt(cx, y + 1, cz);
                        if (p1 == MovementContext.UNBUILT) return found;
                        long d1 = ctx.descriptorOf(cx, y + 1, cz, p1);
                        if (ctx.passable(d1)) {
                            overfly = trigger; // feet cell clear ⇒ a triggering obstacle can be flown over
                            if (g <= flatMax) {
                                int p2 = ctx.packedAt(cx, y + 2, cz);
                                if (p2 != MovementContext.UNBUILT
                                        && ctx.passable(ctx.descriptorOf(cx, y + 2, cz, p2))) {
                                    long vs = verifyPrisms(ctx, x, y, z, dx, dz,
                                            verified, verifiedTransit, g);
                                    if (vs != PRISM_BLOCKED) {
                                        out.accept(cx, y, cz, COST[g] + Float.intBitsToFloat((int) vs)
                                                + ctx.bodyTransitCost(flags, cx, y, cz)
                                                + ctx.floorHazardCost(fd));
                                        found |= DIR_EMITTED;
                                    }
                                }
                            }
                        } else if (g <= riseMax && ctx.standable(d1)) {
                            // Raised-ledge rising form — verify the gap prisms, then the taller arc. The
                            // body above is BLOCKED (a standable y+1), so this obstacle is a ledge you climb,
                            // never overfly: overfly stays false (which also avoids re-emitting this same
                            // rising landing via the floating-ledge branch below).
                            long vs = verifyPrisms(ctx, x, y, z, dx, dz, verified, verifiedTransit, g);
                            if (vs != PRISM_BLOCKED
                                    && emitRising(ctx, out, x, y, z, dx, dz, c, g,
                                            Float.intBitsToFloat((int) vs), d1)) {
                                found |= DIR_EMITTED;
                            }
                        }
                        // else: body blocked and not a rising ledge — cannot overfly (overfly stays false).
                    }
                } else {
                    // g == 0: the obstacle is ADJACENT to the takeoff (no parkour walk candidate — Traverse
                    // owns the step onto it). A triggering adjacent obstacle is still overflown so a landing
                    // beyond it is found (the snow-pit challenge starts here); a blocked body above is
                    // rejected by the landing-beyond's own verifyPrisms, so no pre-read is owed here.
                    overfly = trigger;
                }
                if (!overfly) {
                    return found; // full-block ledge / raised ledge / un-flyable body — end the direction
                }
                // Triggered + body clear: treat this obstacle as an overflyable gap column and CONTINUE the
                // landing-beyond search. Skip the rising/falling detection below — those model an OPEN
                // node-level cell (you can neither rise from nor descend THROUGH a column plugged by this
                // standable obstacle); its y+1..y+3 transit prism is proven by the eventual landing's
                // verifyPrisms exactly like any gap column, and a standable cell (topY<=16) is always
                // overJumpable, so DIR_SAW_GAP is the only bookkeeping owed here.
                found |= DIR_SAW_GAP;
                continue;
            }
            // Node-level cell must be arc-safe as a FLOOR obstacle: open OR a no-taller-than-a-full-block
            // collision top (overJumpable) — the same rule the offset/diagonal scans use. This deliberately
            // admits a fluid gap cell: a 1-wide lava/water pool has no collision box, so the sprint arc
            // clears it with the hitbox always above the fluid (zero contact). Only a fence/wall (topY ≈ 24)
            // that pokes into the feet path blocks the jump. The body-arc prism (y+1..y+3) stays STRICT
            // passable below, so a TALL lava/water column is still rejected there — no wading through fluid.
            if (!ctx.overJumpable(fd)) return found;
            found |= DIR_SAW_GAP; // an OPEN gap column — arms the offset fallback if nothing ever emits

            // Floating-ledge RISING detection — the ONE cell above node level the lazy walk still
            // consults, and only when a rising landing is in envelope AND the gap cell's resident CRAWL
            // headroom bit can't already prove y+1 clear (in open air it proves it for free, so the
            // common case pays zero extra reads). A consulted y+1 that is blocked/unbuilt ends the
            // direction exactly as the eager prism did (nothing behind it could ever verify).
            if (g >= 1 && g <= riseMax
                    && !ctx.headroomProves(MovementContext.flagsOf(p), y,
                            MovementContext.HEADROOM_CRAWL)) {
                int p1 = ctx.packedAt(cx, y + 1, cz);
                if (p1 == MovementContext.UNBUILT) return found;
                long d1 = ctx.descriptorOf(cx, y + 1, cz, p1);
                if (!ctx.passable(d1)) {
                    // A standable floor at y+1 over an OPEN node-level cell is the floating-ledge form of
                    // the RISING landing; either way the flat/falling transit is blocked here, so the
                    // direction ends after it (v1 rule, preserved).
                    if (ctx.standable(d1)) {
                        long vs = verifyPrisms(ctx, x, y, z, dx, dz, verified, verifiedTransit, g);
                        if (vs != PRISM_BLOCKED
                                && emitRising(ctx, out, x, y, z, dx, dz, c, g,
                                        Float.intBitsToFloat((int) vs), d1)) {
                            found |= DIR_EMITTED;
                        }
                    }
                    return found;
                }
            }

            // Falling landings in THIS column: down-scan for the first standable floor (never through a
            // floor — Fall's rule), while some drop row still offers this gap. The descent is modeled
            // straight down the landing column (the v1/Fall approximation; the follower's drop control
            // holds the real arc to it). Emitting does NOT end the scan — the column stays overflyable.
            // The down-cells are DETECTION reads (envelope-capped); the arc's prisms — columns 1..c,
            // this column's own prism included (the conservative simplification, class Javadoc) — are
            // verified backwards only when a landing is actually found.
            if (g >= 1 && g <= fallGapCap) {
                float descTransit = 0f; // descended-cell surcharges, accumulated during detection
                for (int dr = 1; dr <= capsDrop; dr++) {
                    int fy = y - dr;
                    int pf = ctx.packedAt(cx, fy, cz);
                    if (pf == MovementContext.UNBUILT) break; // unknown below — don't drop into it
                    long fdd = ctx.descriptorOf(cx, fy, cz, pf);
                    if (ctx.standable(fdd)) {
                        // Landing body (fy+1, fy+2) is proven passable by the arc verification below for
                        // dr == 1 (node-level cell + prism) and by the descended cells just walked for
                        // deeper drops.
                        if (g <= env[ParkourEnvelope.FALL1 + dr - 1]) {
                            long vs = verifyPrisms(ctx, x, y, z, dx, dz, verified, verifiedTransit, c);
                            if (vs == PRISM_BLOCKED) return found; // nothing farther can verify either
                            verified = (int) (vs >>> 32);
                            verifiedTransit = Float.intBitsToFloat((int) vs);
                            float cost = RUNUP_COST + AIR_COST[g] + FALL_EXTRA[dr] + COMMIT_PENALTY
                                    + verifiedTransit + ctx.cellTransitCost(fd) + descTransit;
                            if (dr > safeFall) {
                                // Damage-as-cost, Fall's exact term: ≈1 HP per excess block × the unified
                                // ticks-per-HP knob (rare branch — a hurt landing — so the caps read is fine).
                                cost += (dr - safeFall) * ctx.caps().costPerHitpoint();
                            }
                            out.accept(cx, fy, cz, cost);
                            found |= DIR_EMITTED;
                        }
                        break; // only the highest landing in this column (never through a floor)
                    }
                    if (!ctx.passable(fdd)) break; // lava/partial below — no landing, still overflyable
                    descTransit += ctx.cellTransitCost(fdd); // a descended cell — Fall's column pricing
                }
            }
            // Column is (so far as consulted) a valid gap — keep walking node-level cells.
        }
        return found;
    }

    /** {@link #verifyPrisms} sentinel: a demanded prism cell was blocked or unbuilt — no landing whose
     *  arc reaches that deep can ever emit, so the caller ends (or is about to end) the direction. */
    private static final long PRISM_BLOCKED = -1L;

    /**
     * The lazy backwards arc verification (class Javadoc): extend the direction's verified-prism prefix
     * from {@code verified} through gap column {@code n}, proving each column's transit prism
     * ({@code y+1..y+3}) passable and accruing its pass-through surcharge column-by-column (the exact
     * eager summation order, so prices are unchanged). Returns the new cursor packed as
     * {@code (newVerified << 32) | floatBits(newTransit)} — primitives only, zero allocation — or
     * {@link #PRISM_BLOCKED} on a blocked/unbuilt cell (UNBUILT is as strict here as it was in the eager
     * walk: the cell was consulted, so it rejects). Demands are monotone ({@code n} never shrinks within
     * a direction — landing columns only grow), so with {@code n <= verified} this is a no-op returning
     * the cursor unchanged, and no prism cell is ever read twice per direction.
     */
    private static long verifyPrisms(MovementContext ctx, int x, int y, int z, int dx, int dz,
            int verified, float transit, int n) {
        while (verified < n) {
            int kx = x + dx * (verified + 1);
            int kz = z + dz * (verified + 1);
            int p1 = ctx.packedAt(kx, y + 1, kz);
            if (p1 == MovementContext.UNBUILT) return PRISM_BLOCKED;
            long d1 = ctx.descriptorOf(kx, y + 1, kz, p1);
            if (!ctx.passable(d1)) return PRISM_BLOCKED;
            int p2 = ctx.packedAt(kx, y + 2, kz);
            if (p2 == MovementContext.UNBUILT) return PRISM_BLOCKED;
            long d2 = ctx.descriptorOf(kx, y + 2, kz, p2);
            if (!ctx.passable(d2)) return PRISM_BLOCKED;
            int p3 = ctx.packedAt(kx, y + 3, kz);
            if (p3 == MovementContext.UNBUILT) return PRISM_BLOCKED;
            long d3 = ctx.descriptorOf(kx, y + 3, kz, p3);
            if (!ctx.passable(d3)) return PRISM_BLOCKED;
            // The column's body-prism surcharge, priced ONCE off the descriptors in hand (pure bit tests).
            transit += ctx.cellTransitCost(d1) + ctx.cellTransitCost(d2) + ctx.cellTransitCost(d3);
            verified++;
        }
        return ((long) verified << 32) | (Float.floatToRawIntBits(transit) & 0xFFFFFFFFL);
    }

    /**
     * Verify + emit a rising(+1) landing found at column {@code c} (floor {@code y+1} standable there).
     * The caller has already lazily verified the gap-column prisms via {@link #verifyPrisms} and passes
     * their surcharge as {@code transit}; the landing body ({@code y+2..y+3}) and the raised-arc row
     * ({@code y+4} over the takeoff column {@code k=0}, every gap column, and the landing column
     * {@code k=c} — see the clearance derivation in the class Javadoc) are verified HERE, lazily and
     * backwards, so a scan that never meets a rising ledge pays zero reads for the taller arc. The gap
     * columns' {@code y+4} cells are priced as transited body cells; the takeoff and landing {@code y+4}
     * are clearance-only, unpriced (Ascend precedent). Cold-ish (runs only when a rising floor actually
     * terminates a gap run), still zero-allocation. Returns whether a candidate was actually accepted
     * (feeds the caller's {@link #DIR_EMITTED} fallback bookkeeping).
     */
    private static boolean emitRising(MovementContext ctx, CandidateSink out, int x, int y, int z,
            int dx, int dz, int c, int g, float transit, long landDesc) {
        // Rise gate (start-surface-aware — the Ascend rule applied to the arc): the +1 landing is gained
        // only when the surface-to-surface rise fits one jump's budget, rise = 16 + landTopY − startSurf
        // sixteenths ≤ JUMP_RISE (20) (MovementContext.rise). A partial-height TAKEOFF floor eats the
        // deficit: from a slab (start top 8) onto a full-block ledge the rise is 24 > 20 — the arc apex
        // (start surface + 20/16) never reaches the landing top, however clear the prisms are.
        // floorSurface (standable → topY, else 16) keeps a non-standable-floor takeoff at its historical
        // geometry. Two cached reads, only on this rare (rising-ledge-found) path; a full-height start
        // onto any standable ledge (landTopY ≤ 16 ⇒ rise ≤ 16) passes unchanged.
        if (MovementContext.rise(1, ctx.topYOf(landDesc), ctx.floorSurface(x, y, z))
                > MovementContext.JUMP_RISE) {
            return false;
        }
        int cx = x + dx * c;
        int cz = z + dz * c;
        // Landing body: feet y+2, head y+3.
        int p2 = ctx.packedAt(cx, y + 2, cz);
        if (p2 == MovementContext.UNBUILT) return false;
        long d2 = ctx.descriptorOf(cx, y + 2, cz, p2);
        if (!ctx.passable(d2)) return false;
        int p3 = ctx.packedAt(cx, y + 3, cz);
        if (p3 == MovementContext.UNBUILT) return false;
        long d3 = ctx.descriptorOf(cx, y + 3, cz, p3);
        if (!ctx.passable(d3)) return false;
        // The raised arc's extra row: y+4 clear over takeoff (k=0) through landing (k=c).
        float riseTransit = 0f;
        for (int k = 0; k <= c; k++) {
            int kx = x + dx * k;
            int kz = z + dz * k;
            int p4 = ctx.packedAt(kx, y + 4, kz);
            if (p4 == MovementContext.UNBUILT) return false;
            long d4 = ctx.descriptorOf(kx, y + 4, kz, p4);
            if (!ctx.passable(d4)) return false;
            if (k >= 1 && k < c) riseTransit += ctx.cellTransitCost(d4); // gap columns only
        }
        // Cost: the flat arc credited RISE_EARLY_TICKS (the +1 floor intercepts the arc early), plus the
        // gap transit already accumulated, the raised row's transit, and the landing body priced off the
        // descriptors in hand (equivalent to the flags-gated bodyTransitCost, with zero extra reads).
        float cost = RUNUP_COST + (AIR_COST[g] - RISE_EARLY_TICKS) + COMMIT_PENALTY
                + transit + riseTransit + ctx.cellTransitCost(d2) + ctx.cellTransitCost(d3)
                + ctx.floorHazardCost(landDesc); // ISSUE-3: landing ON a damaging floor is priced too
        out.accept(cx, y + 1, cz, cost);
        return true;
    }

    /**
     * The {@code (c,±1)} offset FALLBACK probe for one cardinal (class Javadoc section) — called only
     * when the aligned pass saw an open gap but emitted nothing (and {@link #OFFSET_FALLBACK} is on).
     * On grounded terrain that is a genuine ragged rim and rare; for an OPEN-AIR standing node it is
     * every direction of every expansion — bounded at ≤ 2 floor reads per side, see the class Javadoc's
     * cost-honesty paragraph. Per side (±1 lateral, mirrored via the lateral unit
     * {@code (lx,lz) = (−dz·side, dx·side)}), walk the landing cells {@code c = 2..}{@link
     * #OFFSET_C_LIMIT} nearest-first, envelope-gated by the exact integer displacement test
     * {@code c² ≤ flatMax·(flatMax+2)} (⇔ {@code sqrt(c²+1) ≤ flatMax+1}, the flat row's reach — the
     * class Javadoc derivation). ONE floor read per shape until something decides the side:
     * <ul>
     *   <li>a STANDABLE cell is the side's one landing attempt ({@link #emitOffset}) and ends the side
     *       whether or not it accepts — nearest-first / never overfly; a farther shape's cover contains
     *       this column, where a clear-bodied ledge would just re-emerge as the landing and a blocked
     *       one rejects the arc, so ending early is equivalent and cheaper (covers are nested);</li>
     *   <li>a non-passable non-standable cell (fence) or an UNBUILT cell ends the side the same way —
     *       it sits in every farther shape's cover and would reject there;</li>
     *   <li>open air walks on to the next shape.</li>
     * </ul>
     * The fallback pass may re-read a handful of aligned-file cells the forward walk already resolved
     * (their slots are hot) — accepted: it runs only where the aligned scan came up empty, never on the
     * everyday expansion. Primitives only, zero allocation.
     */
    private static void probeOffsets(MovementContext ctx, int x, int y, int z, int dx, int dz,
            CandidateSink out, int flatMax, int maxGapAll) {
        int reach2 = flatMax * (flatMax + 2); // legality: c·c ≤ (flatMax+1)² − 1, integer-exact
        int cMax = Math.min(OFFSET_C_LIMIT, maxGapAll);
        for (int side = 1; side >= -1; side -= 2) {
            int lx = -dz * side;
            int lz = dx * side;
            for (int c = 2; c <= cMax; c++) {
                if (c * c > reach2) break; // beyond the flat row's center-to-center reach
                int tx = x + dx * c + lx;
                int tz = z + dz * c + lz;
                int p = ctx.packedAt(tx, y, tz);
                if (p == MovementContext.UNBUILT) break; // in every farther cover too — side over
                long fd = ctx.descriptorOf(tx, y, tz, p);
                if (ctx.standable(fd)) {
                    emitOffset(ctx, out, x, y, z, dx, dz, lx, lz, c, tx, tz,
                            MovementContext.flagsOf(p), fd);
                    break; // nearest-first: the side ends at its first standable cell, emitted or not
                }
                if (!ctx.passable(fd)) break; // fence at floor level — every farther cover rejects it
            }
        }
    }

    /**
     * Verify + emit one flat {@code (c,±1)} offset landing (floor {@code (tx,y,tz)} standable, its flags
     * in hand). Landing body first — the flat rule: the resident HEADROOM fast path, else read
     * {@code y+1..y+2}; no break folding, a blocked/unbuilt cell kills the candidate — then the shape's
     * swept columns BACKWARDS over the arc via the static {@link #OFFSET_COVER} table: per column the
     * floor cell must be arc-safe ({@link MovementContext#overJumpable} — the corner rule; geometry-only,
     * unpriced) and the transit prism {@code y+1..y+3} passable, priced per
     * cell off the read-once descriptors in table order (a fixed summation order). UNBUILT stays strict
     * for every consulted cell. Cost is the precomputed displacement-interpolated {@link #OFFSET_COST}
     * plus the accrued transit plus the landing's flags-gated {@link MovementContext#bodyTransitCost}
     * (the flat-landing precedent) plus its {@link MovementContext#floorHazardCost} (ISSUE-3: landing ON a
     * damaging floor is priced in the same {@code costPerHitpoint} currency). Cold-ish, zero allocation.
     */
    private static void emitOffset(MovementContext ctx, CandidateSink out, int x, int y, int z,
            int dx, int dz, int lx, int lz, int c, int tx, int tz, int flags, long floorDesc) {
        // Landing body (feet y+1, head y+2) — flags fast path, then the real cells.
        if (!ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK)) {
            int p1 = ctx.packedAt(tx, y + 1, tz);
            if (p1 == MovementContext.UNBUILT
                    || !ctx.passable(ctx.descriptorOf(tx, y + 1, tz, p1))) {
                return;
            }
            int p2 = ctx.packedAt(tx, y + 2, tz);
            if (p2 == MovementContext.UNBUILT
                    || !ctx.passable(ctx.descriptorOf(tx, y + 2, tz, p2))) {
                return;
            }
        }
        // The swept arc — every column the 0.6-wide hitbox crosses between takeoff and landing, from the
        // precomputed supercover table (the takeoff's own prism is the standing body + the y+3 head cell
        // already proven in candidates(); the landing's body was just proven above).
        int[] cover = OFFSET_COVER[c];
        float transit = 0f;
        for (int i = 0; i < cover.length; i += 2) {
            int kx = x + dx * cover[i] + lx * cover[i + 1];
            int kz = z + dz * cover[i] + lz * cover[i + 1];
            int pf = ctx.packedAt(kx, y, kz);
            if (pf == MovementContext.UNBUILT) return;
            long df = ctx.descriptorOf(kx, y, kz, pf);
            // Floor cell arc-safe: open/fluid, or solid no taller than a full block (arced over like flat
            // ground — the shared corner rule; a fence's topY ≈ 24 clips the feet path).
            if (!ctx.overJumpable(df)) return;
            for (int k = 1; k <= 3; k++) {
                int pk = ctx.packedAt(kx, y + k, kz);
                if (pk == MovementContext.UNBUILT) return;
                long dk = ctx.descriptorOf(kx, y + k, kz, pk);
                if (!ctx.passable(dk)) return;
                transit += ctx.cellTransitCost(dk); // priced off the descriptor in hand (aligned rule)
            }
        }
        out.accept(tx, y, tz, OFFSET_COST[c] + transit + ctx.bodyTransitCost(flags, tx, y, tz)
                + ctx.floorHazardCost(floorDesc)); // ISSUE-3: landing ON a damaging floor is priced too
    }

    /**
     * The phase-model jump: RUNUP (drive the line, sprint if the displacement wants it or the landing is a block up,
     * until past the takeoff edge) → TAKEOFF (hold jump until airborne) → AIRBORNE (full forward + sprint;
     * a FALLING arc hands off to drop-control once its centre clears the gap — see the phase comment for
     * the threshold derivation; flat/rising hold the arc inputs to touchdown so the eased-forward landing
     * settle can never bleed the momentum the gap needs) → LAND (re-centre, done once standing on the
     * target cell). All predicates positional; the landing Y comes straight from the to-cell, so the SAME
     * four phases execute flat, rising and falling jumps ({@code advanceWhen(grounded)} simply fires at
     * the higher/lower touchdown and
     * {@code done} tests {@code footY == ty + 1}). {@code resetWhen} re-runs from RUNUP when the bot is
     * physically back on the start cell after a balked jump. The guard is ARMED by the airborne drive and
     * DISARMED by the runup drive: takeoff's {@code advanceWhen} trips at {@link #TAKEOFF_EDGE} (~0.85
     * past centre) with {@code blockPosition()} still the start cell and vanilla onGround still true, so
     * an always-on guard would alias with the very state the jump must fire from — the runner checks
     * {@code resetWhen} before driving whenever the cursor has advanced, snapping back to RUNUP and
     * preempting {@code setJumping} every tick of the takeoff window. Landing in the gap is NOT a plan
     * phase: the follower's grounded-stall recovery arm re-anchors and replans from inside the gap.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final int ddx = tx - fx;
        final int ddz = tz - fz;
        // Fix 3: the FIRST gap-floor cell just past the takeoff lip (node level fy). A hazardous block here
        // (magma/lava/honey) forces the early takeoff below. Cardinal → one axis is 0; for an offset/diagonal
        // shape this is the diagonal first cell (the arc's own first over-flown column).
        final int gapX = fx + Integer.signum(ddx);
        final int gapZ = fz + Integer.signum(ddz);
        // The takeoff→landing line's NORMALIZED horizontal direction: the (c,±1) offset shapes make
        // (Δx,Δz) non-unit-axis, so the along-line progress projections below need a real unit vector.
        // ONE sqrt at plan BUILD time (cold — one MovePlan per waypoint step, the Pillar precedent); the
        // per-tick predicates stay multiply-adds. For a cardinal shape ux/uz degenerate to the old ±1
        // signum axes byte-for-byte (dist == |Δaxis|).
        final double dist = Math.sqrt((double) (ddx * ddx + ddz * ddz));
        final double ux = ddx / dist;
        final double uz = ddz / dist;
        final boolean falling = ty < fy;
        // Sprint decision (REACH-AWARE). A RISING jump always sprints (the +1 landing eats ~½ block of
        // reach). A FLAT / offset jump sprints at center-to-center displacement ≥ the flat 2-gap's 3.0 — the
        // cardinal g >= 2 rule, integer-exact as dx²+dz² ≥ 9 (the offset shapes interpolate it: (2,±1)=√5
        // walks, (3,±1)=√10 sprints). A FALLING jump is the exception: the drop buys extra airtime, so a
        // WALK already clears the shorter gaps, and the sprint-jump +0.2 takeoff boost then carries the bot
        // PAST a narrow ledge — the fall2 overshoot (trace-verified: a sprinted 2-gap fall lands ~1.4 blocks
        // past a 1-wide pad and falls, while a walk lands on it; air-braking can't shed sprint momentum in
        // the descent). So a falling jump sprints only when the gap is long enough to actually need the
        // reach (gap ≥ 3 — a walk's ≈2.9-block reach covers gaps 1–2 with margin); shorter falls walk.
        final int gap = Math.max(Math.abs(ddx), Math.abs(ddz)) - 1;
        final boolean sprint = ty > fy
                || (falling ? gap >= 3 : ddx * ddx + ddz * ddz >= 9);
        // Falling arcs only (all cardinal today — offset shapes are flat-only v1): the along-LINE
        // progress (from the takeoff cell centre) past which the bot's centre is OVER the landing column.
        // The landing centre sits at `dist` and its column begins ~0.5 before it — for a cardinal g-gap
        // this is exactly the old g + 0.5 (dist == g+1). From there the airborne drive hands off to
        // drop-control (see the phase Javadoc) — never earlier, so the full-forward reach that clears
        // the gap is untouched.
        final double gapCleared = dist - 0.5;
        // Regression-guard arm: true only while an arc is live (set airborne, cleared on a runup re-attempt).
        // Cold per-step allocation — a MovePlan is built once per waypoint step (the Pillar precedent).
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
                // Takeoff trigger: grounded AND the bot's along-axis progress past the start-cell centre
                // reaches TAKEOFF_EDGE — jump as late as possible without stepping off the lip. Fix 3: when
                // the first gap-floor cell is a takeoff hazard (magma/lava/honey), switch to the PREDICTIVE
                // early trigger so the center never crosses the lip onto it on a grounded tick.
                .advanceWhen(b -> {
                    if (!b.grounded()) return false;
                    double proj = ux * (b.x() - (fx + 0.5)) + uz * (b.z() - (fz + 0.5));
                    if (b.gapFloorHazardAt(gapX, fy, gapZ)) {
                        double vAlong = ux * b.velX() + uz * b.velZ();
                        return proj + HAZARD_TAKEOFF_TICKS * vAlong >= HAZARD_TAKEOFF_LOOKAHEAD;
                    }
                    return proj >= TAKEOFF_EDGE;
                });
        plan.phase("takeoff")
                .drive((b, v) -> {
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                    b.setJumping(true);
                })
                .advanceWhen(b -> !b.grounded());
        plan.phase("airborne")
                // FLAT/RISING: steerTowards, NOT recenterOnTarget, for the whole arc — the recenter drive
                // eases forward toward 0 near the target column, which kills the horizontal momentum the
                // jump lives on (the same-level touchdown arrives with the gap barely cleared, so there is
                // no momentum to bleed).
                // FALLING: two-stage. Full forward ONLY until the bot's centre passes the last gap column
                // (`gapCleared` — the reach that clears the gap is untouched); from there it is airborne
                // over the landing column with descent still to run, which is exactly Fall's situation, so
                // the drive hands off to Fall's drop-control (recenterOnTarget: proportional forward that
                // still pulls TOWARD the column centre but eases near it and pushes BACK past it). Without
                // the handoff, sprint momentum held to touchdown carries the bot ~0.3-0.5 b past the
                // landing cell — off a 1-wide ledge with a drop beyond (the momentum-overshoot the
                // fall-not-vertical work resolved for Fall). The handoff cannot undershoot: at centre
                // = g+0.5 the feet are still at/above node level for every shipped row (g+0.15 blocks at
                // ~0.28 b/t ⇒ ~t11.3 for g=3, vs feet crossing 0 at ~t12), and recenter keeps pushing
                // forward while short of the centre; the falling 4-gap (in the default envelope) meets
                // the threshold essentially at touchdown, degrading to the v1 drive.
                .drive((b, v) -> {
                    airborneOnce[0] = true; // arc is live → a grounded return to the start cell is a balk
                    if (falling && b.slipperinessAt(tx, ty, tz) >= SteerControl.PARKOUR_ICE_SLIP) {
                        // FALLING onto ICE (Phase 3) → the predictive servo for the WHOLE arc, exactly like
                        // flat/rising (NO gapCleared gate). The gate opens only ~1 tick before touchdown on the
                        // steep falling arc (measured: blue.fall.g4 descended at full sprint vx≈0.26 until proj
                        // 4.5 = one tick from landing, then slid off unbraked), far too late to bleed the
                        // momentum on a frictionless landing — the owner's #1 pathology. parkourAirborne handles
                        // the falling arc natively (predicts touchdown at the LOWER surface ty+1) and its own
                        // near-edge invariant is the gap guard: it only reverse-brakes when a full-reverse
                        // touchdown still lands at/beyond the near edge, so it accelerates while short (clears
                        // the gap) and brakes only as early as is safe — never into the gap. The aggressive
                        // flag tightens the near-edge margin (more runway + earlier brake) for the reach-heavy
                        // falling arc.
                        SteerControl.parkourAirborne(b, v, ux, uz, tx, ty, tz, sprint, true);
                    } else if (falling) {
                        // FALLING onto NON-ICE → Fall's open-loop recenter drop-control, BYTE-IDENTICAL to
                        // before (the stone fall trials falld*/fall*/ctl.fall must stay unchanged): full forward
                        // until the centre clears the last gap column, then recenter onto the landing column.
                        if (ux * (b.x() - (fx + 0.5)) + uz * (b.z() - (fz + 0.5)) >= gapCleared) {
                            b.setSprinting(false);
                            SteerControl.recenterOnTarget(b, v);
                        } else {
                            SteerControl.steerTowards(b, v);
                            b.setSprinting(sprint);
                        }
                    } else {
                        // FLAT / RISING: the predictive landing servo (replaces open-loop full-forward) — air-
                        // brake an overshoot, accelerate a shortfall, never brake short into the gap. Sprint held.
                        SteerControl.parkourAirborne(b, v, ux, uz, tx, ty, tz, sprint);
                    }
                })
                .advanceWhen(b -> b.grounded()); // hold the arc phase until touchdown
        plan.phase("land")
                // Brake to the desired point on the ground until grounded on the target cell. FLAT/RISING and
                // FALLING-onto-ICE use the predictive servo (grounded, the predictor returns the live along-
                // position → a reverse-brake toward the desired point, the ice-slide arrest); FALLING on NON-ICE
                // keeps Fall's recenter (byte-identical stone behaviour).
                .drive((b, v) -> {
                    if (falling && b.slipperinessAt(tx, ty, tz) < SteerControl.PARKOUR_ICE_SLIP) {
                        SteerControl.recenterOnTarget(b, v);
                    } else {
                        SteerControl.parkourAirborne(b, v, ux, uz, tx, ty, tz, sprint, falling);
                    }
                })
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == ty + 1 && b.footZ() == tz);
        return plan;
    }

    /** A jump is an irreversible airborne commitment — its landing cell being the goal must not preempt the
     *  jump mid-arc (the ice-STOP undershoot). See {@link Movement#commitsAcrossArrival()}. */
    @Override
    public boolean commitsAcrossArrival() {
        return true;
    }
}
