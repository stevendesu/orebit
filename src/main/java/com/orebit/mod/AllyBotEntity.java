package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.PathStatus;
import com.orebit.mod.pathfinding.regionpathfinder.RegionEdgeBlacklist;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Replaceable;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The ally bot: a faked {@link net.minecraft.server.level.ServerPlayer} that follows its owner.
 *
 * <p><b>This is the first consumer of the nav grid (PRD Phase 4 / pathfinding milestone).</b> When
 * the owner is far enough away, the bot plans a block-level path to them with {@link BlockPathfinder}
 * over a {@link NavGridView} and steers along the waypoints, jumping over single-block rises — so it
 * routes around obstacles instead of walking straight into them. Where nav data isn't built yet (the
 * owner just outside the bot's loaded radius), it falls back to the old straight-line steer so it
 * never freezes.
 */
public class AllyBotEntity extends FakePlayerEntity {

    private final Player owner;

    // ---- chat-progress de-dup state (Debug.ENABLED): only post when one of these changes ---------
    private int lastChatStep = Integer.MIN_VALUE;
    private PathStatus lastChatStatus;
    private boolean lastChatPartial;
    private PathPlan.TargetKind lastChatKind;

    // ---- Follow / path-steering tuning -------------------------------------------------------
    /** Stop moving once this close to the owner (blocks, horizontal). */
    private static final double ARRIVE_DIST = 2.5;
    /**
     * Vertical arrival tolerance (blocks). Paired with {@link #ARRIVE_DIST} so "arrived" means close in
     * 3D — without it the bot treats being directly under a target as arrival (matches the block A*'s
     * ±2 vertical goal tolerance, so the follower agrees with the planner about reaching the goal cell).
     */
    private static final double ARRIVE_Y = 2.5;
    /**
     * BLOCK-level window-refresh interval (ticks) — the period at which the committed skeleton's current
     * window re-searches its block path even without a region-boundary commit, so a terrain edit under the
     * window is picked up within a second or two. This is NOT a skeleton rebuild (the region skeleton is
     * committed until the goal changes region or a hop is proven BLOCKED — see {@link #driveToward}); kept
     * long so a stationary bot re-searches rarely, which also keeps the debug path stable.
     */
    private static final int REPLAN_TICKS = 40;
    /** Below this squared horizontal speed while trying to move, treat the bot as stuck → jump. */
    private static final double STUCK_SPEED_SQR = 0.0016;
    /** Consecutive stuck ticks before the diagnostic dumps the surrounding blocks (≈1s). */
    private static final int STUCK_DUMP_TICKS = 20;
    /**
     * Upward {@code deltaMovement.y} (blocks/tick) the swim follower drives when the planned cell is ABOVE the
     * bot. Deliberately strong + fixed (not the proportional descent rate): a proportional climb decays to
     * ~nothing near the target and the water-surface buoyancy equilibrium swallows a small velocity outright
     * (measured stall: dy=0.16 → vy=0.047 → the bot floated pinned at the waterline, never clearing the last
     * cell). 0.30 reliably breaks the surface float and clears a one-block lip; descent stays gentle.
     */
    private static final double SWIM_CLIMB_VY = 0.30;

    /**
     * Mid-air horizontal homing gain for a {@code Fall} step. The planner models a fall as a straight
     * vertical drop ({@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} scans the cardinal
     * column straight down), but a bot that walks off a ledge at full speed carries horizontal momentum and
     * arcs — overshooting the planned landing cell by a block or more, so it "slips" off its route. Minecraft
     * grants aerial steering authority (a player drop-controls off ladders the same way), so we don't model
     * the parabola; instead each airborne tick we set horizontal velocity toward the landing column centre at
     * {@code (offset × GAIN)} clamped to {@link #FALL_HOMING_MAX}. This both pulls the bot over the target
     * column and overwrites (bleeds) the overshoot momentum, so the real drop matches the planner's vertical
     * assumption. As the bot nears centre the offset → 0, so velocity decays smoothly with no over-correction.
     */
    private static final double FALL_HOMING_GAIN = 0.5;
    /** Per-tick horizontal speed cap while drop-controlling a {@code Fall} (blocks/tick) — see {@link #FALL_HOMING_GAIN}. */
    private static final double FALL_HOMING_MAX = 0.15;

