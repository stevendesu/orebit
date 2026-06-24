package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Jump up one block onto a cardinal-adjacent floor cell that's a full step up (MOVEMENT-DESIGN.md §2,
 * Tier 1). Distinct from {@link Traverse}'s step-assist: this is a destination whose collision top is
 * <i>above</i> {@link MovementContext#STEP_ASSIST_MAX_TOP_Y}, so gaining it needs a real jump.
 *
 * <p><b>The head-clearance fix.</b> A jump from the source column needs the cell <i>above the bot's own
 * head</i> (source {@code y+3}) clear, or the bot bonks the ceiling and never gains the block — the cell
 * the floor-centric grid can't represent (the "head-in-block" / "2-high dirt wall reads as a step" class
 * of bug, commit {@code 7beda91}). Both that and the landing body clearance are now read through the
 * resident HEADROOM bit: the source's own feet/head are already clear (the bot stands there), so its
 * HEADROOM is {@code JUMP} exactly when {@code y+3} is clear; the landing needs {@code WALK}. Cells the
 * bit can't prove (near a section face, or genuinely blocked) are read and — when the bot may break and
 * the edit isn't {@code RISKY_EDIT} — folded into a break-set.
 */
public final class Ascend implements Movement {

    /** Step + jump: flat cost plus the climb penalty (kept equal to the legacy up-penalty model). */
    public static final float COST = 2.0f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.caps().jumpHeight() < 1) return;
        int uy = y + 1;

        // Source facts are the same for all four directions — read once. The bot stands on (x,y,z) so its
        // feet/head are clear; HEADROOM == JUMP iff the takeoff head-clearance (y+3) is also clear.
        int srcFlags = ctx.flagsAt(x, y, z);
        boolean srcClear = ctx.headroomProves(srcFlags, y, MovementContext.HEADROOM_JUMP);
        boolean srcRisky = MovementContext.risksEdit(srcFlags);

        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            if (!ctx.built(nx, uy, nz)) continue;
            if (!ctx.standable(nx, uy, nz)) continue;
            // A low partial one up is Traverse's step-assist, not a jump — leave it to Traverse.
            if (ctx.topYOf(nx, uy, nz) <= MovementContext.STEP_ASSIST_MAX_TOP_Y) continue;

            int dstFlags = ctx.flagsAt(nx, uy, nz);
            boolean dstClear = ctx.headroomProves(dstFlags, uy, MovementContext.HEADROOM_WALK);
            if (srcClear && dstClear) {
                out.accept(nx, uy, nz, COST, null);
                continue;
            }

            EditScratch e = ctx.edits().reset(!(srcRisky || MovementContext.risksEdit(dstFlags)));
            if (!srcClear) e.requireAir(x, y + 3, z);
            if (!dstClear) {
                e.requireAir(nx, uy + 1, nz);
                e.requireAir(nx, uy + 2, nz);
            }
            if (e.valid()) out.accept(nx, uy, nz, COST + e.extraCost(), e.snapshot());
        }
    }
}
