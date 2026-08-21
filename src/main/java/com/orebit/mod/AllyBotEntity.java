package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.HierarchicalRegionPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.ClutchModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Climb;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.ChunkTracking;
import com.orebit.mod.platform.ClientLoad;
import com.orebit.mod.platform.CommandFeedback;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.MoveReport;
import com.orebit.mod.platform.Replaceable;
import com.orebit.mod.platform.StepHeight;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
    /** The {@code /bot craft} craft-from-inventory state machine. */
    private final BotCrafter crafter;
    /** The {@code /bot farm} tend-the-farm state machine. */
    private final BotFarmer farmer;
    /** The cross-cutting self-defense interrupt (NOT a mode — pre-dispatch, consumed-tick). */
    private final BotFighter fighter;
    /** The {@code /bot build} schematic-execution state machine. */
    private final BotBuilder builder;
    /** The {@code /bot roam} outward-biased random-wander state machine. */
    private final BotRoamer roamer;
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
     * The bot's movement mode to seed the planner's start node with — its REAL pose ({@code Pose.SWIMMING},
     * the 0.6-tall hitbox) ⇒ {@link MovementContext#MODE_PRONE}, so a replan that fires mid-sprint-swim keeps
     * the prone state instead of re-deriving STANDING from a buoyancy bob and re-initiating (or, in genuine
     * 1-deep water, getting stuck unable to re-initiate). Otherwise {@link BlockPathfinder#MODE_AUTO} lets the
     * search derive the mode from the start geometry.
     *
     * <p>Reads the same POSE as {@link #prone()} rather than the {@code isSwimming()} flag, for the same
     * reason and so planner and follower cannot disagree about what mode the bot is in: in the one state where
     * the two differ (sprint dropped under a 1-tall ceiling — flag false, hitbox still 0.6) seeding
     * {@code MODE_AUTO} would have the search plan a 1.8-tall body through a gap the bot is lying in.
     */
    int currentStartMode() {
        return this.prone() ? MovementContext.MODE_PRONE : BlockPathfinder.MODE_AUTO;
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
     *
     * <p><b>{@link Mode#ROAM} derives from that base rather than replacing it.</b> A roaming bot plans with
     * {@link BotCaps#mayFall} OFF — the whole point of {@code /bot roam} is a bot that explores without
     * stepping off a cliff or the rim of a floating island — but every other capability is still the owner's
     * configured one, so roaming does not quietly become a second config. This is THE seam the restriction
     * flows through: {@code caps()} is the single object the navigator's {@link
     * com.orebit.mod.pathfinding.PathPlan} construction, the region tier and {@link #inventoryFeasibility}
     * all read, so gating here gates the whole two-tier search with no per-movement plumbing.
     */
    BotCaps caps() {
        BotCaps base = ConfigLoader.botCaps();
        if (mode != Mode.ROAM) return base;
        // Derive once per distinct base object, not per call. botCaps() is a volatile field replaced wholesale
        // by /bot config reload, so identity IS the staleness test — steady-state roaming allocates nothing,
        // and a reload is picked up on the next replan exactly as it is for every other mode.
        if (base != roamCapsBase) {
            roamCapsBase = base;
            roamCaps = base.withMayFall(false);
        }
        return roamCaps;
    }

    /** The {@link ConfigLoader#botCaps()} object {@link #roamCaps} was derived from — the identity-staleness
     *  test above. Null until the bot first plans while roaming. */
    private BotCaps roamCapsBase;
    /** The cached Fall-free derivation of {@link #roamCapsBase} handed to the planner while roaming. */
    private BotCaps roamCaps;

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
                (ServerLevel) Worlds.of(this),
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
     *   <li>{@link Mode#CRAFT} — the craft-from-inventory loop ({@code /bot craft}); see
     *       {@link BotCrafter}.
     *   <li>{@link Mode#FARM} — the tend-the-farm pass ({@code /bot farm}); see {@link BotFarmer}.
     *   <li>{@link Mode#BUILD} — the schematic build ({@code /bot build}); see {@link BotBuilder}.
     *   <li>{@link Mode#ROAM} — the open-ended outward wander ({@code /bot roam}); see {@link BotRoamer}. The
     *       one mode that changes the bot's PLANNER CAPABILITIES rather than just its goal: it plans with
     *       {@link BotCaps#mayFall} off (see {@link #caps()}), so it never routes off a ledge.
     * </ul>
     */
    public enum Mode { FOLLOW, STAY, COME, GATHER, CRAFT, FARM, BUILD, ROAM }

    private Mode mode = Mode.FOLLOW;
    private BlockPos comeTarget;    // fixed summon cell (owner's feet block at /bot come time)
    // The current COME drive's arrival/planner tolerance. come/follow keep the loose no-overlap default;
    // goto passes an EXACT tolerance (see comeTo overload) so the bot reaches the precise block.
    private double comeArriveDist = BotNavigator.ARRIVE_DIST;
    private double comeArriveY = BotNavigator.ARRIVE_Y;
    private int comeGoalTol = BlockPathfinder.DEFAULT_GOAL_TOL_XZ;
    /** Arrival-settle anchor (owner-ratified 2026-08-19, DESIGN-servo-normalization.md §2.6): the
     *  completed plan's FINAL waypoint cell, armed at the COME→STAY arrival flip and driven by
     *  {@link #holdPosition} so the bot rests on that cell's CENTRE ("stand EXACTLY there" — e.g. keeping
     *  a mob spawner active from exact afk coordinates) instead of parking wherever it first clipped the
     *  arrival radius. Deliberately NOT {@link #comeTarget}: under come-loose tolerance the plan ends
     *  NEAR that cell and the bot must not stand ON it. {@code null} — a command-STAY or a planless
     *  arrival — keeps the plain hold. Cleared on every mode change / new command (the same reset paths
     *  as {@code comeTarget}). */
    private BlockPos settleAnchor;

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
        // Undo ServerPlayer's 1.0 auto-step (see platform/StepHeight). A real player never spends that
        // value — their physics runs client-side on LocalPlayer, which keeps LivingEntity's 0.6 — but this
        // bot runs Entity.move server-side, so without the pin it silently climbs a full block off a
        // FALLING tick and lands a tread above the one the planner routed it to. No-op from 1.20.5, where
        // Mojang dropped the assignment and step height became an attribute.
        StepHeight.pinToPlayerDefault(this);
        this.owner = owner;
        this.mining = new BotMining(this);
        this.navigator = new BotNavigator(this);
        this.gatherer = new BotGatherer(this);
        this.crafter = new BotCrafter(this);
        this.farmer = new BotFarmer(this);
        this.fighter = new BotFighter(this);
        this.builder = new BotBuilder(this);
        this.roamer = new BotRoamer(this);
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

    /** Readable per-journey search-health telemetry lines for {@code /bot stats} (current + last-completed
     *  journey). Pure observation — see {@link NavJourneyStats}. */
    public java.util.List<String> navStatsReport() {
        return navigator.statsReport();
    }

    // ---- Read-only gather observation (HeadlessAutotest only; NO logic change) ------------------
    // Delegate to the gatherer's read-only seams so the headless gather autotest can report the phase
    // reached and the accrued count without reaching into private component state.

    /** Current {@link BotGatherer} phase name for the harness ({@code "IDLE"} when not gathering). */
    String gatherPhaseName() {
        return gatherer.phaseName();
    }

    /** Items accrued toward the current gather quota (the picked-up count). */
    int gatheredCount() {
        return gatherer.gatheredCount();
    }

    /** Where the active {@code /bot gather} run was issued — its RETURN target (null before a run). */
    net.minecraft.core.BlockPos gatherStartPos() {
        return gatherer.gatherStartPos();
    }

    /** Current {@link BotCrafter} phase name for the harness ({@code "IDLE"} when not crafting). */
    String craftPhaseName() {
        return crafter.phaseName();
    }

    /** Result items produced toward the current craft target. */
    int craftedCount() {
        return crafter.craftedCount();
    }

    /** Landed combat strikes this session (observation only — written by {@link MobStrategy#strike},
     *  read by the harness; never consulted by behavior). */
    int combatStrikes;

    /** Whether the self-defense interrupt consumed the last tick (harness observation). */
    boolean fighterEngaged() {
        return fighter.engaged();
    }

    /** Landed combat strikes (harness observation). */
    int combatStrikes() {
        return combatStrikes;
    }

    /** Current {@link BotBuilder} phase name for the harness ({@code "IDLE"} when not building). */
    String buildPhaseName() {
        return builder.phaseName();
    }

    /** Blocks placed this build run (harness observation). */
    int buildPlacedCount() {
        return builder.placedCount();
    }

    /** Blocks cleared this build run (harness observation). */
    int buildClearedCount() {
        return builder.clearedCount();
    }

    /** Current {@link BotFarmer} phase name for the harness ({@code "IDLE"} when not farming). */
    String farmPhaseName() {
        return farmer.phaseName();
    }

    /** Mature crops harvested this farm pass. */
    int farmHarvestedCount() {
        return farmer.harvestedCount();
    }

    /** Seeds planted (crop verified) this farm pass. */
    int farmPlantedCount() {
        return farmer.plantedCount();
    }

    /** Cells tilled to farmland (verified) this farm pass. */
    int farmTilledCount() {
        return farmer.tilledCount();
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
     *  below, this is a straight-down look. Package-private: task components (e.g. {@link BotCrafter}) face
     *  their work cell through this. */
    void lookAtCell(int x, int y, int z) {
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
        this.settleAnchor = null;   // any mode change disarms the arrival settle (the arrival site re-arms)
        navigator.clearPlan();
        portalFollower.resetPortalSeek(); // a fresh command restarts (and re-announces) any cross-dimension seek
    }

    /** {@code /bot come}: path once to {@code summonCell} (the caller's feet block), then hold there. */
    public void comeTo(BlockPos summonCell) {
        this.mode = Mode.COME;
        this.comeTarget = summonCell.immutable();
        this.settleAnchor = null;   // a fresh summon disarms any previous arrival's settle
        this.comeArriveDist = BotNavigator.ARRIVE_DIST;
        this.comeArriveY = BotNavigator.ARRIVE_Y;
        this.comeGoalTol = BlockPathfinder.DEFAULT_GOAL_TOL_XZ;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
    }

    /** Come to a cell with an explicit arrival tolerance. {@code goto} passes an EXACT tolerance so the bot
     *  reaches the precise block; {@code come}/follow keep the loose no-overlap default. */
    public void comeTo(BlockPos summonCell, double arriveDist, double arriveY, int goalTol) {
        comeTo(summonCell);                 // reuse the existing setup (mode, target, clearPlan, resetPortalSeek)
        this.comeArriveDist = arriveDist;
        this.comeArriveY = arriveY;
        this.comeGoalTol = goalTol;
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

    /** {@code /bot gather <output> [count]}: switch to {@link Mode#GATHER} and start the {@link BotGatherer}
     *  loop for the resolved drop {@code output} (Phase 2 — its source resource is scanned/mined with the
     *  silk-aware goal tool), targeting {@code quota} picked-up items of the output Y. */
    public void startGather(com.orebit.mod.worldmodel.resource.DropModel.Output output, int quota) {
        this.mode = Mode.GATHER;
        this.comeTarget = null;
        this.settleAnchor = null;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
        gatherer.startGather(output, quota);
    }

    /** {@code /bot craft <item> [count]}: switch to {@link Mode#CRAFT} and start the {@link BotCrafter}
     *  loop for result name {@code item} (a {@link com.orebit.mod.crafting.RecipeIndex} name), targeting
     *  {@code count} result items. */
    public void startCraft(String item, int count) {
        this.mode = Mode.CRAFT;
        this.comeTarget = null;
        this.settleAnchor = null;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
        crafter.startCraft(item, count);
    }

    /** {@code /bot farm}: switch to {@link Mode#FARM} and start a {@link BotFarmer} tending pass
     *  anchored at the bot's current cell. */
    public void startFarm() {
        this.mode = Mode.FARM;
        this.comeTarget = null;
        this.settleAnchor = null;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
        farmer.startFarm();
    }

    /**
     * {@code /bot roam [radius]}: switch to {@link Mode#ROAM} and start a {@link BotRoamer} wander anchored at
     * the bot's current cell, staying within {@code radius} blocks of it. Runs until another {@code /bot}
     * command changes the mode. Roaming plans WITHOUT the {@code Fall} movement — see {@link #caps()}.
     */
    public void startRoam(int radius) {
        this.mode = Mode.ROAM;
        this.comeTarget = null;
        this.settleAnchor = null;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
        roamer.startRoam(radius);
    }

    /** {@code /bot build <name> <x y z>}: switch to {@link Mode#BUILD} and start a
     *  {@link BotBuilder} run of {@code schematic} anchored at {@code origin}. */
    public void startBuild(com.orebit.mod.building.Schematic schematic, BlockPos origin) {
        this.mode = Mode.BUILD;
        this.comeTarget = null;
        this.settleAnchor = null;
        navigator.clearPlan();
        portalFollower.resetPortalSeek();
        builder.startBuild(schematic, origin);
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
        boolean abilitiesDirty = false;
        if (this.getAbilities().invulnerable != immune) {
            this.getAbilities().invulnerable = immune;
            abilitiesDirty = true;
        }
        // mayfly is a THIRD, independent gate, and it is specific to FALL damage: Player.causeFallDamage opens
        // with `if (abilities.mayfly) return false;` — literally the first two ops (javap, 1.21.11) — so a bot
        // carrying it takes ZERO fall damage however the two flags above are set. That makes it invisible to
        // every other mortality check and silently turns a clutch test into a tautology: the bot survives the
        // drop whether or not the clutch fired.
        //
        // setGameMode(SURVIVAL) at spawn clears it, so on the normal path this is dead code. It is kept because
        // the flag can be reintroduced behind our back (a /gamemode on the bot, a force-gamemode server, a
        // restored profile) and because "no fly" is already a stated goal of the survival-player model. Forcing
        // it false costs an immune bot nothing — entity-level invulnerability still blocks the damage a step later.
        if (this.getAbilities().mayfly) {
            this.getAbilities().mayfly = false;
            this.getAbilities().flying = false;
            abilitiesDirty = true;
        }
        if (abilitiesDirty) {
            this.onUpdateAbilities();
        }
        // Teleport-handshake completion (2026-08-16, the mazelava lava-immunity dig). A cross-dimension
        // teleport sets ServerPlayer.isChangingDimension, and vanilla clears it in EXACTLY one place:
        // handleAcceptTeleportPacket — the client's acknowledgment. While set, isInvulnerableTo returns
        // true for EVERY damage source on EVERY supported version (javap: 1.17.1's and 1.21.11's both
        // gate on it), so a clientless bot that ever crosses a dimension — portal FOLLOW, cross-dim
        // restore, a course teleport — is PERMANENTLY unhurtable. Trace-convicted on the mazelava card:
        // one trial after an End round-trip, 45 ticks in lava, LAVA_IGNITE landing (fire=300) and
        // hp=20.0 flat. The fake client completes every client handshake instantly (the platform/
        // ClientLoad join-load precedent — its sibling latch, clientLoadedTimeoutTimer, self-heals via
        // tickClientLoadTimeout; THIS one has no timeout, only the ack). Range-stable direct call: both
        // methods are public and identical 1.17.1→1.21.11, the swimHazardAt direct-call precedent.
        // NOTE the third sibling is NOT handled here: ServerPlayer.die() latches waitingForRespawn on the
        // connection, cleared only by the client's PERFORM_RESPAWN — a died-then-revived bot keeps
        // hasClientLoaded()==false on 1.21.11+. Separate arc; see the session notes.
        if (this.isChangingDimension()) {
            this.hasChangedDimension();
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
        this.setSneak(false);     // sneak is re-asserted per-step by a move that needs it (Climb lateral hold).
                                  // Through setSneak, NOT setShiftKeyDown directly: setSneak is the only
                                  // writer of sneakInputHeld, so resetting the vanilla input alone left the
                                  // field latched true after the first press of a bot's life — poisoning
                                  // sneakHeld() -> hangingOnClimbable() -> settled() -> Fall.done/reached.
                                  // sneakAppliedLastTick (snapshotted post-physics, end of this method) is
                                  // what keeps PRE-drive readers honest across this reset — see sneakHeld().
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

        // SLOWTICK: roll the per-server-tick boundary at the EARLIEST instrumented entry (the bot's entity tick
        // runs before this level's onWorldTickEnd), then time the whole nav dispatch as the botTick phase. This
        // is the sync replan/search + steer; diagnosis only, no behaviour change.
        SlowTickMonitor.beginTick(level.getServer());
        final long botTickStart = System.nanoTime();
        // SELF-DEFENSE INTERRUPT (DESIGN-bot-abilities.md §2.3): checked BEFORE the mode dispatch
        // each tick — while a threat is engaged, combat CONSUMES the tick and the current mode's
        // machine is simply not stepped (its state freezes in place and resumes when combat ends;
        // the followThroughPortal consumed-tick precedent). Quiescent under the invulnerable
        // default (mobs never target an abilities-invulnerable player).
        if (!fighter.defendTick()) {
            switch (mode) {
                case STAY -> holdPosition();
                case GATHER -> gatherer.gatherLoopTick();
                case CRAFT -> crafter.craftLoopTick();
                case FARM -> farmer.farmLoopTick();
                case BUILD -> builder.buildLoopTick();
                case ROAM -> roamer.roamLoopTick();
                case COME -> {
                    // Summon to a fixed cell; once there, settle into STAY (distinct from FOLLOW, which
                    // would keep chasing). comeTarget can't be null in COME, but guard defensively.
                    if (comeTarget == null) { setMode(Mode.STAY); holdPosition(); break; }
                    // Cross-dimension guard: comeTarget's coordinates were captured in the CALLER's level, so
                    // while the owner is elsewhere the bot follows them through a portal instead of pathing to
                    // a cell that means nothing in this level.
                    if (portalFollower.followThroughPortal()) break;
                    double tx = comeTarget.getX() + 0.5, ty = comeTarget.getY(), tz = comeTarget.getZ() + 0.5;
                    if (navigator.driveToward(tx, ty, tz, comeTarget.below(),
                            comeArriveDist, comeArriveY, comeGoalTol, comeGoalTol)) {
                        setMode(Mode.STAY); // arrived — then arm the settle AFTER the mode flip clears it:
                        settleAnchor = navigator.arrivedPlanEnd(); // rest on the plan's final cell (§2.6)
                    }
                }
                default -> { // FOLLOW
                    if (!portalFollower.followThroughPortal()) {
                        navigator.driveToward(owner.getX(), owner.getY(), owner.getZ(),
                                owner.blockPosition().below());
                    }
                }
            }
        }
        SlowTickMonitor.botTick(botTickStart);

        // Vanilla client-side sneak slowdown (LocalPlayer.aiStep scales a crouching player's movement inputs to
        // ~0.3×). The headless bot never runs that client tick, so a bot holding sneak would otherwise move at
        // FULL walk speed — a hack. Apply the same scaling here, ONLY while shiftKeyDown, so a sneaking bot
        // (today: Climb's lateral hold) genuinely moves at ~30% walk speed: its on-climbable lateral velocity
        // becomes ~0.065 b/t (below the ±0.15 clamp, so this scaling — not the clamp — governs), matching
        // Climb.GRAB_LATERAL_COST's ~15.44 t/block derivation. Non-sneaking movement is untouched.
        if (this.isShiftKeyDown()) {
            this.xxa *= Climb.SNEAK_SPEED_FACTOR;
            this.zza *= Climb.SNEAK_SPEED_FACTOR;
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

        // Recentre the bot's player chunk tickets on this tick's FINAL position (platform/ChunkTracking):
        // the ServerChunkCache.move a real client's move packets drive — a clientless bot never receives
        // them, so its sim bubble stays at spawn and far chunks freeze (the 2026-08-19 floating-bamboo
        // forensic). Self-guards on section change; a no-op tick is one section compare.
        ChunkTracking.recenter(this);

        // Snapshot the sneak input that just DROVE this tick's physics (see sneakHeld()). Taken after
        // doTick so it is exactly "the arrest input in force when the current pose was produced" — the
        // value the NEXT tick's pre-drive evaluations (the follower's advance scan / doneNow, which run
        // AFTER the tick-top input reset but BEFORE any drive re-asserts) must read to judge that pose.
        this.sneakAppliedLastTick = this.sneakInputHeld;

        // Read the prone-pose state AFTER doTick (vanilla's updateSwimming ran inside it, from THIS tick's
        // inputs + resulting position), so a PRONE->STAND flip is dumped with the state that caused it.
        if (Debug.VERBOSE) logSwimTransition();

        // Actuate the "hands": drive any requested block break one tick (real tool + timing + drops). Runs after
        // doTick so the break reflects this tick's inputs/position; a no-op when nothing was requested this tick.
        // Skipped on the teleport tick — `level` above is the pre-teleport level, and any in-flight break
        // request belongs to it.
        if (!levelChanged) mining.tick(level);
    }


    /** STAY: stop in place and face the owner — or, while an arrival armed {@link #settleAnchor}, drive
     *  the settle instead: the unified core's HOLD at the final plan cell's centre
     *  ({@link SteerControl#restHold}, DESIGN-servo-normalization.md §2.6), whose deadband quiescence is
     *  the rest state and whose anchored pull actively re-centres an externally pushed bot. LAND-only
     *  this round (grounded, not in water, not on a climbable — a bot arriving swimming keeps the plain
     *  hold), and the guard re-asks per tick, so a settled bot pushed off its medium simply pauses the
     *  settle for those ticks. A command-{@code /bot stay} never arms the anchor, so it keeps today's
     *  behavior unchanged. */
    private void holdPosition() {
        if (settleAnchor != null && grounded() && !isInWater() && !onClimbable()) {
            navigator.clearPlan(); // STAY still holds no goal — the settle is a servo, not a plan
            SteerControl.restHold(this, settleAnchor.getX() + 0.5, settleAnchor.getZ() + 0.5);
            return;
        }
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
        return traceTo(this.blockPosition().below(), goalFloor);
    }

    /**
     * As {@link #traceTo(BlockPos)} but seeded from an EXPLICIT start floor cell — the
     * {@code /bot trace <from> <to>} form (owner request 2026-08-04).
     *
     * <p><b>Why a synthetic start is needed.</b> The default start is wherever the bot physically is, which
     * leaves some nodes untraceable: a vine hang cannot be held by hand, because an idle bot has no reason
     * to press sneak and slides out of the cell the moment it is teleported there — and blocking the cell to
     * hold it would change the very geometry under test. Seeding the search directly removes the
     * choreography. The goal, skeleton, corridor and goal-forced-cost premium all still derive exactly as
     * they do live, so the node's expansion is the real one.
     *
     * <p>The start must be a cell the search accepts as a node; a nonsense one simply yields an immediate
     * failure, which the dump header reports. The resulting plan describes that CELL, not the bot's pose —
     * it answers "which candidate does the search relax here", not "what would the bot do right now".
     */
    public String traceTo(BlockPos startFloor, BlockPos goalFloor) {
        setMode(Mode.STAY); // stop the per-tick replan/flood; the trace is a standalone one-shot search
        ServerLevel level = (ServerLevel) Worlds.of(this);
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
            // The WAYPOINT LIST, in the same shape the live "plan: N wp … path=[…]" log line prints
            // (owner request 2026-08-04). Without it the dump reports only a COUNT and a COST, so a trace
            // and the live plan it is meant to reproduce can only be compared as two scalars — which is how
            // a systematic 7-9 waypoint / 5-14 cost skew between them went unexplained across four separate
            // traces this session. Printing the cells makes the divergence point directly visible: where the
            // live plan skips cells this one walks is where the two searches stopped agreeing.
            if (plan != null) {
                StringBuilder wp = new StringBuilder("PATH: [");
                for (int i = 0; i < plan.size(); i++) {
                    if (i > 0) wp.append(' ');
                    // move NAME per waypoint, not just the cell: the leading hypothesis for the skew is that
                    // the cuboid MACRO layer collapses runs of steps in the live plan but not here, and a
                    // macro is only distinguishable from the ordinary move it replaces by its name.
                    wp.append(plan.movement(i) == null ? "?" : plan.movement(i).getClass().getSimpleName())
                            .append(compact(plan.waypoint(i)));
                }
                w.write(wp.append("]\n").toString());
            }
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
                    + "  (FULL CASCADE — what /bot goto evaluates; expansions level-tagged 'E <seq> L<level>')\n");
            if (skeletonDump != null) {
                w.write("\n== live cascade skeleton (cross-check) ==\n" + skeletonDump + "\n");
            }
            w.write("\nlegend: 'E <seq> L<level> region=x,y,z frag=<f> g=<g> f=<f> [kind]' = one expansion"
                    + " (pop), in order;  '  C <kind> -> x,y,z frag=<f> cost=<c> crossing=wx,wy,wz <OK|worse>'"
                    + " = a candidate edge it emitted. kinds: walk|air-fall|air-pillar|solid-mine|water-swim|"
                    + "collapsed|unbuilt|mine-sibling|mine-fallback|mine-solid|dig-through.\n\n");
            // Tool-aware region dig cost from the bot's real inventory (PERF-DESIGN region §5), so the trace's
            // dig breakdowns reflect the actual tools (null snapshot ⇒ the stone-tier RegionMineModel.DEFAULT).
            MovementContext.InventoryView traceInv = inventoryFeasibility();
            RegionMineModel mine = RegionMineModel.from(traceInv != null ? traceInv.mining() : null);
            final int minY = grid.minY();
            HierarchicalRegionPlan hier;
            RegionPathfinder.TRACE_OUT = w;
            RegionPathfinder.TRACE = true;
            try {
                // Run the FULL cascade exactly as /bot goto does: the cap-safe top level plans toward the goal,
                // each finer level plans toward the window sub-goal handed down from the level above. This is
                // what a real goto evaluates — NOT a single unbounded direct-L0 search (which was the old
                // trace). Each level's per-node flood is captured, level-tagged in the 'E <seq> L<level>' lines,
                // so the TOP level's flooding (e.g. L1 up-and-over) is greppable: grep '^E .* L1 '.
                // traceInv rides along so the seed-time dominance sig matches the live plan's; a build never
                // RECORDS into the crossing memory (only onBlocked/blacklistCurrentHop do, which a trace
                // never drives), so the trace stays a pure diagnostic.
                hier = HierarchicalRegionPlan.build(grid, minY, startFloor, goalFloor, caps, mine, traceInv);
            } finally {
                RegionPathfinder.TRACE = false;
                RegionPathfinder.TRACE_OUT = null;
            }
            // Per-level skeleton result (top→0): what each level committed to, so the flooded top level's
            // partial (reachedGoal=false) is visible alongside its expansions above.
            StringBuilder sb = new StringBuilder("\nRESULT: cascade top=L" + hier.topLevel()
                    + (hier.isFailed() ? "  FAILED (no route — l0Skeleton null)" : "") + "\n");
            for (int L = hier.topLevel(); L >= 0; L--) {
                RegionPathPlan sk = hier.skeletonAt(L);
                if (sk == null) {
                    sb.append("  L").append(L).append(": (null)\n");
                    continue;
                }
                sb.append("  L").append(L).append(": ").append(sk.size()).append(" regions")
                        .append(sk.reachedGoalRegion() ? " (reached goal region)" : " (PARTIAL — goal not reached)")
                        .append(" committed=").append(hier.committedAt(L)).append('\n');
                for (int i = 0; i < sk.size(); i++) {
                    sb.append("    [").append(i).append("] region=").append(sk.rx(i)).append(',')
                            .append(sk.ry(i)).append(',').append(sk.rz(i));
                    if (sk.isFragmentModel()) sb.append(" frag=").append(sk.fragmentId(i));
                    if (sk.hasPortal(i)) sb.append(" crossing=").append(sk.portalCell(i));
                    sb.append('\n');
                }
            }
            w.write(sb.toString());
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

    @Override public double velX() { return this.getDeltaMovement().x; }
    @Override public double velY() { return this.getDeltaMovement().y; }
    @Override public double velZ() { return this.getDeltaMovement().z; }

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
     * Whether the bot's hitbox is the 0.6-tall prone one — read from the <b>POSE</b>, not the {@code
     * isSwimming()} flag (NOTES-vanilla-fluid-physics.md §3.2). The two diverge in exactly one state and it
     * is a state this design creates: dropping sprint clears the flag on the very next tick, but
     * {@code Player.updatePlayerPose} then FIT-TESTS the desired STANDING pose and falls back through
     * CROUCHING to {@code Pose.SWIMMING} when the bot has no headroom (bytecode-verified against 1.21.11) — so
     * a bot in a 1-tall gap reads "not swimming" while still physically 0.6 tall. Every caller of this seam
     * (EndSprintSwim's stand-up gate, SprintSwim's cursor gate, StartSprintSwim's dive gate) cares about the
     * HITBOX, so the flag would let a move declare the pose flipped a tick or more before it actually did, and
     * hand the next move a frame the bot does not fit — the same class of defect as the Surface cursor skip.
     */
    @Override public boolean prone() { return this.getPose() == Pose.SWIMMING; }

    @Override
    public void faceTowards(double dx, double dy, double dz) {
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > 1.0e-4) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            this.setYRot(yaw);
            this.setYBodyRot(yaw);
            this.setYHeadRot(yaw);
        }
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz))); // dy<0 → look down → dive
        this.setXRot(pitch);
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

    /** Lateral input for the {@link BotSteering} seam: vanilla's {@code xxa} (positive = strafe LEFT). Already
     *  zeroed each tick beside {@code yya}/jump and already scaled by {@code Climb.SNEAK_SPEED_FACTOR} under
     *  sneak, so this write is the whole plumbing — the field was reset-and-scaled long before anything set it. */
    @Override public void setStrafe(float xxa) { this.xxa = xxa; }

    /** Sneak input for the {@link BotSteering} seam: vanilla {@code Entity.setShiftKeyDown}. Held true on a
     *  climbable, {@code isSuppressingSlidingDownLadder()} zeroes the {@code −0.15}/t slide so the bot holds
     *  its height (Climb's lateral grab). Reset false at the top of each tick alongside jump/sprint. */
    @Override public void setSneak(boolean sneaking) { this.sneakInputHeld = sneaking; this.setShiftKeyDown(sneaking); }

    // setSprinting(boolean) is satisfied by the inherited public LivingEntity method.
    /** Widen the inherited protected {@code setJumping} to public so it satisfies the {@link BotSteering} seam.
     *  Held true, vanilla {@code aiStep} jumps on land and swims up in water — the one climb mechanism.
     *  Shadowed into {@link #jumpHeld()} for the diagnostic harnesses (no reflection into the protected
     *  vanilla field). */
    @Override public void setJumping(boolean jumping) { this.jumpInputHeld = jumping; super.setJumping(jumping); }

    /** Shadow of the jump INPUT forged this tick (reset false with the other inputs at the top of each
     *  tick, re-held by whichever steer/drive wants it — so true means "held RIGHT NOW"). */
    private boolean jumpInputHeld;

    /** Diagnostic read of the live jump input — the course harnesses' trace column, so a held-jump ×
     *  climbable capture is convicted from data instead of supposition. */
    public boolean jumpHeld() { return jumpInputHeld; }

    /** Shadow + diagnostic read of the sneak input, same pattern as {@link #jumpHeld()} — sneak is the
     *  load-bearing input in the climbable lateral-cling and edge-guard mechanisms. */
    private boolean sneakInputHeld;

    /** The sneak input that drove the LAST completed physics tick — snapshotted at the end of tick(),
     *  after doTick. See {@link #sneakHeld}. */
    private boolean sneakAppliedLastTick;

    /**
     * Whether the sneak (arrest) input is held — "held now, or in force when the current pose was
     * produced". The OR is load-bearing (the 2026-08-20 flagship vine-hang freeze at (56,170,257)): the
     * follower's advance scan and {@code doneNow} evaluate {@code hangingOnClimbable() -> settled() ->
     * done/reached} BEFORE any drive has re-asserted this tick's inputs, and the tick-top reset has
     * already cleared {@code sneakInputHeld} by then — so a genuinely sneak-arrested hang (pose frozen,
     * stored velY pinned at the one-tick gravity −0.0784, below {@code CLIMBABLE_ARREST_VY}) read
     * {@code settled()==false} every scan and the Fall's hang landing could never complete. The old
     * pre-2026-08-20 LATCH (sneakInputHeld never reset) masked exactly this ordering dependency — and
     * over-reported held-ness everywhere else. The one-tick-stale OR is the honest middle: a pre-drive
     * reader judges the pose by the input that produced it; a drive that releases sneak reads held for
     * at most the release tick itself (the pose it is judging was still a held pose), and the snapshot
     * catches up at that tick's physics. State-based, no timers. */
    public boolean sneakHeld() { return sneakInputHeld || sneakAppliedLastTick; }

    // onClimbable() on the BotSteering seam is satisfied by the inherited public vanilla
    // LivingEntity.onClimbable() (the feet block vs #climbable) — a class method wins over the interface
    // default, so the bot reports its REAL arrest state while headless test doubles default false.

    /** The sink-through-a-scaffold-deck discriminator ({@link BotSteering#scaffoldingBelow}): sneak sinks
     *  through scaffolding's stand-on-top shape but would edge-guard-pin the bot on a ladder plate — see
     *  Climb's Δy&lt;0 grounded steer branch (NOTES-movement-physics.md §5). Live read, solidAt pattern. */
    @Override
    public boolean scaffoldingBelow() {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        return level.getBlockState(this.blockPosition().below()).is(Blocks.SCAFFOLDING);
    }

    /** The topped-out-on-a-curtain discriminator ({@link BotSteering#climbableBelow}). Same live read as
     *  {@link #scaffoldingBelow}, against the same rule vanilla's own {@code onClimbable} applies to a feet
     *  cell — the {@code #climbable} tag PLUS the hardcoded {@code trapdoorUsableAsLadder} special case
     *  (DESIGN-trapdoor-ladder-climb.md §1/§5: an OPEN trapdoor over an equal-facing {@code Blocks.LADDER}
     *  is a rung; HALF/waterlogging not consulted) — so the two stances (feet IN vs feet ABOVE) are
     *  classified by one consistent rule. The trapdoor arm is what lets the §3.4 top-out ABOVE a mouth
     *  reach {@code Climb.reached} / hold the jump stance instead of livelocking (the vanilla predicate
     *  itself only ever evaluates the bot's OWN feet cell, so this below-cell mirror is hand-rolled — two
     *  live block reads, the second behind the trapdoor instanceof). */
    @Override
    public boolean climbableBelow() {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos below = this.blockPosition().below();
        BlockState s = level.getBlockState(below);
        if (s.is(BlockTags.CLIMBABLE)) return true;
        if (!(s.getBlock() instanceof TrapDoorBlock) || !s.getValue(BlockStateProperties.OPEN)) return false;
        BlockState ladder = level.getBlockState(below.below());
        return ladder.is(Blocks.LADDER)
                && ladder.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        == s.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    /**
     * The floor-under-the-curtain discriminator ({@link BotSteering#standableBelow}) — is the block below the
     * feet solid, so the climbable sneak-hold (and with it the vanilla ledge edge-guard) must NOT be pressed?
     * Same live read as {@link #climbableBelow}, but classified through {@link NavBlock#isStandable} — the
     * SEARCH's own floor bit — rather than raw collision, so a ladder plate / fence / dripstone tip does not
     * read as a floor (mirrors {@code BotNavigator.isStandableFloor}, which answers the same question off the
     * same bit on the nav-grid side).
     *
     * <p><b>ONE cell down, across the SUPPORTING columns</b> (owner ruling, 2026-08-02). The question is only
     * ever "would sneaking stop me walking off an edge", so the probe is a single block below the feet — no
     * fall-distance envelope is involved. But it must be read in <b>the column(s) still supporting the feet</b>,
     * not merely {@code blockPosition()}: a bot 99% into its new cell with a 1% overhang on the old one reports
     * the NEW cell as its block position while the edge-guard is still keyed to the old one. Measured on the
     * convicted wedge — bot box spanning {@code x 59.989…60.589}, one cell down:
     * <pre>
     *   (60,170,255) = vine          &lt;- the new cell: NOT standable
     *   (59,170,255) = jungle_leaves &lt;- still supporting the feet: standable
     * </pre>
     * Probing only {@code blockPosition()} answers "no floor" and re-wedges the bot; scanning the columns the
     * bounding box actually overlaps answers "floor" and lets it step off. (An earlier form compensated for the
     * wrong column by scanning two cells DOWN — that reached the leaves by accident, and dragged in a
     * ~3-block fall window nothing had derived.)
     *
     * <p>Deliberately <b>hazard-blind</b> (owner ruling): a damaging floor is still a floor. Avoiding hazards
     * is the PATHFINDER's job; the follower's job is to obey the plan it was given. Better to walk the planned
     * route and take the magma damage than to get clever, sneak or climb, and break the movement entirely.
     */
    @Override
    public boolean horizontalCollision() {
        return this.horizontalCollision;   // vanilla's own flag, set from the ATTEMPTED movement
    }

    @Override
    public boolean standableBelow() {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        final AABB box = this.getBoundingBox();
        final int y = this.blockPosition().getY() - 1;
        // Half-open on the max edge: a box that merely TOUCHES the next column's boundary is not overhanging it.
        final int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX - 1.0E-7);
        final int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ - 1.0E-7);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (NavBlock.isStandable(NavBlock.descriptorFor(
                        level.getBlockState(scratchPos.set(x, y, z))))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- Live-world geometry + block actions (the reconcile seam a MovePlan drives through) -----------

    /**
     * The walk-the-top-of-a-climbable footing test ({@link BotSteering#climbableFloorAt}) — the executor mirror
     * of {@code MovementContext.climbableFloorAt}, cell-for-cell and bit-for-bit: climbable, NOT standable, and
     * nothing climbable above (which is the boundary with {@code Climb}'s lateral cling on a curtain).
     *
     * <p>Classified through {@link NavBlock#descriptorFor} rather than raw block tests, so the runner and the
     * search read the SAME three bits off the same interning table — the two cannot drift apart. Live level
     * read (the {@link #solidAt} pattern), so it reflects the bot's own just-made edits.
     */
    @Override
    public boolean climbableFloorAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        long floor = NavBlock.descriptorFor(level.getBlockState(scratchPos.set(x, y, z)));
        if (!NavBlock.isClimbable(floor) || NavBlock.isStandable(floor)) {
            return false;
        }
        return !NavBlock.isClimbable(NavBlock.descriptorFor(level.getBlockState(scratchPos.set(x, y + 1, z))));
    }

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

    // The bot's body corridor INTO a cell (block-local 0..1 coords), keyed on the step's horizontal movement
    // direction — the "test the geometry against the movement route" primitive. The corridor spans the ENTRY
    // HALF of the cell along the travel axis (entry face → cell CENTRE), the 0.6-wide CENTRED band on the
    // PERPENDICULAR axis (so a panel running ALONG one side is missed), and always ABOVE the auto-step in Y (so
    // a low floor block is missed). That geometry distinguishes, with NO per-block-type gate:
    //   • an OPEN door / open trapdoor — thin panel PARALLEL to travel, against a side → outside corridor → pass;
    //   • a CLOSED door ACROSS the ENTRY — panel in the near half → spans the corridor → mine (iron still breaks);
    //   • a CLOSED door across the FAR face — outside the corridor → pass, the planner owns the exit (see below);
    //   • a carpet / pressure plate / bottom slab / snow layer under the feet — collision below the step → pass;
    //   • a full block (or fence post / wall reaching the centred column) → mine.
    // Blocks with EMPTY collision (ladders, signs, buttons, plants, open fence gates) short-circuit before this.
    //
    // ENTRY HALF, NOT THE WHOLE CELL (owner ruling 2026-08-14, the (216,-26,6) copper-door wedge). The planner
    // reasons CENTRE-TO-CENTRE: a step's contract is "get to the middle of the destination cell", and how to
    // LEAVE it is the next step's problem. `MovementContext.doorEntryClear` encodes exactly that — a door whose
    // blocked edge is not the ENTRY edge is free passage, folding no toggle, and the exit toggle is folded by
    // the following step via `exitDoorDecision`/`foldExitDoorToggle`. Testing the FULL cell here made the
    // executor one face stricter than the planner: entering a north-facing closed door from the north is free
    // by the plan (its panel is on the SOUTH face) but the full-cell corridor saw that panel, raised Need.AIR
    // on a cell no toggle was owed for — so `isOpenableCell` could not shield it — and fell through to mining a
    // door that `mining.protectedBlocks` protects by default. The result was a silent 1960-tick hold with one
    // deduped `mine REFUSED` line to show for it. Every other link in that chain was behaving as designed.
    //
    // Deliberately NOT terminus-aware (same ruling): "is this cell only being passed through?" is exactly the
    // question that recreates the wedge, because the bot WILL later traverse straight on through this door and
    // the executor would block it again. The half-cell rule is unconditional — the executor asks only whether
    // the bot can reach the centre, and the planner is trusted to open the door when it is time.
    private static final double C0 = 0.2, C1 = 0.8;              // the 0.6-wide centred footprint band
    private static final double MID = 0.5;                       // the cell centre — the step's real contract
    private static final double STEP_UP = 9.0 / 16.0;           // auto-step height (STEP_ASSIST_MAX_RISE)
    private static final VoxelShape CORRIDOR_VERT = Shapes.box(C0, STEP_UP, C0, C1, 1.0, C1); // dx==0 && dz==0
    // Along one axis: from the entry face to the centre. The SIGN of the travel delta picks which half.
    private static final VoxelShape CORRIDOR_XP = Shapes.box(0.0, STEP_UP, C0, MID, 1.0, C1); // travelling +X
    private static final VoxelShape CORRIDOR_XN = Shapes.box(MID, STEP_UP, C0, 1.0, 1.0, C1); // travelling −X
    private static final VoxelShape CORRIDOR_ZP = Shapes.box(C0, STEP_UP, 0.0, C1, 1.0, MID); // travelling +Z
    private static final VoxelShape CORRIDOR_ZN = Shapes.box(C0, STEP_UP, MID, C1, 1.0, 1.0); // travelling −Z
    // Diagonal: the entry QUADRANT, corner → centre, indexed [dx>0][dz>0].
    private static final VoxelShape CORRIDOR_D_PP = Shapes.box(0.0, STEP_UP, 0.0, MID, 1.0, MID);
    private static final VoxelShape CORRIDOR_D_PN = Shapes.box(0.0, STEP_UP, MID, MID, 1.0, 1.0);
    private static final VoxelShape CORRIDOR_D_NP = Shapes.box(MID, STEP_UP, 0.0, 1.0, 1.0, MID);
    private static final VoxelShape CORRIDOR_D_NN = Shapes.box(MID, STEP_UP, MID, 1.0, 1.0, 1.0);

    /**
     * Whether the block at {@code (x,y,z)} genuinely OBSTRUCTS the bot's body moving through the cell along the
     * step's horizontal direction {@code (dx,dz)} — the general, geometry-based replacement for a blunt {@link
     * #solidAt} collision test in the {@code Need.AIR} reconcile ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner}). Mine a body cell only when the live collision
     * shape actually intrudes into the direction-keyed body corridor (see the CORRIDOR_* fields), not merely
     * because it has SOME collision. This lets the bot walk THROUGH an already-open door / open trapdoor and
     * stand ON a carpet / plate / slab without swinging at them — matching a player — while still clearing a
     * closed door across the ENTRY or a full-block wall. The corridor covers the ENTRY HALF of the cell only —
     * the step's contract is to reach the CENTRE, and leaving is the next step's business (see the derivation
     * on the CORRIDOR_* fields).
     *
     * <p><b>{@code (dx,dz)} must be SIGNED</b> — it selects which half/quadrant of the cell is the entry side.
     * Any non-zero-per-axis form works (signum or raw delta) as long as the sign is the real direction of
     * travel, which is what {@link com.orebit.mod.pathfinding.blockpathfinder.MovePlan#moveDir} records from
     * the step's from/to cells. {@code (0,0)} is a vertical move (Pillar/MineDown) → the centred footprint
     * column, unchanged. Reads the LIVE level (reflects the bot's own just-made breaks/places), like
     * {@link #solidAt}; the local collision shape and the corridor share the 0..1 frame, so no world offset is
     * needed.
     */
    @Override
    public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos p = new BlockPos(x, y, z);
        VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
        if (shape.isEmpty()) return false;
        final VoxelShape corridor;
        if (dx != 0 && dz != 0) {
            corridor = dx > 0 ? (dz > 0 ? CORRIDOR_D_PP : CORRIDOR_D_PN)
                              : (dz > 0 ? CORRIDOR_D_NP : CORRIDOR_D_NN);
        } else if (dx != 0) {
            corridor = dx > 0 ? CORRIDOR_XP : CORRIDOR_XN;
        } else if (dz != 0) {
            corridor = dz > 0 ? CORRIDOR_ZP : CORRIDOR_ZN;
        } else {
            corridor = CORRIDOR_VERT;
        }
        return Shapes.joinIsNotEmpty(shape, corridor, BooleanOp.AND);
    }

    /** Live swim overshoot-hazard test: a bubble column (vertical drag breaches/ejects a prone swimmer),
     *  lava (damaging fluid), or a teleport portal (one box-clip relocates the bot — an end portal's Portal
     *  transition time is 0, javap-verified 1.21.11). The portal entry keeps the follower's never-touch set
     *  consistent with the planner's: PORTAL_BIT is walker-avoidance ("routes AROUND every portal, never
     *  occupies one mid-path" — NavBlock), so the corner brake must not treat a portal-walled turn as
     *  harmless — found by SwimCourse {@code mazeportal}, where full-cruise carry overshot ~0.7 blocks into
     *  an end-portal wall the brake never armed for (2026-08-16). Reads the live level — the follower's
     *  hazard-aware corner brake affords it. */
    @Override
    public boolean swimHazardAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        BlockState s = level.getBlockState(scratchPos);
        if (s.is(Blocks.BUBBLE_COLUMN)) return true;                  // the drag column itself
        if (s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SOUL_SAND)) return true; // its source under the water
        if (s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY)) {
            return true;                                              // teleport portals (planner-avoided too)
        }
        return level.getFluidState(scratchPos).is(FluidTags.LAVA);   // damaging fluid
    }

    /** Live UP bubble-column probe (see {@link BotSteering#bubbleUpAt}) — a {@code BUBBLE_COLUMN} whose
     *  {@code DRAG_DOWN} is false (soul-sand column, pushes up). The RideBubbleColumn follower's ENTER/RIDE
     *  state gate. {@code BubbleColumnBlock.DRAG_DOWN} is range-stable 1.17+, direct call (no platform seam),
     *  matching the {@code BUBBLE_COLUMN} references in {@link #swimHazardAt}. */
    @Override
    public boolean bubbleUpAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        BlockState s = level.getBlockState(scratchPos);
        return s.is(Blocks.BUBBLE_COLUMN) && !s.getValue(BubbleColumnBlock.DRAG_DOWN);
    }

    /** Live fluid-surface probe (see {@link BotSteering#fluidTopAt}). {@code FluidState.getHeight} already
     *  encodes the rule the swim clamp needs — 1.0 when the same fluid continues above, the partial
     *  {@code getOwnHeight} otherwise — so no arithmetic lives here. Range-stable 1.17+ (the
     *  {@link #swimHazardAt}/{@link #slipperinessAt} direct-call precedent); if a pre-1.17 target is ever
     *  added, route it through a {@code platform/} seam. */
    @Override
    public double fluidTopAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        return level.getFluidState(scratchPos).getHeight(level, scratchPos);
    }

    /** Live directional surface probe (see {@link BotSteering#surfaceTopYToward}) — classifies the live block
     *  and defers to the planner's own {@code directionalTopY}, so the executor and the search can never hold
     *  different opinions about which half of a stair a crossing launches from. */
    @Override
    public int surfaceTopYToward(int x, int y, int z, int dx, int dz) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        return MovementContext.directionalTopY(
                NavBlock.descriptorFor(level.getBlockState(scratchPos)), dx, dz);
    }

    /**
     * Live landing-surface friction (see {@link BotSteering#slipperinessAt}) — the vanilla per-block
     * slipperiness the parkour airborne servo reads to shape its air-brake. PORTABILITY: {@code
     * Block.getFriction()} is the mojmap name from 1.17 onward (older Yarn called it {@code getSlipperiness});
     * range-stable across this era's 1.17.1→1.21.11 targets, so the direct call is used with no platform
     * adapter — if a pre-1.17 target is ever added, route this through a {@code platform/} seam like
     * {@code BlockKinds}. */
    @Override
    public double slipperinessAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        return level.getBlockState(scratchPos).getBlock().getFriction();
    }

    /**
     * Live TAKEOFF-hazard probe (see {@link BotSteering#gapFloorHazardAt}) — magma/lava (damaging on
     * contact) or honey (jump-suppressing, {@code jumpFactor < 1}). Soul sand is deliberately NOT a hazard
     * here (slow but safe). PORTABILITY: {@code Block.getJumpFactor()} is the mojmap name from 1.17 on;
     * range-stable across this era, direct call used (flag for a {@code platform/} seam if a pre-1.17 target
     * is added, like {@link #slipperinessAt}). */
    @Override
    public boolean gapFloorHazardAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        BlockState s = level.getBlockState(scratchPos);
        if (s.is(Blocks.MAGMA_BLOCK)) return true;                       // damaging when stood on
        if (level.getFluidState(scratchPos).is(FluidTags.LAVA)) return true; // damaging fluid
        return s.getBlock().getJumpFactor() < 1.0f;                      // honey — jump-suppressing launch pad
    }

    /**
     * Route a break request to the timed {@link BotMining} actuator (equip/face/swing/real-time/drops).
     *
     * <p><b>The reconcile's break-policy gate (owner ruling 2026-07-30).</b> The sole caller is the
     * {@code PhaseRunner} {@code Need.AIR} reconcile, which exists to repair MISSED EDITS — a cell the
     * plan needs clear should ALREADY be air when the plan was followed properly — never to force the
     * plan true by chewing through someone's build. {@link BotMining} itself is the DELIBERATE-action
     * hands and stays {@code mayBreak}-exempt (owner ruling 2026-07-29), so this PATHING-motivated seam
     * applies {@link Config#mayBreak} itself, like every other route executor (applyEdits/place, the
     * gather LOS-occluder dig, builder clears) — with ONE exemption: a cell the current path's own
     * executed prefix PLACED ({@link BotNavigator#planPlacedAt}). The plan knows its own scaffolding
     * (the {@code isOpenableCell} precedent): the default {@code mining.protectedBlocks} list contains the
     * conjured bridge block, so without the vouch the plan's own later step would be refused its own
     * cobble (the six place→refuse pairs in the 2026-07-30 log). A refusal holds the phase; the refusal
     * log names it once per cell (the place-refusal idiom — {@code Debug.VERBOSE}-gated, so a production
     * hold is silent; promote if that ever bites), since a refused cell is re-requested every tick and
     * the hold would otherwise be indistinguishable from a legitimately slow dig.
     */
    @Override
    public void mine(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        BlockState s = level.getBlockState(scratchPos);
        if (!s.isAir() && !ConfigLoader.config().mayBreak(s, s.getDestroySpeed(level, scratchPos))
                && !navigator.planPlacedAt(x, y, z)) {
            refusalLog("mine|" + x + "," + y + "," + z, "mine REFUSED at (" + x + "," + y + "," + z + "): "
                    + s.getBlock() + " protected/unbreakable — the reconcile repairs missed edits, it never "
                    + "breaks protected blocks; holding");
            return;
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
        // Announce the mutation BEFORE it lands so the plan's own prescribed place is not read back as the
        // world diverging from it (PathPlan.expectOwnEdit); the slot is consumed by the change it predicts.
        navigator.expectOwnEdit(x, y, z, false);
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
        // P7 attribution: one cold line per ACTUAL world edit (bounded by placements, never per-tick) tying
        // the placement to the step frame that requested it — the executor-side half of the plan's plc count.
        OrebitCommon.LOGGER.info("[Orebit] place executed at ({},{},{}) for step {} -> ({},{},{})",
                x, y, z, lastSteerMove, navigator.segToX(), navigator.segToY(), navigator.segToZ());
        this.swing(InteractionHand.MAIN_HAND);
    }

    // ---- Fall clutches (the ClutchModel executor seam) ----------------------------------------------
    //
    // A clutch is a block the bot places INTO ITS OWN LANDING CELL mid-drop to survive a fall Fall would
    // otherwise refuse (ClutchModel's class doc). It cannot ride the place() path above for two independent
    // reasons: place() places the bot's ONE configured/softest block and has no way to be told WHAT to put
    // down, and two of the five kinds (water, powder snow) are not BlockItems at all, so they are invisible to
    // the placeable-block budget place() draws from.
    //
    // Both verbs mutate the world through the WorldEdits seam and swap the inventory item BY HAND, exactly as
    // place()/setDoorOpen do — never through BucketItem.emptyContents or any other item-USE pathway. That is
    // deliberate: the use pathway drags in the interaction stack (hit results, hand swings, per-version
    // BlockPlaceContext / InteractionResult signature drift, and for buckets an ultrawarm-evaporation branch
    // that moved from DimensionType.ultraWarm() to an environment attribute at 1.21.11 — see
    // BotInventory.evaporatesWater). Writing the BlockState directly keeps the whole feature inside seams that
    // are byte-stable across 1.17.1 → 26.2.
    //
    // Neither verb changes the bot's FACING, unlike place() (which lookAtCell()s its footing). A clutch is
    // placed while the bot is BALLISTIC and the airborne servo owns the heading; lookAtCell on a cell directly
    // below yields dx=dz=0 and would snap yaw to 0, fighting the steer for the remaining ticks of the drop.
    // The swing stays — it is cosmetic and costs nothing.

    /**
     * Place the {@link ClutchModel} block of {@code kind} at {@code (x,y,z)}, consuming the matching carried
     * item — see {@link BotSteering#placeClutch}. Returns whether the placement happened.
     *
     * <p><b>Refusals, all side-effect-free</b> (nothing consumed, nothing announced, no world write), so the
     * follower may re-issue this every tick of a drop:
     * <ul>
     *   <li>{@link ClutchModel#BED} — <b>not supported by this executor</b>; see {@link #clutchItem} for the
     *       full reasoning. Flagged as a live gap: {@code ClutchModel.best} will still HAND OUT bed, so a bot
     *       whose only clutch is a bed plans a cushion it then cannot lay.</li>
     *   <li>the cell already holds this clutch block — placing again is a pure loss. Vanilla {@code setBlock}
     *       is a no-op when the new state equals the old, so the item would be spent for no world change AND
     *       the one-shot {@link BotNavigator#expectOwnEdit} slot would be left armed to mispredict the NEXT
     *       real edit. Returning {@code false} rather than {@code true} is the conservative choice: {@code
     *       true} would claim ownership of a block the bot did not place and license a later
     *       {@link #reclaimClutch} of pre-existing terrain.</li>
     *   <li>the cell is not {@linkplain Replaceable replaceable} — a clutch does NOT clear a soft occupant the
     *       way {@link #place} does. That clear exists because a planned FOOTING must exist or the bot walks
     *       onto a cap that was never built; a clutch is a best-effort cushion on a fall the planner already
     *       priced, so the conservative option (refuse) is taken.</li>
     *   <li>the bot is not carrying the kind's item.</li>
     * </ul>
     *
     * <p><b>Dimension legality is NOT re-checked here.</b> {@code ClutchModel.best}'s contract makes the mask
     * authoritative about availability, and {@code BotInventory.clutchMask} already drops water in a level that
     * evaporates it. Re-testing here would duplicate a rule that is deliberately expressed once, on the model.
     */
    @Override
    public boolean placeClutch(int x, int y, int z, int kind) {
        final Item item = clutchItem(kind);
        if (item == null) { // unknown ordinal, or BED (unsupported — see clutchItem)
            refusalLog("clutch-kind|" + kind, "clutch place REFUSED at (" + x + "," + y + "," + z
                    + "): kind " + kind + " is not placeable by this executor");
            return false;
        }
        final ServerLevel level = (ServerLevel) Worlds.of(this);
        final BlockPos p = new BlockPos(x, y, z);
        final BlockState want = ClutchModel.blockState(kind);
        final BlockState existing = level.getBlockState(p);
        if (existing.is(want.getBlock())) {
            refusalLog("clutch-dup|" + x + "," + y + "," + z, "clutch place SKIPPED at (" + x + "," + y + ","
                    + z + "): cell already holds " + want.getBlock());
            return false;
        }
        if (!Replaceable.isReplaceable(existing)) {
            refusalLog("clutch-occ|" + x + "," + y + "," + z, "clutch place REFUSED at (" + x + "," + y + ","
                    + z + "): non-replaceable occupant " + existing.getBlock());
            return false;
        }
        final Inventory inv = this.getInventory();
        final int slot = slotHolding(inv, item);
        if (slot < 0) {
            refusalLog("clutch-inv|" + kind, "clutch place REFUSED at (" + x + "," + y + "," + z
                    + "): not carrying " + item);
            return false;
        }

        // Consume first, then announce, then mutate. The item swap cannot fail from here, so the ordering
        // never leaves an armed expectOwnEdit slot behind (the failure the duplicate-cell guard above exists
        // to prevent). A used bucket becomes an EMPTY bucket in place; a block item just shrinks by one.
        //
        // The bucket branch overwrites the WHOLE slot, which would destroy items if a filled bucket could
        // stack — it cannot: javap on Items.<clinit> shows WATER_BUCKET and POWDER_SNOW_BUCKET registered
        // .stacksTo(1) (only the EMPTY bucket is .stacksTo(16)), so the slot held exactly one and one empty
        // bucket replaces it. That asymmetry is the same one reclaimClutch's two-shape split is built on.
        //
        // The block branch normalizes a fully spent stack to EMPTY. shrink() only writes the count field, so
        // without this the slot keeps a count-0 husk whose getItem() still names the block on 26.1+ (see
        // slotHolding for the version-divergence evidence). Clearing at the WRITE site is the durable half of
        // that fix: the read guards stop US from being fooled, this stops the husk existing for anyone else —
        // the container menu, a hopper, the next /bot drop — to be fooled by.
        final Item residue = clutchResidue(kind);
        if (residue != null) {
            inv.setItem(slot, new ItemStack(residue));
        } else {
            final ItemStack held = inv.getItem(slot);
            held.shrink(1);
            if (held.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
        }

        // Announce the mutation BEFORE it lands so the plan's own prescribed edit is not read back as the
        // world diverging from it — the one-shot slot is consumed by the change it predicts (see place()).
        navigator.expectOwnEdit(x, y, z, false);
        WorldEdits.placeBlock(level, p, want);
        OrebitCommon.LOGGER.info("[Orebit] clutch placed at ({},{},{}) kind={} block={}",
                x, y, z, kind, want.getBlock());
        this.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Remove the {@link ClutchModel} block of {@code kind} at {@code (x,y,z)} and hand the item back — see
     * {@link BotSteering#reclaimClutch}. Returns whether the reclaim happened.
     *
     * <p><b>The destination is resolved BEFORE the world is touched</b>, so a full inventory refuses the
     * reclaim rather than deleting the block to make a point. Two shapes, per {@link #clutchResidue}:
     * <ul>
     *   <li><b>bucket kinds</b> (water, powder snow) — one EMPTY bucket is spent to produce the filled one,
     *       which is what scooping physically is. No empty bucket ⇒ refuse. The empty stack is swapped in
     *       place when it holds exactly one; a larger stack (empty buckets stack, filled ones do not) is
     *       shrunk by one and the filled bucket needs a free storage slot of its own.</li>
     *   <li><b>block kinds</b> (slime, hay) — the item stacks back onto an existing partial stack, else into
     *       a free storage slot.</li>
     * </ul>
     *
     * <p><b>Water is required to be a SOURCE.</b> A player scooping flowing water gets nothing, and the clutch
     * we placed was a source ({@code Blocks.WATER.defaultBlockState()}, {@code LEVEL = 0}); a flowing state in
     * the cell means the source is elsewhere and this is someone else's spill. ({@code WaterFluid.getTickDelay
     * = 5}, so a source reclaimed inside 5 ticks never spread in the first place.)
     *
     * <p><b>Removal writes AIR through {@link WorldEdits#placeBlock} rather than calling
     * {@link WorldEdits#breakBlock}.</b> {@code breakBlock} routes to vanilla {@code Level.destroyBlock},
     * which writes {@code fluidState.createLegacyBlock()} — for a water source that is the water block again,
     * i.e. a no-op — and it plays the break effect for a block we are picking up, not smashing. One uniform
     * AIR write is both correct for the fluid kinds and silent for the rest.
     */
    @Override
    public boolean reclaimClutch(int x, int y, int z, int kind) {
        final Item item = clutchItem(kind);
        if (item == null) return false; // unknown ordinal, or BED — nothing this executor ever placed
        final ServerLevel level = (ServerLevel) Worlds.of(this);
        final BlockPos p = new BlockPos(x, y, z);
        final BlockState placed = ClutchModel.blockState(kind);
        final BlockState existing = level.getBlockState(p);
        if (!existing.is(placed.getBlock())) return false; // the cell no longer holds what we put there
        if (kind == ClutchModel.WATER && !level.getFluidState(p).isSource()) return false;

        final Inventory inv = this.getInventory();
        final Item residue = clutchResidue(kind);
        int spendSlot = -1;  // the empty bucket consumed by the scoop (bucket kinds only)
        int giveSlot;        // where the returned item lands
        if (residue != null) {
            spendSlot = slotHolding(inv, residue);
            if (spendSlot < 0) return false;                     // nothing to scoop into
            giveSlot = inv.getItem(spendSlot).getCount() == 1
                    ? spendSlot                                  // clean one-for-one swap
                    : firstEmptyStorageSlot(inv);                // a stack of empties needs a slot of its own
        } else {
            giveSlot = slotWithRoomFor(inv, item);
            if (giveSlot < 0) giveSlot = firstEmptyStorageSlot(inv);
        }
        if (giveSlot < 0) return false;                          // nowhere to put it — leave the block alone

        // Both destinations are resolved above and the world write happens only after they are known good, so
        // every refusal path leaves the block standing and the inventory untouched — a full bot never trades
        // the clutch away for nothing.
        //
        // The spendSlot shrink cannot underflow, and the reason is the slotHolding isEmpty() guard rather
        // than anything local: that guard makes spendSlot a stack of count >= 1, this branch runs only when
        // giveSlot != spendSlot, and giveSlot differs from spendSlot only on the count != 1 arm above — so
        // count >= 2 here and the shrink lands at >= 1, never at a count-0 husk and never negative. Without
        // the guard a count-0 empty-bucket husk reached exactly this line and shrank to −1 while the setItem
        // below minted a filled bucket from nothing.
        navigator.expectOwnEdit(x, y, z, true);
        WorldEdits.placeBlock(level, p, Blocks.AIR.defaultBlockState());
        if (residue != null) {
            if (giveSlot != spendSlot) inv.getItem(spendSlot).shrink(1);
            inv.setItem(giveSlot, new ItemStack(item));
        } else {
            // isEmpty() here reads as "which resolver won": slotWithRoomFor returns a genuinely partial stack
            // (grow it), firstEmptyStorageSlot a genuinely free one (fill it). That is only a clean dispatch
            // because slotWithRoomFor now rejects count-0 husks; before the guard it could return a husk, and
            // this test was silently rescuing that bug instead of expressing an intent.
            final ItemStack dest = inv.getItem(giveSlot);
            if (dest.isEmpty()) inv.setItem(giveSlot, new ItemStack(item));
            else dest.grow(1);
        }
        OrebitCommon.LOGGER.info("[Orebit] clutch reclaimed at ({},{},{}) kind={} item={}", x, y, z, kind, item);
        this.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * The carried item a {@link ClutchModel} kind is placed FROM (and handed back on reclaim), or {@code null}
     * when this executor cannot handle the kind.
     *
     * <p>A {@code switch} rather than a {@code static final Item[]} on purpose: a table would resolve the
     * {@code Items.*} constants at this class's init, and {@link ClutchModel} goes to the trouble of a lazy
     * holder precisely so the registry is never touched early. A cold five-way switch, called once per clutch
     * action, costs nothing and keeps the resolution at the point of use. The four constants are the same ones
     * {@code BotInventory.clutchKindOf} matches on, all javap-verified present at both ends of the supported
     * range (1.17.1 and 26.2).
     *
     * <p><b>{@link ClutchModel#BED} returns {@code null} — the bed clutch is deliberately NOT executed.</b>
     * Three things would have to be true to lay one safely and none of them is verifiable inside this seam:
     * <ol>
     *   <li><b>It is a two-cell multiblock</b> ({@link ClutchModel#footprint} = 2) and the seam is handed ONE
     *       cell. Picking the second cell — which of four horizontal neighbours, and on what evidence it is
     *       free — is a placement DECISION the planner made and did not communicate; inventing it here would
     *       be widening behaviour beyond the contract.</li>
     *   <li><b>A half-bed deletes itself.</b> {@code BedBlock.updateShape} returns {@code Blocks.AIR} whenever
     *       the neighbour in the part's own direction is not the matching half, so laying the two halves as
     *       two independent {@link WorldEdits#placeBlock} calls depends entirely on which of them triggers a
     *       shape update on the other and when. Vanilla never does this — it places the foot through the item
     *       pathway and the head from {@code setPlacedBy} — and the ordering is exactly the kind of thing that
     *       cannot be asserted from memory across 1.17.1 → 26.2.</li>
     *   <li><b>The item identity is not a constant.</b> Beds are the one clutch with no stable {@code Items.*}
     *       spelling ({@code Items.WHITE_BED} exists at 1.17.1 and not at 26.2, where the dyed variants
     *       collapsed into a colour collection — see {@code BotInventory.clutchKindOf}), so both the consume
     *       and the give-back would have to carry the specific bed the bot holds, and
     *       {@link ClutchModel#blockState} hands out {@code RED_BED} as a representative.</li>
     * </ol>
     * Refusing is the conservative option and it is stated here rather than half-implemented. <b>The live
     * consequence, called out rather than papered over:</b> {@code BotInventory.clutchMask} still sets the BED
     * bit and {@code ClutchModel.PREFERENCE} still tries bed SECOND, so a bot carrying a bed but no hay will
     * plan a cushioned drop and then eat the raw fall. Closing that needs an edit outside this seam (drop the
     * bed bit from the mask, or the BED entry from the preference table) until the multiblock place is built.
     */
    private static Item clutchItem(int kind) {
        return switch (kind) {
            case ClutchModel.WATER -> Items.WATER_BUCKET;
            case ClutchModel.POWDER_SNOW -> Items.POWDER_SNOW_BUCKET;
            case ClutchModel.SLIME -> Items.SLIME_BLOCK;
            case ClutchModel.HAY -> Items.HAY_BLOCK;
            default -> null; // BED (see above) and any out-of-range ordinal
        };
    }

    /**
     * What the consumed slot becomes after a clutch of this kind is placed, or {@code null} when the item is
     * simply spent one from the stack. Only the two bucket kinds leave a residue — an EMPTY bucket, which is
     * also the thing {@link #reclaimClutch} spends to scoop the clutch back up. Encoding the pair this way is
     * what lets both verbs share one shape instead of branching on WATER/POWDER_SNOW by name.
     */
    private static Item clutchResidue(int kind) {
        return (kind == ClutchModel.WATER || kind == ClutchModel.POWDER_SNOW) ? Items.BUCKET : null;
    }

    /**
     * The first slot holding {@code item}, or {@code -1}. Scans the WHOLE container (hotbar + main + armor +
     * offhand) to match {@code BotInventory.clutchMask}, which builds the planner's carried-kind bitmask over
     * the same range — a narrower scan here would refuse a clutch the planner was told the bot had (an
     * offhand water bucket is the realistic case). Cold: one scan per clutch action.
     *
     * <p><b>The {@code isEmpty()} guard is load-bearing, not defensive noise.</b> {@code ItemStack.shrink(n)}
     * is {@code grow(-n)} is {@code setCount(getCount() - n)}, and {@code setCount} writes the field and
     * NOTHING else — a fully spent stack stays in its slot at COUNT ZERO with its {@code item} field intact.
     * Whether that husk is visible to an identity compare is <b>version-divergent</b>, javap-verified at both
     * ends of the range: through 1.21.11 {@code ItemStack.getItem()} opens with {@code isEmpty() ? Items.AIR :
     * this.item}, so the husk answers AIR and an unguarded scan skips it by luck; at <b>26.1+</b>
     * {@code getItem()} was rewritten to a bare {@code typeHolder().value()} with no empty short-circuit, so
     * the husk keeps answering with the item it used to hold. Unguarded, this method therefore returns the
     * same spent slot forever on the 26 era: {@link #placeClutch} "consumes" a clutch that is not there on
     * every tick of every drop (effectively infinite clutch blocks), and {@link #reclaimClutch} finds a
     * count-0 empty bucket, fails its {@code getCount() == 1} test, {@code shrink}s that slot to count −1 and
     * mints a filled bucket in a fresh slot — item duplication out of a husk.
     *
     * <p>Guarding at every READ rather than sweeping the container is vanilla's own convention: nothing
     * normalizes these stacks away (26.2 {@code ContainerHelper.removeItem} {@code split}s and leaves the
     * husk in the list; {@code Inventory.tick} simply skips it with an {@code isEmpty()} test), so every
     * consumer tests. It is also the convention every scan in {@code BotInventory} already follows.
     */
    private static int slotHolding(Inventory inv, Item item) {
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    /**
     * The first slot holding {@code item} with room for one more, or {@code -1} — the stack-onto-a-partial
     * destination {@link #reclaimClutch} prefers over burning a fresh slot. Restricted to the 36 STORAGE slots
     * (hotbar + main), like {@link #firstEmptyStorageSlot}: the slot this returns is WRITTEN to, and armor
     * slots are not a place to put a hay bale.
     *
     * <p>Carries the same {@code isEmpty()} guard as {@link #slotHolding}, for the same count-0-husk reason
     * documented there — and it is needed even though {@code getCount() < getMaxStackSize()} looks like it
     * already excludes a dead stack: it does the opposite. A husk reads {@code 0 < 64}, which is the widest
     * "room" any slot can advertise, so on 26.1+ (where {@code getItem()} does not short-circuit on empty)
     * this scan would preferentially hand back the very slot the last clutch was spent from. That happens to
     * be survivable today only because the one caller re-tests the destination with {@code dest.isEmpty()} and
     * overwrites instead of {@code grow}ing; a caller that trusted the documented postcondition — "an
     * EXISTING partial stack of {@code item}" — would {@code grow} a husk from 0 to 1 and lose the reclaimed
     * item's slot accounting. The guard makes the postcondition true rather than incidental.
     */
    private static int slotWithRoomFor(Inventory inv, Item item) {
        final int n = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item && s.getCount() < s.getMaxStackSize()) return i;
        }
        return -1;
    }

    /**
     * The first empty STORAGE slot (hotbar 0–8 + main 9–35; never worn armor, never the offhand), or
     * {@code -1} when the bot is full. The same 36-slot storage window {@code BotInventory.findSlotMatching}
     * uses, for the same reason: a reclaimed item belongs where the bot's other items are.
     *
     * <p>This one needs no extra guard and gets none: {@code ItemStack.isEmpty()} is {@code this == EMPTY ||
     * item == AIR || count <= 0} on every version in range (javap-verified 1.20.2 → 26.2), so it is already
     * COUNT-aware and a spent count-0 husk is reported free — which is exactly right, since writing over a
     * husk destroys nothing. Correct as written; left alone.
     */
    private static int firstEmptyStorageSlot(Inventory inv) {
        final int n = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < n; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** Open/close the door at {@code (x,y,z)} server-side (DOORS P3) — the "right-click the door" reconcile
     *  action, routed to {@link WorldEdits#setDoorOpen} (authoritative, both-halves sync, iron/non-door no-op).
     *  Cosmetically faces the door (like {@link #place} looks at its footing) but never swings — operating a
     *  door is not a hand attack. Announces the toggle BEFORE it lands ({@link BotNavigator#expectOwnToggle} —
     *  the own-edit forgiveness the break/place actuators already have; DESIGN-trapdoors.md §7 closes the
     *  doors gap too), so the plan's own prescribed toggle is not read back as the world diverging from it. */
    @Override
    public void setDoorOpen(int x, int y, int z, boolean open) {
        lookAtCell(x, y, z); // cosmetic — face the door we operate; no swing
        navigator.expectOwnToggle(x, y, z); // announce BEFORE: our own prescribed door toggle
        WorldEdits.setDoorOpen((ServerLevel) Worlds.of(this), new BlockPos(x, y, z), this, open);
    }

    /** Open/close the trapdoor at {@code (x,y,z)} server-side (DESIGN-trapdoors.md §7) — the trapdoor twin of
     *  {@link #setDoorOpen}, routed to {@link WorldEdits#setTrapdoorOpen} (authoritative single-cell property
     *  write, iron/non-trapdoor no-op). Same discipline: cosmetic face, no swing, own-toggle announced before
     *  the mutation lands. */
    @Override
    public void setTrapdoorOpen(int x, int y, int z, boolean open) {
        lookAtCell(x, y, z); // cosmetic — face the hatch we operate; no swing
        navigator.expectOwnToggle(x, y, z); // announce BEFORE: our own prescribed trapdoor toggle
        WorldEdits.setTrapdoorOpen((ServerLevel) Worlds.of(this), new BlockPos(x, y, z), this, open);
    }

    /** Open/close the fence gate at {@code (x,y,z)} server-side (DESIGN-fence-gates.md §4) — the gate member
     *  of the {@link #setDoorOpen}/{@link #setTrapdoorOpen} verb family, routed to {@link
     *  WorldEdits#setGateOpen} (authoritative single-cell property write, non-gate no-op — no iron refusal,
     *  since no iron gate exists). Same discipline: cosmetic face, no swing, own-toggle announced before the
     *  mutation lands. */
    @Override
    public void setGateOpen(int x, int y, int z, boolean open) {
        lookAtCell(x, y, z); // cosmetic — face the gate we operate; no swing
        navigator.expectOwnToggle(x, y, z); // announce BEFORE: our own prescribed gate toggle
        WorldEdits.setGateOpen((ServerLevel) Worlds.of(this), new BlockPos(x, y, z), this, open);
    }

    /** Live OPEN-property readback for the openable at {@code (x,y,z)} (see {@link BotSteering#doorOpenAt}) —
     *  reads the shared {@code BlockStateProperties.OPEN} of a door, trapdoor OR fence gate (the door-family
     *  widening, DESIGN-trapdoors.md §7 / DESIGN-fence-gates.md §4), NOT collision ({@link #solidAt}): an
     *  open door still has a thin collision box and an open trapdoor its wall panel. {@code false} for a
     *  non-openable cell. The SET executor's gate + verify-readback — without the gate arm a gate
     *  {@code Need.OPEN} could never observe "open" and the runner would re-issue the toggle forever. */
    @Override
    public boolean doorOpenAt(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        scratchPos.set(x, y, z);
        BlockState s = level.getBlockState(scratchPos);
        return (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof TrapDoorBlock
                || s.getBlock() instanceof FenceGateBlock)
                && s.getValue(BlockStateProperties.OPEN);
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