    /**
     * The bot's planner capabilities + throwaway block now come from the owner config (PRD §10 Phase 1a):
     * {@link #caps()} returns the {@link BotCaps} derived from {@code config/orebit.properties}
     * (break/place toggles, mining-hardness cap, A* node cap + greedy weight) and {@link #placeBlock()}
     * the configured conjured block. Both are read at the point of use (in {@link #replan}/{@link #traceTo}/
     * {@link #applyEdits}) from the live {@link ConfigLoader} cache, so a {@code /bot config reload} takes
     * effect on the next plan with no per-tick cost — the cached values are plain field reads, never on the
     * A* hot path. Out of the box the config defaults reproduce the historical {@code BotCaps.BREAK_PLACE}
     * + cobblestone behaviour exactly, so nothing changes until the owner edits the file.
     */
    private BotCaps caps() {
        return ConfigLoader.botCaps();
    }

    /** The throwaway {@link BlockState} the bot places when bridging/footing — the configured conjured block. */
    private BlockState placeBlock() {
        return ConfigLoader.config().conjuredBlockState();
    }

    /**
     * The per-replan inventory feasibility snapshot (PRD §10 Phase 1b/1c): read the bot's REAL inventory
     * ONCE here (cold, before the search) through the {@link BotInventory} adapter into plain primitives the
     * block-A* gates consult on the hot path (carried placeable-block count → placement cap; best carried
     * tool per category → mining-feasibility gate + the resident tick table 1d reads). Built fresh each
     * replan so it reflects the bot's current items; passed into {@link PathPlan} and threaded to every
     * windowed search. Returns {@code null} (caps-only gating) only if the mining table isn't built yet.
     * The {@code consumesBlocks} flag comes from {@code placement.consumesBlocks}; the conjured-block branch
     * (infinite supply) is unaffected. Never on a per-tick / per-node path — one scan per whole replan.
     */
    private MovementContext.InventoryView inventoryFeasibility() {
        Config cfg = ConfigLoader.config();
        return new BotInventory(this).feasibility(
                caps(), cfg.consumesBlocks(), cfg.conjuredBlockState(), cfg.removalCostWeight(),
                cfg.placeBaseCost());
    }

    /**
     * What the bot is currently trying to do, set by the {@code /bot} commands (defaults to
     * {@link Mode#FOLLOW} so a freshly spawned bot behaves as before — auto-follow the owner):
     * <ul>
     *   <li>{@link Mode#FOLLOW} — continuously path to the owner (the original behaviour).
     *   <li>{@link Mode#STAY} — hold position; don't path anywhere.
     *   <li>{@link Mode#COME} — path once to a fixed summon cell, then drop to {@link Mode#STAY}.
     * </ul>
     */
    public enum Mode { FOLLOW, STAY, COME }

    private Mode mode = Mode.FOLLOW;
    private BlockPos comeTarget;    // fixed summon cell (owner's feet block at /bot come time)

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
    private int blockRefreshTicks; // countdown to the next BLOCK-level window refresh (NOT a skeleton rebuild)
    private int stuckTicks;         // consecutive ticks grinding in place; drives the stuck diagnostic
    private int lastEditedIndex = -1; // last step whose break/place edits were applied (apply once per step)
    private boolean loggedHasPath;  // dedupe the path/no-path diagnostic so it logs only on change
    private boolean loggedPlanError; // log a two-tier replan exception only once (then degrade silently)

    // ---- region-tier online repair (the "recover when stuck" half of the stuck arc) ------------------
    /** Region crossings proven unrealizable for this bot's caps; the region A* routes around them. Accumulates
     *  across the stuck episode, cleared when the goal cell changes (see {@link #driveToward}). */
    private final RegionEdgeBlacklist regionBlacklist = new RegionEdgeBlacklist();
    /** Reused 2-long scratch for {@link PathPlan#blockedHop} (no per-replan alloc). */
    private final long[] hopScratch = new long[2];
    /** Set once the region tier can find NO route avoiding the blacklist — the bot holds + tells the owner. */
    private boolean navGaveUp;

