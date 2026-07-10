package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.ClientLoad;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.MoveReport;
import com.orebit.mod.platform.Replaceable;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The ally bot: a faked {@link net.minecraft.server.level.ServerPlayer} that follows its owner.
 *
 * <p><b>This class is the ORCHESTRATOR.</b> It owns the entity identity (owner, vanilla player tick,
 * survival gating, the {@link BotSteering} seam the movements drive through) and the per-tick mode
 * dispatch; each behavioural concern lives on a component it constructs and ticks:
 * <ul>
 *   <li>{@link BotNavigator} — the two-tier drive/follow concern: region skeleton + sliding block window
 *       ({@link PathPlan}), the waypoint follower, boundary-gated replan, region repair, and the
 *       navigation diagnostics. The first consumer of the nav grid (PRD Phase 4).</li>
 *   <li>{@link BotGatherer} — the {@code /bot gather} find→mine→return state machine.</li>
 *   <li>{@link BotPortalFollower} — cross-dimension FOLLOW/COME via known nether portals.</li>
 *   <li>{@link BotMining} — the "hands": per-tick timed block breaking (tool, cracks, drops).</li>
 * </ul>
 * When the bot has NO walkable plan it WAITS (the navigator never moves it without a plan); all movement
 * inputs are owned by the planned {@code Movement}s. This is COLD (tick-rate) code, so plain object
 * composition/polymorphism is fine — the no-polymorphism rule is hot-path-only.
 */
public class AllyBotEntity extends FakePlayerEntity implements BotSteering {

    private final Player owner;

    /** The bot's "hands" for breaking blocks — real tool + vanilla timing + drops (replaces instant edits).
     *  Requested per tick by the follower and actuated once per tick from {@link #tick} (see {@link BotMining}). */
    private final BotMining mining;
    /** The two-tier drive/follow component (region skeleton + block window + waypoint follower). */
    private final BotNavigator navigator;
    /** The {@code /bot gather} find→mine→return state machine. */
    private final BotGatherer gatherer;
    /** The cross-dimension FOLLOW/COME portal-seek/ENTER component. */
    private final BotPortalFollower portalFollower;

    /** {@code /bot mine <pos>} Stage-1 test target: while non-null, requested each tick until it's mined. */
    private BlockPos debugMineTarget;

    /** Last announced silent mine/place refusal key — one line per cell, not per tick ({@link #refusalLog}). */
    private String lastRefusalKey;

    /** The level the bot ended the previous tick in — a difference detected post-{@code doTick} means a
     *  COMPLETED teleport (vanilla's portal process runs inside the player tick). Null until first tick. */
    private Level lastLevel;

    /**
     * The bot's movement mode to seed the planner's start node with — its REAL pose: vanilla {@code
     * isSwimming()} (the prone {@code Pose.SWIMMING}) ⇒ {@link MovementContext#MODE_PRONE}, so a replan that
     * fires mid-sprint-swim keeps the prone state instead of re-deriving STANDING from a buoyancy bob and
     * re-initiating (or, in genuine 1-deep water, getting stuck unable to re-initiate). When the bot is not
     * swimming, {@link BlockPathfinder#MODE_AUTO} lets the search derive the mode from the start geometry.
     */
    int currentStartMode() {
        return this.isSwimming() ? MovementContext.MODE_PRONE : BlockPathfinder.MODE_AUTO;
    }

    /**
     * The bot's planner capabilities + throwaway block now come from the owner config (PRD §10 Phase 1a):
     * {@link #caps()} returns the {@link BotCaps} derived from {@code config/orebit.properties}
     * (break/place toggles, mining-hardness cap, A* node cap + greedy weight) and {@link #placeBlock()}
     * the configured conjured block. Both are read at the point of use (in the navigator's replan /
     * {@link #traceTo} / the navigator's applyEdits) from the live {@link ConfigLoader} cache, so a
     * {@code /bot config reload} takes effect on the next plan with no per-tick cost — the cached values
     * are plain field reads, never on the A* hot path. Out of the box the config defaults reproduce the
     * historical {@code BotCaps.BREAK_PLACE} + cobblestone behaviour exactly, so nothing changes until the
     * owner edits the file.
     */
    BotCaps caps() {
        return ConfigLoader.botCaps();
    }

