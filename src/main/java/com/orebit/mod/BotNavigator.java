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
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Replaceable;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavStore;
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
    private int lastFailLoggedStep = -1; // last step whose validity-envelope FAILURE was logged (log once per step)
    private int lastCautionLoggedStep = -1; // last step whose suspect/pending-search CAUTION hold was logged
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
    /** The active phase plan's search-native FROM-floor / TO-floor frame, snapshotted at plan BUILD so the
     *  validity-envelope failure forensic (which runs every tick, outside the plan-build block) can compare the
     *  plan's expected takeoff-stand foot (fromFloorY+1) against the bot's real foot cell — the one-block
     *  mismatch a PARTIAL standable from-floor (carpet/slab/snow, where floorOf returns the feet cell itself so
     *  foot==floor, not floor+1) produces. Diagnostic only; never drives behavior. */
    private int planFromX, planFromY, planFromZ, planToX, planToY, planToZ;
    /** The plan's topY-aware FEET-block Y for the from/to stands (fed to plan() as fromFootY/toFootY) — the
     *  forensic's real expected takeoff/landing foot (== floorY for a partial standable floor, floorY+1 else). */
    private int planFromFootY, planToFootY;

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

    // ---- NavGrid readiness gate (STEP 2): never plan over an unbuilt vicinity ------------------------
    /** Consecutive ticks the bot has HELD this drive waiting for its {@code navReadyRadiusChunks} ring to
     *  build. State-based: reset to 0 the instant readiness is met (so it only counts CONSECUTIVE unready
     *  ticks); the timeout below is a give-up backstop, not the trigger (no-arbitrary-timers-compliant). */
    private int navReadyWaitTicks;
    /** Dedup for the one-per-episode "waiting for terrain" chat (reset when readiness is met / the plan clears). */
    private boolean navReadyWaitAnnounced;

    /** Reused mutable cursor for the phase-hold occupant read (no per-check allocation). */
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    // ---- per-journey search-health telemetry (pure observation — NAVSTATS) ---------------------------
    /** The live journey's accumulator (reset at each journey start). A journey runs from a goal being set
     *  until it is reached / abandoned / changed (a new goal region). Threaded into each {@link PathPlan} so
     *  every windowed block search + region flood is aggregated; NOTHING here alters a search/plan decision. */
    private final NavJourneyStats journeyStats = new NavJourneyStats();
    /** A snapshot of the last COMPLETED journey (for {@code /bot stats}'s "last completed" section). */
    private final NavJourneyStats lastJourneyStats = new NavJourneyStats();
    private boolean journeyActive;
    /** The level-0 region key of the current journey's goal — a change is a new journey. */
    private long journeyGoalKey;
    /** Bot horizontal position last tick, for the per-tick distance accumulation ({@code havePos} gates the
     *  first tick, which has no previous sample). */
    private double journeyLastX, journeyLastZ;
    private boolean journeyHavePos;
    /** Last-seen region-blacklist size, to delta-accumulate {@code crossingsInvalidated} across plan rebuilds. */
    private int prevBlacklistCount;

    BotNavigator(AllyBotEntity bot) {
        this.bot = bot;
    }

    /** The live journey's telemetry (the autotest reads its aggregates; also the {@code /bot stats} "current"). */
    NavJourneyStats journeyStats() {
        return journeyStats;
    }

    /** The last completed journey's telemetry snapshot (empty if none completed yet). */
    NavJourneyStats lastJourneyStats() {
        return lastJourneyStats;
    }

    /** Readable {@code /bot stats} lines: the current journey, plus the last completed one if it has data. */
    java.util.List<String> statsReport() {
        java.util.List<String> out = new java.util.ArrayList<>(journeyStats.table(
                journeyActive ? "current" : "current (idle)"));
        if (lastJourneyStats.searchCount() > 0 || lastJourneyStats.distanceTraveled() > 1.0) {
            out.add("");
            out.addAll(lastJourneyStats.table("last completed"));
        }
        return out;
    }

    /**
     * Per-tick NAVSTATS bookkeeping (called at the top of {@link #driveToward} — pure observation). Detects
     * a new journey (goal region changed / no active journey) and resets the accumulator, then accumulates
     * this tick's horizontal travel, observes the bot's region for the boundary-recross signal, and
     * delta-accumulates newly-blacklisted crossings. Never affects control flow.
     */
    private void navStatsTick(BlockPos goalFloor) {
        final long goalKey = regionKey(goalFloor);
        if (!journeyActive || goalKey != journeyGoalKey) {
            if (journeyActive) finalizeJourney("superseded"); // the previous goal was replaced mid-flight
            startJourney(goalFloor, goalKey);
        }
        // Abandonment observed here too (covers holdForNavReady's direct navGaveUp set, not just giveUp()).
        if (navGaveUp && journeyActive) {
            finalizeJourney("abandoned");
            return;
        }
        final double x = bot.getX(), z = bot.getZ();
        if (journeyHavePos) {
            final double ddx = x - journeyLastX, ddz = z - journeyLastZ;
            journeyStats.addDistance(Math.sqrt(ddx * ddx + ddz * ddz));
        }
        journeyLastX = x;
        journeyLastZ = z;
        journeyHavePos = true;
        journeyStats.observeRegion(regionKey(floorOf(bot.blockPosition())));
        final int cur = pathPlan != null ? pathPlan.blacklistedCrossings() : 0;
        if (cur >= prevBlacklistCount) journeyStats.addCrossingsInvalidated(cur - prevBlacklistCount);
        prevBlacklistCount = cur; // a plan rebuild drops cur → rebase (no spurious subtract)
    }

    private void startJourney(BlockPos goalFloor, long goalKey) {
        final BlockPos here = floorOf(bot.blockPosition());
        final double dx = goalFloor.getX() - here.getX();
        final double dz = goalFloor.getZ() - here.getZ();
        journeyStats.reset(Math.sqrt(dx * dx + dz * dz));
        journeyActive = true;
        journeyGoalKey = goalKey;
        journeyHavePos = false;
        prevBlacklistCount = pathPlan != null ? pathPlan.blacklistedCrossings() : 0;
    }

    /** End the current journey: record the outcome, log one greppable line (if it did real work), snapshot
     *  it as "last completed", and go idle. Idempotent (a no-op once inactive). */
    private void finalizeJourney(String outcome) {
        if (!journeyActive) return;
        journeyActive = false;
        journeyStats.setOutcome(outcome);
        if (journeyStats.hasActivity()) {
            OrebitCommon.LOGGER.info(journeyStats.logLine());
            journeyStats.copyInto(lastJourneyStats);
        }
    }

    /** Abandonment hook shared by {@link #giveUp} and the nav-readiness timeout — finalizes the journey. */
    private void abandonJourney() {
        finalizeJourney("abandoned");
    }

    /** The level-0 region key of a world cell (the level the skeleton commits over — the boundary-recross
     *  granularity). Packed rx(24)|rz(24)|ry(16); masks make it an identity key, not a reversible address. */
    private long regionKey(BlockPos p) {
        final int minY = RegionGrid.of((ServerLevel) Worlds.of(bot)).minY();
        final long rx = RegionAddress.regionX(p.getX(), 0) & 0xFFFFFFL;
        final long rz = RegionAddress.regionZ(p.getZ(), 0) & 0xFFFFFFL;
        final long ry = RegionAddress.regionY(p.getY(), 0, minY) & 0xFFFFL;
        return (rx << 40) | (rz << 16) | ry;
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

    /** Whether the current plan's reverse-Dijkstra harvest PROVED the goal is unreachable from the bot's
     *  own region (genuinely boxed-in) — as opposed to a budget/other give-up. Distinguishes an honest
     *  boxed-in give-up in the owner chat + autotest reporting (#4 Increment 1). Null-plan → false. */
    boolean boxedInProven() {
        return pathPlan != null && pathPlan.boxedInProven();
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
     * Whether cell {@code (x,y,z)} is one the CURRENT path's own steps up to the executing one PLACED —
     * the "plan knows its own scaffolding" membership test behind the reconcile's break-policy exemption
     * (owner ruling 2026-07-30; the {@code MovePlan.isDoorCell} precedent of plan-carried cell knowledge
     * queried before acting). The default {@code mining.protectedBlocks} list contains the conjured
     * bridge block (cobblestone), so the moment the plan places a scaffold cell, its OWN later step's
     * {@code Need.AIR} on that cell would be refused by {@code Config.mayBreak} without this vouch (the
     * six place→refuse pairs in the 2026-07-30 log: place by Traverse/Ascend, dig by a later Descend of
     * the same plan). Scans the prefix {@code 0..waypointIndex} — a cell a FUTURE step intends to place
     * still holds a foreign block and stays protected. The vouch is planned-prefix MEMBERSHIP, not an
     * executed-place record (none exists): a step the reached-scan SKIPPED, or the current step's
     * not-yet-run place, is inside the prefix even though its place never executed — a protected block
     * deposited by someone else into exactly such a cell mid-window would be wrongly vouched. Accepted:
     * closing it needs new executor-side state (owner sign-off). Cold: a tiny per-step array walk, paid
     * only when {@code mayBreak} already refused the cell.
     */
    boolean planPlacedAt(int x, int y, int z) {
        if (path == null) {
            return false;
        }
        final long key = BlockPos.asLong(x, y, z);
        final int last = Math.min(waypointIndex, path.size() - 1);
        for (int i = 0; i <= last; i++) {
            StepEdits e = path.edits(i);
            if (e == null) {
                continue;
            }
            for (int k = 0; k < e.placeCount(); k++) {
                if (e.placeAt(k) == key) {
                    return true;
                }
            }
        }
        return false;
    }

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
        this.navReadyWaitTicks = 0;      // a fresh goal starts a fresh readiness episode
        this.navReadyWaitAnnounced = false;
    }

    /** Re-anchor after a COMPLETED dimension change: the active plan, its settled/start anchors, and any
     *  give-up state all belong to the OLD level (see {@code AllyBotEntity.onLevelChanged}). */
    void onLevelChanged() {
        clearPlan();
        this.settledFloor = null;
        this.planStartFloor = null;
        this.navGaveUp = false;
    }

    /**
     * Whether the plan is mid-execution of a COMMITTED move that has not yet settled — the move-agnostic
     * generalization of the old {@code parkourJumpInFlight()} gate. A move flagged {@link
     * Movement#commitsAcrossArrival()} (a jump: {@link Parkour}/{@link DiagonalParkour}; the no-jump {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.WalkOff WalkOff} crossing) is an irreversible airborne
     * commitment — once its runup/takeoff/step-off begins the bot leaves the ground and can't stop mid-arc — so a
     * landing cell that is itself the goal must NOT let {@link #driveToward}'s arrival-preempt gate zero forward +
     * clear the plan before touchdown (the ice-STOP parkour undershoot; the WalkOff close-goal step-off).
     *
     * <p>Move-NATURE, not a type test: reads the current step's {@code commitsAcrossArrival()} flag, so it covers
     * every current and future committed move with no {@code instanceof} and no follower edit — an ordinary
     * reversible move (Traverse/Diagonal/Ascend/Descend/Pillar/…) returns {@code false} and arrives normally, and
     * (crucially) a Traverse macro run — which also drives via a multi-phase {@link PhaseRunner} plan — is NOT
     * wrongly blocked. Purely STATE-based (no timers): once the committed move's {@code done} fires, {@link
     * #steerAlongPath} advances the cursor past this step the same tick, so the next tick this reads the next
     * (uncommitted) move — or {@code waypointIndex >= size} — and COMPLETE may fire.
     *
     * <p>RESOLVED KNOWN RISK (the P1 wedge — seen in-game, as the deferral predicted): a TERMINAL committed move
     * that mispredicted onto an unwalkable off-cell spot used to leave this gate latched (its {@code done} never
     * fires because the bot never reaches the exact target cell) → COMPLETE stayed suppressed → a wedge with no
     * re-plan. The state-derived resolution is the committed moves' plan-level validity envelope ({@link
     * MovePlan#failWhen}): grounded anywhere off the plan's own cells FAILS the step, {@link #steerAlongPath}
     * clears the plan, and the normal replan path re-searches from the live floor — so this gate can no longer
     * latch on an off-cell landing. Firing COMPLETE on proximity regardless of an in-flight commitment remains
     * intentionally NOT done: that is the very mid-jump preemption this gate removes (the ice-STOP undershoot).
     */
    private boolean midCommittedMove() {
        if (!phaseRunner.active() || path == null || waypointIndex >= path.size()) {
            return false;
        }
        return path.movement(waypointIndex).commitsAcrossArrival();
    }

    /**
     * Whether BEGINNING step {@code i} commits the bot to risk an unresolved re-search shouldn't carry:
     * a committed move (the parkour family — mid-air on a possibly-stale premise) or a Fall deeper than
     * the bot's safe-fall window (its landing was verified against the possibly-stale grid; Fall carries
     * no commitsAcrossArrival by design — its reached must stay ungated for buoyant landings — so depth
     * rides the waypoint frames). Constant move-nature reads + waypoint math only; no phase plan is
     * built (DESIGN-async-step-safety.md §3).
     */
    private boolean stepCommitsRisk(int i) {
        Movement m = path.movement(i);
        if (m.commitsAcrossArrival()) return true;
        if (m == MovementRegistry.FALL && i > 0) {
            int drop = path.waypoint(i - 1).getY() - path.waypoint(i).getY();
            return drop > bot.caps().safeFallDistance();
        }
        return false;
    }

    /**
     * Whether the bot is standing IN or ON a damaging block (lava, fire, magma, campfire, cactus, wither rose,
     * berry bush, powder snow) — read from the SAME background nav descriptor {@link #isStandableFloor} already
     * uses (no new live/reflective read), checking both the feet cell (a damaging block the bot stands inside —
     * fire/lava/berry) and the floor cell below it (a damaging block it stands on — magma/campfire). An "arrived"
     * bot must not drop all inputs while over a damaging floor: keep the plan and keep moving to walk out of it.
     * Unbuilt cells read AIR (not damaging) → allow arrival, the same permissive default {@link #isStandableFloor}
     * takes when the grid can't answer.
     */
    private boolean onDamagingFloor() {
        NavGridView grid = NavGridView.background((ServerLevel) Worlds.of(bot));
        BlockPos feet = bot.blockPosition();
        return isDamagingCell(grid, feet) || isDamagingCell(grid, floorOf(feet));
    }

    private static boolean isDamagingCell(NavGridView grid, BlockPos p) {
        return grid.built(p.getX(), p.getY(), p.getZ())
                && NavBlock.isDamaging(grid.descriptorAt(p.getX(), p.getY(), p.getZ()));
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
        navStatsTick(goalFloor); // NAVSTATS: journey bookkeeping (pure observation, no control-flow effect)

        double dx = tx - bot.getX();
        double dy = ty - bot.getY();
        double dz = tz - bot.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        // Arrived only when close in 3D. Horizontal proximity alone would let the bot stop directly
        // BELOW the target (it walks under a sky platform / the top of a staircase and quits) — it must
        // also match the target's height, which is what makes it actually climb to reach you.
        //
        // ARRIVAL as a MOVE-AGNOSTIC state test: "arrived" (safe to drop ALL inputs) means the bot is in range
        // AND in a state where stopping is safe AND not mid-commitment to a move that must finish. Three
        // conditions beyond the range test, each covering a whole class rather than one move:
        //   - grounded() || isInWater(): the two stable "stopping is safe" media — on the ground, or buoyant in
        //     water (drop the inputs and you float, you don't fall). This EXCLUDES airborne (Parkour/WalkOff/Fall
        //     mid-arc → not arrived, one check for any airborne move) while still letting a bot floating at a
        //     water/swim goal arrive — grounded-only would leave it treading water at the goal forever, sinking
        //     past a tight-tolerance mid-water goal (the swim `sink` regression). Mirrors the settle anchor below.
        //   - !onDamagingFloor(): standing in/on lava/fire/magma/… → keep moving out, don't settle in it.
        //   - !midCommittedMove(): an irreversible committed move (jump, walk-off) is mid-flight — a landing cell
        //     that is itself the goal must not zero forward + clear the plan before touchdown (the ice-STOP
        //     undershoot; the WalkOff close-goal step-off). State-based: the moment the move's `done` fires,
        //     steerAlongPath advances the cursor past the step, so COMPLETE may fire the next tick.
        boolean withinRange = distXZ <= arriveDist && Math.abs(dy) <= arriveY;
        // The ratified stable-media/settled pair (grounded, or buoyant in water — DESIGN-validity-envelopes
        // §2): the arrival test's "safe to drop ALL inputs" and the consumption-settle write keep exactly
        // this pair — on a climbable or in lava, dropping inputs slides/sinks the bot, so neither arrives.
        final boolean stableMedium = bot.grounded() || bot.isInWater();
        // PLAN-ANCHOR stability (owner ruling 2026-07-30: never plan while the bot is in motion): the
        // launch/adoption gates below ask a DIFFERENT question — "is the bot's floor cell a valid,
        // persistent search/adoption anchor?" — and the answer is yes in any CONTROLLED medium: grounded,
        // buoyant in a swimmable fluid (water AND lava — the swim tier plans lava columns, and a lava-borne
        // bot must plan its escape immediately, not after sinking to the pool floor), or holding a
        // climbable (a hang has always serviced window handoffs; deferring one WAITs the inputs away and
        // slides the bot to the ladder base — the climb/slide-down/re-climb livelock, review finding).
        // Only BALLISTIC states — jump/fall arcs — defer, to the touchdown a few ticks away (the mid-air
        // Fall adoption class the ruling closes).
        final boolean planAnchor = stableMedium || bot.inLava() || bot.onClimbable();
        // Fluid anchors are BOB-QUANTIZED: the buoyancy bob flips a floating bot's floor cell between
        // adjacent Ys at steady state (the swim follower's reached abandoned exact block-Y for exactly
        // this — Swim's continuous REACHED_Y window), so the adoption membership test downstream widens
        // its Y match by ±1 in fluid, mirroring that precedent. Ground anchors stay exact.
        final boolean fluidAnchor = bot.isInWater() || bot.inLava();
        if (withinRange && stableMedium && !onDamagingFloor() && !midCommittedMove()) {
            driveState = "COMPLETE";
            finalizeJourney("reached"); // NAVSTATS: the continuous arrival test is the definition of done
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
                || goalTolXZ != planGoalTolXZ || goalTolY != planGoalTolY
                // EXACT-GOAL MOVED-CELL rebuild (2026-07-29, the farm-sweep freeze): a plan pins its
                // construction-time goal cell, and at EXACT tolerance a COMPLETE plan has no other
                // rebuild trigger when the live goal cell moves WITHIN the same region (the s52b
                // escalation rides a tolerance CHANGE, which an already-exact caller never produces;
                // refreshWindow's `consumed` requires !isComplete). Live repro: the bot's own
                // collision nudged a chased item drop one cell over → the stale COMPLETE plan held
                // the bot one block short forever. A complete exact plan whose stored goal cell
                // differs from the live goal cell is stale — rebuild for the real cell. Non-exact
                // callers (follow/come/mine) keep the escalation path byte-identical.
                || (goalTolXZ == 0 && goalTolY == 0 && pathPlan.isComplete()
                        && !goalFloor.equals(pathPlan.goalFloor()));
        // A BLOCKED status does NOT force a full rebuild: the cascade (HPA-CASCADE.md §6) repairs a blocked hop
        // in place — escalating up its level stack in repairStep — without discarding the whole nested plan. A
        // full rebuild fires only on no-plan or a new goal region.
        if (pathPlan == null || newRegionGoal) {
            // MID-MOTION PLAN GATE (owner ruling 2026-07-30): never LAUNCH a fresh two-tier plan while the
            // bot is ballistic (mid-jump/-fall) — the search would anchor at a floor cell the bot is not
            // actually standing on, and the step-0 phase plan would be built from a frame reality has
            // already left (the mid-air Fall adoption: its instantly-skipped walk-off stride made the
            // landing physically unreachable). Deferral is state-based, not a timer: the trigger (no plan /
            // moved goal) persists, so the replan fires on the first plan-anchor-stable tick; meanwhile any
            // current plan keeps executing (the known-good route from the previous plan) and a planless
            // airborne bot simply WAITs the few ticks to touchdown below.
            if (planAnchor) {
                // NAVGRID READINESS GATE (STEP 2): the block/region tiers read an UNBUILT nav cell as AIR (the
                // background NavGridView has no live-getBlockState fallback), so planning before the bot's
                // surrounding grid has built picks a truncated-world target (the cold-start canopy bug). NavGrids
                // build async over a few ticks after chunks load, so before the FIRST plan / a new-region replan,
                // require the navReadyRadiusChunks ring resident. Not ready → HOLD this tick (don't plan), the same
                // held state a planless drive already uses; after navReadyTimeoutTicks consecutive unready ticks,
                // give up cleanly. State-based (polls real NavStore residency); the counter is a backstop only.
                if (!navVicinityReady()) {
                    return holdForNavReady();
                }
                navReadyWaitTicks = 0;
                navReadyWaitAnnounced = false;
                if (newRegionGoal) {
                    // New destination region → the learned dead-ends no longer apply; start the repair fresh.
                    navGaveUp = false;
                }
                replan(goalFloor);
                blockRefreshTicks = TERRAIN_RECHECK_TICKS;
            }
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
            if (path != null && waypointIndex >= path.size() && stableMedium) {
                this.settledFloor = currentFloor;
            }
            // Floor-cell EQUALITY alone is not "settled": an airborne bot whose feet have not yet left the
            // settled column passes it (the 2026-07-30 11:40:19 mid-air refreshWindow that adopted a plan
            // at grounded=false), and an uncommitted move chain (Climb/Descend) can write the anchor itself
            // while airborne between rungs. So the boundary additionally requires plan-anchor stability
            // (owner ruling 2026-07-30: never plan while the bot is ballistic; a climbable hang or fluid
            // suspension is a controlled anchor and keeps servicing window handoffs — deferring a mid-hang
            // handoff would WAIT the inputs away and slide the bot down the ladder). Deferral costs the few
            // ticks to touchdown; every commit/refresh/adopt below re-fires from persisting state.
            if (settledFloor != null && currentFloor.equals(settledFloor) && planAnchor) {
                pathPlan.onBotMoved(settledFloor, bot.currentStartMode(), fluidAnchor);
                boolean consumed = path != null && waypointIndex >= path.size() && !pathPlan.isComplete();
                if (consumed || blockRefreshTicks <= 0) {
                    // Terrain-recheck debounce (s52): the periodic re-search fires ONLY when the level's
                    // nav grid actually changed since this plan's last search — an unchanged epoch means
                    // the re-search would be byte-identical, so just re-arm. A CONSUMED plan always
                    // refreshes (that's forward progress, not a terrain poll).
                    // PLAN-RELEVANCE gate (owner 2026-07-24): re-search on the periodic recheck only when a
                    // chunk the current PLAN traverses actually changed — not on any dimension-global grid
                    // mutation. A travelling bot's own frontier chunk builds/drops (and a house built 50k
                    // blocks away) formerly re-armed this every window forever (the open-ocean flap). A
                    // CONSUMED plan always refreshes (forward progress, not a poll).
                    // impacted is evaluated INDEPENDENTLY of consumed (not short-circuited behind it):
                    // a window both consumed AND terrain-impacted must launch a SUSPECT search, not hide
                    // under the routine consumed-plan label (the caution gate keys on it —
                    // DESIGN-async-step-safety.md §3).
                    boolean impacted = pathPlan.planImpacted();
                    if (consumed || impacted) {
                        if (Debug.ENABLED) {
                            // goal + baseline are logged alongside the trigger (owner request 2026-08-04).
                            // Comparing a /bot trace against a live search is only meaningful when the
                            // SEARCH INPUTS provably match, and two of them were invisible: the goal FLOOR
                            // cell (a one-block difference from the typed coords silently traces a different
                            // search — it cost a full round-trip on the (56,171,256) cobble investigation),
                            // and the splice baseline (documented "null for every non-spliced plan", so
                            // printing it converts an assumption into an observation).
                            OrebitCommon.LOGGER.info(
                                    "[Orebit] block re-search: site=refreshWindow reason={} wpIdx={}/{} bot={}"
                                            + " goal={} baseline={}",
                                    impacted ? (consumed ? "consumed+impacted" : "plan-impacted") : "consumed-plan",
                                    waypointIndex, path == null ? -1 : path.size(),
                                    AllyBotEntity.compact(bot.blockPosition()),
                                    AllyBotEntity.compact(pathPlan.goalFloor()),
                                    pathPlan.baselineSummary());
                            // WHAT changed, when terrain is the reason (owner request 2026-08-06). The
                            // trigger line above names the reason but never its cause, so "a vine grew
                            // beside the bot" and "something changed 200 blocks away that happens to share a
                            // plan chunk" read identically — and this re-search rebuilds the plan mid-move,
                            // resetting the waypoint cursor under a step that is already executing. The
                            // forensic carries the block, its before/after state, its distance from the bot
                            // AND from the nearest plan waypoint, plus both level-0 region addresses, so
                            // whether the plan-relevance gate earned this re-search is checkable rather
                            // than asserted.
                            if (impacted) {
                                OrebitCommon.LOGGER.info("[Orebit]   impact: {}",
                                        pathPlan.impactForensic(bot.blockPosition()));
                            }
                        }
                        pathPlan.refreshWindow(impacted);
                    }
                    blockRefreshTicks = TERRAIN_RECHECK_TICKS;
                }
                BlockPathPlan now = pathPlan.currentBlockPlan();
                if (now != lastBlockPlanRef) {
                    this.path = now;
                    this.lastBlockPlanRef = now;
                    this.waypointIndex = 0;
                    this.lastEditedIndex = -1;
        this.lastFailLoggedStep = -1;
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
                    pathPlan.preplan(path.floor(path.size() - 1),
                            EditSnapshot.fromRemainingSteps(path, lastEditedIndex + 1),
                            bot.currentStartMode());
                }
            }
        }
        // Planless async adoption: a bot with NO walkable plan can't wait for the settled boundary the
        // normal drain rides — a treading-water bot never establishes one — and there is nothing to
        // un-adopt. Poll at tick rate and, on adoption, run the same swap/anchor mechanics the boundary
        // block runs — anchored at the bot's LIVE floor, exactly how replan() seeds a fresh plan.
        // Plan-anchor gated (owner ruling 2026-07-30): treading water, lava suspension, and a climbable
        // hang are controlled anchors and adopt at tick rate as before, but a MID-FALL first adoption now
        // defers to touchdown — it would anchor planStartFloor/settledFloor and frame step 0 from an
        // airborne cell (the mid-air Fall class), and every fall terminates in an anchor medium within
        // ticks, so nothing waits forever.
        // (A consumed plan needs no special case here — consumption is a settle event above, so it
        // drains through the boundary-gated onBotMoved the tick it settles. s52.)
        // Double adoption with the boundary block is impossible: both compare against lastBlockPlanRef,
        // so whichever runs first swaps and the other no-ops on the same reference.
        if (pathPlan != null && path == null && planAnchor) {
            BlockPos liveFloor = floorOf(bot.blockPosition());
            pathPlan.pollWhenPlanless(liveFloor, fluidAnchor);
            BlockPathPlan adopted = pathPlan.currentBlockPlan();
            if (adopted != lastBlockPlanRef) {
                this.path = adopted;
                this.lastBlockPlanRef = adopted;
                this.waypointIndex = 0;
                this.lastEditedIndex = -1;
        this.lastFailLoggedStep = -1;
                this.activePlanStep = -1;
                this.planStartFloor = liveFloor;
                this.settledFloor = liveFloor;
                this.stuckTicks = 0; // fresh plan → fresh diagnostic window
            }
        }

        // Region repair (cheap status check): a BLOCKED window — wherever it surfaced — gets its dead
        // skeleton hop blacklisted, so the NEXT tick's `skeletonInvalid` reroute already avoids it (one
        // useful region replan, not a wasted same-skeleton rebuild first). Give-up lives here too.
        // Plan-anchor gated (owner ruling 2026-07-30): the repair's replanBlock launches/installs a
        // search exactly like the boundary branch, so it defers mid-flight too; the BLOCKED generation
        // persists, so exactly-one-repair-per-result is preserved, just delayed to touchdown.
        if (planAnchor) {
            repairStep();
        }

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
        journeyStats.onReplan(); // NAVSTATS: a full skeleton (PathPlan) rebuild — includes the initial build
        try {
            if (pathPlan != null) pathPlan.cancelPending(); // the old plan's in-flight search is superseded
            // Async pathing (pathing.async): hand the plan the planner pool so its window searches run off
            // the tick thread. Gated on the LIVE config, not just instance() — the pool is a JVM-lifetime
            // static, so on an integrated server a re-opened world with pathing.async=false would otherwise
            // silently keep using the previous world's pool (review finding).
            PlanExecutor executor = ConfigLoader.config().asyncPathing() ? PlanExecutor.instance() : null;
            this.pathPlan = new PathPlan(level, RegionGrid.of(level), startFloor, goalFloor, bot.caps(),
                    bot.inventoryFeasibility(), bot.currentStartMode(), null, executor,
                    goalTolXZ, goalTolY, journeyStats); // last arg: NAVSTATS telemetry sink (pure observation)
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
        this.lastFailLoggedStep = -1;
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
        // The hop + window target must be sampled BEFORE repairBlocked(), which re-derives the skeleton and
        // resets the window — afterwards the crossing being blamed is already gone. Likewise the failing
        // search's realized-set size + start region (blame-context enrichment).
        final String hop = describeBlockedHop();
        final BlockPos wt = pathPlan.currentWindowTarget();
        final int realized = pathPlan.blockedRealizedCount();
        final String searchStart = pathPlan.blockedStartRegionDesc();
        journeyStats.onRepair(); // NAVSTATS: a region-tier online repair attempt
        final boolean repaired = pathPlan.repairBlocked();
        logRepair(gen, hop, wt, repaired, realized, searchStart);
        if (!repaired) {
            giveUp();
        }
    }

    /**
     * Server-log twin of the region-crossing invalidation chat line. The chat message alone is INVISIBLE to a
     * headless run: {@link AllyBotEntity#chat} routes to the owner's {@code CommandFeedback}, so with a
     * synthetic never-placed owner (the autotest harness) the event leaves NO trace in the log, and a
     * {@code grep} for it wrongly reads as "the bot never invalidated". This makes the event observable
     * regardless of client/chat, in the {@code [Orebit]} logger idiom (cf. {@code window-swap} / {@code
     * START-DEAD}).
     *
     * <p>Logs the repair's ACTUAL outcome, which the chat line (emitted before the attempt) cannot: {@code
     * REROUTED} = a crossing really was blacklisted and a new skeleton derived; {@code GAVE-UP} = there was
     * no onward hop to blame, or every level's blacklist is exhausted — the bot is about to {@link #giveUp}
     * and NOTHING was invalidated. A {@code ->?} to-hop marks the former case (the bot sits on the last
     * skeleton step), which is why the two must not be conflated when reading a run.
     *
     * <p>Deliberately UNGATED (like START-DEAD): it is rare and meaningful, not per-tick noise — {@link
     * #repairStep} fires exactly ONE repair per BLOCKED search result ({@link PathPlan#blockedGeneration}),
     * so the line is bounded by the number of distinct blocked results. Never throws onto the tick.
     */
    private void logRepair(int gen, String hop, BlockPos windowTarget, boolean repaired,
                           int realized, String searchStart) {
        try {
            OrebitCommon.LOGGER.info(
                    "[Orebit] region-crossing BLOCKED (gen={}) -> {} bot={} hop={} windowTarget={} "
                            + "realized={} searchStart={} "
                            + "reason=block tier cannot realize this crossing for these caps",
                    gen, repaired ? "REROUTED (crossing blacklisted)" : "GAVE-UP (no hop to blame / exhausted)",
                    AllyBotEntity.compact(bot.blockPosition()), hop,
                    windowTarget == null ? "?" : AllyBotEntity.compact(windowTarget),
                    realized < 0 ? "?" : Integer.toString(realized), searchStart);
        } catch (Throwable ignored) {
            // diagnostics must never crash the tick
        }
    }

    /** The skeleton hop {@link PathPlan#repairBlocked} is about to blame, as {@code S<i>(rx,ry,rz)->S<i+1>(…)}
     *  — {@link PathPlan#blamedHopIndex}, the same dispatch {@link PathPlan#blockedHop} uses (the realized-
     *  crossing blame walk, no longer a hardcoded {@code windowStart -> windowStart+1}). {@code ?->?} means
     *  no onward hop exists (the give-up branch). Never throws. */
    private String describeBlockedHop() {
        try {
            final int from = pathPlan.blamedHopIndex();
            final RegionPathPlan sk = pathPlan.skeletonPlan();
            return describeStep(sk, from) + "->" + describeStep(sk, from + 1);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** {@code S<step>(rx,ry,rz:f<frag>)} for a skeleton step, or {@code "?"} when out of range (cf.
     *  logWindowSwap). The fragment id names WHICH pocket of the region the hop targets — a region-only
     *  line hid which of two stacked fragments a blamed crossing entered. */
    private static String describeStep(RegionPathPlan sk, int step) {
        if (sk == null || step < 0 || step >= sk.size()) return "?";
        return "S" + step + "(" + sk.rx(step) + "," + sk.ry(step) + "," + sk.rz(step)
                + ":f" + sk.fragmentId(step) + ")";
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
        abandonJourney(); // NAVSTATS: the region tier exhausted its options — journey abandoned
        if (pathPlan != null && pathPlan.boxedInProven()) {
            // Reverse-Dijkstra proved the bot's own region cannot reach the goal — an honest boxed-in
            // give-up, distinct from a budget/other exhaustion (#4 Increment 1).
            bot.chat("I can't reach you — you're walled off (no route exists from here).");
        } else {
            bot.chat("I can't find a way to reach you.");
        }
    }

    /**
     * Whether the bot's surrounding NavGrid is built out to the configured readiness radius — the
     * state-based signal the readiness gate polls (STEP 2). A {@code navReadyRadiusChunks} of 0 tests only
     * the bot's own chunk; the ring must cover a full block-tier window so a plan never extends into unbuilt
     * (AIR-reading) chunks. Cold — one ring residency scan per held tick, never on a hot path.
     */
    private boolean navVicinityReady() {
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        final int radius = ConfigLoader.config().navReadyRadiusChunks();
        final BlockPos p = bot.blockPosition();
        return NavStore.ringBuilt(level, p.getX() >> 4, p.getZ() >> 4, radius);
    }

    /**
     * HOLD this tick because the bot's vicinity NavGrid isn't built yet (STEP 2): drop movement inputs (the
     * same held state a planless drive uses), count the consecutive unready tick, and — only after
     * {@code navReadyTimeoutTicks} of them — give up cleanly so a genuinely un-loadable area fails instead of
     * hanging. Returns {@code false} (not arrived), matching {@link #driveToward}'s hold contract.
     */
    private boolean holdForNavReady() {
        driveState = "WAIT";
        bot.setForward(0.0f);
        bot.lookAtPlayer(bot.owner());
        navReadyWaitTicks++;
        if (!navReadyWaitAnnounced) {
            navReadyWaitAnnounced = true;
            bot.chat("[bot] waiting for the terrain to load…");
        }
        if (navReadyWaitTicks > ConfigLoader.config().navReadyTimeoutTicks() && !navGaveUp) {
            navGaveUp = true;
            abandonJourney(); // NAVSTATS: terrain never loaded — journey abandoned
            bot.chat("I can't get terrain data to path there.");
        }
        if (Debug.VERBOSE) {
            driveStateLog("WAIT (nav grid unbuilt: " + navReadyWaitTicks + "t)");
        }
        return false;
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
    /**
     * {@code /bot debug verbose}: dump a step's INTENDED break/place cells at plan-begin, so "planned but
     * never performed" is a direct diff against the executor's {@code break executed} / {@code place executed}
     * lines rather than something inferred from a phase that failed to appear. Silent when the step carries no
     * edits (the overwhelmingly common case), so this costs a null check on a cold path.
     */
    private void logPlannedEdits(StepEdits edits) {
        if (edits == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("[Orebit]   planned edits:");
        for (int i = 0; i < edits.breakCount(); i++) {
            BlockPos p = edits.breakPos(i);
            sb.append(" BREAK(").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                    .append(')');
        }
        for (int i = 0; i < edits.placeCount(); i++) {
            BlockPos p = edits.placePos(i);
            sb.append(" PLACE(").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                    .append(')');
        }
        if (edits.breakCount() == 0 && edits.placeCount() == 0) {
            sb.append(" (none)");
        }
        OrebitCommon.LOGGER.info(sb.toString());
    }

    private void steerAlongPath() {
        // Advance to the furthest waypoint the bot has reached. Waypoints ARE blocks and so are the bot's feet
        // ({@code blockPosition()}), so the default test is block-exact (Movement.reached); a swim move
        // loosens it vertically for a buoyancy-bobbing bot. Because the match includes Y, the feet block only
        // equals the next step once the bot has actually climbed onto it (a stacked staircase can't be
        // skipped); scanning from the end absorbs any overshoot.
        // A move that OWNS A PHASE PLAN is the sole authority on its own completion (owner ruling,
        // 2026-08-03): "You replan when the current move is FINISHED — which may end with the bot holding
        // sneak inside a vine." While its plan is active and not done, the cursor may not advance, full stop.
        //
        // Two completion authorities used to race here and `reached` always won: PhaseRunner said "phase 1 of
        // 2, still running" while Movement.reached said "feet are in the cell, close enough". Measured
        // 2026-08-03 — a Descend mid-fall through a vine (botY 170.922 -> 170.772, airborne, on a climbable)
        // had `reached` fire at phase 1/2, the cursor jumped, and the NEXT step was framed from that airborne
        // pose (`PLAN ... expectTakeoffFoot.y=170 botY=170.472 grounded=false`). Every guard downstream then
        // measured against a takeoff frame that was ~0.9 blocks wrong from birth, and the bot wedged pressing
        // forward into a leaf it would have cleared had it finished the descent first.
        //
        // NOTE this is the SAME predicate the "ABANDONED ... (reached fired before done)" tripwire already
        // computes to DETECT the illegitimate advance; it only ever logged it. It now refuses it, so that
        // log becomes what it was written to be — a rare-event tripwire rather than a routine race report.
        //
        // `settled()` is deliberately NOT this test. It answers "is the bot supported by some medium right
        // now" (grounded / afloat / hanging), which is a different question from "is it safe to leave this
        // move" — a lateral vine cling is supported for its whole duration while its move is very much in
        // progress. Supported is not interruptible.
        final boolean phaseOwnsCompletion =
                phaseRunner.active() && !lastPhaseDone && !phaseRunner.doneNow(bot);
        for (int j = path.size() - 1; !phaseOwnsCompletion && j >= waypointIndex; j--) {
            BlockPos w = path.waypoint(j);
            if (path.movement(j).reached(bot, w.getX(), w.getY(), w.getZ())) {
                if (Debug.VERBOSE && j > waypointIndex) {
                    bot.vlog("advance SKIPPED " + (j - waypointIndex) + " step(s): " + waypointIndex + "→" + (j + 1)
                            + " — skipped steps' edits/phases never ran");
                }
                waypointIndex = j + 1;
                // A move just COMPLETED — this is the settled stand cell the driver commits/replans off (its
                // edits are now applied). Advancing the anchor only here is what keeps a mid-move transient from
                // triggering a boundary replan (see settledFloor). The anchor is the step's SEARCH-NATIVE floor
                // (carried through reconstruct — path.floor(j)), not a floorOf(w) re-inversion: with the step
                // completed and its edits applied the two agree (the bot cannot stand IN a solid cell), and the
                // carried floor stays exact on partials and never depends on the live cell's current solidity.
                this.settledFloor = path.floor(j);
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
        // search-native FLOOR cells, carried per-waypoint through reconstruct (BlockPathPlan.floorY).
        if (waypointIndex != activePlanStep) {
            // PENDING-SEARCH CAUTION (owner 2026-07-31, DESIGN-async-step-safety.md §3): an async search
            // is outstanding — suspect (terrain-impacted/repair: this plan may be INVALID) or routine (a
            // window slide / P4 pre-plan whose result may change the route's direction). Entering a
            // COMMITTED move or a deep Fall now would launch the bot into that unresolved future, so the
            // transition defers: stand at the just-settled anchor. The wait is bounded by the search
            // budget (the drain runs at this very anchor on the next boundary tick), and a standing bot
            // always seam-accepts — which also breaks the walk-outruns-the-seam retry storm. Safe steps
            // are NOT deferred (the async pipeline keeps walking through routine searches). Never holds
            // on a damaging floor (the arrival gate's own keep-moving rule), and only from a stable
            // medium (a ballistic bot cannot stand safe — it proceeds as before).
            if (pathPlan != null && pathPlan.searchPending()
                    && stepCommitsRisk(waypointIndex)
                    && (bot.grounded() || bot.isInWater())
                    && !onDamagingFloor()) {
                if (Debug.VERBOSE && lastCautionLoggedStep != waypointIndex) {
                    lastCautionLoggedStep = waypointIndex;
                    bot.vlog("CAUTION hold before " + movement.getClass().getSimpleName() + " step "
                            + waypointIndex + " — " + (pathPlan.suspectSearchPending() ? "SUSPECT" : "routine")
                            + " re-search in flight");
                }
                bot.setForward(0.0f);
                return;
            }
            if (Debug.VERBOSE && phaseRunner.active() && !lastPhaseDone && activePlanStep >= 0
                    && !phaseRunner.doneNow(bot)) {
                // The reached-before-done seam: the cursor moved on while the old step's phase plan was still
                // mid-flight — whatever breaks/places its remaining phases owed were dropped on the floor.
                // lastPhaseDone is necessarily ONE TICK STALE here (it sampled last tick's run against last
                // tick's physics state), so a step whose done and reached flip on the SAME state — every
                // single-phase converted move (the sprint-swim flood), and a grounded-gated parkour landing —
                // would log on 100% of its normal completions. Re-asking the outgoing plan's TERMINAL done on
                // the CURRENT state (doneNow — pure, no side effects) suppresses exactly those same-state
                // completions, keeping this the rare-event tripwire it was written as: genuinely mid-phase,
                // or terminal-done still false on the very state that advanced the cursor.
                bot.vlog("ABANDONED " + lastPlanMove + " step " + activePlanStep + " mid-phase "
                        + phaseRunner.phase() + "/" + phaseRunner.phases() + " (reached fired before done)");
            }
            activePlanStep = waypointIndex;
            // Anchor the phase-plan frame on the step's SEARCH-NATIVE floors, carried per-waypoint through
            // reconstruct (path.floorY) — NOT re-derived via floorOf(waypoint). The inversion assumed the feet
            // cell is air or a bottom-partial; on a break-at-feet step (the feet cell solid until the plan's
            // own AIR needs mine it) it read that cell as a standable floor and shifted the whole frame +1 —
            // so the block to be mined became the FOOTING and was never mined (PATHOLOGY P1B). floorOf remains
            // for the bot's LIVE position only (settle anchor, adoption), where inverting real feet is correct.
            final int tfy = path.floorY(waypointIndex); // to-floor: (wp.x, tfy, wp.z)
            final int ffx, ffy, ffz;                    // from-floor
            if (waypointIndex == 0) {
                BlockPos ff = (planStartFloor != null ? planStartFloor : floorOf(bot.blockPosition()));
                ffx = ff.getX(); ffy = ff.getY(); ffz = ff.getZ();
            } else {
                BlockPos pw = path.waypoint(waypointIndex - 1);
                ffx = pw.getX(); ffy = path.floorY(waypointIndex - 1); ffz = pw.getZ();
            }
            // The bot's real FEET-block Y standing on each floor — topY-aware (mirrors BlockPathfinder.feetYOf,
            // the same value the reconstruct carries as the waypoint Y): the floor cell ITSELF for a bottom-
            // partial floor (carpet/slab/snow/plate, feet occupy the floor's own cell), else floorY+1. Fed into
            // plan() so the move's stand guards + body-clearance cells sit at the real feet, not a blanket +1.
            // TO is the waypoint Y (already topY-aware); FROM is the previous waypoint's Y, or the start floor's
            // derived feet-Y for step 0 (the bot's own stand cell).
            final int toFootY = wp.getY();
            final int fromFootY = (waypointIndex == 0)
                    ? feetYForFloor(ffx, ffy, ffz)
                    : path.waypoint(waypointIndex - 1).getY();
            // Snapshot the plan frame for the validity-envelope failure forensic (the fail site runs every tick,
            // outside this plan-build block).
            planFromX = ffx; planFromY = ffy; planFromZ = ffz; planFromFootY = fromFootY;
            planToX = wp.getX(); planToY = tfy; planToZ = wp.getZ(); planToFootY = toFootY;
            MovePlan mp = movement.plan(ffx, ffy, ffz, wp.getX(), tfy, wp.getZ(), fromFootY, toFootY);
            if (mp != null && Debug.VERBOSE) {
                // Plan-begin execution forensic: the search-native FROM/TO floor cells this move drives between,
                // the takeoff-stand foot the plan's guards now expect (topY-aware fromFootY), and the bot's ACTUAL
                // live pose. A partial standable FROM-floor (carpet/slab/snow) shows takeoffTopY<16 with
                // expectTakeoffFoot.y == botFoot.y (the fix: foot==floor for a partial, not floor+1).
                OrebitCommon.LOGGER.info(
                        "[Orebit] PLAN {} from-floor=({},{},{}) to-floor=({},{},{}) expectTakeoffFoot.y={} "
                                + "botFoot=({},{},{}) botY={} grounded={} fromFloorStandable={} takeoffTopY={} toFloorTopY={}"
                                + " | {}",
                        movement.getClass().getSimpleName(), ffx, ffy, ffz, wp.getX(), tfy, wp.getZ(), fromFootY,
                        bot.footX(), bot.footY(), bot.footZ(),
                        String.format("%.3f", bot.y()), bot.grounded(),
                        isStandableFloor(new BlockPos(ffx, ffy, ffz)),
                        takeoffTopY(new BlockPos(ffx, ffy, ffz)), takeoffTopY(new BlockPos(wp.getX(), tfy, wp.getZ())),
                        entryState());
                // PLANNED EDITS for this step (owner request, 2026-08-03). Until now the only edit signal was
                // the aggregate "+edits" flag on the search line and the "break/place executed" lines from the
                // executor — so a break the plan INTENDED but never performed was invisible, and had to be
                // inferred from a missing phase. Printing the intended cells makes planned-vs-performed a
                // direct diff: every cell here should show up as a matching "executed" line, and one that
                // doesn't is a skipped edit (the 2026-08-03 wedge: a 1-tall gap the bot could not fit through
                // because the leaves in front of it were never smashed).
                logPlannedEdits(path.edits(waypointIndex));
            }
            if (mp != null) {
                // The step's horizontal movement direction (signum) → the runner's direction-aware body-obstruction
                // test (Need.AIR mines a cell only if the block blocks the corridor ALONG this route — a closed
                // door across the path, not an open door along a side).
                mp.moveDir(Integer.signum(wp.getX() - ffx), Integer.signum(wp.getZ() - ffz));
                // DOORS P3: fold this step's door-set (SET_OPEN entering / SET_CLOSED on the exit double-toggle)
                // into the plan as Need.OPEN reqs. A movement's cell-geometry plan(...) can't derive a door-open
                // from floor coords alone, but the search already folded it onto the step's StepEdits — inject it
                // here so the PhaseRunner opens/closes the door (and never mines it) as a converted move. Almost
                // always empty; the loop is a no-op on the common step.
                StepEdits se = path.edits(waypointIndex);
                if (se != null) {
                    for (int i = 0; i < se.doorSetCount(); i++) {
                        long c = se.doorSetAt(i);
                        mp.requireDoor(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c), se.doorSetOpenAt(i));
                    }
                }
                // fromFootY arms PhaseRunner's implicit settle gate — the plan is framed from the bot
                // RESTING at that feet cell, and the runner refuses to execute it until that is true.
                phaseRunner.begin(mp, fromFootY);
            } else {
                phaseRunner.clear();
            }
            lastPhaseDone = false;
            lastRegressions = 0;
            lastPlanMove = movement.getClass().getSimpleName();
        }

        // Build the SEGMENT the move tracks: from the previous waypoint (or the window/plan start for the
        // first step — waypoints are start-exclusive) to the current target, plus a one-step look-ahead so
        // the controller can ease momentum into a turn. The cursor is reused (no per-tick allocation).
        //
        // FRAME: every cell fed to the cursor is a FEET cell (path waypoints are the reconstruct's
        // topY-aware feet). Step 0's start is the one cell that doesn't come from the path, so it must be
        // LIFTED from its floor into the same frame (feetYForFloor — the identical lift plan() frames use).
        // Feeding the raw start FLOOR here handed Climb.steer's Δy discriminator (ty − sy; the follower's
        // only sy consumer) a mixed-frame segment whose −1 cancelled: a first-step climb-DOWN read as
        // LATERAL, the lateral branch sneaks to hold height, and the vanilla sneak edge-guard pinned the
        // bot on the lip of its own placed block at the head of a vine descent, forever (the flagship-GOTO
        // freeze). Steps ≥ 1 were already feet-frame on both ends and never saw the bug.
        final BlockPos segStart;
        if (waypointIndex == 0) {
            BlockPos sf = (planStartFloor != null ? planStartFloor : floorOf(bot.blockPosition()));
            segStart = new BlockPos(sf.getX(), feetYForFloor(sf.getX(), sf.getY(), sf.getZ()), sf.getZ());
        } else {
            segStart = path.waypoint(waypointIndex - 1);
        }
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
            if (phaseRunner.failed()) {
                // VALIDITY-ENVELOPE FAILURE (the P1 off-plan wedge): the step's plan declared the live state
                // outside its model (MovePlan.failWhen — e.g. a committed jump grounded on a cell that is
                // neither its takeoff stand nor its landing column). OWNER POLICY (2026-07-23): fail → log →
                // HOLD, not replan. Deviation from the plan is either external force (creeper knockback — a
                // future repair concern) or a MOVEMENT BUG (a mis-executed step) — and while the movement
                // pathologies are still being burned down, a silent auto-replan would MASK the bugs this
                // envelope exists to surface. So: log ONCE per failed step (ungated — rare and meaningful,
                // the STUCK/logRepair idiom), stop driving, and hold the failed plan frozen for inspection.
                // The bot stalls visibly at the failure point; the log carries the step/phase/position needed
                // to reproduce. Flipping this back to clearPlan() (auto-replan) is the ratified future step
                // once the failure classes are fixed.
                if (activePlanStep != lastFailLoggedStep) {
                    lastFailLoggedStep = activePlanStep;
                    OrebitCommon.LOGGER.info(
                            "[Orebit] step FAILED (validity envelope) {} step {} phase {}/{} bot={} — holding"
                                    + " (fail->hold policy; no auto-replan while movement bugs are hunted)",
                            lastPlanMove, activePlanStep, phaseRunner.phase(), phaseRunner.phases(),
                            AllyBotEntity.compact(bot.blockPosition()));
                    // Envelope forensic: the plan's FROM/TO floor frame + the topY-aware expected takeoff foot
                    // (planFromFootY) vs the bot's real grounded pose. After the partial-floor fix these agree
                    // (foot == floorY for a partial standable); a surviving mismatch here is a genuine off-plan.
                    OrebitCommon.LOGGER.info(
                            "[Orebit]   envelope: from-floor=({},{},{}) to-floor=({},{},{}) expectTakeoffFoot.y={}"
                                    + " | botFoot=({},{},{}) botY={} grounded={} fromFloorStandable={} takeoffTopY={} toFloorTopY={}"
                                    + " | {}",
                            planFromX, planFromY, planFromZ, planToX, planToY, planToZ, planFromFootY,
                            bot.footX(), bot.footY(), bot.footZ(),
                            String.format("%.3f", bot.y()), bot.grounded(),
                            isStandableFloor(new BlockPos(planFromX, planFromY, planFromZ)),
                            takeoffTopY(new BlockPos(planFromX, planFromY, planFromZ)),
                            takeoffTopY(new BlockPos(planToX, planToY, planToZ)),
                            entryState());
                }
                bot.setForward(0.0f);
                return;
            }
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
     * plate / repeater), where the feet block IS the floor cell. For the bot's <b>LIVE position only</b>
     * (settle/adoption anchors, damaging-floor probe) — a real standing bot's feet cell is never solid, so
     * the inversion is exact there. Plan waypoints must NOT come through here: a break-at-feet step's feet
     * cell is solid (standable) until its folded break runs, so the inversion drifts +1 — the follower uses
     * the search-native floors carried on the plan instead ({@link BlockPathPlan#floorY}, PATHOLOGY P1B).
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
     * Diagnostic ONLY (validity-envelope forensic): the takeoff SURFACE height (sixteenths within the cell)
     * the PLANNER's {@code MovementContext.floorSurface} reads for a floor cell — {@code NavBlock.topY} of the
     * built descriptor (full block 16, slab 8, carpet ~1), or {@code -1} if unbuilt. Lets the fail log show
     * which {@code ParkourEnvelope} row the search selected, i.e. whether planning factored the from-height. */
    private int takeoffTopY(BlockPos pos) {
        NavGridView grid = NavGridView.background((ServerLevel) Worlds.of(bot));
        if (!grid.built(pos.getX(), pos.getY(), pos.getZ())) return -1;
        return NavBlock.topY(grid.descriptorAt(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * The bot's feet-block Y standing on floor cell {@code (fx,fy,fz)} — topY-aware, mirroring the search's
     * {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}{@code .feetYOf} (the value the
     * reconstruct carries as the waypoint Y): the floor cell ITSELF for a bottom-partial standable floor
     * (bottom slab / snow / carpet / plate, whose collision top is mid-cell so the feet occupy the floor's own
     * cell), else {@code fy + 1} (full block / TOP slab / non-standable → full-block behaviour). Used to derive
     * the FROM-foot for a step-0 phase plan (later steps read the previous waypoint's already-topY-aware Y).
     */
    private int feetYForFloor(int fx, int fy, int fz) {
        NavGridView grid = NavGridView.background((ServerLevel) Worlds.of(bot));
        if (grid.built(fx, fy, fz)) {
            long d = grid.descriptorAt(fx, fy, fz);
            if (NavBlock.isStandable(d)) return fy + (NavBlock.topY(d) == 16 ? 1 : 0);
        }
        return fy + 1;
    }

    /**
     * Reusable {@link SteerView} the follower re-points at the current segment each tick — start → target,
     * plus a one-step look-ahead. Converts FEET-cell {@link BlockPos}es (path waypoints; the step-0 start
     * is lifted into the same frame via {@code feetYForFloor}) to the controller's world frame: block
     * centre horizontally ({@code +0.5}), plus {@code +1.0} vertically — a UNIFORM feet-cell-plus-one
     * vertical frame. Controllers consume x/z absolutely but y only as segment DELTAS ({@code ty − sy},
     * e.g. {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Climb}) or as the tuned absolutes
     * the swim family's depth control was calibrated against; UNIFORMITY across the three cells is the
     * contract (a mixed floor/feet feed is exactly the step-0 bug fixed at the call site). Mutable +
     * reused, so no per-tick garbage; the MC {@link BlockPos} type stays on this side of the MC-free
     * {@link SteerView} seam.
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
    /**
     * The bot is ABOUT to edit {@code (x,y,z)} ({@code toAir} = a break, else a place) — announce the exact
     * mutation so the plan's own prescribed edit is not read back as the world diverging from it
     * ({@link PathPlan#expectOwnEdit}).
     *
     * <p>Call it IMMEDIATELY BEFORE the mutation, never after and never where an edit is merely requested:
     * the announcement is a one-shot slot consumed by the change it predicts, and {@code mine()} is re-issued
     * every tick while a break runs — only the completion changes the world. Announcing late would leave the
     * slot armed to forgive whatever changed next instead.
     */
    void expectOwnEdit(int x, int y, int z, boolean toAir) {
        if (pathPlan != null) {
            pathPlan.expectOwnEdit(x, y, z, toAir);
        }
    }

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
            expectOwnEdit(p.getX(), p.getY(), p.getZ(), true);  // announce BEFORE: our own prescribed break
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
                expectOwnEdit(p.getX(), p.getY(), p.getZ(), false); // announce BEFORE: prescribed place
                WorldEdits.placeBlock(level, p, block.defaultBlockState());
            } else {
                expectOwnEdit(p.getX(), p.getY(), p.getZ(), false);
                WorldEdits.placeBlock(level, p, bot.placeBlock()); // conjured, infinite supply
            }
        }
        // Door-set (DOORS P3): open/close each folded (hand-toggleable) door — the "right-click" edit in place of
        // smashing it. Re-validated on the LIVE door via doorOpenAt (NOT solidAt — an open door keeps a thin
        // collision box), so an already-correct door is left alone (no redundant swing/sound). This path is for an
        // UNCONVERTED (legacy steer) move that folds a door-set; today only Traverse folds one and it is converted
        // (its door-set rides the PhaseRunner Need.OPEN path), so this loop is a defensive backstop for parity.
        for (int i = 0; i < edits.doorSetCount(); i++) {
            long c = edits.doorSetAt(i);
            int dx = BlockPos.getX(c), dy = BlockPos.getY(c), dz = BlockPos.getZ(c);
            boolean open = edits.doorSetOpenAt(i);
            if (bot.doorOpenAt(dx, dy, dz) != open) {
                WorldEdits.setDoorOpen(level, new BlockPos(dx, dy, dz), bot, open);
            }
        }
    }

    // ---- Debug log formatting ----------------------------------------------------------------

    /**
     * The bot's <b>entry state</b> for a step — the sub-cell position and horizontal velocity the movement
     * layer's feasibility model ASSUMES but never measures. Emitted on the PLAN line (state at plan build) and
     * on the validity-envelope failure forensic (state at failure), so a step that "should have been possible"
     * can be checked against the conditions its admission actually relied on.
     *
     * <p><b>Why these four fields</b> (owner question, 2026-08-06 — "our 'started at the center of the block'
     * invariant may not have been met"). Every waypoint is a STAND cell, and the movement geometry is framed
     * from its CENTRE; but nothing on the existing PLAN line can distinguish a bot standing centred and up to
     * speed from one that arrived 0.4 blocks off-centre, or nearly stopped. {@code botFoot} is cell-granular
     * and {@code botY} is vertical only, so the two quantities that decide whether a jump reaches — where in
     * the cell the bot is, and how fast it is already moving — were both invisible.
     *
     * <p><b>The reference values to compare against</b> ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.ParkourEnvelope}, which derives the admitted gap
     * from a takeoff speed it assumes rather than reads): steady-state ground sprint is
     * {@code vInf = A_G/(1−QG) ≈ 0.281} blocks/tick on a normal floor, and the envelope prices the jump tick at
     * {@code vJump ≈ 0.481}. Acceleration from rest is {@code v ← v·0.546 + 0.1274}, i.e.
     * {@code 0.127 → 0.197 → 0.235 → 0.256 → …} — so a bot taking off after ONE approach step from a standstill
     * launches at roughly half the assumed speed. {@code speed} well under {@code 0.281} at plan time is
     * therefore the signature of an admission the entry state never earned; {@code offCentre} well off
     * {@code (0,0)} is the same story for the geometry.
     *
     * <p>Diagnostic only — never drives behavior, and only ever built under {@code Debug.VERBOSE} / a failure
     * log, so the allocation is off every hot path.
     */
    private String entryState() {
        double vx = bot.velX(), vz = bot.velZ();
        return String.format("botXZ=(%.3f,%.3f) offCentre=(%.3f,%.3f) vel=(%.3f,%.3f) speed=%.4f",
                bot.x(), bot.z(),
                bot.x() - (bot.footX() + 0.5), bot.z() - (bot.footZ() + 0.5),
                vx, vz, Math.sqrt(vx * vx + vz * vz));
    }

    /**
     * {@code /bot debug verbose}: the follower's <b>per-tick execution record</b> — one line EVERY tick, to the
     * server log only. Position to three decimals, velocity, every movement input actually held, the servo
     * branch that wrote them, and the live pose/medium: enough to reconstruct a step tick by tick offline
     * (see {@code internal_docs/follow_analysis.py}) rather than infer it from a summary.
     *
     * <p>It was previously deduped to one line per state change and mirrored to the owner's chat; both were
     * removed 2026-08-06 — see the comment on the emit below for the measurements that forced it.
     */
    private void logVerbose(Movement movement, BlockPos wp) {
        String move = movement.getClass().getSimpleName();
        String medium = bot.isInWater() ? "water" : (EntityState.onGround(bot) ? "ground" : "air");
        // The line prints the WAYPOINT it is driving toward; without the bot's own cell you cannot tell a
        // step that arrived from one still short of its target (the 2026-08-01 vine-Ascend re-jump: the
        // grounded tick was ambiguous between "on the landing stand" and "still in the from column"). So the
        // dedup key carries the bot's foot cell + grounded + phase too — still one line per STATE CHANGE,
        // never per tick, but now every cell/phase transition of a step is on the record.
        // SUB-CELL position (tenths) rides the key too: foot-cell granularity cannot tell "landed centred"
        // from "landed 0.4 past centre, hard against the far edge", and that is exactly the difference
        // between a cross-axis carry arrest that has room to work and one that starts already lost. Bounds
        // the extra volume at ~10 lines per cell crossed rather than one per tick.
        // SNEAK rides the key (2026-08-02 vine-Descend measurement): the wedge signature is a bot pinned at a
        // block boundary with input pressing and zero displacement, and the discriminator between "pressed into
        // a wall" and "sneak's vanilla ledge edge-guard is refusing the crossing" is exactly this input plus the
        // realized deltaMovement. Both are otherwise invisible — the commanded velocity is already logged, and
        // it looks identical in both cases.
        final boolean sneak = bot.isShiftKeyDown();
        // JUMP rides the line too (owner question, 2026-08-03). Vanilla's involuntary climb is
        // `(horizontalCollision || jumping) && onClimbable -> vy = +0.2`, so when a bot rises on a vine the
        // FIRST question is which of those two disjuncts fired — and only one of them was ever logged. The
        // measured signature that forced this: dm.y = +0.1176 on the in-vine ticks, which is exactly one tick
        // of decay off a +0.2 impulse ((0.2 - 0.08) x 0.98), against +0.0368 on the ticks out of the vine
        // (pure gravity). The climb was provably firing; the input that caused it was invisible.
        final boolean jump = bot.jumpHeld();
        // The FORWARD INPUT (owner, 2026-08-03). `vel`/`dm` are the POST-collision deltaMovement, so a bot
        // holding forward into a wall reads (0,0) and looks idle — which is exactly how this wedge hid. The
        // input is the load-bearing value: vanilla sets horizontalCollision from the ATTEMPTED movement, so a
        // blocked press still trips `(horizontalCollision || jumping) && onClimbable -> vy = +0.2` and climbs
        // the bot up the vine it is trying to descend. zza is the field setForward writes.
        final float fwd = bot.zza;
        /** The LATERAL input (xxa) the strafe seam writes — see the {@code str=} note on the log call. */
        final float strafe = bot.xxa;
        // YAW + the unit HEADING it implies (2026-08-03). fwd alone says "how hard", never "which way": the
        // ground steer (SteerControl.steerTowards) clamps its pursuit point to the segment END, so an
        // OVERSHOOT should re-face the target and walk BACK — but at the (58,171,254) wedge the telemetry
        // showed zero z displacement and residual +x while the geometry said the press was +z. Without the
        // facing there is no way to tell "re-faced and was blocked" from "never re-faced", and those two want
        // opposite fixes. Vanilla's forward vector for a yaw in degrees is (-sin, cos) over (x, z).
        final float yaw = bot.getYRot();
        final double hx = -Math.sin(Math.toRadians(yaw));
        final double hz = Math.cos(Math.toRadians(yaw));
        // NO DEDUP, NO CHAT (owner ruling 2026-08-06). The line used to collapse on a state key (waypoint, foot
        // cell, phase, position TENTHS, sneak/jump/hcol, 15-degree yaw) and also mirror a one-line summary to
        // the owner's chat.
        //
        // The dedup had to go: it skips ticks precisely when the bot is moving fastest (a tick that crosses two
        // tenths prints once), so a per-tick reconstruction is impossible exactly where it matters. Two
        // mechanism claims were derived from these lines on 2026-08-06 and BOTH were wrong, because consecutive
        // lines were silently non-consecutive ticks. The failures being chased are decided by margins of
        // ~0.001 blocks; a log that drops ticks cannot resolve them, and "log only the interesting steps" is
        // not available either — which step fails is not knowable in advance.
        //
        // The chat mirror had to go for the opposite reason: it carried strictly LESS than the log line (no
        // sub-block position, no inputs, no velocity) while covering the screen during the very runs being
        // measured. Everything it said is in latest.log, better.
        OrebitCommon.LOGGER.info(
                "[Orebit] exec {} wp{} -> {} ({}) phase={}/{} botFoot=({},{},{}) botY={} grounded={}"
                        + " climbable={} reached={} targetY={} x={} z={} vel=({},{})"
                        + " sneak={} jump={} fwd={} str={} src={} yaw={} head=({},{}) hcol={} dm=({},{},{})"
                        + " stance[{}]",
                move, waypointIndex, AllyBotEntity.compact(wp), medium,
                phaseRunner.phase(), phaseRunner.phases(),
                bot.footX(), bot.footY(), bot.footZ(), String.format("%.3f", bot.getY()),
                bot.grounded(), bot.onClimbable(),
                movement.reached(bot, wp.getX(), wp.getY(), wp.getZ()),
                String.format("%.2f", wp.getY() + 1.0),
                String.format("%.3f", bot.getX()), String.format("%.3f", bot.getZ()),
                String.format("%.4f", bot.velX()), String.format("%.4f", bot.velZ()),
                sneak, jump, String.format("%.2f", fwd),
                // STRAFE + the authoring BRANCH (2026-08-06). str: the lateral input added with the strafe
                // seam was invisible, so a cross-axis correction could not be told from no correction at all.
                // src: which servo wrote these inputs — the branches mean different things by the same yaw
                // (groundServo faces the velocity ERROR, arriveOnTarget faces the SEGMENT, the arrest faces
                // the cross correction), and reading a thrust direction as a heading is exactly how two wrong
                // mechanisms got published on 2026-08-06.
                String.format("%.2f", strafe),
                com.orebit.mod.pathfinding.blockpathfinder.SteerControl.lastDrive,
                String.format("%.1f", yaw), String.format("%.3f", hx), String.format("%.3f", hz),
                bot.horizontalCollision,
                String.format("%.4f", bot.getDeltaMovement().x),
                String.format("%.4f", bot.getDeltaMovement().y),
                String.format("%.4f", bot.getDeltaMovement().z),
                com.orebit.mod.pathfinding.blockpathfinder.SteerControl.lastStance);
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
            // Move NAME before the cell, matching the trace dump's PATH: line verbatim (2026-08-04) so a
            // live plan and the /bot trace meant to reproduce it can be diffed directly. Cells alone cannot
            // distinguish a cuboid MACRO from the ordinary move it replaces — the leading explanation for
            // the live-vs-trace waypoint-count skew — and that is exactly the comparison this line exists for.
            sb.append(path.movement(i) == null ? "?" : path.movement(i).getClass().getSimpleName())
                    .append(AllyBotEntity.compact(path.waypoint(i)));
        }
        return sb.append(']').toString();
    }
}
