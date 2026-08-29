package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * Step down exactly one block to a diagonally-adjacent floor cell — the descending half of the dry 3-axis
 * pair (DESIGN-diagonal-vertical-moves.md; the {@link DiagonalAscend} class doc carries the arc's story).
 * {@link Diagonal}'s corner-clearance rule fused with {@link Descend}'s controlled step-down: walk off the
 * corner, let gravity supply the one-block drop, brake on the projected stop. Deeper drops stay
 * {@link Fall}'s job; the gap-with-a-drop is {@link WalkOff}'s (the honey edge case — owner ruling O4
 * keeps this move on Descend's servo shape, not WalkOff's ballistic cross).
 *
 * <p><b>No folded edits (owner ruling O2), same doctrine as {@link DiagonalAscend}:</b> destination floor
 * must already be standable (no {@code requireFloor} place arm, no trapdoor toggle arm), every transit and
 * corner cell already passable — refusals, never breaks. Per-axis edit routes are {@link Descend}+
 * {@link Traverse}'s job.
 *
 * <p><b>Swept volume (D3):</b> {@link Diagonal}'s corner rule at the START level ({@code y+1, y+2} on both
 * corner columns — no jump, so no vertical widening) plus {@link Descend}'s destination transit column
 * {@code (nx, y..y+2, nz)}, provable in one read via the dest floor's {@code HEADROOM_JUMP} bit. Corner
 * brushes priced at the full per-cell rate, Diagonal-style.
 */
public final class DiagonalDescend implements Movement {