    /** The throwaway {@link BlockState} the bot places when bridging/footing — the configured conjured block. */
    BlockState placeBlock() {
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
    MovementContext.InventoryView inventoryFeasibility() {
        Config cfg = ConfigLoader.config();
        return new BotInventory(this).feasibility(
                caps(), cfg.consumesBlocks(), cfg.conjuredBlockState(), cfg.removalCostWeight(),
                cfg.placeBaseCost(), cfg.breakBaseCost());
    }

    /**
     * What the bot is currently trying to do, set by the {@code /bot} commands (defaults to
     * {@link Mode#FOLLOW} so a freshly spawned bot behaves as before — auto-follow the owner):
     * <ul>
     *   <li>{@link Mode#FOLLOW} — continuously path to the owner (the original behaviour).
     *   <li>{@link Mode#STAY} — hold position; don't path anywhere.
     *   <li>{@link Mode#COME} — path once to a fixed summon cell, then drop to {@link Mode#STAY}.
     *   <li>{@link Mode#GATHER} — the find→mine→return resource loop ({@code /bot gather}); see
     *       {@link BotGatherer}.
     * </ul>
     */
    public enum Mode { FOLLOW, STAY, COME, GATHER }

    private Mode mode = Mode.FOLLOW;
    private BlockPos comeTarget;    // fixed summon cell (owner's feet block at /bot come time)

    // ---- swim-pose transition diagnostic (Debug.VERBOSE) — see logSwimTransition() -------------------
    // Vanilla drops the prone Pose.SWIMMING the instant a tick sees !(isSprinting() && isInWater()), and can
    // only re-enter it while isUnderWater() (eyes submerged). To find WHICH link breaks mid-crossing we snapshot
    // the two per-tick inputs the follower controls (was a steer run? was buoyancy-jump held?) and dump them the
    // moment isSwimming() flips, alongside the vanilla state — so PRONE->STAND names its own cause.
    private boolean wasSwimming;    // isSwimming() at the end of the previous tick (edge detector)
    /** A Movement.steer ran this tick (false on a consumed-window early return → no sprint re-assert).
     *  Written by {@link BotNavigator#steerAlongPath}; reset each tick in {@link #tick}. */
    boolean steeredThisTick;
    /** Simple name of the Movement whose steer ran this tick (written by the navigator). */
    String lastSteerMove = "-";

    /** Reused mutable cursor for the {@link #mine} pre-flight refusal read (no per-check allocation). */
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    public AllyBotEntity(MinecraftServer server, ServerLevel world, GameProfile profile, Player owner) {
        super(server, world, profile);
        this.owner = owner;
        this.mining = new BotMining(this);
        this.navigator = new BotNavigator(this);
        this.gatherer = new BotGatherer(this);
        this.portalFollower = new BotPortalFollower(this);
    }

    // ---- component / collaborator accessors (package-private: the components call back through these) ----

    /** The owning player this bot follows (and chats to). */
    Player owner() {
        return owner;
    }

    /** The timed-breaking "hands" — the navigator gates steering on {@code busy()}, GATHER requests breaks. */
    BotMining mining() {
        return mining;
    }

    /** The two-tier drive/follow component — GATHER and the portal follower drive through it. */
    BotNavigator navigator() {
        return navigator;
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

    /** Aim the head (yaw + pitch) at the centre of world cell {@code (x,y,z)} — the "look at what you interact
     *  with" a player does when placing (mirrors {@link BotMining}'s mining look). For a pillar footing directly
     *  below, this is a straight-down look. */
    private void lookAtCell(int x, int y, int z) {
        double dx = x + 0.5 - this.getX();
        double dy = y + 0.5 - this.getEyeY();
        double dz = z + 0.5 - this.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distXZ));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.setXRot(pitch);
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
        navigator.clearPlan();
        portalFollower.resetPortalSeek(); // a fresh command restarts (and re-announces) any cross-dimension seek
    }

