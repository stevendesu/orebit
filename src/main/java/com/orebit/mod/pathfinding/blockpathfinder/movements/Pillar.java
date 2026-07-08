package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Cuboid;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.MacroJump;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;

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
 * a walk-only bot emits nothing here, exactly as before. Also requires a <b>full-height start floor</b>
 * ({@code topY == 16}): the placed footing's top sits {@code 32 − startTopY} sixteenths above the start
 * surface, and a jump gains only {@link MovementContext#JUMP_RISE} (20) — from a slab ({@code 8 + 20 =
 * 28 < 32}) the feet never clear the cell being filled, so you cannot pillar off a slab (derivation at
 * the gate in {@link #candidates}).
 *
 * <h2>Macro-awareness (MACRO-IMPLEMENTATION.md §8.1)</h2>
 * When macro-movement collapse is enabled ({@link BlockPathfinder#MACRO_MOVES} <i>and</i> the search
 * supplied a {@link NavGridCuboidsView} via {@link MovementContext#cuboids()}), Pillar collapses a long
 * uniform run of single up-and-place steps into <b>one</b> candidate at the jump distance, instead of
 * emitting only a 1-step rise. The bound comes from {@link MacroJump#steps}: the cuboid's vertical travel
 * extent (HARD), the forward distance to the goal's Y (HARD — never overshoot), and the escape-hedge
 * {@code ceil(orthogonalFace / moveCost)} (NON-NEGOTIABLE 2 — the {@code / moveCost} caps an <i>expensive</i>
 * pillar to a short jump so it cannot sail past a cheaper exit beside the column; the canonical staircase
 * counter-example of NON-NEGOTIABLE 1). The travel direction is fixed: {@link Axes#AXIS_Y}, {@code sign +1}.
 *
 * <p>The jump folds the same per-step edits the micro move makes — a {@code requireFloor} at each rise
 * level {@code (x, y+k, z)} for {@code k = 1..J} (the support placed under each step), plus the single
 * top head-clearance {@code requireAir(x, y+J+2, z)} (the bot ends standing at {@code y+J}, so its head
 * cell is {@code y+J+2}; for {@code J == 1} this is {@code y+3}, identical to the micro head check). If a
 * per-step requirement fails partway up, {@code J} is clamped to the last valid step — a conservative
 * shrink (a shorter jump is always safe; plain A* fills the remainder). The cuboid having certified the
 * column uniform is exactly what makes skipping the intermediate stand cells sound (NON-NEGOTIABLE 1).
 *
 * <p>The per-step cost is {@code Pillar.COST + MovementContext.placeCost(...)} (the move plus the folded
 * real place ticks), so the macro's total cost is {@code J × COST + }{@link EditScratch#extraCost()} — exactly
 * {@code J} times the per-step cost, never cheaper than {@code J} separate micro steps (the macro is a
 * search-shape optimization, not a cost discount).
 *
 * <p><b>Legacy parity.</b> When {@link BlockPathfinder#MACRO_MOVES} is off <i>or</i>
 * {@link MovementContext#cuboids()} is {@code null}, this emits the ORIGINAL single 1-step candidate
 * byte-for-byte, so the macro layer is a clean, revertible overlay on the verified micro search.
 */
public final class Pillar implements Movement {

    /**
     * Base cost, in <b>ticks</b> = one move step ({@link Traverse#FLAT_COST}) — essentially a jump in place
     * (the jump + settle takes about as long as a walk step). The footing that must be placed first adds its
     * own real place ticks ({@link MovementContext#placeCost}), so a pillar step costs {@code FLAT_COST +
     * placeCost ≈ 4.6 + 6 ≈ 10.6} ticks at the default {@code placement.placeBaseCost} — still expensive
     * enough that pillaring is chosen only when climbing is actually wanted, and a single floating block
     * beside the column (a cheaper exit) easily out-competes it.
     */
    public static final float COST = Traverse.FLAT_COST;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // jump-and-place — only while upright
        BotCaps caps = ctx.caps();
        if (!caps.canPlace() || caps.jumpHeight() < 1) return;

        int ny = y + 1; // new floor = the bot's current feet cell
        int packed = ctx.packedAt(x, ny, z);
        if (packed == MovementContext.UNBUILT) return;
        long floorDesc = ctx.descriptorOf(x, ny, z, packed);
        // Must be an open cell to place into; if it's already standable this isn't a pillar.
        if (!ctx.openForPlace(floorDesc)) return;

        // FULL-height start surface required (canon: heights in sixteenths, jump rise = JUMP_RISE = 20).
        // Pillaring places a full cube into the bot's own feet cell, whose top is 32 − startSurface above
        // the start surface. From a partial start (a slab, top 8) the jump apex reaches only
        // startSurface + 20 = 28 < 32 — the feet can never clear the cell being filled, and the follower
        // would place a block inside the bot. Only a full-height surface (16 + 20 = 36 ≥ 32) pillars; a
        // macro chain's later steps stand on the just-placed full cubes (topY 16 via the path-edit diff),
        // so every chained step passes on its own. floorSurface (standable → topY, else 16) keeps
        // non-standable-floor nodes (a flooded-shaft swim node's water floor — jump = swim-up there) at
        // their historical geometry. One cached read per (rare — place-capable, open-cell-above) reached
        // expansion.
        if (ctx.floorSurface(x, y, z) < 16) return;

        int flags = MovementContext.flagsOf(packed);

        // --- Micro path: emit the original single step, byte-for-byte. Taken when macros are off, there is no
        // cuboid view, OR (Option B) this movement's travel axis (Y) is not the search's primary axis P — an
        // off-P movement skips cuboidAt + MacroJump entirely so a uniform region is extracted on ONE axis only.
        NavGridCuboidsView cuboids = ctx.cuboids();
        if (!BlockPathfinder.MACRO_MOVES || cuboids == null || ctx.macroAxis() != Axes.AXIS_Y) {
            EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
            // Place the footing, supported by the floor below (the bot's current floor — solid by invariant).
            e.requireFloor(x, ny, z);
            // Takeoff head-clearance: the new head cell must be clear (break-fold if the bot may break).
            e.requireAir(x, y + 3, z);
            if (e.valid()) out.accept(x, ny, z, COST + e.extraCost(), e);
            return;
        }

        // --- Macro path: collapse a uniform vertical run of up-and-place steps into ONE candidate. -------
        // The cuboid (over the start floor cell, travel axis Y) certifies the column is uniform for the
        // whole jump, so skipping the intermediate stand cells is sound (NON-NEGOTIABLE 1). Its travel
        // extent, the goal-Y bound, and the escape-hedge ceil(orthFace / moveCost) bound the jump.
        Cuboid box = ctx.cuboidScratch();
        cuboids.cuboidAt(x, ny, z, Axes.AXIS_Y, +1, box); // travel +Y (climbs upward)

        // Real per-step cost = the upward move plus the folded placement, in ticks (NON-NEGOTIABLE 2: the
        // escape-hedge divides the orthogonal face by THIS, so an expensive pillar step gets a short jump and
        // can't sail past a cheaper sideways exit — read placeCost, never a literal). The placed footing is
        // the bot's conjured/throwaway block; placeCost is uniform per step up the (cuboid-certified uniform)
        // column, so the start cell's place cost is the per-step edit cost.
        float moveCost = COST + ctx.placeCost(x, ny, z);
        int j = MacroJump.steps(box, x, y, z, Axes.AXIS_Y, +1, moveCost,
                ctx.goalX(), ctx.goalY(), ctx.goalZ());

        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
        // Fold the same per-step edit the micro move makes, level by level: a support placed under each
        // rise, at floor cell (x, y+k, z) for k = 1..J. If a placement fails partway up, clamp J to the
        // last valid step (conservative — a shorter jump is always safe; plain A* fills the remainder).
        // EditScratch short-circuits once invalid (the failing requireFloor folds nothing), so at the
        // break the scratch holds exactly the places for steps 1..k-1 — the emitted prefix's edit-set.
        for (int k = 1; k <= j; k++) {
            // Re-evaluate RISKY_EDIT per level (the start footing k==1 was gated by reset above): don't place
            // into a cell whose body space risks a fluid/gravity cascade just because the start was safe — the
            // micro move re-checks per node, so the macro must too. Clamp the jump below a risky cell.
            if (k > 1 && MovementContext.risksEdit(ctx.flagsAt(x, y + k, z))) { j = k - 1; break; }
            e.requireFloor(x, y + k, z);
            if (!e.valid()) { j = k - 1; break; }
        }
        if (j < 1) return; // nothing valid

        // Validate the bot's BODY at the final landing. The bot ends standing on the footing at y+J, so it
        // occupies feet cell y+J+1 AND head cell y+J+2. The cuboid certifies cells up to its top are air, but
        // when the jump is bound by the box's travel extent the landing body can poke OUT the top of the
        // column (into the ceiling/terrain just past maxY that the box stopped at) — those cells are NOT
        // certified and may be solid. So check both explicitly (break-fold if the bot may break). For J==1
        // the feet cell y+2 is the old head, already clear by the node body-clearance invariant, so this
        // requireAir folds nothing → byte-for-byte parity with the micro head-only check.
        e.requireAir(x, y + j + 1, z); // feet at landing (new cell for J>1; old head for J==1)
        e.requireAir(x, y + j + 2, z); // head at landing
        if (!e.valid()) {
            // The full-J landing pokes into a non-uniform ceiling. Don't drop the move (that would forbid
            // even a 1-step pillar and break vertical reachability) — fall back to the micro single step,
            // which is always-correct and complete (it fails to emit only when a true 1-block pillar is
            // itself blocked, exactly as the micro move would).
            e = ctx.edits().reset(!MovementContext.risksEdit(flags));
            e.requireFloor(x, ny, z);
            e.requireAir(x, y + 3, z);
            if (e.valid()) out.accept(x, ny, z, COST + e.extraCost(), e);
            return;
        }

        int dy = Axes.stepY(Axes.AXIS_Y, +1) * j; // = j
        out.accept(x, y + dy, z, j * COST + e.extraCost(), e);
    }

    /**
     * Pillar's footing is placed in the bot's OWN feet cell, so it must wait until the bot has jumped clear
     * (airborne) — placing it while still standing there would set a block inside the bot.
     */
    @Override
    public boolean editsReadyNow(BotSteering b) {
        return !b.grounded();
    }

    /**
     * Jump straight up in place while staying centred on the column: re-centre on the column every tick (so a
     * jump-bump or residual momentum can't carry the bot sideways — the fix for the "pillar up a few, then
     * drift sideways, then resume" wander) and hold the jump input. The held jump leaves the feet cell so
     * {@link #editsReadyNow} can fold the footing under it, and it climbs a flooded shaft for free (jump =
     * swim-up in water). Re-centre, not a forward shove, because there is no horizontal target to walk to.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.recenterOnTarget(b, path);
        b.setJumping(true);
    }

    /**
     * "Reached" only once the bot is actually STANDING on the new footing (grounded at the destination cell) —
     * not while it passes the destination Y airborne at the jump apex. The default block-exact test would fire
     * mid-jump and advance the waypoint before the apex placement completes; requiring {@code grounded} keeps
     * the step alive until the footing is placed and landed on (the natural place-at-apex, then settle order).
     */
    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        return b.grounded() && b.footX() == wx && b.footY() == wy && b.footZ() == wz;
    }

    /**
     * The phase-model execution plan (Stage 2 — the first move converted from the {@code steer} + one-shot-edit
     * path to a live-geometry reconcile). Pillar is <b>JUMP &rarr; PLACE &rarr; LAND</b>: rise off the from-floor
     * {@code (fx,fy,fz)}, place the footing in the vacated cell {@code (fx,fy+1,fz)} once airborne over it, and
     * settle onto it. Break/place are declared as needs and established against the LIVE world each tick, so a
     * missed head-clear or footing self-heals; the ordering (place only after airborne) rides the phase order,
     * and the regression guard restarts the climb if a placement never took and the bot fell back.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final double startFeetY = fy + 1.0;        // feet world Y standing on the from-floor
        final int landedFeetBlockY = fy + 2;       // feet BLOCK Y once standing on the placed footing (fy+1)
        MovePlan plan = new MovePlan();
        // Fell back to (or below) the start with no height gained → the footing never took; re-attempt.
        plan.resetWhen(b -> b.grounded() && b.y() < startFeetY + 0.5);
        // JUMP: clear the takeoff head cell if it's solid, then recenter + hold jump until the feet have
        // CLEARED the footing cell (world Y >= fy+2, the cell's top) — so the footing is placed BENEATH the bot
        // at the apex, like a real player, not inside itself the instant it leaves the ground.
        plan.phase("jump")
                .need(MovePlan.Need.AIR, fx, fy + 3, fz)
                .drive((b, v) -> { SteerControl.recenterOnTarget(b, v); b.setJumping(true); })
                .advanceWhen(b -> b.y() >= fy + 2.0);
        // PLACE: airborne over the vacated cell — place the footing there; advance once it's solid.
        plan.phase("place")
                .need(MovePlan.Need.FOOTING, fx, fy + 1, fz)
                .drive(SteerControl::recenterOnTarget)
                .advanceWhen(b -> b.solidAt(fx, fy + 1, fz));
        // LAND: settle straight down onto the new footing; complete once standing on it.
        plan.phase("land")
                .drive(SteerControl::recenterOnTarget)
                .done(b -> b.grounded() && b.footY() == landedFeetBlockY);
        return plan;
    }
}
