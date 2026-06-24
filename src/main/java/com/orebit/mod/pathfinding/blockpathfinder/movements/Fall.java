package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Drop more than one block off a cardinal edge to the first solid landing below (MOVEMENT-DESIGN.md §2,
 * Tier 1). This first pass is the <b>safe-distance-only</b> form: a fall is allowed only while its depth
 * is within {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#safeFallDistance}. Soft-landing
 * absorption (water / hay / slime — a wider safe drop) arrives with the {@code softLanding} NavBlock
 * fact when that's built; for now the bot won't take a drop it can't walk away from.
 *
 * <p>The landing must be {@link MovementContext#standable} (so it never "lands" in lava/cactus — those
 * aren't standable) and the whole drop column, plus the step-off transit, must be {@link
 * MovementContext#passable}. The highest reachable landing wins (shortest, safest drop). Fall folds no
 * edits (you can't usefully break/place mid-drop), so it never consults {@code RISKY_EDIT}.
 */
public final class Fall implements Movement {

    /** Step base cost; each block dropped adds {@link #PER_BLOCK}. */
    public static final float BASE_COST = 1.0f;
    /** Cost per block dropped (matches the legacy fall penalty). */
    public static final float PER_BLOCK = 0.5f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        int maxDrop = ctx.caps().safeFallDistance();
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Step off the edge: the neighbour column at the bot's level must be open (2 cells) — the
            // WALK-level HEADROOM of the air cell at the bot's level. The bit's OOB bias is one-directional
            // (it can only over-claim clearance), so a sub-WALK reading is a trustworthy reject with no
            // reads; only a claims-clear reading near a section top needs the per-cell verify.
            int flags = ctx.flagsAt(nx, y, nz);
            if (MovementContext.headroom(flags) < MovementContext.HEADROOM_WALK) continue;
            if (!ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK)
                    && (!ctx.passable(nx, y + 1, nz) || !ctx.passable(nx, y + 2, nz))) {
                continue;
            }

            // Scan downward for the first (highest) solid landing within the safe-fall window.
            for (int fy = y - 2; fy >= y - maxDrop; fy--) {
                if (!ctx.built(nx, fy, nz)) break;          // unknown below — don't path into it
                if (!ctx.standable(nx, fy, nz)) continue;    // still air; keep falling

                // Landing found: confirm the drop column (down to the new feet) is clear.
                boolean clear = true;
                for (int k = fy + 1; k <= y; k++) {
                    if (!ctx.passable(nx, k, nz)) { clear = false; break; }
                }
                if (clear) {
                    int depth = y - fy;
                    out.accept(nx, fy, nz, BASE_COST + depth * PER_BLOCK);
                }
                break; // only the highest landing in this column
            }
        }
    }
}
