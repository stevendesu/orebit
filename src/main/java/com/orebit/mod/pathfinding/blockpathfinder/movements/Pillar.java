package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Pillar straight up one block (MOVEMENT-DESIGN.md §2) — the vertical-in-place counterpart to {@link
 * Ascend}'s diagonal staircase. The bot jumps and places a footing in the cell its feet just left,
 * landing one block higher in the SAME column. Where {@link Ascend} needs an adjacent destination (and
 * up to two placements to build a step out-and-up), Pillar gains height with a single placement and no
 * horizontal travel, so it's far cheaper per block of pure ascent — A* prefers it for a vertical climb
 * and falls back to a staircase only where the route must also move sideways (or stay descendable; a
 * pillar is a one-way climb the bot can't walk back down, which the cost model leaves to the search to
 * weigh once a descent move competes).
 *
 * <p><b>Geometry.</b> Floor cell {@code (x,y,z)} → {@code (x,y+1,z)}. The new floor is the bot's current
 * feet cell, placed against the floor it stands on (solid by the search invariant — real terrain or a
 * preceding step's block via the {@link com.orebit.mod.pathfinding.blockpathfinder.PathEdits} diff, so
 * repeated Pillar chains upward). The takeoff needs the new head cell ({@code y+3}) clear; the new feet
 * cell ({@code y+2}) is the old head, already clear because any search node has verified body clearance.
 *
 * <p><b>Caps.</b> Requires {@link BotCaps#canPlace} (it places the footing) and {@code jumpHeight ≥ 1};
 * a walk-only bot emits nothing here, exactly as before.
 */
public final class Pillar implements Movement {

    /**
     * Base cost = one step of time — essentially a jump in place, but a footing must be placed first. That
     * placement ({@code PLACE_COST}) adds its own cost, so pillaring is only chosen when climbing is wanted.
     */
    public static final float COST = 1.0f;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        BotCaps caps = ctx.caps();
        if (!caps.canPlace() || caps.jumpHeight() < 1) return;

        int ny = y + 1; // new floor = the bot's current feet cell
        int packed = ctx.packedAt(x, ny, z);
        if (packed == MovementContext.UNBUILT) return;
        long floorDesc = ctx.descriptorOf(x, ny, z, packed);
        // Must be an open cell to place into; if it's already standable this isn't a pillar.
        if (!ctx.openForPlace(floorDesc)) return;

        int flags = MovementContext.flagsOf(packed);
        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
        // Place the footing, supported by the floor below (the bot's current floor — solid by invariant).
        e.requireFloor(x, ny, z);
        // Takeoff head-clearance: the new head cell must be clear (break-fold if the bot may break).
        e.requireAir(x, y + 3, z);
        if (e.valid()) out.accept(x, ny, z, COST + e.extraCost(), e);
    }
}
