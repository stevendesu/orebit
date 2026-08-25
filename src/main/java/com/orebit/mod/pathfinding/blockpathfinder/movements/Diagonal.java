package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * Walk to a diagonally-adjacent floor cell on the SAME level (MOVEMENT-DESIGN.md §2, Tier 1) — the move
 * that turns the search from 4-connected into 8-connected. On open ground this is the single biggest
 * search-efficiency lever: a 4-connected grid must zig-zag (Manhattan) to cover diagonal distance,
 * expanding ~2× the nodes and producing staircased routes, whereas a diagonal step covers one cell in
 * each of x and z for a cost of {@code √2} (so the matching heuristic is octile — see {@link
 * com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder#heuristic}).
 *
 * <p><b>Corner clearance (no wall-clipping).</b> Cutting a diagonal across a corner passes the bot's body
 * through BOTH orthogonal-adjacent columns, so this move requires the feet+head cells of the destination
 * <i>and</i> of both corner columns to be clear. Requiring both corners (rather than "one open side")
 * is the conservative choice that never squeezes the bot through a solid corner — matching vanilla's
 * inability to move diagonally between two blocks.
 *
 * <p><b>First cut: no break/place, flat only.</b> Unlike {@link Traverse}/{@link Ascend}/{@link Descend}
 * this move folds no edits and does not change height — it emits only a cleanly-walkable diagonal. (A
 * diagonal that also breaks a corner or steps up/down one is a later refinement; this is the open-ground
 * speed primitive.)
 *
 * <p><b>Rise gate (owner-ratified bug fix, DESIGN-trapdoors.md §6).</b> Same-block-level is not
 * automatically walkable: the lip between the two floors' REAL tops must fit the auto-step, exactly as
 * {@link Traverse}'s flat gate — {@code rise(0, destTopY, startTopY) ≤ STEP_ASSIST_MAX_RISE}. Without it
 * this move emitted physically unwalkable plate/carpet → full-block diagonals (a 13–15/16 lip no
 * auto-step clears). Plain {@code topY} on both ends (no diagonal stair directionality — a bottom stair's
 * 16 is the conservative reading); a negative lip (stepping DOWN onto a plate) passes as always.
 */
public final class Diagonal implements Movement {

    /**
     * Diagonal step cost (ticks) = {@link Traverse#FLAT_COST} · √2 — walking one cell in each of x and z
     * covers √2 blocks of ground, so it costs √2 walk-ticks (≈ {@code 4.633 · 1.414 ≈ 6.55}). Derived from
     * {@code FLAT_COST} so it re-scales automatically with the walk ruler; matches Baritone's diagonal
     * ({@code WALK_ONE_BLOCK_COST · SQRT_2}). The octile heuristic's face term uses the same √2 (see {@link
     * com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}), so a clean diagonal is heuristic-exact.
     */
    public static final float COST = Traverse.FLAT_COST * 1.41421356f;

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground walk — only while upright
        // The START surface height (sixteenths) for the rise gate below — floorSurface's convention (a
        // non-standable float node's floor reads 16), read once per expansion, cache-served.
        final long startDesc = ctx.descriptorAt(x, y, z);
        final int startTopY = ctx.standable(startDesc) ? ctx.topYOf(startDesc) : 16;
        for (int[] d : DIAGONALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Destination floor must be built + standable (no footing-placement on a diagonal here).
            int packed = ctx.packedAt(nx, y, nz);
            if (packed == MovementContext.UNBUILT) continue;
            long dstDesc = ctx.descriptorOf(nx, y, nz, packed);
            if (!ctx.standable(dstDesc)) continue;

            // Rise gate (class doc; the ratified fix): the same-level lip between the two real floor tops
            // must fit the auto-step — a plate/carpet → full-block diagonal (13–15/16) is unwalkable.
            if (MovementContext.rise(0, ctx.topYOf(dstDesc), startTopY)
                    > MovementContext.STEP_ASSIST_MAX_RISE) {
                continue;
            }

            // Destination body (feet + head) clear — fast-path via the resident HEADROOM bit, else read.
            int flags = MovementContext.flagsOf(packed);
            if (!ctx.headroomProves(flags, nx, y, nz, MovementContext.HEADROOM_WALK)
                    && (!ctx.passable(nx, y + 1, nz) || !ctx.passable(nx, y + 2, nz))) {
                continue;
            }

            // Both corner columns' body cells must be clear, or the diagonal clips a wall corner. Read
            // each corner descriptor ONCE (the old boolean passable(x,y,z) form did the same read) so the
            // pass-through hazard/slow surcharge below reuses it — the body clips both corner columns, so a
            // fire / web / bush corner is priced like a transited cell (conservative: a corner brush is
            // charged the full per-cell rate).
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
                    + ctx.bodyTransitCost(flags, nx, y, nz) // destination body, via the resident flag bits
                    + ctx.cellTransitCost(c1) + ctx.cellTransitCost(c2)
                    + ctx.cellTransitCost(c3) + ctx.cellTransitCost(c4);
            out.accept(nx, y, nz, cost);
        }
    }

    /**
     * The phase-model execution plan — Diagonal's conversion off the bare {@code steer} path (owner ruling
     * 2026-08-01). It was one of only three movements left unconverted, and the gap was not cosmetic: a
     * steer-only move has <b>no validity envelope</b> and <b>no carry arrest</b>, so a diagonal that ends
     * off-lane reports nothing and the error surfaces one step later on whatever move DOES have an
     * envelope. Convicted on the flagship at {@code (58,113,160)}: {@code Diagonal wp21} targeted
     * {@code (58,·,159)}, the bot settled on {@code z=160}, said nothing, and the following
     * {@code Traverse wp22} — correctly framed from {@code z=159} — took the fail&rarr;HOLD for it.
     *
     * <p><b>ONE phase, and deliberately NO needs.</b> {@link #candidates} folds no edits at all: it REFUSES
     * the edge unless the destination floor is already standable and all six body cells (destination feet /
     * head plus both corner columns) are already passable — it never prices a break or a place. Declaring
     * {@code Need.AIR} here would let the executor mine cells the search never paid for, so the geometry
     * stays a precondition and a changed world is caught by the envelope instead. The value of converting
     * is the envelope and the arrest, not the reconcile.
     *
     * <p><b>The envelope admits four columns, not two.</b> A diagonal's foot cell legitimately transits a
     * CORNER column mid-step — that is exactly why {@code candidates} demands both corners be clear — so
     * {@code (fx,tz)} and {@code (tx,fz)} are on-plan. Settled anywhere outside that 2×2, or off the step's
     * height band, means the bot has left the move's model. The band is {@code [fromFootY..toFootY]}: equal
     * for a flat diagonal, but written as a band so a partial-top destination (whose feet seat lower than
     * the floor+1 cell) stays admitted.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        if (ty != fy) return null;                                  // Diagonal is strictly flat
        final int ddx = tx - fx;
        final int ddz = tz - fz;
        if (Math.abs(ddx) != 1 || Math.abs(ddz) != 1) return null;  // exactly one cell on BOTH axes

        final int bandLo = Math.min(fromFootY, toFootY);
        final int bandHi = Math.max(fromFootY, toFootY);

        MovePlan plan = new MovePlan();
        // Physically fell back to the start cell — re-attempt from phase 0. Inert on a one-phase plan; set
        // for uniformity with the other converted moves.
        plan.resetWhen(b -> b.grounded()
                && b.footX() == fx && b.footY() == fromFootY && b.footZ() == fz);
        plan.failWhen(b -> {
            if (!(b.grounded() || b.inWater() || b.inLava())) {
                return false;                                       // mid-step airborne is not a verdict
            }
            // LOWER BOUND IS CONTINUOUS, NOT CELL-QUANTISED (2026-08-25) — the same correction Traverse
            // took on 2026-08-21, for the same stance and the same reason. A Diagonal entered off a
            // CLIMBABLE TOP-OUT hovers on the vine's top rather than standing on it, and dips sub-cell
            // between jump presses; footY() is a cell index, so a ~0.1 dip reads as a whole cell of error
            // against a band that for a flat diagonal (fromFootY == toFootY) has ZERO room below. Allow
            // exactly what the physics recovers on its own and not a block more.
            //
            // The upper bound stays a CELL test: being lifted off the line is a different failure with no
            // self-recovering transient behind it (and off a climbable it is the involuntary-climb ratchet,
            // which holdUntilOverTargetColumn already owns).
            if (b.y() < bandLo - Traverse.STEP_ASSIST_RECOVERY || b.footY() > bandHi) {
                return true;                                        // fell off / was lifted off the step
            }
            final int bx = b.footX();
            final int bz = b.footZ();
            return !((bx == fx || bx == tx) && (bz == fz || bz == tz)); // outside the swept 2x2
        });
        plan.phase("cross")
                // THE FIX: a diagonal entered with cross-axis carry from the previous step slides out of
                // its 2x2 during the ticks ground friction needs to bleed it. Arrest first, commit after.
                .arrestCarryFrom(fx, fz)
                // The default drive is SteerControl::drive — full-throttle locomotion with no stance
                // awareness. On a climbable that IS the involuntary-climb ratchet: vanilla turns a blocked
                // press into vy=+0.2, so a diagonal that clips a corner while the feet are in a vine climbs
                // instead of crossing. Measured 2026-08-05 at (57,172,255): fwd=1.00, jump=false,
                // hcol=true, horizontal dm EXACTLY 0.0000 and dm.y=+0.1176 — the entire press converted to
                // altitude — carrying the bot 172.2 -> 175.0 into a leaf ceiling three blocks above its
                // target. holdUntilOverTargetColumn keeps the stance until the bot is over the destination
                // and then lets go; it is inert off a climbable, so ordinary ground diagonals are unchanged.
                .drive((b, v) -> {
                    SteerControl.holdUntilOverTargetColumn(b, v);
                    SteerControl.drive(b, v);
                    // WALK OFF THE TOP OF A CLIMBABLE (2026-08-25). Traverse has priced and executed this
                    // stance since 2026-08-21 (CLIMBABLE_TOP_COST + atopClimbable + needFootingOrClimbable
                    // + this same dip recovery); Diagonal had the pricing available to the planner but none
                    // of the execution, so a route that climbed a vine and then stepped off it DIAGONALLY
                    // simply let go. Convicted on the 2026-08-25 long flagship at (662,70,616): a Climb
                    // arrived hanging (grounded=false climbable=true, toFloorTopY=0 — the "floor" IS the
                    // vine), the next step planned Diagonal from-floor=(663,70,616) with
                    // fromFootStandable=false takeoffTopY=0, and the bot dropped 71.001 -> 70.011 until its
                    // validity envelope fired and held for the remaining 42k ticks.
                    //
                    // Ordering matches Traverse exactly: drive owns forward/yaw, this owns JUMP. It is
                    // inert off a climbable (onClimbable() is a FEET-block test, false while hovering ON
                    // TOP) and self-arms only once the body has dipped INTO the vine, then presses jump to
                    // bob back up — and its own b.y() >= ty() guard stops it ratcheting above the step, so
                    // it cannot re-create the involuntary-climb bug holdUntilOverTargetColumn exists for.
                    SteerControl.climbableDipRecover(b, v);
                })
                // SETTLED, not grounded — the same widening Descend needed (2026-08-03). A diagonal whose
                // destination cell holds a vine is never grounded there, so a grounded-only guard is
                // unsatisfiable, the move never completes, and it keeps driving — which is what fed the
                // ratchet above. settled() still rejects a ballistic transit.
                .done(b -> b.settled() && atWaypoint(b, tx, toFootY, tz));
        return plan;
    }
}
