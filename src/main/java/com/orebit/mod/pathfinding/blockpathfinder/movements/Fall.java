package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

/**
 * Drop more than one block off a cardinal edge to the first solid landing below (MOVEMENT-DESIGN.md §2,
 * Tier 1). A drop within {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#safeFallDistance} is free;
 * a deeper drop up to {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#maxFallDistance} is allowed but
 * charged a <b>damage penalty</b> of {@link com.orebit.mod.pathfinding.blockpathfinder.BotCaps#costPerHitpoint}
 * ticks per block past the safe window (vanilla fall damage ≈ 1 HP per excess block, priced in the planner's
 * ONE damage currency — the {@code pathing.costPerHitpoint} knob) — fall damage is a
 * cost, not a blocker, so the bot will take a hurtful drop when the alternative is a long detour but prefers a
 * gentle route when one is in reach. Beyond {@code maxFallDistance} the drop is rejected (unacceptable / lethal
 * damage) — <b>unless the landing block absorbs the impact</b> (below).
 *
 * <p><b>Soft-landing absorption ({@link com.orebit.mod.worldmodel.navblock.NavBlock#fallSoftness fallSoftness},
 * bits 48–49).</b> The landing block's fall-damage class scales BOTH the acceptance depth and the damage cost
 * by its multiplier {@code m ∈ {1.0, 0.5, 0.2, 0.0}} (full / bed / hay-honey / slime-and-the-reset-media):
 * <ul>
 *   <li><b>Cost</b> — the excess-fall penalty becomes {@code (depth − safeFall) × costPerHitpoint × m}
 *       (so {@code m = 0} adds nothing — a slime landing is free however deep);</li>
 *   <li><b>Acceptance</b> — a drop is survivable when the SCALED damage stays within the same HP budget the
 *       hard-landing {@code maxFallDistance} allows: {@code (depth − safeFall) × m ≤ (maxFall − safeFall)}.
 *       For {@code m = 0} that is always true (uncapped — a mortal bot may drop from world height onto slime);
 *       for {@code m = 1.0} it is exactly the old {@code depth ≤ maxFall} cap (ordinary falls are unchanged).</li>
 * </ul>
 * <b>V1 scope:</b> {@code Fall} still only lands on a {@link MovementContext#standable} cell, so only the
 * STANDABLE soft-landers — slime, hay, honey, bed — benefit today (a pure cost/height-math change, no landing
 * mechanism change). The fall-distance-RESET media (water, powder snow, sweet berry bush, cobweb, bubble
 * columns) are classified {@code fallSoftness = 0.0} for correctness but are NOT yet landing targets; the
 * non-standable landing predicate + the Fall→swim mode coupling are deferred to v1.1.
 *
 * <p><b>Behaviour change (damage-pricing unification):</b> the penalty was a hardcoded {@code
 * DAMAGE_PER_BLOCK = 10} ticks per excess block; it is now the caps value, default {@code 100}. A MORTAL
 * bot is therefore markedly more fall-averse at defaults (a 5-block drop past safe 3 costs 200 ticks ≈ 43
 * walk-blocks of detour, vs 20 ticks ≈ 4.3 before); an IMMUNE bot is unchanged (its fall window is
 * unlimited, the penalty zone empty). The ratified successor is a cumulative health-aware damage BUDGET
 * (per-path HP ledger vs remaining hearts) — not built yet; this per-block × ticks-per-HP term is the
 * unified interim model.
 *
 * <p>The landing must be {@link MovementContext#standable} (so it never "lands" in lava/cactus — those
 * aren't standable) and the whole drop column, plus the step-off transit, must be {@link
 * MovementContext#passable}. The highest reachable landing wins (shortest, safest drop). Fall folds no
 * edits (you can't usefully break/place mid-drop), so it never consults {@code RISKY_EDIT}. Every cell the
 * drop transits (the step-off body and the whole column down to the landing feet/head) is additionally
 * priced per cell via {@link MovementContext#cellTransitCost}/{@link MovementContext#bodyTransitCost} —
 * dropping through fire / a berry bush costs a mortal bot the damage surcharge, and a cobweb / powder-snow
 * column charges the through-slow term to every bot (both cost, never a blocker).
 */
public final class Fall implements Movement {

