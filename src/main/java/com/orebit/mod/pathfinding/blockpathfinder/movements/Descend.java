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
 *
 * <p><b>Place modifier (MOVEMENT-DESIGN §1, decision 1).</b> When there's no footing one block down, a
 * throwaway floor is <i>placed</i> against the wall to descend onto (the counterpart to {@link Ascend}'s
 * staircase-up). Repeated Descend+place builds a staircase down a sheer drop the bot can't safely
 * {@link Fall} — completing controlled 3D descent through the existing kinds.
 */
public final class Descend implements Movement {

    /**
     * Base cost = one step of time: a flat step plus a free one-block drop (gravity), so descending
     * existing terrain costs no more than a Traverse. A folded place/break (building/digging a step where
     * there's no terrain) adds its own cost.
     */
    public static final float COST = 1.0f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int dy = y - 1; // destination floor one below

            // Destination floor (nx,dy,nz) is read both standable and flags — resolve its slot once.
            int packed = ctx.packedAt(nx, dy, nz);
            if (packed == MovementContext.UNBUILT) continue;

            boolean dstStandable = ctx.standable(ctx.descriptorOf(nx, dy, nz, packed));
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
            if (e.valid()) out.accept(nx, dy, nz, COST + e.extraCost(), e);
        }
    }
}
