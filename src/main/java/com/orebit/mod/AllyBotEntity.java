package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.PathStatus;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.MoveReport;
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
public class AllyBotEntity extends FakePlayerEntity implements BotSteering {

    private final Player owner;

    // ---- chat-progress de-dup state (Debug.ENABLED): only post when one of these changes ---------
    private int lastChatStep = Integer.MIN_VALUE;
    private PathStatus lastChatStatus;
    private boolean lastChatPartial;
    private PathPlan.TargetKind lastChatKind;
    /** Last (waypoint|movement|medium) announced by {@code /bot debug verbose} — dedups it to one line per change. */
    private String lastVerbose;

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
    /** Ticks between cascade BLOCKED-repair attempts ({@link #repairStep}) — keeps the heavy re-search off the
     *  per-tick path while the bot follows the last repaired route. */
    private static final int REPAIR_COOLDOWN = 10;
    /** Below this squared speed while trying to move, treat the bot as stuck (horizontal on the ground →
     *  jump; full-3D off the ground/underwater → a stall the jump can't fix → recover). */
    private static final double STUCK_SPEED_SQR = 0.0016;
    /** Consecutive stuck ticks before the diagnostic dumps the surrounding blocks (≈1s). */
    private static final int STUCK_DUMP_TICKS = 20;
    /** Cross-track distance (blocks) off the planned segment that counts as a genuine slip off the line. */
    private static final double OFF_TRACK_DIST = 1.6;
    /** Consecutive off-track ticks before forcing a re-search from the bot's actual cell (≈0.6s). */
    private static final int OFF_TRACK_TICKS = 12;
    /** Consecutive airborne/underwater stall ticks (no 3-D progress, jump useless) before recovering (≈2s). */
    private static final int STALL_TICKS = 40;
    /** Ticks to wait after a recovery before the off-track/stall detector can fire again (throttle). */
    private static final int RECOVER_COOLDOWN = 20;
    /** Dead-band (blocks) under the planned depth within which the water-rise rule stops holding jump — keeps
     *  a surfaced bot from chattering jump on/off right at its target depth. */
    private static final double WATER_RISE_DEADBAND = 0.2;
    /**
     * How far (blocks) below the planned depth the follower rides a prone-mode move ({@link
     * Movement#keepsSubmerged}). The prone sprint-swim hitbox is only ~0.6 tall, so at a surface-level planned
     * depth the {@link #WATER_RISE_DEADBAND} up-slack would float the whole hitbox clear of the water and drop
     * the pose. Sinking the target ~0.5 keeps the hitbox wet (so vanilla's {@code isInWater()} continuation
     * rule holds) while staying under {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Swim#REACHED_Y}
     * (0.6) so the swim cursor still advances. Standing water moves ride at the plain depth (bias 0).
     */
    private static final double SUBMERGE_BIAS = 0.5;

    /**
     * The bot's movement mode to seed the planner's start node with — its REAL pose: vanilla {@code
     * isSwimming()} (the prone {@code Pose.SWIMMING}) ⇒ {@link MovementContext#MODE_PRONE}, so a replan that
     * fires mid-sprint-swim keeps the prone state instead of re-deriving STANDING from a buoyancy bob and
     * re-initiating (or, in genuine 1-deep water, getting stuck unable to re-initiate). When the bot is not
     * swimming, {@link BlockPathfinder#MODE_AUTO} lets the search derive the mode from the start geometry.
     */
    private int currentStartMode() {
        return this.isSwimming() ? MovementContext.MODE_PRONE : BlockPathfinder.MODE_AUTO;
    }

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

    // ---- closed-loop trajectory tracking (the follower steers along the planned LINE, not a point) ----
    /** Floor cell the current block plan/window started from — the first segment's start (waypoints are
     *  start-exclusive, so the segment before waypoint 0 begins here). Refreshed on replan / window swap. */
    private BlockPos planStartFloor;
    /** Reusable {@link SteerView} re-pointed at the current segment each tick (no per-tick allocation). */
    private final SegmentCursor cursor = new SegmentCursor();
    private int offTrackTicks;  // consecutive ticks the bot is off the planned segment (slip detection)
    private int stallTicks;     // consecutive airborne/underwater no-progress ticks (a jump can't fix these)
    private int recoverCooldown; // throttle between forced re-searches after an off-track/stall recovery

