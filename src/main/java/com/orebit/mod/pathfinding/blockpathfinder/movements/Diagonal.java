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

            float cost = (ctx.isSlow(dstDesc) ? COST + Traverse.SLOW_SURCHARGE : COST)
                    + ctx.bodyTransitCost(flags, nx, y, nz) // destination body, via the resident flag bits
                    + ctx.cellTransitCost(c1) + ctx.cellTransitCost(c2)
                    + ctx.cellTransitCost(c3) + ctx.cellTransitCost(c4);
            out.accept(nx, y, nz, cost);
        }
    }
}
