package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Step down exactly one block to a cardinal-adjacent floor cell (MOVEMENT-DESIGN.md §2, Tier 1) — the
 * gentle counterpart to {@link Ascend}. The bot walks off the edge into the neighbour column and drops
 * a single block; no jump, always safe. Deeper drops are {@link Fall}'s job.
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

            // Clear the transit (source head level) down through the new feet/head; a block in the way
            // is folded into a break-set when the bot may break (else the move fails).
            EditScratch e = ctx.edits().reset();
            e.requireAir(nx, y + 2, nz); // head clearance stepping off
            e.requireAir(nx, y + 1, nz); // transit feet / new head
            e.requireAir(nx, y, nz);     // new feet
            if (e.valid()) {
                out.accept(nx, dy, nz, COST + e.extraCost(), e.snapshot());
            }
        }
    }
}