    public AllyBotEntity(MinecraftServer server, ServerLevel world, GameProfile profile, Player owner) {
        super(server, world, profile);
        this.owner = owner;
    }

    public void lookAtPlayer(Player player) {
        double dx = player.getX() - this.getX();
        double dy = (player.getEyeY()) - this.getEyeY();
        double dz = player.getZ() - this.getZ();

        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (Math.toDegrees(-Math.atan2(dy, distXZ)));

        this.setYHeadRot(yaw);    // where the head turns
        this.setYRot(yaw);        // body rotation
        this.setYBodyRot(yaw);    // optional for full facing
        this.setXRot(pitch);      // up/down looking
    }

    // ---- Command-driven mode control (the /bot commands call these) --------------------------

    /** The bot's current behaviour mode. */
    public Mode mode() {
        return mode;
    }

    /** Switch behaviour mode (e.g. {@code /bot follow}, {@code /bot stay}); clears any active path. */
    public void setMode(Mode mode) {
        this.mode = mode;
        this.comeTarget = null;
        clearPlan();
    }

    /** {@code /bot come}: path once to {@code summonCell} (the caller's feet block), then hold there. */
    public void comeTo(BlockPos summonCell) {
        this.mode = Mode.COME;
        this.comeTarget = summonCell.immutable();
        clearPlan();
    }

    /**
     * Drop the active two-tier driver and its exposed block plan (HPA-IMPLEMENTATION.md §10): a mode change
     * or a STAY hold invalidates the current goal, so the {@link PathPlan} built for it must not be ticked
     * again. The next {@link #driveToward} sees a null {@link #pathPlan} and rebuilds for the new goal.
     */
    private void clearPlan() {
        this.pathPlan = null;
        this.path = null;
        this.lastBlockPlanRef = null;
    }

    @Override
    public void tick() {
        // Drive the player tick directly: baseTick() + a single aiStep() with our inputs set first.
        // (The previous super.tick() already ran aiStep(), so steering followed by a second aiStep()
        // double-stepped physics each tick — see HANDOFF. baseTick/aiStep are stable across versions.)
        this.baseTick();
        this.setNoGravity(false);

        if (owner == this) {
            this.aiStep();
            return;
        }

        this.xxa = 0.0f;
        this.yya = this.isInWater() ? 1.0f : 0.0f;
        this.setJumping(false);   // discrete land jumps use jumpFromGround(); swim following re-enables this
        this.setSprinting(false); // ditto — buoyancy + sprint-swim are refined per-step in steerAlongPath

        switch (mode) {
            case STAY -> holdPosition();
            case COME -> {
                // Summon to a fixed cell; once there, settle into STAY (distinct from FOLLOW, which
                // would keep chasing). comeTarget can't be null in COME, but guard defensively.
                if (comeTarget == null) { setMode(Mode.STAY); holdPosition(); break; }
                double tx = comeTarget.getX() + 0.5, ty = comeTarget.getY(), tz = comeTarget.getZ() + 0.5;
                if (driveToward(tx, ty, tz, comeTarget.below())) setMode(Mode.STAY); // arrived
            }
            default -> // FOLLOW
                driveToward(owner.getX(), owner.getY(), owner.getZ(), owner.blockPosition().below());
        }

        this.aiStep();
    }

    /** STAY: stop in place and face the owner. */
    private void holdPosition() {
        this.zza = 0.0f;
        clearPlan();
        lookAtPlayer(owner);
    }

