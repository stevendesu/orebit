package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
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
 *
 * <p><b>Body clearance via the resident bit.</b> The two body cells above a destination floor are checked
 * through the precomputed {@code HEADROOM} flag ({@link MovementContext#requireBodyClear}) — one grid read
 * instead of two {@code descriptorAt} probes — falling back to per-cell reads (which also fold breaks)
 * only when the bit can't be trusted near a section face or when the bot must mine its way through.
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

            // Flat walk onto an adjacent solid-topped cell. The two body cells must be clear; a block in
            // the way (e.g. leaves) is folded into a break-set when the bot may break, raising the cost
            // instead of failing the move (MOVEMENT-DESIGN.md §1 — the motivating forest-leaves case).
            if (ctx.built(nx, y, nz) && ctx.standable(nx, y, nz)) {
                int flags = ctx.flagsAt(nx, y, nz);
                EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                ctx.requireBodyClear(e, nx, y, nz, flags);
                if (e.valid()) {
                    out.accept(nx, y, nz, cost(ctx, nx, y, nz) + e.extraCost(), e.snapshot());
                    continue; // already have footing here; don't also step-assist/bridge this column
                }
            }

            // Step-assist: one cell up onto a low partial (slab / snow / stair lip) — no jump. Same
            // break-the-body-path modifier as the flat case.
            int uy = y + 1;
            if (ctx.built(nx, uy, nz) && ctx.standable(nx, uy, nz)
                    && ctx.topYOf(nx, uy, nz) <= MovementContext.STEP_ASSIST_MAX_TOP_Y) {
                int flags = ctx.flagsAt(nx, uy, nz);
                EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                ctx.requireBodyClear(e, nx, uy, nz, flags);
                if (e.valid()) {
                    out.accept(nx, uy, nz, cost(ctx, nx, uy, nz) + e.extraCost(), e.snapshot());
                    continue;
                }
            }

            // Bridge: no footing in the neighbour column — place a throwaway floor and walk onto it when
            // the bot may place (the source cell is always an adjacent face to build against). "Bridge"
            // is not its own movement, just Traverse with a place in its edit-set (decision 1).
            if (ctx.built(nx, y, nz) && !ctx.standable(nx, y, nz)) {
                int flags = ctx.flagsAt(nx, y, nz);
                EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                e.requireFloor(nx, y, nz);
                ctx.requireBodyClear(e, nx, y, nz, flags);
                if (e.valid()) {
                    out.accept(nx, y, nz, cost(ctx, nx, y, nz) + e.extraCost(), e.snapshot());
                }
            }
        }
    }

    private static float cost(MovementContext ctx, int x, int y, int z) {
        return ctx.isSlow(x, y, z) ? FLAT_COST + SLOW_SURCHARGE : FLAT_COST;
    }
}
