package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Walk to a cardinal-adjacent floor cell <b>without jumping</b> — the cheapest, most common move
 * (MOVEMENT-DESIGN.md §2, Tier 1). Covers two cases the player handles with the same flat walk:
 *
 * <ul>
 *   <li><b>Flat</b> (same floor level) — step onto an adjacent solid-topped cell with two clear cells
 *       above it.
 *   <li><b>Step-assist</b> (one cell up onto a low partial) — a slab / single snow layer / stair lip
 *       whose collision top is ≤ {@link MovementContext#STEP_ASSIST_MAX_TOP_Y} sixteenths is auto-stepped
 *       (~0.6 blocks) without a jump. This is the visible "uses stairs naturally" behaviour, and it
 *       falls straight out of the {@code topY} fact — no jump means the follower must <i>not</i> trigger
 *       one, which is why this is a distinct movement from {@link Ascend}.
 * </ul>
 */
public final class Traverse implements Movement {

    /** Flat-walk base cost (the search's minimum step). */
    public static final float FLAT_COST = 1.0f;
    /** Surcharge for crossing a slow surface (soul sand / honey / cobweb / slime). */
    public static final float SLOW_SURCHARGE = 2.0f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Flat walk: adjacent floor at the same level, body clear above it.
            if (ctx.built(nx, y, nz) && ctx.standable(nx, y, nz)
                    && ctx.passable(nx, y + 1, nz) && ctx.passable(nx, y + 2, nz)) {
                out.accept(nx, y, nz, cost(ctx, nx, y, nz));
                continue; // can't also step-assist onto the same column when it's already flat here
            }

            // Step-assist: one cell up onto a low partial (slab / snow / stair lip) — no jump.
            int uy = y + 1;
            if (ctx.built(nx, uy, nz) && ctx.standable(nx, uy, nz)
                    && ctx.topYOf(nx, uy, nz) <= MovementContext.STEP_ASSIST_MAX_TOP_Y
                    && ctx.passable(nx, uy + 1, nz) && ctx.passable(nx, uy + 2, nz)) {
                out.accept(nx, uy, nz, cost(ctx, nx, uy, nz));
            }
        }
    }

    private static float cost(MovementContext ctx, int x, int y, int z) {
        return ctx.isSlow(x, y, z) ? FLAT_COST + SLOW_SURCHARGE : FLAT_COST;
    }
}
