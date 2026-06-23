package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Jump up one block onto a cardinal-adjacent floor cell that's a full step up (MOVEMENT-DESIGN.md §2,
 * Tier 1). Distinct from {@link Traverse}'s step-assist: this is a destination whose collision top is
 * <i>above</i> {@link MovementContext#STEP_ASSIST_MAX_TOP_Y}, so gaining it needs a real jump.
 *
 * <p><b>The head-clearance fix.</b> The coarse 2-bit grid marks the destination "clear" when it has two
 * air cells above its floor — but a jump from the source column also needs the cell <i>above the bot's
 * own head</i> (source {@code y+3}) to be clear, or the bot bonks the ceiling and never gains the block.
 * That cell is exactly what the floor-centric grid can't represent (the "head-in-block" / "2-high dirt
 * wall reads as a step" class of bug, commit {@code 7beda91}). Verifying it here with live geometry —
 * not the grid — is the whole reason the fine layer exists.
 */
public final class Ascend implements Movement {

    /** Step + jump: flat cost plus the climb penalty (kept equal to the legacy up-penalty model). */
    public static final float COST = 2.0f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.caps().jumpHeight() < 1) return;

        // Head clearance at the takeoff column: the cell above the bot's head must be open to rise.
        if (!ctx.passable(x, y + 3, z)) return;

        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int uy = y + 1;

            if (!ctx.built(nx, uy, nz)) continue;
            if (!ctx.standable(nx, uy, nz)) continue;
            // A low partial one up is Traverse's step-assist, not a jump — leave it to Traverse.
            if (ctx.topYOf(nx, uy, nz) <= MovementContext.STEP_ASSIST_MAX_TOP_Y) continue;
            // Destination body clear (feet + head at the landing).
            if (!ctx.passable(nx, uy + 1, nz) || !ctx.passable(nx, uy + 2, nz)) continue;

            out.accept(nx, uy, nz, COST);
        }
    }
}
