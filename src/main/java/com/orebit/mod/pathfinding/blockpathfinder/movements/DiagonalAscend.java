package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * Jump to a diagonally-adjacent floor cell ONE level up — the dry 3-axis move
 * (DESIGN-diagonal-vertical-moves.md; DESIGN-region-corner-crossing-v2.md §2.1's named fast-follow, the
 * move that makes the region tier's vertex corner chain realizable). {@link Diagonal}'s corner-clearance
 * rule fused with {@link Ascend}'s jump arithmetic: the bot walks toward the corner while holding jump and
 * lands one cell over on both horizontal axes and one up.
 *
 * <p><b>No folded edits, ever (owner ruling O2).</b> Grid-aligned edits are by necessity per-axis, and a
 * per-axis edit sequence is exactly {@link Ascend}+{@link Traverse}'s job — so this move REFUSES unless the
 * destination floor is already standable and every swept cell already passable. Like {@link Diagonal}, the
 * geometry is a precondition and a changed world is the envelope's business; declaring needs here would let
 * the executor mine cells the search never paid for. No same-level arm, no trapdoor arm, no build arm (D5).
 *
 * <p><b>Ownership window (owner ruling O3, mirroring {@link Ascend}'s carve-outs).</b> Emits only for
 * {@code STEP_ASSIST_MAX_RISE < rise(1, destTop, startTop) ≤ JUMP_RISE}. Above, one jump cannot gain it.
 * Below is the KNOWN UNOWNED BAND (D4): cardinal Ascend defers {@code rise ≤ 9} to Traverse's step-assist,
 * but no diagonal move emits a {@code dy=+1} small-rise step (e.g. full block → slab-one-up diagonally,
 * rise 8) — a pre-existing hole (never routable before this move existed either), deliberately not closed
 * here and pinned by test so closing it later is a visible change. Plain {@code topY} both ends — no
 * diagonal stair directionality, {@link Diagonal}'s own conservative-16 reading (a diagonal edge has no
 * single facing for {@link MovementContext#directionalTopY} to resolve).
 *
 * <p><b>Corner sweep is {@code y+1..y+3}, strict passable (D2).</b> One row taller than walking
 * {@link Diagonal}'s {@code y+1..y+2} — the jump arc carries the feet through the corner columns' second
 * row and the head to {@code +3.05} — and capped at {@code y+3} by {@link Ascend}'s own truncated-apex
 * argument (its source-column {@code HEADROOM_JUMP} bar): a ceiling at the top of {@code y+3} caps feet at
 * {@code +1.2}, still enough for a full-block rise, and the 0.05 apex-head poke into {@code y+4} is the
 * same one cardinal Ascend accepts over its own columns today. {@link DiagonalParkour}'s {@code y+1..y+4}
 * is the BALLISTIC-gap contrast (full apex mid-gap at sprint speed) and deliberately not copied. Corner
 * brushes are priced at the full per-cell rate, Diagonal-style.
 */
public final class DiagonalAscend implements Movement {

    /**
     * Base cost, in <b>ticks</b> = {@link Diagonal#COST} ({@code FLAT_COST · √2} ≈ 6.55): a diagonal walk
     * step whose jump overlaps the forward motion, composing {@link Ascend}'s "walk forward while jumping
     * costs one walk step" with {@link Diagonal}'s √2 ground distance. NOTE the octile relationship (D1):
     * the heuristic's 3-axis term is {@code FLAT_COST · √3} ≈ 8.02, so h mildly over-estimates this step —
     * deliberately, and it is the SAME relationship cardinal {@link Ascend} (4.633) already has to its
     * 2-axis octile term (6.55): the heuristic's own doctrine is "verticality carries no special penalty…
     * base cost = a walk step" and the search is weighted/greedy by design, so a free height gain on
     * existing terrain prices below its octile term. Not a new admissibility class.
     */
    public static final float COST = Traverse.FLAT_COST * 1.41421356f;

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        // Hoisted takeoff gates — Ascend's set verbatim (a diagonal jump-up launches the same jump).
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // jump-up — only while upright
        if (ctx.caps().jumpHeight() < 1) return;
        if (ctx.reducesJump(x, y, z)) return;    // honey-block floor: the jump apex clears nothing
        if (ctx.noJumpFromBody(x, y, z)) return; // cobweb body cell: the stuck multiplier kills take-off
        if (!ctx.solidFooting(x, y, z)) return;  // R1: no jump launches from climbable stances
        // Takeoff head-clearance (y+3) as a REFUSAL, not Ascend's requireAir fold (O2): proven by the
        // source HEADROOM_JUMP bit, else read directly — clearance-only, unpriced (Ascend's convention).
        final int srcFlags = ctx.flagsAt(x, y, z);
        if (!ctx.headroomProves(srcFlags, x, y, z, MovementContext.HEADROOM_JUMP)
                && !ctx.passable(x, y + 3, z)) {
            return;
        }
        final int uy = y + 1;
        final long startDesc = ctx.descriptorAt(x, y, z);
        final int startTopY = ctx.standable(startDesc) ? ctx.topYOf(startDesc) : 16;

        for (int[] d : DIAGONALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Destination floor one up must be built + ALREADY standable (no toggle/build arms — D5).
            int packed = ctx.packedAt(nx, uy, nz);
            if (packed == MovementContext.UNBUILT) continue;
            long dstDesc = ctx.descriptorOf(nx, uy, nz, packed);
            if (!ctx.standable(dstDesc)) continue;

            // The O3 ownership window, plain topY both ends (class doc): below is the D4 unowned band,
            // above is taller than one jump gains.
            int rise = MovementContext.rise(1, ctx.topYOf(dstDesc), startTopY);
            if (rise <= MovementContext.STEP_ASSIST_MAX_RISE) continue; // D4: unowned diagonal small-rise
            if (rise > MovementContext.JUMP_RISE) continue;            // taller than one jump gains

            // Destination body (feet + head over the raised floor) — Diagonal's pattern at uy.
            int flags = MovementContext.flagsOf(packed);
            if (!ctx.headroomProves(flags, nx, uy, nz, MovementContext.HEADROOM_WALK)
                    && (!ctx.passable(nx, uy + 1, nz) || !ctx.passable(nx, uy + 2, nz))) {
                continue;
            }

            // Both corner columns swept y+1..y+3 (D2), descriptors read once and reused for pricing —
            // Diagonal's read-once form, one row taller for the jump arc.
            long c1 = ctx.descriptorAt(nx, y + 1, z);
            if (!ctx.passable(c1)) continue;
            long c2 = ctx.descriptorAt(nx, y + 2, z);
            if (!ctx.passable(c2)) continue;
            long c3 = ctx.descriptorAt(nx, y + 3, z);
            if (!ctx.passable(c3)) continue;
            long c4 = ctx.descriptorAt(x, y + 1, nz);
            if (!ctx.passable(c4)) continue;
            long c5 = ctx.descriptorAt(x, y + 2, nz);
            if (!ctx.passable(c5)) continue;
            long c6 = ctx.descriptorAt(x, y + 3, nz);
            if (!ctx.passable(c6)) continue;

            float cost = (ctx.isSlow(dstDesc) ? COST * Traverse.SLOW_COST_FACTOR : COST)
                    + ctx.floorHazardCost(dstDesc)
                    + ctx.bodyTransitCost(flags, nx, uy, nz) // destination body, via the resident flag bits
                    + ctx.cellTransitCost(c1) + ctx.cellTransitCost(c2) + ctx.cellTransitCost(c3)
                    + ctx.cellTransitCost(c4) + ctx.cellTransitCost(c5) + ctx.cellTransitCost(c6);
            out.accept(nx, uy, nz, cost);
        }
    }

    /**
     * Grounded-gated exact match — {@link Ascend#reached}'s idiom, adopted for the same reason: the steer
     * is a HELD JUMP, and the ungated default fires on the very tick the feet block first matches, while
     * that held input has just launched a fresh hop off the target cell.
     */
    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz, Movement next) {
        return b.grounded() && atWaypoint(b, wx, wy, wz) && Movement.teedUp(b, wx, wy, wz, next);
    }

    /**
     * ONE phase — {@link Ascend}'s CLIMB with {@link Diagonal}'s 2×2 column admit and NO build phase
     * (candidates folds no edits, so there is nothing to establish; the geometry is a precondition and a
     * changed world is caught by the envelope). Every Ascend element carries over: the carry arrest before
     * the jump, the climbable-transit jump hold ({@code footY < landFootY}), the launch-armed reset, the
     * grounded-gated completion.
     *
     * <p><b>The envelope admits four columns with per-column bands.</b> The from column keeps Ascend's
     * stand-to-landing feet band; the TARGET column its {@code [landFootY−1, landFootY]} (the face-press
     * transit); the two CORNER columns the from band {@code [fromFootY, landFootY]} — the body legitimately
     * sweeps them between takeoff and landing heights, and settled DEEPER in a corner column (fell off the
     * move) is off-plan. LOWER bounds cell-quantised, Ascend's convention (logged choice: Diagonal's
     * continuous climbable-dip lower bound is a flat-move top-out concern; this move's takeoff refuses
     * climbable stances outright via solidFooting, so the dip transient cannot arise at the start).
     * UPPER bounds continuous with {@link Diagonal#CORNER_LIFT_MAX} of headroom since 2026-08-30 — the
     * corner-lift rule; see the failWhen.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // Contract tripwire (Ascend's broken-plan precedent — detection, not recovery): by contract a
        // diagonal step exactly one up. No same-level arm exists (D5), so ty == fy+1 strictly.
        if (ty != fy + 1 || Math.abs(tx - fx) != 1 || Math.abs(tz - fz) != 1) {
            MovePlan broken = new MovePlan();
            broken.failWhen(b -> true);
            return broken;
        }
        final int landFootY = toFootY;
        final boolean[] launched = new boolean[1]; // reset-guard arm (Parkour's airborneOnce precedent)
        MovePlan plan = new MovePlan();
        // INERT on a one-phase plan (PhaseRunner checks resetWhen only at cursor > 0) — set for uniformity,
        // Diagonal's precedent. A balked ascend actually re-attempts through the DRIVE: the failWhen admits
        // the from-cell, and the held jump re-fires whenever footY < landFootY.
        plan.resetWhen(b -> launched[0]
                && b.grounded()
                && atWaypoint(b, fx, fromFootY, fz));
        plan.failWhen(b -> {
            if (!(b.grounded() || b.inWater() || b.inLava())) {
                return false; // mid-jump airborne is not a verdict
            }
            final int bx = b.footX();
            final int bz = b.footZ();
            final int by = b.footY();
            // EVERY column's UPPER bound gains Diagonal.CORNER_LIFT_MAX of continuous headroom (owner
            // ruling 2026-08-30, Diagonal's village-path corner-lift rule — pinned here by the pathup
            // card). The candidate's own y+1..y+3 corner sweep bounds in-2×2 corner floors at the FROM
            // level (rest ≤ fromFootY+1.0 = landFootY — the old cell band happened to cover it), but a
            // 15/16 LANDING (resting landFootY+0.938) straddling a full block just BEYOND the 2×2 — the
            // next hop's corner terrain, exactly the pathdiag shape one level up — rests the box at
            // exactly landFootY+1.000, a 1/16 rise the old cell tests read as a whole-cell departure.
            // Lower bounds unchanged: the face-press transit floor and "settled deeper in a corner
            // column fell off the move" both keep their cell tests.
            if (bx == tx && bz == tz) {
                // face-press transit + the landing stand (+ the corner-lift headroom)
                return !(by >= landFootY - 1 && b.y() <= landFootY + Diagonal.CORNER_LIFT_MAX);
            }
            if ((bx == fx || bx == tx) && (bz == fz || bz == tz)) {
                // from column + both corner sweeps (+ the same headroom)
                return !(by >= fromFootY && b.y() <= landFootY + Diagonal.CORNER_LIFT_MAX);
            }
            return true;                                          // settled outside the 2×2 — off-plan
        });
        plan.phase("climb")
                // Align before jumping (Ascend's precedent): cross-axis carry from the previous step
                // launches off-lane and grounds outside the admitted columns — a permanent fail→HOLD.
                .arrestCarryFrom(fx, fz)
                .drive((b, v) -> {
                    if (!b.grounded()) launched[0] = true;      // arm the reset only once off the ground
                    SteerControl.steerTowards(b, v);
                    // CLIMB FIRST, TRANSLATE AFTER (Ascend's climbable-transit discriminator): while the
                    // feet transit a climbable below the landing, horizontal input only ejects the bot.
                    if (b.onClimbable() && b.footY() < landFootY) {
                        b.setForward(0.0f);
                    }
                    // Hold the jump until GROUNDED AT THE WAYPOINT — not until the landing's foot CELL
                    // (owner-directed fix 2026-08-30, pinned by the pathup card's drive livelock). The
                    // old cell gate (footY() < landFootY) is 15/16-blind: a partial landing seats its
                    // feet in its own floor cell, so any support at the landing's cell-floor height —
                    // hop corner terrain, exactly where a short arc grounds — satisfies the gate while
                    // the bot still stands 0.938 BELOW the landing rest. Measured: 1150 ticks of
                    // fwd=1.00 jump=false hcol=true wall-pressing the path block's face, envelope
                    // silent (the support is inside the admitted band — correctly). On FULL blocks a
                    // short landing keeps footY < landFootY and the re-jump wall-hops the face, which
                    // is why this never fired before partial floors. The waypoint gate re-fires the
                    // jump from ANY admitted short stance and still releases on the arrival tick
                    // (grounded at the waypoint), which was the old predicate's other job.
                    b.setJumping(!(b.grounded() && atWaypoint(b, tx, landFootY, tz)));
                })
                .done(b -> b.grounded()
                        && atWaypoint(b, tx, landFootY, tz));
        return plan;
    }
}