    /**
     * Step base cost, in <b>ticks</b> = one walk-off step ({@link Traverse#FLAT_COST}): the bot walks off
     * the edge (Baritone {@code WALK_OFF_BLOCK_COST ≈ WALK_ONE_BLOCK_COST}), then falls. Each block dropped
     * adds {@link #PER_BLOCK}.
     */
    public static final float BASE_COST = Traverse.FLAT_COST;
    /**
     * Ticks added per block of drop. Falling is fast — under vanilla gravity the first blocks take well
     * under a tick each, but the average rises with depth; Baritone's {@code FALL_N_BLOCKS_COST} table over
     * the small safe-fall window (≤ {@code safeFallDistance}) averages ≈ 2.5 ticks/block once the walk-off
     * is paid separately. Kept a flat per-block term (not the full physics table) because Tier 1 caps the
     * drop at the safe window, where the linear approximation is within a tick of the table. Source:
     * Baritone {@code ActionCosts.FALL_N_BLOCKS_COST}.
     */
    public static final float PER_BLOCK = 2.5f;

    // The fall-DAMAGE cost is caps.costPerHitpoint() ticks per block of drop beyond safeFallDistance
    // (each block past the safe window ≈ 1 vanilla HP — same formula as the old DAMAGE_PER_BLOCK = 10
    // constant, new unified currency). Damage is a COST, not a blocker: the bot takes a hurtful drop when
    // the only alternative is a long detour, but the penalty makes it prefer a damage-free route (e.g. the
    // 2-block-drop cave entrance over the 5-block-drop one) whenever one exists within reach.

    /**
     * The fall-damage multiplier per {@link NavBlock#fallSoftness} class (index = the 2-bit class): full /
     * half (bed) / fifth (hay, honey) / zero (slime + the fall-distance-reset media). Static, alloc-free —
     * one array index turns the descriptor's 2-bit class into the {@code m} the acceptance + cost math use.
     */
    private static final float[] FALL_MULT = {1.0f, 0.5f, 0.2f, 0.0f};

