package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Step down exactly one block to a cardinal-adjacent floor cell (MOVEMENT-DESIGN.md §2, Tier 1) — the
 * gentle counterpart to {@link Ascend}. The bot walks off the edge into the neighbour column and drops
 * a single block; no jump, always safe. Deeper drops are {@link Fall}'s job.
 *
 * <p>The step-off transit is the three cells {@code (nx, y..y+2, nz)} — head clearance stepping off, the
 * transit, and the new head — which are exactly the destination floor's body column. So the dest's
 * {@code JUMP}-level HEADROOM bit proves the transit clear in a single read; where it can't (near a
 * section face, or a block in the way), the cells are read and folded into a break-set under the
 * {@code RISKY_EDIT} gate.
 *
 * <p><b>Place modifier (MOVEMENT-DESIGN §1, decision 1).</b> When there's no footing one block down, a
 * throwaway floor is <i>placed</i> against the wall to descend onto (the counterpart to {@link Ascend}'s
 * staircase-up). Repeated Descend+place builds a staircase down a sheer drop the bot can't safely
 * {@link Fall} — completing controlled 3D descent through the existing kinds.
 */
public final class Descend implements Movement {

    /**
     * Base cost, in <b>ticks</b> = one walk step ({@link Traverse#FLAT_COST}): a flat step plus a free
     * one-block drop (gravity is "free" time the bot would spend walking anyway), so descending existing
     * terrain costs no more than a Traverse — matching Baritone's {@code MovementDescend} traversal term.
     * A folded place/break (building/digging a step where there's no terrain) adds its own real ticks.
     */
    public static final float COST = Traverse.FLAT_COST;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground step-down — only while upright
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int dy = y - 1; // destination floor one below

            // Destination floor (nx,dy,nz) is read both standable and flags — resolve its slot once.
            int packed = ctx.packedAt(nx, dy, nz);
            if (packed == MovementContext.UNBUILT) continue;

