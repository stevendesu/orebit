package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

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
 */
public final class Diagonal implements Movement {

    /** Diagonal step cost = {@link Traverse#FLAT_COST} · √2 (one cell in each of x and z). */
    public static final float COST = Traverse.FLAT_COST * 1.41421356f;

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        for (int[] d : DIAGONALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Destination floor must be built + standable (no footing-placement on a diagonal here).
            int packed = ctx.packedAt(nx, y, nz);
            if (packed == MovementContext.UNBUILT) continue;
            long dstDesc = ctx.descriptorOf(nx, y, nz, packed);
            if (!ctx.standable(dstDesc)) continue;

            // Destination body (feet + head) clear — fast-path via the resident HEADROOM bit, else read.
            int flags = MovementContext.flagsOf(packed);
            if (!ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK)
                    && (!ctx.passable(nx, y + 1, nz) || !ctx.passable(nx, y + 2, nz))) {
                continue;
            }

            // Both corner columns' body cells must be clear, or the diagonal clips a wall corner.
            if (!ctx.passable(nx, y + 1, z) || !ctx.passable(nx, y + 2, z)) continue;
            if (!ctx.passable(x, y + 1, nz) || !ctx.passable(x, y + 2, nz)) continue;

            float cost = ctx.isSlow(dstDesc) ? COST + Traverse.SLOW_SURCHARGE : COST;
            out.accept(nx, y, nz, cost);
        }
    }
}