    /**
     * Base cost, in <b>ticks</b> = {@link Diagonal#COST} ({@code FLAT_COST · √2} ≈ 6.55): a diagonal walk
     * step whose one-block drop is free (gravity is time the bot would spend walking anyway — {@link
     * Descend}'s ruling), composing Descend's "step-down costs one walk step" with {@link Diagonal}'s √2
     * ground distance. See {@link DiagonalAscend#COST} for the deliberate octile relationship (D1) — the
     * same note applies verbatim here.
     */
    public static final float COST = Traverse.FLAT_COST * 1.41421356f;

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground step-down — only while upright
        for (int[] d : DIAGONALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int dy = y - 1; // destination floor one below

            // CHEAPEST-FIRST (A/B 2026-08-29, CLIFFS +14% conviction): probe the landing column's FEET
            // cell before resolving the dest floor. On flat terrain — the overwhelmingly common pop — the
            // neighbour's own floor block sits at (nx, y, nz), so this ONE read rejects the direction;
            // the old ladder resolved the buried dest floor + flags + headroom first and paid ~2× per
            // pop. EMISSION-IDENTICAL: whenever the dest's HEADROOM_JUMP bit proves the column, this
            // cell is passable by implication, so a reject here can never kill an admitted candidate.
            if (!ctx.passable(nx, y, nz)) continue;

            // Destination floor must be built + ALREADY standable (no footing placement, no toggle — O2).
            int packed = ctx.packedAt(nx, dy, nz);
            if (packed == MovementContext.UNBUILT) continue;
            long dstDesc = ctx.descriptorOf(nx, dy, nz, packed);
            if (!ctx.standable(dstDesc)) continue;

            // The rest of the step-off transit (nx, y+1..y+2, nz) — proven by the dest's JUMP-level
            // HEADROOM bit (Descend's one-read rule), else read strictly (refusals, no breaks; the feet
            // cell was already read above).
            int flags = MovementContext.flagsOf(packed);
            if (!ctx.headroomProves(flags, nx, dy, nz, MovementContext.HEADROOM_JUMP)
                    && (!ctx.passable(nx, y + 1, nz) || !ctx.passable(nx, y + 2, nz))) {
                continue;
            }

            // Both corner columns at the START level (D3 — no jump, flat Diagonal's y+1..y+2 rule),
            // descriptors read once and reused for pricing. KNOWN RESIDUAL (review 2026-08-29): on an
            // off-center crossing the descending feet can transit the corner cell at START-FLOOR level
            // (nx,y,z)/(x,y,nz) — a cell this sweep neither clears nor prices. Verified benign for
            // CLEARANCE (a solid there is landable support inside the envelope's corner band, and
            // arrestCarryFrom's containment makes a corner-only free-fall unreachable); a passable
            // HAZARD there (web/fire/bush) is transited unpriced — a one-cell search-preference error
            // only, accepted v1 (DESIGN-diagonal-vertical-moves.md Known residuals).
            long c1 = ctx.descriptorAt(nx, y + 1, z);
            if (!ctx.passable(c1)) continue;
            long c2 = ctx.descriptorAt(nx, y + 2, z);
            if (!ctx.passable(c2)) continue;
            long c3 = ctx.descriptorAt(x, y + 1, nz);
            if (!ctx.passable(c3)) continue;
            long c4 = ctx.descriptorAt(x, y + 2, nz);
            if (!ctx.passable(c4)) continue;

            float cost = (ctx.isSlow(dstDesc) ? COST * Traverse.SLOW_COST_FACTOR : COST)
                    + ctx.floorHazardCost(dstDesc)
                    + ctx.bodyTransitCost(flags, nx, dy, nz) // landing body cells (the transit), zero-read
                    + ctx.cellTransitCost(c1) + ctx.cellTransitCost(c2)
                    + ctx.cellTransitCost(c3) + ctx.cellTransitCost(c4);
            out.accept(nx, dy, nz, cost);
        }
    }

    /**
     * ONE phase — {@link Descend}'s STEP with {@link Diagonal}'s 2×2 column admit and NO clear phase:
     * {@link Descend}'s CLEAR exists solely to establish its needs, and this move declares none (O2), so
     * the whole plan is the pure-locomotion step-off. Every Descend STEP element carries over: the
     * declarative carry arrest, the column-gated climbable hold, the medium-aware fluid branch, the stance
     * hold, {@code arriveOnTarget}'s projected-stop braking (owner ruling O4 — the Descend servo shape,
     * never WalkOff's ballistic cross), and the settled-not-grounded completion.
     *
     * <p><b>Envelope:</b> Descend's shape widened to the 2×2 — the from STAND (via {@code inWaypointCell}),
     * plus the target and both corner columns over the topY-aware band {@code [toFootY..fromFootY]}
     * ({@code fromFootY} on the target/corner columns is the LIP TRANSIT: stepping off the corner, the
     * centre crosses while the box is still grounded on the from-block's lip — Descend's longrun-8 rule,
     * legitimately on any of the three swept columns for a diagonal). The from column below its stand is
     * off-plan, exactly as in Descend. The drop itself is airborne-exempt.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // Contract tripwire (Ascend's broken-plan precedent): by contract a diagonal step exactly one down.
        if (ty != fy - 1 || Math.abs(tx - fx) != 1 || Math.abs(tz - fz) != 1) {
            MovePlan broken = new MovePlan();
            broken.failWhen(b -> true);
            return broken;
        }
        final boolean[] left = new boolean[1]; // reset-guard arm — set once the bot leaves the start floor
        final int bandLo = Math.min(fromFootY, toFootY);
        final int bandHi = Math.max(fromFootY, toFootY);
        MovePlan plan = new MovePlan();
        // INERT on a one-phase plan (PhaseRunner checks resetWhen only at cursor > 0) — set for uniformity,
        // Diagonal's precedent. A balked descend actually re-attempts through the DRIVE: the failWhen
        // admits the from-cell and the arrive servo keeps driving at the target.
        plan.resetWhen(b -> left[0]
                && b.grounded() && atWaypoint(b, fx, fromFootY, fz));
        plan.failWhen(b -> (b.grounded() || b.inWater() || b.inLava())
                && !inWaypointCell(b, fx, fromFootY, fz)
                && !(b.footX() == tx && b.footZ() == tz
                        && b.footY() >= bandLo && b.footY() <= bandHi)
                && !(((b.footX() == fx && b.footZ() == tz) || (b.footX() == tx && b.footZ() == fz))
                        && b.footY() >= bandLo && b.footY() <= bandHi));
        plan.phase("step")
                .arrestCarryFrom(fx, fz)   // the declarative carry gate — a chained step's cross-axis
                                           // carry would drift the bot off the corner during the walk-off
                .drive((b, v) -> {
                    if (!b.grounded() || b.footX() != fx || b.footZ() != fz) left[0] = true; // left start
                    // From a HANG the drop must wait until the bot is over the DESTINATION column
                    // (Descend's (55,173,256) rule). No-op off a climbable.
                    SteerControl.holdUntilOverTargetColumn(b, v);
                    if (b.inWater()) {
                        // Fluid keeps the medium-aware drive: the upright swim servo + holdDepth own a wet
                        // descend; arriveOnTarget's drag model is the AIR/GROUND pair, not water's.
                        SteerControl.drive(b, v);
                        return;
                    }
                    SteerControl.holdClimbableStance(b, v, true);
                    // ARRIVE, not a position deadband (Descend's (105,91,170) ruling): servo the PROJECTED
                    // stopping point so the brake starts from the first airborne tick.
                    SteerControl.arriveOnTarget(b, v);
                })
                // SETTLED, not grounded (Descend's (58,171,254) vine ruling): a destination cell holding a
                // climbable supports the bot without ever grounding it.
                .done(b -> b.settled()
                        && atWaypoint(b, tx, toFootY, tz));
        return plan;
    }
}
