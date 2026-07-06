package com.orebit.mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.PathStatus;
import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BlockShapes;
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
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NetherPortalIndex;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceQuery;
import com.orebit.mod.worldmodel.resource.ResourceScan;
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
 * <p><b>This is the first consumer of the nav grid (PRD Phase 4 / pathfinding milestone).</b> When
 * the owner is far enough away, the bot plans a block-level path to them with {@link BlockPathfinder}
 * over a {@link NavGridView} and steers along the waypoints, jumping over single-block rises — so it
 * routes around obstacles instead of walking straight into them. Where nav data isn't built yet (the
 * owner just outside the bot's loaded radius), it falls back to the old straight-line steer so it
 * never freezes.
 */
public class AllyBotEntity extends FakePlayerEntity implements BotSteering {

    private final Player owner;

    /** The bot's "hands" for breaking blocks — real tool + vanilla timing + drops (replaces instant edits).
     *  Requested per tick by the follower and actuated once per tick from {@link #tick} (see {@link BotMining}). */
    private final BotMining mining;
    /** {@code /bot mine <pos>} Stage-1 test target: while non-null, requested each tick until it's mined. */
    private BlockPos debugMineTarget;

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
    /** Player mining reach (blocks, eye→block-centre) the bot must be within before it can break a
     *  {@code /bot gather} target cell — otherwise it paths closer first (see {@link #gatherMine}). */
    private static final double MINE_REACH = 4.5;
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
    /** Consecutive grounded low-speed ticks before the held-jump backstop may fire (≈0.3s). A bot starting
     *  a plan from REST is inherently below {@link #STUCK_SPEED_SQR} on its first driving ticks (walk
     *  acceleration crosses the 0.04 b/t threshold in ~2–4 ticks), so an unarmed backstop hopped on tick 1
     *  of every command — cosmetic on flat ground, off the course entirely when the plan started on a
     *  ledge. A real physical hitch (a lip, a fence, a missed jump grinding in a gap) persists far past
     *  this window, so recovery is only delayed by ~¼s, never lost. */
    private static final int JUMP_FREE_ARM_TICKS = 6;
    /** Consecutive stuck ticks before the diagnostic dumps the surrounding blocks (≈1s). */
    private static final int STUCK_DUMP_TICKS = 20;
    /** Cross-track distance (blocks) off the planned segment that counts as a genuine slip off the line. */
    private static final double OFF_TRACK_DIST = 1.6;
    /** Consecutive off-track ticks before forcing a re-search from the bot's actual cell (≈0.6s). */
    private static final int OFF_TRACK_TICKS = 12;
    /** Consecutive airborne/underwater stall ticks (no 3-D progress, jump useless) before recovering (≈2s). */
    private static final int STALL_TICKS = 40;
    /** Consecutive GROUNDED no-progress ticks (the held-jump backstop isn't freeing it) before the same
     *  escape hatch as off-track/stall fires (≈1.5s). Covers the grind the other two arms can't see: a
     *  parkour jump that came up short and landed in the gap is grounded (the stall arm needs
     *  {@code !grounded}), roughly under the planned line (the off-track arm is horizontal-only), and not
     *  at a settled waypoint (the boundary-gated refresh never runs) — previously a permanent grind unless
     *  the gap was 1 deep (the only case holding jump self-heals). Longer than {@link #STUCK_DUMP_TICKS}
     *  so the diagnostic dump still fires first. */
    private static final int GROUNDED_STALL_TICKS = 30;
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

    // ---- Nether-portal follow (owner changed dimension while the bot is in FOLLOW/COME) ---------------
    /** Horizontal distance (blocks) from the portal cell centre at which pathing hands off to the ENTER
     *  terminal state (face the portal + walk straight in). Slightly under {@link #ARRIVE_DIST} so the
     *  handoff also fires when {@link #driveToward} declares arrival first (its 2.5-block tolerance stops
     *  the bot adjacent to the portal, never inside it — hence a dedicated terminal state). */
    private static final double PORTAL_ENTER_DIST = 2.0;
    /** Ticks one ENTER attempt runs before it is declared failed. Must comfortably exceed the survival
     *  portal wait: {@code Player.getPortalWaitTime()} is ~80 ticks in survival but ~1 tick while
     *  abilities-invulnerable — and the bot's abilities flag tracks {@code survival.takesDamage} (see
     *  {@link #tick}), so with damage ON the bot stands the full 80 ticks before vanilla teleports it. */
    private static final int PORTAL_ENTER_TIMEOUT_TICKS = 200;
    /** Back-off walk (ticks) away from the portal after a timed-out attempt, before the single retry
     *  re-queries the index and re-approaches (covers a portal broken while the bot ground against it). */
    private static final int PORTAL_BACKOFF_TICKS = 15;

