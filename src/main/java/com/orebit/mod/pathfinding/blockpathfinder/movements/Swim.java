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
 *       requirement, and the two cases are priced apart ({@link #COST} vs {@link #SUBMERGED_COST}).</li>
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
     * committed plunge belongs to a future water-Fall variant); a deeper entry just isn't offered here.
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
     *  upstream by {@link MovementContext#water} (their own move owns them). */
    private static boolean fluid(MovementContext ctx, long d) {
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
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        // A Swim cruise waypoint is a MODE_STANDING (upright) node — symmetric with SprintSwim.reached: only
        // "reached" once the bot is NOT prone, so the cursor can't skip past the pose transition on the way
        // out of a dive. Either fluid counts, since every rung is now medium-agnostic.
        return !b.prone() && (b.inWater() || b.inLava()) && reachedSwim(b, wx, wy, wz);
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
     * Vertical reach tolerance for the swim cursor (blocks of continuous Y). Kept under 1 so a vertical rung's
     * stacked waypoints (1 block apart) can't both be "reached" at once — that was the {@code ±1} block
     * tolerance's "drop two cells at a time" leapfrog — while still wide enough to absorb the buoyancy bob.
     */
    static final double REACHED_Y = 0.6;

    /**
     * Swim cursor-advance test (shared with {@link SprintSwim}): horizontal cell match plus the bot's
     * <i>continuous</i> feet Y within {@link #REACHED_Y} of the depth the move actually holds, minus the
     * pose's submersion {@code bias}. A PRONE move rides {@link SteerControl#SUBMERGE_BIAS} below the planned
     * feet height to keep the short hitbox wet, so its reach window must centre on the depth it holds —
     * otherwise a submerged bot never lands inside the window and stalls. Upright moves pass bias 0 (the
     * 3-arg overload). Using continuous Y rather than a ±1 <i>block</i> tolerance keeps a vertical rung
     * advancing one waypoint at a time instead of leapfrogging the column.
     *
     * <h2>The target is clamped to what the geometry actually permits</h2>
     * The nominal target is {@code wy + 1 - bias}: a floating bot rises until it breaches, so it settles near
     * the TOP of its feet cell, a full block above the cell's base. <b>A ceiling can make that height
     * physically unreachable</b>, and then the test can never pass however long the bot swims.
     *
     * <p>Convicted 2026-08-07 on the flagship, at the top of the very waterfall this design set out to climb.
     * The bot arrived correctly at the last swim node — feet cell {@code (154,-7,103)}, the top water cell,
     * head in the air cell above, the {@code Diagonal} exit onto its deepslate ledge one step away — and then
     * held for 378 ticks. Tuff at {@code (154,-5,103)} caps a 1.8-tall body at {@code botY = -6.800}, while
     * the test demanded {@code botY > -6.6}: short by 0.2 blocks, permanently. The telemetry signature is
     * unmistakable and worth recognising again — {@code dm.y} pinned at exactly {@code -0.0050} every tick
     * (vanilla's in-fluid gravity with the jump impulse consumed by a vertical collision) while
     * {@code holdDepth} dutifully held jump forever.
     *
     * <p>So the ceiling is folded into the target rather than special-cased around: when the cell above the
     * body is solid, the highest attainable feet height is that cell's floor minus the pose's height. This is
     * <b>byte-identical wherever there is headroom</b> (the {@code min} does nothing), so the tuned open-water
     * behaviour — and the 7-to-9-tick-per-cell climb cadence the same flagship run demonstrated — is
     * untouched; it only ever LOWERS the bar, and only to a height the bot has already proven it can hold.
     * A partial ceiling (a TOP slab, whose real underside sits mid-cell) clamps conservatively low, which
     * stays inside {@link #REACHED_Y} rather than overshooting it.
     *
     * <p><b>Note the historical trap in the parameter.</b> This helper's contract used to describe {@code wy}
     * as the FLOOR cell ("feet on top of the floor cell"), and callers pass the WAYPOINT — which is the
     * <i>feet</i> cell ({@link Movement#atWaypoint} tests {@code footY() == wy}). The {@code + 1} is
     * therefore a ride-height convention, NOT a floor-to-feet conversion, and reading it as the latter is
     * what hid this bug: mid-column the bot rises straight through the threshold, so the target being a block
     * high is invisible everywhere except against a ceiling.
     */
    static boolean reachedSwim(BotSteering b, int wx, int wy, int wz, double bias) {
        double target = wy + 1.0 - bias;
        if (b.solidAt(wx, wy + 2, wz)) {                 // first cell above the body — the float ceiling
            double height = b.prone() ? PRONE_HEIGHT : STANDING_HEIGHT;
            target = Math.min(target, (wy + 2) - height);
        }
        return b.footX() == wx && b.footZ() == wz && Math.abs(b.y() - target) < REACHED_Y;
    }

    /** Vanilla hitbox heights, the clamp's only inputs beyond the ceiling cell (upright / {@code Pose.SWIMMING}). */
    private static final double STANDING_HEIGHT = 1.8;
    private static final double PRONE_HEIGHT = 0.6;

    /** Upright (bias-free) swim reach test — delegates to the bias-aware form with bias 0.0. */
    static boolean reachedSwim(BotSteering b, int wx, int wy, int wz) {
        return reachedSwim(b, wx, wy, wz, 0.0);
    }
}
