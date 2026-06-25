package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Mine straight down one block (MOVEMENT-DESIGN.md §2) — the vertical-in-place counterpart to {@link
 * Pillar}, and the efficient way into a deep pit. The bot breaks the block it stands on and drops one
 * cell onto the block below. Where {@link Descend}'s dig-a-step-down folds ~3 breaks per level (the
 * diagonal transit column), MineDown is ONE break per level straight down — so A* uses it for descending
 * a shaft / deep pit instead of an expensive diagonal staircase, and the search reaches deep goals
 * within budget that the diagonal-only descent couldn't.
 *
 * <p><b>Geometry.</b> Floor cell {@code (x,y,z)} → {@code (x,y-1,z)}. The destination one below must be
 * standable (so the bot lands on solid ground — never "mines down" into a void or lava, which aren't
 * standable). The bot's current floor {@code (x,y,z)} is broken so it can drop into it; the resulting
 * feet cell ({@code y}, now broken air via the {@link com.orebit.mod.pathfinding.blockpathfinder.PathEdits}
 * diff) and head ({@code y+1}, the old feet) are both clear, so the new stand position is valid for the
 * following move.
 *
 * <p><b>Caps.</b> Requires {@link BotCaps#canBreak}; a non-breaking bot emits nothing. The floor must be
 * actually breakable (not bedrock/fluid) and not {@code RISKY_EDIT} (don't undermine sand / tap a flow).
 */
public final class MineDown implements Movement {

    /** One-block drop base cost (matches {@link Descend#COST}); the break adds its hardness-scaled cost. */
    public static final float COST = 1.5f;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (!ctx.caps().canBreak()) return;

        int dy = y - 1; // destination floor one below
        int packed = ctx.packedAt(x, dy, z);
        if (packed == MovementContext.UNBUILT) return;
        if (!ctx.standable(ctx.descriptorOf(x, dy, z, packed))) return; // must land on solid ground

        // Break the block the bot currently stands on so it can drop into it. RISKY_EDIT on that cell
        // forbids the break (don't undermine a gravity stack / open a fluid); unbreakable floor → invalid.
        int flags = ctx.flagsAt(x, y, z);
        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
        e.requireAir(x, y, z);
        if (e.valid()) out.accept(x, dy, z, COST + e.extraCost(), e.snapshot());
    }
}
