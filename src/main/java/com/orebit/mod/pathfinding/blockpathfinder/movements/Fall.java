package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.ClutchModel;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
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
 * owner ruling) — see {@link #tryHang} and NOTES-movement-physics.md §4.
 *
 * <p><b>Water cushion (NOTES-movement-physics.md §7).</b> Water standing in a landing's FEET cell makes that
 * landing damage-free from ANY height — the "one block of water on the ground" rule. This is a distinct
 * mechanism from the vine arrest above and carries no tunneling bound, because vanilla resets fallDistance
 * from the bot's FINAL post-move position rather than by sampling cells along the way. The remaining
 * fall-distance-RESET media (powder snow, sweet berry bush, cobweb) stay classified {@code fallSoftness = 0.0}
 * for descriptor correctness but are deliberately NOT cushions: cobweb's reset provably runs a tick-phase too
 * late to save a same-tick landing, while powder snow / sweet berry are merely UNVERIFIED (powder snow in
 * particular has a genuine same-tick {@code fallOn} override — see the notes beside {@link #WATER_PENETRATION}).
 *
 * <p><b>Wet endpoints — a fall may END in the water rather than under it</b> (owner ruling 2026-08-07). Water
 * is not standable, so it is never a landing TARGET the scans hunt for; but when the scan's standable seabed
 * lies deeper than the bot's entry momentum can carry it, the fall does not reach that floor and the candidate
 * used to be refused outright. That refusal is what made a deep pool unpathable — a 16-deep column under a
 * 60-block drop was skipped entirely and the bot pillared down a staircase beside it. Such a fall now ENDS at
 * the cell where the bot actually comes to rest, floating ({@link #waterStop}). The emitted node is an ordinary
 * {@code (x,y,z,mode)} row whose key cell happens to be water — the exact shape {@link Swim}'s own SINK rung
 * already emits — so the search continues from it with the swim vocabulary at {@link Swim}'s honestly-derived
 * rates (sink to the seabed, rise, or paddle out sideways), and the whole notion of an unreachable seabed
 * disappears: the bot was always able to press sink. {@code Fall} remains the movement ON the waypoint, so the
 * follower runs this class's walk-off → air state machine for the entire edge; only its endpoint is wet.
 *
 * <p><b>Behaviour change (damage-pricing unification):</b> the penalty was a hardcoded {@code
 * DAMAGE_PER_BLOCK = 10} ticks per excess block; it is now the caps value, default {@code 100}. A MORTAL
 * bot is therefore markedly more fall-averse at defaults (a 5-block drop past safe 3 costs 200 ticks ≈ 43
 * walk-blocks of detour, vs 20 ticks ≈ 4.3 before); an IMMUNE bot is unchanged (its fall window is
 * unlimited, the penalty zone empty). The ratified successor is a cumulative health-aware damage BUDGET
 * (per-path HP ledger vs remaining hearts) — not built yet; this per-block × ticks-per-HP term is the
 * unified interim model.
 *
 * <p><b>Clutch — the carried soft landing ({@link ClutchModel}).</b> Everything above prices the block that
 * is ALREADY on the ground. A bot carrying water, powder snow, slime, hay or a bed can supply that block
 * itself, dropping it into the landing's FEET cell on the way down; the residual damage is then the same
 * {@code (depth − safeFall) × m} arithmetic with {@code m} coming from the carried kind instead of from the
 * terrain (the bed excepted — it scales the DISTANCE, so {@link ClutchModel#damageBlocks} owns the formula).
 * The rules are all deliberate:
 * <ul>
 *   <li><b>Feasibility only, never pricing.</b> The clutch is consulted ONLY on a landing the softness gate
 *       has just REFUSED. A drop the old model accepted keeps its old cost to the last float even for a bot
 *       with a full clutch bag — repricing an already-survivable landing against a cheaper carried absorber
 *       (a 10-block drop onto stone leaves 7 HP; hay would leave 1.4) is a real improvement, and it is
 *       deliberately left OUT of this pass rather than guessed at.</li>
 *   <li><b>One field load on the reject path.</b> The probe is {@code ctx.clutchMask()} and a zero test, taken
 *       only after the gate has already decided to refuse — so a bot carrying nothing, and every headless /
 *       benchmark / caps-only search (whose mask is {@code 0} by construction, see {@link
 *       MovementContext#clutchMask()}), reaches the same {@code return} as before having read nothing extra.
 *       Same discipline as the inlined water probe below: the common case pays a load and a branch, never a
 *       call.</li>
 *   <li><b>The survivability budget is unchanged.</b> {@link ClutchModel#best} returns a kind only when its
 *       residual fits {@code maxFall − safeFall} — the very HP budget a hard landing at {@code
 *       maxFallDistance} already spends — so a clutch never widens what the bot will survive; it only lets a
 *       deeper drop reuse the budget the caps already sanction.</li>
 *   <li><b>Cost</b> = {@link ClutchModel#damageBlocks} × {@code costPerHitpoint} (replacing the
 *       resident-softness term, which is zeroed) plus the ordinary {@link MovementContext#placeCost} of the
 *       folded place. A clutch spends a SPECIFIC carried item, so that flat place price understates it; a
 *       per-kind price (a bucket is dearer than a hay bale) is deliberately DEFERRED rather than invented.</li>
 *   <li><b>The place is FOLDED into the step edits</b> at the landing feet cell — see {@link #tryLanding} for
 *       the two independent reasons it has to be.</li>
 * </ul>
 *
 * <p>The landing must be {@link MovementContext#standable} (so it never "lands" in lava/cactus — those
 * aren't standable) and the whole drop column, plus the step-off transit, must be {@link
 * MovementContext#passable}. The highest reachable landing wins (shortest, safest drop). Fall folds no edits
 * on any landing the TERRAIN alone makes survivable (you can't usefully break/place mid-drop), so it never
 * consults {@code RISKY_EDIT}; the single clutch place above is the one exception. Every cell the
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

    // ---- Water cushion (NOTES-movement-physics.md §7) -------------------------------------------------
    //
    // A standable landing whose FEET cell holds water takes NO fall damage, from ANY height. Verified in
    // 1.21.11 bytecode: LivingEntity.checkFallDamage calls updateInWaterStateAndDoWaterCurrentPushing()
    // FIRST (which calls resetFallDistance() when the post-move AABB overlaps water) and only then calls
    // Entity.checkFallDamage, which is where Block.fallOn applies the damage. The reset therefore lands
    // strictly before the impact, off the FINAL position — so unlike a vine arrest it is SPEED-INDEPENDENT
    // and needs no tunneling bound: the solid under the water stops the bot IN the water cell, and that is
    // the whole mechanism ("you can always land in one block of water if the water is on the ground").
    //
    // COBWEB deliberately does NOT qualify, and the reason is specific to it: WebBlock has no fallOn
    // override, so its only protection is Entity.makeStuckInBlock (from entityInside ← applyEffectsFromBlocks),
    // which LivingEntity.aiStep invokes at offset 639 — AFTER travel()→move()→checkFallDamage at offset 618.
    // That reset lands one tick-phase too late to save a same-tick landing, so a web only protects a bot that
    // was already inside it on a previous tick: the same speed-dependent class HANG_MAX_DROP refuses for vines.
    //
    // POWDER SNOW is excluded for a DIFFERENT and weaker reason — do not merge the two. It carries BOTH
    // makeStuckInBlock AND a PowderSnowBlock.fallOn override that plays a sound and never calls
    // causeFallDamage, and that override is same-tick. Its protection is therefore real; what is unverified
    // is the 1-deep case (empty collision means the bot sinks THROUGH, so whether checkFallDamage sees powder
    // snow or the solid beneath it as getOnPosLegacy() on the landing tick has not been established). It is
    // excluded pending that verification, not because the mechanism is late. Sweet berry likewise unverified.
    // Excluding costs only a refused-but-survivable drop; including a wrong one would kill the bot.

    /** Vanilla in-water vertical drag (LivingEntity.travelInWater — the Y factor is hardcoded 0.8). */
    private static final double WATER_DRAG = 0.8;
    /** In-fluid gravity per tick while falling (Attributes.GRAVITY 0.08 / 16 — getFluidFallingAdjustedMovement). */
    private static final double WATER_GRAVITY = 0.005;
    /** Terminal sink speed with no input: {@code 0.005 / (1 − 0.8)} = 0.025 b/t — the ~40 tick/block crawl. */
    private static final double WATER_TERMINAL = WATER_GRAVITY / (1 - WATER_DRAG);
    /** Terminal free-fall speed in air: {@code 0.08 × 0.98 / (1 − 0.98)} = 3.92 b/t. */
    private static final double AIR_TERMINAL = 0.08 * 0.98 / (1 - 0.98);

    /**
     * Momentum-carried penetration into water, in whole blocks, indexed by the free-fall distance (blocks)
     * ABOVE the water surface. Derived at class-load from the two vanilla recurrences, not tabulated by hand:
     * air free fall is {@code v' = (v + 0.08) × 0.98} (the same recurrence {@link #HANG_MAX_DROP} comes from),
     * and in-water descent with no input is {@code v' = 0.8×v + 0.005}. The excess of entry speed over the
     * terminal crawl decays geometrically, so the distance momentum contributes is
     * {@code Σ (vₜ − v∞) = (v_entry − v∞) / (1 − 0.8) = 5 × (v_entry − v∞)} — saturating at
     * {@code 5 × (3.92 − 0.025) ≈ 19.5} blocks once the fall reaches air terminal velocity.
     *
     * <p>This is where a wet fall STOPS, not a feasibility gate (it was one until 2026-08-07, and refusing
     * everything past it is what made deep water unpathable). The value is a genuine resting depth rather than
     * an arbitrary cutoff: the excess over the terminal crawl decays at {@code 0.8/t}, so the bot is within
     * 0.1 block of it about 21 ticks after entry and merely creeps at 0.025 b/t thereafter.
     *
     * <p><b>Terminal velocity is approached far more slowly than the saturation figure suggests</b> — the air
     * recurrence converges at ratio 0.98, so a 60-block drop enters at 2.34 b/t (penetration <b>11</b>), not
     * 3.92. Crossing 16 blocks of water would take a 176-block free fall, and even a 399-block drop only
     * reaches 18. Do not read the ~19 ceiling as a practical allowance; it is asymptotic and never attained.
     */
    private static final int[] WATER_PENETRATION = new int[256];

    /**
     * The deepest water ANY entry momentum could cross — {@code 5 × (3.92 − 0.025) ≈ 19} blocks, the value at
     * air terminal velocity. Taken from the closed form rather than from {@code max(WATER_PENETRATION)}
     * because the table is indexed by whole blocks of free fall and its last entry is a ~255-block drop,
     * which has not QUITE reached terminal velocity.
     *
     * <p>Documentation and a test anchor only — <b>no longer a scan cap</b>. It bounded the column walk while
     * that walk answered a yes/no gate; the walk now has to find the water SURFACE in order to place a
     * floating landing, so truncating it would misplace the node. And as {@link #WATER_PENETRATION} notes,
     * this ceiling is asymptotic: no reachable drop attains it.
     */
    static final int MAX_WATER_PENETRATION = (int) Math.floor(5.0 * (AIR_TERMINAL - WATER_TERMINAL));

    static {
        double v = 0, dist = 0;
        int d = 0;
        for (int t = 0; t < 20_000 && d < WATER_PENETRATION.length; t++) {
            // Fill every whole-block depth already reached, with the speed the bot carries there.
            while (d < WATER_PENETRATION.length && dist >= d) {
                WATER_PENETRATION[d++] = (int) Math.floor(5.0 * Math.max(0.0, v - WATER_TERMINAL));
            }
            v = (v + 0.08) * 0.98; // gravity then drag — LivingEntity.travelInAir
            dist += v;
        }
        // Saturate the tail past air terminal velocity (the loop exits once the table is full anyway).
        while (d < WATER_PENETRATION.length) {
            WATER_PENETRATION[d] = WATER_PENETRATION[d - 1];
            d++;
        }
    }

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
     * resets fallDistance BEFORE impact; NOTES-movement-physics.md §3).
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
     * nondeterministic anyway; NOTES-movement-physics.md §6).
     */
    static final int HANG_MAX_DROP = 7;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        // Walk off a ledge — only while upright, and only if this bot is allowed to fall at all. The caps gate
        // rides the SAME early return as the mode gate (one extra already-loaded record field test on the
        // standing path, none at all off it), mirroring how Pillar/MineDown self-gate on the place/break caps.
        // caps.mayFall() false ⇒ this movement contributes NO candidates, so no returned path contains a
        // walk-off drop of any depth (/bot roam — BotRoamer). Deliberately NOT expressed as a squeezed
        // safeFall/maxFall window: that window prices a drop, it cannot forbid one (the soft-landing/clutch and
        // immune branches read through it), and shrinking it would also move Parkour's falling-landing tier.
        if (ctx.mode() != MovementContext.MODE_STANDING || !ctx.caps().mayFall()) return;
        // Scan to the bot's MAX fall (not just the safe one): drops past safeFall are allowed at a damage cost,
        // so a route that needs a hurtful drop isn't a dead end — it's just dearer than a gentle one. maxFall
        // is BOTH the phase-1 scan bound and the hard-landing HP budget the softness acceptance scales.
        final int safeFall = ctx.caps().safeFallDistance();
        final int maxFall = Math.max(ctx.caps().maxFallDistance(), safeFall);
        final float hpCost = ctx.caps().costPerHitpoint(); // ticks per HP — read once, a local in the loop

        // §4 (NOTES-movement-physics.md) — the in-column release-drop from a HANG node: when this
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
     *
     * <p>Being the sole authority is also why the <b>clutch</b> branch (class doc §Clutch) lives here and
     * NOWHERE else: all four entry points — the floorGap exact-landing fast path, the phase-1 hard scan, the
     * phase-2 extended soft scan and {@link #releaseDrop} — route their acceptance through this one method, so
     * one branch covers every landing the movement can offer and there is no second copy to drift.
     */
    private static void tryLanding(MovementContext ctx, CandidateSink out, int nx, int y, int nz,
                                   int fy, int flags, int safeFall, int maxFall, float hpCost, float base) {
        int landY = fy;          // the cell the emitted node keys on — the seabed, unless the fall ends wet
        int depth = y - landY;
        // Softness gate — consulted ONLY when the drop is beyond the free window (depth > safeFall), so a
        // short drop, an immune bot (safeFall == maxFall), and the whole common case read no extra descriptor
        // and behave byte-for-byte as before. m ∈ {1.0,0.5,0.2,0.0}: the excess-fall damage the landing block
        // actually deals is scaled by m, so a drop is survivable when that scaled damage stays within the same
        // HP budget the hard maxFall allows — (depth-safeFall)*m ≤ (maxFall-safeFall); m = 0 ⇒ uncapped.
        float m = 1.0f;
        int clutch = ClutchModel.NONE;   // the carried kind that rescues this landing, if the gate needs one
        if (depth > safeFall) {
            // Water standing on the landing governs BOTH the impact and WHERE the fall ends: a cushion zeroes
            // the damage however deep the drop, and a column deeper than entry momentum can cross ends the
            // fall floating part-way down instead of on the seabed ({@link #waterStop}).
            //
            // The feet-cell probe is INLINED here rather than folded into waterStop, and the split is
            // deliberate: a dry landing — the overwhelming case — must cost one read and one branch with no
            // call, leaving the column walk in a cold callee that only genuine water ever enters. Measured:
            // routing every landing through the helper cost TOWER ~+2% (pinned fresh-JVM A/B).
            if (ctx.water(nx, fy + 1, nz)) {
                landY = waterStop(ctx, nx, fy, nz, depth);
                if (landY == NO_WATER_LANDING) return;
                depth = y - landY;  // a floating stop is a SHORTER fall than the one to the seabed would be
                m = 0f;
            } else {
                m = FALL_MULT[NavBlock.fallSoftness(ctx.descriptorAt(nx, fy, nz))];
            }
            // Too deep for this landing's softness — reject with zero further reads, exactly as pre-arc,
            // UNLESS the bot carries a clutch it can drop into the landing cell on the way down (below).
            // (No hang-rescue sweep here: a vine above an UNSURVIVABLE landing sits > HANG_MAX_DROP in
            // every default-caps path that reaches this reject, and the sweep's cost on the flood-heavy
            // scenarios was the measured regression — owner ruling, see HANG_MAX_DROP.)
            if ((depth - safeFall) * m > (maxFall - safeFall)) {
                // ---- CLUTCH (class doc §Clutch; ClutchModel) -------------------------------------------
                // Reached ONLY on a landing the resident-softness gate has already refused, so this branch
                // can never change the cost of a drop the old model accepted — with or without a mask, an
                // accepted landing's floats are untouched. The probe order is load-bearing for the hot path:
                // ONE int field load + a zero test comes before anything else, so a bot with no clutch, and
                // every headless / benchmark / caps-only search (mask 0 by construction), falls out of this
                // reject having read exactly what it read before this arc existed. Same reason the water
                // probe above is inlined rather than helper-called (measured TOWER ~+2% when it wasn't).
                int mask = ctx.clutchMask();
                if (mask == 0 || !ctx.caps().canPlace()) return;
                // best() is pure integer/float math over a 5-entry preference table (HAY, BED, SLIME,
                // POWDER_SNOW, WATER — most expendable item first) and applies the SAME maxFall−safeFall HP
                // budget this gate just applied, so it can only ever return a kind that makes the landing
                // acceptable. NONE ⇒ nothing carried fits the budget ⇒ the drop stays refused.
                clutch = ClutchModel.best(mask, depth, safeFall, maxFall);
                if (clutch == ClutchModel.NONE) return;
                // A footprint-2 kind (the BED, a head+foot multiblock) additionally needs a second cell
                // beside the landing feet — a 1-wide shaft cannot take one. Checked ONLY when the chosen
                // kind actually needs it, so the four-cardinal probe never runs for the single-cell kinds.
                // Refusing the CANDIDATE (rather than re-running best() with the bed masked out and taking
                // the next kind) is the conservative option: it emits fewer edges, never a wrong one, and a
                // second best() pass is a behaviour widening the owner has not ratified — deliberately
                // deferred, not overlooked.
                if (ClutchModel.footprint(clutch) > 1 && !clutchFootprintFits(ctx, nx, fy + 1, nz)) return;
                // ---- GEOMETRY SPLIT ({@link ClutchModel#landsOnTop}) --------------------------------------
                // The block always goes in the SAME cell — the landing's feet cell (nx, fy+1, nz) — but where
                // the bot ends up differs by kind, and getting this wrong is what made the first cut of this
                // branch emit a self-blocking dead end:
                //
                //   LANDS-ON-TOP (slime, hay) — the carried block BECOMES the floor. The bot stands ON it, so
                //   the node is one cell higher and the drop is one block SHORTER. Folding a place at that
                //   cell is then correct: it is the node's FLOOR, which is exactly what requireFloor models.
                //
                //   SINK-THROUGH (water, powder snow) — the bot comes to rest on the PRE-EXISTING floor with
                //   the clutch in its feet cell, so the node and the depth are unchanged. Powder snow belongs
                //   here despite being solid mid-fall: its 0.9-tall box exists only while fallDistance > 2.5,
                //   and the landing is what resets fallDistance, so the shape empties and the bot sinks in.
                //   These fold NO geometry — a place at the feet cell reads as solid cobblestone
                //   (PathEdits.PLACED) to every later expansion, and the node would refuse its own body cell.
                //
                // best() was asked about the UNADJUSTED depth, so a lands-on-top kind is judged against a drop
                // one block longer than it will actually take. That is the conservative direction and is left
                // deliberately un-tightened: re-running best() on the shorter depth could promote a cheaper
                // kind, which is a behaviour widening the owner has not ratified.
                if (ClutchModel.landsOnTop(clutch)) {
                    landY = fy + 1;
                    depth = y - landY;
                }
                // The clutch SUPERSEDES the resident block's softness: the bot lands on what it brought, so
                // the m-term below must contribute nothing and ClutchModel.damageBlocks owns the residual
                // (which is a different curve entirely for the distance-scaled bed).
                m = 0f;
            }
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
        for (int k = landY + 1; k <= y; k++) {
            long cd = ctx.descriptorAt(nx, k, nz);
            // A fall column may contain WATER. {@code passable} is the WALK-clearance predicate and
            // deliberately excludes fluids, but a falling bot enters water freely — and rejecting it here is
            // precisely what made every water landing unreachable before the cushion arc. Lava and bubble
            // columns remain blockers (neither is {@code passable} nor {@code water}). {@link #tryHang}'s own
            // span walk is deliberately NOT relaxed: a climbable arrest INSIDE water is unverified physics
            // (buoyancy vs the −0.15 clamp), so such a column emits nothing rather than a guessed candidate.
            // Reachable via WATERLOGGED ladders/scaffolding only — both implement SimpleWaterloggedBlock and
            // have empty collision, so they read as climbable AND swimmable. Vines cannot (VineBlock is not
            // waterloggable), so the common vine-curtain column is unaffected by that restriction.
            if (!ctx.passable(cd) && !ctx.water(cd)) return;
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
                // floor descriptor ONLY for a mortal bot; an immune bot pays zero reads here. A floating
                // (water) landY is not a damaging floor, so this reads zero on that branch.
                + ctx.floorHazardCost(nx, landY, nz);
        if (depth > safeFall) {
            cost += (depth - safeFall) * hpCost * m; // ≈1 HP per excess block × ticks-per-HP × softness
        }
        if (clutch == ClutchModel.NONE) {
            out.accept(nx, landY, nz, cost); // the edit-free emit — byte-identical to the pre-clutch code
            return;
        }
        // ---- Clutch emit: record the cell, then price it ----------------------------------------------
        // The clutch block goes in (nx, fy+1, nz) for BOTH families — the landing's original feet cell. What
        // differs is what the SEARCH must believe about that cell afterwards (see the geometry split above).
        //
        // RECORDING THE CELL IS REQUIRED whichever family it is, because PathPlan.prescribesEdit is
        // coordinate-only: one recorded entry forgives BOTH announcements the clutch makes — the place on the
        // way down and the reclaim after settling — so neither reads as an unplanned world edit that
        // invalidates the plan out from under an airborne bot. For the sink-through kinds there is no folded
        // place to carry that, which is exactly why prescribesEdit also consults the clutch cell.
        //
        // setClutch is additionally the ONLY channel the chosen KIND has to the executor: it rides the step's
        // StepEdits, BotNavigator injects it into the MovePlan (the requireDoor pattern), and Fall's phases
        // below read it back. Without it the follower would place the conjured cobblestone, not water.
        EditScratch edits = ctx.edits().reset();
        if (ClutchModel.landsOnTop(clutch)) {
            // The placed block is the node's FLOOR, so the search must see it as one — otherwise every
            // expansion out of this node finds no footing and the landing is a dead end. requireFloor is the
            // right primitive rather than merely the available one: it proves the cell is placeable (refusing
            // the candidate when it is not, which is correct — a clutch the executor could not place must not
            // be planned) and charges exactly ctx.placeCost via extraCost() below, so the placement price is
            // the ordinary one, reused rather than invented.
            //
            // This arm is also the ONLY proof that (nx, fy+1, nz) is free: landY moved up before the transit
            // loop ran, so that loop started at fy+2 and never inspected this cell. requireFloor's own
            // placeable gate covers it.
            edits.requireFloor(nx, fy + 1, nz);
            if (!edits.valid()) return;
        } else {
            // Sink-through: the bot stands on the pre-existing floor, so NOTHING about the world's geometry
            // changes as far as the search is concerned — the clutch is placed and reclaimed inside this one
            // move. The transit loop has already proven this cell passable (landY stayed at fy, so the loop
            // began at fy+1), which is exactly the replaceable-or-empty condition the placement needs.
            // The place is still real work, so it is priced with the same ordinary placeCost requireFloor
            // would have charged — added directly, since there is no fold to carry it.
            cost += ctx.placeCost(nx, fy + 1, nz);
        }
        edits.setClutch(clutch, nx, fy + 1, nz);
        cost += ClutchModel.damageBlocks(clutch, depth, safeFall) * hpCost // residual damage, HP-blocks × ticks/HP
                + edits.extraCost();                                      // the folded place, when there is one
        out.accept(nx, landY, nz, cost, edits);
    }

    /**
     * Whether a footprint-2 clutch (the {@link ClutchModel#BED}, a head+foot multiblock) has room for its
     * second half beside the landing feet cell {@code (feetX, feetY, feetZ)}: true when any of the four
     * cardinal neighbours at that level can take a placed block.
     *
     * <p>The predicate is {@link MovementContext#placeable}, not a bare emptiness test, and that is the
     * conservative choice on purpose: it additionally requires the cell to have a face to place against
     * (normally the landing floor beneath it), so it refuses some beds vanilla would allow rather than
     * planning one the executor cannot place. Whether {@code BedBlock} truly needs support under BOTH halves
     * is not established here and is deliberately not guessed at — over-refusing costs a rare candidate,
     * over-accepting plans a drop that ends on nothing.
     *
     * <p>Cold by construction: {@link #tryLanding} calls this only after {@link ClutchModel#best} has chosen
     * a kind whose {@link ClutchModel#footprint} exceeds 1, i.e. only for a bed clutch on an
     * otherwise-refused drop. Single-cell kinds never pay a read for it.
     */
    private static boolean clutchFootprintFits(MovementContext ctx, int feetX, int feetY, int feetZ) {
        for (int[] d : CARDINALS) {
            if (ctx.placeable(feetX + d[0], feetY, feetZ + d[1])) return true;
        }
        return false;
    }

    /** {@link #waterStop} sentinel: this column offers no Fall landing at all. */
    private static final int NO_WATER_LANDING = Integer.MIN_VALUE;

    /**
     * <b>Where a fall into water actually ends</b>, given the scan's standable seabed at {@code (nx,fy,nz)}
     * and a drop of {@code depth} blocks. Called ONLY once the caller has proven the seabed's feet cell holds
     * water. Returns the CELL the emitted node keys on — {@code fy} itself when the bot reaches the floor, a
     * higher water cell when it does not — or {@link #NO_WATER_LANDING} to refuse the candidate.
     *
     * <p>Three facts drive it, and each is load-bearing:
     * <ul>
     *   <li><b>Water at the FEET is what cushions.</b> Vanilla's reset is driven by the bot's final
     *       post-move position, so what matters is the cell it ends up in — not water it passed through.
     *       This is why a column suspended over a void needs no special handling: the scan only ever accepts
     *       a STANDABLE landing, so it walks past the suspended water to the real floor below, whose feet
     *       cell is air, and the drop is refused on the ordinary budget with no extra machinery. (A slow
     *       fall would in truth be reset by that suspended water; over-refusing is the safe direction — we
     *       never accept a lethal drop, we occasionally decline a survivable one.)</li>
     *   <li><b>Momentum decides WHERE the fall stops, not WHETHER it is allowed.</b> Entry speed carries the
     *       bot {@code p} blocks below the surface; if the column is deeper than that, the bot simply comes
     *       to rest in the water rather than on the seabed, and THAT cell is the landing. Refusing instead
     *       (the pre-2026-08-07 behaviour) rested on the premise that the remaining descent was a ~1400-tick
     *       terminal crawl — false, because {@link Swim} has an active sink rung ({@code DOWN_COST}, the
     *       {@code sinkInWater} press) and simply continues from the floating node at its own honest rate.</li>
     *   <li><b>It is still not a trajectory model</b> ({@code fall-not-vertical}). The planner names one
     *       cell and the follower servos to it ({@link SteerControl#holdDepth} in the FALL phase), so the
     *       whole-block table need only be right to within the servo's authority.</li>
     * </ul>
     *
     * <p>Costs ONE descriptor read when the feet cell is dry (the overwhelming case). The column walk runs
     * only over genuine water and never exceeds the drop itself — i.e. never more iterations than the
     * transit loop in {@link #tryLanding} already walks on the very same candidate.
     */
    private static int waterStop(MovementContext ctx, int nx, int fy, int nz, int depth) {
        // Walk the contiguous water column up to find the SURFACE. The caller has already proven the feet
        // cell (fy+1) is water, so this starts at the cell above it. Bounded by the drop itself: there
        // cannot be more water above the landing than there is fall to reach it. (The old
        // MAX_WATER_PENETRATION bound is gone — it was sound while this answered a yes/no gate, but the
        // surface is now needed to PLACE the floating node, and truncating the walk would misplace it.)
        int w = 1;
        while (w < depth && ctx.water(nx, fy + 1 + w, nz)) w++;
        int airDrop = depth - w;
        // No free fall above the water: the bot is stepping off INTO the column, which is Swim's business
        // (it would sink under its own weight, not fall) — decline rather than model it here.
        if (airDrop < 1) return NO_WATER_LANDING;
        int p = WATER_PENETRATION[Math.min(airDrop, WATER_PENETRATION.length - 1)];
        // w <= p: momentum crosses the whole column and the bot stands on the seabed — the historical
        // cushioned landing, priced exactly as before. Otherwise the feet come to rest p cells below the
        // surface, so the node cell is one below that. The two branches AGREE at the boundary: w == p+1
        // yields fy exactly, so there is no discontinuity to tune.
        return w <= p ? fy : fy + w - p - 1;
    }

    /**
     * §4 (NOTES-movement-physics.md) — price + emit a fall arrested in the passable-climbable
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
     * Walk off the lip and ARRIVE on the landing column — one servo for the whole move ({@link
     * SteerControl#arriveOnTarget}): aim the bot's projected stopping point at the landing centre, braking with
     * reverse thrust and correcting sideways with strafe. The medium switch is the servo's own: grounded it
     * projects with the ground coast (so the walk-off runs at full throttle and simply steps off), airborne it
     * projects with the ~8× longer air coast (so the arrest begins on the first airborne tick, while there is
     * still drop left to spend it in). {@link #candidates} models a fall as a straight vertical drop, and this
     * keeps the real drop near that column.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.arriveOnTarget(b, path);
    }

    /**
     * The phase-model execution plan — the reactive counterpart of {@link #steer}, which is now the SAME servo
     * in both phases, so the two drive paths cannot drift apart. Fall is <b>WALKOFF &rarr; FALL</b>: stride off the
     * lip toward the landing column {@code (tx,tz)} (which {@link #candidates} makes identical to the step-off
     * neighbour column — the bot walks off into it and drops straight down), then, once airborne, home onto
     * that column while the drop runs, completing only when actually standing on the landing cell.
     *
     * <p><b>No needs.</b> Every {@link #candidates} emit but the clutch one is the 4-arg edit-free {@code
     * accept}: the whole drop column and the step-off body are proven passable-<i>intact</i> and priced with
     * intact-transit costs ({@link MovementContext#cellTransitCost}/{@link MovementContext#bodyTransitCost}),
     * never a break or place (the class Javadoc's "Fall folds no edits on a terrain-survivable landing" rule).
     * So neither phase carries a {@link MovePlan.Need}: the plan's empty need set covers that empty edit set
     * exactly. Declaring an AIR need would be actively wrong — the runner would try to {@code mine} a mid-drop
     * cell it can never reach while airborne.
     *
     * <p><b>The clutch place is deliberately NOT a Need either</b> (class doc §Clutch). A {@link MovePlan.Need}
     * is satisfied <i>before</i> its phase runs, and a clutch is by definition placed MID-DROP — the runner's
     * need vocabulary cannot express "do this while airborne", and inventing a variant of it here would be
     * guessing at follower machinery this class does not own. The fold on that candidate is therefore a
     * PLANNER-side record, carrying the two properties {@link #tryLanding} documents (edit prescription and the
     * {@code mayBreak} reclaim exemption); executing the placement is the follower's business and the phases
     * below are unchanged by it.
     *
     * <p>No {@code boolean[]} arm is needed for {@link #resetWhen} (unlike {@link Parkour}): the runner only
     * evaluates it once the cursor has advanced, and Fall reaches phase 1 ONLY via {@code advanceWhen(!grounded)},
     * so by the time the guard is live the bot has already gone airborne — the phase-0→1 transition IS the
     * "went airborne" event, with no takeoff-window aliasing to disarm around. The guard can then be true only
     * if the bot came back down onto the exact start cell, which is precisely the balked step-off to re-attempt.
     */
    /**
     * Block-interaction reach, blocks — the vanilla {@code block_interaction_range} attribute default
     * (javap: {@code RangedAttribute("attribute.name.block_interaction_range", 4.5d, 0, 64)}).
     *
     * <p>This is what makes {@link #clutchTick}'s place-every-tick strategy a GUARANTEE rather than a hope:
     * air terminal velocity is {@code 0.08 × 0.98 / 0.02 = 3.92} b/t, strictly less than 4.5, so however deep
     * the drop the clutch cell is inside reach on at least one tick and cannot be stepped over between
     * samples. It is the same tunneling question {@link #HANG_MAX_DROP} answers with a bound — here the
     * inequality falls the right way and no bound is needed.
     */
    private static final double CLUTCH_REACH = 4.5;
    /** Standing eye height (blocks) above the feet — {@link BotSteering#y()} reports FEET Y, and reach is
     *  measured from the eyes. Vanilla {@code Pose.STANDING} eye height. */
    private static final double EYE_HEIGHT = 1.62;
    /** Vanilla's own damage-free fall window (the {@code safe_fall_distance} attribute default, 3.0 blocks) —
     *  NOT {@code caps.safeFallDistance()}, which is the planner's model. The bounce servo is deciding whether
     *  a REAL landing will hurt, so it must ask vanilla's number; {@link MovePlan} carries no caps anyway. */
    private static final double VANILLA_SAFE_FALL = 3.0;

    /**
     * Per-tick clutch execution, run from the FALL phase's drive (class doc §Clutch). Does nothing at all when
     * the step carries no clutch — one {@code int} compare, which is every fall the bot has ever taken.
     *
     * <p>Two jobs, in order:
     * <ul>
     *   <li><b>Place</b>, while still falling. The attempt is made EVERY tick the cell is in reach rather than
     *       computed for one exact tick, which is how a human clutches (spam the button on the way down) and
     *       is provably sufficient here — see {@link #CLUTCH_REACH}. {@code placeClutch} is side-effect-free
     *       when it refuses, so a failed attempt costs nothing and the next tick tries again.</li>
     *   <li><b>Reclaim</b>, once the bot has actually come to rest. Water is the reason this must be prompt:
     *       {@code WaterFluid.getTickDelay} is 5, so a source reclaimed inside that window never spreads and
     *       therefore never produces the cascade of unannounced neighbour edits that would invalidate the plan
     *       out from under the bot.</li>
     * </ul>
     *
     * <p>{@code st} is the per-move latch the phase closures capture ({@link Ascend}'s {@code boolean[]}
     * precedent): {@code [0]} placed, {@code [1]} reclaimed. It lives in the plan, not on this singleton —
     * {@link com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry#FALL} is shared across every search
     * and every planner thread, so instance state here would be a race.
     */
    private static void clutchTick(BotSteering b, MovePlan p, boolean[] st) {
        final int kind = p.clutchKind();
        if (kind == ClutchModel.NONE) return;
        final int cx = p.clutchX(), cy = p.clutchY(), cz = p.clutchZ();
        if (!st[0]) {
            if (inClutchReach(b, cx, cy, cz)) st[0] = b.placeClutch(cx, cy, cz, kind);
            return;
        }
        if (st[1] || !b.settled()) return;
        // A LANDS-ON-TOP clutch is DELIBERATELY NOT RECLAIMED, and this is a consequence of the planner's
        // geometry split rather than a special case (owner report 2026-08-08: hay landed, took its minor
        // damage, reclaimed — and the bot then stopped moving).
        //
        // For those kinds tryLanding folds the placed block as the node's FLOOR and emits the node ON it, so
        // every later step of this plan was searched standing on that block. Taking it back deletes the floor
        // the bot is on: it drops a block, its feet cell is no longer the waypoint, and the whole remaining
        // path is framed off a cell it is not in. The plan and the world would disagree from that tick on.
        //
        // Sink-through kinds are the mirror image and are why the reclaim exists at all: they fold NO geometry,
        // the plan says that cell is empty, and reclaiming is what MAKES that true again.
        //
        // So the item is spent. That is already what the cost model assumes — the placement is priced and the
        // recovery was never credited — so nothing is mis-priced by keeping it; the bot simply leaves a hay
        // bale behind as the step it built.
        if (!ClutchModel.reclaimable(kind)) {
            st[1] = true;
            return;
        }
        // A bouncing kind is NOT at rest merely because it is momentarily grounded — see bounceSettled.
        if (ClutchModel.bounces(kind) && !bounceSettled(b, kind)) return;
        st[1] = b.reclaimClutch(cx, cy, cz, kind);
    }

    /** Whether the clutch cell's centre is inside {@link #CLUTCH_REACH} of the bot's EYES. */
    private static boolean inClutchReach(BotSteering b, int cx, int cy, int cz) {
        double dx = (cx + 0.5) - b.x();
        double dy = (cy + 0.5) - (b.y() + EYE_HEIGHT);
        double dz = (cz + 0.5) - b.z();
        return dx * dx + dy * dy + dz * dz <= CLUTCH_REACH * CLUTCH_REACH;
    }

    /**
     * The BOUNCE servo (owner design, 2026-08-08) — slime and beds rebound, so a landing on one is not over
     * when the bot first touches it.
     *
     * <p>The rule is "let it bounce, then suppress once suppression is free", and the ORDER is load-bearing
     * because of a version divergence at <b>1.21.2</b>: on 1.21.1 and older, {@code SlimeBlock.fallOn}'s
     * suppressing branch calls {@code super.fallOn} and deals FULL damage; from 1.21.2 it simply returns.
     * Sneaking on the FIRST contact would therefore be lethal on the older half of the range, where
     * {@code fallDistance} is still the whole drop. Not sneaking is the zero-multiplier path on EVERY version,
     * so bouncing first is universally safe, and each bounce resets {@code fallDistance} (Entity
     * .checkFallDamage resets unconditionally whenever grounded). Once the rebound is small enough that the
     * next landing is inside vanilla's own safe window, suppressing it costs nothing on either side of the
     * divergence — so one servo is correct across the whole range and no {@code overlays/} entry is needed.
     *
     * <p>The test is the predicted apex of the bounce this impact would produce, not a tick count: sneak is
     * pressed in the drive, which runs before the tick's physics, so the press suppresses THIS bounce.
     */
    private static boolean bounceSettled(BotSteering b, int kind) {
        double impact = Math.abs(b.velY());
        if (ClutchModel.bounceApex(kind, impact) > VANILLA_SAFE_FALL) return false; // let it rebound again
        b.setSneak(true); // suppresses this tick's bounce; the residual fall is damage-free on every version
        return true;
    }

    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        final int landFeetY = toFootY;            // feet BLOCK Y once standing on the landing floor (topY-aware)
        MovePlan plan = new MovePlan();
        // Per-move clutch latch: [0] placed, [1] reclaimed. Allocated once per plan, never per tick.
        final boolean[] clutchState = new boolean[2];
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
                // arriveOnTarget, NOT steerTowards (measured 2026-08-06). steerTowards has no velocity term, so
                // it aimed at the pursuit point and held FULL forward through the support-loss point; the cross
                // component of that facing then coasted uncorrected for the whole drop (+0.344 blocks of X
                // drift). The same servo drives both phases now: while grounded it projects with the GROUND
                // coast, so the walk-off runs at full throttle and simply steps off the lip; the tick the bot
                // is airborne the projection flips to the AIR coast and the air-brake arrests the carry. The
                // interim steppingOff variant that pre-braked on the ground is REJECTED — see arriveOnTarget.
                .drive(SteerControl::arriveOnTarget)
                .advanceWhen(b -> !b.grounded());
        // FALL: airborne drop-control — arriveOnTarget aims the bot's PROJECTED stopping point at the landing
        // column centre, braking with reverse input the moment that projection overshoots, so held step-off
        // momentum can't carry the bot off a 1-wide landing (or, as measured 2026-08-06, park it at the far
        // cell edge with no runup left for the step that follows).
        // Complete only once actually SETTLED on the landing cell. One predicate covers all THREE landing
        // kinds, because BotSteering.settled() is `grounded() || inWater() || inLava() || hangingOnClimbable()`:
        // a standable floor (grounded), a HANG in a vine (never grounded — NOTES-movement-physics.md §5), and
        // now a WET endpoint (floating in the column, also never grounded — see waterStop). Being in water
        // cannot fire this early: settled() goes true the instant the bot touches the surface, but the feet
        // cell only matches at the planned stop, many cells further down.
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
                    // Wet endpoints (see {@link #waterStop}): a fall into deep water ends FLOATING at the
                    // cell the entry momentum carries the bot to, and nothing about being in water makes
                    // the bot grounded. holdDepth is the arrest — it presses the inputs that bring the feet
                    // to the planned height, so the landing is reached even where the whole-block momentum
                    // table lands a cell off (the servo, not the table, is what makes reality match the
                    // model — `fall-not-vertical`). It is a NO-OP out of fluid, so every dry fall — the
                    // overwhelming case — is bit-identical to before. Bias 0: the bot arrives upright, the
                    // same bias `drive`'s in-water branch uses for ground moves crossing water.
                    SteerControl.holdDepth(b, v, 0);
                    // Clutch execution (class doc §Clutch): place on the way down, reclaim once at rest.
                    // A no-clutch step exits on one int compare, so every ordinary fall is unaffected.
                    clutchTick(b, plan, clutchState);
                })
                // SETTLED, not the loose onClimbable(): being INSIDE a vine is true on every tick of a fall
                // THROUGH one, so the loose test called a still-falling bot landed. It also disagreed with
                // Movement.reached (which gates the cursor advance on settled()), and THAT disagreement is
                // what wedged the bot — the phase reported done at the landing cell while the cursor refused
                // to advance, so the fall just continued. One predicate now answers both questions.
                .done(b -> b.settled()
                        && atWaypoint(b, tx, landFeetY, tz)
                        // A clutch step is not finished until the block it dropped is back in the bag. The
                        // reclaim runs in the drive ABOVE, which the runner executes before it evaluates this
                        // predicate, so a clean clutch still completes on the very tick it settles — no extra
                        // phase and no extra tick for the ordinary fall, whose latch reads done immediately.
                        && clutchDone(plan, clutchState));
        return plan;
    }

    /**
     * Whether the step's clutch obligation is discharged: trivially true when there is no clutch (every fall
     * before this arc), otherwise true only once the placed block has been reclaimed.
     *
     * <p>Gating {@code done} on this is what keeps the reclaim from being skipped, and it is deliberately the
     * ONLY thing gated: the placement is best-effort by design (a refused place simply retries next tick, and
     * if the bot somehow lands unclutched the drop was priced as survivable-with-clutch and it takes the
     * damage), whereas a skipped RECLAIM leaks a real item and, for water, leaves a source that spreads five
     * ticks later into a cascade of unannounced edits.
     */
    private static boolean clutchDone(MovePlan p, boolean[] st) {
        return p.clutchKind() == ClutchModel.NONE || st[1];
    }
}
