package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Drop more than one block off a cardinal edge to the first solid landing below (MOVEMENT-DESIGN.md §2,
 * Tier 1). A drop within {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#safeFallDistance} is free;
 * a deeper drop up to {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#maxFallDistance} is allowed but
 * charged a {@link #DAMAGE_PER_BLOCK} <b>damage penalty</b> per block past the safe window — fall damage is a
 * cost, not a blocker, so the bot will take a hurtful drop when the alternative is a long detour but prefers a
 * gentle route when one is in reach. Beyond {@code maxFallDistance} the drop is rejected (unacceptable / lethal
 * damage). Soft-landing absorption (water / hay / slime — a wider safe drop) arrives with the {@code
 * softLanding} NavBlock fact when that's built.
 *
 * <p>The landing must be {@link MovementContext#standable} (so it never "lands" in lava/cactus — those
 * aren't standable) and the whole drop column, plus the step-off transit, must be {@link
 * MovementContext#passable}. The highest reachable landing wins (shortest, safest drop). Fall folds no
 * edits (you can't usefully break/place mid-drop), so it never consults {@code RISKY_EDIT}.
 */
public final class Fall implements Movement {

    /**
     * Step base cost, in <b>ticks</b> = one walk-off step ({@link Traverse#FLAT_COST}): the bot walks off
     * the edge (Baritone {@code WALK_OFF_BLOCK_COST ≈ WALK_ONE_BLOCK_COST}), then falls. Each block dropped
     * adds {@link #PER_BLOCK}.
     */
    public static final float BASE_COST = Traverse.FLAT_COST;
    /**
     * Ticks added per block of drop. Falling is fast — under vanilla gravity the first blocks take well
     * under a tick each, but the average rises with depth; Baritone's {@code FALL_N_BLOCKS_COST} table over
     * the small safe-fall window (≤ {@code safeFallDistance}) averages ≈ 2.5 ticks/block once the walk-off
     * is paid separately. Kept a flat per-block term (not the full physics table) because Tier 1 caps the
     * drop at the safe window, where the linear approximation is within a tick of the table. Source:
     * Baritone {@code ActionCosts.FALL_N_BLOCKS_COST}.
     */
    public static final float PER_BLOCK = 2.5f;

    /**
     * Ticks charged per block of drop <b>beyond</b> {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#safeFallDistance}
     * — the fall-DAMAGE cost (each block past the safe window is ≈ 1 vanilla damage). Damage is a COST, not a
     * blocker: the bot takes a hurtful drop when the only alternative is a long detour, but this penalty makes
     * it prefer a damage-free route (e.g. the 2-block-drop cave entrance over the 5-block-drop one) whenever one
     * exists within reach. Ordinal / tunable (a future health-aware model can scale it by remaining hearts).
     */
    public static final float DAMAGE_PER_BLOCK = 10f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        // Scan to the bot's MAX fall (not just the safe one): drops past safeFall are allowed at a damage cost,
        // so a route that needs a hurtful drop isn't a dead end — it's just dearer than a gentle one.
        final int safeFall = ctx.caps().safeFallDistance();
        final int maxDrop = Math.max(ctx.caps().maxFallDistance(), safeFall);
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
                int packed = ctx.packedAt(nx, fy, nz);
                if (packed == MovementContext.UNBUILT) break;        // unknown below — don't path into it
                if (!ctx.standable(ctx.descriptorOf(nx, fy, nz, packed))) continue; // still air; keep falling

                // Landing found: confirm the drop column (down to the new feet) is clear.
                boolean clear = true;
                for (int k = fy + 1; k <= y; k++) {
                    if (!ctx.passable(nx, k, nz)) { clear = false; break; }
                }
                if (clear) {
                    int depth = y - fy;
                    // Base walk-off + per-block fall time, plus a damage penalty for every block past the safe
                    // window (depth > safeFall) — the cost-not-blocker model.
                    float cost = BASE_COST + depth * PER_BLOCK;
                    if (depth > safeFall) {
                        cost += (depth - safeFall) * DAMAGE_PER_BLOCK;
                    }
                    out.accept(nx, fy, nz, cost);
                }
                break; // only the highest landing in this column
            }
        }
    }
}
