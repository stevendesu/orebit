package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

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
 */
public final class Descend implements Movement {

    /** Step + one-block drop (matches the legacy {@code STEP_COST + FALL_PENALTY} for a 1-drop). */
    public static final float COST = 1.5f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int dy = y - 1; // destination floor one below

            if (!ctx.built(nx, dy, nz)) continue;
            if (!ctx.standable(nx, dy, nz)) continue;

            int flags = ctx.flagsAt(nx, dy, nz);
            if (ctx.headroomProves(flags, dy, MovementContext.HEADROOM_JUMP)) {
                out.accept(nx, dy, nz, COST, null); // transit provably clear — no probes, no edits
                continue;
            }

            EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
            e.requireAir(nx, y + 2, nz); // head clearance stepping off
            e.requireAir(nx, y + 1, nz); // transit feet / new head
            e.requireAir(nx, y, nz);     // new feet
            if (e.valid()) out.accept(nx, dy, nz, COST + e.extraCost(), e.snapshot());
        }
    }
}
