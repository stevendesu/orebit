package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * Parkour — a running gap jump to a same-level landing across {@code g} open columns (MOVEMENT-DESIGN
 * Tier 1 parkour; the gap move the {@link Movement} interface's own Javadoc forecast). For each cardinal
 * the move scans outward: every gap column's transit prism must be genuinely open, the first standable
 * cell at node level ends the scan (never overfly a ledge in v1), and the landing body must be clear.
 * Emits ONE multi-cell edge — {@code macroSteps} gates re-expansion by movement identity, so the jump
 * stays a single follower waypoint automatically.
 *
 * <h2>No edit folding — a hard validity rule</h2>
 * You cannot mine or place mid-jump, so Parkour uses the plain edit-free {@code accept} (the {@link Fall}
 * precedent): a takeoff/gap/landing cell that would need a break simply kills the candidate — the
 * break-through/bridge moves already own that terrain. Requiring the gap column open at node level
 * {@code y} (not just the body cells) excludes fences/walls whose {@code SHAPE_OTHER} collision at
 * {@code y} pokes up into the transit space.
 *
 * <h2>Cost model (derivations)</h2>
 * Vanilla jump impulse 0.42 with gravity {@code 0.08·0.98} gives a ~12-tick same-level arc; shorter jumps
 * land earlier, longer ones add sprint windup: {@code AIR_COST = 8 / 11 / 14} ticks for {@code g = 1/2/3}.
 * Plus {@link #RUNUP_COST} (one walk step onto the takeoff edge, {@link Traverse#FLAT_COST}) and
 * {@link #COMMIT_PENALTY} (3 ticks — a behavioral premium for an all-or-nothing move). Totals 15.6 /
 * 18.6 / 21.6. Honesty vs bridging: a canPlace bot bridging g=2 pays ≈32 ticks vs 18.6, so parkour wins
 * when legal and the bridge remains the fallback when clearance fails; a no-place bot gets a route where
 * none existed. Per-block ≈6.2 t ≥ the octile ruler (4.633) — no heuristic concern (and SprintSwim's 3.56
 * already set the sub-heuristic precedent under {@code greedyWeight ≥ 1}).
 *
 * <h2>Hazard / through-slow vocabulary (post-design cost pass) — consulted deliberately</h2>
 * The ratified design predates {@link MovementContext#cellTransitCost}/{@link
 * MovementContext#bodyTransitCost}; Parkour adopts them because they are free here: the three transited
 * body cells per gap column ({@code y+1..y+3}) are read for the passable gate anyway, so pricing them is
 * pure bit tests on already-read descriptors (zero extra grid reads), and the landing body rides the
 * flags-gated {@code bodyTransitCost} exactly as {@link Traverse} does. So jumping through fire prices
 * the mortal-bot damage surcharge and a cobweb in the arc adds {@code WEB_TRANSIT_COST} (~88 ticks) —
 * which prices a web-blocked jump out of every contested route WITHOUT a bespoke reject rule (a web
 * would physically arrest the arc; if it is truly the only route the miss lands the bot in the gap and
 * the follower's grounded-stall arm replans — cost-not-blocker, the {@link Fall} model). The gap cell at
 * node level {@code y} is a geometry proxy (the fence exclusion), not a transited cell, and is left
 * unpriced; the source takeoff cell {@code y+3} is clearance-only, unpriced (the {@link Ascend}
 * precedent).
 *
 * <h2>Irreversibility</h2>
 * Same-level jumps are symmetric — genuinely reversible (the return jump is the same move). The partial
 * -path guard's Y-only model ({@code lastReversibleRow} inspects drops {@code > jumpHeight} only) is
 * therefore CORRECT for parkour edges precisely because the horizontal component is symmetric: they never
 * truncate a partial, and never should.
 *
 * <h2>Execution — the second consumer of the phase framework after {@link Pillar}</h2>
 * {@link #plan} is RUNUP → TAKEOFF → AIRBORNE → LAND, all predicates positional ({@code BotSteering}
 * exposes no velocity): run at the takeoff edge and jump as late as possible ({@link #TAKEOFF_EDGE}),
 * hold full forward + sprint through the whole arc ({@code steerTowards}, never {@code recenterOnTarget}
 * — its eased forward near the column would kill mid-air momentum), then settle. {@code resetWhen} covers
 * the balked jump (physically back on the start cell → re-run from RUNUP) and is ARMED only once the arc
 * has actually begun — the takeoff fires from a state (grounded, {@code blockPosition()} still the start
 * cell) the guard would otherwise alias with, preempting the jump input; a landing overshoot is
 * absorbed by the follower's scan-from-the-end waypoint advance; landing IN the gap (grounded, on-line,
 * no progress) is the follower's grounded-stall recovery arm — see {@code AllyBotEntity.steerAlongPath}.
 * No {@code need(...)} entries (edit-free), so the {@code PhaseRunner} never holds.
 */
public final class Parkour implements Movement {

    /**
     * The largest gap (open columns) the move offers, indexing {@link #COST}. Default 2 (shipped enabled);
     * 3-gap jumps are gated behind setting this to 3 (default-off — a max-distance sprint jump barely
     * clears 3 open columns and stays off until the takeoff timing is proven in-game). Values above 3 are
     * clamped to the cost table.
     */
    public static int PARKOUR_MAX_GAP = 2;

    /** Ticks for the approach step onto the takeoff edge — one walk step ({@link Traverse#FLAT_COST}). */
    public static final float RUNUP_COST = Traverse.FLAT_COST;

    /**
     * Ticks in the air by gap size ({@code [g]}, index 0 unused): the ~12-tick full same-level arc, with
     * shorter jumps landing earlier (g=1: 8, g=2: 11) and g=3 adding sprint windup (14).
     */
    private static final float[] AIR_COST = {0f, 8f, 11f, 14f};

    /** Behavioral premium (ticks) for an all-or-nothing move — a jump can't be abandoned halfway. */
    public static final float COMMIT_PENALTY = 3f;

    /** Total edge cost by gap size: {@code RUNUP + AIR[g] + COMMIT} → 15.6 / 18.6 / 21.6 ticks. */
    private static final float[] COST = {
            0f,
            RUNUP_COST + AIR_COST[1] + COMMIT_PENALTY,
            RUNUP_COST + AIR_COST[2] + COMMIT_PENALTY,
            RUNUP_COST + AIR_COST[3] + COMMIT_PENALTY,
    };

    /**
     * How far past the takeoff cell's centre (blocks, along the jump axis) the bot runs before jumping —
     * leaves ~0.15 block behind the 0.3-half-width hitbox trailing edge, i.e. jump as late as possible
     * without stepping off. Tune 0.30–0.40 in-game.
     */
    public static final double TAKEOFF_EDGE = 0.35;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // a running jump — only while upright
        if (ctx.caps().jumpHeight() < 1) return;

        // Takeoff head-clearance (source y+3) — direction-independent, proven once. The bot stands here so
        // its feet/head are clear; HEADROOM == JUMP iff y+3 is also clear. No break folding (you cannot
        // mine mid-jump): an unproven bit falls back to reading the real cell, and a blocked cell rejects.
        int srcFlags = ctx.flagsAt(x, y, z);
        if (!ctx.headroomProves(srcFlags, y, MovementContext.HEADROOM_JUMP)) {
            int p3 = ctx.packedAt(x, y + 3, z);
            if (p3 == MovementContext.UNBUILT
                    || !ctx.passable(ctx.descriptorOf(x, y + 3, z, p3))) {
                return;
            }
        }

        final int maxGap = Math.min(PARKOUR_MAX_GAP, COST.length - 1);
        for (int[] d : CARDINALS) {
            int dx = d[0], dz = d[1];
            float transit = 0f; // accumulated pass-through surcharge over the gap body cells
            for (int c = 1; c <= maxGap + 1; c++) {
                int cx = x + dx * c;
                int cz = z + dz * c;

                // The column's node-level cell decides landing-vs-gap — read its slot once.
                int p = ctx.packedAt(cx, y, cz);
                if (p == MovementContext.UNBUILT) break; // unknown column — don't jump into it
                long fd = ctx.descriptorOf(cx, y, cz, p);

                if (ctx.standable(fd)) {
                    // A landing (or, at c == 1, an ordinary Traverse ledge): never overfly it in v1.
                    int g = c - 1;
                    if (g >= 1) {
                        int flags = MovementContext.flagsOf(p);
                        boolean clear = ctx.headroomProves(flags, y, MovementContext.HEADROOM_WALK);
                        if (!clear) {
                            // Verify the real landing body cells — again with no break folding.
                            int p1 = ctx.packedAt(cx, y + 1, cz);
                            int p2 = ctx.packedAt(cx, y + 2, cz);
                            clear = p1 != MovementContext.UNBUILT && p2 != MovementContext.UNBUILT
                                    && ctx.passable(ctx.descriptorOf(cx, y + 1, cz, p1))
                                    && ctx.passable(ctx.descriptorOf(cx, y + 2, cz, p2));
                        }
                        if (clear) {
                            out.accept(cx, y, cz,
                                    COST[g] + transit + ctx.bodyTransitCost(flags, cx, y, cz));
                        }
                    }
                    break; // standable at node level always ends this direction's scan
                }

                // Not standable ⇒ must be a valid gap column, and there must still be room for a landing.
                if (c > maxGap) break; // no landing within the gap cap
                // Node-level cell open (the fence/wall exclusion — SHAPE_OTHER at y pokes into the transit
                // space). A gap cell that's water/lava also fails passable — no jumping over an open fluid
                // column's surface cell in v1.
                if (!ctx.passable(fd)) break;
                // The three transited body cells (y+1..y+3): open, and priced per cell as they are read
                // (read-once — the same descriptor answers passable AND the hazard/through-slow surcharge).
                int p1 = ctx.packedAt(cx, y + 1, cz);
                if (p1 == MovementContext.UNBUILT) break;
                long d1 = ctx.descriptorOf(cx, y + 1, cz, p1);
                if (!ctx.passable(d1)) break;
                int p2 = ctx.packedAt(cx, y + 2, cz);
                if (p2 == MovementContext.UNBUILT) break;
                long d2 = ctx.descriptorOf(cx, y + 2, cz, p2);
                if (!ctx.passable(d2)) break;
                int p3 = ctx.packedAt(cx, y + 3, cz);
                if (p3 == MovementContext.UNBUILT) break;
                long d3 = ctx.descriptorOf(cx, y + 3, cz, p3);
                if (!ctx.passable(d3)) break;
                transit += ctx.cellTransitCost(d1) + ctx.cellTransitCost(d2) + ctx.cellTransitCost(d3);
            }
        }
    }

    /**
     * The phase-model jump: RUNUP (drive the line, sprint if {@code g >= 2}, until past the takeoff edge)
     * → TAKEOFF (hold jump until airborne) → AIRBORNE (full forward + sprint the whole arc — held until
     * touchdown so the eased-forward landing settle can never bleed mid-air momentum) → LAND (re-centre,
     * done once standing on the target cell). All predicates positional; {@code resetWhen} re-runs from
     * RUNUP when the bot is physically back on the start cell after a balked jump. The guard is ARMED by
     * the airborne drive and DISARMED by the runup drive: takeoff's {@code advanceWhen} trips at {@link
     * #TAKEOFF_EDGE} (~0.85 past centre) with {@code blockPosition()} still the start cell and vanilla
     * onGround still true, so an always-on guard would alias with the very state the jump must fire from —
     * the runner checks {@code resetWhen} before driving whenever the cursor has advanced, snapping back
     * to RUNUP and preempting {@code setJumping} every tick of the takeoff window. Landing in the gap is
     * NOT a plan phase: the follower's grounded-stall recovery arm re-anchors and replans from inside the
     * gap.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        final int ux = Integer.signum(tx - fx);
        final int uz = Integer.signum(tz - fz);
        final boolean sprint = Math.abs(tx - fx) + Math.abs(tz - fz) - 1 >= 2; // g >= 2 needs sprint reach
        // Regression-guard arm: true only while an arc is live (set airborne, cleared on a runup re-attempt).
        // Cold per-step allocation — a MovePlan is built once per waypoint step (the Pillar precedent).
        final boolean[] airborneOnce = new boolean[1];
        MovePlan plan = new MovePlan();
        plan.resetWhen(b -> airborneOnce[0] && b.grounded()
                && b.footX() == fx && b.footY() == fy + 1 && b.footZ() == fz);
        plan.phase("runup")
                .drive((b, v) -> {
                    airborneOnce[0] = false; // re-attempt begins → disarm until the next arc is live
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                })
                // Takeoff trigger: grounded AND the bot's along-axis progress past the start-cell centre
                // reaches TAKEOFF_EDGE — jump as late as possible without stepping off the lip.
                .advanceWhen(b -> b.grounded()
                        && ux * (b.x() - (fx + 0.5)) + uz * (b.z() - (fz + 0.5)) >= TAKEOFF_EDGE);
        plan.phase("takeoff")
                .drive((b, v) -> {
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                    b.setJumping(true);
                })
                .advanceWhen(b -> !b.grounded());
        plan.phase("airborne")
                // steerTowards, NOT recenterOnTarget: the recenter drive eases forward toward 0 near the
                // target column, which kills the horizontal momentum the jump lives on.
                .drive((b, v) -> {
                    airborneOnce[0] = true; // arc is live → a grounded return to the start cell is a balk
                    SteerControl.steerTowards(b, v);
                    b.setSprinting(sprint);
                })
                .advanceWhen(b -> b.grounded()); // hold the arc inputs until touchdown
        plan.phase("land")
                .drive(SteerControl::recenterOnTarget)
                .done(b -> b.grounded()
                        && b.footX() == tx && b.footY() == ty + 1 && b.footZ() == tz);
        return plan;
    }
}