    /**
     * The world-height backstop (blocks) on the EXTENDED soft-landing scan below {@code maxFallDistance}
     * (phase 2). A {@code fallSoftness = 0.0} landing (slime / water) is acceptance-UNCAPPED, so the scan
     * that hunts for it needs a finite floor to terminate in a pathological FULLY-BUILT all-air column; it
     * is sized to the tallest supported world (−64…320 ⇒ 384) so "drop from world height onto slime" is
     * reachable. In real terrain / a void the phase-2 scan stops far sooner — at the first standable cell
     * (the landing the column ends in) or the first unbuilt cell — so this ceiling is almost never reached.
     */
    private static final int SOFT_SCAN_LIMIT = 384;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // walk off a ledge — only while upright
        // Scan to the bot's MAX fall (not just the safe one): drops past safeFall are allowed at a damage cost,
        // so a route that needs a hurtful drop isn't a dead end — it's just dearer than a gentle one. maxFall
        // is BOTH the phase-1 scan bound and the hard-landing HP budget the softness acceptance scales.
        final int safeFall = ctx.caps().safeFallDistance();
        final int maxFall = Math.max(ctx.caps().maxFallDistance(), safeFall);
        final float hpCost = ctx.caps().costPerHitpoint(); // ticks per HP — read once, a local in the loop
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Step off the edge: the neighbour column at the bot's level must be open (2 cells) — the
            // WALK-level HEADROOM of the air cell at the bot's level. The bit's OOB bias is one-directional
            // (it can only over-claim clearance), so a sub-WALK reading is a trustworthy reject with no
            // reads; only a claims-clear reading near a section top needs the per-cell verify.
            int flags = ctx.flagsAt(nx, y, nz);
            if (MovementContext.headroom(flags) < MovementContext.HEADROOM_WALK) continue;
            if (!ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK)
                    && (!ctx.passable(nx, y + 1, nz) || !ctx.passable(nx, y + 2, nz))) {
                continue;
            }

            // floorGap fast path (docs/Optimizations/09_depth_nibbles.md): the resident nibble answers "where
            // is the first standable cell below" in one read — trusted only when it is maintained (not
            // UNKNOWN — column-built grids always are; single-section test grids aren't) AND no path edit
            // can intersect the cells the scan would have read ((nx, y-maxFall..y-1, nz) — the verify loop
            // below cell y stays edit-aware either way). The gap is the memoized result of the EXACT scan
            // predicate (NavBlock.isStandable over the same resident navtypes), so each branch reproduces
            // the legacy scan's outcome byte-for-byte; UNKNOWN or overlapping edits fall through to the
            // legacy loop. Measured: FLOOD −5.1% / CLIFFS −4.3% / TOWER −3.4% (docs/Optimizations/09_depth_nibbles.md).
            int scanFrom = y - 2;
            int fg = ctx.floorGapAt(nx, y, nz);
            if (fg != TraversalGrid.DEPTH_UNKNOWN
                    && ctx.editsDisjointFromColumn(nx, y - maxFall, y - 1, nz)) {
                if (fg == 0) continue; // standable at y-1: the scan's landing always fails verify (§7.2)
                if (fg < TraversalGrid.DEPTH_SAT) {
                    // The exact first landing within the resident window (≤ 14 blocks down, always ≤ maxFall
                    // for the default caps). tryLanding is the sole acceptance authority — a within-window
                    // hard landing is priced exactly as before; a soft one gets its reduced cost.
                    tryLanding(ctx, out, nx, y, nz, y - 1 - fg, flags, safeFall, maxFall, hpCost);
                    continue;              // highest landing decided — zero scan reads, no deeper phase
                }
                // Proven no landing in y-1..y-14: resume the legacy scan below the window.
                scanFrom = y - (TraversalGrid.DEPTH_SAT + 1);
            }

            // Phase 1 — the normal HARD-landing scan to maxFall (unchanged cost): the first (highest)
            // standable cell within the window is the landing. This is the common case; its cost is
            // byte-identical to the pre-softness scan (tryLanding prices an m = 1.0 landing exactly as before).
            int landingY = Integer.MIN_VALUE;
            boolean hitUnbuilt = false;
            int fy = scanFrom;
            for (; fy >= y - maxFall; fy--) {
                int packed = ctx.packedAt(nx, fy, nz);
                if (packed == MovementContext.UNBUILT) { hitUnbuilt = true; break; } // unknown below
                if (ctx.standable(ctx.descriptorOf(nx, fy, nz, packed))) { landingY = fy; break; }
            }
            if (landingY != Integer.MIN_VALUE) {
                tryLanding(ctx, out, nx, y, nz, landingY, flags, safeFall, maxFall, hpCost);
                continue;
            }
            if (hitUnbuilt) continue; // unknown within the normal window — don't path into it (as before)

            // Phase 2 — the EXTENDED soft-landing scan. Reached ONLY at the lip of a drop deeper than maxFall
            // whose top maxFall cells are all built AIR (no hard landing, no unbuilt). Keep scanning for a
            // landing soft enough to survive the deeper fall; tryLanding rejects one that isn't (a hard/too-
            // deep cell emits nothing, exactly as the old maxFall cap did). Terminates at the first standable
            // cell (what the column ends in) or the first unbuilt cell; SOFT_SCAN_LIMIT is only the
            // fully-built-air-column backstop. This is the only path that costs more reads than before, and
            // only at genuine >maxFall ledges — flat ground and every landing within maxFall are unchanged.
            for (fy = y - maxFall - 1; fy >= y - SOFT_SCAN_LIMIT; fy--) {
                int packed = ctx.packedAt(nx, fy, nz);
                if (packed == MovementContext.UNBUILT) break;
                if (ctx.standable(ctx.descriptorOf(nx, fy, nz, packed))) {
                    tryLanding(ctx, out, nx, y, nz, fy, flags, safeFall, maxFall, hpCost);
                    break;
                }
            }
        }
    }

    /**
     * Verify and price a landing at {@code (nx,fy,nz)} for a step-off from level {@code y} — the scan-loop
     * body, split out so the floorGap exact-landing path and both scan phases share ONE copy (identical
     * reads, identical costs, identical emit). It is also the SOLE acceptance authority: a drop too deep for
     * the landing's {@link NavBlock#fallSoftness softness} is rejected here (emits nothing), so the callers
     * only have to find the highest landing — for an ordinary {@code m = 1.0} landing this reproduces the old
     * {@code depth ≤ maxFall} cap exactly.
     */
    private static void tryLanding(MovementContext ctx, CandidateSink out, int nx, int y, int nz,
                                   int fy, int flags, int safeFall, int maxFall, float hpCost) {
        int depth = y - fy;
        // Softness gate — consulted ONLY when the drop is beyond the free window (depth > safeFall), so a
        // short drop, an immune bot (safeFall == maxFall), and the whole common case read no extra descriptor
        // and behave byte-for-byte as before. m ∈ {1.0,0.5,0.2,0.0}: the excess-fall damage the landing block
        // actually deals is scaled by m, so a drop is survivable when that scaled damage stays within the same
        // HP budget the hard maxFall allows — (depth-safeFall)*m ≤ (maxFall-safeFall); m = 0 ⇒ uncapped.
        float m = 1.0f;
        if (depth > safeFall) {
            m = FALL_MULT[NavBlock.fallSoftness(ctx.descriptorAt(nx, fy, nz))];
            if ((depth - safeFall) * m > (maxFall - safeFall)) return; // too deep for this landing's softness
        }
        // Landing accepted: confirm the drop column (down to the new feet) is clear, pricing each
        // transited cell as it is read (read-once: the same descriptor answers passable AND the
        // pass-through hazard/through-slow surcharge — falling through fire / a web / a berry bush
        // is a per-cell cost, not a blocker; the loop spans the landing body too, so a hazardous
        // landing pocket is charged). The column cells fy+1..y sit BELOW the step-off body
        // (nx, y+1..y+2), which is priced separately off the flags already read — no double count.
        float transit = 0f;
        for (int k = fy + 1; k <= y; k++) {
            long cd = ctx.descriptorAt(nx, k, nz);
            if (!ctx.passable(cd)) return;
            transit += ctx.cellTransitCost(cd);
        }
        // Base walk-off + per-block fall time, plus a damage penalty for every block past the safe
        // window (depth > safeFall) SCALED by the landing softness m — the cost-not-blocker model —
        // plus the per-cell pass-through surcharges: the drop column (above) and the step-off body
        // cells (nx, y+1..y+2, the two cells the flags at (nx,y,nz) describe; zero-read when clear).
        float cost = BASE_COST + depth * PER_BLOCK
                + transit + ctx.bodyTransitCost(flags, nx, y, nz)
                // Landing-floor contact damage (magma — standable since s52b): coordinate form reads the
                // floor descriptor ONLY for a mortal bot; an immune bot pays zero reads here.
                + ctx.floorHazardCost(nx, fy, nz);
        if (depth > safeFall) {
            cost += (depth - safeFall) * hpCost * m; // ≈1 HP per excess block × ticks-per-HP × softness
        }
        out.accept(nx, fy, nz, cost);
    }

    /**
     * Walk off the lip, then steer onto the landing column while airborne. On the ground this is the generic
     * line-tracking walk toward the landing; once airborne it re-centres on the landing column via the
     * forward input (Minecraft's aerial control is weak, so this is a gentle correction, not a teleported
     * velocity — a player drop-controls off a ledge the same way). {@link #candidates} models a fall as a
     * straight vertical drop, and this keeps the real drop near that column.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        if (b.grounded()) {
            SteerControl.steerTowards(b, path);
        } else {
            SteerControl.recenterOnTarget(b, path);
        }
    }

    /**
     * The phase-model execution plan — the reactive counterpart of {@link #steer}, mapping its two branches
     * (grounded → {@code steerTowards}, airborne → {@code recenterOnTarget}) 1:1 onto phase order so the
     * driven behaviour is byte-for-byte the legacy drive. Fall is <b>WALKOFF &rarr; FALL</b>: stride off the
     * lip toward the landing column {@code (tx,tz)} (which {@link #candidates} makes identical to the step-off
     * neighbour column — the bot walks off into it and drops straight down), then, once airborne, home onto
     * that column while the drop runs, completing only when actually standing on the landing cell.
     *
     * <p><b>No needs — Fall folds ZERO edits.</b> Every {@link #candidates} emit is the 4-arg edit-free {@code
     * accept}; the whole drop column and the step-off body are proven passable-<i>intact</i> and priced with
     * intact-transit costs ({@link MovementContext#cellTransitCost}/{@link MovementContext#bodyTransitCost}),
     * never a break or place (the class Javadoc's "Fall folds no edits" rule). So neither phase carries a
     * {@link MovePlan.Need}: the plan's empty need set covers the move's empty edit set exactly. Declaring an
     * AIR need would be actively wrong — the runner would try to {@code mine} a mid-drop cell it can never
     * reach while airborne.
     *
     * <p>No {@code boolean[]} arm is needed for {@link #resetWhen} (unlike {@link Parkour}): the runner only
     * evaluates it once the cursor has advanced, and Fall reaches phase 1 ONLY via {@code advanceWhen(!grounded)},
     * so by the time the guard is live the bot has already gone airborne — the phase-0→1 transition IS the
     * "went airborne" event, with no takeoff-window aliasing to disarm around. The guard can then be true only
     * if the bot came back down onto the exact start cell, which is precisely the balked step-off to re-attempt.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final int landFeetY = ty + 1;             // feet BLOCK Y once standing on the landing floor
        MovePlan plan = new MovePlan();
        // Balked walk-off: physically back on the start floor with no drop taken → re-attempt from WALKOFF.
        plan.resetWhen(b -> b.grounded()
                && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
        // WALKOFF: line-track the takeoff→landing segment and hold forward, striding off the lip (the legacy
        // grounded branch — steerTowards, not the medium-aware drive). Advance the moment the bot is airborne.
        plan.phase("walkoff")
                .drive(SteerControl::steerTowards)
                .advanceWhen(b -> !b.grounded());
        // FALL: airborne drop-control — recenterOnTarget pulls toward the landing column centre, eases near
        // it and pushes BACK past it, so held step-off momentum can't carry the bot off a 1-wide landing.
        // Complete only once actually STANDING on the landing cell (a touchdown on a wrong cell simply never
        // fires done — the follower's grounded-stall recovery re-anchors and replans).
        plan.phase("fall")
                .drive(SteerControl::recenterOnTarget)
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == landFeetY && b.footZ() == tz);
        return plan;
    }
}