    // ---- swim-pose transition diagnostic (Debug.VERBOSE) — see logSwimTransition() -------------------
    // Vanilla drops the prone Pose.SWIMMING the instant a tick sees !(isSprinting() && isInWater()), and can
    // only re-enter it while isUnderWater() (eyes submerged). To find WHICH link breaks mid-crossing we snapshot
    // the two per-tick inputs the follower controls (was a steer run? was buoyancy-jump held?) and dump them the
    // moment isSwimming() flips, alongside the vanilla state — so PRONE->STAND names its own cause.
    private boolean wasSwimming;           // isSwimming() at the end of the previous tick (edge detector)
    private boolean steeredThisTick;       // a Movement.steer ran (false on a consumed-window early return → no sprint re-assert)
    private boolean heldWaterJumpThisTick; // the water-rise rule held jump this tick (buoyancy → possible surface breach)
    private String lastSteerMove = "-";    // simple name of the Movement whose steer ran this tick

    // ---- region-tier online repair (the "recover when stuck" half of the stuck arc) ------------------
    /**
     * Throttle for the cascade's in-place BLOCKED repair ({@link #repairStep}): ticks remaining before the next
     * {@link PathPlan#repairBlocked} attempt. A repair re-derives the L0 skeleton + runs a full windowed block
     * search (~a tick of work), so it must NOT run every tick while stuck — that floods the console/chat and the
     * tick budget. Reset to {@link #REPAIR_COOLDOWN} on each attempt, cleared when the route recovers.
     */
    private int repairCooldown;
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
        // Tick the bot as a real player: forge its movement inputs, then run the FULL vanilla player tick.
        // super.tick() (via FakePlayerEntity) is ServerPlayer's housekeeping — i-frame countdown, container +
        // advancement sync, attribute updates, block-break progress, the now-harmless client-load timeout (we
        // mark the connection loaded at spawn). doTick() is Player.tick() — physics/aiStep + updatePlayerPose +
        // food/air. super.tick() runs NO physics, so this is housekeeping + one physics step, never a
        // double-step. Inputs must be set before doTick(). Running BOTH (instead of the old hand-rolled
        // baseTick()+aiStep()) is what makes the bot feel like a player and stops us re-implementing player
        // effects (pose, i-frames, …) one at a time. Survival systems are gated by the config flags below +
        // the decreaseAirSupply/causeFoodExhaustion overrides (defaults: invulnerable / no hunger / no breath).
        final Vec3 posBefore = this.position(); // captured pre-movement for the forged move report (below)
        this.setNoGravity(false);
        // Mortality: drive BOTH invulnerability flags from survival.takesDamage. The entity-level flag is the
        // usual gate, but a fake player can also carry the ABILITIES-level flag (spawned into a creative world
        // before we force survival, or a force-gamemode server), and that one blocks damage independently — so
        // keep them in lockstep. Re-sync abilities only when it actually flips (avoids per-tick packet churn).
        final boolean immune = !ConfigLoader.config().takesDamage();
        this.setInvulnerable(immune);
        if (this.getAbilities().invulnerable != immune) {
            this.getAbilities().invulnerable = immune;
            this.onUpdateAbilities();
        }

        if (owner == this) {
            super.tick();
            this.doTick();
            return;
        }

        this.xxa = 0.0f;
        this.yya = 0.0f;          // no idle float-up: a swimming step drives its vertical via velocity, and
                                  // an idle/holding bot in water should hold, not auto-rise (was isInWater?1:0)
        this.setJumping(false);   // discrete land jumps use jumpFromGround(); swim following re-enables this
        this.setSprinting(false); // ditto — buoyancy + sprint-swim are refined per-step in steerAlongPath
        this.steeredThisTick = false;       // reset the swim-pose diagnostic snapshot for this tick
        this.heldWaterJumpThisTick = false; // (set below in steerAlongPath; read post-doTick in logSwimTransition)

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

