package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Ordinary (non-sprint) swimming — the <b>upright fluid-medium movement</b>
 * (NOTES-vanilla-fluid-physics.md §1). Six-directional: the four cardinals plus a straight rise and a
 * straight sink, all in the tall {@link MovementContext#MODE_STANDING} pose. It is the structural analogue of
 * {@link Climb} — one movement, several rungs, each priced from its own real vanilla rate — and it is the move
 * that <b>un-walls fluid</b>: before it, {@link MovementContext#passable} rejected every fluid cell, so a river
 * was an impassable wall the bot bridged over.
 *
 * <h2>Fluid is a MEDIUM, not a pose (owner ruling 2026-08-07)</h2>
 * The prone {@link SprintSwim} family is fast <i>lateral</i> travel and nothing else; everything a bot can do in
 * fluid <i>upright</i> lives here. Two consequences the old design got wrong, both convicted on the flagship
 * waterfall at {@code (154,*,104)}:
 * <ul>
 *   <li><b>Entry no longer requires an air head</b> (§3.2). The old scan demanded {@code passable} at the
 *       destination head, and {@code passable} excludes fluid — so a submerged destination was rejected, and the
 *       rejection was a {@code break}, killing the down-scan too. Combined with {@link StartSprintSwim} needing
 *       the bot's own feet already wet and {@link SprintSwim} needing {@code MODE_PRONE}, <b>Swim was the only
 *       dry&rarr;wet rung in the whole vocabulary and it demanded a surface cell.</b> A dry bot could not walk
 *       into the body of a waterfall. That is why the planner placed cobble at {@code (153,-14,104)} — the one
 *       cell where the fall spread over a stone block and happened to have air above it. There was never a pool.
 *       The head test is now "<b>not solid</b>" (air <i>or</i> fluid), which is the real upright-fit
 *       requirement, and the two cases are priced apart ({@link #COST} vs {@link #SUBMERGED_COST}).
 *       A <b>STEP-DOWN</b> entry (fluid below the bot's own feet level) from a NON-submerged start additionally
 *       requires the <b>walk-out head cell</b> — {@code (nx, feetY+1, nz)}, the cell over the destination column
 *       at the bot's own standing head height — to be non-solid (owner rule 2026-08-20; the walk-out clearance
 *       gate in {@link #candidates}). The executor walks a step-down out ABOVE the water at standing height like
 *       a {@link Descend} and then drops, so the destination column needs headroom "as tall as the starting
 *       cell" — 3 open blocks over the destination feet, exactly Descend's transit shape.</li>
 *   <li><b>The verticals live here, not on the prone family</b> (§3.1/§4). {@link SprintSwim}'s pure-up and
 *       pure-down rungs were never real — a swimming look clamps near 80&deg;, so the last ~10&deg; is always
 *       lateral drift, and holding the prone pose requires holding forward every tick. In open water that is
 *       recoverable drift; in a 1&times;1 waterfall it is ejection at speed followed by a fall. The upright
 *       rise/sink below replaces them, so the defect is deleted at its source rather than servoed around.</li>
 * </ul>
 *
 * <h2>Every fluid, one vocabulary (§3.3)</h2>
 * There is no lava-only rung any more. Each rung works in water and in lava alike, and the <b>damage and slow
 * factors carry the cost</b> ({@link MovementContext#lavaSwimCellCost} — a mortal bot prices a lava cell at
 * ~1000 ticks and routes around it; an immune bot pays only the 2.5&times; slow factor and swims through). What
 * does <i>not</i> unify is the prone family, and that is vanilla's ruling, not ours: {@code
 * Entity.updateSwimming}'s entry branch carries an explicit {@code FluidTags.WATER} test and its stay branch
 * tests {@code isInWater()}, so {@code Pose.SWIMMING} is unreachable in lava (bytecode-verified against 1.21.11).
 * {@link SprintSwim}/{@link StartSprintSwim} therefore gate on {@link MovementContext#water}, and in lava the
 * upright rungs here are simply all there is.
 *
 * <h2>The floor-cell convention for a floating bot (decision C)</h2>
 * The search space is floor cells with the bot's feet at {@code floor.above()}. Fluid has no solid floor, so a
 * swim node's "floor" is simply the cell BELOW the feet — which is allowed to be fluid. This keeps
 * {@code floor.above() == feet} universal, so {@code reconstruct} and the follower geometry are unchanged; a
 * swim node simply has a non-standable floor.
 */
public final class Swim implements Movement {

    // ---- Costs: every rung is 20 ticks/s divided by its own measured or bytecode-derived vanilla rate ----
    // (physically-derived-costs — real ticks, never tuned magic numbers).

    /**
     * <b>Surface</b> lateral swim, ticks/block: {@code 20 / 2.2 ≈ 9.09} — the "head in open air" paddle, and
     * appreciably slower than the 4.633-tick walk.
     */
    public static final float COST = 20f / 2.2f;

    /**
     * <b>Submerged</b> lateral swim, ticks/block: {@code 20 / 1.97 ≈ 10.15}. Not an invented number — the
     * class doc has reserved 1.97 b/s as "the submerged normal-swim case … deferred to the stateful
     * refinement" since the move was written. This is that refinement.
     */
    public static final float SUBMERGED_COST = 20f / 1.97f;

    // The vertical rates are derived from vanilla's fluid integrator rather than a wiki figure, because no
    // published b/s number covers "hold jump in water" (bytecode-adjudicated against 1.21.11, 2026-08-07):
    //   LivingEntity.jumpInLiquid          +0.04 /t   (and AllyBotEntity.sinkInWater mirrors it at -0.04)
    //   LivingEntity.getWaterSlowDown       0.8       (travelInWater multiplies Y by a hardcoded 0.8)
    //   getFluidFallingAdjustedMovement    -gravity/16 = -0.08/16 = -0.005 /t  (Attributes.GRAVITY = 0.08)
    // The impulse lands in aiStep BEFORE travel, so one tick is v' = drag*(v ± impulse) - gravity and the
    // terminal speed is (drag*impulse ∓ gravity)/(1 - drag). Sinking is the faster of the two because gravity
    // and the impulse pull the same way — which is why a dive is cheaper than a rise.
    private static final float FLUID_DRAG = 0.8f;
    private static final float FLUID_GRAVITY = 0.005f;
    private static final float SWIM_IMPULSE = 0.04f;
    private static final float RISE_PER_TICK = (FLUID_DRAG * SWIM_IMPULSE - FLUID_GRAVITY) / (1f - FLUID_DRAG);
    private static final float SINK_PER_TICK = (FLUID_DRAG * SWIM_IMPULSE + FLUID_GRAVITY) / (1f - FLUID_DRAG);

    /**
     * <b>Rise</b> one cell, ticks/block: {@code 1 / 0.135 ≈ 7.41} — hold jump, the derivation above. This is the
     * rung that carries a bot up a 1&times;1 waterfall, and it is deliberately the ONLY way up a fluid column
     * now that {@link SprintSwim} has no verticals. <i>Derived, not yet measured in-game</i> — the design doc's
     * provisional estimate of ~5 was arrived at without vanilla's in-fluid gravity term and is superseded here.
     */
    public static final float UP_COST = 1f / RISE_PER_TICK;

    /**
     * <b>Sink</b> one cell, ticks/block: {@code 1 / 0.185 ≈ 5.41} — the {@link BotSteering#sinkInWater} press,
     * cheaper than the rise because gravity assists it.
     */
    public static final float DOWN_COST = 1f / SINK_PER_TICK;

    /**
     * How far below the bot's current feet the lateral scan looks for the fluid surface — the maximum drop it
     * will take to enter fluid from a bank/ledge. Small and conservative (fluid cushions the landing, but a big
     * committed plunge belongs to a future water-Fall variant); a deeper entry just isn't offered here. Every
     * step BELOW the feet level found by this scan is a step-down entry and, from a non-submerged start, must
     * also pass the walk-out clearance gate in {@link #candidates} (owner rule 2026-08-20): the walk-out head
     * cell {@code (nx, feetY+1, nz)} must be non-solid, because the executor walks out above the water at
     * standing height before dropping.
     */
    private static final int MAX_SINK = 4;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // the upright paddle — STANDING pose only

        // Is the bot itself in fluid? Gates the vertical rungs (you cannot rise or sink through air) and the
        // lateral dominance gate below. A dry bot — the overwhelming majority of expansions — answers this in
        // ONE section resolve (packedAt, the read-once seam) and falls straight through to the entry scan.
        final int feetY = y + 1;
        final int feetPacked = ctx.packedAt(x, feetY, z);
        final boolean wet = feetPacked != MovementContext.UNBUILT
                && fluid(ctx, ctx.descriptorOf(x, feetY, z, feetPacked));

        if (wet) {
            // RISE. New feet (x,y+2,z) must be fluid; the new head (x,y+3,z) must be non-solid or the upright
            // pose does not fit there (vanilla Player.updatePlayerPose falls back through CROUCHING to
            // SWIMMING rather than standing in a 1-tall gap — see EndSprintSwim §5.1).
            int risePacked = ctx.packedAt(x, y + 2, z);
            if (risePacked != MovementContext.UNBUILT) {
                long riseDesc = ctx.descriptorOf(x, y + 2, z, risePacked);
                if (fluid(ctx, riseDesc) && bodyClear(ctx, x, y + 3, z)) {
                    out.accept(x, y + 1, z, cellCost(ctx, riseDesc, UP_COST));
                }
            }
            // SINK. New feet (x,y,z) must be fluid. No head test is needed: the destination's head cell IS the
            // bot's current feet cell, already proven fluid by `wet`.
            int sinkPacked = ctx.packedAt(x, y, z);
            if (sinkPacked != MovementContext.UNBUILT) {
                long sinkDesc = ctx.descriptorOf(x, y, z, sinkPacked);
                if (fluid(ctx, sinkDesc)) {
                    out.accept(x, y - 1, z, cellCost(ctx, sinkDesc, DOWN_COST));
                }
            }
        }

        // THE DOMINANCE GATE, lateral rungs only (§5.3). Where a prone sprint-swim can actually make PROGRESS,
        // it strictly dominates the slow lateral paddle: crossing N cells costs 9.09N upright against
        // 2 + 3.56N + 2 prone, and the dive already wins at N = 1 (7.56 vs 9.09). Suppressing the slow rung is
        // what makes the dive FINDABLE at all — the search is greedy (w = 2.0) and a lateral paddle toward the
        // goal is f-DECREASING (+9.09 g against −9.27 h) while the in-place dive is a ≈ +5.8 f-hill, so the
        // dive node is otherwise never popped. There is no de-greeding step anywhere in the search; this is
        // the only mechanism.
        //
        // The question it asks changed with this design. It used to ask "can sprint-swim INITIATE here?",
        // which is wrong twice over: it suppressed the VERTICAL rungs too (and now that SprintSwim has no
        // verticals, nothing would carry a bot up a 1×1 column), and initiating is worthless if the prone bot
        // cannot then go anywhere. It now asks "can sprint-swim make PROGRESS from here?" — is there a lateral
        // water neighbour at the depth the prone bot would actually occupy? `water`, not `fluid`: the prone
        // pose is water-only by vanilla, so lava never suppresses the upright paddle.
        if (wet && lateralSprintDominates(ctx, x, y, z)) return;

        // Start-side term of the WALK-OUT CLEARANCE GATE below (owner rule 2026-08-20): is the bot's own head
        // cell already fluid? Eyes underwater is one of the only two dive initiations (the other, a trapdoor
        // crawl, is unmodeled), so a submerged start KEEPS its step-down entries — it swims down and out, it
        // never walks out above the surface. Gated on `wet`: a dry bot's head cannot be fluid (fluid does not
        // float on air in a column the bot stands dry in), so the overwhelmingly common dry expansion pays
        // nothing here; a wet one pays one section-cached read, computed once for all four cardinals.
        final boolean startSubmerged;
        if (wet) {
            int headPacked = ctx.packedAt(x, y + 2, z);
            startSubmerged = headPacked != MovementContext.UNBUILT
                    && fluid(ctx, ctx.descriptorOf(x, y + 2, z, headPacked));
        } else {
            startSubmerged = false;
        }

        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Highest enterable cell in this neighbour column: scan from the bot's feet level downward. Air
            // above the surface keeps the scan going; the first fluid cell is the entry. Hitting a solid cell
            // first means there is no fluid reachable straight down this column.
            for (int wf = feetY; wf >= feetY - MAX_SINK; wf--) {
                if (!ctx.built(nx, wf, nz)) break;          // unknown column — don't path into it
                long fd = ctx.descriptorAt(nx, wf, nz);
                if (fluid(ctx, fd)) {
                    // The head cell decides FIT and PRICE, not admissibility (§3.2). Fluid head → the bot is
                    // fully submerged and swims at the slower submerged rate; air head → the surface paddle;
                    // SOLID head → the upright pose does not fit and the column is capped for this move (a
                    // 1-tall submerged gap is SprintSwim's prone job), so stop scanning it.
                    if (!ctx.built(nx, wf + 1, nz)) break;
                    long hd = ctx.descriptorAt(nx, wf + 1, nz);
                    boolean headFluid = fluid(ctx, hd);
                    if (!headFluid && !ctx.passable(hd)) break;
                    // THE WALK-OUT CLEARANCE GATE (owner rule 2026-08-20): "we shouldn't emit a step-down
                    // into water until the target has 3 blocks of headroom" — headroom "as tall as the
                    // starting cell". A step-down entry (wf < feetY) does not dive off the lip: the executor
                    // walks out ABOVE the destination column at STANDING height exactly like a Descend and
                    // only then drops, so the walk-out head cell (nx, feetY+1, nz) must be non-solid
                    // (passable or fluid) for the body to clear the lip. The cells between it and the
                    // destination head are already proven passable by this scan on its way down. The old
                    // code tested only the DESTINATION feet and head — never the transit — and Swim has no
                    // failWhen: convicted on the 2026-08-20 run-5 wedge at (467,63,630)->(467,62,631), where
                    // a dirt overhang at (467,64,631) blocked the walk-out and the bot pressed into it for
                    // ~53k ticks (flagship-r5-async-838blocks.log). A SUBMERGED start is exempt: eyes
                    // underwater is a dive initiation (owner rule) — it swims down, it never walks the lip.
                    // Shape: the walk-out cell is SHARED by every deeper fluid cell in this column, so a
                    // solid one `break`s the column scan (deeper fluid is unreachable through the same
                    // blocked walk-out) rather than `continue`ing; an UNBUILT one is a conservative refusal
                    // of the column, mirroring the `!ctx.built(...) break` idiom above.
                    if (wf < feetY && !startSubmerged) {
                        if (!ctx.built(nx, feetY + 1, nz)) break;
                        long wd = ctx.descriptorAt(nx, feetY + 1, nz);
                        if (!fluid(ctx, wd) && !ctx.passable(wd)) break;
                    }
                    out.accept(nx, wf - 1, nz, cellCost(ctx, fd, headFluid ? SUBMERGED_COST : COST));
                    break;
                }
                if (!ctx.passable(fd)) break;               // hit solid before any fluid — no entry this way
                // else open air above the surface: keep scanning down.
            }
        }
    }

    /**
     * A cell the bot's body may occupy while swimming upright: genuinely clear, or fluid it can swim in.
     * The upright counterpart to {@link MovementContext#passable} — deliberately NOT {@code passable} alone,
     * which excludes fluid by construction and was the whole reason a dry bot could not enter a waterfall.
     */
    private static boolean bodyClear(MovementContext ctx, int x, int y, int z) {
        int p = ctx.packedAt(x, y, z);
        if (p == MovementContext.UNBUILT) return false;
        long d = ctx.descriptorOf(x, y, z, p);
        return ctx.passable(d) || fluid(ctx, d);
    }

    /** A cell the bot can swim in — either fluid, priced by which one it is. Bubble columns are excluded
     *  upstream by {@link MovementContext#water} (their own move owns them). Package-private since
     *  2026-08-15 so {@link ExitWater} shares this one definition rather than restating it. */
    static boolean fluid(MovementContext ctx, long d) {
        return ctx.water(d) || ctx.lava(d);
    }

    /**
     * Price one rung by the fluid the bot's destination feet sit in: lava charges the ratified hazard-media
     * adjustment ({@code 2.5×} slow plus immersion damage in the one damage currency), water charges the base
     * rate. This IS the "let the damage and slow factors carry the cost" rule of §3.3 — the rungs are unified,
     * their prices are not, which is exactly what lets A* sort lava out instead of us special-casing it.
     */
    private static float cellCost(MovementContext ctx, long fluidDesc, float base) {
        return ctx.lava(fluidDesc) ? ctx.lavaSwimCellCost(base) : base;
    }

    /**
     * Can a prone sprint-swim make real lateral PROGRESS from this cell — the dominance gate's predicate
     * (§5.3). Answers the two-step question the search would actually take: {@link StartSprintSwim} goes prone
     * (in place when the head is already submerged, or one cell down when treading a surface over deep water),
     * and from the feet height that leaves, {@link SprintSwim} needs a lateral water neighbour to continue into.
     * If no such neighbour exists the prone branch is a dead end and the slow upright paddle must stay on offer.
     *
     * <p><b>Known narrowness, deliberately kept (not a regression).</b> The test is any-direction, so a prone
     * continuation to the EAST suppresses the upright paddle to the WEST as well — including the one case
     * sprint-swim genuinely cannot replace, a lateral rung whose destination the down-scan found several cells
     * LOWER through air (a drop into a separate, unconnected pool). The gate the old code ran was strictly
     * broader (it fired on the initiation test alone and suppressed every rung, verticals included), so this is
     * a narrowing in every direction; refining it to per-direction dominance is a real improvement but a
     * behaviour change beyond what §5.3 ratified, so it waits for a ruling.
     */
    private static boolean lateralSprintDominates(MovementContext ctx, int x, int y, int z) {
        final int proneFeetY;
        if (ctx.built(x, y + 2, z) && ctx.water(x, y + 2, z)) {
            proneFeetY = y + 1;                 // already 2-deep: StartSprintSwim (1) goes prone in place
        } else if (ctx.built(x, y, z) && ctx.water(x, y, z)) {
            proneFeetY = y;                     // treading deep water: StartSprintSwim (2) dives one cell
        } else {
            return false;                       // nowhere to initiate — never suppress
        }
        for (int[] d : CARDINALS) {
            if (ctx.built(x + d[0], proneFeetY, z + d[1]) && ctx.water(x + d[0], proneFeetY, z + d[1])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz, Movement next) {
        // A Swim cruise waypoint is a MODE_STANDING (upright) node — symmetric with SprintSwim.reached: only
        // "reached" once the bot is NOT prone, so the cursor can't skip past the pose transition on the way
        // out of a dive. Either fluid counts, since every rung is now medium-agnostic.
        //
        // The medium test is a WORLD fact, not the ENTITY's state (owner ruling 2026-08-14, the (247,51,16)
        // waterfall-apron livelock). It used to ask `b.inWater() || b.inLava()`, and vanilla answers that
        // question with `wasTouchingWater` — which reads FALSE for a bot standing on the floor of a thin
        // apron, and reads it inconsistently: 706 `ground` against 8 `water` and 5 `air` on one STATIONARY
        // bot in 0.556 blocks of water, measured over the stall. By the AABB test it should have been
        // touching throughout (box bottom 51.001 against a surface at 51.556); that discrepancy is real,
        // unexplained, and tracked separately — it is not something this predicate should be built on.
        //
        // `fluidTopAt` is the same read the surface clamp in reachedSwim already trusts, and it answers the
        // question the waypoint actually cares about: does this CELL hold fluid. A dry cell still refuses,
        // so the clause keeps its meaning; it simply stops depending on an entity flag that disagrees with
        // the world it is derived from. Either fluid still counts — getFluidState is medium-agnostic.
        return !b.prone() && b.fluidTopAt(wx, wy, wz) > 0.0 && reachedSwim(b, wx, wy, wz)
                && Movement.teedUp(b, wx, wy, wz, next);
    }

    /**
     * Upright swim: track the planned cell horizontally and hold the planned depth with the
     * {@link SteerControl#holdDepth depth autopilot} (bias 0 — the tall standing pose rides at the planned feet
     * height). Not sprinting; the prone 1×1 pose is {@link SprintSwim}. This move owns its whole control set —
     * rising, sinking, and holding depth (s52; no follower water rule exists).
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.uprightSwimServo(b, path);
        SteerControl.holdDepth(b, path, 0.0);
    }

    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // A swim node's floor is the (non-standable) fluid cell below the feet, so feetYOf always returns
        // floorY+1 — fromFootY/toFootY equal fy+1/ty+1 here; the fluid frame is unaffected by partial floors.
        MovePlan plan = new MovePlan();
        plan.phase("paddle")
                .drive((b, v) -> {
                    b.setSprinting(false);      // upright: sprinting would re-enter the prone pose AND, per
                                                // getFluidFallingAdjustedMovement, cancel the gravity term the
                                                // vertical costs above are derived from
                    SteerControl.uprightSwimServo(b, v);
                    SteerControl.holdDepth(b, v, 0.0);
                })
                .done(b -> reachedSwim(b, tx, ty + 1, tz));
        return plan;
    }

    /**
     * Swim cursor-advance test (shared with {@link SprintSwim}): the bot's feet CELL is the waypoint cell.
     * Nothing more — no continuous-Y band, no clamps, and the {@code bias} parameter is vestigial (kept only
     * so the swim moves' call sites stay uniform; {@link SteerControl#SUBMERGE_BIAS} is now identity).
     *
     * <h2>Why this collapsed to a cell test (2026-08-15)</h2>
     * It used to be {@code |b.y() - target| < REACHED_Y} with {@code REACHED_Y = 0.6} around a nominal target
     * of {@code wy + 1 - bias}, plus two clamps that pulled that target back down — a CEILING clamp
     * ({@code min(target, (wy+2) - height)}) and a SURFACE clamp ({@code min(target, wy + fluidTopAt(...))}).
     * All of it existed to cope with a set-point that was wrong: {@code wy + 1.0} is the feet cell's CEILING,
     * so the window {@code (target-0.6, target+0.6)} straddled the cell boundary and the test could fire with
     * the feet a whole cell away from {@code wy}.
     *
     * <p>Two failures were caused by exactly that. The flagship {@code Diagonal} at {@code (154,-8,103)}: tuff
     * at {@code (154,-5,103)} clamped the target to {@code -6.8}, the window opened at {@code -7.4}, and the
     * bot was accepted at {@code botY = -7.289} — feet cell {@code -8}, one BELOW the waypoint's {@code -7} —
     * so the following {@code Diagonal}, framed for {@code -7}, failed its envelope on its first tick. And in
     * the SwimCourse {@code cross} entry, the surface clamp put the target at {@code 160.889} and the bot was
     * accepted at {@code 161.000} while still standing DRY on the lip, one cell ABOVE. Same defect, both
     * directions.
     *
     * <p>{@link SteerControl#SWIM_RIDE} now parks the bot at {@code wy + 0.2} with a {@code ±0.2} dead-band,
     * i.e. {@code [wy+0.0, wy+0.4]} — entirely inside the feet cell. So "is the ride at the right height" and
     * "is the bot in the right cell" became the same question, and the cell test is the honest spelling of it.
     * Both clamps computed heights inside that same cell and are simply gone; the ceiling clamp in particular
     * evaluated {@code (wy+2) - 1.8 == wy + 0.2}, which IS the new set-point, so its case is now the default
     * rather than a correction. {@code REACHED_Y}'s stacked-waypoint guard ("a vertical rung's waypoints are
     * 1 block apart, so the window must stay under 1") is satisfied by construction: one cell, one waypoint.
     *
     * <p>The residual the surface clamp left open — a nearly-full top cell where a bot resting on the floor
     * sits further than {@code REACHED_Y} from the ride height — is closed by the same collapse, since resting
     * on that floor puts the feet in the waypoint cell.
     */
    static boolean reachedSwim(BotSteering b, int wx, int wy, int wz, double bias) {
        return b.footX() == wx && b.footY() == wy && b.footZ() == wz;
    }

    /** Upright (bias-free) swim reach test — delegates to the bias-aware form with bias 0.0. */
    static boolean reachedSwim(BotSteering b, int wx, int wy, int wz) {
        return reachedSwim(b, wx, wy, wz, 0.0);
    }

    /** Permissive entry: this move's servo establishes its own stance, so it accepts any pose (see
     *  {@link Movement#entryReady}). Also exactly the pre-2026-08-15 behaviour, where no entry test existed. */
    @Override
    public boolean entryReady(BotSteering b, int wx, int wy, int wz) {
        return true;
    }
}
