package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Cuboid;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.MacroJump;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;

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
 *
 * <h2>Macro-awareness (cuboid collapse — MACRO-IMPLEMENTATION.md §8.1)</h2>
 *
 * <p>The <b>flat-walk</b> case is macro-aware: instead of always emitting a single one-step candidate, it
 * collapses a uniform run of flat walks into ONE jump candidate via {@link MacroJump}. For each of the
 * four cardinal directions it resolves that direction's maximal uniform {@link Cuboid}
 * ({@link NavGridCuboidsView#cuboidAt}) and lets {@link MacroJump#steps} bound the jump length {@code J}
 * (box edge, goal projection, and the cost-normalised escape-hedge of NON-NEGOTIABLE 2). The {@code J}
 * per-step floor/body requirements are then folded into one {@link EditScratch} by re-running the SAME
 * micro checks ({@link EditScratch#requireFloor} under each cell, {@link MovementContext#requireBodyClear}
 * for each cell's body) at step {@code k = 1..J}; the first failing step clamps {@code J} (conservative —
 * an under-jump is always safe, a plain A* step fills the gap). The single emitted candidate costs
 * {@code J × FLAT_COST + e.extraCost()} — exactly the {@code N × per-step} of MACRO-MOVEMENTS §3b.
 *
 * <p>The macro emit is gated on {@link BlockPathfinder#MACRO_MOVES} <b>and</b> {@code ctx.cuboids() != null}.
 * When the flag is off or no cuboids view is present (legacy / unbounded search), the flat-walk case emits
 * the ORIGINAL single micro candidate byte-for-byte (legacy parity is required). A direction not toward the
 * goal gets {@code goalBound == 0 → J == 1}, i.e. it naturally degrades to the plain micro step. The
 * <b>step-assist</b> and <b>bridge</b> variants are left as their existing single-step emits (step-assist
 * is a vertical level change and bridge places a throwaway floor — neither is a uniform flat run).
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

            // The same-level neighbour floor (nx,y,nz) drives both the flat-walk and the bridge case — read
            // its grid slot ONCE and derive built-ness / descriptor / flags from it (no second resolve).
            int p = ctx.packedAt(nx, y, nz);
            boolean built = p != MovementContext.UNBUILT;
            long pd = built ? ctx.descriptorOf(nx, y, nz, p) : 0L;
            boolean standable = built && ctx.standable(pd);

            // Flat walk onto an adjacent solid-topped cell. The two body cells must be clear; a block in
            // the way (e.g. leaves) is folded into a break-set when the bot may break, raising the cost
            // instead of failing the move (MOVEMENT-DESIGN.md §1 — the motivating forest-leaves case).
            if (standable) {
                int flags = MovementContext.flagsOf(p);

                // Macro path: collapse a uniform flat run into a single jump candidate. Gated on the master
                // flag, a present cuboids view, AND (Option B) this cardinal's travel axis being the search's
                // primary axis P — an off-P direction skips cuboidAt + MacroJump and takes its plain micro step,
                // so a uniform region is extracted on ONE axis only (CUBOID-PERF-OPTIONS.md). A flat walk
                // travels X or Z; derive its axis from the cardinal's (dx,dz) step.
                int travelAxis = d[0] != 0 ? Axes.AXIS_X : Axes.AXIS_Z;
                NavGridCuboidsView cuboids = ctx.cuboids();
                if (BlockPathfinder.MACRO_MOVES && cuboids != null && travelAxis == ctx.macroAxis()) {
                    if (emitMacro(ctx, out, cuboids, x, y, z, nx, nz, d, pd, flags)) {
                        continue; // already have footing here; don't also step-assist/bridge this column
                    }
                    // Macro produced nothing (J<1 with no valid step) — try step-assist/bridge below.
                } else {
                    // Legacy micro emit — byte-for-byte the pre-macro flat-walk single step.
                    EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                    ctx.requireBodyClear(e, nx, y, nz, flags);
                    if (e.valid()) {
                        out.accept(nx, y, nz, cost(ctx, pd) + e.extraCost(), e);
                        continue; // already have footing here; don't also step-assist/bridge this column
                    }
                }
            }

            // Step-assist: one cell up onto a low partial (slab / snow / stair lip) — no jump. Same
            // break-the-body-path modifier as the flat case. Distinct cell, so its own single resolve.
            int uy = y + 1;
            int pu = ctx.packedAt(nx, uy, nz);
            if (pu != MovementContext.UNBUILT) {
                long pud = ctx.descriptorOf(nx, uy, nz, pu);
                if (ctx.standable(pud) && ctx.topYOf(pud) <= MovementContext.STEP_ASSIST_MAX_TOP_Y) {
                    int flags = MovementContext.flagsOf(pu);
                    EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                    ctx.requireBodyClear(e, nx, uy, nz, flags);
                    if (e.valid()) {
                        out.accept(nx, uy, nz, cost(ctx, pud) + e.extraCost(), e);
                        continue;
                    }
                }
            }

            // Bridge: no footing in the neighbour column — place a throwaway floor and walk onto it when
            // the bot may place (the source cell is always an adjacent face to build against). "Bridge"
            // is not its own movement, just Traverse with a place in its edit-set (decision 1). Reuses the
            // same-level slot read at the top of the loop.
            if (built && !standable) {
                int flags = MovementContext.flagsOf(p);
                EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                e.requireFloor(nx, y, nz);
                ctx.requireBodyClear(e, nx, y, nz, flags);
                if (e.valid()) {
                    out.accept(nx, y, nz, cost(ctx, pd) + e.extraCost(), e);
                }
            }
        }
    }

    /**
     * The macro flat-walk: resolve this cardinal's uniform cuboid, bound the jump, fold the {@code J}
     * per-step floor + body requirements, and emit ONE candidate at the jump distance. Returns
     * {@code true} when a candidate was emitted (the caller then skips step-assist/bridge for this
     * column), {@code false} when nothing valid remained (caller falls through to step-assist/bridge).
     *
     * <p>The cardinal direction {@code (d[0], d[1])} is converted to an {@code (axis, sign)} pair (a flat
     * walk is always X or Z travel, never Y). {@link MacroJump} returns {@code J == 1} for a direction not
     * toward the goal ({@code goalBound == 0}) or where the cuboid is invalid/degenerate, so the
     * "single micro step" case is folded into this same loop with no special-casing.
     */
    private static boolean emitMacro(MovementContext ctx, CandidateSink out, NavGridCuboidsView cuboids,
                                     int x, int y, int z, int nx, int nz, int[] d, long pd, int startFlags) {
        // A cardinal flat walk travels along X or Z. Derive (axis, sign) from the (dx,dz) step.
        int axis = d[0] != 0 ? Axes.AXIS_X : Axes.AXIS_Z;
        int sign = d[0] != 0 ? d[0] : d[1];

        // The maximal uniform box containing the FIRST destination cell (nx,y,nz), resolved over committed
        // navtypes with the search's speculative PathEdits applied. cuboidScratch() is a per-context reusable
        // Cuboid — no per-query allocation (HOT-PATH-NO-ALLOC).
        Cuboid box = ctx.cuboidScratch();
        cuboids.cuboidAt(nx, y, nz, axis, sign, box); // cardinal travel direction (Option D forward clip)

        // Jump length, bounded by the box edge (HARD), goal projection (HARD), and the cost-normalised
        // escape-hedge (NON-NEGOTIABLE 2). MacroJump divides the orthogonal face by the move cost — never
        // dropped. The bound is measured from the first destination cell along the same (axis, sign).
        float moveCost = cost(ctx, pd);
        int j = MacroJump.steps(box, nx, y, nz, axis, sign, moveCost, ctx.goalX(), ctx.goalY(), ctx.goalZ());

        // Fold the J per-step requirements into one edit-set, re-running the SAME micro checks the flat walk
        // uses at each step. risksEdit gate is taken from the FIRST destination cell's flags (its body space
        // is the run's leading edge); a uniform run shares its hazard classification by construction.
        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(startFlags));
        int valid = 0;
        for (int k = 1; k <= j; k++) {
            int cx = x + Axes.stepX(axis, sign) * k;
            int cz = z + Axes.stepZ(axis, sign) * k;
            // Re-evaluate RISKY_EDIT per cell (the start cell k==1 was gated by reset above): don't fold a
            // body break/footing place at a cell whose edit risks a fluid/gravity cascade just because the
            // run's leading edge was safe — the micro move re-checks per node. Clamp the run before it.
            if (k > 1 && MovementContext.risksEdit(ctx.flagsAt(cx, y, cz))) { valid = k - 1; break; }
            // Footing under the k-th cell (already standable for a flat run; placeable fallback for a bridge
            // cell that crept into the run), then the two body cells above it. Same vocabulary as the micro
            // move's requireBodyClear, but read per cell so each step's headroom is verified.
            e.requireFloor(cx, y, cz);
            e.requireAir(cx, y + 1, cz);
            e.requireAir(cx, y + 2, cz);
            if (!e.valid()) {
                // Conservative clamp: the first failing step ends the run; everything up to it stayed valid.
                // We must drop the partial edits this step folded before it failed, so re-fold steps 1..k-1
                // cleanly (the run is short — corridor-bounded — so this is a handful of reads, not a hot
                // cost). An under-jump is always safe; a plain A* step fills the remaining gap.
                valid = k - 1;
                break;
            }
            valid = k;
        }
        if (valid < 1) {
            return false; // nothing valid even at step 1 — let step-assist/bridge try this column
        }
        if (valid != j) {
            // The run clamped short of MacroJump's bound: re-fold exactly the valid steps so the emitted
            // edit-set carries no placement/break from the failed step.
            e = ctx.edits().reset(!MovementContext.risksEdit(startFlags));
            for (int k = 1; k <= valid; k++) {
                int cx = x + Axes.stepX(axis, sign) * k;
                int cz = z + Axes.stepZ(axis, sign) * k;
                e.requireFloor(cx, y, cz);
                e.requireAir(cx, y + 1, cz);
                e.requireAir(cx, y + 2, cz);
            }
        }

        int dx = Axes.stepX(axis, sign) * valid;
        int dz = Axes.stepZ(axis, sign) * valid;
        out.accept(x + dx, y, z + dz, valid * moveCost + e.extraCost(), e);
        return true;
    }

    private static float cost(MovementContext ctx, long d) {
        return ctx.isSlow(d) ? FLAT_COST + SLOW_SURCHARGE : FLAT_COST;
    }
}
