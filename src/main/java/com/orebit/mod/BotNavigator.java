package com.orebit.mod;

import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.PathStatus;
import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Replaceable;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's two-tier drive/follow concern (HPA-IMPLEMENTATION.md §9/§10), owned by {@link AllyBotEntity}
 * (see {@link BotMining} for the component pattern): the coarse region skeleton + sliding block window
 * ({@link PathPlan}), the waypoint follower ({@link #steerAlongPath}), the boundary-gated commit/replan
 * machinery ({@link #driveToward}), the region-tier online repair ({@link #repairStep}), and the
 * navigation-owned diagnostics (progress chat, window-swap/phase/stuck forensics).
 *
 * <p>When the bot has NO walkable plan (an async search still in flight, a consumed window, nav data not
 * built), it WAITS: the bot never moves without a plan. All movement inputs are owned by the planned
 * {@link Movement}s (steer/plan hooks); there is deliberately no motion-signature "stuck recovery" here —
 * pathologies are diagnosed (see {@link #dumpStuck}) and fixed at the source, per-move.
 */
final class BotNavigator {

    private final AllyBotEntity bot;

    // ---- Follow / path-steering tuning -------------------------------------------------------
    /** Stop moving once this close to the owner (blocks, horizontal). */
    static final double ARRIVE_DIST = 2.5;
    /**
     * Vertical arrival tolerance (blocks). Paired with {@link #ARRIVE_DIST} so "arrived" means close in
     * 3D — without it the bot treats being directly under a target as arrival (matches the block A*'s
     * ±2 vertical goal tolerance, so the follower agrees with the planner about reaching the goal cell).
     */
    static final double ARRIVE_Y = 2.5;
    /**
     * Terrain-change DEBOUNCE (ticks) for the block-level window re-search. The ratified model: replanning
     * on every block edit would melt the server, so edits are picked up at a bounded cadence instead — at
     * most one window re-search per this many ticks, and (s52) ONLY when the level's nav-grid
     * {@link com.orebit.mod.worldmodel.pathing.NavGridUpdater#editEpoch edit epoch} actually advanced since
     * this plan's last search: a quiet world never re-searches at all (the old form was an unconditional
     * countdown poll, re-searching a stationary bot every 2s forever). This is NOT a skeleton rebuild (the
     * region skeleton stays committed until the goal changes region or a hop is proven BLOCKED).
     * Known coarseness (documented at editEpoch): level-global, and the bot's own plan edits also advance
     * the epoch — those re-searches are redundant-but-correct (PathEdits already modelled them).
     */
    private static final int TERRAIN_RECHECK_TICKS = 40;
    /** Below this squared speed while grounded, the DIAGNOSTIC stuck counter runs (≈0.04 b/t). Purely
     *  observational — it feeds {@link #dumpStuck} only. All motion-signature recovery actuation (held
     *  jumps, escape-hatch re-searches) was removed in the s52 hack-removal pass: base behavior is being
     *  measured, and stalls get principled per-move fixes instead of after-the-fact course correction. */
    private static final double STUCK_SPEED_SQR = 0.0016;
    /** Consecutive stuck ticks before the diagnostic dumps the surrounding blocks (≈1s). */
    private static final int STUCK_DUMP_TICKS = 20;
    // (Water vertical control — deadband, submerge bias, the rise/sink rule — lives in SteerControl.holdDepth
    // now, called by each water-capable Movement's steer: the moves own their controls. s52.)

    // ---- chat-progress de-dup state (Debug.ENABLED): only post when one of these changes ---------
    private int lastChatStep = Integer.MIN_VALUE;
    private PathStatus lastChatStatus;
    private boolean lastChatPartial;
    private PathPlan.TargetKind lastChatKind;
    /** Last (waypoint|movement|medium) announced by {@code /bot debug verbose} — dedups it to one line per change. */
    private String lastVerbose;

    // ---- Debug.VERBOSE execution forensics (cold; only touched while verbose is on) --------------------
    // The stuck-bot discriminators: which of the known edit-execution seams is biting (see logPhaseDiagnostics).
    /** Result of the current step's {@code phaseRunner.run} (previously discarded) — {@code false} at a step
     *  change means the cursor advanced past a phase that never finished (the reached-before-done seam). */
    private boolean lastPhaseDone;
    /** Move name of the active phase plan — names the OLD step's move in the abandon line. */
    private String lastPlanMove = "";
    /** {@code phaseRunner.regressions()} at last look — a change is a fall-back/re-attempt. */
    private int lastRegressions;
    /** (step|need|cell) key + consecutive ticks of the current PhaseRunner hold — throttles the holding line. */
    private String lastHoldKey;
    private int holdTicks;
    /** Last announced non-following drive state (HOLD / straight-line) — one line per change. */
    private String lastDriveState;
    /** Re-announce a persistent phase hold every this-many held ticks (2s at 20 tps). */
    private static final int HOLD_LOG_TICKS = 40;

    /**
     * The two-tier driver (HPA-IMPLEMENTATION.md §9/§10): owns the coarse region skeleton and feeds the
     * follower one windowed {@link BlockPathPlan} at a time via {@link PathPlan#currentBlockPlan()}. Built
     * fresh per goal in {@link #replan}; ticked by {@link #driveToward} (which calls
     * {@link PathPlan#onBotMoved}). The existing {@link #steerAlongPath}/{@link #applyEdits} machinery is
     * unchanged — it walks whatever {@link #path} the driver currently exposes.
     */
    private PathPlan pathPlan;
    /**
     * Identity of the {@link BlockPathPlan} {@link #path} currently points at. When the driver advances its
     * sliding window it swaps in a NEW {@link BlockPathPlan} instance; we detect that by reference identity
     * and reset {@link #waypointIndex}/{@link #lastEditedIndex} so the follower restarts at the head of the
     * new window's path (HPA-IMPLEMENTATION.md §10).
     */
    private BlockPathPlan lastBlockPlanRef;

    private BlockPathPlan path;
    private int waypointIndex;
    private int blockRefreshTicks; // terrain-recheck debounce countdown (see TERRAIN_RECHECK_TICKS)
    private int lastRefreshEditEpoch; // NavGridUpdater.editEpoch at this plan's last window search
    // The current drive's planner goal tolerance (set per driveToward call) and the tolerance the active
    // plan was BUILT with — a mismatch on the same goal region forces a fresh plan (s52, reached-vs-done).
    private int goalTolXZ = BlockPathfinder.DEFAULT_GOAL_TOL_XZ;
    private int goalTolY = BlockPathfinder.DEFAULT_GOAL_TOL_Y;
    private int planGoalTolXZ = BlockPathfinder.DEFAULT_GOAL_TOL_XZ;
    private int planGoalTolY = BlockPathfinder.DEFAULT_GOAL_TOL_Y;
    private int stuckTicks;         // consecutive ticks grinding in place; drives the stuck diagnostic
    private int lastEditedIndex = -1; // last step whose break/place edits were applied (apply once per step)
    private boolean loggedHasPath;  // dedupe the path/no-path diagnostic so it logs only on change
    private boolean loggedPlanError; // log a two-tier replan exception only once (then degrade silently)
    /** COMPLETE-but-not-arrived ratchet (s52b): once the plan declared done short of the caller's
     *  arrival test, this goal is re-planned at EXACT (0/0) tolerance until arrival/clear/goal-move. */
    private boolean exactGoalEscalated;

    /** DIAGNOSTIC (swim harness): which driveToward outcome branch ran this tick — STEER (following the
     *  path), WAIT (no walkable plan), HOLD (gave up / window BLOCKED), COMPLETE (arrived). Label only,
     *  no control-flow effect. */
    private String driveState = "INIT";

    /** The drive-state label set on the last {@link #driveToward} tick (see {@link #driveState}). */
    String driveState() { return driveState; }

    // ---- closed-loop trajectory tracking (the follower steers along the planned LINE, not a point) ----
    /** Floor cell the current block plan/window started from — the first segment's start (waypoints are
     *  start-exclusive, so the segment before waypoint 0 begins here). Refreshed on replan / window swap. */
    private BlockPos planStartFloor;
    /**
     * The floor cell of the last <b>completed</b> waypoint — the bot's last SETTLED stand position, updated only
     * when a move's {@link Movement#reached} fires (see {@link #steerAlongPath}). The driver's region commit /
     * replan runs off THIS, not the live {@code bot.blockPosition()}: a move in progress passes transiently
     * through cells it hasn't finished into (a pillar's jump apex is momentarily "floor+1" with the footing not
     * yet placed), and committing/replanning on that phantom position rebuilt the plan mid-move — discarding the
     * in-flight pillar before it placed, which stranded the bot in a MineDown↔Pillar loop at region boundaries.
     * Anchoring the commit to completed waypoints means we only ever replan from a cell the bot actually reached
     * (with that move's edits already applied) — the synchronous form of the assumed-post-move position a future
     * background planner will require. (The old motion-signature recovery could re-anchor this to the live cell;
     * that escape hatch was removed in s52 — a move that never completes is now a per-move fix, not a re-anchor.)
     */
    private BlockPos settledFloor;
    /** Reusable {@link SteerView} re-pointed at the current segment each tick (no per-tick allocation). */
    private final SegmentCursor cursor = new SegmentCursor();

    // ---- Stage-2 phase-model execution (converted moves only; others keep the steer + one-shot-edit path) ----
    /** Runs the current step's {@link MovePlan} when its move provides one (Pillar today); reactive geometry. */
    private final PhaseRunner phaseRunner = new PhaseRunner();
    /** The waypoint index the active plan was built for; a change rebuilds the plan for the new step (-1 = none). */
    private int activePlanStep = -1;

    // ---- region-tier online repair (the "recover when stuck" half of the stuck arc) ------------------
    /**
     * The last {@link PathPlan#blockedGeneration() BLOCKED-result generation} a repair was attempted for —
     * exactly ONE {@link PathPlan#repairBlocked} per BLOCKED search result (each null-returning search is a
     * new fact; identical inputs deterministically fail identically, so re-attempting between results is
     * waste). Reset with each fresh {@link PathPlan}. (s52: replaced the REPAIR_COOLDOWN tick throttle.)
     */
    private int lastRepairedBlockedGen;
    /** Set once the region tier can find NO route avoiding the blacklist — the bot holds + tells the owner. */
    private boolean navGaveUp;

    /** Reused mutable cursor for the phase-hold occupant read (no per-check allocation). */
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    BotNavigator(AllyBotEntity bot) {
        this.bot = bot;
    }

    // ---- the surface the entity + sibling components drive ------------------------------------------

    /** Set once the region tier exhausted its options for the current goal — the callers HOLD (or, for
     *  GATHER, blacklist the target) instead of blind-walking. Cleared by a new goal region / level change. */
    boolean navGaveUp() {
        return navGaveUp;
    }

    /** Consume a give-up (GATHER blacklists the unreachable target and moves on to the next one). */
    void clearNavGaveUp() {
        this.navGaveUp = false;
    }

    /** The floor cell of the last completed waypoint — the bot's last SETTLED stand position (see
     *  {@link #settledFloor}); GATHER's opportunistic re-target challenge keys off changes to this. */
    BlockPos settledFloor() {
        return settledFloor;
    }

    /** Current waypoint cursor — for the entity's swim-pose transition diagnostic line. */
    int waypointIndex() {
        return waypointIndex;
    }

    /** Size of the current window's block plan, or -1 with no plan — for the same diagnostic line. */
    int pathSize() {
        return path != null ? path.size() : -1;
    }

    /** The current window's block plan (or null with no plan) — for the swim harness's once-per-plan dump of
     *  the full waypoint list + movement types (identity changes on each window-swap/replan). */
    com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan currentPlan() {
        return path;
    }

    // The executing step's segment cells (from = previous waypoint / plan start, to = current waypoint) —
    // diagnostic only, snapshot each driven tick. The parkour harness logs these to see the ACTUAL jump the
    // planner routed (from/to cells) vs the intended one, e.g. a greedy diagonal corner-cut off a turn.
    private int segFromX, segFromY, segFromZ, segToX, segToY, segToZ;
    int segFromX() { return segFromX; }
    int segFromY() { return segFromY; }
    int segFromZ() { return segFromZ; }
    int segToX() { return segToX; }
    int segToY() { return segToY; }
    int segToZ() { return segToZ; }

    /**
     * Drop the active two-tier driver and its exposed block plan (HPA-IMPLEMENTATION.md §10): a mode change
     * or a STAY hold invalidates the current goal, so the {@link PathPlan} built for it must not be ticked
     * again. The next {@link #driveToward} sees a null {@link #pathPlan} and rebuilds for the new goal.
     */
    void clearPlan() {
        if (pathPlan != null) pathPlan.cancelPending(); // stop caring about any in-flight background search
        this.pathPlan = null;
        this.path = null;
        this.lastBlockPlanRef = null;
        this.activePlanStep = -1;
        this.phaseRunner.clear();
        this.exactGoalEscalated = false; // a cleared goal releases the exact-tolerance ratchet
    }

    /** Re-anchor after a COMPLETED dimension change: the active plan, its settled/start anchors, and any
     *  give-up state all belong to the OLD level (see {@code AllyBotEntity.onLevelChanged}). */
    void onLevelChanged() {
        clearPlan();
        this.settledFloor = null;
        this.planStartFloor = null;
        this.navGaveUp = false;
    }

    /** Drive toward {@code (tx,ty,tz)} with the default follow/come arrival tolerance ({@link #ARRIVE_DIST}/
     *  {@link #ARRIVE_Y}). Mining passes a tighter tolerance so it goes all the way to the target. */
    boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor) {
        return driveToward(tx, ty, tz, goalFloor, ARRIVE_DIST, ARRIVE_Y);
    }

    /** As below with the {@link BlockPathfinder#DEFAULT_GOAL_TOL_XZ default} ±1/±2 planner goal tolerance
     *  ("get near the cell" — right for follow/come/mining-reach drives). */
    boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor,
                        double arriveDist, double arriveY) {
        return driveToward(tx, ty, tz, goalFloor, arriveDist, arriveY,
                BlockPathfinder.DEFAULT_GOAL_TOL_XZ, BlockPathfinder.DEFAULT_GOAL_TOL_Y);
    }

    /**
     * Path toward {@code (tx,ty,tz)} (goal floor cell {@code goalFloor}), steering along the plan. Returns
     * {@code true} once within {@code arriveDist} horizontally <i>and</i> {@code arriveY} vertically — the
     * caller decides what arrival means for its mode (loose for FOLLOW/COME; tight for MINE, which must
     * reach a line-of-sight cell before it can break). {@code goalTolXZ}/{@code goalTolY} is the PLANNER's
     * goal tolerance — the caller's definition of "the plan is done" (s52): the default ±1/±2 plans TO NEAR
     * the cell; 0/0 plans onto the exact cell (drop collection — an adjacent stop leaves the item outside
     * the pickup box). A tolerance change on the same goal region forces a fresh plan (the old plan's
     * completion semantics no longer match the caller's).
     */
    boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor,
                        double arriveDist, double arriveY, int goalTolXZ, int goalTolY) {
        double dx = tx - bot.getX();
        double dy = ty - bot.getY();
        double dz = tz - bot.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        // Arrived only when close in 3D. Horizontal proximity alone would let the bot stop directly
        // BELOW the target (it walks under a sky platform / the top of a staircase and quits) — it must
        // also match the target's height, which is what makes it actually climb to reach you.
        if (distXZ <= arriveDist && Math.abs(dy) <= arriveY) {
            driveState = "COMPLETE";
            bot.setForward(0.0f);
            clearPlan(); // also resets the exact-goal escalation — this goal is DONE
            bot.lookAtPlayer(bot.owner());
            return true;
        }

        // COMPLETE-BUT-NOT-ARRIVED ESCALATION (s52b — the frozen-WAIT bug): the planner's CELL tolerance
        // (±goalTol) and the caller's CONTINUOUS arrival test (arriveDist/arriveY) are different rulers,
        // and where they disagree — a portal frame base ~2 cells above the bot satisfies the plan's ±2
        // vertical tolerance while failing the 2.5 continuous check — the plan went COMPLETE, onBotMoved
        // early-returns forever, and the bot froze in WAIT one step short. The caller's test is the
        // definition of done, so a COMPLETE plan that hasn't arrived escalates THIS goal to EXACT
        // tolerance (0/0): the tolerance-change trigger below rebuilds the plan, which now ends only ON
        // the goal cell (standing there always satisfies any caller's arrival radius). The ratchet resets
        // on arrival/clearPlan or when the goal moves to a new region. If even the exact plan can't reach
        // (goal cell genuinely unoccupiable), the search goes BLOCKED → repair → honest give-up.
        if (pathPlan == null || !pathPlan.sameGoalRegion(goalFloor)) {
            exactGoalEscalated = false; // a fresh/relocated goal starts back at the caller's tolerance
        } else if (pathPlan.isComplete() && (goalTolXZ > 0 || goalTolY > 0)) {
            exactGoalEscalated = true;
        }
        if (exactGoalEscalated) {
            goalTolXZ = 0;
            goalTolY = 0;
        }
        this.goalTolXZ = goalTolXZ;
        this.goalTolY = goalTolY;

        // COMMIT TO THE SKELETON. The region skeleton is a valid S1→…→Sn route to the goal; we follow it by
        // SLIDING THE WINDOW and replanning only at the BLOCK level as the bot crosses region boundaries.
        // We REBUILD the skeleton (a region replan) only when there is genuinely a new region problem to
        // solve: there's no plan yet, the goal entered a NEW region (a step within the same region keeps the
        // skeleton — the final window tracks the live goal cell), or the committed skeleton was proven invalid
        // (BLOCKED — the block tier couldn't realize a window; the online repair then blacklists the dead hop
        // and reroutes). Recomputing the skeleton as the bot MOVES (the old per-boundary / 40-tick full
        // replan) let near-equal-cost region routes flip-flop, so the bot oscillated mid-route ("start one
        // route, change its mind, take another, go back"). [Coarse S5 note: a >LEVEL0 skeleton may need region
        // refinement of its FAR, unresolved tail as the bot approaches — never the committed near end.]
        boolean newRegionGoal = pathPlan == null || !pathPlan.sameGoalRegion(goalFloor)
                || goalTolXZ != planGoalTolXZ || goalTolY != planGoalTolY;
        // A BLOCKED status does NOT force a full rebuild: the cascade (HPA-CASCADE.md §6) repairs a blocked hop
        // in place — escalating up its level stack in repairStep — without discarding the whole nested plan. A
        // full rebuild fires only on no-plan or a new goal region.
        if (pathPlan == null || newRegionGoal) {
            if (newRegionGoal) {
                // New destination region → the learned dead-ends no longer apply; start the repair fresh.
                navGaveUp = false;
            }
            replan(goalFloor);
            blockRefreshTicks = TERRAIN_RECHECK_TICKS;
        } else {
            // FORWARD-LOOKING / boundary-gated replan (the synchronous form of the background-planner model):
            // only commit / refresh / swap the block plan when the bot is physically SETTLED at its last
            // completed waypoint (blockPosition == settledFloor) — NEVER mid-move. A lagging replan from a cell
            // the bot has already moved past is wrong for any move you can't undo: a horizontal Traverse can't
            // "fall sideways" back to the block it left. Gating on the boundary means we only (re)generate and
            // start following a plan from a cell whose realized position we are actually AT, with that move's
            // edits already applied — so the plan the follower switches to always matches where the bot really
            // is (realized == the plan source). The eventual background planner generates the NEXT segment from
            // the PREDICTED post-move cell while still en route and switches at this same boundary; that needs
            // modelling the in-flight PathEdits, which lands with the background-thread work. The timer counts
            // every tick; only the ACTION waits for a boundary.
            if (blockRefreshTicks > 0) blockRefreshTicks--;
            // PLAN CONSUMPTION IS A SETTLE EVENT (s52 — first-class, not a recovery): every waypoint of
            // the current plan has been walked, so by definition NO move is in flight — the bot is settled
            // wherever it stands, in any stable medium (grounded, or buoyant in water — the one consumed
            // state that persists ungrounded). The block tier's ±1/±2 goal tolerance legitimately ends
            // window plans short of the target cell, so consuming a plan away from the old settle anchor
            // is a NORMAL boundary: re-anchor to the live floor so the boundary machinery below
            // (onBotMoved / refreshWindow / async drain) runs this tick. Mid-fall is excluded — a falling
            // bot is still moving and lands (grounded) within ticks, and planning from an airborne floor
            // cell would anchor the next search in the air.
            BlockPos currentFloor = floorOf(bot.blockPosition()); // topY-aware, computed once per drain tick
            if (path != null && waypointIndex >= path.size() && (bot.grounded() || bot.isInWater())) {
                this.settledFloor = currentFloor;
            }
            if (settledFloor != null && currentFloor.equals(settledFloor)) {
                pathPlan.onBotMoved(settledFloor, bot.currentStartMode());
                boolean consumed = path != null && waypointIndex >= path.size() && !pathPlan.isComplete();
                if (consumed || blockRefreshTicks <= 0) {
                    // Terrain-recheck debounce (s52): the periodic re-search fires ONLY when the level's
                    // nav grid actually changed since this plan's last search — an unchanged epoch means
                    // the re-search would be byte-identical, so just re-arm. A CONSUMED plan always
                    // refreshes (that's forward progress, not a terrain poll).
                    final int epoch = NavGridUpdater.editEpoch((ServerLevel) Worlds.of(bot));
                    if (consumed || epoch != lastRefreshEditEpoch) {
                        pathPlan.refreshWindow();
                        lastRefreshEditEpoch = epoch;
                    }
                    blockRefreshTicks = TERRAIN_RECHECK_TICKS;
                }
                BlockPathPlan now = pathPlan.currentBlockPlan();
                if (now != lastBlockPlanRef) {
                    this.path = now;
                    this.lastBlockPlanRef = now;
                    this.waypointIndex = 0;
                    this.lastEditedIndex = -1;
                    this.activePlanStep = -1; // rebuild the phase plan for the new window's first step
                    this.planStartFloor = settledFloor; // follower anchor == the search source (both settledFloor)
                    if (Debug.ENABLED) logWindowSwap(goalFloor); // capture boundary-wiggle: alternating targets/hops
                }
                // Eager pre-plan (DESIGN-background-pathfinding.md §7, async only): once THIS window's plan
                // is more than half walked, precompute the next boundary's search from the plan's predicted
                // end cell, seeded with the edits we haven't applied yet (the splice baseline) — so arriving
                // at the window end adopts a ready plan with no pause. wantsPreplan() is tested FIRST so the
                // argument construction (a BlockPos + the EditSnapshot fold) is never paid when it can't
                // submit — in particular never in sync mode and never twice per window target.
                if (path != null && !path.isEmpty() && waypointIndex > path.size() / 2
                        && waypointIndex < path.size() && pathPlan.wantsPreplan()) {
                    pathPlan.preplan(floorOf(path.waypoint(path.size() - 1)),
                            EditSnapshot.fromRemainingSteps(path, lastEditedIndex + 1),
                            bot.currentStartMode());
                }
            }
        }
        // Planless async adoption: a bot with NO walkable plan can't wait for the settled boundary the
        // normal drain rides — it may never settle (treading water, mid-fall), and there is nothing to
        // un-adopt. Poll at tick rate and, on adoption, run the same swap/anchor mechanics the boundary
        // block runs — anchored at the bot's LIVE floor, exactly how replan() seeds a fresh plan.
        // (A consumed plan needs no special case here — consumption is a settle event above, so it
        // drains through the boundary-gated onBotMoved the tick it settles. s52.)
        // Double adoption with the boundary block is impossible: both compare against lastBlockPlanRef,
        // so whichever runs first swaps and the other no-ops on the same reference.
        if (pathPlan != null && path == null) {
            BlockPos liveFloor = floorOf(bot.blockPosition());
            pathPlan.pollWhenPlanless(liveFloor);
            BlockPathPlan adopted = pathPlan.currentBlockPlan();
            if (adopted != lastBlockPlanRef) {
                this.path = adopted;
                this.lastBlockPlanRef = adopted;
                this.waypointIndex = 0;
                this.lastEditedIndex = -1;
                this.activePlanStep = -1;
                this.planStartFloor = liveFloor;
                this.settledFloor = liveFloor;
                this.stuckTicks = 0; // fresh plan → fresh diagnostic window
            }
        }

        // Region repair, every tick (cheap status check): a BLOCKED window — wherever it surfaced — gets its
        // dead skeleton hop blacklisted now, so the NEXT tick's `skeletonInvalid` reroute already avoids it
        // (one useful region replan, not a wasted same-skeleton rebuild first). Give-up lives here too.
        repairStep();

        if (path != null && waypointIndex < path.size()) {
            driveState = "STEER";
            lastDriveState = null; // following a path again → re-announce the next non-following state
            steerAlongPath();
        } else if (navGaveUp || (pathPlan != null && pathPlan.status() == PathStatus.BLOCKED)) {
            driveState = "HOLD";
            // HOLD when we either gave up OR a committed-skeleton window is momentarily BLOCKED (the region
            // repair reroutes next tick). Do NOT straight-line here: that ignores the planner and could walk
            // the bot off the very ledge the guard refused (the irreversible yeet). Straight-line stays only
            // for the genuinely off-grid case below (no plan / no built ground under the owner).
            if (Debug.VERBOSE) driveStateLog(navGaveUp ? "HOLD (nav gave up)" : "HOLD (window BLOCKED)");
            bot.setForward(0.0f);
        } else {
            driveState = "WAIT";
            // No walkable plan → WAIT. In async mode this branch is every tick a first/boundary search
            // is still in flight; the old straight-line fallback walked (and hopped) the bot toward the
            // raw goal during exactly those ticks — off ledges and floating platforms the planner would
            // have refused, invalidating the very plan being computed. Standing still until a plan (or
            // a give-up → HOLD above) arrives is the only honest behavior. (s52: steerStraight deleted.)
            if (Debug.VERBOSE) {
                // Geometry-rich WAIT line (s52b): the deduped bare label hid everything that mattered
                // in the frozen-portal report — include where the bot is, where the goal is, how far,
                // and what the plan thinks, so a stuck WAIT self-diagnoses from one line.
                driveStateLog("WAIT (" + (path == null ? "no block plan" : "plan consumed, not arrived")
                        + ") bot=" + AllyBotEntity.compact(bot.blockPosition()) + " goal=" + AllyBotEntity.compact(goalFloor)
                        + String.format(" dxz=%.1f dy=%.1f", distXZ, dy)
                        + " status=" + (pathPlan == null ? "null" : pathPlan.status())
                        + (exactGoalEscalated ? " [EXACT-ESCALATED]" : ""));
            }
            bot.setForward(0.0f);
        }

        if (Debug.ENABLED && path != null) {
            PathDebugRenderer.render((ServerLevel) Worlds.of(bot), path, waypointIndex,
                    bot.getX(), bot.getY(), bot.getZ());
        }
        if (Debug.ENABLED && pathPlan != null) {
            // Macro overlay: region skeleton + portal cells, to SEE the HPA plan vs the local block path
            // (and catch buried portal targets — the §6 bug — as blue particles inside rock).
            PathDebugRenderer.renderSkeleton((ServerLevel) Worlds.of(bot), pathPlan);
        }
        announceProgress();
        return false;
    }

    /**
     * Post the bot's high-level progress to the owner's chat (Debug.ENABLED) — one message per state change, not
     * per tick: which skeleton step it's heading to, whether it's on a best-effort PARTIAL, blocked, or
     * arrived. Lets you follow a long route in freecam without tailing the console. Never throws onto the tick.
     */
    private void announceProgress() {
        if (!Debug.ENABLED || pathPlan == null || bot.owner() == null) {
            return;
        }
        final PathStatus status = pathPlan.status();
        final int step = pathPlan.windowTargetStepIndex();
        final boolean partial = pathPlan.isPartialPlan();
        final PathPlan.TargetKind kind = pathPlan.windowTargetKind();
        if (step == lastChatStep && status == lastChatStatus && partial == lastChatPartial
                && kind == lastChatKind) {
            return; // nothing changed
        }
        lastChatStep = step;
        lastChatStatus = status;
        lastChatPartial = partial;
        lastChatKind = kind;

        final RegionPathPlan sk = pathPlan.skeletonPlan();
        final String where = (sk == null || sk.isEmpty() || step < 0 || step >= sk.size())
                ? "?"
                : "S" + step + "/" + (sk.size() - 1) + " (" + sk.rx(step) + "," + sk.ry(step) + "," + sk.rz(step) + ")";
        // Why this target was chosen (only the non-default cases are worth surfacing) — see PathPlan.TargetKind.
        final String how;
        switch (kind) {
            case EXTENDED: how = " [extended down — falling to landing]"; break;
            case SNAPPED:  how = " [target adjusted → standable]"; break;
            case CENTER:   how = " [no portal — aiming region center]"; break;
            case DIG:      how = " [digging through to buried crossing]"; break;
            default:       how = ""; break; // GOAL / PORTAL — the normal cases, no annotation
        }
        final String msg;
        switch (status) {
            case COMPLETE: msg = "arrived"; break;
            case BLOCKED:  msg = "blocked — no path to " + where; break;
            case FAILED:   msg = "failed — no route"; break;
            default:       msg = (partial ? "partial path toward " : "moving to ") + where + how; break;
        }
        bot.chat("[bot] " + msg);
    }

    /**
     * Diagnostic ({@code /bot debug}) for the region-boundary WIGGLE: one server-log line each time the
     * committed skeleton's block WINDOW is swapped (bot cell → new window). It prints the bot cell, the goal,
     * the window target and <b>why</b> it was chosen ({@link PathPlan.TargetKind} — {@code SNAPPED} = the goal
     * was unstandable and got adjusted to a nearby cell, so the adjusted cell can differ each search → a prime
     * oscillation source; {@code CENTER} = aiming a region centre), and the skeleton hop being followed. When
     * the bot ping-pongs across a boundary this logs the TWO alternating (target, hop) pairs back to back, so
     * the flip — region-route tie-break (hop alternates) vs goal-snap instability (target alternates, same
     * hop) — is explicit. Never throws onto the tick.
     */
    private void logWindowSwap(BlockPos goalFloor) {
        try {
            if (pathPlan == null) return;
            final BlockPos wt = pathPlan.currentWindowTarget();
            final int step = pathPlan.windowTargetStepIndex();
            final RegionPathPlan sk = pathPlan.skeletonPlan();
            final String hop = (sk == null || step < 0 || step >= sk.size())
                    ? "?"
                    : "S" + step + "(" + sk.rx(step) + "," + sk.ry(step) + "," + sk.rz(step) + ")";
            OrebitCommon.LOGGER.info("[Orebit] window-swap bot={} goal={} target={} kind={} hop={}",
                    AllyBotEntity.compact(bot.blockPosition()), AllyBotEntity.compact(goalFloor),
                    wt == null ? "?" : AllyBotEntity.compact(wt), pathPlan.windowTargetKind(), hop);
        } catch (Throwable ignored) {
            // diagnostics must never crash the tick
        }
    }

    /**
     * Plan a fresh <b>two-tier</b> path from the bot's floor cell to {@code goalFloor}
     * (HPA-IMPLEMENTATION.md §10). Builds a new {@link PathPlan} — which plans the coarse region skeleton
     * once ({@link com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder}) and computes the first
     * window's block path — and exposes that window's block plan through {@link #path} so the existing
     * follower keeps working unchanged. The driver then advances the sliding window per tick via
     * {@link PathPlan#onBotMoved} (see {@link #driveToward}); this whole-plan rebuild only fires on a new
     * goal / when the driver is fully exhausted.
     *
     * <p>The nav grid the block tier reads is kept live by the {@code LevelChunk.setBlockState} mixin
     * (BlockChangeEvents → NavGridUpdater.patchCell), so each window's block search sees current terrain —
     * including the bot's own break/place edits — without a per-replan rebuild. A chunk not yet built by
     * the on-load pipeline reads as unbuilt and is skipped, the same as any unloaded area.
     */
    private void replan(BlockPos goalFloor) {
        ServerLevel level = (ServerLevel) Worlds.of(bot);
        BlockPos startFloor = floorOf(bot.blockPosition());

        // The two-tier driver is a large, freshly-built subsystem (region A* + leaf mini-pathfinds). A bug
        // in it must NOT crash the server tick — degrade to "no plan" (which falls back to the visible
        // straight-line steer below), log once, and keep the game playable. Remove the guard once the region
        // tier is hardened.
        try {
            if (pathPlan != null) pathPlan.cancelPending(); // the old plan's in-flight search is superseded
            // Async pathing (pathing.async): hand the plan the planner pool so its window searches run off
            // the tick thread. Gated on the LIVE config, not just instance() — the pool is a JVM-lifetime
            // static, so on an integrated server a re-opened world with pathing.async=false would otherwise
            // silently keep using the previous world's pool (review finding).
            PlanExecutor executor = ConfigLoader.config().asyncPathing() ? PlanExecutor.instance() : null;
            this.pathPlan = new PathPlan(level, RegionGrid.of(level), startFloor, goalFloor, bot.caps(),
                    bot.inventoryFeasibility(), bot.currentStartMode(), null, executor,
                    goalTolXZ, goalTolY);
            this.path = pathPlan.currentBlockPlan();
        } catch (Throwable t) {
            if (!loggedPlanError) {
                loggedPlanError = true;
                OrebitCommon.LOGGER.error("[Orebit] two-tier replan threw — falling back to straight-line "
                        + "(this log is shown once)", t);
            }
            this.pathPlan = null;
            this.path = null;
        }
        this.lastBlockPlanRef = this.path;
        this.waypointIndex = 0;
        this.lastEditedIndex = -1;
        this.activePlanStep = -1; // rebuild any phase plan for the new plan's first step
        this.planStartFloor = startFloor; // first segment begins at the bot's current floor cell
        this.settledFloor = startFloor;   // the commit/replan anchor starts at the fresh plan's start cell
        this.stuckTicks = 0; // fresh plan → fresh diagnostic window (the counter is observation-only)
        this.lastRepairedBlockedGen = 0; // fresh PathPlan → its BLOCKED-result generation restarts at 0
        this.lastRefreshEditEpoch = NavGridUpdater.editEpoch(level); // this plan's search saw the world as-of now
        this.startDeadReported = false; // fresh goal → fresh start-dead episode
        this.planGoalTolXZ = goalTolXZ; // the tolerance this plan was built with (a change forces a fresh plan)
        this.planGoalTolY = goalTolY;

        boolean hasPath = path != null && path.size() > 0;
        if (Debug.ENABLED) {
            // Verbose per-plan trace while debugging: the full waypoint list shows exactly which cells
            // A* produced (e.g. whether a stacked staircase step is present or skipped).
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] plan: {} wp cost={} start={} goal={} path={}",
                        path.size(), path.cost(), AllyBotEntity.compact(startFloor),
                        AllyBotEntity.compact(goalFloor), waypointsString());
            } else {
                // FAIL-visible diagnostic (HPA-IMPLEMENTATION.md §10: pathological failures stay visible).
                // The two-tier driver exposes no block plan — report whether the start/goal cells even have
                // built nav data, the most common cause (cells outside the loaded/built radius). A throwaway
                // NavGridView is built only on this rare no-path branch, purely for the built() probes.
                NavGridView grid = new NavGridView(level);
                OrebitCommon.LOGGER.info("[Orebit] plan: NONE start={}(built={}) goal={}(built={})",
                        startFloor, grid.built(startFloor.getX(), startFloor.getY(), startFloor.getZ()),
                        goalFloor, grid.built(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ()));
            }
        } else if (hasPath != loggedHasPath) {
            loggedHasPath = hasPath;
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] bot path: {} waypoints (cost {})", path.size(), path.cost());
            } else {
                NavGridView grid = new NavGridView(level);
                OrebitCommon.LOGGER.info("[Orebit] bot path: none (startBuilt={}, goalBuilt={})",
                        grid.built(startFloor.getX(), startFloor.getY(), startFloor.getZ()),
                        grid.built(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ()));
            }
        }
    }

    /**
     * The region-tier online repair, run every tick after the driver updates (the "recover when stuck" half of
     * the stuck arc). The irreversibility guard already stops the bot yeeting off a one-way ledge; this is what
     * gets it UNSTUCK — whenever the driver reports {@link PathStatus#BLOCKED}, the block tier just proved it
     * can't realize the committed skeleton's current hop for this bot's caps (e.g. the only way to the next
     * region is a drop a no-place bot can't reverse). We blacklist that region→region crossing ({@link
     * PathPlan#blockedHop}) so the next {@code skeletonInvalid} reroute in {@link #driveToward} routes around
     * it — the large walk-around that only the region tier can find cheaply (a block-tier detour that long
     * would flood the node cap, which is why this lives at the region level). The trigger is IMMEDIATE: with
     * server render distance ≫ the 3-region window, chunks load long before the path reaches them, so a BLOCKED
     * is a real dead-end, never a transient unbuilt cell.
     *
     * <p>When no onward hop exists to blame ({@link PathStatus#FAILED} = the region A* found no route at all,
     * or the bot is in the goal region but can't reach the goal cell) the bot has exhausted its options: it
     * sets {@link #navGaveUp} (→ HOLD, no blind straight-line) and tells the owner once. A new goal clears it.
     */
    private void repairStep() {
        if (pathPlan == null || navGaveUp) return;
        final PathStatus status = pathPlan.status();
        // Cascade repair (HPA-CASCADE.md §6): a BLOCKED hop is blacklisted + escalated up the level stack IN
        // PLACE by the plan itself (which owns its per-level blacklists). It re-derives the L0 skeleton on
        // success; only a hard exhaustion (no route at any level) gives up.
        if (status == PathStatus.FAILED) {
            giveUp();
            return;
        }
        if (status != PathStatus.BLOCKED) {
            return; // route is fine
        }
        // START-DEAD (s52b): the search died AT the start cell (≤1 expansion — the bot's own feet/head
        // cells emit no candidates). That proves nothing about any skeleton hop, so hop repair must NOT
        // run (blacklisting on it was an unbounded repair→resubmit→fail churn at planner speed — the
        // log-flood). NO automatic escape is attempted (owner ruling, s52b lava incident: assumption-
        // driven rescue subroutines are the bandaid class this codebase removes) — the bot reports
        // exactly WHAT its cells contain, once, and holds; the fix for each entombment class is designed
        // from that data (e.g. lava/magma passability = DESIGN-hazard-media.md, owner review).
        if (pathPlan.startDead()) {
            reportStartDead();
            return;
        }
        // EVENT-DRIVEN repair (s52 — replaced the REPAIR_COOLDOWN tick throttle): exactly ONE repair per
        // BLOCKED search RESULT. A null-returning search is a new fact about the world; the repair consumes
        // it by blacklisting a hop and re-searching. Between results there is nothing new to react to —
        // identical inputs deterministically fail identically — so a timer retry was pure waste, and each
        // repair strictly consumes a blacklist option (escalating to FAILED), so the sequence terminates.
        final int gen = pathPlan.blockedGeneration();
        if (gen == lastRepairedBlockedGen) {
            return; // this BLOCKED result already got its one repair; await the next search result
        }
        lastRepairedBlockedGen = gen;
        bot.chat("[bot] path blocked — invalidating a region crossing and rerouting.");
        if (!pathPlan.repairBlocked()) {
            giveUp();
        }
    }

    /**
     * START-DEAD diagnostic (s52b): the planner just proved the bot's own start cell emits no candidate
     * moves. Report the LIVE contents of the feet/head cells ONCE (chat + log) so the owner sees exactly
     * what the bot is inside — lava, a fallen gravel column, an unbuilt grid — and hold. Deliberately NO
     * automatic escape: every entombment class gets a designed fix from this data (lava/magma media =
     * DESIGN-hazard-media.md), not an assumption-driven rescue subroutine.
     */
    private void reportStartDead() {
        if (startDeadReported) {
            return;
        }
        startDeadReported = true;
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        final BlockPos feet = bot.blockPosition();
        final BlockPos head = feet.above();
        final BlockState feetState = level.getBlockState(feet);
        final BlockState headState = level.getBlockState(head);
        // EVIDENCE-CONDITIONAL (s52b false-alarm fix): a start-dead search has TWO distinct causes and
        // the live world tells them apart. (a) The bot's cells are genuinely obstructed (collision or
        // fluid occupant) → tell the owner what it is stuck in. (b) The live cells are CLEAR — the
        // search failed on GRID state (nav data not yet built at the start, the seconds-after-world-open
        // case; or a stale cell) → that is NOT "I can't move", so no chat: one log line with the
        // evidence, and the search retries as chunk builds advance the edit epoch.
        final boolean feetObstructed = !feetState.isAir()
                && (!feetState.getCollisionShape(level, feet).isEmpty() || !feetState.getFluidState().isEmpty());
        final boolean headObstructed = !headState.isAir()
                && (!headState.getCollisionShape(level, head).isEmpty() || !headState.getFluidState().isEmpty());
        if (feetObstructed || headObstructed) {
            bot.chat("I can't move from here — feet: " + feetState.getBlock().getName().getString()
                    + ", head: " + headState.getBlock().getName().getString()
                    + " (no movement can start from this cell).");
            OrebitCommon.LOGGER.info("[Orebit] START-DEAD at {} feet={} head={} — no candidates from start; "
                    + "holding (no auto-escape).", AllyBotEntity.compact(feet), feetState, headState);
        } else {
            OrebitCommon.LOGGER.info("[Orebit] START-DEAD at {} but live cells are CLEAR (feet={} head={}) — "
                    + "nav grid unbuilt/stale at the start; no chat, retrying as the grid builds.",
                    AllyBotEntity.compact(feet), feetState, headState);
        }
    }

    /** One report per start-dead episode; reset on replan (a fresh goal is a fresh episode). */
    private boolean startDeadReported;

    private void giveUp() {
        navGaveUp = true;
        bot.chat("I can't find a way to reach you.");
    }

    /**
     * Steer along the current path SEGMENT, advancing the waypoint cursor by block occupancy and delegating
     * each step's inputs to its {@link com.orebit.mod.pathfinding.blockpathfinder.Movement} (the cold {@code
     * reached}/{@code editsReadyNow}/{@code steer} hooks — MOVEMENT-DESIGN.md §1). The follower owns only the
     * generic plumbing: advance the cursor, apply each step's folded edits once, hand the move a {@link
     * SteerView} over the current segment. Per-move behaviour
     * (the swim velocity track, the Fall/Pillar airborne column-homing, the Ascend/Pillar jump, the swim
     * cursor tolerance, Pillar's airborne edit timing) lives on the move classes via {@link
     * com.orebit.mod.pathfinding.blockpathfinder.BotSteering} +
     * {@link com.orebit.mod.pathfinding.blockpathfinder.SteerControl}, so adding a capability never touches
     * this method.
     */
    private void steerAlongPath() {
        // Advance to the furthest waypoint the bot has reached. Waypoints ARE blocks and so are the bot's feet
        // ({@code blockPosition()}), so the default test is block-exact (Movement.reached); a swim move
        // loosens it vertically for a buoyancy-bobbing bot. Because the match includes Y, the feet block only
        // equals the next step once the bot has actually climbed onto it (a stacked staircase can't be
        // skipped); scanning from the end absorbs any overshoot.
        for (int j = path.size() - 1; j >= waypointIndex; j--) {
            BlockPos w = path.waypoint(j);
            if (path.movement(j).reached(bot, w.getX(), w.getY(), w.getZ())) {
                if (Debug.VERBOSE && j > waypointIndex) {
                    bot.vlog("advance SKIPPED " + (j - waypointIndex) + " step(s): " + waypointIndex + "→" + (j + 1)
                            + " — skipped steps' edits/phases never ran");
                }
                waypointIndex = j + 1;
                // A move just COMPLETED — this is the settled stand cell the driver commits/replans off (its
                // edits are now applied). Advancing the anchor only here is what keeps a mid-move transient from
                // triggering a boundary replan (see settledFloor). w is the stand position; floorOf() = its floor
                // (topY-aware: on a bottom-partial the stand cell IS the floor, so floorOf != w.below()).
                this.settledFloor = floorOf(w);
                break;
            }
        }
        if (waypointIndex >= path.size()) {
            bot.setForward(0.0f);
            return;
        }

        Movement movement = path.movement(waypointIndex);
        BlockPos wp = path.waypoint(waypointIndex);

        // Build (once per step) this move's phase-model plan, if it has one. A change of waypoint (a new step, or
        // a window swap that reset the cursor) rebuilds it and resets the runner. The plan is written in the
        // search-native FLOOR cells; waypoints are floor.above() (stand positions), so the floor is one below.
        if (waypointIndex != activePlanStep) {
            if (Debug.VERBOSE && phaseRunner.active() && !lastPhaseDone && activePlanStep >= 0) {
                // The reached-before-done seam: the cursor moved on while the old step's phase plan was still
                // mid-flight — whatever breaks/places its remaining phases owed were dropped on the floor.
                bot.vlog("ABANDONED " + lastPlanMove + " step " + activePlanStep + " mid-phase "
                        + phaseRunner.phase() + "/" + phaseRunner.phases() + " (reached fired before done)");
            }
            activePlanStep = waypointIndex;
            BlockPos toFloor = floorOf(wp);
            BlockPos fromFloor = (waypointIndex == 0)
                    ? (planStartFloor != null ? planStartFloor : floorOf(bot.blockPosition()))
                    : floorOf(path.waypoint(waypointIndex - 1));
            MovePlan mp = movement.plan(fromFloor.getX(), fromFloor.getY(), fromFloor.getZ(),
                    toFloor.getX(), toFloor.getY(), toFloor.getZ());
            if (mp != null) phaseRunner.begin(mp); else phaseRunner.clear();
            lastPhaseDone = false;
            lastRegressions = 0;
            lastPlanMove = movement.getClass().getSimpleName();
        }

        // Build the SEGMENT the move tracks: from the previous waypoint (or the window/plan start for the
        // first step — waypoints are start-exclusive) to the current target, plus a one-step look-ahead so
        // the controller can ease momentum into a turn. The cursor is reused (no per-tick allocation) and
        // converts to the feet-target frame the controller expects (block-centre xz, floor-cell-top y).
        BlockPos segStart = (waypointIndex == 0)
                ? (planStartFloor != null ? planStartFloor : floorOf(bot.blockPosition()))
                : path.waypoint(waypointIndex - 1);
        BlockPos next = (waypointIndex + 1 < path.size()) ? path.waypoint(waypointIndex + 1) : null;
        cursor.set(segStart, wp, next);
        // Diagnostic snapshot of the executing step's segment cells (read by the parkour harness / Debug only).
        segFromX = segStart.getX(); segFromY = segStart.getY(); segFromZ = segStart.getZ();
        segToX = wp.getX(); segToY = wp.getY(); segToZ = wp.getZ();

        // Execute the step. A CONVERTED move (has a phase plan) reconciles its geometry against the LIVE world
        // each tick — breaking/placing reactively via the PhaseRunner, no one-shot applyEdits, so a missed edit
        // self-heals. An UNCONVERTED move keeps the original path: apply its folded edits once, then steer.
        if (phaseRunner.active()) {
            lastPhaseDone = phaseRunner.run(bot, cursor);
            if (Debug.VERBOSE) logPhaseDiagnostics(movement);
        } else {
            StepEdits edits = path.edits(waypointIndex);
            if (edits != null && waypointIndex != lastEditedIndex && movement.editsReadyNow(bot)) {
                lastEditedIndex = waypointIndex;
                applyEdits(edits);
            }
            movement.steer(bot, cursor);
        }
        bot.steeredThisTick = true;                            // a step ran → sprint re-asserted if a sprint move
        bot.lastSteerMove = movement.getClass().getSimpleName(); // (swim-pose diagnostic; Debug.VERBOSE)

        // (No cross-cutting water rule here — s52: water vertical control is the movements' own
        // SteerControl.holdDepth autopilot, called from each steer()/drive() with the move's pose bias.)

        if (Debug.VERBOSE) logVerbose(movement, wp);

        // DIAGNOSTIC ONLY (s52 hack removal): count grounded low-speed ticks and dump the surrounding
        // columns once per grind. There is deliberately NO recovery actuation here — no held jump, no
        // motion-signature escape hatch, no forced re-search. Those bandaids guessed "stuck" from speed/
        // position signatures, misfired (startup hop, in-place hopping under a wall-pressed Traverse),
        // fed each other (a jump resets the very counters that detect the jump's own livelock), and hid
        // the pathologies they papered over. Base behavior is measured bare; a move that stalls is fixed
        // IN that move (per-move validity envelopes), not course-corrected after the fact.
        // A timed break is not a grind: mining holds the bot grounded+stationary legitimately.
        Vec3 velocity = bot.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR && EntityState.onGround(bot)
                && !bot.mining().busy()) {
            if (++stuckTicks == STUCK_DUMP_TICKS && Debug.ENABLED) dumpStuck(wp);
        } else {
            stuckTicks = 0;
        }
    }

    /**
     * The floor cell a feet-block stands on, topY-aware. Byte-identical to {@code feetBlock.below()} EXCEPT
     * when the feet cell is itself a standable partial floor (a bottom slab / snow layer / carpet / pressure
     * plate / repeater), where the feet block IS the floor cell. The search models nodes as floor cells and
     * builds waypoints as the topY-aware feet block ({@link BlockPathfinder} reconstruct); localization must
     * invert that with the same rule or the settle anchor / phase-plan floor drifts a cell on partials.
     */
    private BlockPos floorOf(BlockPos feetBlock) {
        return isStandableFloor(feetBlock) ? feetBlock : feetBlock.below();
    }

    /**
     * Whether cell {@code pos} is itself a standable floor block (bottom slab / snow / carpet / plate — the
     * bot's feet occupy it while standing). Reads the SAME classified nav descriptor the search reads (via
     * the cheap {@link NavGridView#background} seam — a bare wrapper over the level's built {@code NavStore},
     * no live {@code getBlockState}, no per-search setup bill), so {@link #floorOf} inverts exactly the
     * {@code standable}/{@code topY} the waypoint feet-Y was built from ({@link BlockPathfinder} reconstruct).
     * Unbuilt → {@code false} (→ {@link #floorOf} falls back to {@code below()}, today's behaviour). For a
     * full block the bot's feet cell is AIR (not standable) → {@code below()} — unchanged.
     */
    private boolean isStandableFloor(BlockPos pos) {
        NavGridView grid = NavGridView.background((ServerLevel) Worlds.of(bot));
        return grid.built(pos.getX(), pos.getY(), pos.getZ())
                && NavBlock.isStandable(grid.descriptorAt(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Reusable {@link SteerView} the follower re-points at the current segment each tick — start → target,
     * plus a one-step look-ahead. Converts floor-cell {@link BlockPos}es to the controller's feet-target
     * world frame: block centre horizontally ({@code +0.5}), top face of the floor cell vertically
     * ({@code +1.0}, the world Y a bot standing on that cell has its feet at). Mutable + reused, so no
     * per-tick garbage; the MC {@link BlockPos} type stays on this side of the MC-free {@link SteerView} seam.
     */
    private static final class SegmentCursor implements SteerView {
        private double sx, sy, sz, tx, ty, tz, nx, ny, nz;
        private boolean hasNext;

        void set(BlockPos start, BlockPos target, BlockPos next) {
            sx = start.getX() + 0.5; sy = start.getY() + 1.0; sz = start.getZ() + 0.5;
            tx = target.getX() + 0.5; ty = target.getY() + 1.0; tz = target.getZ() + 0.5;
            hasNext = next != null;
            if (hasNext) {
                nx = next.getX() + 0.5; ny = next.getY() + 1.0; nz = next.getZ() + 0.5;
            }
        }

        @Override public double sx() { return sx; }
        @Override public double sy() { return sy; }
        @Override public double sz() { return sz; }
        @Override public double tx() { return tx; }
        @Override public double ty() { return ty; }
        @Override public double tz() { return tz; }
        @Override public boolean hasNext() { return hasNext; }
        @Override public double nx() { return nx; }
        @Override public double ny() { return ny; }
        @Override public double nz() { return nz; }
    }

    /**
     * Execute a step's folded break/place edits server-side, re-validated against the live world (cells
     * may have changed since planning). The nav grid isn't refreshed here — the bot performs exactly the
     * edits the plan assumed, so the route is now physically walkable, and the next replan rebuilds the
     * spanned chunks from the live world (so it plans over the bot's own changes).
     *
     * <p><b>Inventory deduction (PRD §10 Phase 1b/1c).</b> The bot's REAL inventory backs these actions:
     * <ul>
     *   <li><b>Break:</b> mined yields drop into the world and vanilla {@code ItemEntity} pickup carries
     *       them into the bot's inventory — no extra pipeline needed. When {@code mining.consumesTools} is
     *       on, each real break wears the bot's best tool by one use via the {@link BotInventory} adapter.</li>
     *   <li><b>Place:</b> when {@code placement.consumesBlocks} is on, each footing draws one real placeable
     *       block out of inventory ({@link BotInventory#consumeOnePlaceable}) and places THAT block; if the
     *       bot ran dry the placement is skipped (the feasibility cap should have prevented planning it, and
     *       a rare miss is netted by replan). When off, the configured conjured block is placed with infinite
     *       supply (today's behaviour). The geometry/validity check is unchanged.</li>
     * </ul>
     */
    private void applyEdits(StepEdits edits) {
        ServerLevel level = (ServerLevel) Worlds.of(bot);
        Config cfg = ConfigLoader.config();
        BotInventory inv = (cfg.consumesTools() || cfg.consumesBlocks()) ? new BotInventory(bot) : null;

        for (int i = 0; i < edits.breakCount(); i++) {
            BlockPos p = edits.breakPos(i);
            BlockState target = level.getBlockState(p);
            if (target.isAir()) continue;
            // Execution-side break policy backstop (planner/executor parity, Config.mayBreak): never break
            // an owner-protected block, nor a vanilla-unbreakable one without mining.allowUnbreakable. The
            // planner's descriptor-bit gates should never fold such a break; this re-check on the LIVE
            // state also covers a stale grid (block protected/changed after classification). Skip → the
            // step stays blocked → the stall/replan loop routes around it.
            if (!cfg.mayBreak(target, target.getDestroySpeed(level, p))) continue;
            if (inv != null && cfg.consumesTools()) inv.damageBestTool(target); // wear the tool one use
            WorldEdits.breakBlock(level, p);
        }
        for (int i = 0; i < edits.placeCount(); i++) {
            BlockPos p = edits.placePos(i);
            BlockState occupant = level.getBlockState(p);
            // A protected occupant is never cleared NOR replaced by a placement (filling the cell destroys
            // it either way) — the planner's OPEN_PLACE bit excludes protected cells, this is the live
            // backstop. Skip the cell; replan nets it.
            if (!occupant.isAir() && cfg.protectedBlocks().matches(occupant)) continue;
            if (!Replaceable.isReplaceable(occupant)) {
                // Same planner/executor vocabulary gap as place(): the search's open-for-place bit is
                // shape-based, vanilla replaceability is stricter — a soft empty-shape occupant (berry
                // bush, torch, sapling) must be cleared first or the planned place silently no-ops and
                // the follower jumps onto a cap that never existed (the berry-maze hop-over bug). Clear
                // it like a player would — unless the occupant is unbreakable or owner-protected
                // (mayBreak): then skip, replan nets it.
                if (!cfg.mayBreak(occupant, occupant.getDestroySpeed(level, p))) continue;
                WorldEdits.breakBlock(level, p);
            }
            if (inv != null && cfg.consumesBlocks()) {
                Block block = inv.consumeOnePlaceable();
                if (block == null) continue; // out of blocks — skip; replan nets it
                WorldEdits.placeBlock(level, p, block.defaultBlockState());
            } else {
                WorldEdits.placeBlock(level, p, bot.placeBlock()); // conjured, infinite supply
            }
        }
    }

    // ---- Debug log formatting ----------------------------------------------------------------

    /**
     * {@code /bot debug verbose}: announce which {@link Movement} the bot is executing, toward which cell, and
     * in which medium — one line per change (not per tick), to the owner's chat and the log. This is the
     * diagnostic for "is the bot actually swimming, or is it trying to Ascend in water?": the medium flips to
     * {@code water} the moment it submerges, and the move name says exactly which strategy is driving.
     */
    private void logVerbose(Movement movement, BlockPos wp) {
        String move = movement.getClass().getSimpleName();
        String medium = bot.isInWater() ? "water" : (EntityState.onGround(bot) ? "ground" : "air");
        String key = waypointIndex + "|" + move + "|" + medium;
        if (key.equals(lastVerbose)) return;
        lastVerbose = key;
        bot.chat("[bot] " + move + " → " + AllyBotEntity.compact(wp) + " (" + medium + ")");
        OrebitCommon.LOGGER.info("[Orebit] exec {} -> {} ({}) feetY={} targetY={}",
                move, AllyBotEntity.compact(wp), medium, String.format("%.2f", bot.getY()),
                String.format("%.2f", wp.getY() + 1.0));
    }

    /**
     * {@code /bot debug verbose}: per-tick phase-execution forensics for a CONVERTED move (the PhaseRunner
     * path) — the discriminators for the "missed a break/place and got stuck" reports, since a stuck bot goes
     * silent on every one-line-per-change log. Emits (deduped/throttled):
     * <ul>
     *   <li><b>regressed → re-attempt #n</b> — the move fell back and restarted; a climbing count is the
     *       attempt/mis-land/reset livelock (e.g. a parkour jump that keeps coming up short).</li>
     *   <li><b>holding Nt … needs AIR/FOOTING at (cell)</b> every {@link #HOLD_LOG_TICKS} held ticks, with the
     *       live occupant and {@code mining.busy()}: an AIR hold with {@code busy=false} means the break is
     *       being refused or never starting (see the mine/place REFUSED lines); {@code busy=true} with the
     *       occupant unchanged across many lines is a legitimately slow dig. A FOOTING hold that persists
     *       means {@code place()} is silently refusing — its own REFUSED line names why.</li>
     * </ul>
     */
    private void logPhaseDiagnostics(Movement movement) {
        final int r = phaseRunner.regressions();
        if (r != lastRegressions) {
            lastRegressions = r;
            bot.vlog(movement.getClass().getSimpleName() + " step " + waypointIndex + " regressed → re-attempt #" + r);
        }
        final MovePlan.Need need = phaseRunner.holdNeed();
        if (need == null) {
            lastHoldKey = null;
            holdTicks = 0;
            return;
        }
        final String key = waypointIndex + "|" + need + "|"
                + phaseRunner.holdX() + "," + phaseRunner.holdY() + "," + phaseRunner.holdZ();
        if (key.equals(lastHoldKey)) {
            holdTicks++;
        } else {
            lastHoldKey = key;
            holdTicks = 1;
        }
        if (holdTicks % HOLD_LOG_TICKS == 0) {
            ServerLevel level = (ServerLevel) Worlds.of(bot);
            scratchPos.set(phaseRunner.holdX(), phaseRunner.holdY(), phaseRunner.holdZ());
            bot.vlog("holding " + holdTicks + "t: " + movement.getClass().getSimpleName()
                    + " step " + waypointIndex + " phase " + phaseRunner.phase() + "/" + phaseRunner.phases()
                    + " needs " + need + " at " + AllyBotEntity.compact(scratchPos)
                    + " occ=" + level.getBlockState(scratchPos).getBlock()
                    + " miningBusy=" + bot.mining().busy());
        }
    }

    /** {@code Debug.VERBOSE}: announce (once per change) that the driver is NOT following a path and why —
     *  the state every other execution log is blind to (a consumed/blocked plan emits nothing per tick). */
    private void driveStateLog(String state) {
        if (state.equals(lastDriveState)) return;
        lastDriveState = state;
        bot.vlog("drive: " + state);
    }

    private void dumpStuck(BlockPos wp) {
        ServerLevel level = (ServerLevel) Worlds.of(bot);
        BlockPos foot = bot.blockPosition();
        OrebitCommon.LOGGER.info("[Orebit] STUCK pos=({},{},{}) foot={} target={} | "
                        + "botCol[floor,feet,head,+1,+2]={} targetCol[floor,feet,head,+1,+2]={} (S=solid .=air)",
                String.format("%.2f", bot.getX()), String.format("%.2f", bot.getY()),
                String.format("%.2f", bot.getZ()),
                AllyBotEntity.compact(foot), AllyBotEntity.compact(wp), column(level, foot), column(level, wp));
    }

    /** Solidity of 5 cells from the floor (one below the feet block) up through head+2, as S/. */
    private static String column(ServerLevel level, BlockPos feetBlock) {
        StringBuilder sb = new StringBuilder();
        for (int dy = -1; dy <= 3; dy++) {
            sb.append(level.getBlockState(feetBlock.above(dy)).isAir() ? '.' : 'S');
        }
        return sb.toString();
    }

    private String waypointsString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(AllyBotEntity.compact(path.waypoint(i)));
        }
        return sb.append(']').toString();
    }
}
