package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * WalkOff — a no-jump crossing (MOVEMENT-DESIGN Tier 1, {@code DESIGN walkoff}): from a standing floor cell
 * advance <b>two</b> cells in one cardinal direction over a 1-wide air gap and descend <b>one</b> block,
 * landing on existing geometry. No block-breaking, no jump. It is how a human clears a "gap-1 / descend-1"
 * by holding forward, and — critically — the ONLY way to cross a <b>honey</b> obstacle now that the
 * slow-flyover capability is removed: you cannot jump <i>off</i> honey ({@link MovementContext#reducesJump})
 * and a Parkour scan no longer flies <i>over</i> a slow block. The bot Traverses ONTO the honey (a slow but
 * standable full block), then WalkOffs from it across the void to the landing one below.
 *
 * <h2>Why its OWN move, not a Fall special-case (design §1)</h2>
 * {@link Fall} steps off to the neighbour column {@code x±1} and scans <i>that same column</i> straight
 * down (its landing is always in the step-off column, and it explicitly excludes the depth-1 case —
 * "Descend's job"). WalkOff's landing is in the <b>far</b> column {@code x±2}, with the step-off column
 * {@code x±1} a pass-through gap that must NOT contain a landing. That is a horizontal-plus-vertical arc,
 * not Fall's straight drop. And the executors are OPPOSITE: Fall's airborne drive
 * ({@link SteerControl#recenterOnTarget}) KILLS horizontal momentum to drop straight down a 1-wide column;
 * WalkOff must PRESERVE momentum (§5) to carry the hitbox across two cells while dropping one, and it must
 * be at sprint to do so at all. So WalkOff is edit-free like Fall but reuses Parkour's predictive airborne
 * servo ({@link SteerControl#parkourAirborne}) instead.
 *
 * <h2>Geometry + predicates (design §2)</h2>
 * Cardinal {@code d=(dx,dz)} from start floor {@code (x,y,z)}: gap column {@code (gx,gz)=(x+dx,z+dz)},
 * landing floor {@code (lx,lz)=(x+2dx,z+2dz)} one block lower.
 * <ol>
 *   <li>{@code MODE_STANDING} — a ground move, upright only (mirrors {@link Descend}/{@link Fall}).</li>
 *   <li><b>No jump-takeoff refusal</b> — WalkOff works FROM honey / cobweb-body, precisely where every jump
 *       move refuses (it does NOT copy Parkour's {@code reducesJump}/{@code noJumpFromBody} gates — those are
 *       the defining trait). It DOES still require {@link MovementContext#solidFooting} at the takeoff (see
 *       the eligibility gate §7): a sprint walk-off needs real ground to accelerate off, so a vine / ladder
 *       climbing state is refused exactly as a jump is — and honey / cobweb-body are both solid footing, so
 *       this doesn't narrow them.)</li>
 *   <li>{@code floorGapAt(gx,y,gz) != 0} — the cheap resident-nibble reject: {@code fg==0} means
 *       {@code (gx,y-1)} is standable, i.e. this is a {@link Descend}, not a gap (reuses the exact nibble
 *       {@link Fall} reads; UNKNOWN falls through to the explicit reads below, which are the authority).</li>
 *   <li>{@code (gx,y)} and {@code (gx,y-1)} both {@code passable} and NOT {@code standable} — the air gap
 *       the feet transit / descend through (a floor at either is a {@link Descend}/flat {@link Traverse}).</li>
 *   <li>{@code (gx,y+1)},{@code (gx,y+2)} clear ({@link MovementContext#HEADROOM_WALK}, with the
 *       {@code headroomProves}/per-cell fallback) — the feet+head sweep the gap column stepping off.</li>
 *   <li>{@code (lx,y-1)} {@code standable} (the landing, one below — {@code standable} already excludes
 *       lava/cactus; magma is standable and priced by {@link MovementContext#floorHazardCost}).</li>
 *   <li>{@code (lx,y)},{@code (lx,y+1)} {@code passable} — the new feet + head cells at the landing.</li>
 * </ol>
 * WalkOff folds ZERO edits (like {@link Fall}) — every emit is the edit-free {@code accept}.
 *
 * <h2>Composition with Parkour — the jump-refused emission gate (design §7, open-Q3 resolved)</h2>
 * A Parkour falling gap-1 jump can reach the SAME {@code (lx,y-1,lz)} destination via a jump. WalkOff's
 * physically-honest cost ({@code 2·FLAT_COST ≈ 9.27}) is CHEAPER than that jump ({@code ≈17.6}), so
 * unconditional emission would let WalkOff <b>hijack</b> a destination a legal jump should own — e.g. jump
 * FROM soul sand (soul is slow but NOT {@code reducesJump}), which the owner requires stay a jump. The
 * design's open question §8.3 ("raise the cost, or adopt the §7 jump-refused gate") is resolved in favour
 * of the <b>gate</b>: raising {@code COST} above a jump is physically backwards (a walk-off is genuinely
 * cheaper and safer than a jump) and would lose to short walk-around detours. So WalkOff emits only when a
 * jump is refused at the start cell — {@code reducesJump} (honey), {@code noJumpFromBody} (cobweb body), or
 * a sub-JUMP ceiling — which is exactly "WalkOff is the fallback crosser where you cannot jump." Honey →
 * WalkOff (sole crosser); soul sand → Parkour jump-from-soul (WalkOff silent). The gate is loop-invariant
 * (depends only on the start cell) and evaluated LAZILY — only once a direction's rare gap-1/descend-1
 * geometry has already matched — so flat ground pays zero gate reads.
 *
 * <h2>Executor — the critical correctness point (design §5)</h2>
 * The honey wall-slide ({@code HoneyBlock.doSlideMovement}) steals horizontal momentum only while the
 * hitbox is horizontally colliding with honey AND descending with {@code vy < −0.13}. At SPRINT (~0.25 b/t)
 * the hitbox clears the honey column's face within ~1 tick — before free-fall {@code vy} crosses −0.13 at
 * ~tick 2 — so the slide never engages; a slow walk stays beside the honey at tick 2 and gets clamped; a
 * jump is worst (rises then descends straight back down beside the honey for many ticks). Sprint is ALSO
 * mandatory for the reach: feet fall 1 block in ~5 ticks, in which the center must cross ~1.0 block; sprint
 * airborne (~0.24–0.26 b/t) just makes it, a walk (~0.19 b/t) falls short. So the honey constraint and the
 * reach constraint share the SAME solution: sprint + full forward, never jump, preserve momentum. The
 * airborne phase reuses {@link SteerControl#parkourAirborne} verbatim (input-only, holds sprint,
 * accelerates while predicted short — guaranteeing the gap is cleared — and reverse-brakes an overshoot only
 * when a full-reverse touchdown still lands at/beyond the near edge, so it never brakes short into the gap).
 * {@code parkourAirborne} is pure kinematics from the bot's current state; it does not assume a jump
 * happened, so it drops straight into WalkOff.
 *
 * <h2>Scope</h2>
 * v1 is advance-2 / descend-1 ONLY (gap width 1, drop exactly 1). Wider gaps are unreachable by a no-jump
 * walk-off ({@link Parkour}'s job); advance-2/descend-N is a clean future extension (scan the FAR column
 * down for the first landing + add Fall's damage term). Diagonal WalkOff is deferred.
 */
public final class WalkOff implements Movement {

    /**
     * Base cost, in <b>ticks</b> = two walk steps ({@code 2 · }{@link Traverse#FLAT_COST} ≈ 9.27): WalkOff
     * covers two horizontal cells of ground-equivalent distance while the 1-block drop overlaps that travel
     * (gravity is "free" time the bot spends moving forward anyway — the same argument {@link Descend}
     * ({@code COST == FLAT_COST}) and {@link Fall} use to make the vertical free). Seed value; tunable. Kept
     * physically honest and BELOW a jump deliberately — the jump-refused emission gate (class Javadoc §7),
     * not an inflated cost, is what keeps Parkour the owner of a legal-jump destination.
     */
    public static final float COST = 2f * Traverse.FLAT_COST;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a ground crossing — only while upright

        // The start-cell eligibility gate (class Javadoc §7) is loop-invariant but its reads are wasted on
        // the common flat node, so it is evaluated LAZILY: -1 unknown / 0 not eligible (WalkOff yields the
        // whole node) / 1 eligible (WalkOff may emit). The geometry rejects below fire first on flat ground,
        // so the gate is reached only on genuine gap-1/descend-1 geometry (rare).
        int eligible = -1;

        for (int[] d : CARDINALS) {
            int dx = d[0], dz = d[1];
            int gx = x + dx, gz = z + dz;      // gap column (1 away)
            int lx = x + 2 * dx, lz = z + 2 * dz; // landing column (2 away)

            // (#2) cheap resident-nibble reject: fg==0 ⇒ (gx,y-1) standable ⇒ a Descend, not a gap. Safe as a
            // standalone reject — the nibble reads 0 ONLY when a standable-at-y-1 was actually computed;
            // UNKNOWN reads 15 (never 0), so this never wrongly skips a real gap, and the explicit (gx,y-1)
            // read below is the authority either way. One resident nibble in the common (flat/step-down) case.
            if (ctx.floorGapAt(gx, y, gz) == 0) continue;

            // (#6) landing floor standable, one below — read its slot once. Cheapest strong discriminator:
            // most gaps have no floor exactly 2-out/1-down, so this rejects the bulk after one resolve.
            int lp = ctx.packedAt(lx, y - 1, lz);
            if (lp == MovementContext.UNBUILT) continue;
            long landDesc = ctx.descriptorOf(lx, y - 1, lz, lp);
            if (!ctx.standable(landDesc)) continue;

            // (#4) gap at foot level: passable AND NOT standable (a floor here is a flat Traverse) — read-once.
            int gp = ctx.packedAt(gx, y, gz);
            if (gp == MovementContext.UNBUILT) continue;
            long gapDesc = ctx.descriptorOf(gx, y, gz, gp);
            if (!ctx.passable(gapDesc) || ctx.standable(gapDesc)) continue;

            // (#5) gap one below: passable AND NOT standable (a floor here is Descend's landing) — read-once.
            int gp1 = ctx.packedAt(gx, y - 1, gz);
            if (gp1 == MovementContext.UNBUILT) continue;
            long gapBelow = ctx.descriptorOf(gx, y - 1, gz, gp1);
            if (!ctx.passable(gapBelow) || ctx.standable(gapBelow)) continue;

            // (#3 headroom) the gap column's two body cells (gx,y+1),(gx,y+2) — feet+head sweep it stepping
            // off. Fall's exact pattern: the resident HEADROOM bit proves it in one read (its OOB bias is
            // one-directional, so a sub-WALK reading is a trustworthy reject); only a claims-clear reading
            // near a section top needs the per-cell verify.
            int gapFlags = MovementContext.flagsOf(gp);
            if (MovementContext.headroom(gapFlags) < MovementContext.HEADROOM_WALK) continue;
            if (!ctx.headroomProves(gapFlags, y, MovementContext.HEADROOM_WALK)
                    && (!ctx.passable(gx, y + 1, gz) || !ctx.passable(gx, y + 2, gz))) {
                continue;
            }

            // (#7) landing body: new feet (lx,y) + new head (lx,y+1) must be clear.
            if (!ctx.passable(lx, y, lz) || !ctx.passable(lx, y + 1, lz)) continue;

            // Geometry matched — now the (rare) direction-independent eligibility gate. When the start cell
            // isn't eligible (a legal jump exists, OR the takeoff isn't solid footing) NO direction may
            // emit, so return outright.
            if (eligible < 0) eligible = eligibleTakeoff(ctx, x, y, z) ? 1 : 0;
            if (eligible == 0) return;

            // Cost (design §4): 2·FLAT_COST + per-cell transit over the transited body cells + landing
            // hazard. All flag-gated / read-once — zero extra reads in the all-clear common case (the void
            // honey/soul repro). WalkOff takes no damage from the honey itself (honey is not damaging); its
            // only effect, the wall-slide, is handled in the executor, not the cost.
            int landFlags = MovementContext.flagsOf(lp);
            float cost = COST
                    + ctx.cellTransitCost(gapDesc) + ctx.cellTransitCost(gapBelow) // gap feet cells
                    + ctx.bodyTransitCost(gapFlags, gx, y, gz)                      // gap head sweep (gx,y+1..y+2)
                    + ctx.bodyTransitCost(landFlags, lx, y - 1, lz)                 // landing body (lx,y..y+1)
                    + ctx.floorHazardCost(landDesc);                               // magma landing (mortal bots)
            out.accept(lx, y - 1, lz, cost);
        }
    }

    /**
     * The start-cell eligibility gate (class Javadoc §7) — two loop-invariant conditions, called at most
     * once per node.
     *
     * <p><b>1. Solid footing.</b> WalkOff sprints off the lip to win the honey race and the 2-cell reach, so
     * the takeoff floor must be genuine ground the bot can accelerate off ({@link
     * MovementContext#solidFooting} = {@code standable && !climbable}). A vine / ladder / scaffolding puts the
     * bot in a CLIMBING state with no horizontal launch — a walk-off is as physically impossible there as a
     * jump (the same gate {@link Parkour} uses), so it is refused. Honey and cobweb-body — the cases WalkOff
     * exists for — are BOTH solid footing (honey is a standable full block; a cobweb body sits over a solid
     * floor), so this does not narrow them; it only excludes the non-solid climbable takeoff.
     *
     * <p><b>2. Jump refused.</b> WalkOff emits only when a running jump is NOT an option from the start —
     * honey floor ({@link MovementContext#reducesJump}), cobweb body ({@link MovementContext#noJumpFromBody}),
     * or a ceiling below {@link MovementContext#HEADROOM_JUMP} — so Parkour owns every legal-jump destination
     * and WalkOff is only the crosser where a jump can't be made (the anti-hijack guard; {@code reducesJump}
     * is the cheapest read and the honey/soul discriminator, so it is tested first).
     */
    private static boolean eligibleTakeoff(MovementContext ctx, int x, int y, int z) {
        if (!ctx.solidFooting(x, y, z)) return false;
        return ctx.reducesJump(x, y, z)
                || ctx.noJumpFromBody(x, y, z)
                || MovementContext.headroom(ctx.flagsAt(x, y, z)) < MovementContext.HEADROOM_JUMP;
    }

    /**
     * The phase-model execution plan (design §5) — WALKOFF &rarr; CROSS, mirroring {@link Fall}'s
     * WALKOFF&rarr;FALL shape but with the OPPOSITE airborne drive: {@link SteerControl#parkourAirborne}
     * (preserve + servo the momentum onto the far landing), never {@link SteerControl#recenterOnTarget}
     * (which kills it). NEVER jumps.
     *
     * <p><b>WALKOFF</b>: stride off the lip at SPRINT with full forward — sprint is what both wins the honey
     * {@code vy<−0.13}-vs-collision race and supplies the 2-cell reach (class Javadoc §5). {@code setJumping(false)}
     * throughout (a no-launch step-off). Advance the moment the bot is airborne.
     *
     * <p><b>CROSS</b>: the predictive reach+overshoot servo homes the far landing {@code (tx,ty,tz)} while
     * the 1-block drop runs, holding sprint, accelerating while predicted short (guarantees the gap is
     * cleared) and reverse-braking an overshoot only when a full-reverse touchdown still lands at/beyond the
     * near edge — so it never brakes the bot short into the gap. Completes only once actually STANDING on the
     * landing cell.
     *
     * <p><b>No needs — WalkOff folds ZERO edits</b> (the {@link Fall} precedent): every {@code candidates}
     * emit is the edit-free {@code accept}, so neither phase carries a {@link MovePlan.Need}. No
     * {@code boolean[]} reset arm is needed either (again like Fall): CROSS is reached ONLY via
     * {@code advanceWhen(!grounded)}, so by the time {@code resetWhen} is live the bot has already gone
     * airborne — the phase-0&rarr;1 transition IS the "went airborne" event, and the guard can then be true
     * only on a genuine grounded return to the start cell (a balked step-off to re-attempt).
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final int ddx = tx - fx, ddz = tz - fz;
        // Unit travel axis (cardinal today ⇒ degenerates to the ±1 signum axis). ONE sqrt at plan BUILD time
        // (cold — one MovePlan per waypoint step, the Pillar/Parkour precedent); per-tick predicates stay
        // multiply-adds. dist is never 0 (WalkOff always spans 2 cells).
        final double dist = Math.sqrt((double) (ddx * ddx + ddz * ddz));
        final double ux = ddx / dist, uz = ddz / dist;
        final int landFeetY = ty + 1; // feet BLOCK Y once standing on the landing floor

        MovePlan plan = new MovePlan();
        // Balked step-off: physically back on the start floor with no drop taken → re-attempt from WALKOFF.
        plan.resetWhen(b -> b.grounded()
                && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
        // WALKOFF: sprint off the lip toward the landing, NO jump. steerTowards (not the medium-aware drive) —
        // the honey/reach race needs full sprint at the lip.
        plan.phase("walkoff")
                .drive((b, v) -> {
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(true);
                    b.setJumping(false);
                })
                .advanceWhen(b -> !b.grounded());
        // CROSS: preserve momentum via the predictive airborne servo (holds sprint, accelerate-while-short,
        // never-brake-into-gap). NEVER jump.
        plan.phase("cross")
                .drive((b, v) -> {
                    SteerControl.parkourAirborne(b, v, ux, uz, tx, ty, tz, /*sprint=*/true);
                    b.setJumping(false);
                })
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == landFeetY && b.footZ() == tz);
        return plan;
    }

    /** A walk-off is an irreversible airborne crossing — once the bot strides off the lip it cannot stop
     *  mid-gap, so a landing cell that is itself the goal (a close-goal crossing) must not preempt the move.
     *  See {@link Movement#commitsAcrossArrival()}. */
    @Override
    public boolean commitsAcrossArrival() {
        return true;
    }
}