        super.tick(); // ServerPlayer housekeeping (i-frames, containers, advancements, attributes, …)
        this.doTick(); // Player.tick physics + pose + survival
        // Forge the per-tick move report a real client's move packet would drive: feeds getKnownMovement() for
        // movement-based block damage (sweet berry / cactus / magma / powder snow) and applies player fall
        // damage (doCheckFallDamage). Uses the bot's ACTUAL movement this tick. No-op pre-26.
        final Vec3 moved = this.position().subtract(posBefore);
        MoveReport.after(this, moved.x, moved.y, moved.z, EntityState.onGround(this));

        // Read the prone-pose state AFTER doTick (vanilla's updateSwimming ran inside it, from THIS tick's
        // inputs + resulting position), so a PRONE->STAND flip is dumped with the state that caused it.
        if (Debug.VERBOSE) logSwimTransition();
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
        // A BLOCKED status does NOT force a full rebuild: the cascade (HPA-CASCADE.md §6) repairs a blocked hop
        // in place — escalating up its level stack in repairStep — without discarding the whole nested plan. A
        // full rebuild fires only on no-plan or a new goal region.
        if (pathPlan == null || newRegionGoal) {
            if (newRegionGoal) {
                // New destination region → the learned dead-ends no longer apply; start the repair fresh.
                navGaveUp = false;
            }
            replan(goalFloor);
            blockRefreshTicks = REPLAN_TICKS;
        } else {
            // Per-tick driver hook (HPA-IMPLEMENTATION.md §10): report the bot's floor cell so the sliding
            // window can COMMIT into the next region (the wiggle rule) and replan THAT window's block path.
            pathPlan.onBotMoved(this.blockPosition().below(), currentStartMode());
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
                this.planStartFloor = this.blockPosition().below(); // new window begins at the bot's cell
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
                    inventoryFeasibility(), currentStartMode());
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
        this.planStartFloor = startFloor; // first segment begins at the bot's current floor cell

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
        // Cascade repair (HPA-CASCADE.md §6): a BLOCKED hop is blacklisted + escalated up the level stack IN
        // PLACE by the plan itself (which owns its per-level blacklists). It re-derives the L0 skeleton on
        // success; only a hard exhaustion (no route at any level) gives up.
        if (status == PathStatus.FAILED) {
            giveUp();
            return;
        }
        if (status != PathStatus.BLOCKED) {
            repairCooldown = 0; // route is fine — reset the throttle
            return;
        }
        // THROTTLE: a repair re-derives the L0 skeleton AND runs a full windowed block search (up to
        // MAX_EXPANSIONS ≈ a whole tick of work). Doing that every tick while stuck floods the console with
        // 10001-node searches and the chat with re-derived-target churn (and blows the tick budget). Cap it to
        // once per REPAIR_COOLDOWN ticks: attempt a repair, then follow whatever route it found for a few ticks
        // before trying again. (A genuine give-up still comes from repairBlocked returning false.)
        if (repairCooldown > 0) {
            repairCooldown--;
            return;
        }
        repairCooldown = REPAIR_COOLDOWN;
        chat("[bot] path blocked — invalidating a region crossing and rerouting.");
        if (!pathPlan.repairBlocked()) {
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

    /**
     * Steer along the current path SEGMENT, advancing the waypoint cursor by block occupancy and delegating
     * each step's inputs to its {@link com.orebit.mod.pathfinding.blockpathfinder.Movement} (the cold {@code
     * reached}/{@code editsReadyNow}/{@code steer} hooks — MOVEMENT-DESIGN.md §1). The follower owns only the
     * generic plumbing: advance the cursor, apply each step's folded edits once, hand the move a {@link
     * SteerView} over the current segment, and run the cross-cutting slip/stall recovery. Per-move behaviour
     * (the swim velocity track, the Fall/Pillar airborne column-homing, the Ascend/Pillar jump, the swim
     * cursor tolerance, Pillar's airborne edit timing) lives on the move classes via {@link BotSteering} +
     * {@link SteerControl}, so adding a capability never touches this method.
     */
    private void steerAlongPath() {
        // Advance to the furthest waypoint the bot has reached. Waypoints ARE blocks and so are the bot's feet
        // ({@link #blockPosition()}), so the default test is block-exact (Movement.reached); a swim move
        // loosens it vertically for a buoyancy-bobbing bot. Because the match includes Y, the feet block only
        // equals the next step once the bot has actually climbed onto it (a stacked staircase can't be
        // skipped); scanning from the end absorbs any overshoot.
        for (int j = path.size() - 1; j >= waypointIndex; j--) {
            BlockPos w = path.waypoint(j);
            if (path.movement(j).reached(this, w.getX(), w.getY(), w.getZ())) {
                waypointIndex = j + 1;
                break;
            }
        }
        if (waypointIndex >= path.size()) {
            this.zza = 0.0f;
            return;
        }

        Movement movement = path.movement(waypointIndex);
        BlockPos wp = path.waypoint(waypointIndex);

        // Apply this step's folded break/place edits once, the moment the move says they're due: the generic
        // case clears/fills the cells in front before the bot moves into them (the bot is standing at the
        // previous waypoint, the cells are right ahead). Pillar defers until the bot is airborne
        // (Movement.editsReadyNow), since its footing lands in the bot's OWN feet cell.
        StepEdits edits = path.edits(waypointIndex);
        if (edits != null && waypointIndex != lastEditedIndex && movement.editsReadyNow(this)) {
            lastEditedIndex = waypointIndex;
            applyEdits(edits);
        }

        // Build the SEGMENT the move tracks: from the previous waypoint (or the window/plan start for the
        // first step — waypoints are start-exclusive) to the current target, plus a one-step look-ahead so
        // the controller can ease momentum into a turn. The cursor is reused (no per-tick allocation) and
        // converts to the feet-target frame the controller expects (block-centre xz, floor-cell-top y).
        BlockPos segStart = (waypointIndex == 0)
                ? (planStartFloor != null ? planStartFloor : this.blockPosition().below())
                : path.waypoint(waypointIndex - 1);
        BlockPos next = (waypointIndex + 1 < path.size()) ? path.waypoint(waypointIndex + 1) : null;
        cursor.set(segStart, wp, next);

        // Per-move steering: track the planned LINE toward the waypoint (look + forward), plus whatever extra
        // inputs the move needs (hold jump for a climb, the sprint flag). All callbacks go through this
        // entity's BotSteering implementation below.
        movement.steer(this, cursor);
        this.steeredThisTick = true;                            // a steer ran → sprint was re-asserted if this is a sprint move
        this.lastSteerMove = movement.getClass().getSimpleName(); // (swim-pose diagnostic; Debug.VERBOSE)

        // Cross-cutting WATER RULE — the vertical control for EVERY move, exactly what a player presses: in
        // water, hold SPACE to rise toward the planned depth, hold SHIFT to sink toward it. This one rule (not
        // per-move code) is how the bot dives to a submerged hole, holds depth, surfaces, and climbs out onto a
        // bank — for a Swim/SprintSwim, a StartSprintSwim dive, or a Traverse leaving water. The up half is
        // vanilla's jumpInLiquid (setJumping); the down half is goDownInWater (sinkInWater), which a headless
        // bot must replicate since the client tick that normally runs it is absent. The dead-band stops chatter.
        if (this.isInWater()) {
            // A prone-mode move (sprint-swim) rides pinned a bit under the surface so its short 0.6-tall hitbox
            // never floats fully clear of the water — otherwise vanilla drops the prone pose and the plan
            // degrades to the slow Swim (the diagnosed surface-breach). Standing water moves ride at plain depth.
            double depth = cursor.ty() - (movement.keepsSubmerged() ? SUBMERGE_BIAS : 0.0);
            if (this.getY() < depth - WATER_RISE_DEADBAND) {
                this.setJumping(true);   // below the planned depth → rise (hold space)
                this.heldWaterJumpThisTick = true; // (swim-pose diagnostic: buoyancy that can breach the surface)
            } else if (this.getY() > depth + WATER_RISE_DEADBAND) {
                this.sinkInWater();      // above the planned depth → sink (hold shift) — the dive the bot lacked
            }
        }

        if (Debug.VERBOSE) logVerbose(movement, wp);

        // Generic recovery (cross-cutting, not per-move) — DETECT a slip / stall the planner can't see and
        // HANDLE it. On the ground, holding jump frees most physical hitches (and persistent grinding dumps
        // the surrounding column, which the floor-centric 2-bit grid can't represent). Off the ground / in
        // water a jump can't help, so a no-progress stall forces a re-search; likewise a sustained drift off
        // the planned line. The old backstop only jumped when grounded, so airborne/underwater stalls never
        // recovered.
        Vec3 velocity = this.getDeltaMovement();
        boolean grounded = EntityState.onGround(this);
        if (velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR && grounded) {
            this.setJumping(true);
            if (++stuckTicks == STUCK_DUMP_TICKS && Debug.ENABLED) dumpStuck(wp);
        } else {
            stuckTicks = 0;
        }

        // Slip detection: count consecutive ticks the bot is off the planned segment (a transient bob won't
        // trip it). Stall detection: no 3-D progress while airborne/underwater (a jump can't help there).
        offTrackTicks = (SteerControl.crossTrack(this, cursor) > OFF_TRACK_DIST) ? offTrackTicks + 1 : 0;
        stallTicks = (!grounded && velocity.lengthSqr() < STUCK_SPEED_SQR) ? stallTicks + 1 : 0;
        if (recoverCooldown > 0) {
            recoverCooldown--;
        } else if (offTrackTicks >= OFF_TRACK_TICKS || stallTicks >= STALL_TICKS) {
            blockRefreshTicks = 0; // re-search the window from the bot's actual cell next tick (driveToward)
            offTrackTicks = 0;
            stallTicks = 0;
            recoverCooldown = RECOVER_COOLDOWN;
        }
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

    /**
     * {@code /bot debug verbose}: announce which {@link Movement} the bot is executing, toward which cell, and
     * in which medium — one line per change (not per tick), to the owner's chat and the log. This is the
     * diagnostic for "is the bot actually swimming, or is it trying to Ascend in water?": the medium flips to
     * {@code water} the moment it submerges, and the move name says exactly which strategy is driving.
     */
    private void logVerbose(Movement movement, BlockPos wp) {
        String move = movement.getClass().getSimpleName();
        String medium = isInWater() ? "water" : (EntityState.onGround(this) ? "ground" : "air");
        String key = waypointIndex + "|" + move + "|" + medium;
        if (key.equals(lastVerbose)) return;
        lastVerbose = key;
        chat("[bot] " + move + " → " + compact(wp) + " (" + medium + ")");
        OrebitCommon.LOGGER.info("[Orebit] exec {} -> {} ({}) feetY={} targetY={}",
                move, compact(wp), medium, String.format("%.2f", getY()),
                String.format("%.2f", wp.getY() + 1.0));
    }

    /**
     * {@code /bot debug verbose}: dump the bot's swim state the moment the prone {@code Pose.SWIMMING} flips
     * (either direction) — the diagnostic for "why does the bot drop sprint-swim mid-crossing?". Vanilla's
     * continuation rule keeps the pose only while {@code isSprinting() && isInWater()} and can re-enter it only
     * while {@code isUnderWater()}, so a {@code PRONE->STAND} line names its own cause:
     * <ul>
     *   <li>{@code sprinting=false} (usually with {@code steered=false}, {@code wp=n/n}) → a one-tick sprint drop:
     *       the window was consumed and {@link #steerAlongPath} early-returned without re-asserting sprint.</li>
     *   <li>{@code inWater=false} with {@code y} above the surface and a positive {@code vy}, {@code heldJump=true}
     *       → a buoyancy breach: the water-rise rule launched the bot clear of the water for a tick.</li>
     * </ul>
     * Read post-{@code doTick} (see {@link #tick}), so the state is the one vanilla's {@code updateSwimming} just
     * decided from. One line per flip (not per tick); never throws onto the tick.
     */
    private void logSwimTransition() {
        boolean now = this.isSwimming();
        if (now != wasSwimming) {
            Vec3 v = this.getDeltaMovement();
            String edge = now ? "STAND->PRONE" : "PRONE->STAND";
            OrebitCommon.LOGGER.info("[Orebit] swim {} sprint={} inWater={} underWater={} onGround={} "
                            + "y={} vy={} move={} steered={} heldJump={} wp={}/{}",
                    edge, isSprinting(), isInWater(), isUnderWater(), EntityState.onGround(this),
                    String.format("%.2f", getY()), String.format("%.3f", v.y), lastSteerMove,
                    steeredThisTick, heldWaterJumpThisTick, waypointIndex, path != null ? path.size() : -1);
            chat("[bot] swim " + edge + " sprint=" + isSprinting() + " inWater=" + isInWater()
                    + " underWater=" + isUnderWater() + " vy=" + String.format("%.3f", v.y)
                    + " move=" + lastSteerMove + " steered=" + steeredThisTick);
        }
        wasSwimming = now;
    }

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
        faceHorizontally(dx, dz);
        this.zza = 1.0f;

        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR;
        if (stuck && EntityState.onGround(this)) {
            this.setJumping(true);
        }
    }

    // ---- BotSteering seam (the cold per-tick ops the Movement steer hooks call back through) ----------
    // Implemented against this bot's ServerPlayer ops; exposes only primitives so the movements/ package
    // stays MC-type-free (see BotSteering). Cold (tick rate), so virtual dispatch through the interface is
    // fine — the no-polymorphism rule is hot-path-only.

    @Override public double x() { return this.getX(); }
    @Override public double y() { return this.getY(); }
    @Override public double z() { return this.getZ(); }

    @Override public int footX() { return this.blockPosition().getX(); }
    @Override public int footY() { return this.blockPosition().getY(); }
    @Override public int footZ() { return this.blockPosition().getZ(); }

    /** Via the {@link EntityState} adapter (the accessor name drifts across versions) — see {@link BotSteering#grounded}. */
    @Override public boolean grounded() { return EntityState.onGround(this); }

    @Override public boolean inWater() { return this.isInWater(); }

    @Override
    public void faceHorizontally(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
    }

    /**
     * Sink in water — replicate vanilla {@code LocalPlayer.goDownInWater()} (the effect of holding shift),
     * which the headless bot's missing client tick would otherwise run: subtract {@code 0.04} from the
     * vertical velocity, the exact counterpart to {@code jumpInLiquid}'s {@code +0.04} rise.
     */
    @Override
    public void sinkInWater() {
        this.setDeltaMovement(this.getDeltaMovement().subtract(0.0, 0.04, 0.0));
    }

    @Override public void setForward(float zza) { this.zza = zza; }

    // setSprinting(boolean) is satisfied by the inherited public LivingEntity method.
    /** Widen the inherited protected {@code setJumping} to public so it satisfies the {@link BotSteering} seam.
     *  Held true, vanilla {@code aiStep} jumps on land and swims up in water — the one climb mechanism. */
    @Override public void setJumping(boolean jumping) { super.setJumping(jumping); }

    // ---- Survival gating (the bot runs the full vanilla player tick via doTick — see tick()) ----------
    // Two of the now-live survival systems are gated by their config flags by intercepting vanilla's own
    // decrement hooks, so when a flag is ON the bot uses the real vanilla machinery unchanged. Damage is
    // gated separately by setInvulnerable(!takesDamage) in tick() (Entity.setInvulnerable is stable across
    // versions; overriding hurt() is not — it split into hurtServer/hurtClient at 1.21.5).

    /**
     * Breath: suppress air loss when {@code survival.needsBreath} is off (the default) so the bot never drowns
     * and its bubbles stay full; when on, defer to vanilla. {@code decreaseAirSupply} is the per-tick hook
     * {@code LivingEntity.baseTick} calls while submerged.
     */
    @Override
    protected int decreaseAirSupply(int air) {
        return ConfigLoader.config().needsBreath() ? super.decreaseAirSupply(air) : air;
    }

    /**
     * Hunger: drop exhaustion accumulation when {@code survival.hunger} is off (the default) so food never
     * depletes — which also keeps it above the sprint floor (vanilla cancels sprint below ~6), so the bot can
     * always sprint / sprint-swim. When on, defer to vanilla. {@code causeFoodExhaustion} is the single entry
     * point every activity (walking, sprinting, jumping) routes food cost through.
     */
    @Override
    public void causeFoodExhaustion(float amount) {
        if (ConfigLoader.config().hunger()) super.causeFoodExhaustion(amount);
    }
}