            long dstDesc = ctx.descriptorOf(nx, dy, nz, packed);
            boolean dstStandable = ctx.standable(dstDesc);
            int flags = MovementContext.flagsOf(packed);
            EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
            // Footing: step onto the block below, or BUILD A STEP DOWN — place a throwaway floor one down
            // against the wall and descend onto it (if the bot may place and the spot is placeable).
            if (!dstStandable) e.requireFloor(nx, dy, nz);
            // The step-off transit (nx, y..y+2, nz) is the dest floor's body column; clear it through the
            // dest's JUMP-level HEADROOM, else read/break the three cells under the RISKY_EDIT gate.
            if (!ctx.headroomProves(flags, dy, MovementContext.HEADROOM_JUMP)) {
                e.requireAir(nx, y + 2, nz); // head clearance stepping off
                e.requireAir(nx, y + 1, nz); // transit feet / new head
                e.requireAir(nx, y, nz);     // new feet
            }
            if (e.valid()) {
                // Slow-FLOOR surcharge on the landing (same rule as Traverse/Diagonal; a PLACED step-down
                // floor reads as the conjured cube, never slow) plus the pass-through hazard/through-slow
                // surcharge for the landing body cells (nx, y-1's body = y, y+1 — the transit), zero-read
                // when the dest flag bits are clear; the edit-folding form breaks through a bush/web where
                // that's cheaper. The step-off head cell (y+2) is clearance-only.
                float cost = COST
                        + (ctx.isSlow(dstDesc) ? Traverse.SLOW_SURCHARGE : 0f)
                        + ctx.bodyTransitCost(e, flags, nx, dy, nz);
                out.accept(nx, dy, nz, cost + e.extraCost(), e);
            }
        }
    }

    /**
     * The phase-model execution plan (Stage 2 — the reactive-reconcile path that replaces {@code steer} +
     * one-shot edits). Descend is <b>CLEAR &rarr; STEP</b>: establish ALL of the step-off geometry up front —
     * break the three transit cells over the destination column and build the step-down floor — then walk off
     * the edge and let gravity supply the one-block drop.
     *
     * <p><b>Geometry (plan coords).</b> {@code from} floor {@code (fx,fy,fz)} &rarr; {@code to} floor
     * {@code (tx,ty,tz)} with {@code ty == fy-1} and {@code (tx,tz)} a cardinal neighbour of {@code (fx,fz)}.
     * The bot starts standing on {@code (fx,fy,fz)} (feet block {@code (fx,fy+1,fz)}) and ends standing on
     * {@code (tx,fy-1,tz)} (feet block {@code (tx,fy,tz)}), one block lower and one over. The step-off column is
     * {@code (tx, fy..fy+2, tz)} — new feet, new head, and the step-off head-clearance — and the step-down
     * floor is {@code (tx,fy-1,tz)}.
     *
     * <p><b>Why all geometry in CLEAR, none in STEP.</b> Unlike {@link Pillar}, Descend has no kinematic
     * ordering constraint: the placed floor cell {@code (tx,fy-1,tz)} is never occupied by the bot (it lands
     * <i>on</i> it, one over), so there is no "place only after airborne" gate. Establishing everything in the
     * prep phase also walls the bot in while the transit column is still solid — it physically cannot walk off
     * before the floor exists, and because the runner issues the footing place the same tick the last transit
     * cell clears (before any drive), the floor is always down before the bot can step into the cleared column.
     * Keeping STEP need-free makes it pure locomotion.
     *
     * <p><b>Coverage.</b> The three {@code Need.AIR} cells cover {@code candidates()}'s three {@code requireAir}
     * breaks (folded only in the {@code !headroomProves} case), and the {@code Need.FOOTING} covers its
     * {@code requireFloor} place (folded only when the dest isn't already standable). Both are declared
     * unconditionally — the runner no-ops a {@code Need.AIR} whose cell is already air (the {@code headroomProves}
     * case) and a {@code Need.FOOTING} whose cell is already solid (real terrain), so the superset is exactly
     * correct. The two {@code bodyTransitCost} break-through cells are a subset of the transit {@code Need.AIR}
     * cells, so any punch-through the search priced is covered.
     *
     * <p><b>Regression guard (armed).</b> {@code resetWhen} fires only once the bot has physically LEFT the
     * start cell and then finds itself grounded back on it — a genuine balk (knocked back mid-step). The
     * {@code left[0]} arm (set in STEP's drive after departure, cleared on re-entry to CLEAR) avoids the Parkour
     * aliasing trap: the first STEP tick, still grounded at start, must not satisfy a bare "grounded at start"
     * guard and bounce {@code STEP&rarr;CLEAR} forever. An overshoot that lands off the dest floor is not a reset
     * case — that is the follower's grounded-stall recovery / replan arm (as with {@link Fall}/{@link Parkour}).
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final boolean[] left = new boolean[1]; // reset-guard arm — set once the bot leaves the start floor
        MovePlan plan = new MovePlan();
        // Physically back on the START floor AFTER having left it → re-establish geometry. Armed by STEP and
        // disarmed on (re)entry to CLEAR, so it can't alias the first STEP tick (bot still grounded at start).
        plan.resetWhen(b -> left[0]
                && b.grounded() && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
        // CLEAR: break the step-off transit column and build the step-down floor. The runner mines one AIR cell
        // per tick (holding, recentring) and places the FOOTING once the AIR cells are clear; while the transit
        // is still solid the bot is walled in, so it cannot walk off before the floor exists.
        plan.phase("clear")
                .need(MovePlan.Need.AIR,     tx, fy + 2, tz)   // break: step-off head clearance
                .need(MovePlan.Need.AIR,     tx, fy + 1, tz)   // break: transit / new head
                .need(MovePlan.Need.AIR,     tx, fy,     tz)   // break: new feet
                .need(MovePlan.Need.FOOTING, tx, fy - 1, tz)   // place: step-down floor (no-op on real terrain)
                .drive((b, v) -> left[0] = false)              // disarm on (re)entry; advances same tick
                .advanceWhen(b -> true);                       // geometry held (runner drives only when met) → STEP
        // STEP: walk off the edge toward the dest column; gravity does the one-block drop. Complete once
        // standing on the new floor (feet block == (tx, ty+1, tz) == (tx, fy, tz)).
        plan.phase("step")
                .drive((b, v) -> {
                    if (!b.grounded() || b.footX() != fx || b.footZ() != fz) left[0] = true; // left start → arm
                    SteerControl.drive(b, v);                  // medium-aware (walk on land, swim if submerged)
                })
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == fy && b.footZ() == tz);
        return plan;
    }
}