    /** {@code /bot come}: path once to {@code summonCell} (the caller's feet block), then hold there. */
    public void comeTo(BlockPos summonCell) {
        this.mode = Mode.COME;
        this.comeTarget = summonCell.immutable();
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
    }

    /**
     * {@code /bot mine <pos>} — Stage-1 verification of the timed {@link BotMining} actuator: stop in place and
     * dig one block with the real tool, animation, tick-cost, and drops, so the "hands" can be confirmed in-game
     * before the movement reconcile (Stage 2) drives them. Puts the bot in {@link Mode#STAY} so it stands and
     * mines instead of pathing.
     */
    public void debugMineAt(BlockPos pos) {
        setMode(Mode.STAY);
        this.debugMineTarget = pos.immutable();
    }

    /** {@code /bot gather <resource> [count]}: switch to {@link Mode#GATHER} and start the
     *  {@link BotGatherer} loop for indexed resource {@code column}, targeting {@code quota} picked-up items. */
    public void startGather(int column, int quota) {
        this.mode = Mode.GATHER;
        this.comeTarget = null;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
        gatherer.startGather(column, quota);
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

        // Stage-1 mining test hook: while a /bot mine target is set, request it each tick until it's gone, then
        // report and clear. (Stage 2 replaces this debug field with each Movement's reconcile driving the break.)
        final ServerLevel level = (ServerLevel) Worlds.of(this);

        // Flush barrier (PERF-DESIGN-navgrid-edit-batching.md §4.4): drain the level's deferred
        // block-edit queue before this tick's mode dispatch, so the region-tier reads that do NOT go
        // through a NavGridView — the lazy LeafCostComputer/FragmentLeafComputer mini-pathfinds
        // triggered during region planning read NavStore sections directly — see every change fired
        // earlier this tick. Sync block searches are covered again by the NavGridView ctor barrier
        // (then a clean no-op). One static int test per bot per tick when clean.
        NavGridUpdater.flush(level);
        if (debugMineTarget != null) {
            if (level.getBlockState(debugMineTarget).isAir()) {
                chat("[bot] mined " + compact(debugMineTarget));
                debugMineTarget = null;
            } else {
                mining.request(debugMineTarget);
            }
        }

        switch (mode) {
            case STAY -> holdPosition();
            case GATHER -> gatherer.gatherLoopTick();
            case COME -> {
                // Summon to a fixed cell; once there, settle into STAY (distinct from FOLLOW, which
                // would keep chasing). comeTarget can't be null in COME, but guard defensively.
                if (comeTarget == null) { setMode(Mode.STAY); holdPosition(); break; }
                // Cross-dimension guard: comeTarget's coordinates were captured in the CALLER's level, so
                // while the owner is elsewhere the bot follows them through a portal instead of pathing to
                // a cell that means nothing in this level.
                if (portalFollower.followThroughPortal()) break;
                double tx = comeTarget.getX() + 0.5, ty = comeTarget.getY(), tz = comeTarget.getZ() + 0.5;
                if (navigator.driveToward(tx, ty, tz, comeTarget.below())) setMode(Mode.STAY); // arrived
            }
            default -> { // FOLLOW
                if (!portalFollower.followThroughPortal()) {
                    navigator.driveToward(owner.getX(), owner.getY(), owner.getZ(),
                            owner.blockPosition().below());
                }
            }
        }

        super.tick(); // ServerPlayer housekeeping (i-frames, containers, advancements, attributes, …)
        this.doTick(); // Player.tick physics + pose + survival

        // Completed-teleport detection: vanilla's portal process (and any other dimension change) runs
        // inside the player tick above, so a level change is visible HERE first — re-anchor everything
        // per-level and re-arm the 1.21.11+ client-loaded gate before anything reads the stale state.
        final boolean levelChanged = Worlds.of(this) != lastLevel;
        if (levelChanged) {
            if (lastLevel != null) onLevelChanged();
            lastLevel = Worlds.of(this);
        }

        // Forge the per-tick move report a real client's move packet would drive: feeds getKnownMovement() for
        // movement-based block damage (sweet berry / cactus / magma / powder snow) and applies player fall
        // damage (doCheckFallDamage). Uses the bot's ACTUAL movement this tick. No-op pre-26.
        // SKIPPED on the teleport tick: posBefore is a pre-teleport position in the OLD level, so the delta
        // would be a nonsense cross-dimension "move" and could forge lethal fall damage out of thin air.
        if (!levelChanged) {
            final Vec3 moved = this.position().subtract(posBefore);
            MoveReport.after(this, moved.x, moved.y, moved.z, EntityState.onGround(this));
        }

        // Read the prone-pose state AFTER doTick (vanilla's updateSwimming ran inside it, from THIS tick's
        // inputs + resulting position), so a PRONE->STAND flip is dumped with the state that caused it.
        if (Debug.VERBOSE) logSwimTransition();

        // Actuate the "hands": drive any requested block break one tick (real tool + timing + drops). Runs after
        // doTick so the break reflects this tick's inputs/position; a no-op when nothing was requested this tick.
        // Skipped on the teleport tick — `level` above is the pre-teleport level, and any in-flight break
        // request belongs to it.
        if (!levelChanged) mining.tick(level);
    }

