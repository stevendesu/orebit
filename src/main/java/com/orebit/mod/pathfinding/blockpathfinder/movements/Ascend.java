package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Jump up one block onto a cardinal-adjacent floor cell that's a full step up (MOVEMENT-DESIGN.md §2,
 * Tier 1). Distinct from {@link Traverse}'s step-assist: this is a rise (start floor top → destination
 * floor top, in sixteenths — {@link MovementContext#rise}) <i>above</i>
 * {@link MovementContext#STEP_ASSIST_MAX_RISE}, so gaining it needs a real jump — and at most
 * {@link MovementContext#JUMP_RISE}, the most one jump gains. Both ends' partial heights count: from a
 * slab (start top 8) onto a full block one up the rise is {@code 16 + 16 − 8 = 24 > 20} — NOT jumpable
 * (you cannot ascend 1.5 blocks) — while slab → slab one up is {@code 16 + 8 − 8 = 16}, an ordinary jump.
 *
 * <p><b>The head-clearance fix.</b> A jump from the source column needs the cell <i>above the bot's own
 * head</i> (source {@code y+3}) clear, or the bot bonks the ceiling and never gains the block — the cell
 * the floor-centric grid can't represent (the "head-in-block" / "2-high dirt wall reads as a step" class
 * of bug, commit {@code 7beda91}). Both that and the landing body clearance are now read through the
 * resident HEADROOM bit: the source's own feet/head are already clear (the bot stands there), so its
 * HEADROOM is {@code JUMP} exactly when {@code y+3} is clear; the landing needs {@code WALK}. Cells the
 * bit can't prove (near a section face, or genuinely blocked) are read and — when the bot may break and
 * the edit isn't {@code RISKY_EDIT} — folded into a break-set.
 *
 * <p><b>Break / place modifiers (MOVEMENT-DESIGN §1, decision 1).</b> Two folds give the bot upward
 * mobility through this one kind: a blocked body/takeoff cell is <i>broken</i> (dig a staircase up into a
 * hillside), and a missing destination floor is <i>placed</i> — including, when the footing one-up-and-over
 * has no face of its own, a second <b>support</b> block beneath it placed against the floor the bot stands
 * on ({@link com.orebit.mod.pathfinding.blockpathfinder.EditScratch#requireFootingOn the two-block step}).
 * So repeated Ascend+place builds a diagonal staircase up through <i>open air</i> — off a ledge, out of a
 * cave, up to a hovering owner — not just up against existing terrain. It's two placements per step, so A*
 * picks it only when it's the cheapest way up; a straight vertical Pillar (cheaper per block, but a
 * one-way death-trap you can't descend) stays a separate kind the search will weigh against this once built.
 */
public final class Ascend implements Movement {

    /**
     * Base cost, in <b>ticks</b> = one walk step ({@link Traverse#FLAT_COST}). Ascending is "walk forward
     * while jumping": the jump impulse overlaps the forward motion, so gaining a horizontal AND a vertical
     * cell up an existing step takes about as long as a flat walk — climbing pre-existing terrain (stairs,
     * a hillside) is no dearer than a Traverse, matching Baritone, whose {@code MovementAscend} charges
     * ≈ {@code WALK_ONE_BLOCK_COST} for the traversal and adds the build cost only when it must place. A
     * folded placement (building a step in open air) adds its own real place ticks ({@link
     * MovementContext#placeCost}), so building up is naturally avoided unless it's the only way.
     */
    public static final float COST = Traverse.FLAT_COST;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // jump-up — only while upright
        if (ctx.caps().jumpHeight() < 1) return;
        int uy = y + 1;

        // Source facts are the same for all four directions — read once. The bot stands on (x,y,z) so its
        // feet/head are clear; HEADROOM == JUMP iff the takeoff head-clearance (y+3) is also clear.
        int srcFlags = ctx.flagsAt(x, y, z);
        boolean srcClear = ctx.headroomProves(srcFlags, y, MovementContext.HEADROOM_JUMP);
        boolean srcRisky = MovementContext.risksEdit(srcFlags);
        // The START surface height (sixteenths) — a partial start (slab, top 8) eats into the jump
        // budget: every rise below is measured from THIS surface (MovementContext.rise). floorSurface,
        // not raw topY: a surface-swim node's water "floor" reads as 16 (feet at the cell boundary), so
        // shore exits keep their historical geometry. Hoisted out of the direction loop (one read per
        // expansion; the per-search chunk cache makes it an array index).
        final int startTopY = ctx.floorSurface(x, y, z);
        // A PLACED step is a full cube, so its top is a fixed rise of 32 − startTopY sixteenths (one block
        // level up + a full 16 top). From a low partial start (startTopY < 12) that exceeds JUMP_RISE = 20,
        // so the build-a-step arm is dead for the whole expansion — decided once, not per direction.
        final boolean canGainPlacedStep =
                MovementContext.rise(1, 16, startTopY) <= MovementContext.JUMP_RISE;

        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // The destination floor (nx,uy,nz) is read three ways below (standable, topY, flags) — resolve
            // its grid slot ONCE and derive each from it.
            int dstPacked = ctx.packedAt(nx, uy, nz);
            if (dstPacked == MovementContext.UNBUILT) continue;
            long dstDesc = ctx.descriptorOf(nx, uy, nz, dstPacked);

            // Rise gate (start-top-aware): measure the real surface-to-surface gain one block level up.
            //  - rise ≤ STEP_ASSIST_MAX_RISE (9): Traverse's step-assist owns it (a no-jump auto-step) —
            //    same partition as before, now measured from the START surface too.
            //  - rise > JUMP_RISE (20): one jump can't gain it (slab → full one up = 24) — no candidate.
            // A missing floor is the build-a-step arm: the placed step is a full cube, gated once above.
            boolean dstStandable = ctx.standable(dstDesc);
            if (dstStandable) {
                int rise = MovementContext.rise(1, ctx.topYOf(dstDesc), startTopY);
                if (rise <= MovementContext.STEP_ASSIST_MAX_RISE) continue; // Traverse's step-assist
                if (rise > MovementContext.JUMP_RISE) continue;            // taller than one jump gains
            } else if (!canGainPlacedStep) {
                continue; // a placed full-cube step would sit 32 − startTopY > 20 above the start surface
            }

            int dstFlags = MovementContext.flagsOf(dstPacked);
            EditScratch e = ctx.edits().reset(!(srcRisky || MovementContext.risksEdit(dstFlags)));
            // Footing: stand on the block that's there, or BUILD A STEP UP. If the footing one-up-and-over
            // has no face of its own (open air / a ledge), a support block is placed beneath it against the
            // floor the bot stands on, then the footing on top — the two-block staircase step. requireFootingOn
            // folds 0, 1 or 2 places; invalid if the bot can't place or the spot is RISKY_EDIT.
            if (!dstStandable) e.requireFootingOn(nx, uy, nz, nx, y, nz);
            // The takeoff head-clearance (source y+3) and the landing body (feet+head) must be clear; cells
            // the HEADROOM bit can't prove are read and — when allowed — folded into a break-set (dig up).
            if (!srcClear) e.requireAir(x, y + 3, z);
            ctx.requireBodyClear(e, nx, uy, nz, dstFlags);
            if (e.valid()) {
                // Slow-FLOOR surcharge on the landing (soul sand / honey — same rule as Traverse/Diagonal;
                // a floor this move PLACES reads as the conjured full cube, never slow) plus the
                // pass-through hazard/through-slow surcharge for the landing body cells (zero-read when the
                // dest flag bits are clear; the edit-folding form breaks through a bush/web where that's
                // cheaper). The source y+3 takeoff cell is clearance-only — not a body cell the bot
                // lingers in — and is left unpriced.
                float cost = (ctx.isSlow(dstDesc) ? COST * Traverse.SLOW_COST_FACTOR : COST)
                        + ctx.floorHazardCost(dstDesc)
                        + ctx.bodyTransitCost(e, dstFlags, nx, uy, nz);
                out.accept(nx, uy, nz, cost + e.extraCost(), e);
            }
        }
    }

    /**
     * Walk toward the step while holding jump — an Ascend is "walk forward while jumping" onto a full block
     * one up (head-clearance already verified by {@link #candidates}). Holding the jump <i>input</i> (not a
     * one-shot ground impulse) means vanilla jumps onto the step when grounded and, when this Ascend is the
     * move that leaves a body of water, swims the bot up and out — the same input doing the right thing in
     * both media, so an underwater ledge needs no special case.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.steerTowards(b, path);
        b.setJumping(true);
    }

    /**
     * The phase-model execution plan (the reactive counterpart to {@link #candidates}'s edit-fold — the same
     * conversion {@link Pillar} and {@link Parkour} made from the {@code steer} + one-shot-edit path to a
     * live-geometry reconcile). An Ascend is <b>BUILD &rarr; CLIMB</b>: mine any solid takeoff/landing body
     * cells clear and (in open air) build the step up, then walk-and-jump onto it. The from-floor is
     * {@code (fx,fy,fz)} and the destination floor {@code (tx,ty,tz)} with {@code ty == fy + 1} (a cardinal
     * unit step one up — the geometry {@link #candidates} resolved), so all cells below are derived off those.
     *
     * <h2>Coverage — every {@link #candidates} fold reproduced reactively</h2>
     * The phase 0 {@code AIR} needs re-mine the three break folds (self-healing — one timed break/tick while
     * the cell reads solid, no-op once already air):
     * <ul>
     *   <li>{@code (fx,fy+3,fz)} — the takeoff head-clearance (candidates' {@code requireAir(x,y+3,z)});</li>
     *   <li>{@code (tx,ty+1,tz)} — the landing feet (candidates' {@code requireBodyClear} feet cell);</li>
     *   <li>{@code (tx,ty+2,tz)} — the landing head (candidates' {@code requireBodyClear} head cell).</li>
     * </ul>
     * The phase 0 DRIVE is the reactive mirror of {@code requireFootingOn(nx,uy,nz, nx,y,nz)}, made safe by
     * keying every place on {@code solidAt}: it places the footing {@code (tx,ty,tz)} only when that cell is
     * not already solid, and the support {@code (tx,ty-1,tz)} beneath it only when the footing cell AND the
     * cell below it are both air (the open-air staircase step). A natural floating ledge (footing solid, cell
     * beneath air) places nothing and never stalls — the exact reason the support is a {@code solidAt}-gated
     * drive body, NOT an unconditional {@code Need.FOOTING} (a declared footing need would place forever under
     * a walk-only bot ascending a solid ledge). The candidates' pass-through-hazard break-through ({@code BT})
     * is deliberately NOT reproduced — {@code Need.AIR} keys on {@code solidAt} and a berry-bush / cobweb is
     * passable, so the runner transits it intact (harmless, identical to the {@link Pillar}/{@link Parkour}
     * limitation). A footing with a natural SIDE face but air beneath places one extra unpriced support block
     * vs candidates' single place — harmless fill under the new floor.
     *
     * <h2>The launch-armed reset (the {@link Parkour} {@code airborneOnce} precedent)</h2>
     * {@code launched} is armed only once the climb drive sees the bot actually airborne, and cleared by the
     * build drive on every re-attempt. The {@code resetWhen} guard — "we launched the jump and fell back onto
     * the from-floor" — is checked by {@link PhaseRunner} only while in {@code "climb"} (cursor &gt; 0), so the
     * arm is essential: without it, the instant phase 0 advances (tick 1 for a natural all-clear step) the bot
     * is still on the from-cell and the guard would alias the start state and ping-pong (the aliasing Parkour
     * documents). A balked ascend snaps back to phase 0, which re-mines and rebuilds the step and re-jumps.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final int fromFootY = fy + 1;              // feet BLOCK Y standing on the from-floor
        final int landFootY = ty + 1;              // feet BLOCK Y standing on the destination floor (= fy+2)
        final boolean[] launched = new boolean[1]; // reset-guard arm (Parkour's airborneOnce precedent)
        MovePlan plan = new MovePlan();
        // Launched the jump then fell back onto the from-floor → a balked ascend; re-mine + rebuild + re-jump.
        // Only meaningful in "climb" (PhaseRunner checks resetWhen at cursor > 0), and only once truly airborne
        // (the launched arm), so it can't alias the still-on-from-cell state the instant phase 0 advances.
        plan.resetWhen(b -> launched[0]
                && b.grounded()
                && b.footX() == fx && b.footY() == fromFootY && b.footZ() == fz);
        // BUILD: mine the takeoff head + landing body clear (AIR needs, self-healing), then — once those hold —
        // build the step up in open air. The drive places nothing on a natural step (footing already solid);
        // digging into terrain places just the footing (support cell has a face); an open-air staircase places
        // support then footing (the two-block step, the only path that ever calls place). Hold on the column
        // (recenter) while building; advance the instant the footing is established (built or naturally present).
        plan.phase("build")
                .need(MovePlan.Need.AIR, fx, fy + 3, fz)
                .need(MovePlan.Need.AIR, tx, ty + 1, tz)
                .need(MovePlan.Need.AIR, tx, ty + 2, tz)
                .drive((b, v) -> {
                    launched[0] = false;                        // disarm the reset until the jump truly launches
                    if (!b.solidAt(tx, ty, tz)) {               // footing needs building (skip on a natural step)
                        if (!b.solidAt(tx, ty - 1, tz)) b.place(tx, ty - 1, tz); // open-air support first
                        b.place(tx, ty, tz);                    // then the footing on top
                    }
                    SteerControl.recenterOnTarget(b, v);        // hold on the step column while building
                })
                .advanceWhen(b -> b.solidAt(tx, ty, tz));
        // CLIMB: walk-forward-while-jumping onto the step (the legacy steer()). The held jump input also swims
        // the bot up-and-out when this Ascend leaves water. Arm the reset only once actually airborne. Complete
        // only when standing ON the destination floor (grounded at the landing feet cell).
        plan.phase("climb")
                .drive((b, v) -> {
                    if (!b.grounded()) launched[0] = true;      // arm the reset only once off the ground
                    SteerControl.steerTowards(b, v);
                    b.setJumping(true);
                })
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == landFootY && b.footZ() == tz);
        return plan;
    }
}
