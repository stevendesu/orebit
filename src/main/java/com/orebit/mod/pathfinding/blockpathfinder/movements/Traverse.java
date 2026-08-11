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
 *       above it. Same-level is NOT automatically flat: the LIP between the two floors' real tops
 *       ({@code destTopY − startTopY}, sixteenths) must be ≤ {@link MovementContext#STEP_ASSIST_MAX_RISE}
 *       — walking from a very low partial (a 2/16 repeater plate) onto a full block is a 14/16 rise no
 *       auto-step clears. A same-level lip of 10..20 sixteenths is {@link Ascend}'s <b>same-level jump
 *       arm</b> (owner-ratified, DESIGN-trapdoors.md §6 — the historical "one-way plate pocket" gap is
 *       closed): Traverse still owns everything within the auto-step budget, Ascend owns the jumpable
 *       band above it.
 *   <li><b>Step-assist</b> (one cell up onto a low partial) — a slab / snow-layer / stair lip is
 *       auto-stepped (~0.6 blocks) without a jump when the RISE from the start floor's top to the
 *       destination floor's top ({@link MovementContext#rise}: {@code 16 + destTopY − startTopY}) is
 *       ≤ {@link MovementContext#STEP_ASSIST_MAX_RISE} sixteenths. Both ends' partial heights count:
 *       full → slab one up is {@code 16 + 8 − 16 = 8 ≤ 9} (auto-step), slab → slab one up is
 *       {@code 16 + 8 − 8 = 16 > 9} (that's {@link Ascend}'s jump). This is the visible "uses stairs
 *       naturally" behaviour, and it falls straight out of the {@code topY} fact — no jump means the
 *       follower must <i>not</i> trigger one, which is why this is a distinct movement from
 *       {@link Ascend}.
 * </ul>
 *
 * <p><b>Body clearance via the resident bit.</b> The two body cells above a destination floor are checked
 * through the precomputed {@code HEADROOM} flag ({@link MovementContext#requireBodyClear}) — <b>ZERO</b>
 * extra grid reads instead of two {@code descriptorAt} probes (the flags ride the {@code packedAt} slot
 * already resolved for the standability test, and {@code requireBodyClearToward} takes them as a
 * parameter) — falling back to per-cell reads (which also fold breaks)
 * only when the bit can't be trusted near a section face or when the bot must mine its way through. The
 * fallback applies the §4 residual rules face-aware (DESIGN-trapdoors.md): the dest floor's real
 * {@code topY} is threaded so a ≤3/16 plate floor skips its head cell and a slab floor admits a flush
 * top-band ceiling, and openable body cells pass free / fold SETs per their blocked face.
 *
 * <p><b>Close-and-stand ({@code requireFloorOrToggle}, DESIGN-trapdoors.md §5).</b> A non-standable dest
 * floor cell that is a toggleable OPEN trapdoor folds a {@code SET_CLOSED} — the closed state (a 3/16
 * plate for a BOTTOM half, a flush 16/16 hatch for a TOP half) is standable by construction — and the
 * rise gates then judge the TOGGLED topY: the flat arm admits a same-level close (step down onto the
 * plate / walk onto the flush hatch), the step-assist arm admits the one-up close-and-step (the §5
 * "closing a panel creates a plate" emission, {@code rise(1,3,16)=3 ≤ 9}); a toggled rise past the
 * auto-step budget is left to {@link Ascend}'s jump arms. The exit gate ({@link
 * MovementContext#exitDoorDecision}) covers a feet-cell OPEN trapdoor's panel face identically to a door
 * (registered by {@code setCurrentDoorEdge}, folded single-cell), and the macro collapse already skips
 * when an exit toggle is owed — trapdoor exits included.
 *
 * <h2>Macro-awareness (cuboid collapse — MACRO-IMPLEMENTATION.md §8.1)</h2>
 *
 * <p>The <b>flat-walk</b> case is macro-aware: instead of always emitting a single one-step candidate, it
 * collapses a uniform run of flat walks into ONE jump candidate via {@link MacroJump}. For each of the
 * four cardinal directions it resolves that direction's maximal uniform {@link Cuboid}
 * ({@link NavGridCuboidsView#cuboidAt}) and lets {@link MacroJump#steps} bound the jump length {@code J}
 * (box edge, goal projection, and the cost-normalised escape-hedge of NON-NEGOTIABLE 2). The {@code J}
 * per-step floor/body requirements are then folded into one {@link EditScratch} at step {@code k = 1..J}:
 * {@link EditScratch#requireFloor} under each cell, then the body via TWO direct
 * {@link EditScratch#requireAirToward} calls ({@code y+1}, {@code y+2}). <b>This is NOT the micro emit's
 * body check</b> — the macro run does not go through {@link MovementContext#requireBodyClear}, so it never
 * reaches the {@code HEADROOM} zero-read fast path and pays two descriptor reads per step (recorded
 * 2026-08-11; this previously claimed the SAME micro checks were re-run, which mis-states macro read
 * cost). The first failing step clamps {@code J} (conservative —
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
     * Cost MULTIPLIER for a move onto a slow floor (vanilla {@code speedFactor} 0.4 — soul sand, honey —
     * ⇒ {@code 1/0.4 = 2.5×} the move's own base time). A multiplier, NOT a flat surcharge (owner ruling,
     * s52b): a diagonal covers 1.41× the distance of a cardinal step, so its slow penalty must scale with
     * the move's base cost — the old flat {@code SLOW_SURCHARGE = 7.0} undercharged every longer move.
     * For the cardinal walk the two agree by construction ({@code 4.633 × 2.5 ≈ 11.6}, Baritone's
     * {@code WALK_ONE_OVER_SOUL_SAND_COST}).
     */
    public static final float SLOW_COST_FACTOR = 1f / 0.4f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground walk — only while upright
        // The START surface height (sixteenths): every rising-lip test below measures from this real
        // surface (a slab start is 8/16 lower than a full block). floorSurface, not raw topY — a
        // surface-swim node's water "floor" reads as 16 (feet at the cell boundary), so walking OUT of
        // water onto the bank keeps its historical zero-lip geometry. Read the start descriptor ONCE and
        // derive both the scalar surface and stair-ness from it (one read per expansion, cache-served).
        final long startDesc = ctx.descriptorAt(x, y, z);
        final int startTopY = ctx.standable(startDesc) ? ctx.topYOf(startDesc) : 16; // == floorSurface
        final boolean startStair = ctx.isStair(startDesc); // a stair start's surface is DIRECTIONAL per move
        // NO AUTO-STEP FROM A PRECARIOUS CLIMB STANCE (owner physics, 2026-08-01). Vanilla's auto-step needs
        // the bot to be standing on a real, FULL-FACED floor, which two climb stances are not:
        //   CURTAIN (vine — climbable, non-standable): hanging inside it, or hovering on its top, there is
        //       no ground contact at all. "You may only traverse onto a block whose top equals your feet
        //       height — never UP onto a slab / stair / snow layer."
        //   LADDER TOP (climbable, NARROW_TOP): the 3/16 plate does support a step, but only toward the
        //       face the ladder is mounted on — stepping the other way just walks off the ledge. FACING is
        //       not packed in the descriptor, so the direction that works is unknowable here and the whole
        //       case is refused rather than guessed.
        // Scoped to CLIMBABLE stances on purpose: a scaffold DECK is full-faced real footing and keeps its
        // auto-step, and water is not climbable, so the surface-swim / walk-out-onto-a-bank geometry above
        // is untouched. Only the step-ASSIST branch consults this — the FLAT walk stays legal (it IS the
        // lateral cling and the step off a curtain top onto a level block), as does the BRIDGE (place a full
        // block beside you and walk on level).
        final boolean noAutoStepFromStance = ctx.isClimbable(startDesc) && !ctx.parkourLandable(startDesc);
        for (int[] d : CARDINALS) {
            // §2b door EXIT: when the bot STANDS in an intact door (open OR closed) whose swung panel blocks THIS
            // travel edge, a plain walk can't leave that way. The shared exitDoorDecision (also used by Ascend /
            // Descend) is EXIT_CLEAR off any door (the common case), EXIT_BLOCKED for an iron / flag-off door
            // (skip the direction, P1), or EXIT_TOGGLE for a hand-toggleable feet door (P2): fold a door SET to
            // the opposite state onto each variant below to free the exit — state-agnostic, so it re-opens a door
            // that entry already toggled shut.
            int exitDoor = ctx.exitDoorDecision(x, y, z, d[0], d[1]);
            if (exitDoor == MovementContext.EXIT_BLOCKED) continue;
            boolean exitDoorToggle = exitDoor == MovementContext.EXIT_TOGGLE;
            int nx = x + d[0];
            int nz = z + d[1];
            // The START surface toward THIS neighbour: directional for a stair takeoff (high 16/16 on the
            // FACING side, low 8/16 opposite), the hoisted scalar for everything else (byte-identical).
            int sTop = startStair ? ctx.directionalTopY(startDesc, d[0], d[1]) : startTopY;

            // The same-level neighbour floor (nx,y,nz) drives both the flat-walk and the bridge case — read
            // its grid slot ONCE and derive built-ness / descriptor / flags from it (no second resolve).
            int p = ctx.packedAt(nx, y, nz);
            boolean built = p != MovementContext.UNBUILT;
            long pd = built ? ctx.descriptorOf(nx, y, nz, p) : 0L;
            boolean standable = built && ctx.standable(pd);

            // Flat walk onto an adjacent solid-topped cell. The two body cells must be clear; a block in
            // the way (e.g. leaves) is folded into a break-set when the bot may break, raising the cost
            // instead of failing the move (MOVEMENT-DESIGN.md §1 — the motivating forest-leaves case).
            // START-SIDE lip gate: same-level is only "flat" when the rising lip between the two floors'
            // real tops (destTopY − startTopY, sixteenths) fits the auto-step — a low-partial start onto
            // a full block can be a 10..14/16 rise no step assist clears (class Javadoc; a NEGATIVE lip,
            // stepping DOWN off a slab, is always walkable and passes). This gate covers the macro path
            // too: a cuboid run is navtype-uniform, so within the run the lip is 0 — only the entry lip
            // from the start cell can differ, and it is exactly what's tested here.
            if (standable
                    && MovementContext.rise(0, ctx.directionalTopY(pd, -d[0], -d[1]), sTop)
                            <= MovementContext.STEP_ASSIST_MAX_RISE) {
                int flags = MovementContext.flagsOf(p);

                // Macro path: collapse a uniform flat run into a single jump candidate. Gated on the master
                // flag, a present cuboids view, AND (Option B) this cardinal's travel axis being the search's
                // primary axis P — an off-P direction skips cuboidAt + MacroJump and takes its plain micro step,
                // so a uniform region is extracted on ONE axis only (the primary travel axis). A flat walk
                // travels X or Z; derive its axis from the cardinal's (dx,dz) step.
                int travelAxis = d[0] != 0 ? Axes.AXIS_X : Axes.AXIS_Z;
                NavGridCuboidsView cuboids = ctx.cuboids();
                // Skip the macro collapse when we owe an exit-door toggle: the macro's internal edit-set can't
                // carry the source door's SET_CLOSED, so force the micro emit (which folds it) — a conservative,
                // rare case (only when leaving a doorway through its swung-panel edge).
                if (BlockPathfinder.MACRO_MOVES && cuboids != null && travelAxis == ctx.macroAxis()
                        && !exitDoorToggle) {
                    if (emitMacro(ctx, out, cuboids, x, y, z, nx, nz, d, pd, flags)) {
                        continue; // already have footing here; don't also step-assist/bridge this column
                    }
                    // Macro produced nothing (J<1 with no valid step) — try step-assist/bridge below.
                } else {
                    // Legacy micro emit — the pre-macro flat-walk single step, plus the pass-through
                    // hazard/slow surcharge for the destination body (zero-read when the flag bits are
                    // clear; the edit-folding form breaks through a bush/web where that's cheaper).
                    EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                    if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z, d[0], d[1]);
                    // Dest floor top threaded for the §4 exact-fit body rules (a ≤3/16 plate floor skips the
                    // head cell; a slab floor admits a flush top-band ceiling). Plain topY — a stair's 16 is
                    // the conservative standing surface for clearance, matching the historical default.
                    ctx.requireBodyClearToward(e, nx, y, nz, flags, d[0], d[1], ctx.topYOf(pd));
                    if (e.valid()) {
                        out.accept(nx, y, nz,
                                cost(ctx, pd) + ctx.bodyTransitCost(e, flags, nx, y, nz) + e.extraCost(), e);
                        continue; // already have footing here; don't also step-assist/bridge this column
                    }
                }
            }

            // Close-and-stand, same level (DESIGN-trapdoors.md §5): the dest floor cell is a toggleable
            // OPEN trapdoor — fold a SET_CLOSED (requireFloorOrToggle) and stand on the closed state (a
            // 3/16 plate or a flush 16/16 hatch per its half), gating the rise on the TOGGLED topY: a
            // BOTTOM half is a −13 step down (always walks), a TOP half a 16−sTop lip (walks off a full
            // start; from a low partial it exceeds the auto-step and Ascend's same-level jump arm owns
            // it). Reached only behind the !standable failure path + one almost-always-false predicate,
            // so trapdoor-free worlds pay nothing new.
            if (built && !standable && ctx.trapdoorSetFloors(pd)) {
                int flags = MovementContext.flagsOf(p);
                EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z, d[0], d[1]);
                long fd = e.requireFloorOrToggle(nx, y, nz); // folds the SET_CLOSED; returns the toggled floor
                int fTop = ctx.topYOf(fd);
                if (e.valid()
                        && MovementContext.rise(0, fTop, sTop) <= MovementContext.STEP_ASSIST_MAX_RISE) {
                    ctx.requireBodyClearToward(e, nx, y, nz, flags, d[0], d[1], fTop);
                    if (e.valid()) {
                        out.accept(nx, y, nz,
                                cost(ctx, fd) + ctx.bodyTransitCost(e, flags, nx, y, nz) + e.extraCost(), e);
                        continue;
                    }
                }
            }

            // Step-assist: one cell up onto a low partial (slab / snow / stair lip) — no jump. The rise
            // is measured surface-to-surface (16 + destTopY − startTopY ≤ STEP_ASSIST_MAX_RISE), so a
            // partial START narrows what's auto-steppable (slab → slab one up is a 16/16 rise — Ascend's
            // jump, not a step). Same break-the-body-path modifier as the flat case. Distinct cell, so
            // its own single resolve.
            int uy = y + 1;
            int pu = ctx.packedAt(nx, uy, nz);
            if (pu != MovementContext.UNBUILT) {
                long pud = ctx.descriptorOf(nx, uy, nz, pu);
                if (!noAutoStepFromStance && ctx.standable(pud)
                        && MovementContext.rise(1, ctx.directionalTopY(pud, -d[0], -d[1]), sTop)
                                <= MovementContext.STEP_ASSIST_MAX_RISE) {
                    int flags = MovementContext.flagsOf(pu);
                    EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                    if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z, d[0], d[1]);
                    // Dest floor top threaded (§4 exact-fit) — the step-assist ONTO a 3/16 plate one up is
                    // exactly the case whose head cell must not be consulted (2-tall hallway hatch pocket).
                    ctx.requireBodyClearToward(e, nx, uy, nz, flags, d[0], d[1], ctx.topYOf(pud));
                    if (e.valid()) {
                        out.accept(nx, uy, nz,
                                cost(ctx, pud) + ctx.bodyTransitCost(e, flags, nx, uy, nz) + e.extraCost(), e);
                        continue;
                    }
                } else if (!noAutoStepFromStance && ctx.trapdoorSetFloors(pud)) {
                    // Close-and-stand, one up (§5): a toggleable OPEN trapdoor one cell up folds SET_CLOSED
                    // and the auto-step gates the TOGGLED topY — a closed BOTTOM half is the ratified
                    // "closing a panel creates a plate" emission (rise(1,3,16)=3 ≤ 9, the dy=+1 plate node
                    // the self-refusing flat arm hands over to); a closed TOP half (rise 16+16−sTop > 9)
                    // refuses here and is Ascend's jump-onto-hatch arm.
                    int flags = MovementContext.flagsOf(pu);
                    EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                    if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z, d[0], d[1]);
                    long fd = e.requireFloorOrToggle(nx, uy, nz);
                    int fTop = ctx.topYOf(fd);
                    if (e.valid()
                            && MovementContext.rise(1, fTop, sTop) <= MovementContext.STEP_ASSIST_MAX_RISE) {
                        ctx.requireBodyClearToward(e, nx, uy, nz, flags, d[0], d[1], fTop);
                        if (e.valid()) {
                            out.accept(nx, uy, nz,
                                    cost(ctx, fd) + ctx.bodyTransitCost(e, flags, nx, uy, nz) + e.extraCost(),
                                    e);
                            continue;
                        }
                    }
                }
            }

            // Bridge: no footing in the neighbour column — place a throwaway floor and walk onto it when
            // the bot may place (the source cell is always an adjacent face to build against). "Bridge"
            // is not its own movement, just Traverse with a place in its edit-set (decision 1). Reuses the
            // same-level slot read at the top of the loop. The placed plank is a full cube (top 16), so
            // walking onto it from a partial start is a rising lip of 16 − startTopY sixteenths — gated
            // by the same auto-step budget (a slab start's 8/16 lip walks; a 2/16 plate start's 14/16
            // lip doesn't).
            if (built && !standable
                    && MovementContext.rise(0, 16, sTop) <= MovementContext.STEP_ASSIST_MAX_RISE) {
                int flags = MovementContext.flagsOf(p);
                EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
                if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z, d[0], d[1]);
                e.requireFloor(nx, y, nz);
                ctx.requireBodyClearToward(e, nx, y, nz, flags, d[0], d[1]);
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
        // The door ENTRY edge is uniform along the run (each cell entered from the previous, same direction) —
        // so an already-open door in a collapsed run's body is crossed free rather than mined (§2a, P1).
        int entryEdge = MovementContext.ordinalOf(-Axes.stepX(axis, sign), -Axes.stepZ(axis, sign));

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
            e.requireAirToward(cx, y + 1, cz, entryEdge);
            e.requireAirToward(cx, y + 2, cz, entryEdge);
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
                e.requireAirToward(cx, y + 1, cz, entryEdge);
                e.requireAirToward(cx, y + 2, cz, entryEdge);
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
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
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
                    && atWaypoint(b, fx, fromFootY, fz));
            // Validity envelope (PATHOLOGY P1 family — the Parkour/Ascend failWhen precedent): settled
            // (grounded, or bodily in fluid — a displaced executor that fell into water is never grounded)
            // at a foot cell outside the step's two columns is off-plan: done/resetWhen can never fire
            // there and re-attempting latches. Allowed: the from stand, and the target column's transitional
            // band [fromFootY .. toFootY] (topY-aware — the auto-step's rise crosses the lower foot cell for a
            // tick; for full blocks this is the old [ty, ty+1]).
            final int bandLo = Math.min(fromFootY, toFootY);
            final int bandHi = Math.max(fromFootY, toFootY);
            plan.failWhen(b -> (b.grounded() || b.inWater() || b.inLava())
                    && !(atWaypoint(b, fx, fromFootY, fz))
                    && !(b.footX() == tx && b.footZ() == tz
                            && b.footY() >= bandLo && b.footY() <= bandHi));
            plan.phase("stepup")
                    .arrestCarryFrom(fx, fz)                                // align before the lip (90°-turn carry)
                    // FLOOR-frame body clearance — the same correction as the run branch below: candidates()
                    // folded requireBodyClearToward(e, nx, uy, nz, …) with uy = y+1, i.e. (ty+1, ty+2).
                    .need(MovePlan.Need.AIR, tx, ty + 1, tz)                // landing feet (above the raised floor)
                    .need(MovePlan.Need.AIR, tx, ty + 2, tz)                // landing head
                    .drive(SteerControl::drive)                             // hold forward + face; vanilla auto-steps the lip
                    .done(b -> b.grounded()
                            && atWaypoint(b, tx, toFootY, tz));
            return plan;
        }

        // Case A — horizontal run (ddy == 0): flat / bridge / macro, one phase per run cell. The reset guard is
        // only consulted once the cursor has advanced: the run physically fell back to its start cell. A macro
        // run is collapsed only from a UNIFORM run, so for n>1 every cell shares one surface height
        // (fromFootY == toFootY); wp.getY() (toFootY) is path-edit-aware, so a BRIDGED plank reads as a full
        // block (foot == floor+1). Using fromFootY for the start guard and toFootY for the run body is therefore
        // exact for the single-cell case and for the uniform macro run; for full blocks both are floor+1 → the
        // old fy+1 behaviour byte-for-byte.
        plan.resetWhen(b -> b.grounded()
                && atWaypoint(b, fx, fromFootY, fz));
        // Validity envelope (PATHOLOGY P1 family): settled off the run LINE is off-plan — e.g. dropped off
        // a ledge mid-run (the longrun-6 (120,64,18) latch: bot fell 3 below its Traverse and ground-looped
        // forever). Allowed: any foot cell ON the run line at the run's height band [min .. max of the two
        // foot heights] (walking the run through shallow water is legitimately in-fluid, so the line test — not
        // the medium — is the discriminator). Cardinal line: one of sx/sz is 0, so the along-axis projection
        // plus the cross-axis pin is two int compares.
        final int runLo = Math.min(fromFootY, toFootY);
        final int runHi = Math.max(fromFootY, toFootY);
        plan.failWhen(b -> {
            if (!(b.grounded() || b.inWater() || b.inLava())) {
                return false;
            }
            if (b.footY() < runLo || b.footY() > runHi) {
                return true; // off the run's height — fell off (or was lifted off) the line
            }
            final int along = (b.footX() - fx) * sx + (b.footZ() - fz) * sz;
            final boolean crossPinned = sx != 0 ? b.footZ() == fz : b.footX() == fx;
            return !(crossPinned && along >= 0 && along <= n);
        });
        for (int k = 1; k <= n; k++) {
            final int kk = k;
            final int cx = fx + sx * k;
            final int cz = fz + sz * k;
            MovePlan.Phase ph = plan.phase("walk" + k)
                    .need(MovePlan.Need.FOOTING, cx, fy, cz)               // plank under the cell (bridge places; flat/macro noop) — FLOOR-relative
                    // BODY CLEARANCE IS FLOOR-FRAME (corrected 2026-08-01), mirroring exactly what the
                    // search folded — MovementContext.requireBodyClearToward(e, nx, y, nz, …) over (y+1,
                    // y+2). Asking in the FEET frame collided with the FOOTING cell above whenever the
                    // destination floor is a standable PARTIAL: feetYOf returns fy for topY<16, so
                    // toFootY == fy and the plan demanded FOOTING and AIR at the SAME cell. On honey
                    // (topY 15) that made the executor place the floor and then mine it right back —
                    // convicted as the whole cause of owner.honeyflyover / .runup, where the bot teetered
                    // at maxProj 0.96 of 3.00 and never crossed. Restores this plan's own documented
                    // invariant ("re-establishes exactly the cells candidates folded ... without ever
                    // mining a cell the search priced as intact transit"). No block-type special case:
                    // the collision is removed by construction for every partial floor. topY<=9 (carpet /
                    // plate / bottom slab / snow) is behaviour-neutral — those needs were already no-ops
                    // under movementBlockedAt's 9/16 corridor floor.
                    .need(MovePlan.Need.AIR, cx, fy + 1, cz)               // feet-body cell clear (FLOOR-frame)
                    .need(MovePlan.Need.AIR, cx, fy + 2, cz)               // head-body cell clear (FLOOR-frame)
                    .drive(SteerControl::drive);                           // medium-aware line-track walk (Traverse's default)
            if (k == 1) {
                // Only the FIRST run cell commits out of the start column, so only it can be entered with a
                // cross-axis carry from the previous step (the chained 90° turn). Cells k>=2 are already
                // travelling along this line — their cross velocity is the servo's own residual, and gating
                // them would stutter every cell crossing.
                ph.arrestCarryFrom(fx, fz);
            }
            if (k < n) {
                // Non-terminal: advance once grounded AT OR PAST cell k. Progress is monotone along the cardinal
                // line (one of sx/sz is 0), so >= is skip-proof against a lag tick — at walk speed a cell is
                // never skipped anyway, but >= cascades cleanly if it ever were.
                ph.advanceWhen(b -> b.grounded()
                        && (b.footX() - fx) * sx + (b.footZ() - fz) * sz >= kk);
            } else {
                // Terminal: the whole move is done standing on the to-cell.
                ph.done(b -> b.grounded()
                        && atWaypoint(b, tx, toFootY, tz));
            }
        }
        return plan;
    }

    private static float cost(MovementContext ctx, long d) {
        // Slow floor = multiplier on the walk time; damaging floor (magma — standable since s52b) = the
        // flat 1-HP contact charge in the one damage currency (0 for an immune bot). Both read the
        // destination-floor descriptor already in hand.
        return (ctx.isSlow(d) ? FLAT_COST * SLOW_COST_FACTOR : FLAT_COST) + ctx.floorHazardCost(d);
    }
}