    /** STAY: stop in place and face the owner. */
    private void holdPosition() {
        this.zza = 0.0f;
        navigator.clearPlan();
        lookAtPlayer(owner);
    }

    /**
     * Re-anchor after a COMPLETED dimension change (detected post-{@code doTick} in {@link #tick}). The
     * active plan, its settled/start anchors, and any give-up/portal-seek state all belong to the OLD
     * level; NavStore/RegionGrid are per-{@code ServerLevel}, so once these are dropped the next replan
     * transparently plans in the new level (its nav data fills in over a few ticks — a short visible
     * pause, not a failure). {@code ClientLoad.markLoaded} is re-armed because a respawn-style teleport
     * resets {@code connection.hasClientLoaded()} on 1.21.11+, and a clientless bot never re-sends the
     * signal — without this it would go permanently invulnerable again (same fix as spawn; see BotManager).
     */
    private void onLevelChanged() {
        navigator.onLevelChanged();
        portalFollower.resetPortalSeek();
        ClientLoad.markLoaded(this);
    }

    /** Send one line to the owner's chat (reusing the version-portable {@link CommandFeedback}); swallow any
     *  error so debug chatter can never break the server tick. */
    void chat(String message) {
        try {
            CommandFeedback.sendTo(owner, message);
        } catch (Throwable ignored) {
            // never let progress chatter crash the tick
        }
    }

    /** A {@code Debug.VERBOSE} forensic line → owner chat AND the server log (callers dedup/throttle). */
    void vlog(String msg) {
        chat("[bot] " + msg);
        OrebitCommon.LOGGER.info("[Orebit] {}", msg);
    }

    static String compact(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
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

        final boolean haveWindow = target != null && corridor != null;
        final BlockPos searchGoal = haveWindow ? target : goalFloor;

        // PROTOTYPE region-informed heuristic A/B (region cost/fragment work): build a per-region cost-to-goal
        // field rooted at the block search's goal, bounded to a box around the start+goal regions, and run the
        // trace TWICE — baseline (octile only) → orebit-trace.txt, region-heuristic ON → orebit-trace-region.txt
        // — so we can compare expansion counts + PNG both. The field is a prototype extra; on any failure we fall
        // back to a baseline-only trace.
        final RegionGrid rgrid = RegionGrid.of(level);
        final int minY = rgrid.minY();
        final RegionMineModel mine = RegionMineModel.from(inv != null ? inv.mining() : null);
        final RegionPlaceModel place = RegionPlaceModel.from(inv); // capability-aware field pillar cost
        RegionCostField field = null;
        try {
            RegionPathfinder.RegionBox bound = RegionPathfinder.RegionBox.around(
                    RegionAddress.regionX(startFloor.getX(), 0), RegionAddress.regionY(startFloor.getY(), 0, minY),
                    RegionAddress.regionZ(startFloor.getZ(), 0),
                    RegionAddress.regionX(searchGoal.getX(), 0), RegionAddress.regionY(searchGoal.getY(), 0, minY),
                    RegionAddress.regionZ(searchGoal.getZ(), 0), 3);
            field = RegionPathfinder.costToGoalField(rgrid, minY, searchGoal, startFloor,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), mine, place, bound);
        } catch (Throwable t) {
            field = null;
        }

