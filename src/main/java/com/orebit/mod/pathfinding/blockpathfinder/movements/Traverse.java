package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
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
 * {@code J × per-step + Σ per-cell pass-through surcharge + e.extraCost()} — exactly the {@code N ×
 * per-step} of MACRO-MOVEMENTS §3b (the slow-FLOOR term rides the per-step cost, uniform over the run by
 * cuboid construction, so a collapsed soul-sand run charges the surcharge for EVERY cell; the body
 * hazard/through-slow term is accumulated per cell off the flags read the per-cell risky-edit check
 * already makes).
 *
 * <p>The macro emit is gated on {@link BlockPathfinder#MACRO_MOVES} <b>and</b> {@code ctx.cuboids() != null}.
 * When the flag is off or no cuboids view is present (legacy / unbounded search), the flat-walk case emits
 * the ORIGINAL single micro candidate byte-for-byte (legacy parity is required). A direction not toward the
 * goal gets {@code goalBound == 0 → J == 1}, i.e. it naturally degrades to the plain micro step. The
 * <b>step-assist</b> and <b>bridge</b> variants are left as their existing single-step emits (step-assist
 * is a vertical level change and bridge places a throwaway floor — neither is a uniform flat run).
 */
public final class Traverse implements Movement {

    /**
     * Flat-walk base cost, in <b>ticks</b> (the search's whole cost unit is real game ticks; 20 ticks = 1 s).
     * Seeded from Baritone's {@code WALK_ONE_BLOCK_COST = 20 / 4.317 ≈ 4.633} — the time to walk one block at
     * vanilla ground speed (4.317 m/s). This is the per-block "ruler" every other cost (and the octile
     * heuristic, via {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}) is measured against,
     * so "mine vs. walk around" is a true time comparison in one unit (physically-derived-costs memory).
     * Source: Baritone {@code baritone.api.pathing.movement.ActionCosts.WALK_ONE_BLOCK_COST}.
     */
    public static final float FLAT_COST = 4.633f;
    /**
     * Extra ticks for crossing a slow surface (soul sand / honey / cobweb / slime), on top of {@link
     * #FLAT_COST}. Baritone walks soul sand at ~0.4× speed ({@code WALK_ONE_OVER_SOUL_SAND_COST ≈
     * WALK_ONE_BLOCK_COST / 0.4 ≈ 11.6}), i.e. ~2.5× the flat walk; the surcharge is that delta
     * ({@code ≈ 11.6 − 4.633 ≈ 7.0}). Source: Baritone {@code ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST}.
     */
    public static final float SLOW_SURCHARGE = 7.0f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground walk — only while upright
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
                    // Legacy micro emit — the pre-macro flat-walk single step, plus the pass-through
                    // hazard/slow surcharge for the destination body (zero-read when the flag bits are
                    // clear; the edit-folding form breaks through a bush/web where that's cheaper).
                    EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                    ctx.requireBodyClear(e, nx, y, nz, flags);
                    if (e.valid()) {
                        out.accept(nx, y, nz,
                                cost(ctx, pd) + ctx.bodyTransitCost(e, flags, nx, y, nz) + e.extraCost(), e);
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
                        out.accept(nx, uy, nz,
                                cost(ctx, pud) + ctx.bodyTransitCost(e, flags, nx, uy, nz) + e.extraCost(), e);
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
                    out.accept(nx, y, nz,
                            cost(ctx, pd) + ctx.bodyTransitCost(e, flags, nx, y, nz) + e.extraCost(), e);
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
        // dropped. The bound is measured from the first destination cell along the same (axis, sign). The
        // per-step cost handed to the hedge includes the FIRST cell's pass-through hazard/slow surcharge:
        // the floor navtype (and so the slow-floor term in cost()) is uniform over the run by cuboid
        // construction, and a run that starts in fire/webs gets a dearer per-step estimate → a SHORTER jump
        // (the conservative direction — it can only tighten the hedge, never sail past a cheap exit).
        //
        // KNOWN WEAKENER (recorded, not fixed): the hedge is sized from the START cell's transit ONLY. A
        // macro run whose first cell is CLEAN but which crosses hazard cells DOWNSTREAM gets the cheap
        // per-step estimate → a LONGER jump that swallows those hazard cells into one candidate. They are
        // still fully PRICED (the per-cell loop below accumulates every cell's surcharge into the emitted
        // cost — nothing is free), but the search loses the intermediate nodes it would branch away from,
        // so hazard AVOIDANCE inside a collapsed run is weakened — never zeroed — vs the micro search.
        // With damage now priced at caps.costPerHitpoint() per cell the swallowed cost is large, so a
        // swallowed-hazard jump usually loses to a clean alternative anyway; a per-cell hedge re-size is
        // the proper fix if this ever shows up in traces.
        float moveCost = cost(ctx, pd);
        float startTransit = ctx.bodyTransitCost(startFlags, nx, y, nz);
        int j = MacroJump.steps(box, nx, y, nz, axis, sign, moveCost + startTransit,
                ctx.goalX(), ctx.goalY(), ctx.goalZ());

        // Fold the J per-step requirements into one edit-set, re-running the SAME micro checks the flat walk
        // uses at each step. risksEdit gate is taken from the FIRST destination cell's flags (its body space
        // is the run's leading edge); a uniform run shares its hazard classification by construction. The
        // pass-through surcharge is accumulated PER CELL (body cells may differ along the run even when the
        // floor is uniform — fire sits on some of it), reusing the flags read the risky-edit check already
        // makes, so the collapsed run charges exactly what J micro steps would.
        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(startFlags));
        int valid = 0;
        float transit = 0f;
        for (int k = 1; k <= j; k++) {
            int cx = x + Axes.stepX(axis, sign) * k;
            int cz = z + Axes.stepZ(axis, sign) * k;
            // Re-evaluate RISKY_EDIT per cell (the start cell k==1 was gated by reset above): don't fold a
            // body break/footing place at a cell whose edit risks a fluid/gravity cascade just because the
            // run's leading edge was safe — the micro move re-checks per node. Clamp the run before it.
            int cellFlags = k == 1 ? startFlags : ctx.flagsAt(cx, y, cz);
            if (k > 1 && MovementContext.risksEdit(cellFlags)) { valid = k - 1; break; }
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
            // Charge the surcharge only for a step that stayed valid (a clamped step's cells are not
            // walked). The edit-folding form breaks through a bush/web cell where that's cheaper than
            // transiting it intact (the fold's cost rides e.extraCost, the transit charge is dropped);
            // the hedge above kept the non-folding startTransit estimate — conservative, it can only
            // shorten the jump.
            transit += ctx.bodyTransitCost(e, cellFlags, cx, y, cz);
            valid = k;
        }
        if (valid < 1) {
            return false; // nothing valid even at step 1 — let step-assist/bridge try this column
        }
        if (valid != j) {
            // The run clamped short of MacroJump's bound: re-fold exactly the valid steps so the emitted
            // edit-set carries no placement/break from the failed step. The transit accumulator is redone
            // too — the folding bodyTransitCost records break-throughs on the scratch just reset, and each
            // re-run cell repeats the identical fold-vs-transit decision it made above (steps 1..valid all
            // stayed valid, and nothing they read has changed).
            e = ctx.edits().reset(!MovementContext.risksEdit(startFlags));
            transit = 0f;
            for (int k = 1; k <= valid; k++) {
                int cx = x + Axes.stepX(axis, sign) * k;
                int cz = z + Axes.stepZ(axis, sign) * k;
                e.requireFloor(cx, y, cz);
                e.requireAir(cx, y + 1, cz);
                e.requireAir(cx, y + 2, cz);
                transit += ctx.bodyTransitCost(e, k == 1 ? startFlags : ctx.flagsAt(cx, y, cz), cx, y, cz);
            }
        }

        int dx = Axes.stepX(axis, sign) * valid;
        int dz = Axes.stepZ(axis, sign) * valid;
        out.accept(x + dx, y, z + dz, valid * moveCost + transit + e.extraCost(), e);
        return true;
    }

    /**
     * The phase-model execution plan (Stage 2 — Traverse converted from the {@code steer} + one-shot-edit path
     * to a live-geometry reconcile). Traverse produces <b>four</b> step shapes, all distinguishable from the
     * search-native FLOOR cells {@code (fx,fy,fz) → (tx,ty,tz)} alone, and this plan re-establishes exactly the
     * cells {@link #candidates} folded into each shape's {@link EditScratch}:
     *
     * <ul>
     *   <li><b>Flat micro</b> ({@code ddy==0}, one cell, no place) — clear the two body cells and walk on.
     *   <li><b>Bridge micro</b> ({@code ddy==0}, one cell) — place the destination floor, then the two body cells.
     *   <li><b>Macro flat run</b> ({@code ddy==0}, {@code J} cells on one cardinal axis) — one phase PER run cell.
     *   <li><b>Step-assist</b> ({@code ddy==1}, single low partial, no jump) — clear the two (raised) body cells.
     * </ul>
     *
     * <p><b>Why one phase per run cell.</b> A macro run can carry a break several cells ahead of the bot, but
     * {@link com.orebit.mod.BotMining} has a reach limit — a single phase that declared every run cell's needs
     * up front would {@code mine()} an out-of-reach cell forever and deadlock. So the horizontal run is modeled
     * as one {@code walk}<i>k</i> phase per cell: the bot walks up to cell {@code k}, and the phase entered
     * while it is still standing on cell {@code k-1} establishes cell {@code k}'s geometry from adjacency (bridge
     * / mine one plank ahead, step on it). A single-cell step collapses to exactly one (terminal) phase.
     *
     * <p><b>Transit vs. break falls out of {@code Need.AIR} for free.</b> The runner mines a {@code Need.AIR}
     * cell only while it is {@code solidAt} (movement-blocking); a passable-but-slow body cell (cobweb, berry
     * bush) is not solid → never mined → transited intact, while a solid obstruction (a leaf block) is mined.
     * That is exactly {@code candidates}' {@code bodyTransitCost}/{@code transitOrBreak} per-cell arbitration, so
     * declaring {@code Need.AIR} on the body cells covers every {@code breakThrough} fold without ever mining a
     * cell the search priced as intact transit.
     *
     * <p><b>Shapes we don't own</b> return {@code null} (stay legacy {@code steer}): a {@code ddy} outside
     * {0, +1}, a diagonal (both horizontal axes move — that is {@link Diagonal}'s), or a multi-cell {@code +1}
     * (step-assist is single-cell). The FOOTING gotcha is never triggered: every declared FOOTING sits under a
     * cell the bot stands ON (never inside), and each run cell's FOOTING is declared while the bot is still on
     * the previous cell, so it is never placed into an occupied cell.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        int ddx = tx - fx;
        int ddy = ty - fy;
        int ddz = tz - fz;

        // Recognize only Traverse's own shapes; anything else stays on the legacy steer path.
        if (ddy != 0 && ddy != 1) return null;                              // Traverse is flat or +1 only
        boolean cardinal = (ddx == 0) ^ (ddz == 0);                        // exactly one horizontal axis moves
        if (!cardinal) return null;                                        // a diagonal belongs to Diagonal
        if (ddy == 1 && Math.abs(ddx) + Math.abs(ddz) != 1) return null;   // step-assist is single-cell

        // Cold per-step math, done ONCE at build time — every per-tick predicate below is a couple of int
        // compares plus the cheap along-axis multiply-add (one of sx/sz is 0 by the cardinal test).
        final int sx = Integer.signum(ddx);
        final int sz = Integer.signum(ddz);
        final int n = Math.abs(ddx) + Math.abs(ddz);                       // run length (1 for micro / step-assist)

        MovePlan plan = new MovePlan();

        // Case B — step-assist (ddy == 1): a single low partial auto-stepped (~0.6 block) with NO jump. The body
        // cells sit one higher (candidates' uy = fy+1), so AIR at (tx,ty+1,tz) and (tx,ty+2,tz); the destination
        // floor is already standable, so NO footing (faithful to candidates, which folds no place here).
        if (ddy == 1) {
            // Inert for a one-phase plan, but set for uniformity: physically regressed to the from-cell.
            plan.resetWhen(b -> b.grounded()
                    && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
            plan.phase("stepup")
                    .need(MovePlan.Need.AIR, tx, ty + 1, tz)                // = (tx, fy+2, tz): above the raised floor
                    .need(MovePlan.Need.AIR, tx, ty + 2, tz)                // = (tx, fy+3, tz)
                    .drive(SteerControl::drive)                             // hold forward + face; vanilla auto-steps the lip
                    .done(b -> b.grounded()
                            && b.footX() == tx && b.footY() == ty + 1 && b.footZ() == tz);
            return plan;
        }

        // Case A — horizontal run (ddy == 0): flat / bridge / macro, one phase per run cell. The reset guard is
        // only consulted once the cursor has advanced: the run physically fell back to its start cell.
        plan.resetWhen(b -> b.grounded()
                && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
        for (int k = 1; k <= n; k++) {
            final int kk = k;
            final int cx = fx + sx * k;
            final int cz = fz + sz * k;
            MovePlan.Phase ph = plan.phase("walk" + k)
                    .need(MovePlan.Need.FOOTING, cx, fy, cz)               // plank under the cell (bridge places; flat/macro noop)
                    .need(MovePlan.Need.AIR, cx, fy + 1, cz)               // feet-body cell clear (mine a solid, leave slow-passable)
                    .need(MovePlan.Need.AIR, cx, fy + 2, cz)               // head-body cell clear
                    .drive(SteerControl::drive);                           // medium-aware line-track walk (Traverse's default)
            if (k < n) {
                // Non-terminal: advance once grounded AT OR PAST cell k. Progress is monotone along the cardinal
                // line (one of sx/sz is 0), so >= is skip-proof against a lag tick — at walk speed a cell is
                // never skipped anyway, but >= cascades cleanly if it ever were.
                ph.advanceWhen(b -> b.grounded()
                        && (b.footX() - fx) * sx + (b.footZ() - fz) * sz >= kk);
            } else {
                // Terminal: the whole move is done standing on the to-cell.
                ph.done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == ty + 1 && b.footZ() == tz);
            }
        }
        return plan;
    }

    private static float cost(MovementContext ctx, long d) {
        return ctx.isSlow(d) ? FLAT_COST + SLOW_SURCHARGE : FLAT_COST;
    }
}
