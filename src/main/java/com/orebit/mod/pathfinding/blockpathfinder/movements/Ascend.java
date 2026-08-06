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
        if (ctx.reducesJump(x, y, z)) return; // honey-block floor: the jump apex clears nothing — can't take off
        if (ctx.noJumpFromBody(x, y, z)) return; // cobweb body cell: the stuck multiplier kills take-off velocity
        // R1 (DESIGN-climb-vocabulary.md §2: "no jump launches from climbable stances — solidFooting floors
        // only"). An Ascend is jump-based, and a jump is physically impossible with the feet INSIDE a
        // climbable: vanilla truncates a grounded jump whose feet start in one back to the 0.2 climb branch,
        // so the 0.42 launch never happens. solidFooting already encodes exactly that — its third conjunct
        // tests the FEET cell (x,y+1,z), not just the floor — and every other jump-takeoff move (Parkour /
        // DiagonalParkour / WalkOff / Climb's own jump-grab) already gates on it. Ascend was the sole
        // exemption, because Climb's class doc delegates the curtain TOP-OUT to it ("from the topmost climb
        // node, Ascend emits onto the adjacent floor one up (it never checks the source floor)").
        //
        // NO EXEMPTION (owner ruling 2026-08-01, from manual in-game proof). The delegation is wrong and the
        // capability does not need it: from a hang you climb to JUST above the curtain's top and step off
        // LATERALLY, so an up-and-over is Climb-UP then a lateral step — never a jump. Both edges already
        // exist (Climb's up and its lateral GRAB). The search only ever preferred Ascend here because it is
        // priced at Traverse.FLAT_COST and was wrongly legal; making it illegal leaves the physically correct
        // route as the cheapest one.
        //
        // Convicted 2026-08-01 on the flagship at (58,*,189) and (55,*,207): an Ascend emitted from a hang
        // burned ~12000 ticks in an eject/fall/re-grab limit cycle no envelope can see (the bot is never
        // settled). A first attempt to keep the edge alive when the source column continues overhead was ALSO
        // wrong — it exempted Ascend from the very rule above, and the staggered-curtain geometry the owner
        // demonstrated shows the transfer is a climb, not a launch. Reverted to the plain gate.
        if (!ctx.solidFooting(x, y, z)) return;
        int uy = y + 1;

        // Source facts are the same for all four directions — read once. The bot stands on (x,y,z) so its
        // feet/head are clear; HEADROOM == JUMP iff the takeoff head-clearance (y+3) is also clear.
        int srcFlags = ctx.flagsAt(x, y, z);
        boolean srcClear = ctx.headroomProves(srcFlags, x, y, z, MovementContext.HEADROOM_JUMP);
        boolean srcRisky = MovementContext.risksEdit(srcFlags);
        // The START surface height (sixteenths) — a partial start (slab, top 8) eats into the jump
        // budget: every rise below is measured from THIS surface (MovementContext.rise). Read the start
        // descriptor ONCE and derive both the scalar surface (== floorSurface: a non-standable float node's
        // water "floor" reads 16 so shore exits keep their geometry) and stair-ness. A stair takeoff's
        // surface is DIRECTIONAL, so it's resolved per neighbour inside the loop.
        final long startDesc = ctx.descriptorAt(x, y, z);
        final int startTopY = ctx.standable(startDesc) ? ctx.topYOf(startDesc) : 16;
        final boolean startStair = ctx.isStair(startDesc);

        for (int[] d : CARDINALS) {
            // §2b door EXIT (shared with Traverse / Descend): if the bot STANDS in an intact door whose panel
            // blocks THIS horizontal travel edge, an Ascend can't climb out that way either. EXIT_CLEAR off any
            // door (common case); EXIT_BLOCKED (iron / flag-off) skips the direction; EXIT_TOGGLE folds a door SET
            // (below) to free the exit. The crossing edge is the same feet cell a flat walk leaves through.
            int exitDoor = ctx.exitDoorDecision(x, y, z, d[0], d[1]);
            if (exitDoor == MovementContext.EXIT_BLOCKED) continue;
            boolean exitDoorToggle = exitDoor == MovementContext.EXIT_TOGGLE;
            int nx = x + d[0];
            int nz = z + d[1];
            // START surface toward this neighbour (directional on a stair). A PLACED step is a full cube, so
            // its top is a fixed rise of 32 − sTop (one level up + a full 16 top); from a low partial start
            // that exceeds JUMP_RISE, killing the build-a-step arm for this direction.
            int sTop = startStair ? ctx.directionalTopY(startDesc, d[0], d[1]) : startTopY;
            boolean canGainPlacedStep = MovementContext.rise(1, 16, sTop) <= MovementContext.JUMP_RISE;

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
                // Directional dest surface (the stair's edge facing back toward the start) — a stair step-up
                // reads as an 8/16 rise (Traverse's step-assist owns it) instead of a spurious 16/16 jump.
                int rise = MovementContext.rise(1, ctx.directionalTopY(dstDesc, -d[0], -d[1]), sTop);
                if (rise <= MovementContext.STEP_ASSIST_MAX_RISE) continue; // Traverse's step-assist
                if (rise > MovementContext.JUMP_RISE) continue;            // taller than one jump gains
            } else if (!canGainPlacedStep) {
                continue; // a placed full-cube step would sit 32 − startTopY > 20 above the start surface
            }

            int dstFlags = MovementContext.flagsOf(dstPacked);
            EditScratch e = ctx.edits().reset(!(srcRisky || MovementContext.risksEdit(dstFlags)));
            // §2b: fold the exit-door toggle onto this arm when leaving through a blocked (toggleable) feet door.
            if (exitDoorToggle) ctx.foldExitDoorToggle(e, x, y, z);
            // Footing: stand on the block that's there, or BUILD A STEP UP. If the footing one-up-and-over
            // has no face of its own (open air / a ledge), a support block is placed beneath it against the
            // floor the bot stands on, then the footing on top — the two-block staircase step. requireFootingOn
            // folds 0, 1 or 2 places; invalid if the bot can't place or the spot is RISKY_EDIT.
            if (!dstStandable) e.requireFootingOn(nx, uy, nz, nx, y, nz);
            // The takeoff head-clearance (source y+3) and the landing body (feet+head) must be clear; cells
            // the HEADROOM bit can't prove are read and — when allowed — folded into a break-set (dig up). The
            // landing body is cleared DOOR-AWARE: a RAISED doorway (a door standing on the step the bot climbs
            // onto) is walked past free / opened rather than mined, exactly as Traverse handles a flat one. The
            // entry edge is derived from the horizontal component (dx,dz), the SAME feet-door direction as a walk.
            if (!srcClear) e.requireAir(x, y + 3, z);
            ctx.requireBodyClearToward(e, nx, uy, nz, dstFlags, d[0], d[1]);
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
     * Grounded-gated exact match — the committed-move idiom ({@link Movement#reached}'s gate), adopted
     * because Ascend's steering is a HELD JUMP: the ungated default fires on the very tick the feet
     * block first matches, while that held input has just launched a fresh hop off the target cell — the
     * follower then abandons this plan mid-launch and builds the NEXT step's plan airborne (the
     * log-convicted vine-topout → gap-4 Parkour undershoot: "ABANDONED Ascend … (reached fired before
     * done)" then "PLAN Parkour … grounded=false"; DESIGN-async-step-safety.md §2). Completing a jump-up
     * is inherently a grounded event; the gate costs at most the ballistic ticks, and the transition then
     * fires on the first grounded steer tick BEFORE that tick's drive can re-press jump — an anchored
     * handoff. (Water top-outs stay covered: a bot that ends the step swimming never grounds, but the
     * reached-scan catching a LATER waypoint supersedes this step entirely, and the plan's own
     * fluid-extended done/failWhen governs the step itself.)
     */
    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        return b.grounded() && atWaypoint(b, wx, wy, wz);
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
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // Contract tripwire (PATHOLOGY P1B): an Ascend is BY CONTRACT a cardinal unit step one up (ty == fy+1,
        // the geometry candidates resolved — see the class doc). A caller handing us any other frame (the
        // historical +1 floor drift) would have us build a physically-impossible fiction (a 2-block jump) and
        // livelock; report it through the EXISTING validity-envelope FAILED path instead — detection, not
        // recovery: the follower drops the plan and replans from the bot's real floor. FLOOR-level contract
        // (the foot deltas below are topY-aware and may differ when either floor is a partial standable).
        if (ty != fy + 1) {
            MovePlan broken = new MovePlan();
            broken.failWhen(b -> true);
            return broken;
        }
        final int landFootY = toFootY;             // feet BLOCK Y standing on the destination floor (topY-aware)
        final boolean[] launched = new boolean[1]; // reset-guard arm (Parkour's airborneOnce precedent)
        MovePlan plan = new MovePlan();
        // Launched the jump then fell back onto the from-floor → a balked ascend; re-mine + rebuild + re-jump.
        // Only meaningful in "climb" (PhaseRunner checks resetWhen at cursor > 0), and only once truly airborne
        // (the launched arm), so it can't alias the still-on-from-cell state the instant phase 0 advances.
        plan.resetWhen(b -> launched[0]
                && b.grounded()
                && atWaypoint(b, fx, fromFootY, fz));
        // Validity envelope (PATHOLOGY P1 family — the Parkour failWhen precedent, extended to the FLUID
        // medium): a bot SETTLED — grounded, or bodily in fluid (a displaced ground-move executor that fell
        // into water is never "grounded"; the longrun-5 under-wall pocket latch) — at a foot cell outside
        // the plan's own two columns is off-plan: the plan is fiction there, resetWhen/done can never fire,
        // and re-attempting in place latches forever. Allowed set (cells the plan already carries — no world
        // reads, no timers): the FROM column's stand-to-landing feet band (a water-leaving climb legitimately
        // swims that band; the from stand itself stays resetWhen's, which the runner checks first) and the
        // TARGET column's feet band [landFootY-1 .. landFootY] — landFootY is the landing stand, and
        // landFootY-1 is the FACE-PRESS transit: approaching the step, the bot's centre crosses into the
        // target column at the pre-jump height while its box is still grounded on the from-block (the
        // longrun-9 second-hold false positive — the exact mirror of Descend's lip transit). Deeper in the
        // target column (fell into a hole or fluid pocket) is off-plan. Airborne dry-climb ticks are
        // neither grounded nor in fluid — exempt.
        plan.failWhen(b -> (b.grounded() || b.inWater() || b.inLava())
                && !(b.footX() == fx && b.footZ() == fz
                        && b.footY() >= fromFootY && b.footY() <= landFootY)
                && !(b.footX() == tx && b.footZ() == tz
                        && b.footY() >= landFootY - 1 && b.footY() <= landFootY));
        // BUILD: mine the takeoff head + landing body clear (AIR needs, self-healing), then — once those hold —
        // build the step up in open air. The drive places nothing on a natural step (footing already solid);
        // digging into terrain places just the footing (support cell has a face); an open-air staircase places
        // support then footing (the two-block step, the only path that ever calls place). Hold on the column
        // (recenter) while building; advance the instant the footing is established (built or naturally present).
        plan.phase("build")
                .need(MovePlan.Need.AIR, fx, fromFootY + 2, fz)   // takeoff head-clearance (2 above the feet)
                .need(MovePlan.Need.AIR, tx, landFootY, tz)        // landing feet
                .need(MovePlan.Need.AIR, tx, landFootY + 1, tz)    // landing head
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
        //
        // CLIMBABLE-TRANSIT DISCRIMINATOR (the 2026-07-31 vine-elevator, log-convicted by the ascvine.face
        // course card): when the bot's FEET cell is a climbable, vanilla reinterprets the held jump as its
        // +0.2/t climb — with a vine in the landing stance the bot climbs THROUGH its own target, and at the
        // curtain top a held jump re-launches it off every falling re-entry (a 4-tick hover limit cycle, feet
        // one block above the stance, never grounded — so done/resetWhen/failWhen, all settled-gated, can
        // never fire). So the jump is held only while the climb still NEEDS height: at/above the landing feet
        // in a climbable, release it and let the climbable's -0.15/t descent clamp settle the bot onto the
        // floor, where done fires. Below the target the held jump is right in BOTH media (ballistic launch on
        // land, +0.2/t ratchet up a curtain toward the step); water is untouched (a water cell is never a
        // climbable cell, so onClimbable() and the swim-out behaviour can't overlap).
        plan.phase("climb")
                // Align before jumping (MovePlan.Phase.arrestCarryFrom): an Ascend entered carrying momentum
                // perpendicular to its own step — the 2026-07-31 post-replan specimen, where an abandoned
                // sprinting Parkour runup had parked the centre ~0.15 from the −z lip and the adopted Ascend
                // drove −x — launches off-lane and grounds off both of the envelope's admitted columns, a
                // permanent fail→HOLD. A straight approach carries no cross velocity and is unaffected.
                .arrestCarryFrom(fx, fz)
                .drive((b, v) -> {
                    if (!b.grounded()) launched[0] = true;      // arm the reset only once off the ground
                    SteerControl.steerTowards(b, v);
                    // CLIMB FIRST, TRANSLATE AFTER — the vanilla ladder top-out this phase's Javadoc already
                    // claims to reproduce. On a climbable, horizontal input does not carry the bot toward the
                    // ledge: MovementContext.solidFooting's derivation states it outright — "the jump key only
                    // climbs UP faster and horizontal input merely EJECTS the bot from the climbable". Driving
                    // full-forward through the hang therefore ejects the bot off the curtain BEFORE it has the
                    // height to clear the landing, and it free-falls back down the column.
                    //
                    // Convicted 2026-08-01 on the flagship at (55,*,207): a Climb GRAB left the bot hanging on
                    // a node whose floor is provably non-standable (Climb refuses the grab unless the floor is
                    // NOT standable), Ascend was emitted for the top-out (its documented job — it never checks
                    // source-floor standability), and its horizontal drive ejected the bot at botY 139.162 →
                    // climbable=false at 138.933 → free fall to 133.5 → re-grab → climb → repeat. A limit cycle
                    // that burned ~12000 ticks and is invisible to every envelope, because the bot is never
                    // grounded and the failWhen medium-set excludes onClimbable.
                    //
                    // So while the hang still NEEDS height, keep the FACING (for the exit) but withhold the
                    // horizontal drive and let the held jump's +0.2/t ratchet do the climbing. At/above the
                    // landing feet the jump releases (below) and the ordinary drive carries the bot across.
                    // Not a vine special case: it keys on onClimbable, the same predicate the whole climb
                    // vocabulary uses, and on the plan's own landFootY.
                    if (b.onClimbable() && b.footY() < landFootY) {
                        b.setForward(0.0f);
                    }
                    // Hold the jump only while the climb still NEEDS height. The height test alone is the
                    // whole rule; the earlier form additionally required onClimbable, which silently made it
                    // a vine-only release and left a bot that had climbed OUT of the curtain still jumping.
                    // Log-convicted 2026-08-01 (headless flagship, (47,*,220) vine → Ascend to (47,146,219)):
                    // the bot rode the vine to foot 147 == landFootY, left the climbable (climbable=false),
                    // grounded in the FROM column, and the still-held jump launched it from one block too
                    // high — apex foot 148, outside the target column's admitted [landFootY-1, landFootY]
                    // band, a permanent fail->HOLD. At or above the landing feet there is no height left to
                    // gain in EITHER medium: on a curtain the -0.15/t clamp settles the bot onto the floor,
                    // on solid ground it simply walks across at the right level, and both reach the landing
                    // stand where done fires. This also stops the mirror case — re-jumping off the landing
                    // itself on the arrival tick, which is the same predicate (footY == landFootY).
                    b.setJumping(b.footY() < landFootY);
                })
                .done(b -> b.grounded()
                        && atWaypoint(b, tx, landFootY, tz));
        return plan;
    }
}