        int baseExp = traceOneRun(new java.io.File("orebit-trace.txt"), level, startFloor, goalFloor,
                searchGoal, haveWindow, corridor, caps, inv, skeletonDump, false, null);
        if (baseExp < 0) {
            return "trace FAILED: I/O error on baseline run";
        }
        String msg = "baseline=" + baseExp + " expansions → orebit-trace.txt";
        if (field != null) {
            int regExp = traceOneRun(new java.io.File("orebit-trace-region.txt"), level, startFloor, goalFloor,
                    searchGoal, haveWindow, corridor, caps, inv, skeletonDump, true, field);
            msg += (regExp >= 0)
                    ? "   |   region-heuristic=" + regExp + " expansions → orebit-trace-region.txt"
                            + String.format("  (field pillar=%.2f/blk vs stand-in 2.29)", place.pillarPerBlock())
                    : "   |   region run I/O error";
        } else {
            msg += "   |   region field unavailable (prototype)";
        }
        return msg;
    }

    /** One traced block-A* run for {@link #traceTo} — writes the trace to {@code file} and returns the expansion
     *  count. {@code regionMode} toggles the prototype region-informed heuristic ({@code field} its cost field). */
    private int traceOneRun(java.io.File file, ServerLevel level, BlockPos startFloor, BlockPos goalFloor,
                            BlockPos searchGoal, boolean haveWindow, RegionBound corridor, BotCaps caps,
                            MovementContext.InventoryView inv, String skeletonDump, boolean regionMode,
                            RegionCostField field) {
        boolean savedTiming = BlockPathfinder.LOG_TIMING;
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            w.write("Orebit A* trace  start=" + startFloor + "  goal=" + goalFloor
                    + (haveWindow ? "  window target=" + searchGoal + "  corridor=" + corridor
                                  : "  (raw block-A*, no corridor)")
                    + "  regionHeuristic=" + (regionMode && field != null) + "  caps=" + caps + "\n");
            if (skeletonDump != null) {
                w.write("\n" + skeletonDump + "\n\n");
            }
            if (regionMode && field != null) {
                w.write(field.dump() + "\n");
            }
            w.write("legend: 'E <seq> <x> <y> <z> g=<g> f=<f> via=<move|start>' = one expansion (pop), in"
                    + " order;  '  C <move> <x> <y> <z> cost=<c> <OK|worse|corridor>' = a candidate it"
                    + " emitted (OK=relaxed onto the open set, worse=not an improvement).\n\n");
            BlockPathfinder.TRACE_OUT = w;
            BlockPathfinder.TRACE = true;
            BlockPathfinder.LOG_TIMING = false;
            RegionCostField useField = regionMode ? field : null; // A/B: baseline run passes null (plain octile)
            BlockPathPlan plan = haveWindow
                    ? BlockPathfinder.findPath(new NavGridView(level), startFloor, searchGoal, caps, null, corridor,
                            inv, BlockPathfinder.MODE_AUTO, null, useField)
                    : BlockPathfinder.findPath(new NavGridView(level), startFloor, goalFloor, caps, null, null,
                            inv, BlockPathfinder.MODE_AUTO, null, useField);
            BlockPathfinder.TRACE = false;
            int exp = BlockPathfinder.lastExpansions();
            w.write("\nRESULT: " + (plan == null ? "FAIL (null)" : plan.size() + "wp cost=" + plan.cost())
                    + "  expansions=" + exp + "\n");
            return exp;
        } catch (java.io.IOException e) {
            return -1;
        } finally {
            BlockPathfinder.TRACE = false;
            BlockPathfinder.TRACE_OUT = null;
            BlockPathfinder.LOG_TIMING = savedTiming;
        }
    }

    /**
     * {@code /bot rtrace} — the REGION-tier counterpart of {@link #traceTo}: a one-shot diagnostic of WHY the
     * region A* builds the skeleton it does (the down→over→up cavern-drop investigation). Stops the bot, then
     * runs a single direct level-0 {@link RegionPathfinder#plan} from the bot to the caller with
     * {@link RegionPathfinder#TRACE} on, dumping every expansion + candidate edge (kind, cost, crossing cell,
     * accept/reject) to {@code <run dir>/orebit-region-trace.txt} for offline analysis.
     *
     * <p>It first builds the real two-tier {@link PathPlan} (TRACE off) purely to capture
     * {@link PathPlan#describeSkeleton} — the skeleton the bot actually used — as a cross-check in the header;
     * the traced search is the direct level-0 fragment plan, which reproduces that skeleton for a near
     * (cap-safe level-0) goal like an in-cavern hop. For a far goal the live cascade may plan at a coarser
     * level, so the header skeleton is the authoritative record and the traced level-0 search is the detail.
     */
    public String regionTraceTo(BlockPos goalFloor) {
        setMode(Mode.STAY); // stop the per-tick replan; the trace is a standalone one-shot search
        ServerLevel level = (ServerLevel) Worlds.of(this);
        RegionGrid grid = RegionGrid.of(level);
        BlockPos startFloor = this.blockPosition().below();
        final BotCaps caps = caps();

        String skeletonDump = null;
        try {
            PathPlan plan = new PathPlan(level, grid, startFloor, goalFloor, caps, inventoryFeasibility());
            skeletonDump = plan.describeSkeleton(); // the skeleton the live cascade actually produced
        } catch (Throwable t) {
            skeletonDump = "(live PathPlan threw " + t + ")";
        }

        java.io.File file = new java.io.File("orebit-region-trace.txt"); // run dir
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            w.write("Orebit REGION A* trace  start=" + startFloor + "  goal=" + goalFloor + "  caps=" + caps
                    + "  (direct level-0 fragment plan)\n");
            if (skeletonDump != null) {
                w.write("\n== live cascade skeleton (cross-check) ==\n" + skeletonDump + "\n");
            }
            w.write("\nlegend: 'E <seq> L<level> region=x,y,z frag=<f> g=<g> f=<f> [kind]' = one expansion"
                    + " (pop), in order;  '  C <kind> -> x,y,z frag=<f> cost=<c> crossing=wx,wy,wz <OK|worse>'"
                    + " = a candidate edge it emitted. kinds: walk|air-fall|air-pillar|solid-mine|water-swim|"
                    + "collapsed|unbuilt|mine-sibling|mine-fallback|mine-solid|dig-through.\n\n");
            RegionPathPlan rp;
            // Tool-aware region dig cost from the bot's real inventory (PERF-DESIGN region §5), so the trace's
            // dig breakdowns reflect the actual tools (null snapshot ⇒ the stone-tier RegionMineModel.DEFAULT).
            MovementContext.InventoryView traceInv = inventoryFeasibility();
            RegionMineModel mine = RegionMineModel.from(traceInv != null ? traceInv.mining() : null);
            RegionPathfinder.TRACE_OUT = w;
            RegionPathfinder.TRACE = true;
            try {
                rp = RegionPathfinder.plan(level, grid, startFloor, goalFloor, caps, mine);
            } finally {
                RegionPathfinder.TRACE = false;
                RegionPathfinder.TRACE_OUT = null;
            }
            if (rp == null || rp.isEmpty()) {
                w.write("\nRESULT: no skeleton (null/empty)\n");
            } else {
                StringBuilder sb = new StringBuilder("\nRESULT: " + rp.size() + " regions"
                        + (rp.reachedGoalRegion() ? " (reached goal region)" : " (PARTIAL — goal not reached)")
                        + "  L" + rp.level() + "\n");
                for (int i = 0; i < rp.size(); i++) {
                    sb.append("  [").append(i).append("] region=").append(rp.rx(i)).append(',')
                            .append(rp.ry(i)).append(',').append(rp.rz(i));
                    if (rp.isFragmentModel()) sb.append(" frag=").append(rp.fragmentId(i));
                    if (rp.hasPortal(i)) sb.append(" crossing=").append(rp.portalCell(i));
                    sb.append('\n');
                }
                w.write(sb.toString());
            }
        } catch (java.io.IOException e) {
            return "region trace FAILED: " + e;
        }
        return file.getAbsolutePath();
    }

    // ---- Debug log formatting ----------------------------------------------------------------

    /** {@code Debug.VERBOSE}: name a silent mine/place refusal the moment it first happens (one line per
     *  (kind|cell), not per re-issued tick) — these silent returns are the prime "phase holds forever" causes. */
    private void refusalLog(String key, String msg) {
        if (!Debug.VERBOSE || key.equals(lastRefusalKey)) return;
        lastRefusalKey = key;
        vlog(msg);
    }

    /**
     * {@code /bot debug verbose}: dump the bot's swim state the moment the prone {@code Pose.SWIMMING} flips
     * (either direction) — the diagnostic for "why does the bot drop sprint-swim mid-crossing?". Vanilla's
     * continuation rule keeps the pose only while {@code isSprinting() && isInWater()} and can re-enter it only
     * while {@code isUnderWater()}, so a {@code PRONE->STAND} line names its own cause:
     * <ul>
     *   <li>{@code sprinting=false} (usually with {@code steered=false}, {@code wp=n/n}) → a one-tick sprint drop:
     *       the window was consumed and {@code steerAlongPath} early-returned without re-asserting sprint.</li>
     *   <li>{@code inWater=false} with {@code y} above the surface and a positive {@code vy} → a buoyancy
     *       breach: the depth autopilot's rise launched the bot clear of the water for a tick.</li>
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
                            + "y={} vy={} move={} steered={} wp={}/{}",
                    edge, isSprinting(), isInWater(), isUnderWater(), EntityState.onGround(this),
                    String.format("%.2f", getY()), String.format("%.3f", v.y), lastSteerMove,
                    steeredThisTick, navigator.waypointIndex(), navigator.pathSize());
            chat("[bot] swim " + edge + " sprint=" + isSprinting() + " inWater=" + isInWater()
                    + " underWater=" + isUnderWater() + " vy=" + String.format("%.3f", v.y)
                    + " move=" + lastSteerMove + " steered=" + steeredThisTick);
        }
        wasSwimming = now;
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

    @Override public boolean inLava() { return this.isInLava(); }

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

    // ---- Live-world geometry + block actions (the reconcile seam a MovePlan drives through) -----------

    /** Live movement-blocking test: the cell has a non-empty collision shape (air/water/plants read clear).
     *  Reads the live level so it reflects the bot's own just-made breaks/places (unlike the cached nav grid). */
    @Override
    public boolean solidAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos p = new BlockPos(x, y, z);
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    @Override
    public boolean airAt(int x, int y, int z) {
        return !solidAt(x, y, z);
    }

    /** Route a break request to the timed {@link BotMining} actuator (equip/face/swing/real-time/drops). */
    @Override
    public void mine(int x, int y, int z) {
        if (Debug.VERBOSE) {
            // Pre-flight the exact refusal BotMining applies SILENTLY (its mayBreak backstop stop()s the
            // break with no signal) — a phase re-requesting a refused cell every tick holds forever, and
            // without this line the hold is indistinguishable from a legitimately slow dig.
            ServerLevel level = (ServerLevel) Worlds.of(this);
            scratchPos.set(x, y, z);
            BlockState s = level.getBlockState(scratchPos);
            if (!s.isAir() && !ConfigLoader.config().mayBreak(s, s.getDestroySpeed(level, scratchPos))) {
                refusalLog("mine|" + x + "," + y + "," + z, "mine REFUSED at (" + x + "," + y + "," + z + "): "
                        + s.getBlock() + " protected/unbreakable — BotMining will release it every tick");
            }
        }
        mining.request(new BlockPos(x, y, z));
    }

    /** Place a footing block server-side (carried block when {@code placement.consumesBlocks}, else conjured);
     *  the placement half of the reconcile seam, mirroring the navigator's applyEdits place path for a single
     *  cell. */
    @Override
    public void place(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos p = new BlockPos(x, y, z);
        BlockState existing = level.getBlockState(p);
        Config cfg = ConfigLoader.config();
        // A protected occupant is never cleared NOR replaced by a placement (filling the cell destroys it
        // either way) — the planner's OPEN_PLACE bit excludes protected cells; this live backstop also
        // covers a stale grid. Give up on the cell; the next tick / replan nets it.
        if (!existing.isAir() && cfg.protectedBlocks().matches(existing)) {
            refusalLog("place-prot|" + x + "," + y + "," + z, "place REFUSED at (" + x + "," + y + "," + z
                    + "): protected occupant " + existing.getBlock());
            return;
        }
        if (!Replaceable.isReplaceable(existing)) {
            // Planner/executor vocabulary gap: the search's open-for-place bit is SHAPE-based (an
            // empty-collision cell — sweet berry bush, torch, sapling — is open to place into), but
            // vanilla replaceability is stricter. Refusing outright here made a planned place silently
            // no-op, so the bot jumped onto a cap that never existed (the berry-maze hop-over bug). Do
            // what a player does instead: clear the soft occupant, then place. Every empty-shape
            // occupant is soft (hardness ~0), so the clear is effectively free and stays unpriced
            // planner-side (EditScratch.requireFloor). mayBreak refuses an unbreakable occupant (give
            // up, replan nets it) AND an owner-protected one (mining.protectedBlocks — never broken).
            if (!cfg.mayBreak(existing, existing.getDestroySpeed(level, p))) {
                refusalLog("place-occ|" + x + "," + y + "," + z, "place REFUSED at (" + x + "," + y + "," + z
                        + "): unbreakable/protected occupant " + existing.getBlock());
                return;
            }
            WorldEdits.breakBlock(level, p);
        }
        lookAtCell(x, y, z); // look at what we place — for a pillar footing that's straight down, like a player
        if (cfg.consumesBlocks()) {
            Block block = new BotInventory(this).consumeOnePlaceable();
            if (block == null) { // out of blocks — the next tick / replan nets it
                refusalLog("place-inv|" + x + "," + y + "," + z, "place REFUSED at (" + x + "," + y + "," + z
                        + "): out of placeable blocks");
                return;
            }
            WorldEdits.placeBlock(level, p, block.defaultBlockState());
        } else {
            WorldEdits.placeBlock(level, p, placeBlock()); // conjured, infinite supply
        }
        this.swing(InteractionHand.MAIN_HAND);
    }

    // ---- Survival gating (the bot runs the full vanilla player tick via doTick — see tick()) ----------
    // Two of the now-live survival systems are gated by their config flags by intercepting vanilla's own
    // decrement hooks, so when a flag is ON the bot uses the real vanilla machinery unchanged. Damage is
    // gated separately by setInvulnerable(!takesDamage) in tick() (Entity.setInvulnerable is stable across
    // versions; overriding hurt() is not — it split into hurtServer/hurtClient at 1.21.5).

    /**
     * Bring the bot back to life after {@code /bot spawn} restored a DEAD saved profile (see the call in
     * {@link BotManager#spawnBotFor}). A no-op unless the bot is actually dead, so a live/returning bot keeps
     * its state. This reproduces the observable state of vanilla's respawn, which never revives in place — it
     * builds a fresh entity and copies from the old one via {@code ServerPlayer.restoreFrom(old, false)}, whose
     * death branch is {@code setHealth(getMaxHealth())}. We reuse-and-reload instead of constructing fresh, so
     * the death fields the reload dragged back in are reset explicitly: {@code Health} (persisted, comes back 0)
     * and {@code deathTime} (persisted). {@code dead} is transient (never in NBT) so a freshly-constructed
     * bot already has it {@code false}; it's reset too as belt-and-suspenders in case the load path evolves.
     *
     * <p>Public so the {@code overlays/1.21.9} {@code BotSpawn} can call it BETWEEN the .dat load and
     * {@code placeNewPlayer} — that ordering is what fixes the intermittent client death-render (the spawn
     * metadata is snapshotted synchronously inside {@code placeNewPlayer}, so health must be restored first).
     */
    public void reviveIfDead() {
        if (this.getHealth() > 0.0F) return;
        this.setHealth(this.getMaxHealth());
        this.deathTime = 0;
        this.dead = false;
    }

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
