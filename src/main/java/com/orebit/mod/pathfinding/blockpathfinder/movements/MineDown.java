package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Cuboid;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.MacroJump;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;

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
 *
 * <h2>Macro collapse (MACRO-IMPLEMENTATION.md §8.1)</h2>
 * A deep shaft is a long uniform run of "break the block underfoot, drop one." Rather than emit one
 * single-step candidate and let A* re-expand the shaft block-by-block, the macro path collapses the whole
 * uniform run into ONE candidate at the jump distance, folding the {@code J} per-step breaks into the
 * {@link EditScratch}. The jump length comes from {@link MacroJump#steps} over the cuboid containing the
 * start cell — so skipping the intermediate cells is sound precisely because the cuboid certifies the
 * orthogonal cross-section is uniform over the whole run (NON-NEGOTIABLE 1) and the escape-hedge keeps the
 * jump from over-shooting a cheaper sideways exit (NON-NEGOTIABLE 2).
 *
 * <p>The macro emit is gated on {@link BlockPathfinder#MACRO_MOVES} AND a non-null {@link
 * MovementContext#cuboids()}. When either is off, this movement reproduces the original single-step micro
 * candidate byte-for-byte — the macro work is a clean, revertible layer over the verified micro search.
 *
 * <p><b>Travel direction:</b> {@code (AXIS_Y, -1)} — straight down. Per step {@code k} (1-based), the move
 * breaks the cell at {@code (x, y-(k-1), z)} (the block the bot would be standing on at the start of step
 * {@code k}) and drops one cell. After {@code J} steps the destination floor is {@code (x, y-J, z)}.
 */
public final class MineDown implements Movement {

    /**
     * Base cost = one step of time — essentially a one-block Fall, but the floor must be broken first. That
     * break adds its own cost, so mining down is only chosen when descending is actually wanted.
     */
    public static final float COST = 1.0f;

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

        // ---- Micro fallback: macros off, or no cuboid seam (legacy unbounded search). Byte-for-byte the
        //      original single-step behaviour — emit exactly one mine-down candidate. ----
        NavGridCuboidsView cuboids = ctx.cuboids();
        if (!BlockPathfinder.MACRO_MOVES || cuboids == null) {
            EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
            e.requireAir(x, y, z);
            if (e.valid()) out.accept(x, dy, z, COST + e.extraCost(), e);
            return;
        }

        // ---- Macro path: collapse the uniform shaft into one jump. ----
        // Per-step move cost = base move + the break folded onto each level. Use the REAL break cost of the
        // column substrate (the floor cell the bot is currently standing on), not a literal — cheap moves
        // get large jumps, expensive ones small (NON-NEGOTIABLE 2). The shaft is uniform by construction
        // (the cuboid certifies one navtype), so the start cell's break cost is the per-step edit cost.
        float moveCost = COST + ctx.breakCost(ctx.descriptorAt(x, y, z));

        Cuboid box = ctx.cuboidScratch();
        cuboids.cuboidAt(x, y, z, Axes.AXIS_Y, box);
        int J = MacroJump.steps(box, x, y, z, Axes.AXIS_Y, -1, moveCost,
                ctx.goalX(), ctx.goalY(), ctx.goalZ());

        // Fold the J per-step breaks into the scratch, replaying the SAME per-step requirement the micro
        // move checks (requireAir at the level broken on step k). Clamp J to the last valid step — an
        // over-claimed jump must SHRINK, never grow (conservative-only, MACRO-MOVEMENTS §3b).
        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
        for (int k = 1; k <= J; k++) {
            int by = y - (k - 1); // the floor cell broken on step k
            // Re-evaluate RISKY_EDIT per level (the micro move does, since each shaft cell is its own node):
            // don't undermine a gravity stack / tap a fluid mid-shaft just because the START cell was safe.
            // The start level (k==1) was already gated by the reset() above. Clamp the jump above a risky cell.
            if (k > 1 && MovementContext.risksEdit(ctx.flagsAt(x, by, z))) { J = k - 1; break; }
            e.requireAir(x, by, z);          // break the block the bot stands on at the start of step k
            if (!e.valid()) {                // this level can't be broken — clamp to the last valid step
                J = k - 1;
                break;
            }
        }
        if (J < 1) return; // nothing valid — not even the first step

        int dz = Axes.stepY(Axes.AXIS_Y, -1) * J; // = -J (the Y delta of a straight-down jump)
        out.accept(x, y + dz, z, J * COST + e.extraCost(), e);
    }
}