    /**
     * Path toward {@code (tx,ty,tz)} (goal floor cell {@code goalFloor}), steering along the plan and
     * falling back to a straight-line steer off-grid. Returns {@code true} once within
     * {@link #ARRIVE_DIST} horizontally <i>and</i> {@link #ARRIVE_Y} vertically (the caller decides what
     * arrival means for its mode).
     */
    private boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor) {
        double dx = tx - this.getX();
        double dy = ty - this.getY();
        double dz = tz - this.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        // Arrived only when close in 3D. Horizontal proximity alone would let the bot stop directly
        // BELOW the target (it walks under a sky platform / the top of a staircase and quits) — it must
        // also match the target's height, which is what makes it actually climb to reach you.
        if (distXZ <= ARRIVE_DIST && Math.abs(dy) <= ARRIVE_Y) {
            this.zza = 0.0f;
            clearPlan();
            lookAtPlayer(owner);
            return true;
        }

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
        boolean newRegionGoal = pathPlan == null || !pathPlan.sameGoalRegion(goalFloor);
        boolean skeletonInvalid = pathPlan != null && pathPlan.status() == PathStatus.BLOCKED;
        if (pathPlan == null || newRegionGoal || skeletonInvalid) {
            if (newRegionGoal) {
                // New destination region → the learned dead-ends no longer apply; start the repair fresh.
                regionBlacklist.clear();
                navGaveUp = false;
            }
            replan(goalFloor);
            blockRefreshTicks = REPLAN_TICKS;
        } else {
            // Per-tick driver hook (HPA-IMPLEMENTATION.md §10): report the bot's floor cell so the sliding
            // window can COMMIT into the next region (the wiggle rule) and replan THAT window's block path.
            pathPlan.onBotMoved(this.blockPosition().below());
            // Block-level refresh (NOT a skeleton rebuild): when the current block path is consumed but the
            // route isn't COMPLETE, re-search the window toward the SAME target so the bot keeps inching along
            // a committed partial; also do it periodically so a terrain edit under the window is picked up.
            boolean consumed = path != null && waypointIndex >= path.size() && !pathPlan.isComplete();
            if (consumed || --blockRefreshTicks <= 0) {
                pathPlan.refreshWindow();
                blockRefreshTicks = REPLAN_TICKS;
            }
            // Refresh `path` to whatever block plan the driver now exposes; when it swapped in a NEW
            // BlockPathPlan (window advanced / re-searched), restart the follower at its head so
            // steerAlongPath/applyEdits don't index a stale waypoint/edit cursor.
            BlockPathPlan now = pathPlan.currentBlockPlan();
            if (now != lastBlockPlanRef) {
                this.path = now;
                this.lastBlockPlanRef = now;
                this.waypointIndex = 0;
                this.lastEditedIndex = -1;
            }
        }
        // Region repair, every tick (cheap status check): a BLOCKED window — wherever it surfaced — gets its
        // dead skeleton hop blacklisted now, so the NEXT tick's `skeletonInvalid` reroute already avoids it
        // (one useful region replan, not a wasted same-skeleton rebuild first). Give-up lives here too.
        repairStep();

        if (path != null && waypointIndex < path.size()) {
            steerAlongPath();
        } else if (navGaveUp || (pathPlan != null && pathPlan.status() == PathStatus.BLOCKED)) {
            // HOLD when we either gave up OR a committed-skeleton window is momentarily BLOCKED (the region
            // repair reroutes next tick). Do NOT straight-line here: that ignores the planner and could walk
            // the bot off the very ledge the guard refused (the irreversible yeet). Straight-line stays only
            // for the genuinely off-grid case below (no plan / no built ground under the owner).
            this.zza = 0.0f;
        } else {
            steerStraight(dx, dz); // no nav data here — fall back to straight-line follow
        }

        if (Debug.ENABLED && path != null) {
            PathDebugRenderer.render((ServerLevel) Worlds.of(this), path, waypointIndex,
                    this.getX(), this.getY(), this.getZ());
        }
        if (Debug.ENABLED && pathPlan != null) {
            // Macro overlay: region skeleton + portal cells, to SEE the HPA plan vs the local block path
            // (and catch buried portal targets — the §6 bug — as blue particles inside rock).
            PathDebugRenderer.renderSkeleton((ServerLevel) Worlds.of(this), pathPlan);
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
        if (!Debug.ENABLED || pathPlan == null || owner == null) {
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
            default:       how = ""; break; // GOAL / PORTAL — the normal cases, no annotation
        }
        final String msg;
        switch (status) {
            case COMPLETE: msg = "arrived"; break;
            case BLOCKED:  msg = "blocked — no path to " + where; break;
            case FAILED:   msg = "failed — no route"; break;
            default:       msg = (partial ? "partial path toward " : "moving to ") + where + how; break;
        }
        chat("[bot] " + msg);
    }

    /** Send one line to the owner's chat (reusing the version-portable {@link CommandFeedback}); swallow any
     *  error so debug chatter can never break the server tick. */
    private void chat(String message) {
        try {
            CommandFeedback.sendTo(owner, message);
        } catch (Throwable ignored) {
            // never let progress chatter crash the tick
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
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos startFloor = this.blockPosition().below();

        // The two-tier driver is a large, freshly-built subsystem (region A* + leaf mini-pathfinds). A bug
        // in it must NOT crash the server tick — degrade to "no plan" (which falls back to the visible
        // straight-line steer below), log once, and keep the game playable. Remove the guard once the region
        // tier is hardened.
        try {
            this.pathPlan = new PathPlan(level, RegionGrid.of(level), startFloor, goalFloor, caps(),
                    inventoryFeasibility(), regionBlacklist);
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

        boolean hasPath = path != null && path.size() > 0;
        if (Debug.ENABLED) {
            // Verbose per-plan trace while debugging: the full waypoint list shows exactly which cells
            // A* produced (e.g. whether a stacked staircase step is present or skipped).
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] plan: {} wp cost={} start={} goal={} path={}",
                        path.size(), path.cost(), compact(startFloor), compact(goalFloor), waypointsString());
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
        if (status == PathStatus.BLOCKED) {
            // The window's block search couldn't leave the bot's region toward the next skeleton step.
            if (pathPlan.blockedHop(hopScratch)) {
                regionBlacklist.add(hopScratch[0], hopScratch[1]); // dedups; next replan reroutes
            } else {
                giveUp(); // BLOCKED in the goal's own region with no onward hop — can't reach the goal cell
            }
        } else if (status == PathStatus.FAILED && regionBlacklist.size() > 0) {
            // We blacklisted our way to "no route remains" — every crossing the bot trusts is exhausted.
            giveUp();
        }
    }

    private void giveUp() {
        navGaveUp = true;
        chat("I can't find a way to reach you.");
    }

    /**
     * One-shot diagnostic ({@code /bot trace}): run the <b>full two-tier HPA* path</b> the way {@code /bot
     * come} does, then trace the <b>first window's block-A*</b> to a file — <i>with</i> its HPA*-derived
     * corridor, so cuboids, macro-ops, and the goal-forced-cost premium are all ACTIVE. (The old trace ran a
     * raw cornerless block-A*, which silently disables that whole layer — {@code CuboidExtractor} invalidates
     * when {@code bound == null}, so macros and the premium never engage — and therefore could never
     * reproduce, or exonerate, a corridor'd failure.)
     *
     * <p>It builds a {@link PathPlan} (skeleton + first window) with tracing OFF — so the region tier's
     * leaf-cost mini-pathfinds don't pollute the dump — then reads that window's target + corridor and
     * re-runs the <b>same</b> windowed {@link BlockPathfinder#findPath} once with {@link BlockPathfinder#TRACE}
     * on. Puts the bot in {@code STAY} first so it stops auto-replanning. Slow (file I/O per node on the tick
     * thread) — run once and review offline. Falls back to a raw cornerless trace (clearly labelled) only when
     * HPA* produces no window (no built ground at the start).
     */
    public String traceTo(BlockPos goalFloor) {
        setMode(Mode.STAY); // stop the per-tick replan/flood; the trace is a standalone one-shot search
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos startFloor = this.blockPosition().below();
        final BotCaps caps = caps(); // snapshot the configured caps once for this whole trace
        final MovementContext.InventoryView inv = inventoryFeasibility(); // the bot's real-inventory cap

        // Build the two-tier plan exactly as /bot come does (TRACE off → the HPA* leaf-cost searches stay out
        // of the dump); the first window's target + corridor are what we then trace.
        BlockPos target = null;
        RegionBound corridor = null;
        String skeletonDump = null;
        try {
            PathPlan plan = new PathPlan(level, RegionGrid.of(level), startFloor, goalFloor, caps, inv);
            target = plan.currentWindowTarget();
            corridor = plan.currentCorridor();
            skeletonDump = plan.describeSkeleton(); // the HPA region plan that produced this window target
        } catch (Throwable t) {
            return "trace FAILED: two-tier plan threw " + t;
        }

        java.io.File file = new java.io.File("orebit-trace.txt"); // run dir
        boolean savedTiming = BlockPathfinder.LOG_TIMING;
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            final boolean haveWindow = target != null && corridor != null;
            if (haveWindow) {
                w.write("Orebit A* trace  start=" + startFloor + "  goal=" + goalFloor
                        + "  window target=" + target + "  corridor=" + corridor + "  caps=" + caps
                        + "  (HPA* first window — corridor + cuboids + macro-ops + goal premium ACTIVE)\n");
            } else {
                w.write("Orebit A* trace  start=" + startFloor + "  goal=" + goalFloor + "  caps=" + caps
                        + "  (HPA* produced NO window — falling back to raw block-A*, no corridor)\n");
            }
            if (skeletonDump != null) {
                w.write("\n" + skeletonDump + "\n\n"); // the region skeleton behind this window target
            }
            w.write("legend: 'E <seq> <x> <y> <z> g=<g> f=<f> via=<move|start>' = one expansion (pop), in"
                    + " order;  '  C <move> <x> <y> <z> cost=<c> <OK|worse|corridor>' = a candidate it"
                    + " emitted (OK=relaxed onto the open set, worse=not an improvement).\n\n");
            BlockPathfinder.TRACE_OUT = w;
            BlockPathfinder.TRACE = true;
            BlockPathfinder.LOG_TIMING = false; // the trace IS the record; skip the one-line summary
            // Match the live driver: UNCONFINED search (confineBound=null) with the cuboid GROWTH cap
            // (cuboidBound=corridor) so macros/forced-cost are active but the trace isn't walled to a corridor.
            BlockPathPlan plan = haveWindow
                    ? BlockPathfinder.findPath(new NavGridView(level), startFloor, target, caps, null, corridor, inv)
                    : BlockPathfinder.findPath(new NavGridView(level), startFloor, goalFloor, caps, null, null, inv);
            BlockPathfinder.TRACE = false;
            w.write("\nRESULT: " + (plan == null ? "FAIL (null)" : plan.size() + "wp cost=" + plan.cost())
                    + "\n");
        } catch (java.io.IOException e) {
            return "trace FAILED: " + e;
        } finally {
            BlockPathfinder.TRACE = false;
            BlockPathfinder.TRACE_OUT = null;
            BlockPathfinder.LOG_TIMING = savedTiming;
        }
        return file.getAbsolutePath();
    }

    /** Steer toward the current waypoint, advancing by block occupancy and jumping over rises. */
    private void steerAlongPath() {
        // Advance to the furthest waypoint whose block the bot's feet currently occupy. Waypoints ARE
        // blocks and so are the bot's feet ({@link #blockPosition()}), so this is block-exact — no
        // distance epsilon. Because the comparison includes Y, the feet block can only equal the next
        // step once the bot has actually climbed onto it, so a stacked staircase can't be skipped;
        // scanning from the end also absorbs any overshoot.
        BlockPos foot = this.blockPosition();
        for (int j = path.size() - 1; j >= waypointIndex; j--) {
            BlockPos w = path.waypoint(j);
            boolean swimStep = path.movement(j) == MovementRegistry.SWIM
                    || path.movement(j) == MovementRegistry.SPRINT_SWIM;
            // While swimming the bot's Y bobs with buoyancy, so an exact 3-D block match can stall the
            // cursor; for a swim step match horizontally with a ±1 vertical tolerance instead.
            boolean reached = swimStep
                    ? (foot.getX() == w.getX() && foot.getZ() == w.getZ() && Math.abs(foot.getY() - w.getY()) <= 1)
                    : foot.equals(w);
            if (reached) {
                waypointIndex = j + 1;
                break;
            }
        }
        if (waypointIndex >= path.size()) {
            this.zza = 0.0f;
            return;
        }

        // Apply this step's folded break/place edits once, the moment it becomes the current step: the
        // bot is standing at the previous waypoint and the cells to clear/fill are right in front, so the
        // route is made physically walkable before the bot tries to move into it. PILLAR is the exception:
        // its footing is placed in the bot's OWN feet cell, so it must wait until the bot has jumped clear
        // of that cell (airborne) — otherwise the block would be set inside the bot.
        boolean pillar = path.movement(waypointIndex) == MovementRegistry.PILLAR;
        StepEdits edits = path.edits(waypointIndex);
        if (edits != null && waypointIndex != lastEditedIndex
                && (!pillar || !EntityState.onGround(this))) {
            lastEditedIndex = waypointIndex;
            applyEdits(edits);
        }

        BlockPos wp = path.waypoint(waypointIndex);
        double ddx = (wp.getX() + 0.5) - this.getX();
        double ddz = (wp.getZ() + 0.5) - this.getZ();

        float yaw = (float) (Math.toDegrees(Math.atan2(-ddx, ddz)));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0f;

        // Swim vertical control — by DIRECT velocity, not the `yya` input. In water the movement input is
        // weak: getInputVector NORMALIZES (xxa,yya,zza) and scales by the small water speed, so with zza=1 a
        // yya=-1 yields only ~-0.02/tick downward — and prone sprint-swim is near-neutral buoyancy, so that
        // tiny push is cancelled and the bot just hovers (it never descended to a submerged target). Instead
        // drive deltaMovement.y straight toward the target depth, clamped to a swim rate: zza+yaw still propel
        // horizontally, and aiStep's drag leaves the set velocity essentially intact, so the bot reliably
        // sinks/rises to track the planned cell. Normal (surface) swim uses the same tracking — it converges
        // on the surface cell and self-corrects against the gentle sink.
        boolean normalSwim = path.movement(waypointIndex) == MovementRegistry.SWIM;
        boolean sprintSwim = path.movement(waypointIndex) == MovementRegistry.SPRINT_SWIM;
        if (normalSwim || sprintSwim) {
            this.setSprinting(sprintSwim); // submerged + sprinting → vanilla prone sprint-swim (5.612 b/s)
            this.setJumping(false);
            this.yya = 0.0f;
            double dyTarget = wp.getY() - this.getY();
            // Vertical authority is ASYMMETRIC. A proportional rate decays to ~nothing as it nears the target,
            // and at the water SURFACE the buoyancy float-equilibrium swallows a small velocity entirely —
            // observed stall: dy=0.16 → vy=0.047 → botY pinned, the bot never climbs the last bit onto the
            // next cell / out of the water. So drive a STRONG fixed climb whenever we need to go up (enough to
            // break the surface float and clear a block lip); only the DESCENT is gently proportional, and a
            // tiny dead-band holds depth without jitter.
            double vy;
            if (dyTarget > 0.05) {
                vy = SWIM_CLIMB_VY;                          // climb hard (surface break / clear the lip)
            } else if (dyTarget < -0.05) {
                vy = Math.max(-0.20, dyTarget * 0.3);        // descend gently (proportional)
            } else {
                vy = 0.0;                                    // at depth — hold
            }
            Vec3 dm = this.getDeltaMovement();
            this.setDeltaMovement(dm.x, vy, dm.z);
            if (Debug.ENABLED) {
                OrebitCommon.LOGGER.info("[Orebit] swim {} target={} botY={} dy={} inWater={} swimming={} vy->{}",
                        sprintSwim ? "SPRINT" : "normal", compact(wp), String.format("%.2f", this.getY()),
                        String.format("%.2f", dyTarget), this.isInWater(), this.isSwimming(),
                        String.format("%.3f", vy));
            }
        }

        // Fall control — drop STRAIGHT down the planned column instead of arcing off the lip. The planner
        // models a multi-block Fall as a vertical drop, but walking off an edge at full speed carries
        // horizontal momentum, so the bot overshoots the planned landing cell and slips off-route. We have
        // full aerial authority (we drive deltaMovement directly, as for swim), so while airborne we home
        // the bot onto the landing column centre at a clamped rate — this pulls it over the target column and
        // bleeds the overshoot. zza is zeroed so the forward walk input doesn't re-add horizontal speed.
        boolean fall = path.movement(waypointIndex) == MovementRegistry.FALL;
        if (fall && !EntityState.onGround(this)) {
            double cx = (wp.getX() + 0.5) - this.getX();
            double cz = (wp.getZ() + 0.5) - this.getZ();
            double vx = Math.max(-FALL_HOMING_MAX, Math.min(FALL_HOMING_MAX, cx * FALL_HOMING_GAIN));
            double vz = Math.max(-FALL_HOMING_MAX, Math.min(FALL_HOMING_MAX, cz * FALL_HOMING_GAIN));
            Vec3 dm = this.getDeltaMovement();
            this.setDeltaMovement(vx, dm.y, vz);
            this.zza = 0.0f;
        }

        // Jump only when the step the planner chose is an Ascend (a real jump-up onto a full block,
        // head-clearance already verified). A Traverse step-assist (slab/stair/snow lip) is auto-stepped
        // by vanilla movement and must NOT jump, or the bot launches over low steps. Stuck is the
        // backstop for physical hitches the planner can't see.
        // Jump on an Ascend (jump-up onto a full block) or a Pillar (jump-and-place straight up); both
        // need the bot off the ground. A Traverse step-assist must NOT jump (vanilla auto-steps it).
        boolean ascend = path.movement(waypointIndex) == MovementRegistry.ASCEND;
        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR;
        if ((ascend || pillar || stuck) && EntityState.onGround(this)) {
            this.jumpFromGround();
        }

        // Stuck-on-a-step diagnostic: when the bot grinds in place against the current target for a
        // while, dump the ACTUAL block solidity (live world, not the coarse grid) for the bot's column
        // and the target's column — this reveals jump head-clearance, which the floor-centric 2-bit
        // grid can't represent (a CLEAR cell guarantees only 2 air blocks above the floor; a jump-up
        // needs ~3). Logged once per stuck episode.
        if (stuck && EntityState.onGround(this)) {
            if (++stuckTicks == STUCK_DUMP_TICKS && Debug.ENABLED) dumpStuck(wp);
        } else {
            stuckTicks = 0;
        }
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
        ServerLevel level = (ServerLevel) Worlds.of(this);
        Config cfg = ConfigLoader.config();
        BotInventory inv = (cfg.consumesTools() || cfg.consumesBlocks()) ? new BotInventory(this) : null;

        for (int i = 0; i < edits.breakCount(); i++) {
            BlockPos p = edits.breakPos(i);
            BlockState target = level.getBlockState(p);
            if (target.isAir()) continue;
            if (inv != null && cfg.consumesTools()) inv.damageBestTool(target); // wear the tool one use
            WorldEdits.breakBlock(level, p);
        }
        for (int i = 0; i < edits.placeCount(); i++) {
            BlockPos p = edits.placePos(i);
            if (!Replaceable.isReplaceable(level.getBlockState(p))) continue;
            if (inv != null && cfg.consumesBlocks()) {
                Block block = inv.consumeOnePlaceable();
                if (block == null) continue; // out of blocks — skip; replan nets it
                WorldEdits.placeBlock(level, p, block.defaultBlockState());
            } else {
                WorldEdits.placeBlock(level, p, placeBlock()); // conjured, infinite supply
            }
        }
    }

    // ---- Debug log formatting ----------------------------------------------------------------

    private void dumpStuck(BlockPos wp) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos foot = this.blockPosition();
        OrebitCommon.LOGGER.info("[Orebit] STUCK pos=({},{},{}) foot={} target={} | "
                        + "botCol[floor,feet,head,+1,+2]={} targetCol[floor,feet,head,+1,+2]={} (S=solid .=air)",
                String.format("%.2f", getX()), String.format("%.2f", getY()), String.format("%.2f", getZ()),
                compact(foot), compact(wp), column(level, foot), column(level, wp));
    }

    /** Solidity of 5 cells from the floor (one below the feet block) up through head+2, as S/. */
    private static String column(ServerLevel level, BlockPos feetBlock) {
        StringBuilder sb = new StringBuilder();
        for (int dy = -1; dy <= 3; dy++) {
            sb.append(level.getBlockState(feetBlock.above(dy)).isAir() ? '.' : 'S');
        }
        return sb.toString();
    }

    private static String compact(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private String waypointsString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(compact(path.waypoint(i)));
        }
        return sb.append(']').toString();
    }

    /** Original straight-at-the-owner steer, used when nav data for the route isn't built yet. */
    private void steerStraight(double dx, double dz) {
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0f;

        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR;
        if (stuck && EntityState.onGround(this)) {
            this.jumpFromGround();
        }
    }
}
