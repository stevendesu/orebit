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
 * <b>Landing kinds:</b> a {@link MovementContext#standable} floor (the classic landing, soft-landers —
 * slime, hay, honey, bed — scaling as above), or a <b>HANG</b> in a passable-climbable run (the vine
 * family): vanilla arrests a faller whose feet begin a tick inside a climbable cell (−0.15 clamp +
 * fallDistance reset), so the fall stops IN the column, damage-free, at the run's bottom cell — but
 * only within {@link #HANG_MAX_DROP} blocks of prior fall (deeper arrests are deliberately unsupported,
 * owner ruling) — see {@link #tryHang} and DESIGN-climb-vocabulary.md §3.1/§3.2. The other fall-distance-RESET media (water,
 * powder snow, sweet berry bush, cobweb, bubble columns) are classified {@code fallSoftness = 0.0} for
 * correctness but are NOT yet landing targets; the non-standable water landing + the Fall→swim mode
 * coupling stay deferred to v1.1.
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

    /**
     * Ticks to settle an arrested fall into a hang (one arrest tick + one stabilise tick at the −0.15
     * clamp) — the small fixed tail a hang landing pays instead of any fall-damage term (the arrest
     * resets fallDistance BEFORE impact; DESIGN-climb-vocabulary.md §3.1).
     */
    private static final float ARREST_SETTLE = 2f;

    /**
     * Max prior free-fall (blocks) for a hang landing — the guaranteed-arrest regime. Vanilla samples
     * feet once per tick, so a fall step of {@code dy} b/t can skip a 1-cell climbable once {@code dy}
     * exceeds 1.0 — which happens after ≈7.5 blocks of fall from rest (the exact recurrence
     * {@code v' = (v+0.08)×0.98}; {@code HangBoundTest} re-derives the crossing and asserts this floor
     * sits safely inside it). Deeper falls onto climbables are deliberately UNSUPPORTED (owner ruling
     * 2026-07-31): longer-run relaxations (a 2-run arrests to ≈40, ≥4-run from any height) would need
     * deep-column hangable sweeps whose measured per-node cost (TOWER +8-13%, FLOOD +14-18%) buys a
     * case too rare to matter — the column is refused instead (arrest-vs-tunnel past the bound is
     * nondeterministic anyway; DESIGN-climb-vocabulary.md §5).
     */
    static final int HANG_MAX_DROP = 7;

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

        // §3.2 (DESIGN-climb-vocabulary.md) — the in-column release-drop from a HANG node: when this
        // node's own feet cell is hangable (feet in a vine — one extra cache-hot read per standing node;
        // non-hang nodes, the overwhelming case, pay one read + one AND and skip) and the cell below the
        // node floor is passable NON-climbable (a climbable below is the contiguous column — Climb-down
        // owns it; a standable below is Climb's dismount), let go and drop straight down the column.
        // Landing rules identical to a cardinal fall (the same tryLanding/tryHang authorities, column top
        // = the node floor cell): the next hang under the tunneling bound, or a standable floor with fall
        // damage measured FROM the hang (fallDistance restarts at the arrest). No walk-off base
        // (base = 0) and no step-off body (flags = 0 → bodyTransitCost's zero-read prefilter) — the body
        // cells ARE the hang column, already priced as transit.
        int pSelf = ctx.packedAt(x, y + 1, z);
        if (pSelf != MovementContext.UNBUILT
                && ctx.hangable(ctx.descriptorOf(x, y + 1, z, pSelf))) {
            int pFloor = ctx.packedAt(x, y, z);
            if (pFloor != MovementContext.UNBUILT) {
                long dFloor = ctx.descriptorOf(x, y, z, pFloor);
                if (ctx.passable(dFloor) && !ctx.isClimbable(dFloor)) {
                    releaseDrop(ctx, out, x, y, z, safeFall, maxFall, hpCost);
                }
            }
        }

        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Step off the edge: the neighbour column at the bot's level must be open (2 cells) — the
            // WALK-level HEADROOM of the air cell at the bot's level. The bit's OOB bias is one-directional
            // (it can only over-claim clearance), so a sub-WALK reading is a trustworthy reject with no
            // reads; only a claims-clear reading near a section top needs the per-cell verify.
            int flags = ctx.flagsAt(nx, y, nz);
            if (MovementContext.headroom(flags) < MovementContext.HEADROOM_WALK) continue;
            if (!ctx.headroomProves(flags, nx, y, nz, MovementContext.HEADROOM_WALK)
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
                    // hard landing is priced exactly as before; a soft one gets its reduced cost (and its
                    // transit verify diverts to the hang when a vine hides in the nibble-invisible column).
                    tryLanding(ctx, out, nx, y, nz, y - 1 - fg, flags, safeFall, maxFall, hpCost, BASE_COST);
                    continue;              // highest landing decided — zero scan reads, no deeper phase
                }
                // Proven no landing in y-1..y-14: resume the legacy scan below the window. (The nibble is
                // standable-blind to vines, so a vine hiding in this window over a >14-deep column goes
                // unseen — deliberate: any hang here would need a prior drop ≤ HANG_MAX_DROP anyway, and
                // the per-cardinal window sweep that would catch the residue cost TOWER +8-13% / FLOOD
                // +14-18% in the A/B — the owner-ruled trade is to refuse deep-column arrests, not to
                // scan for them. A landing found below still walks the whole column in tryLanding, whose
                // verify diverts to the hang exactly when one is really there.)
                scanFrom = y - (TraversalGrid.DEPTH_SAT + 1);
            }

            // Phase 1 — the normal HARD-landing scan to maxFall (unchanged cost): the first (highest)
            // standable cell within the window is the landing. This is the common case; its cost is
            // byte-identical to the pre-softness scan (tryLanding prices an m = 1.0 landing exactly as before).
            int landingY = Integer.MIN_VALUE;
            boolean hitUnbuilt = false;
            boolean hungOut = false;
            int fy = scanFrom;
            for (; fy >= y - maxFall; fy--) {
                int packed = ctx.packedAt(nx, fy, nz);
                if (packed == MovementContext.UNBUILT) { hitUnbuilt = true; break; } // unknown below
                long sd = ctx.descriptorOf(nx, fy, nz, packed);
                if (ctx.standable(sd)) { landingY = fy; break; }
                // Arrest above any floor (§3.1): the highest hangable IS this cardinal's landing. One
                // extra AND on the already-loaded long for every non-standable scanned cell. (This scan
                // starts at y-2, so a vine at y/y-1 is only caught by the nibble paths' verify — the
                // UNKNOWN/edit-overlap fallback conservatively misses those two cells: fewer edges, never
                // wrong ones.)
                if (ctx.hangable(sd)) {
                    tryHang(ctx, out, nx, y, nz, fy, flags, BASE_COST);
                    hungOut = true;
                    break;
                }
            }
            if (hungOut) continue;
            if (landingY != Integer.MIN_VALUE) {
                tryLanding(ctx, out, nx, y, nz, landingY, flags, safeFall, maxFall, hpCost, BASE_COST);
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
                // No hangable check here: at the default caps this scan starts below maxFall (16) which
                // is past HANG_MAX_DROP (7), and deep-curtain arrests are deliberately unsupported
                // (owner ruling; see HANG_MAX_DROP). A custom maxFall < 7 forgoes the tiny residue.
                if (ctx.standable(ctx.descriptorOf(nx, fy, nz, packed))) {
                    tryLanding(ctx, out, nx, y, nz, fy, flags, safeFall, maxFall, hpCost, BASE_COST);
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
                                   int fy, int flags, int safeFall, int maxFall, float hpCost, float base) {
        int depth = y - fy;
        // Softness gate — consulted ONLY when the drop is beyond the free window (depth > safeFall), so a
        // short drop, an immune bot (safeFall == maxFall), and the whole common case read no extra descriptor
        // and behave byte-for-byte as before. m ∈ {1.0,0.5,0.2,0.0}: the excess-fall damage the landing block
        // actually deals is scaled by m, so a drop is survivable when that scaled damage stays within the same
        // HP budget the hard maxFall allows — (depth-safeFall)*m ≤ (maxFall-safeFall); m = 0 ⇒ uncapped.
        float m = 1.0f;
        if (depth > safeFall) {
            m = FALL_MULT[NavBlock.fallSoftness(ctx.descriptorAt(nx, fy, nz))];
            // Too deep for this landing's softness — reject with zero further reads, exactly as pre-arc.
            // (No hang-rescue sweep here: a vine above an UNSURVIVABLE landing sits > HANG_MAX_DROP in
            // every default-caps path that reaches this reject, and the sweep's cost on the flood-heavy
            // scenarios was the measured regression — owner ruling, see HANG_MAX_DROP.)
            if ((depth - safeFall) * m > (maxFall - safeFall)) return;
        }
        // Landing accepted: confirm the drop column (down to the new feet) is clear, pricing each
        // transited cell as it is read (read-once: the same descriptor answers passable AND the
        // pass-through hazard/through-slow surcharge — falling through fire / a web / a berry bush
        // is a per-cell cost, not a blocker; the loop spans the landing body too, so a hazardous
        // landing pocket is charged). The column cells fy+1..y sit BELOW the step-off body
        // (nx, y+1..y+2), which is priced separately off the flags already read — no double count.
        // The same pass records the TOPMOST climbable seen (one extra AND on the already-loaded long;
        // ascending loop ⇒ the last hit is the highest; a passable climbable here is hangable by
        // definition, and a solid one already returned via !passable) — when one exists the fall
        // physically arrests there and never reaches this landing, so the emission diverts to the hang
        // (§3.1). A clean column pays only the AND and emits bit-identically to the pre-arc code.
        float transit = 0f;
        int climbTop = Integer.MIN_VALUE;
        for (int k = fy + 1; k <= y; k++) {
            long cd = ctx.descriptorAt(nx, k, nz);
            if (!ctx.passable(cd)) return;
            if (ctx.isClimbable(cd)) climbTop = k;
            transit += ctx.cellTransitCost(cd);
        }
        if (climbTop != Integer.MIN_VALUE) {
            tryHang(ctx, out, nx, y, nz, climbTop, flags, base);
            return;
        }
        // Base walk-off + per-block fall time, plus a damage penalty for every block past the safe
        // window (depth > safeFall) SCALED by the landing softness m — the cost-not-blocker model —
        // plus the per-cell pass-through surcharges: the drop column (above) and the step-off body
        // cells (nx, y+1..y+2, the two cells the flags at (nx,y,nz) describe; zero-read when clear).
        float cost = base + depth * PER_BLOCK
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
     * §3.1 (DESIGN-climb-vocabulary.md) — price + emit a fall arrested in the passable-climbable
     * ({@link MovementContext#hangable}) run whose TOP cell is {@code climbTop}: walk the run to its
     * bottom, apply the flat tunneling bound ({@link #HANG_MAX_DROP} — feet are sampled once per tick,
     * so a too-fast entry can skip the cell), and emit the HANG node at the run's BOTTOM cell
     * (post-arrest the −0.15 clamp slides the bot down the run, so every catch point converges there —
     * a deterministic landing). NO fall-damage term ever: the arrest resets fallDistance before any
     * impact. The transit loop verifies AND prices the whole descended span {@code runBot..y} ascending
     * — the verify is load-bearing: the phase scans skip solid non-climbable cells (a fence post above
     * the vine) without checking them, exactly as they do before a standable landing, and rely on the
     * landing authority's column walk to reject (the {@code tryLanding} pattern). The rare diverted
     * branch pays the extra loop — clean columns never reach this method.
     */
    private static void tryHang(MovementContext ctx, CandidateSink out, int nx, int y, int nz,
                                int climbTop, int flags, float base) {
        // The vine here is a SAFE LANDING TARGET, not something to fall through (owner clarification,
        // 2026-08-02): landing on it makes an otherwise-lethal drop safe exactly as water or hay does, the
        // fall still crosses contiguous AIR to reach it, and HANG_MAX_DROP exists because past ~7 blocks the
        // bot moves >1 block/tick and can MISS the vine between feet samples. So this node is correct and
        // stays. What failed in the owner's 2026-08-02 run was the COMPLETION: on touching the vine the Fall
        // should have ended and the cursor moved to the next move, and instead the step never completed.
        int runBot = climbTop;
        while (true) {
            int packed = ctx.packedAt(nx, runBot - 1, nz);
            if (packed == MovementContext.UNBUILT) break;
            if (!ctx.hangable(ctx.descriptorOf(nx, runBot - 1, nz, packed))) break;
            runBot--;
        }
        int runLen = climbTop - runBot + 1;
        int priorDrop = y - climbTop; // free-fall blocks before the feet reach the run's entry plane
        if (priorDrop > HANG_MAX_DROP) return; // the flat guaranteed-arrest bound (§1; owner ruling)
        float transit = 0f;
        for (int k = runBot; k <= y; k++) {
            long cd = ctx.descriptorAt(nx, k, nz);
            if (!ctx.passable(cd)) return; // ballistic stretch blocked (fence/wall above the run)
            transit += ctx.cellTransitCost(cd);
        }
        float cost = base + priorDrop * PER_BLOCK
                + (runLen - 1) * Climb.CLIMB_DOWN_COST // the in-run slide down to the bottom cell
                + ARREST_SETTLE
                + transit + ctx.bodyTransitCost(flags, nx, y, nz);
        out.accept(nx, runBot - 1, nz, cost);
    }

    /**
     * §3.2 — the release-drop scan straight down from a hang node's floor cell: the first standable
     * floor (→ {@link #tryLanding}, fall damage measured FROM the hang — fallDistance restarted at the
     * arrest) or hangable run (→ {@link #tryHang}) wins; a solid cell, an unbuilt cell, or nothing
     * within {@link #SOFT_SCAN_LIMIT} ends the scan with no emit. Starts at y−1, so the 1-deep
     * standable duplicates Climb's dismount edge with the honest faster release cost (A* keeps the
     * cheaper edge). No nibble fast path — hang nodes are rare and the loop terminates at the first hit.
     */
    private static void releaseDrop(MovementContext ctx, CandidateSink out, int x, int y, int z,
                                    int safeFall, int maxFall, float hpCost) {
        for (int k = y - 1; k >= y - SOFT_SCAN_LIMIT; k--) {
            int packed = ctx.packedAt(x, k, z);
            if (packed == MovementContext.UNBUILT) return; // don't drop into unknown
            long d = ctx.descriptorOf(x, k, z, packed);
            if (ctx.standable(d)) {
                tryLanding(ctx, out, x, y, z, k, 0, safeFall, maxFall, hpCost, 0f);
                return;
            }
            if (ctx.hangable(d)) {
                tryHang(ctx, out, x, y, z, k, 0, 0f);
                return;
            }
            if (!ctx.passable(d)) return; // a solid (climbable or not) — no deterministic entry from above
        }
    }

    /**
     * Walk off the lip, then steer onto the landing column while airborne. On the ground this is the generic
     * line-tracking walk toward the landing; once airborne it ARRIVES on the landing column via the forward
     * input — braking with reverse thrust when its projected stopping point overshoots ({@link
     * SteerControl#arriveOnTarget}), since Minecraft's aerial control is weak enough that a correction begun
     * at the moment of overshoot is already too late. {@link #candidates} models a fall as a straight vertical
     * drop, and this keeps the real drop near that column.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        if (b.grounded()) {
            SteerControl.steerTowards(b, path);
        } else {
            SteerControl.arriveOnTarget(b, path);
        }
    }

    /**
     * The phase-model execution plan — the reactive counterpart of {@link #steer}, mapping its two branches
     * (grounded → {@code steerTowards}, airborne → {@code arriveOnTarget}) 1:1 onto phase order so the two
     * drive paths cannot drift apart. Fall is <b>WALKOFF &rarr; FALL</b>: stride off the
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
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        final int landFeetY = toFootY;            // feet BLOCK Y once standing on the landing floor (topY-aware)
        MovePlan plan = new MovePlan();
        // Balked walk-off: physically back on the start floor with no drop taken → re-attempt from WALKOFF.
        plan.resetWhen(b -> b.grounded()
                && atWaypoint(b, fx, fromFootY, fz));
        // WALKOFF: line-track the takeoff→landing segment and hold forward, striding off the lip (the legacy
        // grounded branch — steerTowards, not the medium-aware drive). Advance the moment the bot is airborne.
        plan.phase("walkoff")
                // Align before striding off the lip: a Fall entered with cross-axis carry leaves the edge
                // off-line and drops down the wrong column, where the airborne drop-control can no longer
                // recover it. The most consequential of the family — a mis-aimed step-off is irreversible.
                .arrestCarryFrom(fx, fz)
                .drive(SteerControl::steerTowards)
                .advanceWhen(b -> !b.grounded());
        // FALL: airborne drop-control — arriveOnTarget aims the bot's PROJECTED stopping point at the landing
        // column centre, braking with reverse input the moment that projection overshoots, so held step-off
        // momentum can't carry the bot off a 1-wide landing (or, as measured 2026-08-06, park it at the far
        // cell edge with no runup left for the step that follows).
        // Complete only once actually SETTLED on the landing cell: grounded (a standable floor) OR arrested
        // on a climbable (a HANG landing — feet in the vine cell, never grounded; the one predicate covers
        // both kinds, since a standing landing reads onClimbable false; DESIGN-climb-vocabulary.md §4).
        // A touchdown on a wrong cell simply never fires done — the follower's grounded-stall recovery
        // re-anchors and replans.
        plan.phase("fall")
                // Airborne drop-control: home onto the landing column (x/z) AND run the universal stance
                // servo (y). Not vine-specific machinery — it is the same per-tick rule every move runs
                // (owner, 2026-08-02): "if we're on a climbable and our Y is below the Y we want, hold jump;
                // if it's above, hold nothing; if it's AT the Y we want, hold sneak."
                //
                // That rule is what makes a hang landing work, and it CAUSES the arrest rather than trying to
                // detect it. Descending, the servo holds nothing and the drop runs. The tick the feet reach
                // the landing height it presses sneak; vanilla's isSuppressingSlidingDownLadder zeroes the
                // -0.15 climbable slide, the bot stops IN the vine, sneakHeld makes hangingOnClimbable true,
                // settled() goes true, and `done` below fires — ending the Fall and advancing the cursor,
                // which is exactly what failed to happen in the owner's 2026-08-02 run (the fall rode past
                // its landing to four blocks below, then STUCK). Without this the arrest had to be INFERRED
                // from velocity, and a clamped slide (-0.15) is indistinguishable from a fall that has just
                // begun (-0.0784) — a threshold that cannot be made both safe and sufficient.
                //
                // translating=false: a landing is not a lip crossing, so the hold is never relaxed away.
                .drive((b, v) -> {
                    SteerControl.arriveOnTarget(b, v);
                    SteerControl.holdClimbableStance(b, v, false);
                })
                // SETTLED, not the loose onClimbable(): being INSIDE a vine is true on every tick of a fall
                // THROUGH one, so the loose test called a still-falling bot landed. It also disagreed with
                // Movement.reached (which gates the cursor advance on settled()), and THAT disagreement is
                // what wedged the bot — the phase reported done at the landing cell while the cursor refused
                // to advance, so the fall just continued. One predicate now answers both questions.
                .done(b -> b.settled()
                        && atWaypoint(b, tx, landFeetY, tz));
        return plan;
    }
}