    /** The level the bot ended the previous tick in — a difference detected post-{@code doTick} means a
     *  COMPLETED teleport (vanilla's portal process runs inside the player tick). Null until first tick. */
    private Level lastLevel;
    /** Bottom NETHER_PORTAL cell of the column the bot is heading into (null = none chosen yet). Targeting
     *  the BOTTOM cell makes the pathing goal floor ({@code portalTarget.below()}) the standable obsidian
     *  frame base rather than another intangible portal cell. */
    private BlockPos portalTarget;
    /** ENTER terminal state: face the portal column, walk in, then stand still — path steering suppressed
     *  (like the STAY hold) while vanilla's portal process ticks the wait inside the bot's own baseTick. */
    private boolean enteringPortal;
    private int portalEnterTicks;    // ticks spent in the current ENTER attempt (timeout counter)
    private int portalBackoffTicks;  // remaining back-off walk ticks between a timeout and the retry
    private int portalEnterRetries;  // attempts consumed after the first (one retry, then give up)
    private boolean portalSeekAnnounced; // one "heading for the portal" chat line per seek
    private boolean portalSeekGaveUp;    // no known portal / entry failed → hold + one chat line

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
     *       {@link #gatherLoopTick} and {@link #startGather}.
     * </ul>
     */
    public enum Mode { FOLLOW, STAY, COME, GATHER }

    private Mode mode = Mode.FOLLOW;
    private BlockPos comeTarget;    // fixed summon cell (owner's feet block at /bot come time)

    // ---- GATHER: live-scan-primary find→mine→return resource loop ------------------------------------
    /** Phases of a {@code /bot gather} run. SCAN live-scans loaded sections around the bot nearest-first
     *  (the PRIMARY ore source — robust to the coarse, load-populated, sometimes-mis-bucketed resource
     *  pyramid); MINE approaches + timed-mines the queued cells; COMPASS walks toward a distant pyramid hint
     *  when nothing is loaded nearby, live-scanning en route so it grabs ore it passes; RETURN walks back to
     *  the issue cell. Stepped one phase per tick by {@link #gatherLoopTick}. */
    private enum GatherPhase { SCAN, MINE, COLLECT, COMPASS, RETURN }

    private int gatherColumn = -1;            // indexed resource column being gathered (-1 = not gathering)
    private int gatherQuota;                  // target count of PICKED-UP items (owner-ratified, §10)
    private int gathered;                     // items accrued so far (counted on standing-mine ticks)
    private GatherPhase gatherPhase;          // current phase (null = inactive)
    private BlockPos gatherStartPos;          // where /bot gather was issued — the fixed RETURN target (§10)
    private int gatherLastInvTotal;           // inventory item total at the last standing-mine tick (Δ = drops)
    private int scanCursor;                   // index into SCAN_OFFSETS for the throttled nearest-first scan
    private int scanAnchorX, scanAnchorY, scanAnchorZ; // bot cell the current scan sweep is centred on
    private BlockPos compassTarget;           // centre of the pyramid-hinted region walked toward (COMPASS)
    private long compassKey;                  // that region's blacklist key
    /** Target ore cells found by the live scan, nearest-first; drained by MINE. */
    private final ArrayDeque<BlockPos> mineQueue = new ArrayDeque<>();
    /** Pyramid regions the COMPASS reached but found no live ore in — skipped (stale/phantom tally). */
    private final HashSet<Long> gatherBlacklist = new HashSet<>();
    /** Ore cells the driver could not reach this run — skipped by the scan so MINE can't loop on them. */
    private final HashSet<Long> unreachableCells = new HashSet<>();
    /** Candidates accumulated across a (possibly multi-tick) scan sweep, so exposed-vs-buried can be ranked. */
    private final List<BlockPos> scanFound = new ArrayList<>();
    /** MINE: the ore currently being pursued — chosen from {@link #mineQueue} by the two-A* route-cost compare
     *  ({@link #selectMineTarget}), not simply the nearest. Null → re-select on the next MINE tick. */
    private BlockPos mineTarget;
    /** COLLECT: the just-mined cell whose drop we are waiting to pick up (now air). */
    private BlockPos collectCell;
    /** COLLECT: ticks spent chasing the current drop (bounded by {@link #COLLECT_TIMEOUT}). */
    private int collectTicks;
    /** MINE: the level-0 region (blockPos>>4) the current target was selected from; a change re-runs selection. */
    private long mineSelectRegion = Long.MIN_VALUE;
    /** MINE: ticks since the last opportunistic re-target challenge (throttles it — see {@link #maybeChallengeTarget}). */
    private int proximityTicks;
    /** Reused mutable cursor for the LOS raycast + exposed-face neighbour reads (no per-check allocation). */
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    /** Ticks to wait for a mined drop to be collected before giving up on it (drop despawned / fell away). */
    private static final int COLLECT_TIMEOUT = 60;
    /** Drive-arrival tolerance while MINING/COLLECTING: deliberately TIGHTER than the ~1.0 closest a bot can get
     *  to a solid block's centre, so {@code driveToward} never short-circuits to "arrived" and stops the bot a
     *  block of stone short — it keeps tunnelling until the real stop condition ({@link #hasLineOfSight} in MINE,
     *  the drop pickup in COLLECT) is met. Follow/come keep the looser {@link #ARRIVE_DIST}/{@link #ARRIVE_Y}. */
    private static final double MINE_ARRIVE_DIST = 0.6;
    private static final double MINE_ARRIVE_Y = 0.6;
    /** The 6 face neighbours ({dx,dy,dz}) — for the {@link #hasExposedFace} targeting check. */
    private static final int[][] FACE_OFFSETS =
            {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** How often (ticks) MINE re-weighs its committed target against a newly-close pooled vein. Cheap distance
     *  math runs free every tick, but the A* challenge only every this-many ticks (throttled). */
    private static final int PROXIMITY_INTERVAL = 15;
    /** How close (blocks) a pooled ore must come before it may CHALLENGE the committed target — the "there's a
     *  vein right here, reconsider" radius (well beyond mining reach, so it fires as the bot draws near). */
    private static final int PROXIMITY_GRAB_RADIUS = 14;

    // Live-scan volume around the bot (in sections): horizontal ± chunks, plus a downward-biased vertical
    // band (ore sits below). Swept nearest-first, SCAN_SECTIONS_PER_TICK at a time, stopping at the first ore.
    private static final int SCAN_RADIUS_CHUNKS = 3;
    private static final int SCAN_DOWN_SECTIONS = 6;
    private static final int SCAN_UP_SECTIONS = 2;
    private static final int SCAN_SECTIONS_PER_TICK = 12;
    /** Section offsets {dChunkX, dSectionY, dChunkZ} in the scan volume, sorted nearest-first (built once). */
    private static final int[][] SCAN_OFFSETS;
    static {
        List<int[]> offs = new ArrayList<>();
        for (int dcx = -SCAN_RADIUS_CHUNKS; dcx <= SCAN_RADIUS_CHUNKS; dcx++) {
            for (int dcz = -SCAN_RADIUS_CHUNKS; dcz <= SCAN_RADIUS_CHUNKS; dcz++) {
                for (int dsy = -SCAN_DOWN_SECTIONS; dsy <= SCAN_UP_SECTIONS; dsy++) {
                    offs.add(new int[]{dcx, dsy, dcz});
                }
            }
        }
        offs.sort((a, b) -> Integer.compare(
                a[0] * a[0] + a[1] * a[1] + a[2] * a[2],
                b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        SCAN_OFFSETS = offs.toArray(new int[0][]);
    }

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
    /**
     * The floor cell of the last <b>completed</b> waypoint — the bot's last SETTLED stand position, updated only
     * when a move's {@link Movement#reached} fires (see {@link #steerAlongPath}). The driver's region commit /
     * replan runs off THIS, not the live {@link #blockPosition()}: a move in progress passes transiently through
     * cells it hasn't finished into (a pillar's jump apex is momentarily "floor+1" with the footing not yet
     * placed), and committing/replanning on that phantom position rebuilt the plan mid-move — discarding the
     * in-flight pillar before it placed, which stranded the bot in a MineDown↔Pillar loop at region boundaries.
     * Anchoring the commit to completed waypoints means we only ever replan from a cell the bot actually reached
     * (with that move's edits already applied) — the synchronous form of the assumed-post-move position a future
     * background planner will require. The stall/off-track recovery re-anchors this to the live cell (its escape
     * hatch — a move that never completes is exactly when we DO want to bail and re-search from where we're stuck).
     */
    private BlockPos settledFloor;
    /** Reusable {@link SteerView} re-pointed at the current segment each tick (no per-tick allocation). */
    private final SegmentCursor cursor = new SegmentCursor();
    private int offTrackTicks;  // consecutive ticks the bot is off the planned segment (slip detection)
    private int stallTicks;     // consecutive airborne/underwater no-progress ticks (a jump can't fix these)
    private int recoverCooldown; // throttle between forced re-searches after an off-track/stall recovery

    // ---- Stage-2 phase-model execution (converted moves only; others keep the steer + one-shot-edit path) ----
    /** Runs the current step's {@link MovePlan} when its move provides one (Pillar today); reactive geometry. */
    private final PhaseRunner phaseRunner = new PhaseRunner();
    /** The waypoint index the active plan was built for; a change rebuilds the plan for the new step (-1 = none). */
    private int activePlanStep = -1;

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
        this.mining = new BotMining(this);
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
        clearPlan();
        resetPortalSeek(); // a fresh command restarts (and re-announces) any cross-dimension seek
    }

    /** {@code /bot come}: path once to {@code summonCell} (the caller's feet block), then hold there. */
    public void comeTo(BlockPos summonCell) {
        this.mode = Mode.COME;
        this.comeTarget = summonCell.immutable();
        clearPlan();
        resetPortalSeek();
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

    /**
     * Drop the active two-tier driver and its exposed block plan (HPA-IMPLEMENTATION.md §10): a mode change
     * or a STAY hold invalidates the current goal, so the {@link PathPlan} built for it must not be ticked
     * again. The next {@link #driveToward} sees a null {@link #pathPlan} and rebuilds for the new goal.
     */
    private void clearPlan() {
        if (pathPlan != null) pathPlan.cancelPending(); // stop caring about any in-flight background search
        this.pathPlan = null;
        this.path = null;
        this.lastBlockPlanRef = null;
        this.activePlanStep = -1;
        this.phaseRunner.clear();
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

        // Stage-1 mining test hook: while a /bot mine target is set, request it each tick until it's gone, then
        // report and clear. (Stage 2 replaces this debug field with each Movement's reconcile driving the break.)
        final ServerLevel level = (ServerLevel) Worlds.of(this);
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
            case GATHER -> gatherLoopTick();
            case COME -> {
                // Summon to a fixed cell; once there, settle into STAY (distinct from FOLLOW, which
                // would keep chasing). comeTarget can't be null in COME, but guard defensively.
                if (comeTarget == null) { setMode(Mode.STAY); holdPosition(); break; }
                // Cross-dimension guard: comeTarget's coordinates were captured in the CALLER's level, so
                // while the owner is elsewhere the bot follows them through a portal instead of pathing to
                // a cell that means nothing in this level.
                if (followThroughPortal()) break;
                double tx = comeTarget.getX() + 0.5, ty = comeTarget.getY(), tz = comeTarget.getZ() + 0.5;
                if (driveToward(tx, ty, tz, comeTarget.below())) setMode(Mode.STAY); // arrived
            }
            default -> { // FOLLOW
                if (!followThroughPortal()) {
                    driveToward(owner.getX(), owner.getY(), owner.getZ(), owner.blockPosition().below());
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
        clearPlan();
        lookAtPlayer(owner);
    }

    // ---- GATHER loop: scan (live) → mine → (compass) → return ----------------------------------------

    /**
     * {@code /bot gather <resource> [count]} entry point. Begin the loop for indexed resource {@code column},
     * targeting {@code quota} PICKED-UP items (§10). Anchors the RETURN target at the bot's current cell and
     * enters {@link GatherPhase#SCAN} — the primary ore source is a LIVE nearest-first scan of the loaded
     * sections around the bot (the resource pyramid is coarse + load-populated + can mis-bucket, so it's used
     * only as the COMPASS heading when nothing is loaded nearby).
     */
    public void startGather(int column, int quota) {
        this.mode = Mode.GATHER;
        this.comeTarget = null;
        clearPlan();
        resetPortalSeek();
        this.gatherColumn = column;
        this.gatherQuota = Math.max(1, quota);
        this.gathered = 0;
        this.gatherStartPos = this.blockPosition().immutable();
        this.compassTarget = null;
        this.mineQueue.clear();
        this.gatherBlacklist.clear();
        this.unreachableCells.clear();
        this.navGaveUp = false;
        beginScanSweep();
        this.gatherPhase = GatherPhase.SCAN;
    }

    /** One tick of the {@code /bot gather} state machine — dispatch to the current phase. */
    private void gatherLoopTick() {
        if (gatherPhase == null) { setMode(Mode.STAY); return; } // defensive: lost phase → stop
        ServerLevel level = (ServerLevel) Worlds.of(this);
        switch (gatherPhase) {
            case SCAN -> gatherScan(level);
            case MINE -> gatherMine(level);
            case COLLECT -> gatherCollect(level);
            case COMPASS -> gatherCompass(level);
            case RETURN -> gatherReturn(level);
        }
    }

    /** Re-anchor a fresh nearest-first live scan on the bot's current cell. */
    private void beginScanSweep() {
        BlockPos p = this.blockPosition();
        scanAnchorX = p.getX();
        scanAnchorY = p.getY();
        scanAnchorZ = p.getZ();
        scanCursor = 0;
        scanFound.clear();
        mineTarget = null; // a fresh sweep re-picks the target
    }

    /**
     * SCAN: the primary ore source. Live-scan the loaded sections around the bot nearest-first (throttled,
     * {@link #advanceScan}); ore found → MINE. If the whole nearby volume is swept with nothing → fall back to
     * the pyramid COMPASS for a distant heading.
     */
    private void gatherScan(ServerLevel level) {
        this.zza = 0.0f; // stand still while scanning
        lookAtPlayer(owner);
        if (advanceScan(level)) {
            gatherLastInvTotal = new BotInventory(this).totalItemCount();
            clearPlan();
            enterMine();
            return;
        }
        if (scanCursor >= SCAN_OFFSETS.length) { // nothing loaded nearby → ask the pyramid where to head
            beginCompass(level);
        }
    }

    /**
     * Advance the throttled nearest-first live scan by up to {@link #SCAN_SECTIONS_PER_TICK} sections from
     * {@link #scanCursor}, accumulating this run's exact target cells ({@link ResourceScan#exactCells} — a
     * {@code null} return skips an unloaded chunk) into {@link #scanFound}.
     *
     * <p><b>Prefers the EASY ore.</b> Rather than committing to the nearest ore (which may be buried behind
     * stone — a 9-block dig when a slightly-farther vein is exposed), it keeps sweeping nearest-first until it
     * finds an ore with an {@linkplain #hasExposedFace exposed face} OR the whole volume is swept, and only then
     * commits — ranking {@link #mineQueue} exposed-first, then by distance. So it walks to visible ore before it
     * digs, and only digs the nearest buried ore when nothing nearby is exposed. Returns {@code true} on commit;
     * {@code false} while it still wants to sweep more (or found nothing — then {@link #gatherScan} goes COMPASS).
     */
    private boolean advanceScan(ServerLevel level) {
        final int bcx = scanAnchorX >> 4;
        final int bcz = scanAnchorZ >> 4;
        final int bsy = (scanAnchorY - RegionGrid.of(level).minY()) >> 4;
        int scanned = 0;
        while (scanCursor < SCAN_OFFSETS.length && scanned < SCAN_SECTIONS_PER_TICK) {
            final int[] o = SCAN_OFFSETS[scanCursor++];
            scanned++;
            final int ry = bsy + o[1];
            if (ry < 0) continue; // below the world column
            final List<BlockPos> cells =
                    ResourceScan.exactCells(level, bcx + o[0], ry, bcz + o[2], gatherColumn);
            if (cells == null || cells.isEmpty()) continue;
            for (BlockPos cell : cells) {
                if (!unreachableCells.contains(cell.asLong())) scanFound.add(cell);
            }
        }
        if (scanFound.isEmpty()) return false; // nothing found yet — keep sweeping / (exhausted → COMPASS)
        // Keep sweeping until there is at least one EXPOSED option in the pool (or the volume is exhausted), so
        // {@link #selectMineTarget}'s two-A* has both an exposed and a buried candidate to weigh — otherwise we
        // could commit to digging the nearest buried ore before ever noticing a nearby vein we could walk to.
        boolean anyExposed = false;
        for (BlockPos cell : scanFound) {
            if (hasExposedFace(level, cell)) { anyExposed = true; break; }
        }
        if (!anyExposed && scanCursor < SCAN_OFFSETS.length) return false;
        mineQueue.clear();
        mineQueue.addAll(scanFound); // the candidate POOL — selectMineTarget picks the cheapest-route target
        scanFound.clear();
        return true;
    }

    /**
     * MINE: drain {@link #mineQueue} nearest-first. A cell out of reach is approached with the two-tier driver
     * (targeting the ore cell itself, so it tunnels right up to it — fixing the old "stop a few blocks short");
     * once within {@link #MINE_REACH} the bot stands and timed-mines it via {@link BotMining} (real drops).
     * Quota counts PICKED-UP items: the inventory delta is accrued ONLY on standing-mine ticks (the sole fresh
     * drops there are the target's), so path-clearing on the approach never counts. An unreachable cell is
     * blacklisted + skipped. Queue drained → re-SCAN around the (now-moved) bot for the next nearest ore — this
     * is what makes it grab ore it walked past. Quota met → RETURN.
     */
    private void gatherMine(ServerLevel level) {
        // Re-run SELECTION when the bot crosses into a new region (the two-A* route-cost compare is
        // position-dependent — walking toward a cave mouth changes which vein is cheapest) — unless mid-break.
        if (!mining.busy() && regionOf(this.blockPosition()) != mineSelectRegion) {
            clearPlan();
            beginScanSweep();
            gatherPhase = GatherPhase.SCAN;
            return;
        }
        // (Re)choose the target when we have none, or the current one was mined / proven unreachable.
        if (mineTarget == null || level.getBlockState(mineTarget).isAir()
                || unreachableCells.contains(mineTarget.asLong())) {
            selectMineTarget(level);
        }
        if (mineTarget == null) { // pool worked out → re-scan around the (now-moved) bot for the next veins
            clearPlan();
            beginScanSweep();
            gatherPhase = GatherPhase.SCAN;
            return;
        }
        // Opportunistic re-target (throttled): while still approaching (not mid-break), every PROXIMITY_INTERVAL
        // ticks let a newly-close pooled vein challenge the committed target — but only switch if A* PROVES it
        // cheaper. This is what stops the bot walking past a nearer vein it discovers en route (the cave case).
        if (!mining.busy() && ++proximityTicks >= PROXIMITY_INTERVAL) {
            proximityTicks = 0;
            maybeChallengeTarget(level);
        }
        final BlockPos cell = mineTarget;
        if (Debug.ENABLED) PathDebugRenderer.highlightCell(level, cell); // show the chosen ore (/bot debug)
        // Break ONLY when both within reach AND with line-of-sight — the LOS gate is what stops the bot mining
        // ore through 3 blocks of stone (raw reach alone did). Buried ore therefore keeps driving (tunnelling)
        // until the bot is adjacent with a clear line; exposed ore can be mined from reach like a real player.
        if (withinReach(cell) && hasLineOfSight(level, cell)) {
            this.zza = 0.0f;
            if (level.getBlockState(cell).isAir()) { // the break just completed → collect its drop before moving on
                beginCollect(cell);
                return;
            }
            mining.request(cell); // BotMining faces + times the break + drops
        } else {
            gatherLastInvTotal = new BotInventory(this).totalItemCount(); // exclude approach pickups from Δ
            // Tight MINE arrival so it tunnels ALL the way to a line-of-sight cell, not 3 blocks short.
            boolean arrived = driveToward(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, cell,
                    MINE_ARRIVE_DIST, MINE_ARRIVE_Y);
            if (!arrived && navGaveUp) { // can't reach it — blacklist + drop from the pool, re-select next tick
                unreachableCells.add(cell.asLong());
                mineQueue.remove(cell);
                mineTarget = null;
                this.navGaveUp = false;
                clearPlan();
            }
        }
    }

    /**
     * Choose the next ore to mine (sets {@link #mineTarget}): compare the nearest EXPOSED candidate and the
     * nearest BURIED candidate by a REAL block-A* route cost (which prices the digging), and keep the cheaper —
     * so the bot walks to a visible vein rather than tunnelling past it, yet still digs a near buried vein when
     * that is genuinely cheaper. Because the cost is a real search, A* distinguishes "buried behind stone" (an
     * expensive dig) from "exposed in a cavern we can drop into" (a cheap fall) far better than any Y-heuristic.
     * Only TWO searches (owner-ratified — one-per-ore is untenable). {@code null} → the pool is worked out.
     */
    private void selectMineTarget(ServerLevel level) {
        mineQueue.removeIf(c -> level.getBlockState(c).isAir() || unreachableCells.contains(c.asLong()));
        if (mineQueue.isEmpty()) { mineTarget = null; return; }
        final double bx = getX(), by = getY(), bz = getZ();
        BlockPos nearestExposed = null, nearestBuried = null;
        double eD = Double.MAX_VALUE, bD = Double.MAX_VALUE;
        for (BlockPos c : mineQueue) {
            final double d = distSq(c, bx, by, bz);
            if (hasExposedFace(level, c)) { if (d < eD) { eD = d; nearestExposed = c; } }
            else                          { if (d < bD) { bD = d; nearestBuried = c; } }
        }
        if (nearestExposed == null) { mineTarget = nearestBuried; return; }
        if (nearestBuried == null)  { mineTarget = nearestExposed; return; }
        // Two real A* route costs (dig priced in); cheaper wins, tie → exposed (walking beats tunnelling). A null
        // plan (unreachable within the node budget) scores +inf, so the reachable candidate is preferred.
        final BotCaps caps = caps();
        final NavGridView grid = new NavGridView(level);
        final BlockPos start = this.blockPosition().below();
        final BlockPathPlan pe = BlockPathfinder.findPath(grid, start, nearestExposed, caps);
        final BlockPathPlan pb = BlockPathfinder.findPath(grid, start, nearestBuried, caps);
        final float ce = pe != null ? pe.cost() : Float.MAX_VALUE;
        final float cb = pb != null ? pb.cost() : Float.MAX_VALUE;
        mineTarget = (ce <= cb) ? nearestExposed : nearestBuried;
        proximityTicks = 0; // fresh target — wait a full interval before challenging it
    }

    /**
     * Opportunistic re-target: if a pooled vein has come within {@link #PROXIMITY_GRAB_RADIUS} of the bot, run a
     * real A* to it AND to the currently-committed {@link #mineTarget} from HERE, and switch only if the newcomer
     * is <b>proven strictly cheaper</b> (sticky — we do not abandon the committed vein on a hunch). This catches
     * the case the up-front two-A* can't: the bot heads the long way to a straight-line-nearest vein and, en
     * route, passes a genuinely cheaper one (the cave diagram). Gated + throttled by the caller, so it is at most
     * two A* every {@link #PROXIMITY_INTERVAL} ticks — and only when a candidate is actually near.
     */
    private void maybeChallengeTarget(ServerLevel level) {
        if (mineTarget == null) return;
        final double bx = getX(), by = getY(), bz = getZ();
        BlockPos challenger = null;
        double best = (double) PROXIMITY_GRAB_RADIUS * PROXIMITY_GRAB_RADIUS;
        for (BlockPos c : mineQueue) {
            if (c.equals(mineTarget)) continue;
            if (level.getBlockState(c).isAir() || unreachableCells.contains(c.asLong())) continue;
            final double d = distSq(c, bx, by, bz);
            if (d < best) { best = d; challenger = c; } // the nearest newly-close vein is the best challenger
        }
        if (challenger == null) return; // nothing close enough to reconsider — keep going
        final BotCaps caps = caps();
        final NavGridView grid = new NavGridView(level);
        final BlockPos start = this.blockPosition().below();
        final BlockPathPlan pc = BlockPathfinder.findPath(grid, start, challenger, caps);
        if (pc == null) return; // challenger unreachable → stick with the committed target
        final BlockPathPlan pt = BlockPathfinder.findPath(grid, start, mineTarget, caps);
        final float committed = pt != null ? pt.cost() : Float.MAX_VALUE;
        if (pc.cost() < committed) mineTarget = challenger; // proven cheaper → take it
    }

    /** Enter COLLECT: the target block just broke; pause to actually pick up its drop before the next ore. */
    private void beginCollect(BlockPos cell) {
        collectCell = cell;
        collectTicks = 0;
        gatherLastInvTotal = new BotInventory(this).totalItemCount(); // baseline before the drop lands in us
        clearPlan();
        gatherPhase = GatherPhase.COLLECT;
    }

    /**
     * COLLECT: the just-mined block is now air and its drop is on the ground. Step onto the cell so vanilla
     * auto-pickup grabs it; the moment the inventory count ticks up, accrue to the quota and MINE the next ore.
     * Bounded by {@link #COLLECT_TIMEOUT} so a drop that fell into a pit / lava / despawned can't strand the loop.
     * This is what stops the bot running straight off to the next vein and leaving its iron on the floor.
     */
    private void gatherCollect(ServerLevel level) {
        final int now = new BotInventory(this).totalItemCount();
        if (now > gatherLastInvTotal) { // got it
            gathered += now - gatherLastInvTotal;
            gatherLastInvTotal = now;
            finishCollect();
            return;
        }
        if (collectCell == null || ++collectTicks > COLLECT_TIMEOUT) { // drop lost / gone — give up, move on
            finishCollect();
            return;
        }
        if (Debug.ENABLED) PathDebugRenderer.highlightCell(level, collectCell); // show the drop we're collecting
        driveToward(collectCell.getX() + 0.5, collectCell.getY() + 0.5, collectCell.getZ() + 0.5, collectCell,
                MINE_ARRIVE_DIST, MINE_ARRIVE_Y);
    }

    /** Leave COLLECT: drop the mined cell from the queue, check the quota, and resume MINE (or RETURN). */
    private void finishCollect() {
        if (collectCell != null) mineQueue.remove(collectCell); // done with it (it's air now anyway)
        collectCell = null;
        mineTarget = null; // pick the next ore via a fresh two-A* compare
        clearPlan();
        if (gathered >= gatherQuota) {
            gatherPhase = GatherPhase.RETURN;
            chat("got " + gathered + " " + ResourceClasses.nameOfColumn(gatherColumn) + " — heading back.");
            return;
        }
        enterMine();
    }

    /** Transition into MINE, stamping the region the current target was selected from (for the re-select gate). */
    private void enterMine() {
        mineSelectRegion = regionOf(this.blockPosition());
        gatherPhase = GatherPhase.MINE;
    }

    /** The level-0 region (16-block cube) a world cell sits in — the granularity of the MINE re-select trigger. */
    private static long regionOf(BlockPos p) {
        return regionKey(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
    }

    /**
     * COMPASS: nothing loaded nearby, so ask the pyramid for the nearest non-blacklisted region holding the
     * resource and start walking toward it — while still live-scanning (so ore that loads en route diverts us
     * straight to MINE; we don't stride past visible ore). No pyramid hint → report + STAY.
     */
    private void beginCompass(ServerLevel level) {
        List<ResourceQuery.ResourceHit> hits =
                ResourceQuery.find(level, gatherColumn, this.blockPosition(), 1, 8);
        for (ResourceQuery.ResourceHit h : hits) {
            final long key = regionKey(h.rx(), h.ry(), h.rz());
            if (gatherBlacklist.contains(key)) continue;
            compassTarget = h.center();
            compassKey = key;
            this.navGaveUp = false;
            clearPlan();
            beginScanSweep();
            gatherPhase = GatherPhase.COMPASS;
            return;
        }
        chat("I don't see any " + ResourceClasses.nameOfColumn(gatherColumn) + " nearby.");
        setMode(Mode.STAY);
    }

    /** COMPASS drive: head for the pyramid hint, live-scanning as we go (re-anchoring the scan as the bot
     *  travels). Ore found → MINE. Reached (or can't reach) the hint with the local scan still empty → the
     *  tally was stale; blacklist that region and get the next hint. */
    private void gatherCompass(ServerLevel level) {
        final BlockPos p = this.blockPosition();
        if (Math.abs(p.getX() - scanAnchorX) + Math.abs(p.getY() - scanAnchorY)
                + Math.abs(p.getZ() - scanAnchorZ) >= 12) {
            beginScanSweep(); // re-anchor the scan on the bot as it travels
        }
        if (advanceScan(level)) {
            gatherLastInvTotal = new BotInventory(this).totalItemCount();
            clearPlan();
            enterMine();
            return;
        }
        final BlockPos c = compassTarget;
        boolean arrived = driveToward(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, c.below());
        if (arrived || navGaveUp) {
            gatherBlacklist.add(compassKey);
            this.navGaveUp = false;
            clearPlan();
            beginCompass(level);
        }
    }

    /** RETURN: drive back to where {@code /bot gather} was issued, then STAY. */
    private void gatherReturn(ServerLevel level) {
        BlockPos s = gatherStartPos;
        boolean arrived = driveToward(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, s.below());
        if (arrived) {
            chat("back with " + gathered + " " + ResourceClasses.nameOfColumn(gatherColumn) + ".");
            setMode(Mode.STAY);
        } else if (navGaveUp) {
            chat("I got " + gathered + " " + ResourceClasses.nameOfColumn(gatherColumn)
                    + " but can't find my way back.");
            setMode(Mode.STAY);
        }
    }

    /** True when {@code cell}'s centre is within a player's mining reach ({@link #MINE_REACH}) of the bot's eyes. */
    private boolean withinReach(BlockPos cell) {
        double dx = cell.getX() + 0.5 - getX();
        double dy = cell.getY() + 0.5 - getEyeY();
        double dz = cell.getZ() + 0.5 - getZ();
        return dx * dx + dy * dy + dz * dz <= MINE_REACH * MINE_REACH;
    }

    /**
     * The mining line-of-sight gate: true iff a straight line from the bot's eyes to {@code target}'s centre
     * is not interrupted by a full solid block BEFORE the target (the target itself is excluded). This is what
     * stops the bot breaking ore <i>through</i> a wall — {@link #withinReach} alone (raw distance) let it mine
     * 3 blocks of stone away. A stepped sample (~4/block) is plenty at reach distance and stays allocation-free
     * (a single reused {@link BlockPos.MutableBlockPos}); solidity uses the version-portable
     * {@link BlockShapes#isSolidRender} seam, not a fragile direct MC raytrace.
     */
    private boolean hasLineOfSight(net.minecraft.world.level.Level level, BlockPos target) {
        final double ox = getX(), oy = getEyeY(), oz = getZ();
        final double dx = target.getX() + 0.5 - ox, dy = target.getY() + 0.5 - oy, dz = target.getZ() + 0.5 - oz;
        final int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 4.0));
        for (int i = 1; i < steps; i++) {
            final double t = (double) i / steps;
            final int bx = (int) Math.floor(ox + dx * t);
            final int by = (int) Math.floor(oy + dy * t);
            final int bz = (int) Math.floor(oz + dz * t);
            if (bx == target.getX() && by == target.getY() && bz == target.getZ()) continue; // the target itself
            scratchPos.set(bx, by, bz);
            final BlockState s = level.getBlockState(scratchPos);
            if (!s.isAir() && BlockShapes.isSolidRender(s, level, scratchPos)) return false;
        }
        return true;
    }

    /**
     * A cheap, position-independent "easy target" test for ore SELECTION: true iff any of {@code cell}'s six
     * face neighbours is not a full solid block (air / water / a non-cube), i.e. the ore can be reached and
     * mined without tunnelling to it. Preferred over a per-bot line-of-sight for targeting because it does not
     * change as the bot moves (no flip-flop) and is 1-6 block reads with an early out.
     */
    private boolean hasExposedFace(net.minecraft.world.level.Level level, BlockPos cell) {
        for (int[] o : FACE_OFFSETS) {
            scratchPos.set(cell.getX() + o[0], cell.getY() + o[1], cell.getZ() + o[2]);
            final BlockState s = level.getBlockState(scratchPos);
            if (s.isAir() || !BlockShapes.isSolidRender(s, level, scratchPos)) return true;
        }
        return false;
    }

    private static double distSq(BlockPos p, double x, double y, double z) {
        double dx = p.getX() + 0.5 - x, dy = p.getY() + 0.5 - y, dz = p.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Pack a level-0 region's coords into a blacklist key (21/21/22 bits — ample for any world extent). */
    private static long regionKey(int rx, int ry, int rz) {
        return ((long) (rx & 0x1FFFFF) << 43) | ((long) (rz & 0x1FFFFF) << 22) | (ry & 0x3FFFFF);
    }

    // ---- Nether-portal follow ------------------------------------------------------------------------

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
        clearPlan();
        this.settledFloor = null;
        this.planStartFloor = null;
        this.navGaveUp = false;
        resetPortalSeek();
        ClientLoad.markLoaded(this);
    }

    /**
     * FOLLOW/COME cross-dimension guard. When the owner is in another level, the normal goal is
     * meaningless in the bot's level — instead the bot seeks the nearest KNOWN nether portal and walks
     * in (vanilla teleports it; {@link #onLevelChanged} re-anchors on arrival). Level equality is
     * re-evaluated every tick, so an owner returning to the bot's dimension aborts the seek mid-route
     * and normal behaviour resumes immediately.
     *
     * @return {@code true} if the owner is elsewhere and the portal-follow consumed this tick.
     */
    private boolean followThroughPortal() {
        if (Worlds.of(owner) == Worlds.of(this)) {
            if (portalTarget != null || enteringPortal || portalSeekGaveUp || portalSeekAnnounced) {
                resetPortalSeek(); // owner came back — abandon the seek and resume normal FOLLOW/COME
            }
            return false;
        }
        portalSeekTick();
        return true;
    }

    /**
     * One portal-seek tick: pick the nearest known portal (once), path to it with the normal two-tier
     * driver, and hand off to the {@link #enterPortalTick ENTER} terminal state on approach. No known
     * portal → one chat line + hold (the {@link #navGaveUp}-style hold: no blind straight-line walking).
     */
    private void portalSeekTick() {
        if (portalSeekGaveUp) { // hold; cleared when the owner returns, the mode changes, or we teleport
            this.zza = 0.0f;
            return;
        }
        if (enteringPortal) {
            enterPortalTick();
            return;
        }

        final ServerLevel level = (ServerLevel) Worlds.of(this);
        if (portalTarget == null) {
            BlockPos p = NetherPortalIndex.nearest(level, this.blockPosition());
            if (p == null) {
                portalSeekGaveUp = true;
                this.zza = 0.0f;
                chat("I don't know a portal to follow you through.");
                return;
            }
            // Descend to the BOTTOM portal cell of the column so the pathing goal floor (below it) is the
            // standable obsidian frame base. Live-world read (cold, a few cells, once per seek).
            BlockPos below = p.below();
            while (NavBlock.isPortal(NavBlock.descriptorFor(level.getBlockState(below)))) {
                p = below;
                below = p.below();
            }
            portalTarget = p.immutable();
            if (!portalSeekAnnounced) {
                portalSeekAnnounced = true;
                chat("You left this world — heading for the portal at " + compact(portalTarget) + ".");
            }
        }

        // Path to the portal. isGoal tolerance (±1 xz / ±2 y) and ARRIVE_DIST both stop the bot ADJACENT
        // to the portal, so arrival (or proximity) switches to the dedicated ENTER state below.
        boolean arrived = driveToward(portalTarget.getX() + 0.5, portalTarget.getY(),
                portalTarget.getZ() + 0.5, portalTarget.below());
        double dx = portalTarget.getX() + 0.5 - getX();
        double dy = portalTarget.getY() - getY();
        double dz = portalTarget.getZ() + 0.5 - getZ();
        if (arrived || (dx * dx + dz * dz <= PORTAL_ENTER_DIST * PORTAL_ENTER_DIST
                && Math.abs(dy) <= ARRIVE_Y)) {
            enteringPortal = true;
            portalEnterTicks = 0;
            clearPlan(); // ENTER suppresses replan/steer (like the STAY hold): face + walk-in only
        }
    }

    /**
     * The ENTER terminal state: face the portal cell centre and walk forward until the feet occupy the
     * portal column, then stand still — no jump, no steering — and let vanilla do everything
     * ({@code NetherPortalBlock.entityInside} marks the entity, the portal process ticks its wait inside
     * the bot's own {@code baseTick}, then the dimension-change path runs; its client packets are absorbed
     * by FakeClientConnection). One attempt runs {@link #PORTAL_ENTER_TIMEOUT_TICKS}; the first timeout
     * backs off {@link #PORTAL_BACKOFF_TICKS} and retries once from a fresh index query (the portal may
     * have broken while we ground against it); the second gives up with a chat line.
     */
    private void enterPortalTick() {
        // Retry back-off: step away from the portal for a moment, then re-approach from scratch.
        if (portalBackoffTicks > 0) {
            portalBackoffTicks--;
            faceHorizontally(getX() - (portalTarget.getX() + 0.5), getZ() - (portalTarget.getZ() + 0.5));
            this.zza = 1.0f;
            if (portalBackoffTicks == 0) {
                enteringPortal = false;
                portalTarget = null; // re-query nearest on the retry; portalEnterRetries stays consumed
            }
            return;
        }

        portalEnterTicks++;
        if (footX() == portalTarget.getX() && footZ() == portalTarget.getZ()) {
            this.zza = 0.0f; // inside the portal column: stand still while the portal process ticks
        } else {
            faceHorizontally(portalTarget.getX() + 0.5 - getX(), portalTarget.getZ() + 0.5 - getZ());
            this.zza = 1.0f; // walk straight in
        }

        if (portalEnterTicks >= PORTAL_ENTER_TIMEOUT_TICKS) {
            if (portalEnterRetries == 0) {
                portalEnterRetries = 1;
                portalEnterTicks = 0;
                portalBackoffTicks = PORTAL_BACKOFF_TICKS;
            } else {
                enteringPortal = false;
                portalSeekGaveUp = true;
                this.zza = 0.0f;
                chat("I couldn't get through the portal.");
            }
        }
    }

    /** Drop all portal-seek/ENTER state (owner returned, mode changed, or the teleport completed). */
    private void resetPortalSeek() {
        portalTarget = null;
        enteringPortal = false;
        portalEnterTicks = 0;
        portalBackoffTicks = 0;
        portalEnterRetries = 0;
        portalSeekAnnounced = false;
        portalSeekGaveUp = false;
    }

    /** Drive toward {@code (tx,ty,tz)} with the default follow/come arrival tolerance ({@link #ARRIVE_DIST}/
     *  {@link #ARRIVE_Y}). Mining passes a tighter tolerance so it goes all the way to the target. */
    private boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor) {
        return driveToward(tx, ty, tz, goalFloor, ARRIVE_DIST, ARRIVE_Y);
    }

    /**
     * Path toward {@code (tx,ty,tz)} (goal floor cell {@code goalFloor}), steering along the plan and
     * falling back to a straight-line steer off-grid. Returns {@code true} once within {@code arriveDist}
     * horizontally <i>and</i> {@code arriveY} vertically — the caller decides what arrival means for its mode
     * (loose for FOLLOW/COME; tight for MINE, which must reach a line-of-sight cell before it can break).
     */
    private boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor,
                                double arriveDist, double arriveY) {
        double dx = tx - this.getX();
        double dy = ty - this.getY();
        double dz = tz - this.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        // Arrived only when close in 3D. Horizontal proximity alone would let the bot stop directly
        // BELOW the target (it walks under a sky platform / the top of a staircase and quits) — it must
        // also match the target's height, which is what makes it actually climb to reach you.
        if (distXZ <= arriveDist && Math.abs(dy) <= arriveY) {
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
            // every tick; only the ACTION waits for a boundary. The stall/off-track recovery re-anchors
            // settledFloor to the live cell, so a move that never completes still reaches a boundary and
            // re-searches (the escape hatch).
            if (blockRefreshTicks > 0) blockRefreshTicks--;
            if (settledFloor != null && this.blockPosition().below().equals(settledFloor)) {
                pathPlan.onBotMoved(settledFloor, currentStartMode());
                boolean consumed = path != null && waypointIndex >= path.size() && !pathPlan.isComplete();
                if (consumed || blockRefreshTicks <= 0) {
                    pathPlan.refreshWindow();
                    blockRefreshTicks = REPLAN_TICKS;
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
                    pathPlan.preplan(path.waypoint(path.size() - 1).below(),
                            EditSnapshot.fromRemainingSteps(path, lastEditedIndex + 1),
                            currentStartMode());
                }
            }
        }
        // Planless async adoption (review finding): a bot with NO walkable plan can't wait for the settled
        // boundary the normal drain rides — it may never settle (treading water, mid-fall), and there is
        // nothing to un-adopt. Poll at tick rate and, on adoption, run the same swap/anchor mechanics the
        // boundary block runs — anchored at the bot's LIVE floor, exactly how replan() seeds a fresh plan.
        if (pathPlan != null && path == null) {
            BlockPos liveFloor = this.blockPosition().below();
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
                this.stuckTicks = 0;
                this.stallTicks = 0;
                this.offTrackTicks = 0;
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
                    compact(this.blockPosition()), compact(goalFloor),
                    wt == null ? "?" : compact(wt), pathPlan.windowTargetKind(), hop);
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
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos startFloor = this.blockPosition().below();

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
            this.pathPlan = new PathPlan(level, RegionGrid.of(level), startFloor, goalFloor, caps(),
                    inventoryFeasibility(), currentStartMode(), null, executor);
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
        // A fresh plan starts with fresh recovery state: steerAlongPath stops running when a drive ends, so
        // these counters FREEZE at whatever the old drive last saw (e.g. a grind at its final waypoint) and
        // a stale count would pre-arm the jump backstop / escape hatch on the new plan's first ticks — the
        // other half of the startup-hop bug alongside the JUMP_FREE_ARM_TICKS arming above.
        this.stuckTicks = 0;
        this.stallTicks = 0;
        this.offTrackTicks = 0;

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
            field = RegionPathfinder.costToGoalField(rgrid, minY, searchGoal,
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
            BlockPathfinder.REGION_FIELD = field;
            BlockPathfinder.REGION_HEURISTIC = regionMode && field != null;
            BlockPathPlan plan = haveWindow
                    ? BlockPathfinder.findPath(new NavGridView(level), startFloor, searchGoal, caps, null, corridor, inv)
                    : BlockPathfinder.findPath(new NavGridView(level), startFloor, goalFloor, caps, null, null, inv);
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
            BlockPathfinder.REGION_HEURISTIC = false;
            BlockPathfinder.REGION_FIELD = null;
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
                // A move just COMPLETED — this is the settled stand cell the driver commits/replans off (its
                // edits are now applied). Advancing the anchor only here is what keeps a mid-move transient from
                // triggering a boundary replan (see settledFloor). w is the stand position; below() = its floor.
                this.settledFloor = w.below();
                break;
            }
        }
        if (waypointIndex >= path.size()) {
            this.zza = 0.0f;
            return;
        }

        Movement movement = path.movement(waypointIndex);
        BlockPos wp = path.waypoint(waypointIndex);

        // Build (once per step) this move's phase-model plan, if it has one. A change of waypoint (a new step, or
        // a window swap that reset the cursor) rebuilds it and resets the runner. The plan is written in the
        // search-native FLOOR cells; waypoints are floor.above() (stand positions), so the floor is one below.
        if (waypointIndex != activePlanStep) {
            activePlanStep = waypointIndex;
            BlockPos toFloor = wp.below();
            BlockPos fromFloor = (waypointIndex == 0)
                    ? (planStartFloor != null ? planStartFloor : this.blockPosition().below())
                    : path.waypoint(waypointIndex - 1).below();
            MovePlan mp = movement.plan(fromFloor.getX(), fromFloor.getY(), fromFloor.getZ(),
                    toFloor.getX(), toFloor.getY(), toFloor.getZ());
            if (mp != null) phaseRunner.begin(mp); else phaseRunner.clear();
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

        // Execute the step. A CONVERTED move (has a phase plan) reconciles its geometry against the LIVE world
        // each tick — breaking/placing reactively via the PhaseRunner, no one-shot applyEdits, so a missed edit
        // self-heals. An UNCONVERTED move keeps the original path: apply its folded edits once, then steer.
        if (phaseRunner.active()) {
            phaseRunner.run(this, cursor);
        } else {
            StepEdits edits = path.edits(waypointIndex);
            if (edits != null && waypointIndex != lastEditedIndex && movement.editsReadyNow(this)) {
                lastEditedIndex = waypointIndex;
                applyEdits(edits);
            }
            movement.steer(this, cursor);
        }
        this.steeredThisTick = true;                            // a step ran → sprint re-asserted if a sprint move
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
        // A timed break in progress is NOT a stall: a phase holding on an AIR need is legitimately grounded
        // and stationary for the whole vanilla mining time, so the jump-free would misfire (vanilla's
        // destroy progress is 5x slower off the ground) and the grounded-stall arm below would burn a window
        // re-search every RECOVER_COOLDOWN ticks until a hard block finally breaks.
        Vec3 velocity = this.getDeltaMovement();
        boolean grounded = EntityState.onGround(this);
        if (velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR && grounded && !mining.busy()) {
            // ARM before acting: the jump-free fires only once the no-progress state has PERSISTED
            // (JUMP_FREE_ARM_TICKS) — on the first driving ticks of a fresh plan, low speed is just a bot
            // that hasn't accelerated yet, and the old unarmed jump was the "startup hop" (a spurious leap
            // off the course when the plan started on a ledge). The counter still runs from tick 1, so the
            // GROUNDED_STALL_TICKS escape-hatch arm below keeps its exact semantics.
            if (++stuckTicks >= JUMP_FREE_ARM_TICKS) this.setJumping(true);
            if (stuckTicks == STUCK_DUMP_TICKS && Debug.ENABLED) dumpStuck(wp);
        } else {
            stuckTicks = 0;
        }

        // Slip detection: count consecutive ticks the bot is off the planned segment (a transient bob won't
        // trip it). Stall detection: no 3-D progress while airborne/underwater (a jump can't help there).
        // Grounded-stall detection rides the stuckTicks counter above: grounded + on-line + no progress —
        // the case the first two arms are blind to (a missed parkour jump grinding at the bottom of the gap).
        offTrackTicks = (SteerControl.crossTrack(this, cursor) > OFF_TRACK_DIST) ? offTrackTicks + 1 : 0;
        stallTicks = (!grounded && velocity.lengthSqr() < STUCK_SPEED_SQR) ? stallTicks + 1 : 0;
        if (recoverCooldown > 0) {
            recoverCooldown--;
        } else if (offTrackTicks >= OFF_TRACK_TICKS || stallTicks >= STALL_TICKS
                || stuckTicks >= GROUNDED_STALL_TICKS) {
            blockRefreshTicks = 0; // re-search the window from the bot's actual cell next tick (driveToward)
            // Recovery escape hatch: the bot has genuinely slipped/stalled off-plan (a move that won't complete),
            // so re-anchor the driver to its LIVE cell — this is exactly the case where we DO want to bail on the
            // unfinished move and re-plan from where we're actually stuck, overriding the settled-waypoint gate.
            this.settledFloor = this.blockPosition().below();
            offTrackTicks = 0;
            stallTicks = 0;
            stuckTicks = 0;
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
                // the follower jumps onto a cap that never existed. Clear it like a player would — unless
                // the occupant is unbreakable or owner-protected (mayBreak): then skip, replan nets it.
                if (!cfg.mayBreak(occupant, occupant.getDestroySpeed(level, p))) continue;
                WorldEdits.breakBlock(level, p);
            }
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
        mining.request(new BlockPos(x, y, z));
    }

    /** Place a footing block server-side (carried block when {@code placement.consumesBlocks}, else conjured);
     *  the placement half of the reconcile seam, mirroring {@link #applyEdits}'s place path for a single cell. */
    @Override
    public void place(int x, int y, int z) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos p = new BlockPos(x, y, z);
        BlockState existing = level.getBlockState(p);
        Config cfg = ConfigLoader.config();
        // A protected occupant is never cleared NOR replaced by a placement (filling the cell destroys it
        // either way) — the planner's OPEN_PLACE bit excludes protected cells; this live backstop also
        // covers a stale grid. Give up on the cell; the next tick / replan nets it.
        if (!existing.isAir() && cfg.protectedBlocks().matches(existing)) return;
        if (!Replaceable.isReplaceable(existing)) {
            // Planner/executor vocabulary gap: the search's open-for-place bit is SHAPE-based (an
            // empty-collision cell — sweet berry bush, torch, sapling — is open to place into), but
            // vanilla replaceability is stricter. Refusing outright here made a planned place silently
            // no-op, so the bot jumped onto a cap that never existed (the berry-maze hop-over bug). Do
            // what a player does instead: clear the soft occupant, then place. Every empty-shape
            // occupant is soft (hardness ~0), so the clear is effectively free and stays unpriced
            // planner-side (EditScratch.requireFloor). mayBreak refuses an unbreakable occupant (give
            // up, replan nets it) AND an owner-protected one (mining.protectedBlocks — never broken).
            if (!cfg.mayBreak(existing, existing.getDestroySpeed(level, p))) return;
            WorldEdits.breakBlock(level, p);
        }
        lookAtCell(x, y, z); // look at what we place — for a pillar footing that's straight down, like a player
        if (cfg.consumesBlocks()) {
            Block block = new BotInventory(this).consumeOnePlaceable();
            if (block == null) return; // out of blocks — the next tick / replan nets it
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
