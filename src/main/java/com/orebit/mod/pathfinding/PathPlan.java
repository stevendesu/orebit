package com.orebit.mod.pathfinding;

import com.orebit.mod.Debug;
import com.orebit.mod.NavJourneyStats;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.async.SearchRequest;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.ClutchModel;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.HierarchicalRegionPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The two-tier <b>sliding-window driver</b> (PRD §6.3–6.5, §7.1, §10 Phase 3; HPA-IMPLEMENTATION.md §9,
 * "3h"). Unifies the coarse region {@link RegionPathPlan skeleton} with the per-window block plans the
 * follower actually walks, so a multi-thousand-block goal that flat block-A* would flood/refuse becomes a
 * sequence of short (~3-region, ~48-block) block searches.
 *
 * <h2>Ratified design — sliding window over a region skeleton (NOT portals, NOT staged region transitions)</h2>
 * On construction we plan the coarse skeleton once via {@link RegionPathfinder#plan}. We then keep a
 * <b>window</b> of {@link #WINDOW} consecutive skeleton regions and run the block tier toward a target only
 * ~3 regions ahead — far enough that each block search is short, near enough that it stays inside loaded
 * terrain. There are <b>no entrances and no portals</b> (PRD §6.5): the block target is either the real
 * {@code goalFloor} (when the goal's region is already in the window) or the window's far region center
 * projected down to a standable floor column — any traversable arrival is acceptable. The existing
 * {@code steerAlongPath}/{@code applyEdits} follower machinery is unchanged; it just follows these windowed
 * block plans instead of one monolithic path.
 *
 * <h2>The wiggle / commit rule (HPA-IMPLEMENTATION.md §9 — only the FINAL committed crossing advances)</h2>
 * Block-A* ignores regions, so the block path may weave across a region boundary many times
 * ({@code A→B→A→B→A→B→C}). Naively advancing the window the first time the bot's floor lands in the next
 * skeleton region would thrash the window (replanning on every transient dip). Instead we track
 * {@code committedIndex} = the furthest skeleton index the bot has <i>committed</i> to. On each
 * {@link #onBotMoved}, we look forward for the skeleton index {@code j} of the bot's current region and
 * advance only when {@link #committed(int) committed(j)} holds: <b>none of the active block plan's REMAINING
 * waypoints map to a skeleton region with index in {@code [committedIndex, j)}</b> — i.e. the path never
 * goes back. This advances {@code A→B→A→B→A→B→C} to B exactly once (on the final B-entry after which the
 * remaining path stays in B/C) and to C when the path stays in C. Transient dips back to earlier regions
 * never lower {@code committedIndex} and never replan. A window target the bot ALREADY satisfies (within the
 * block tier's ±1/±2 goal tolerance) is committed+slid at target-selection time — {@link #replanBlock}'s
 * forward-slide — so a search is never aimed at a satisfied target (s52; no debounce fallback exists).
 *
 * <h2>Replan triggers</h2>
 * The window's block plan is recomputed ({@link #replanBlock}) when the window advances (commit), when the
 * current block plan is {@code null} (BLOCKED — terrain changed under us), and when the bot exhausts the
 * current block plan before reaching the window target (handled by the same commit/advance logic on the next
 * move). {@link #replanBlock} runs {@link BlockPathfinder#findPath} over a <b>fresh full</b>
 * {@code new NavGridView(level)} — not a bounded view, because the window already bounds the search by
 * keeping the target only ~3 regions away.
 *
 * <h2>Fragment model (HPA-FRAGMENTS.md §6, §S4)</h2>
 * The region tier is the connectivity-aware <b>fragment</b> model: the skeleton's steps are
 * {@code (region, fragment)} nodes carrying a per-step <b>portal cell</b> (the reachable on-face boundary cell
 * the path enters the step's region through). Two places use it:
 * <ul>
 *   <li><b>Window target</b> ({@link WindowTargeting#target}): the far step's {@link RegionPathPlan#portalCell portal
 *       cell} — a real occupiable cell — instead of a {@code center → projectToStandableFloor} projection that
 *       would land on buried/mid-air cells in carved terrain (the §6 "buried target" bug).</li>
 *   <li><b>Commit key</b> ({@link #forwardIndexOf}): the bot's current skeleton step is matched on
 *       {@code (region, fragment)} (its fragment resolved alloc-free via {@link RegionPathfinder#fragmentOf}),
 *       so an <i>intra-region mine edge</i> (two skeleton steps sharing a region but not a fragment) is
 *       distinguished and committed when the bot reaches the new fragment — there is no boundary thrash within
 *       a single-region dig, so that case bypasses the inter-region wiggle hysteresis.</li>
 * </ul>
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * Allocation-light: the wiggle scan reads the live {@link BlockPathPlan}'s waypoints in place (≤ a window of
 * ~48 cells, no copy, no boxing), region mapping is pure {@link RegionAddress} integer math, and the per-tick
 * fast path (no commit) does only a region-coord compare. A new {@link NavGridView} + a {@link BlockPathPlan}
 * are allocated per <i>replan</i> (an infrequent, already-heavy event — the block search itself is the
 * allocation-free hot path), not per tick.
 *
 * @see RegionPathfinder
 * @see RegionPathPlan
 * @see BlockPathfinder
 */
public final class PathPlan {

    /**
     * Number of consecutive skeleton regions the window spans (HPA-IMPLEMENTATION.md §9). <b>4</b> (was 3):
     * the region graph is 6-connected, so a goal that is a 3-axis DIAGONAL from the bot (Chebyshev 1) has a
     * <b>4-region</b> L-shaped skeleton (up → over → across); a 3-region window left that goal one hop past the
     * horizon, so {@code goalInWindow} was false and the window aimed at an intermediate CORNER portal off the
     * direct diagonal — the bot detoured to the region centre before doubling back (the sand-dune / origin
     * short-path wander). A 4-region window contains the whole such skeleton, so the goal is targeted directly.
     * The cost is a longer worst-case block search (an extra ~16-block region span, worst-case cornerwise ~45
     * more waypoints), affordable now that the region-refined heuristic + forced-cost premium + macro cuboids
     * hold the flood pathologies that made a tight window necessary — verified no flood/partial regression on
     * the headless region/full-search suite. Stopgap until the movement executor is reliable enough to re-tune.
     */
    public static final int WINDOW = 4;

    /**
     * Horizontal corridor slack in blocks, added to the window's <b>region bounds</b> (not the bot's current
     * position) — HPA-IMPLEMENTATION.md §9/§15a. Set to <b>9 = a region half (8, boundary→center) + 1</b>:
     * that is exactly the reach the coarse <i>face-to-center</i> cost represents into an adjacent region, so a
     * beneficial one-region dip is admitted while anything past a neighbour's center (territory the cost model
     * attributes to the <i>next</i> region — 2-away wandering) is forbidden. Tighter than a full region (16):
     * for the straight pillar the scan footprint is 16+9+9 = 34 wide → 34² ≈ 1156 cells vs 48² ≈ 2304 at
     * margin 16 — about half the worst-case flood, for the same beneficial-dip coverage. The balance knob
     * between "don't over-constrain" and "don't flood"; the widen-on-failure retry covers the rare deeper dip.
     */
    public static final int CORRIDOR_MARGIN = 9;

    /** Vertical corridor slack in blocks — room for a fall/jump just outside the window's region span. */
    public static final int CORRIDOR_VMARGIN = 8;

    /**
     * Margin (blocks) around the bot→target segment for the macro-cuboid GROWTH cap (NOT a search confinement —
     * the search is unconfined). Caps how big one cuboid can grow so a flat world can't grow one unbounded;
     * small because cuboids only need to span the immediate uniform terrain the search collapses.
     */
    public static final int CUBOID_CAP_MARGIN = 16;

    /**
     * Make the {@link WindowTargeting#target window-target} "goal in window" test <b>fragment-aware</b>. The test fires
     * when a window step is the goal <i>region</i>; but a region can appear in the window at one fragment while
     * the goal is a DIFFERENT fragment of that region reached only via a loop (the goal is a separate pocket of
     * the bot's own start region — e.g. the start on an upper ledge, the goal in a lower cave of the same
     * region, connected only by an up-and-over route). A region-only match then targets the goal <b>directly</b>,
     * unconfining the block search into a huge flood (it hunts the whole open volume for a way over/around).
     * When {@code true}, the goal branch additionally requires the window step's <i>fragment</i> to be the goal
     * fragment (the skeleton tail), so the false positive falls through to the near-window portal target and the
     * search stays local.
     *
     * <p><b>Default {@code true}</b> (the fix is live): the region-informed field work that used the region-only
     * flood as its A/B repro has landed, so the false positive is now corrected in production — a goal in a
     * different fragment of a windowed region targets the near-window portal and the search stays local instead of
     * flooding the open volume. (Left as a flag for a quick revert while the field wiring beds in.)
     */
    public static boolean FRAGMENT_AWARE_GOAL_WINDOW = true;

    // ---- immutable inputs (package-private where a same-package collaborator reads them) --------------
    final ServerLevel level;
    final RegionGrid regionGrid;
    private final BlockPos goalFloor;
    /** The region-informed cost-to-goal heuristic field threaded read-only into every windowed block search,
     *  sync and async — rooted at the CURRENT window's search target ({@link #fieldRoot}), NOT the final goal.
     *  A window search guided by a final-goal-rooted gradient chases the wrong attractor (startH 60× the
     *  window octile), floods tens of thousands of nodes, and its PARTIAL commit point (min-h at pop) inches
     *  toward the final goal instead of the window target — the 2026-07-06 cave incident. Rebuilt lazily by
     *  {@link #regionFieldFor} whenever the window target moves (~6 µs, replan cadence only); each rebuild
     *  constructs a NEW write-once instance and swaps the reference, so in-flight async workers keep reading
     *  the old (still-immutable) instance. {@code null} before the first {@link #replanBlock} and after a
     *  failed build (⇒ the block search falls back to plain octile, the documented byte-identical fallback). */
    private RegionCostField regionField;
    /** The search target {@link #regionField} is rooted at ({@code null} until the first build). The root
     *  compare in {@link #regionFieldFor} gates the rebuild — an unchanged window target reuses the cached
     *  field across every replan/pre-plan toward it, never rebuilding per tick. */
    private BlockPos fieldRoot;
    /** The floor cell the CURRENT {@link #blockPlan} was searched FROM — the plan's implicit step "−1".
     *  Only meaningful while {@code blockPlan != null} (every reader gates on that first), so the null
     *  sites deliberately leave it stale rather than clearing it at seven places.
     *
     *  <p>Load-bearing for {@link #botOnBlockPlan}: {@code BlockPathfinder}'s reconstruct is
     *  START-EXCLUSIVE ("the start cell itself is not a waypoint — the bot is already there"), so a plan
     *  whose FIRST move is a long-range jump (Parkour/DiagonalParkour gaps 1–3, Fall) has NO waypoint
     *  within the ±1 membership box of the very cell it was planned from. Without this the cascade reads a
     *  freshly-installed plan as "bot is off-route" on the very next tick — a matcher artifact, exactly what
     *  {@code HierarchicalRegionPlan}'s exhausted/deviated invariant forbids. */
    private BlockPos blockPlanStart;
    /** The waypoint index of {@link #blockPlan} the bot's floor matched when the plan installed via a
     *  FAST_FORWARD seam adoption ({@link AsyncWindowSearch#pollSeededParked} case 3), {@code -1} on
     *  every other install. Like {@link #blockPlanStart}, only meaningful while {@code blockPlan != null}
     *  — every install site overwrites it. Read by the follower's install seed so an already-executed
     *  prefix is never re-run from a mis-anchored frame (DESIGN-replan-handoff.md §5/R3; the 2026-08-19
     *  run-5 stale-frame Pillar wedge). */
    private int adoptedMatchedIndex = -1;
    /** #4 Increment 1 (DESIGN-boxed-in-reachability §4.2): whether the last region-tier give-up
     *  ({@link #repairBlocked}) harvested a CLOSED-flood proof that the bot's own region is provably
     *  goal-disconnected (boxed-in) under these caps — the honest discriminator between a STRUCTURAL BLOCKED
     *  and a mere budget artifact. Journey-scoped, never persisted (owner ruling 2026-07-25). */
    private boolean boxedInProven;
    /** #4 (PROACTIVE rework): the goal-neighbourhood build signal ({@link #goalNeighbourhoodBuildSignal}) at
     *  which {@link #maybeProactiveBoxedIn} last ran its harvest, and whether that cache is valid. The
     *  proactive harvest re-runs ONLY when this signal advances (the goal region or an adjacent chunk column —
     *  its sealing perimeter — was (re)built or edited), so a static world pays one O(9) monotone-version read
     *  per settled boundary, never a full harvest flood. Reset ({@code proactiveSignalValid=false}) whenever
     *  the goal region is unbuilt, so the first post-build signal always forces a check. Journey-scoped. */
    private long lastProactiveSignal;
    private boolean proactiveSignalValid;
    /** Tool-aware region dig-cost model, snapshotted once from the ctor's inventory — shared by the cascade
     *  build and every {@link #regionFieldFor} field rebuild (same snapshot semantics as before). */
    private final RegionMineModel regionMine;
    /** Place-cost sibling of {@link #regionMine} (the field's pillar/climb term), snapshotted once likewise. */
    private final RegionPlaceModel regionPlace;
    private final BotCaps caps;
    /**
     * The live bot's per-pathfind inventory feasibility snapshot (PRD §10 Phase 1b/1c), passed straight to
     * each windowed {@link BlockPathfinder#findPath} so the break/place gates account for the bot's REAL
     * carried tools + blocks. {@code null} when no bot supplied one (the existing single-arg constructor,
     * headless callers), leaving the gates in their historical caps-only mode.
     */
    private final MovementContext.InventoryView inventory;
    /**
     * The splice baseline seeded into every windowed search — the not-yet-applied edits of an EARLIER
     * plan this plan is spliced after (DESIGN-background-pathfinding.md §7). {@code null} for every
     * non-spliced plan (all existing callers): the search pays one compare per pop and is byte-identical.
     */
    private final EditSnapshot baseline;
    /**
     * The background planner pool, or {@code null} = synchronous (every headless caller, and live bots
     * with {@code pathing.async=false} — byte-identical to before). When set, {@link #replanBlock}
     * SUBMITS instead of computing: the current {@link #blockPlan} stays live while the search runs, and
     * the result is adopted at the next settled boundary via {@link #pollPending} — seam-acceptance-gated
     * (DESIGN-background-pathfinding.md §5).
     */
    private final PlanExecutor executor;
    /**
     * Per-journey search-health accumulator (pure observation) the live driver attaches, or {@code null}
     * for every headless / test caller. {@link #resultStatus} feeds it every completed block search
     * (expansions / partial / cap-hit) and the ctor + cascade feed it region-tier flood trips; nothing here
     * ever changes a search / plan / repair decision. Set in the ctor BEFORE the first {@link #replanBlock},
     * so the ctor's own first search is counted.
     */
    private final NavJourneyStats stats;
    /**
     * The async search mailbox — the in-flight/parked/pre-plan-attempt state and its transitions
     * ({@link AsyncWindowSearch}). Always constructed (empty in sync mode, so {@link #cancelPending} is
     * always safe); its state is only ever touched when {@link #executor} is non-null. The adopt/status
     * decisions its drains feed stay HERE, in {@link #pollPending} — the mailbox only classifies.
     */
    private final AsyncWindowSearch async;
    /** Window-target selection ({@link WindowTargeting}) — the plan-immutable context is captured once in
     *  the ctor; {@link #replanBlock} asks it for a fresh choice per replan. */
    private final WindowTargeting targeting;
    private final int minY;
    /** The goal's level-0 region coords (so we can test "goal in window" by index). */
    final int goalRX, goalRY, goalRZ;
    /** The FINAL goal's arrival tolerance — the caller's definition of done (s52). Applied to searches whose
     *  target IS {@link #goalFloor} and to {@link #withinGoalTolerance}; window-portal targets keep the
     *  {@link BlockPathfinder#DEFAULT_GOAL_TOL_XZ default}. */
    private final int goalTolXZ, goalTolY;

    // ---- skeleton + window state ---------------------------------------------------------------------
    /**
     * The level-0 region skeleton the block window drives — sourced from {@link #hier} and <b>swapped</b>
     * whenever the cascade re-derives a fresh L0 segment (HPA-CASCADE.md §5). Non-final for that swap; all the
     * window/commit/target readers below treat it as the current skeleton.
     */
    RegionPathPlan skeleton;
    /**
     * The region-tier nested-skeleton cascade (HPA-CASCADE.md) — the self-refreshing <b>source</b> of
     * {@link #skeleton}: {@link #onBotMoved} steps it first and, on an L0 change, swaps {@link #skeleton} +
     * resets the block window; {@link #repairBlocked} drives its blocked-hop escalation. It owns the per-level
     * blacklists, so PathPlan keeps none of its own.
     */
    final HierarchicalRegionPlan hier;
    /** Index into the skeleton of the window's leading (start) region. */
    int windowStart;
    /** Furthest skeleton index the bot has committed to (HPA-IMPLEMENTATION.md §9, the wiggle anchor). */
    int committedIndex;

    /** Diagnostic ONLY (debug builds): the last (from -> to) pair whose forward slide the wiggle guard
     *  refused, so the per-tick refusal logs once per distinct pair instead of once per tick. Never read by
     *  logic; {@code -1} means "nothing refused yet". */
    private int lastRefusedFrom = -1;
    private int lastRefusedTo = -1;

    // ---- window fragment-label slabs (the commit key's membership source) ----------------------------
    /** Max distinct level-0 regions a window's slabs cover; a window is a handful of steps. */
    private static final int SLAB_MAX = 12;
    /** Cells per level-0 leaf slab (16³), indexed {@code ly<<8 | lz<<4 | lx}. */
    private static final int SLAB_CELLS = 16 * 16 * 16;
    /** Sentinel: the region has a single fragment identity — every cell answers {@code 0}, no flood cut. */
    private static final byte[] SLAB_UNIFORM = new byte[0];
    /** Sentinel: the section was not resident when the slab was cut — membership is unprovable, answer -1. */
    private static final byte[] SLAB_UNKNOWN = new byte[0];
    private final long[] slabKeys = new long[SLAB_MAX];
    private final byte[][] slabVals = new byte[SLAB_MAX][];
    /** Per-slot pooled 4096-cell slabs, reused across cuts so a re-cut allocates nothing after warm-up. */
    private final byte[][] slabPool = new byte[SLAB_MAX][];
    private int slabCount;
    /** The (skeleton identity, window) the slabs were cut for — the self-validating cache key. */
    private RegionPathPlan slabSkeleton;
    private int slabWindowStart = Integer.MIN_VALUE;
    private int slabWindowLast = Integer.MIN_VALUE;

    /** Diagnostic ONLY: whether the last {@link #forwardIndexOf} scan saw a step in the bot's REGION whose
     *  fragment did not match and therefore REFUSED it. Never read by logic — it exists so the slide logs
     *  distinguish "the bot is nowhere on the route" from "the bot is in a region the route visits, but in
     *  the wrong pocket of it" (the latter is the interesting one: a stale skeleton id, or a genuine dip into
     *  an unplanned fragment). */
    private boolean lastMatchRegionOnly;

    // (The nearest-centroid scratch buffers that lived here are gone with the centroid commit key itself —
    //  membership now comes from the window's label slabs above, which need no per-call scratch.)
    /** Reused 2-long scratch for the cascade's blocked-hop repair ({@link #repairBlocked}); no per-repair alloc. */
    private final long[] repairHopScratch = new long[2];

    // ---- active block plan ---------------------------------------------------------------------------
    BlockPathPlan blockPlan;
    /** Whether {@link #blockPlan} is a best-effort PARTIAL (from {@code BlockPathfinder.lastWasPartial()} /
     *  the async result). */
    boolean lastPlanPartial;
    private PathStatus status;
    /** The bot's last reported floor cell. The block-A* start for the next replan — EXCEPT a
     *  seam-SEEDED mid-motion re-search, which starts from the horizon seam instead
     *  (DESIGN-replan-handoff.md §4); every OTHER reader (the forward-slide tolerance test,
     *  {@link WindowTargeting#target}, {@link #cuboidCapBox}, {@link #regionFieldFor}) stays
     *  live-anchored on this cell. */
    BlockPos botFloor;
    /** The bot's current movement mode ({@link BlockPathfinder#MODE_AUTO} = derive from geometry, else the
     *  live pose STANDING/PRONE) — threaded into every windowed search so a replan mid-sprint-swim keeps the
     *  prone state instead of re-deriving STANDING from a buoyancy bob and re-initiating. Updated per tick by
     *  {@link #onBotMoved}. */
    private int startMode = BlockPathfinder.MODE_AUTO;
    // ---- horizon-seam handoff (DESIGN-replan-handoff.md §3/§4/§5) — refreshed by every onBotMoved ----
    /** The live bot's steering seam, for the case-2 adoption's step-0 {@code entryReady} gate (§5);
     *  {@code null} for every headless/no-seam caller (the gate is then skipped — it is vacuously true
     *  for the current movement set anyway). */
    private BotSteering seamBot;
    /** The plan the FOLLOWER is walking (identity guard: {@link #seamIndex} is meaningful only while
     *  {@link #blockPlan} is this same object — an install between handoffs invalidates the walk) and
     *  the §3 walk's seam waypoint index on it; {@code -1} = no walkable incumbent (planless/consumed)
     *  → every launch site seeds from {@link #botFloor} exactly as before. */
    private BlockPathPlan seamPlan;
    private int seamIndex = -1;
    /** The follower's first not-yet-applied step ({@code lastEditedIndex + 1}) — the lower bound of the
     *  §4 SUB-RANGE baseline fold {@code EditSnapshot.fromSteps(plan, first, seam)}. */
    private int seamFirstUnedited;
    /** The follower's live waypoint cursor — with {@link #seamIndex}, the §11 trichotomy's execution
     *  position (before / at / beyond the seam — owner ruling 2026-08-20). */
    private int seamCursor = -1;
    /** Whether the follower has a move IN FLIGHT at {@link #seamCursor} (cursor inside its §11 terminal
     *  view). {@code false} = the degenerate no-move-in-flight regime — planless, consumed, or holding
     *  at a truncated terminal — where adoption consummates immediately on the settled floor. Set by
     *  {@link #onBotMoved} / {@link #pollWhenPlanless}; only read inside {@link #pollPending}. */
    private boolean seamMoveInFlight;

    // ---- §11 armed consummation (DESIGN-replan-handoff.md §11, owner ruling 2026-08-20) --------------
    /** The DEFERRED seam verdict awaiting its move-completion consummation ({@code null} = none): the
     *  in-execution trichotomy ruled ADOPT / FAST_FORWARD / PANIC, the follower truncates the walked
     *  plan at {@link #armedTerminal}, and the verdict consummates at the first settled boundary with
     *  the terminal move COMPLETE ({@code seamCursor > armedTerminal}) — never mid-move. State-based:
     *  disarmed whenever its premise dies (plan swapped/dropped, parked result gone, window target
     *  moved). */
    private AsyncWindowSearch.SeamVerdict armedVerdict;
    /** The follower plan the armed verdict was ruled on (identity guard, the {@link #seamPlan} idiom). */
    private BlockPathPlan armedPlan;
    /** The §11 truncation terminal: the waypoint index of the LAST move the follower executes on
     *  {@link #armedPlan} — the seam move (at-seam ADOPT) or the verdict-time in-flight move (beyond). */
    private int armedTerminal = -1;
    /** The FAST_FORWARD body match of the terminal move's landing on the parked plan ({@code -1} on
     *  ADOPT/PANIC) — handed to {@link AsyncWindowSearch#consummateSeeded} at consummation. */
    private int armedMatched = -1;
    /**
     * The current window's block target + corridor (set by {@link #replanBlock}), exposed via
     * {@link #currentWindowTarget()} / {@link #currentCorridor()} so {@code /bot trace} can re-run the SAME
     * windowed block search the driver runs — with the real HPA*-derived corridor, and thus cuboids,
     * macro-ops, and the goal premium, all active (a raw cornerless trace disables that whole layer).
     */
    private BlockPos windowTargetPos;
    /** The skeleton step {@link #windowTargetPos} corresponds to — used to COMMIT the window the moment the bot
     *  reaches the target's tolerance (a boundary portal can be "reached" 1 block short, leaving the bot in the
     *  previous region so the region-based commit never fires; this is the anti-boundary-bounce). */
    private int windowTargetStep;
    private RegionBound windowCorridor;

    // Plan-relevance terrain-recheck gate (owner 2026-07-24): the current block plan's chunk-version
    // fingerprint. The periodic recheck re-searches only when a chunk the PATH traverses changed — not on
    // any dimension-global grid mutation (frontier chunk builds, a house 50k blocks away). Grow-only scratch,
    // re-baselined by snapshotPlanChunks() after every block-plan install; parallel arrays, no per-tick alloc.
    private long[] planChunks = new long[16];  // distinct chunk keys the block plan traverses
    /** {@link NavGridUpdater#foreignVersion} of each column at snapshot time — the count of changes NOT
     *  announced by the follower. Deliberately not {@code chunkVersion}: the plan's own prescribed edits move
     *  that one, so comparing it re-searched the plan every time it executed itself. */
    private int[] planChunkVers = new int[16];
    private int planChunkCount;                 // used length of the two arrays above
    private int planSnapshotEpoch;              // the dimension epoch at snapshot time (the O(1) early-out)

    /**
     * How {@link WindowTargeting#target} chose the current target — surfaced so the debug chat can explain a movement
     * choice (a target adjusted for caps, or the window extended down a fall). {@code GOAL} = the real goal;
     * {@code PORTAL} = the stored portal centroid as-is; {@code DIG} = a buried crossing passed through RAW
     * (deliberately unsnapped) for a break-capable bot — either a region-committed dig-through step or a lossy
     * centroid that landed in breakable solid; the block A* digs to it under break pricing + its goal tolerance;
     * {@code SNAPPED} = the centroid was unusable (a mid-air cell the bot can't/shouldn't stand at, or buried
     * with a no-break bot, or buried in unbreakable/protected rock) so it was snapped to a real standable cell
     * in the footprint; {@code EXTENDED} = the
     * whole window was air, so the horizon was extended DOWN the skeleton to the first standable landing (a
     * free fall); {@code CENTER} = last-resort region-center projection.
     */
    public enum TargetKind { GOAL, PORTAL, DIG, SNAPPED, EXTENDED, CENTER }
    private TargetKind windowTargetKind = TargetKind.PORTAL;

    /** How the current window target was chosen (debug visibility) — see {@link TargetKind}. */
    public TargetKind windowTargetKind() {
        return windowTargetKind;
    }

    /**
     * Plan a fresh two-tier path from {@code startFloor} to {@code goalFloor}. Plans the coarse region
     * skeleton immediately ({@link RegionPathfinder#plan}) and computes the first window's block plan, so
     * {@link #currentBlockPlan()} is valid right after construction (HPA-IMPLEMENTATION.md §10: the follower
     * keeps working off the returned block plan).
     *
     * @param level      the dimension being navigated
     * @param regionGrid the dimension's cached {@link RegionGrid} (the region-tier read chokepoint)
     * @param startFloor the bot's current floor cell
     * @param goalFloor  the destination floor cell
     * @param caps       the bot's movement capabilities (typically {@link BotCaps#BREAK_PLACE})
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps) {
        this(level, regionGrid, startFloor, goalFloor, caps, null);
    }

    /**
     * As {@link #PathPlan(ServerLevel, RegionGrid, BlockPos, BlockPos, BotCaps)}, additionally carrying the
     * live bot's per-pathfind inventory feasibility snapshot {@code inventory} (PRD §10 Phase 1b/1c), which
     * is threaded into every windowed {@link BlockPathfinder#findPath} so the break/place gates account for
     * the bot's REAL carried tools + blocks. {@code null} = the historical caps-only behaviour.
     *
     * @param inventory the bot's inventory feasibility snapshot, or {@code null} for caps-only gating
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps, MovementContext.InventoryView inventory) {
        this(level, regionGrid, startFloor, goalFloor, caps, inventory, BlockPathfinder.MODE_AUTO);
    }

    /**
     * As above, additionally seeding the bot's initial movement mode ({@code startMode}: STANDING/PRONE, or
     * {@link BlockPathfinder#MODE_AUTO} to derive from start geometry) so the very first window search already
     * matches the bot's pose. Subsequent searches use the per-tick pose from {@link #onBotMoved}.
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps, MovementContext.InventoryView inventory, int startMode) {
        this(level, regionGrid, startFloor, goalFloor, caps, inventory, startMode, null);
    }

    /**
     * As above, additionally seeding every windowed {@link BlockPathfinder#findPath} with a splice
     * {@code baseline} ({@link EditSnapshot}) — the not-yet-applied edits of an EARLIER plan this plan
     * will be spliced after (DESIGN-background-pathfinding.md §7). Threaded into ALL of this plan's
     * window searches (simplest correct form: {@link PathEdits}' bbox reject + latest-wins shadowing
     * make windows far from the baseline pay only the per-pop no-op). {@code null} = every existing
     * caller, byte-identical behaviour.
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps, MovementContext.InventoryView inventory, int startMode,
                    EditSnapshot baseline) {
        this(level, regionGrid, startFloor, goalFloor, caps, inventory, startMode, baseline, null);
    }

    /**
     * As above, additionally handing the plan a background {@code executor}
     * (DESIGN-background-pathfinding.md §5): non-null = every windowed block search is SUBMITTED to the
     * planner pool instead of computed on the tick thread, with adoption at the settled boundary,
     * seam-acceptance-gated. {@code null} = fully synchronous, byte-identical to before (all headless
     * callers, and live bots with {@code pathing.async=false}). The region tier (cascade build, window
     * targets, repairs) stays on the tick thread either way — only {@code BlockPathfinder.findPath}
     * moves off it.
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps, MovementContext.InventoryView inventory, int startMode,
                    EditSnapshot baseline, PlanExecutor executor) {
        this(level, regionGrid, startFloor, goalFloor, caps, inventory, startMode, baseline, executor,
                BlockPathfinder.DEFAULT_GOAL_TOL_XZ, BlockPathfinder.DEFAULT_GOAL_TOL_Y);
    }

    /**
     * As above with an explicit FINAL-goal arrival tolerance ({@code goalTolXZ}/{@code goalTolY}) — the
     * CALLER's definition of "done" (s52, the reached-vs-done decoupling). {@code (1,2)} is the historical
     * "get near the cell" (follow, window slides, mining reach); {@code (0,0)} means the plan is complete
     * only when the bot stands ON the exact goal cell (drop collection — the block tier otherwise ends
     * plans adjacent and the item sits just outside the pickup box). Applies ONLY to searches aimed at the
     * real {@code goalFloor} and to {@link #isComplete()}'s tolerance mirror; intermediate window-portal
     * targets keep the default (a window hop needs no exactness).
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps, MovementContext.InventoryView inventory, int startMode,
                    EditSnapshot baseline, PlanExecutor executor, int goalTolXZ, int goalTolY) {
        this(level, regionGrid, startFloor, goalFloor, caps, inventory, startMode, baseline, executor,
                goalTolXZ, goalTolY, null);
    }

    /**
     * As above, additionally attaching a per-journey search-health accumulator {@code stats}
     * ({@link NavJourneyStats}) — a pure-observation sink the live {@link com.orebit.mod.BotNavigator driver}
     * passes so EVERY windowed block search (the ctor's first one included) and every region-tier flood is
     * aggregated for {@code /bot stats} + the journey-end log line. {@code null} = every headless / test
     * caller (no telemetry, byte-identical behaviour).
     */
    public PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor,
                    BotCaps caps, MovementContext.InventoryView inventory, int startMode,
                    EditSnapshot baseline, PlanExecutor executor, int goalTolXZ, int goalTolY,
                    NavJourneyStats stats) {
        this.stats = stats;
        this.goalTolXZ = goalTolXZ;
        this.goalTolY = goalTolY;
        this.baseline = baseline;
        this.executor = executor;
        this.level = level;
        this.regionGrid = regionGrid;
        this.goalFloor = goalFloor;
        this.caps = caps;
        this.inventory = inventory;
        this.minY = regionGrid.minY();
        this.botFloor = startFloor;
        this.startMode = startMode;

        this.goalRX = RegionAddress.regionX(goalFloor.getX(), 0);
        this.goalRY = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        this.goalRZ = RegionAddress.regionZ(goalFloor.getZ(), 0);

        // Collaborators (same-package, replan-cadence only — one construction per plan, cold):
        // window-target selection with the plan-immutable context, and the async search mailbox
        // (empty/no-op in sync mode, so cancelPending is always safe).
        this.targeting = new WindowTargeting(level, regionGrid, minY, caps, goalFloor, goalRX, goalRY, goalRZ);
        this.async = new AsyncWindowSearch(executor);

        // Region tier: the nested-skeleton cascade (HPA-CASCADE.md) re-derives its L0 segment as the bot moves
        // and owns its per-level blacklists; PathPlan just drives the L0 segment it hands back. The region dig
        // cost is made tool-aware from the SAME inventory snapshot the block tier uses (PERF-DESIGN region §5).
        RegionMineModel mine = RegionMineModel.from(inventory != null ? inventory.mining() : null);
        this.regionMine = mine;
        this.regionPlace = RegionPlaceModel.from(inventory);

        // Region-informed block heuristic: a cost-to-goal field over the fragment graph feeding BlockPathfinder's
        // Relaxer a topology-aware lower bound, so the block search follows the skeleton and DIGS to a buried
        // target (via the goal dig-flood multi-source seed) instead of flooding / walking around. NOT built here:
        // it is built lazily per WINDOW TARGET by regionFieldFor() at each search-launch site — a plan-lifetime
        // final-goal-rooted field mis-guided every window search toward the wrong attractor (see the regionField
        // javadoc). The ctor's replanBlock() below performs the first build on the tick thread, so the first
        // window search already runs with a field.

        // The inventory snapshot rides along so the cascade's #5 crossing-memory sig is the EFFECTIVE sig of
        // the searches this plan runs (and so null-inv plans — headless/trace/tests — never record, §3.3).
        this.hier = HierarchicalRegionPlan.build(regionGrid, minY, startFloor, goalFloor, caps, mine, inventory);
        this.skeleton = hier.l0Skeleton();
        this.windowStart = 0;
        this.committedIndex = 0;
        // Telemetry: the initial region plan tripped the cap-safe flood guard (a region-tier area problem,
        // not a proven dead-end). Read the per-thread flag the build's last region search set — pure
        // observation (RegionPathfinder.lastWasFlood is set on this same tick thread by the build above).
        if (stats != null && RegionPathfinder.lastWasFlood()) stats.onRegionFlood();

        if (skeleton == null || skeleton.isEmpty()) {
            // No coarse route at all (no built ground at the start region). Leave the block plan null and the
            // status FAILED so AllyBotEntity gives up visibly (HOLD + a chat line — pathological failures
            // stay visible, HPA-IMPLEMENTATION.md §10).
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            // A NO-ROUTE SKELETON *IS* THE DEGENERATE BOXED-IN CASE (owner ruling 2026-08-25), so prove it
            // rather than discard it. Region adjacency is fragment-to-fragment: a sealed pocket is a fragment
            // with NO EDGES, so the region A* legitimately returns nothing, and the goal-rooted flood over
            // that same fragment closes after ZERO hops. This is not a case the prover struggles with -- it
            // is the one it is most certain about, reached by the cheapest possible path.
            //
            // It used to be reached the long way round. Before ce31f5a (2026-08-14) the region search could
            // detour through UNBUILT space, which is optimistically connected to everything including a
            // sealed fragment in a built neighbour, so a sealed goal still produced a bogus non-empty
            // skeleton -- and that false skeleton was the only thing keeping control flowing past this
            // `return` and into the honest prover below. ce31f5a correctly stopped the speculation and, in
            // doing so, removed the prover's accidental precondition: from 2026-08-14 to 2026-08-25 every
            // sealed goal gave up REACTIVELY (boxedInProven=false), which is exactly what BoxedInCourse's
            // `tomb` scenario had been reporting, unread, for eleven days. Both commits are individually
            // right; the interaction was the defect. Bisected d78f1fd(PASS) -> 5421cdb(FAIL), one commit.
            //
            // Ignoring the return value is deliberate: this plan has already FAILED either way. What the
            // call adds is the REASON -- boxedInProven turns an unexplained give-up into "walled off", and
            // separates it from this branch's other meaning, "no built ground at the start region".
            maybeProactiveBoxedIn();
            if (Debug.VERBOSE) {
                OrebitCommon.LOGGER.info("[Orebit] plan: NO SKELETON (skeleton={}) start={} goal={}"
                        + " goalRegionBuilt={} boxedInProven={}",
                        skeleton == null ? "null" : "empty", startFloor, goalFloor,
                        regionGrid.fragmentRecord(0, goalRX, goalRY, goalRZ) != null, this.boxedInProven);
            }
            return;
        }
        // #4 PROACTIVE boxed-in check (DESIGN-boxed-in-reachability §4 — the proactive rework of Increment 1's
        // reactive give-up trigger): before committing the optimistic skeleton, prove whether the JOURNEY GOAL
        // is walled off under these caps. A born-boxed-in goal gives up honestly NOW (no optimistic skeleton to
        // wander) — the reactive give-up would never fire here, because optimistic-unbuilt terrain between the
        // bot and the sealed goal keeps the forward block search returning a partial forever.
        if (maybeProactiveBoxedIn()) {
            return; // FAILED + boxedInProven set; no optimistic skeleton
        }
        dumpSkeleton("initial");
        replanBlock();
    }

    /**
     * Region-tier diagnostics for ONE skeleton. Called from EVERY site that (re)assigns {@link #skeleton}.
     *
     * <p><b>Why it is a method</b> (2026-08-12). The dump used to be an inline {@code Debug.ENABLED} block at
     * the INITIAL build only, so the two cascade re-derive sites — the extension shift and the SWAPPED
     * re-plan — replaced the L0 skeleton silently. That was not a small gap: a full flagship run logged ONE
     * skeleton while its window swaps referenced steps up to {@code S17}, which cannot exist on the 9-step
     * skeleton that was dumped. Every skeleton after the first was invisible, including whichever one chose
     * the route the bot actually took.
     *
     * <p>Two channels, deliberately gated differently:
     * <ul>
     *   <li>the full multi-level dump stays behind {@link Debug#ENABLED} — it is tens of lines per call;</li>
     *   <li>{@link SkeletonDump#unbuiltSummary} logs UNCONDITIONALLY, but only when the count is non-zero.
     *       Planning through terrain the nav layer has never seen is rare, high-signal, and the thing that
     *       silently bends a route to the surface preference, so it must be visible in an ordinary run — the
     *       headless autotest does not set {@code -BotDebug} by default. A fully-built plan logs nothing.</li>
     * </ul>
     * Cold path: one call per skeleton (re)derive, never per search or per tick.
     */
    private void dumpSkeleton(String why) {
        final String unbuilt = SkeletonDump.unbuiltSummary(this);
        if (unbuilt != null) {
            OrebitCommon.LOGGER.info("[Orebit] UNBUILT-IN-PLAN ({}) {}", why, unbuilt);
        }
        if (Debug.ENABLED) {
            // HPA-tier visibility: dump the whole region skeleton + per-step portal/center built-standable probe
            // (a [SOLID/buried] portal is the §6 buried-target bug). Counterpart to the block tier's /bot trace.
            OrebitCommon.LOGGER.info("[Orebit] [skeleton:{}] {}", why, describeSkeleton());
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Public surface (HPA-IMPLEMENTATION.md §9 / §10)
    // ---------------------------------------------------------------------------------------------------

    /** The active windowed block path the follower walks; {@code null} when BLOCKED/FAILED. */
    public BlockPathPlan currentBlockPlan() {
        return blockPlan;
    }

    /** The floor cell {@link #currentBlockPlan()} was searched FROM — the plan's implicit step −1, its
     *  TRUE frame origin (== the settled/live floor on an ordinary install, the horizon seam on a
     *  seam-seeded one). Only meaningful while {@code currentBlockPlan() != null} — see
     *  {@code blockPlanStart}'s field javadoc; the follower's install seed anchors on this rather than
     *  the adoption-time bot floor (DESIGN-replan-handoff.md §5/R3). */
    public BlockPos blockPlanStart() {
        return blockPlanStart;
    }

    /** The waypoint index of {@link #currentBlockPlan()} the bot's floor matched when the plan installed
     *  via a FAST_FORWARD seam adoption, {@code -1} on every other install — see
     *  {@code adoptedMatchedIndex}'s field javadoc. The follower's install seed consumes it so an
     *  already-executed prefix never re-runs. */
    public int adoptedMatchedIndex() {
        return adoptedMatchedIndex;
    }

    /** The driver's current lifecycle state. */
    public PathStatus status() {
        return status;
    }

    /**
     * The current window's block-A* target — the real {@code goalFloor} when the goal's region is within the
     * window, else the far region's standable centre (HPA-IMPLEMENTATION.md §9). Exposed so {@code /bot trace}
     * can re-run the SAME windowed block search the driver ran (with the corridor → cuboids, macro-ops, and
     * the goal premium active). {@code null} when no skeleton was produced (no built ground at the start).
     */
    public BlockPos currentWindowTarget() {
        return windowTargetPos;
    }

    /**
     * The current window's corridor box (the {@link RegionBound} the windowed block-A* is confined to).
     * Exposed for {@code /bot trace} alongside {@link #currentWindowTarget()}; {@code null} when no skeleton
     * was produced.
     */
    public RegionBound currentCorridor() {
        return windowCorridor;
    }

    /**
     * The coarse region skeleton this plan is driving ({@code null} when none was produced — no built ground at
     * the start). Exposed for the debug skeleton overlay ({@link PathDebugRenderer#renderSkeleton}) so the macro
     * region/fragment route + portal cells can be drawn alongside the local block path; read-only.
     */
    public RegionPathPlan skeletonPlan() {
        return skeleton;
    }

    /** The window's leading (start) skeleton index (debug overlay). */
    public int windowStartIndex() {
        return windowStart;
    }

    /** The window's far (last) skeleton index, or {@code -1} when no skeleton was produced (debug overlay). */
    public int windowLastIndex() {
        return skeleton == null ? -1 : windowLast();
    }

    /** The furthest committed skeleton index — the wiggle anchor (debug overlay). */
    public int committedStepIndex() {
        return committedIndex;
    }

    /** The skeleton step the current window is heading toward ({@link WindowTargeting#target} aims at it). */
    public int windowTargetStepIndex() {
        return windowTargetStep;
    }

    /**
     * A multi-line dump of the coarse region skeleton — the HPA-tier counterpart to the block tier's
     * {@code /bot trace}. Per step it prints the region coords, committed fragment, region {@code kind}, the
     * <b>portal cell</b> it is entered through, and the geometric center — each annotated with a built/standable
     * <b>probe</b> ({@code [stand]} = a real floor, {@code [air-no-floor]} = passable but nothing to stand on,
     * {@code [SOLID/buried]} = inside rock, {@code [unbuilt]} = unloaded). A {@code [SOLID/buried]} portal is the
     * §6 buried-target bug made legible. The {@code *TARGET} marker flags the step {@link WindowTargeting#target}
     * aims at. Cold path (builds a fresh {@link NavGridView}); call only on replan / trace under
     * {@link Debug#ENABLED}. Formatting lives in {@link SkeletonDump}.
     */
    public String describeSkeleton() {
        return SkeletonDump.describeSkeleton(this);
    }

    /** {@code true} once the bot's floor is within the block tier's goal tolerance of the real goal. */
    public boolean isComplete() {
        return status == PathStatus.COMPLETE;
    }

    /**
     * Per-tick hook (HPA-IMPLEMENTATION.md §9/§10): the follower passes the bot's current floor cell after
     * moving. Advances the sliding window when the bot <b>commits</b> into a forward skeleton region (the
     * wiggle rule), replans the window's block plan on commit / when BLOCKED, and flips to
     * {@link PathStatus#COMPLETE} when the real goal tolerance is met. A transient dip back to an earlier
     * region neither retreats {@code committedIndex} nor replans.
     */
    public void onBotMoved(BlockPos botFloor, int startMode, boolean fluidAnchor) {
        onBotMoved(botFloor, startMode, fluidAnchor, null, null, -1, 0, -1, false, true);
    }

    /**
     * As above, additionally carrying the follower's horizon-seam handoff (DESIGN-replan-handoff.md
     * §3/§4/§5): {@code bot} is the live steering seam (the case-2 adoption's step-0 {@code entryReady}
     * gate), {@code followerPlan}/{@code seamIndex} name the §3 walk's seam waypoint on the plan the
     * follower is walking ({@code -1} = no walkable incumbent — planless or consumed — so every launch
     * site seeds from {@code botFloor} exactly as before), {@code firstUneditedStep} is the follower's
     * first not-yet-applied step (the §4 sub-range fold's lower bound), {@code followerCursor} is
     * the live waypoint cursor, and {@code moveInFlight} says whether the move at that cursor is
     * actually executing (cursor inside the follower's §11 terminal view) — together the §11
     * trichotomy's execution position (owner ruling 2026-08-20). {@code restForLaunch} is the
     * §10 rest gate ({@code BotSteering.atRest}): it gates ONLY the blocked-null (S3) relaunch below —
     * the one launch in this method anchored at the live {@code botFloor} — deferring it until the bot's
     * carry has stopped (the trigger, a null plan, persists; the follower re-anchors its settled floor
     * at rest, so the deferred launch always re-fires). The three-arg form is the headless/no-seam entry
     * and behaves byte-identically to before (rest {@code true}).
     */
    public void onBotMoved(BlockPos botFloor, int startMode, boolean fluidAnchor, BotSteering bot,
                           BlockPathPlan followerPlan, int seamIndex, int firstUneditedStep,
                           int followerCursor, boolean moveInFlight, boolean restForLaunch) {
        this.seamBot = bot;
        this.seamPlan = followerPlan;
        this.seamIndex = seamIndex;
        this.seamFirstUnedited = firstUneditedStep;
        if (Debug.ENABLED && armedVerdict != null && followerCursor != this.seamCursor) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] seamCursor {}→{} (onBotMoved) armedTerminal={} inFlight={} — {}",
                    this.seamCursor, followerCursor, armedTerminal, moveInFlight,
                    followerCursor > armedTerminal ? "PAST the armed terminal: consummation will fire"
                                                   : "still at/behind the terminal: hold");
        }
        this.seamCursor = followerCursor;
        this.seamMoveInFlight = moveInFlight;
        this.botFloor = botFloor;
        this.startMode = startMode; // the bot's live pose, used by the next windowed search (keeps PRONE while swimming)

        if (status == PathStatus.COMPLETE || status == PathStatus.FAILED || skeleton == null) {
            return;
        }

        // Async result drain (DESIGN-background-pathfinding.md §5): the caller only invokes onBotMoved at
        // a settled boundary, so adopting here IS the boundary-gated adoption the design requires. No-op
        // when sync or nothing is in flight (one null compare). A parked SEAM-SEEDED result also pumps in
        // SYNC mode (DESIGN-replan-handoff.md §5 — the sync park is what closes the §1 wedge), which the
        // seededParked() disjunct covers; a sync plan with nothing parked still never enters.
        // (+ §11: an ARMED consummation must keep pumping even with the parked slot EMPTY — a sync
        // deferred PANIC drops the slot at verdict time, and without this disjunct its consummation
        // would never run: the bot would hold at its truncated terminal forever.)
        if (executor != null || async.seededParked() || armedVerdict != null) {
            pollPending(botFloor, fluidAnchor);
        }

        // Goal tolerance check (the block tier reaches the goal within 1 horizontally, 2 vertically —
        // mirror that here so the driver completes exactly when the follower's block plan would).
        if (withinGoalTolerance(botFloor)) {
            status = PathStatus.COMPLETE;
            return;
        }

        // #4 PROACTIVE boxed-in check (DESIGN-boxed-in-reachability §4): as the bot approaches and the goal's
        // sealing perimeter builds in, the goal may become PROVABLY walled off even though the forward block
        // search still returns an optimistic partial through unbuilt terrain (so no reactive give-up ever
        // fires). Gated on the goal-neighbourhood build signal, so a static world pays one O(9) version read
        // here, not a flood. A proven boxed-in gives up now (honest FAILED), no further wandering.
        if (maybeProactiveBoxedIn()) {
            return;
        }

        // Cascade step (HPA-CASCADE.md §5): advance the per-level commits and re-derive only the suffix the bot
        // exited. When the L0 segment changed, swap it in and reset the block window from the bot's region;
        // otherwise fall through to the block-window slide over the unchanged (or EXTENDED — rolling) skeleton.
        if (stepCascade()) {
            return;
        }

        // INV-4, driver form (DESIGN-rolling-skeleton.md §9, §13-A): on a healthy non-final, non-degraded L0
        // skeleton, windowLast() < size-1 — the window target never pins at a tail. Debug-gated observation,
        // never a throw: a violation logs and the retained exhausted-on-occupancy machinery degrades per
        // INV-5. The cascade-clamp conjunct (the cascade's own committed cursor also clamped) keeps the
        // transient forward-slide-ahead-of-occupancy settle from false-alarming; l0RollingExempt() carries
        // the ratified exemptions (degraded interim, topLevel 0, parent clamped at ITS tail — increment B).
        if (Debug.ENABLED && !skeleton.reachedGoalRegion() && windowLast() >= skeleton.size() - 1
                && hier.committedAt(0) + HierarchicalRegionPlan.WINDOW_CELLS > skeleton.size() - 1
                && !hier.l0RollingExempt()) {
            OrebitCommon.LOGGER.warn(
                    "[Orebit] INV-4 violated: block window [{}..{}] clamped at the tail of a non-final, "
                            + "non-degraded L0 skeleton (size {}, cascade committed {})",
                    windowStart, windowLast(), skeleton.size(), hier.committedAt(0));
        }

        // (Window commit-on-approach lives in replanBlock's FORWARD-SLIDE now: a target the bot satisfies
        // within the block tier's own ±1/±2 goal tolerance is committed+slid at target-selection time —
        // the search's own arrival radius, no separate spatial hysteresis constant. s52.)

        // Fragment model (HPA-FRAGMENTS.md §S4): the bot's current skeleton step is matched on
        // (region, fragment), so resolve which fragment of its region the bot occupies (alloc-free). Center-
        // model plans (flag off / coarse branch) skip this entirely and behave exactly as before.
        final boolean fragModel = skeleton.isFragmentModel();
        final int botFrag = fragModel ? windowFragmentAt(botFloor) : 0;

        final int last = windowLast();
        final int curRegion = forwardIndexOf(botFloor, botFrag, committedIndex, last);

        if (curRegion > committedIndex) {
            // An intra-region MINE edge (the new step shares the committed step's REGION but is a different
            // fragment — a dig between two tunnels of one region) commits the moment the bot reaches the new
            // fragment: there is no boundary thrash within a single-region dig, so the inter-region wiggle
            // hysteresis does not apply. Inter-region steps still gate on the wiggle/commit test exactly as the
            // center model does. (For a center-model plan sameRegionDig is always false ⇒ pure committed().)
            final boolean sameRegionDig = fragModel
                    && skeleton.rx(curRegion) == skeleton.rx(committedIndex)
                    && skeleton.ry(curRegion) == skeleton.ry(committedIndex)
                    && skeleton.rz(curRegion) == skeleton.rz(committedIndex);
            final boolean wiggleOk = sameRegionDig || committed(curRegion);
            if (wiggleOk && !lastPlanPartial) {
                // A real forward step: the path no longer revisits any region in [committedIndex, curRegion).
                // FOLLOW-TO-TERMINUS (#5 partial-invalidation): gated on !lastPlanPartial. While the current
                // window plan is a best-effort PARTIAL (it did NOT reach its target), do not slide/re-search
                // mid-partial — let the bot follow the partial all the way to its terminus and re-evaluate
                // there (via the consumption refresh). Sliding here re-searches from an intermediate cell each
                // boundary crossing, which produces the oscillation that stops the bot ever settling at the
                // terminal dead-end where the region crossing would be blamed. A COMPLETE window plan still
                // commits and slides exactly as before.
                if (Debug.ENABLED) {
                    final boolean goalInWindow = skeleton.reachedGoalRegion() && last == skeleton.size() - 1;
                    OrebitCommon.LOGGER.info(
                            "[Orebit] block re-search: site=forward-slide commit {}->{} goalInWindow={} "
                                    + "planWasPartial={} sameRegionDig={} regionOnlyRefused={}bot=({},{},{}) "
                                    + "botKey={} span={}",
                            committedIndex, curRegion, goalInWindow, lastPlanPartial, sameRegionDig,
                            lastMatchRegionOnly,
                            botFloor.getX(), botFloor.getY(), botFloor.getZ(),
                            SkeletonDump.botKey(minY, botFloor, botFrag),
                            SkeletonDump.stepSpan(this, committedIndex, curRegion));
                }
                committedIndex = curRegion;
                windowStart = curRegion;
                replanBlock();
                return;
            }
            // A slide the guard REFUSED. Logged once per distinct (from -> to) pair, because the refusal
            // repeats every tick while it holds and an unbounded line would drown the run. The pair is the
            // whole diagnostic: it says the cursor WANTED to move and what stopped it, which is the other
            // half of the multi-step-slide question (a 4->7 slide is only mysterious if 4->5 and 4->6 were
            // never offered — this line proves whether they were).
            if (Debug.ENABLED && (curRegion != lastRefusedTo || committedIndex != lastRefusedFrom)) {
                lastRefusedFrom = committedIndex;
                lastRefusedTo = curRegion;
                OrebitCommon.LOGGER.info(
                        "[Orebit] forward-slide REFUSED {}->{} wiggleOk={} sameRegionDig={} regionOnlyRefused={}"
                                + "planWasPartial={} bot=({},{},{}) botKey={} span={}",
                        committedIndex, curRegion, wiggleOk, sameRegionDig, lastMatchRegionOnly,
                        lastPlanPartial,
                        botFloor.getX(), botFloor.getY(), botFloor.getZ(),
                        SkeletonDump.botKey(minY, botFloor, botFrag),
                        SkeletonDump.stepSpan(this, committedIndex, curRegion));
            }
        }
        // (No debounce fallback: its inconclusive case — a null/empty block plan at commit time — no longer
        // exists. Empty plans are never produced (the forward-slide commits satisfied targets pre-search) and
        // a null plan is BLOCKED, which the online repair owns. s52: COMMIT_TICKS deleted.)

        // Terrain changed under us (BLOCKED) — recompute the current window's block plan from where we are.
        // §10 REST GATE (restForLaunch): this is the sync-and-async planless relaunch site — it seeds AND
        // (sync) installs anchored at the live botFloor, so it defers until the bot is at rest. This is
        // also PANIC's deferred resubmit (§10: PANIC nulls only; the follower's WAIT coasts the bot to
        // rest, rest re-anchors the settled floor, and this site fires from the true rest cell).
        if (blockPlan == null && restForLaunch) {
            if (Debug.ENABLED) {
                OrebitCommon.LOGGER.info("[Orebit] block re-search: site=blocked-null bot=({},{},{})",
                        botFloor.getX(), botFloor.getY(), botFloor.getZ());
            }
            replanBlock(true); // blocked-null: the live plan is gone — plan-invalid evidence
        }
    }

    /**
     * One step of the region-tier cascade (HPA-CASCADE.md §5; DESIGN-rolling-skeleton.md §4.3): advance the
     * per-level commits, extend/re-derive as the three-way verdict directs. Returns {@code true} (and
     * {@link #onBotMoved} stops) only on a {@code SWAPPED} re-derive — we swap the skeleton in, reset the
     * block window from the bot's region, and replan the block path; or when the cascade ran out of route
     * (→ FAILED). {@code UNCHANGED} and {@code EXTENDED} both return {@code false} so the caller proceeds
     * with the normal block-window slide: an EXTENDED splice keeps the live block plan (its waypoints and
     * target cell are unchanged — the plan is still exactly as valid) and merely shifts every index-valued
     * cursor by the head-drop, so the block replan for the crossing fires through <b>T2 itself</b> — the same
     * wiggle-gated commit-slide as today, now over an un-clamped window. Rolling adds NO replan trigger.
     */
    private boolean stepCascade() {
        // onRoute: the block plan vouches for the bot's position (it stands at/near a plan waypoint), so an
        // off-window excursion (a fall-lineup clip into an adjacent region) is intentional, not a deviation —
        // the cascade only re-derives for a bot that is off-window AND off its plan (s52; replaced the old
        // BOUNDARY_CLIP_CHEB spatial tolerance with asking the plan). windowStart is the §4.2/D3 head-drop
        // bound — a bound, not a behavior; the cascade still never reads driver state.
        final HierarchicalRegionPlan.Verdict verdict =
                hier.onBotMoved(botFloor, botOnBlockPlan(botFloor), windowStart);
        if (verdict == HierarchicalRegionPlan.Verdict.UNCHANGED) {
            return false; // still within every level's window — slide the block window over the unchanged L0
        }
        if (verdict == HierarchicalRegionPlan.Verdict.EXTENDED) {
            // EXTENDED(shift) (DESIGN-rolling-skeleton.md §4.3/§4.4): the L0 skeleton was SPLICED — head
            // dropped by `shift`, tail appended, prefix content identical (INV-1). Swap the skeleton
            // REFERENCE and shift every index-valued live state so each keeps addressing the SAME skeleton
            // step (INV-3: all blame/blacklist rows are content-keyed — verified — so only these cursors and
            // the BLOCKED snapshot are index-valued). KEEP the live block plan; NO resetWindow() here — that
            // call stays on the true swap paths (cascade SWAPPED, repair). The max(0,·) clamps are defensive
            // only: the drop is bounded by windowStart, so live cursors never go negative; a stale BLOCKED
            // snapshot from before the drop is already meaningless and clamps harmlessly.
            this.skeleton = hier.l0Skeleton();
            final int shift = hier.lastExtensionShift();
            if (shift > 0) {
                // A head-drop RENUMBERS every skeleton index. Without this line, cursor values logged on
                // either side of an extension are silently measured against different skeletons — which is
                // how "commit 0->1, 1->2 ... commit 0->3" reads as a cursor resetting for no reason. Log the
                // shift with the before/after cursors so index-valued diagnostics stay comparable.
                if (Debug.ENABLED) {
                    OrebitCommon.LOGGER.info(
                            "[Orebit] skeleton EXTENDED: headDrop={} committed {}->{} windowStart {}->{} "
                                    + "targetStep {}->{} newSize={}",
                            shift, committedIndex, Math.max(0, committedIndex - shift),
                            windowStart, Math.max(0, windowStart - shift),
                            windowTargetStep, Math.max(0, windowTargetStep - shift),
                            skeleton == null ? -1 : skeleton.size());
                }
                windowStart = Math.max(0, windowStart - shift);
                committedIndex = Math.max(0, committedIndex - shift);
                windowTargetStep = Math.max(0, windowTargetStep - shift);
                blockedWindowStart = Math.max(0, blockedWindowStart - shift);
                blockedTargetStep = Math.max(0, blockedTargetStep - shift);
                lastRefusedFrom = -1; // refusal dedup keys on indices; they just moved
                lastRefusedTo = -1;
            }
            dumpSkeleton("extended");
            return false; // fall through: T2's commit logic replans over the now-un-clamped window
        }
        // SWAPPED — a genuine re-derive (deviation, exhaustion, flood-widen, top-collapse) or FAILED.
        // Telemetry: a re-derivation ran (L0 changed) — count a flood if its region search tripped the guard.
        if (stats != null && RegionPathfinder.lastWasFlood()) stats.onRegionFlood();
        this.skeleton = hier.l0Skeleton();
        dumpSkeleton("swapped");
        if (skeleton == null || skeleton.isEmpty()) {
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            return true;
        }
        resetWindow();
        replanBlock();
        return true;
    }

    /**
     * Whether the bot's floor cell sits on (within one block of) a waypoint of the active block plan — the
     * "is the bot following its plan" vouch passed to the cascade's deviation test. The follower settles
     * exactly on waypoint floors, so while it executes the plan this is a hit by construction; the ±1 slack
     * absorbs seam-adoption drift. Alloc-free scan of ≤ a window of waypoints, settle cadence only.
     */
    /**
     * Re-baseline the plan-relevance fingerprint (owner 2026-07-24): record the current dimension epoch and,
     * for each distinct chunk column the block plan's waypoints traverse, its {@link NavGridUpdater#chunkVersion}.
     * {@link #planImpacted} compares against this. Called after every block-plan install (sync + async adoption).
     * Cold (once per search); the dedup is a short linear scan because a path stays in a chunk for many
     * consecutive waypoints, so the distinct-chunk count is small (a handful per window).
     */
    private void snapshotPlanChunks() {
        planSnapshotEpoch = NavGridUpdater.editEpoch(level);
        planChunkCount = 0;
        final BlockPathPlan bp = blockPlan;
        if (bp == null || bp.isEmpty()) {
            return;
        }
        final int n = bp.size();
        for (int i = 0; i < n; i++) {
            final BlockPos wp = bp.waypoint(i);
            final long ck = NavStore.key(wp.getX() >> 4, wp.getZ() >> 4);
            boolean seen = false;
            for (int j = 0; j < planChunkCount; j++) {
                if (planChunks[j] == ck) { seen = true; break; }
            }
            if (seen) {
                continue;
            }
            if (planChunkCount == planChunks.length) {
                planChunks = java.util.Arrays.copyOf(planChunks, planChunkCount * 2);
                planChunkVers = java.util.Arrays.copyOf(planChunkVers, planChunkCount * 2);
            }
            planChunks[planChunkCount] = ck;
            planChunkVers[planChunkCount] = NavGridUpdater.foreignVersion(level, ck);
            planChunkCount++;
        }
    }

    /**
     * Whether a grid change since the last search impacted a chunk THIS plan traverses — the terrain-recheck
     * gate (owner 2026-07-24, replacing the dimension-global epoch compare). O(1) common case: if the
     * dimension epoch is unchanged nothing changed anywhere. Only on a change does it scan the plan's few
     * chunks. A {@code null} plan is always "impacted" (there is nothing to keep following). Server thread.
     */
    public boolean planImpacted() {
        if (blockPlan == null) {
            impactChunk = Long.MIN_VALUE;
            return true;
        }
        if (NavGridUpdater.editEpoch(level) == planSnapshotEpoch) {
            return false; // nothing changed anywhere in the dimension since this plan's search
        }
        for (int i = 0; i < planChunkCount; i++) {
            // FOREIGN changes only. Our own prescribed break/place was announced to the grid before it
            // landed (expectOwnEdit → NavGridUpdater.expectChange), so it never moved this counter — the
            // plan executing itself is not the plan going stale.
            if (NavGridUpdater.foreignVersion(level, planChunks[i]) != planChunkVers[i]) {
                impactChunk = planChunks[i];   // diagnostic: WHICH chunk, for impactForensic()
                return true; // a chunk the path traverses changed → the plan may be stale, re-search
            }
        }
        return false; // changes happened, but not in a chunk this plan traverses — keep following
    }

    /**
     * The bot is ABOUT to edit {@code (x,y,z)} — if THIS plan prescribed it, announce the exact mutation to
     * the grid ({@link NavGridUpdater#expectChange}) so {@link #planImpacted} does not read the plan's own
     * execution as the world diverging from it. {@code toAir} is the mutation's direction: a break makes the
     * cell air, a place fills it.
     *
     * <p><b>Why</b> (measured 2026-08-06). The follower's edits go through the same {@code onBlockChanged}
     * seam as any other block change, so every break the plan ordered bumped the chunk version, tripped
     * {@code planImpacted}, and re-searched the plan that ordered it. In one flagship run <b>16 of 27</b>
     * re-searches were the bot invalidating its own plan — 11 {@code stone→air}, 3 {@code air→cobblestone},
     * 2 {@code diorite→air}. That is pure waste (the post-edit world matches the plan's assumption BETTER
     * than the pre-edit world did) and it is not merely wasteful: a re-search rebuilds the plan mid-move and
     * resets the waypoint cursor, which is the mechanism behind the 2026-08-06 Descend that failed its
     * validity envelope by 0.001 blocks while carrying a DiagonalParkour's momentum.
     *
     * <p><b>Mutation identity, not a budget</b> (owner ruling 2026-08-06). The first cut let a column tolerate
     * N changes, N being the number of edits executed there. It answered the wrong question — "how many things
     * changed here" rather than "did we expect THIS mutation" — and it is sound only while an executed edit
     * bumps the version exactly once, failing silently in the DANGEROUS direction if one ever bumps zero times
     * (a grid-invisible change, {@code NavGridEpochTest}): the unspent credit then absorbs the next foreign
     * change. Announcing the exact cell and direction removes the question. A stone vanishing from the cell we
     * were mining is our plan executing; a vine appearing in that same cell is not, and the grid can now tell
     * them apart on its own.
     *
     * <p><b>Still verified against the plan's own edit set.</b> The cell must appear in this plan's {@link
     * StepEdits}: {@code /bot mine} and gather drive the same actuators, and forgiving one of those would
     * blind the gate for a change no plan predicted.
     *
     * <p>Cascading changes are NOT forgiven — gravel falling because we broke its support arrives after the
     * one-shot slot is spent, at a different cell, and correctly counts as foreign, because the plan never
     * modelled it — with exactly ONE carve-out (DESIGN-fluid-flow-prediction.md §8.3): a break this plan
     * folded as {@code PathEdits.BROKEN_WATER}/{@code BROKEN_LAVA} <i>predicted</i> the fluid that later
     * flows into the opened cell, so the expectation armed for it is the two-phase SET {air, that fluid}
     * ({@link NavGridUpdater#expectFloodedBreak}), not the single to-air direction. The immediate air is
     * phase 1; the fluid arriving on vanilla's spread schedule (≥5 ticks for water, ≥30 for overworld lava —
     * §1.4) consumes a state-based pending-flood residual instead of bumping the foreign version; and a
     * WATERLOGGED break — where vanilla leaves the water block with no air phase at all — is forgiven
     * outright (previously a false foreign bump from the bot's own prescribed break). Any OTHER state
     * observed at the cell stays foreign. The fold kind is recovered from this plan's own {@link StepEdits}
     * ({@link #prescribedBreakKind}), so the carve-out is exactly as wide as what the plan actually
     * predicted — a break folded dry still arms the plain direction, and its flood (a wrong dry verdict)
     * still invalidates and replans, which is §8's recoverable errs-dry direction.
     */
    public void expectOwnEdit(int x, int y, int z, boolean toAir) {
        final BlockPathPlan bp = blockPlan;
        if (bp == null || !prescribesEdit(bp, x, y, z)) {
            return;   // not ours to forgive — let the change count as divergence
        }
        if (toAir) {
            // A fluid-folded break arms the widened TWO-PHASE expectation {air, fluid} instead of the plain
            // to-air direction — the §8.3 carve-out documented above. Gated on toAir so a place (or a clutch
            // reclaim's fill direction) at a coordinate that also carries a folded break never mis-arms.
            final int kind = prescribedBreakKind(bp, x, y, z);
            if (kind == PathEdits.BROKEN_WATER) {
                NavGridUpdater.expectFloodedBreak(level, new BlockPos(x, y, z), NavGridUpdater.FLOOD_WATER);
                return;
            }
            if (kind == PathEdits.BROKEN_LAVA) {
                NavGridUpdater.expectFloodedBreak(level, new BlockPos(x, y, z), NavGridUpdater.FLOOD_LAVA);
                return;
            }
        }
        NavGridUpdater.expectChange(level, new BlockPos(x, y, z), toAir);
    }

    /**
     * The bot is ABOUT to TOGGLE the openable (door/trapdoor) at {@code (x,y,z)} — if THIS plan prescribed a
     * SET there, announce the same-block state toggle to the grid ({@link NavGridUpdater#expectToggle}) so
     * {@link #planImpacted} does not read the plan's own toggle as the world diverging from it
     * (DESIGN-trapdoors.md §7 — this closes the doors gap: previously every own door toggle counted as a
     * FOREIGN change and cost one wasted-but-correct re-search). The toggle has no {@code toAir} direction —
     * the SAME Block changes state in place — so it rides its own expectation slot with its own match rule
     * (pos + same Block + {@code old != new}; a door's other half is covered there too). Verified against the
     * plan's own {@link StepEdits} doorSets exactly as {@link #expectOwnEdit} is against breaks/places: a
     * toggle no plan prescribed is never forgiven.
     */
    public void expectOwnToggle(int x, int y, int z) {
        final BlockPathPlan bp = blockPlan;
        if (bp == null || !prescribesEdit(bp, x, y, z)) {
            return;   // not ours to forgive — let the change count as divergence
        }
        NavGridUpdater.expectToggle(level, new BlockPos(x, y, z));
    }

    /**
     * Whether this plan folded a break, a place, a <b>door/trapdoor SET</b>, or a <b>clutch</b> at
     * {@code (x,y,z)} on any of its steps.
     * Cold: runs once per executed edit, over a window's worth of mostly edit-free steps. Package-private for
     * {@code PathPlanOwnEditTest}, which pins the semantic that keeps this honest: an edit the plan never
     * prescribed (a {@code /bot mine} or gather break through the same actuators) is NOT forgiven.
     *
     * <p><b>Why the clutch cell has to be tested separately.</b> The two clutch landing geometries fold
     * different edits (ClutchModel §Landing). A LANDS-ON-TOP kind (slime, hay) folds a place at the landing
     * floor, so its cell is already covered by the place loop. A SINK-THROUGH kind ({@link ClutchModel#WATER},
     * {@link ClutchModel#POWDER_SNOW}) deliberately folds <b>no geometry edit at all</b> — a
     * {@code PathEdits.PLACED} at the landing FEET cell would make the node read its own body space as solid
     * and dead-end the search — so without this third loop its cell is unknown to the gate entirely. Both the
     * place AND the reclaim would then be classified as foreign divergence, and the resulting
     * {@link #planImpacted} would re-search the plan MID-CLUTCH: the bot is airborne over a drop it only
     * survives because of the cushion this plan prescribed, which is the worst possible moment to reset the
     * waypoint cursor. Testing {@link StepEdits#clutchCell()} closes exactly that hole.
     *
     * <p><b>Coordinate-only, deliberately.</b> This answers "is this cell one of ours", not "is this the right
     * mutation" — and it must stay that way, because a clutch cell is edited TWICE in opposite directions (the
     * place fills it, the reclaim empties it) and a direction test here would have to forgive both, which is
     * strictly weaker than forgiving neither. The direction is matched exactly once, later and per-mutation,
     * at {@code NavGridUpdater.consumeExpected} against {@code newState.isAir()} — the announced {@code toAir}
     * flag {@link #expectOwnEdit} passes through. Keeping the two concerns split is what lets a vine growing
     * into the very cell we were about to clutch still count as foreign.
     */
    static boolean prescribesEdit(BlockPathPlan bp, int x, int y, int z) {
        for (int s = 0; s < bp.size(); s++) {
            final StepEdits e = bp.edits(s);
            if (e == null) {
                continue;
            }
            for (int i = 0; i < e.breakCount(); i++) {
                final BlockPos p = e.breakPos(i);
                if (p.getX() == x && p.getY() == y && p.getZ() == z) return true;
            }
            for (int i = 0; i < e.placeCount(); i++) {
                final BlockPos p = e.placePos(i);
                if (p.getX() == x && p.getY() == y && p.getZ() == z) return true;
            }
            // Door/trapdoor SETs (DESIGN-trapdoors.md §7): the toggle executors verify their own-toggle
            // announcement here. Coordinate-only like the rest — the toggle DIRECTION is matched later, at
            // NavGridUpdater.consumeExpectedToggle (same Block + old != new), the same concern split the
            // clutch cell documents above.
            for (int i = 0; i < e.doorSetCount(); i++) {
                final long c = e.doorSetAt(i);
                if (BlockPos.getX(c) == x && BlockPos.getY(c) == y && BlockPos.getZ(c) == z) return true;
            }
            if (e.clutchKind() != ClutchModel.NONE) {
                // Packed compare, no BlockPos: clutchCell is only MEANINGFUL past the NONE guard (it is 0 on
                // every other step, which is a real world cell at (0,0,0) and would forgive edits there).
                final long c = e.clutchCell();
                if (BlockPos.getX(c) == x && BlockPos.getY(c) == y && BlockPos.getZ(c) == z) return true;
            }
        }
        return false;
    }

    /**
     * The {@link PathEdits} fold kind of the break THIS plan folded at {@code (x,y,z)} —
     * {@code BROKEN}, {@code BROKEN_WATER} or {@code BROKEN_LAVA} — or {@link PathEdits#NONE} when no step
     * breaks that cell (the coordinate may still be prescribed as a place/doorSet/clutch cell;
     * {@link #prescribesEdit} answers that broader question, and {@link #expectOwnEdit} always asks it
     * first). This is how the executor's arm learns which expectation the planner's verdict calls for: a
     * fluid kind arms the two-phase {air, fluid} set, everything else the plain to-air direction
     * (DESIGN-fluid-flow-prediction.md §8.3). First match in step order wins, mirroring
     * {@link #prescribesEdit}'s walk — a cell is not broken twice on one plan (once broken it stays open in
     * the diff), so the first folded break at the coordinate is the only one. Cold: runs once per executed
     * break, after {@link #prescribesEdit} already vouched for the cell.
     *
     * <p>Kind recovery deliberately does NOT weaken the coordinate-only concern split documented at
     * {@link #prescribesEdit}: the kind chooses only WHICH expectation gets armed; the observed state is
     * still matched per-mutation at the grid ({@code NavGridUpdater.consumeExpected} and the pending-flood
     * residual's {@code observePendingFlood}), so a vine growing into the cell stays foreign either way.
     * Package-private for the own-edit tests, beside the walk it mirrors.
     */
    static int prescribedBreakKind(BlockPathPlan bp, int x, int y, int z) {
        for (int s = 0; s < bp.size(); s++) {
            final StepEdits e = bp.edits(s);
            if (e == null) {
                continue;
            }
            for (int i = 0; i < e.breakCount(); i++) {
                final BlockPos p = e.breakPos(i);
                if (p.getX() == x && p.getY() == y && p.getZ() == z) return e.breakKindAt(i);
            }
        }
        return PathEdits.NONE;
    }

    /** Diagnostic only: the chunk key whose version last tripped {@link #planImpacted}. */
    private long impactChunk = Long.MIN_VALUE;

    /**
     * DIAGNOSTIC: describe the change that just tripped {@link #planImpacted} and how relevant it actually
     * was to this plan (owner request 2026-08-06). Called only on the re-search log line, never by logic.
     *
     * <p>A {@code plan-impacted} re-search rebuilds the plan and resets the waypoint cursor <b>mid-move</b>,
     * so the standing question is whether the trigger deserved it. The gate is supposed to make a distant
     * redstone clock impossible — it compares only the chunks this path traverses — but that is an assertion
     * about the chunk list, and nothing has ever checked it against a real trigger. This prints the parts
     * needed to check it: the block and its before/after state, its distance from the bot, and the NEAREST
     * PLAN WAYPOINT to it. A change 3 blocks off the route is a legitimate impact; one 200 blocks away that
     * still matched a plan chunk means the chunk list is over-broad and the gate is being defeated.
     *
     * <p>Region addresses at level 0 (the leaf tier the region skeleton is built from) come along for the
     * ride so "same region as the bot" is answerable without arithmetic; the adjacent {@code HPA window}
     * line carries the skeleton those regions sit in.
     */
    public String impactForensic(BlockPos botPos) {
        if (impactChunk == Long.MIN_VALUE) {
            return "no plan (nothing to keep following)";
        }
        final int cx = NavStore.keyX(impactChunk), cz = NavStore.keyZ(impactChunk);
        final NavGridUpdater.ChunkChange c = NavGridUpdater.lastChange(level, impactChunk);
        final int minY = LevelBounds.minY(level);
        final StringBuilder sb = new StringBuilder(224);
        sb.append("chunk=(").append(cx).append(',').append(cz).append(')');
        if (c == null) {
            // Bumped by a nav build/drop or the fluid-edge fold — no block change is on record for it.
            sb.append(" change=<none recorded: nav build/drop or fluid-edge fold>");
        } else {
            final BlockPos p = BlockPos.of(c.pos);
            sb.append(" block=(").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                    .append(") old=").append(c.oldState).append(" new=").append(c.newState)
                    .append(" v=").append(c.version)
                    .append(" distFromBot=").append(String.format("%.1f", Math.sqrt(p.distSqr(botPos))))
                    .append(" blockRegion=(")
                    .append(RegionAddress.regionX(p.getX(), 0)).append(',')
                    .append(RegionAddress.regionY(p.getY(), 0, minY)).append(',')
                    .append(RegionAddress.regionZ(p.getZ(), 0)).append(')');
            // The load-bearing number: how near the change is to the route we are about to throw away.
            final BlockPathPlan bp = blockPlan;
            int bestI = -1;
            double bestD = Double.MAX_VALUE;
            if (bp != null) {
                for (int i = 0; i < bp.size(); i++) {
                    final double d = bp.waypoint(i).distSqr(p);
                    if (d < bestD) { bestD = d; bestI = i; }
                }
            }
            if (bestI >= 0) {
                final BlockPos w = bp.waypoint(bestI);
                sb.append(" nearestWp=#").append(bestI)
                        .append(" (").append(w.getX()).append(',').append(w.getY()).append(',')
                        .append(w.getZ()).append(')')
                        .append(" dist=").append(String.format("%.1f", Math.sqrt(bestD)));
            }
        }
        sb.append(" botRegion=(")
                .append(RegionAddress.regionX(botPos.getX(), 0)).append(',')
                .append(RegionAddress.regionY(botPos.getY(), 0, minY)).append(',')
                .append(RegionAddress.regionZ(botPos.getZ(), 0)).append(')')
                .append(" planChunks=").append(planChunkCount);
        return sb.toString();
    }

    private boolean botOnBlockPlan(BlockPos floor) {
        return onBlockPlan(blockPlan, blockPlanStart, floor);
    }

    /**
     * Whether {@code floor} is a cell the given plan vouches for — the {@code onRoute} half of the
     * cascade's deviation test ("the plan vouches for intentional off-window steps like a cliff-edge
     * fall-lineup"). Package-private + pure so the start-cell rule below can be pinned headlessly.
     *
     * <p>Membership is the plan's START cell (its implicit step −1) plus every waypoint, each in a ±1
     * box. The start term is NOT redundant: reconstruct is start-exclusive, so a plan whose first move
     * is a Parkour/DiagonalParkour/Fall puts waypoint 0 two-to-five cells away and leaves the bot's own
     * planned-from cell unmatched. Reading that as a DEVIATION discards the whole region skeleton and
     * re-derives a fresh route while the bot is still standing exactly where it was planned to stand —
     * a matcher artifact, and the trigger behind the 2026-07-31 post-replan Ascend hold.
     */
    static boolean onBlockPlan(BlockPathPlan plan, BlockPos planStart, BlockPos floor) {
        if (plan == null || plan.isEmpty()) {
            return false;
        }
        if (planStart != null && within1(planStart, floor)) {
            return true; // standing at the cell this plan was searched FROM — maximally on-route
        }
        final int n = plan.size();
        for (int i = 0; i < n; i++) {
            final BlockPos wp = plan.waypoint(i); // the stand cell; its floor is one below
            if (within1(wp.below(), floor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean within1(BlockPos planFloor, BlockPos floor) {
        return Math.abs(planFloor.getX() - floor.getX()) <= 1
                && Math.abs(planFloor.getY() - floor.getY()) <= 1
                && Math.abs(planFloor.getZ() - floor.getZ()) <= 1;
    }

    /** Reset the sliding window to the head of a freshly-swapped skeleton (cascade L0 change). */
    private void resetWindow() {
        this.windowStart = 0;
        this.committedIndex = 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // The wiggle / commit rule (HPA-IMPLEMENTATION.md §9)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The hysteresis test that distinguishes the FINAL crossing into {@code skeleton[j]} from a transient
     * wiggle: the bot has committed to {@code skeleton[j]} iff <b>none of the active block plan's REMAINING
     * waypoints lie in any skeleton region with index in {@code [committedIndex, j)}</b> — i.e. the path
     * never goes back. Scans the live {@link BlockPathPlan} in place (≤ a window of ~48 waypoints, no copy,
     * no boxing) and maps each remaining waypoint to its level-0 region.
     *
     * <p>Inconclusive (no block plan / empty) → {@code false}: a null plan is BLOCKED (repair owns it) and
     * the commit then happens via {@link #replanBlock}'s forward-slide when the next plan arrives.
     */
    private boolean committed(int j) {
        if (blockPlan == null || blockPlan.isEmpty()) {
            return false; // inconclusive — no commit without a plan to vouch for it
        }
        // Find the bot's current waypoint (nearest remaining), then scan from there to the window target.
        final int n = blockPlan.size();
        final int from = nearestWaypointIndex(botFloor);
        for (int i = from; i < n; i++) {
            BlockPos wp = blockPlan.waypoint(i);
            int idx = skeletonIndexOf(wp, committedIndex, j - 1);
            if (idx >= committedIndex && idx < j) {
                return false; // a remaining waypoint still revisits an earlier region — not committed yet
            }
        }
        return true;
    }

    /**
     * Index of the remaining waypoint nearest (squared distance) to {@code floor} — the bot's current step
     * along the block plan, the start of the "remaining" scan. Allocation-free linear scan; the block plan is
     * only a window long.
     */
    private int nearestWaypointIndex(BlockPos floor) {
        final int n = blockPlan.size();
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            BlockPos wp = blockPlan.waypoint(i);
            long dx = wp.getX() - floor.getX();
            long dy = wp.getY() - floor.getY();
            long dz = wp.getZ() - floor.getZ();
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------
    // Window target + block replan (HPA-IMPLEMENTATION.md §9)
    // ---------------------------------------------------------------------------------------------------

    /** The skeleton index of the window's far (last) region: {@code min(windowStart+WINDOW-1, lastIndex)}. */
    int windowLast() {
        return Math.min(windowStart + WINDOW - 1, skeleton.size() - 1);
    }

    /**
     * Replan the current window's block path (HPA-IMPLEMENTATION.md §9): pick the window's block target —
     * the real {@code goalFloor} when the goal's region is at or before the window's far index, else the
     * far region center projected to a standable floor column — and run {@link BlockPathfinder#findPath}
     * over a fresh full {@link NavGridView}. Sets {@link #status} to RUNNING (found) or BLOCKED (null).
     */
    /** Routine-launch overload — the search does not impugn the current plan (fresh plan,
     *  forward-slide commit, cascade re-derive). */
    private void replanBlock() {
        replanBlock(false);
    }

    /** @param suspect whether this launch site implies the CURRENT plan may be invalid
     *  (terrain-impacted refresh / repair / blocked-null, retries inheriting) — carried onto the
     *  mailbox for the follower's caution gate (DESIGN-async-step-safety.md §3). */
    private void replanBlock(boolean suspect) {
        WindowTargeting.Result choice = targeting.target(skeleton, windowStart, windowLast(), botFloor);
        // FORWARD-SLIDE (s52 — replaces both slideWindowOnEmptyPlan and the REPLAN_NEAR_TARGET
        // commit-on-approach): never aim a search at a target the bot ALREADY satisfies within the block
        // tier's own goal tolerance (±1 horizontal / ±2 vertical). Such a search returns a FOUND
        // 0-waypoint plan the follower can't walk (the 2026-07-06 starvation), and the old fixes either
        // consumed that empty plan after paying for it (slideWindowOnEmptyPlan) or pre-empted it with a
        // magic Chebyshev-3 approach radius. Instead: the block tier's own arrival tolerance IS the
        // commit radius — the search itself would declare this target reached from here — so commit the
        // step and slide the window forward BEFORE paying any search. Also covers the boundary-straddle
        // bob (a portal 1 cell into the next region, "reached" 1 short). Bounded: each slide strictly
        // advances committedIndex toward the skeleton tail. The GOAL target is excluded — arrival there
        // is owned by onBotMoved's goal-tolerance check (→ COMPLETE).
        while (!choice.pos.equals(goalFloor) && choice.step > committedIndex
                && withinTolerance(botFloor, choice.pos)) {
            committedIndex = choice.step;
            windowStart = choice.step;
            choice = targeting.target(skeleton, windowStart, windowLast(), botFloor);
        }
        final BlockPos target = choice.pos;
        this.windowTargetStep = choice.step;
        this.windowTargetKind = choice.kind;
        // No corridor envelope: the block-A* searches the full grid toward the near (~3-region) window target,
        // so it can take the REAL route even when that wanders a few regions off the coarse skeleton (the
        // skeleton is a hint, not a cage — the old corridor's job, capping the pillar flood, is now done by the
        // cuboid/forced-cost/macro layer). A cuboid GROWTH cap (not a confinement) keeps a flat world from
        // growing one unbounded. Partial paths (BlockPathfinder.PARTIAL_PATH) make best-effort progress.
        final RegionBound cuboidCap = cuboidCapBox(target);
        this.windowTargetPos = target;
        this.windowCorridor = cuboidCap; // exposed for /bot trace (now the cuboid cap, not a confinement)

        if (Debug.ENABLED) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] HPA window: skeleton={}regions committed={} window=[{}..{}] of {} "
                            + "bot=({},{},{}) target=({},{},{}) goalInWindow={} cuboidCap={}",
                    skeleton.size(), committedIndex, windowStart, windowLast(), skeleton.size() - 1,
                    botFloor.getX(), botFloor.getY(), botFloor.getZ(),
                    target.getX(), target.getY(), target.getZ(), target.equals(goalFloor), cuboidCap);
            // The CASCADE STACK, once per window swap. The window target alone cannot tell you whether the
            // planner is healthy: a 1-cell target is the SYMPTOM of a collapsed lower level, and the cause is
            // several levels up. This prints size / committed / reachesGoal / unbuilt for every level, so the
            // two standing invariants are a grep rather than an inference — the TOP level must always read
            // reachesGoal=true, and no level should reach committed == size-1 without having slid first.
            OrebitCommon.LOGGER.info("[Orebit] {}", SkeletonDump.stackSummary(this));
            // The caps this search actually runs with — the one line that catches "the config file says X
            // but the search priced with Y" (stale reload, wrong server dir, a caller passing a preset).
            OrebitCommon.LOGGER.info(
                    "[Orebit] search caps: takesDamage={} costPerHitpoint={} canBreak={} canPlace={} "
                            + "maxNodes={} greedyWeight={}",
                    caps.takesDamage(), caps.costPerHitpoint(), caps.canBreak(), caps.canPlace(),
                    caps.maxNodes(), caps.greedyWeight());
        }

        // ASYNC (DESIGN-background-pathfinding.md §5): submit instead of compute. The current blockPlan
        // stays live (the follower keeps walking it); the result is adopted at the next settled boundary
        // by pollPending. Status stays RUNNING while a search is in flight — BLOCKED now means "a search
        // RETURNED null", never "a search is still running". A pending search toward this same target is
        // left alone (the 40-tick refresh timer would otherwise churn resubmits); anything else in flight
        // is superseded (latest-wins).
        // SEAM SEEDING (DESIGN-replan-handoff.md §4): a re-search launched while an incumbent plan is
        // executing starts from the horizon seam — the follower-chosen waypoint the bot settles on after
        // a max-budget search completes (§3), carried as the plan's search start (blockPlanStart, no new
        // identity concept) — with the §4 SUB-RANGE baseline fold: only the edits of steps up to and
        // including the seam (the whole-suffix fold would inject phantom edits from steps the new plan
        // never executes). The one condition implements the §4 per-site table: S1 (ctor — no incumbent),
        // S3 (blocked-null), S6 (repair) and S5-consumed (follower hands seam -1) all seed from botFloor
        // exactly as before; S2 forward-slide, S4 cascade-swap, S5-impacted and S7 retry seed from the
        // seam. The identity guard (seamPlan == blockPlan) additionally un-seeds any launch after an
        // install this tick already replaced the walked plan. botFloor's OTHER readers — the
        // forward-slide tolerance above, WindowTargeting, cuboidCapBox, regionFieldFor — stay
        // live-anchored: only the search start changes (the split P4 already demonstrates).
        final boolean seeded = blockPlan != null && seamPlan == blockPlan
                && seamIndex >= 0 && seamIndex < blockPlan.size();
        final BlockPos searchStart = seeded ? blockPlan.floor(seamIndex) : botFloor;
        final EditSnapshot searchBaseline = seeded
                ? EditSnapshot.fromSteps(blockPlan, seamFirstUnedited, seamIndex)
                : baseline;
        if (Debug.ENABLED && seeded) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] seam-seed: k={} start=({},{},{}) botFloor=({},{},{}) fold=[{}..{}]",
                    seamIndex, searchStart.getX(), searchStart.getY(), searchStart.getZ(),
                    botFloor.getX(), botFloor.getY(), botFloor.getZ(), seamFirstUnedited, seamIndex);
        }

        if (executor != null) {
            if (async.pendingSearchToward(target)) {
                // A boundary replan toward this same target is already in flight → skip. An in-flight
                // PRE-PLAN toward it is also left alone WHILE the current plan is still walkable (the
                // 40-tick refresh timer would otherwise routinely kill the precompute — review finding;
                // the seam-reject → replan-from-actual fallback covers a stall that invalidated the
                // prediction, one round-trip later). Only a genuinely planless bot preempts a pre-plan.
                // (This same skip is what keeps a SEEDED search alive through a consumed-plan
                // refreshWindow at the terminus — DESIGN-replan-handoff.md §5, the consumed-before-
                // landing race — and what makes the post-PANIC blocked-null firing harmless.)
                if (!async.pendingIsPreplan() || blockPlan != null) return;
            }
            if (blockPlan != null && async.parkedFor(target)) {
                return; // the precomputed result is already parked for this target — arrival adopts it
            }
            submit(searchStart, target, cuboidCap, searchBaseline, false, suspect,
                    seeded, seeded ? blockPlan : null, seeded ? seamIndex : -1);
            if (status != PathStatus.RUNNING) status = PathStatus.RUNNING;
            return;
        }

        // confineBound = null (unconfined), cuboidBound = the growth cap. startMode = the bot's live pose (so a
        // replan mid-sprint-swim stays PRONE instead of re-deriving STANDING from a bob and re-initiating).
        // baseline = the splice seed (null for every non-spliced plan; the §4 sub-range fold when seeded).
        // The grid view is built HERE, below the async branch — in async mode the worker builds its own
        // background view, so the tick thread must not pay the per-search view construction twice
        // (SHORT-guard discipline).
        final NavGridView grid = new NavGridView(level);
        final BlockPathPlan found = BlockPathfinder.findPath(grid, searchStart, target, caps, null,
                cuboidCap, inventory, startMode, searchBaseline, 0L, regionFieldFor(target),
                tolXZFor(target), tolYFor(target));
        if (seeded && found != null) {
            // SYNC PARK (DESIGN-replan-handoff.md §5): a seeded result — sync exactly like async —
            // installs only via the four ordered adoption cases at a settled boundary, never inline
            // mid-move: the same-tick install under a bot still moving inside the settled cell IS the
            // §1 wedge (the run was sync). The incumbent keeps walking; pollPending adopts at the seam.
            // A null result carries no frame to park and falls through to the normal BLOCKED install
            // below (the repair owns it) — the sync mirror of drainPending's null-plan pass-through.
            async.parkSeededResult(found, BlockPathfinder.lastWasPartial(),
                    BlockPathfinder.lastExpansions(), BlockPathfinder.lastWasBudgetHit(),
                    searchStart, target, blockPlan, seamIndex);
            if (Debug.ENABLED) {
                OrebitCommon.LOGGER.info(
                        "[Orebit] seam-park: sync seeded result parked seam=({},{},{}) {}wp",
                        searchStart.getX(), searchStart.getY(), searchStart.getZ(), found.size());
            }
            if (status != PathStatus.RUNNING) status = PathStatus.RUNNING;
            return;
        }
        this.blockPlan = found;
        this.blockPlanStart = searchStart; // this plan's implicit step −1 (== botFloor unless seeded-null)
        this.adoptedMatchedIndex = -1;     // an inline (sync) install always runs from its step 0
        this.lastPlanPartial = blockPlan != null && BlockPathfinder.lastWasPartial();
        this.status = resultStatus(blockPlan, BlockPathfinder.lastExpansions(),
                BlockPathfinder.lastWasPartial(), BlockPathfinder.lastWasBudgetHit(),
                blockPlan == null ? BlockPathfinder.lastRealizedCrossings() : null, searchStart);
        if (Debug.ENABLED && blockPlan != null) {
            logBlockPlan();
        }
        snapshotPlanChunks(); // plan-relevance baseline for the terrain recheck (owner 2026-07-24)
    }

    /**
     * Map a search <b>result</b> to the driver status. Every site that installs a result as
     * {@link #blockPlan} must come through here:
     * <ul>
     *   <li>non-null plan → RUNNING (and clears {@link #startDead}).</li>
     *   <li>{@code null} with real exploration → BLOCKED + {@link #blockedGeneration}++ — a new fact about
     *       the world; the driver's online repair consumes exactly one repair per such result (replaced
     *       the old REPAIR_COOLDOWN throttle).</li>
     *   <li>{@code null} with ≤1 expansion → BLOCKED + {@link #startDead} — the search died AT the start
     *       (the bot's own feet/head cells emit no candidates: a buried bot). That proves nothing about
     *       any skeleton hop, so it must NEVER feed the repair blacklist (doing so was an unbounded
     *       repair→resubmit→fail churn at planner speed — the s52b log-flood); the follower self-rescues
     *       instead (dig out, {@code BotNavigator.selfRescue}).</li>
     * </ul>
     */
    private PathStatus resultStatus(BlockPathPlan plan, int expansions, boolean partial, boolean budgetHit,
                                    long[] realized, BlockPos searchStart) {
        // Telemetry (pure observation — the single choke every installed result passes through): record the
        // search's node count, whether it was a best-effort PARTIAL, and whether its node/time cap bound it.
        if (stats != null) stats.onBlockSearch(expansions, partial, budgetHit);
        if (plan != null) {
            startDead = false;
            return PathStatus.RUNNING;
        }
        startDead = expansions <= 1;
        // CLASSIFICATION FORENSIC (2026-08-21): the one choke every installed result passes through. If a
        // run shows exhausted searches but no "region-crossing BLOCKED", the question is whether this line
        // ever ran — a result discarded upstream (see AsyncWindowSearch.drainLog) never reaches it, so the
        // status stays RUNNING and repairStep's `status != BLOCKED` guard silently disables invalidation.
        if (Debug.ENABLED) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] resultStatus: plan={} expansions={} partial={} budgetHit={} -> {} (startDead={})",
                    plan == null ? "NULL" : (plan.size() + "wp"), expansions, partial, budgetHit,
                    plan != null ? "RUNNING" : "BLOCKED", startDead);
        }
        if (!startDead) {
            blockedGeneration++;
            // Snapshot the failed search's realized crossings WITH the window geometry it ran under, so
            // repairBlocked (a later tick) blames the search that actually failed, not the live window.
            // The search's START floor rides along (the from-floor the sync findPath / async SearchRequest
            // ran from): the blame walk needs the start's skeleton position to skip hops behind it — a
            // cameFrom walk grows outward from the start, so a crossing INTO the start's own region can
            // never appear realized (the start-position blind spot).
            blockedRealized = realized;
            blockedStartFloor = searchStart;
            blockedWindowStart = windowStart;
            blockedTargetStep = windowTargetStep;
        }
        return PathStatus.BLOCKED;
    }

    /**
     * Monotone counter of BLOCKED search <b>results</b> (each null-returning search with real exploration
     * increments it once). The follower's repair step keys on this: one {@link #repairBlocked} attempt per
     * new BLOCKED result — identical inputs deterministically fail identically, so re-attempting between
     * results is pure waste, and each attempt consumes the result by blacklisting a hop and re-searching.
     * (s52: replaced the REPAIR_COOLDOWN tick throttle.)
     */
    public int blockedGeneration() {
        return blockedGeneration;
    }

    /** Total region→region crossings the cascade has blacklisted (summed across its per-level blacklists) —
     *  a monotone-within-this-plan telemetry read; the driver deltas it into the journey's
     *  {@code crossingsInvalidated} (a plan rebuild resets it to 0, which the delta handles). */
    public int blacklistedCrossings() {
        return hier != null ? hier.totalBlacklisted() : 0;
    }

    /** §9 rolling counter (DESIGN-rolling-skeleton.md §9/§12, pure observation): cascade {@code exhausted}
     *  firings at L0 — the rolling safety net engaging. Must be 0 on healthy battery routes. */
    public int exhaustedFires() {
        return hier != null ? hier.exhaustedFires() : 0;
    }

    /** §9 rolling counter (DESIGN-rolling-skeleton.md §9/§12, pure observation): settles where the L0
     *  extension search failed (the §7 degraded interim). Must be 0 on healthy battery routes. */
    public int degradedSettles() {
        return hier != null ? hier.degradedSettles() : 0;
    }

    /** Whether the last BLOCKED came from a START-DEAD search (≤1 expansion — see {@link #resultStatus}).
     *  The follower branches to self-rescue instead of hop repair. Cleared by any adopted plan. */
    public boolean startDead() {
        return startDead;
    }

    private int blockedGeneration;
    private boolean startDead;
    /** Realized region-crossing pairs of the search that produced the current BLOCKED result (raw-cell>>4
     *  {@link RegionAddress#packLevelKey} pairs from {@link BlockPathfinder#lastRealizedCrossings}), with the
     *  window geometry it ran under — snapshotted atomically in {@link #resultStatus} so {@link #repairBlocked}
     *  (a later tick) blames the search that actually failed, not the live window. {@code null} = no realized
     *  data (defensive / legacy positional fallback). */
    private long[] blockedRealized;
    /** The START floor cell of the search that produced the current BLOCKED result (the sync
     *  {@code findPath}'s from-floor / the async {@link SearchRequest}'s start), snapshotted in
     *  {@link #resultStatus} beside {@link #blockedRealized}. The blame walk uses it to skip hops that end
     *  at-or-before the start's own skeleton position — unrealizable-by-construction (the start-position
     *  blind spot). {@code null} = unknown (defensive) ⇒ the historical windowStart walk. */
    private BlockPos blockedStartFloor;
    private int blockedWindowStart;
    private int blockedTargetStep;

    /** Realized region-crossing count of the search behind the current BLOCKED result, or {@code -1} when
     *  no realized data travelled with it (diagnostic read for the repair log line). */
    public int blockedRealizedCount() {
        return blockedRealized == null ? -1 : blockedRealized.length / 2;
    }

    /**
     * Whether ANY async search is outstanding (in flight or finished-but-undrained), routine or
     * suspect, window slide or P4 pre-plan — the follower's committed-move caution keys on this
     * (owner 2026-07-31): even a routine slide may change the route's direction, and a committed jump
     * launched into that unresolved future can land the bot mid-air off whatever plan gets adopted.
     * A PARKED pre-plan does not count (finished, waiting at a known seam). Always false in sync mode.
     */
    public boolean searchPending() {
        return executor != null && async.searchPending();
    }

    /** The failing search's start region as {@code (rx,ry,rz)} (minY-rebased level-0 region coords, the
     *  skeleton convention), or {@code "?"} when unknown — diagnostic read for the repair log line. */
    /**
     * DIAGNOSTIC (2026-08-21 seam-pause forensic) — serviced boundary ticks spent waiting on the current
     * search, and the last drained search's submit-to-drain wall clock in ns ({@code -1} before the first
     * drain).
     *
     * <p>Read together with {@code BotNavigator.seamPauseTicks} these separate the three ways a seam-pause
     * burns ticks, which no previous log could tell apart: {@code polls ~= pauseTicks} = the search really
     * is slow; {@code polls == 0} while the pause climbs = the boundary is never serviced at all, so the
     * drain is unreachable (the {@code planAnchor} gate refused); {@code polls} small but the pause long =
     * the result is in hand and something downstream (an entry refusal) is holding it.
     */
    public int pendingPollTicks() {
        return async.pendingPollTicks();
    }

    /** See {@link #pendingPollTicks}. */
    public long lastSearchNanos() {
        return async.lastSearchNanos();
    }

    public String blockedStartRegionDesc() {
        if (blockedStartFloor == null) return "?";
        return "(" + RegionAddress.regionX(blockedStartFloor.getX(), 0) + ","
                + RegionAddress.regionY(blockedStartFloor.getY(), 0, minY) + ","
                + RegionAddress.regionZ(blockedStartFloor.getZ(), 0) + ")";
    }

    /** Build this submission's {@link SearchRequest} and hand it to the {@link AsyncWindowSearch mailbox}
     *  (which supersedes any in-flight search and, for a boundary replan, drops the parked pre-plan). */
    private void submit(BlockPos fromFloor, BlockPos target, RegionBound cuboidCap,
                        EditSnapshot seed, boolean preplan, boolean suspect,
                        boolean seeded, BlockPathPlan seededFrom, int seamIdx) {
        // regionFieldFor(target): the snapshot must carry the field rooted at THIS submission's target —
        // covers both the boundary replan and the P4 pre-plan (which targets windowTargetPos, so the root
        // matches the cached field from the last replanBlock and this is a cheap equals hit).
        async.submit(new SearchRequest(level, fromFloor, target, caps, inventory, startMode,
                cuboidCap, seed, executor.budgetNanos(), regionFieldFor(target),
                tolXZFor(target), tolYFor(target)), fromFloor, target, preplan, suspect,
                seeded, seededFrom, seamIdx);
    }

    /** The goal-arrival tolerance for a search toward {@code target}: the caller's {@link #goalTolXZ} when
     *  the target IS the real goal, else the default (window hops need no exactness). */
    private int tolXZFor(BlockPos target) {
        return target.equals(goalFloor) ? goalTolXZ : BlockPathfinder.DEFAULT_GOAL_TOL_XZ;
    }

    private int tolYFor(BlockPos target) {
        return target.equals(goalFloor) ? goalTolY : BlockPathfinder.DEFAULT_GOAL_TOL_Y;
    }

    /**
     * Drain the in-flight search if it finished (tick thread). Called from {@link #onBotMoved} — which
     * the follower only invokes at a settled boundary, so mid-plan adoption is boundary-gated by
     * construction — and from {@link #pollWhenPlanless}, the planless-bot exception (nothing to un-adopt).
     * The splice contract's accept+adopt steps (DESIGN-background-pathfinding.md §5/§7): the
     * {@link AsyncWindowSearch mailbox} classifies the finished handle
     * ({@link AsyncWindowSearch#drainPending}) and tests the parked pre-plan's seam
     * ({@link AsyncWindowSearch#pollParked}); the DECISIONS — adopt / BLOCKED / resubmit from the actual
     * floor — happen here, keeping the driver the sole writer of
     * {@link #blockPlan}/{@link #lastPlanPartial}/{@link #status}:
     * <ul>
     *   <li><b>Boundary replan</b> result: adopt if the bot is still within seam tolerance of the cell the
     *       search started from AND the window target hasn't moved AND the bot's floor is the searched
     *       start or ON the result plan (the post-plan reconcile, owner ruling 2026-07-30 — the follower's
     *       reached-scan then enters the plan mid-way when the bot walked ahead during the search);
     *       otherwise resubmit from the actual floor (the same recovery the escape hatches use). A
     *       {@code null} result = BLOCKED, exactly the sync path's semantics. An executor-rejected handle
     *       also retries — NOT a search verdict, never BLOCKED (that blacklists a real skeleton hop —
     *       review finding).</li>
     *   <li><b>Pre-plan</b> result (P4): PARK it — the bot hasn't reached the predicted start yet. Each
     *       boundary visit re-tests the parked seam; on accept it's adopted with no search pause at all,
     *       on target-change it's dropped (the window moved on).</li>
     *   <li><b>Seam-seeded</b> result (DESIGN-replan-handoff.md §5 as amended by §11, sync AND async):
     *       PARKS at drain, then each boundary runs the §11 execution-edge pump — the index trichotomy
     *       against the seam while a move is in flight (before-seam park / at-seam truncate+defer /
     *       beyond: the in-flight move's LANDING cell decides FAST-FORWARD vs PANIC), with every
     *       non-KEEP verdict CONSUMMATED only at the truncated terminal move's completion (the armed
     *       consummation below — no plan ever swaps mid-move, owner ruling 2026-08-20); the degenerate
     *       no-move-in-flight regimes (planless/consumed/holding) consummate immediately, step-0
     *       {@code entryReady}-gated on ADOPT.</li>
     * </ul>
     */
    private void pollPending(BlockPos actualFloor, boolean fluidAnchor) {
        // §11 ARMED CONSUMMATION (owner ruling 2026-08-20): a deferred seam verdict owns this boundary
        // until it consummates or its premise dies — nothing else may install while it holds.
        if (consummationTick(actualFloor)) {
            return;
        }
        switch (async.drainPending(actualFloor, inFlightLanding(), windowTargetPos, startMode, fluidAnchor)) {
            case RETRY:
                // Executor hiccup / drifted past seam tolerance / window moved — plan from where we
                // really are (the mailbox never decides; see AsyncWindowSearch.Drain).
                replanBlock(async.lastDrainSuspect()); // a retried suspect search stays suspect
                break;
            case PARKED:
                // A seam-SEEDED boundary result finished and PARKED (DESIGN-replan-handoff.md §5, R2) —
                // nothing installs now; the four-case pump below adopts it at the seam.
                if (Debug.ENABLED) {
                    final BlockPos ps = async.parkedStartCell();
                    OrebitCommon.LOGGER.info("[Orebit] seam-park: seeded result parked seam=({},{},{})",
                            ps.getX(), ps.getY(), ps.getZ());
                }
                break;
            case RESULT:
                this.blockPlan = async.resultPlan();
                this.blockPlanStart = async.resultStart(); // see blockPlanStart's javadoc
                this.adoptedMatchedIndex = async.resultMatchedIndex(); // -1 here — never a mid-body entry
                this.lastPlanPartial = blockPlan != null && async.resultPartial();
                this.status = resultStatus(blockPlan, async.resultExpansions(),
                        async.resultPartial(), async.resultBudgetHit(), async.resultRealized(),
                        async.resultStart());
                if (Debug.ENABLED && blockPlan != null) logBlockPlan();
                snapshotPlanChunks(); // adopted an async result — re-baseline the plan-relevance snapshot
                break;
            default: // NONE — nothing finished / pre-plan parked or dropped internally
                break;
        }
        if (async.seededParked()) {
            // The §11 execution-edge pump for a parked SEAM-SEEDED result (DESIGN-replan-handoff.md §5
            // as amended by §11, owner ruling 2026-08-20) — the verdict computation lives in the mailbox
            // (pollSeededParked); the DECISIONS (install now / arm a deferred consummation / drop) live
            // here, keeping the driver the sole writer of blockPlan/lastPlanPartial/status. The
            // execution position (follower plan identity + cursor + move-in-flight) rides the seam
            // handoff fields the follower refreshes every boundary.
            final AsyncWindowSearch.SeamVerdict verdict = async.pollSeededParked(seamBot, actualFloor,
                    inFlightLanding(), windowTargetPos, startMode, fluidAnchor,
                    seamPlan, seamCursor, seamMoveInFlight);
            if (async.verdictDeferred()) {
                // IN-EXECUTION: a move is in flight — no install now, full stop. Arm the consummation;
                // the follower truncates its plan at verdictTerminal ("the plan will now END when the
                // current movement ends") and the verdict consummates at that move's completion.
                switch (verdict) {
                    case ADOPT:
                    case FAST_FORWARD:
                    case PANIC:
                        this.armedVerdict = verdict;
                        this.armedPlan = seamPlan;
                        this.armedTerminal = async.verdictTerminal();
                        this.armedMatched = async.verdictMatched();
                        if (Debug.ENABLED) {
                            OrebitCommon.LOGGER.info(
                                    "[Orebit] seam-verdict {} armed: terminal={} matched={} cursor={} seam={}"
                                            + " — consummating at move completion (§11)",
                                    verdict, armedTerminal, armedMatched, seamCursor,
                                    async.parkedSeamIndex());
                        }
                        break;
                    default: // KEEP never defers
                        break;
                }
                return; // the seeded state owns this boundary — the P4 pump below must not run
            }
            switch (verdict) {
                case ADOPT:        // degenerate (no move in flight) — immediate consummation at the seam
                case FAST_FORWARD: // degenerate — settled on the new plan's body past the seam
                    installSeededAdoption(
                            verdict == AsyncWindowSearch.SeamVerdict.ADOPT ? "ADOPT" : "FAST-FORWARD",
                            actualFloor);
                    break;
                case ENTRY_REFUSED:
                    // The immediate ADOPT's step-0 entryReady gate refused the bot's pose — stay parked
                    // one more boundary (provably unreachable for the current movement set).
                    if (Debug.ENABLED) {
                        OrebitCommon.LOGGER.info(
                                "[Orebit] seam-adopt entryReady REFUSED at ({},{},{}) — staying parked",
                                actualFloor.getX(), actualFloor.getY(), actualFloor.getZ());
                    }
                    break;
                case PANIC:
                    // Degenerate PANIC — the bot is SETTLED past the seam and off the plan (no move in
                    // flight to finish). Drop the incumbent and stop THERE: null-only, NO immediate
                    // resubmit (the mid-slide install hazard). The follower's planless WAIT zeroes
                    // forward, rest re-anchors the settled floor, and the rest-gated blocked-null site
                    // relaunches from the true rest cell (§10 U5) — "resubmit from rest", literally.
                    this.blockPlan = null;
                    this.lastPlanPartial = false;
                    if (status != PathStatus.RUNNING) status = PathStatus.RUNNING;
                    if (Debug.ENABLED) {
                        OrebitCommon.LOGGER.info(
                                "[Orebit] seam-adopt PANIC: bot=({},{},{}) settled past the seam and off the"
                                        + " parked plan — dropped plan+result; rest-gated replan follows (§10)",
                                actualFloor.getX(), actualFloor.getY(), actualFloor.getZ());
                    }
                    break;
                default: // KEEP — before the seam / outside the sanity box / stale-dropped — stay parked
                    break;
            }
            return; // the parked slot was seeded — the P4 pump below must not run this tick
        }
        // Parked pre-plan adoption: the no-pause splice. Adopt only when the bot has actually arrived at
        // the predicted start (seam accept) and the window target is still the parked one.
        if (async.pollParked(actualFloor, windowTargetPos, startMode, fluidAnchor)) {
            this.blockPlan = async.resultPlan();
            this.blockPlanStart = async.resultStart(); // see blockPlanStart's javadoc
            this.adoptedMatchedIndex = async.resultMatchedIndex(); // -1 — a P4 park adopts at its start
            this.lastPlanPartial = async.resultPartial();
            this.status = resultStatus(blockPlan, async.resultExpansions(),
                    async.resultPartial(), async.resultBudgetHit(), null, // parked plans are never null
                    async.resultStart());
            if (Debug.ENABLED) logBlockPlan();
            snapshotPlanChunks(); // adopted a parked pre-plan — re-baseline the plan-relevance snapshot
        }
    }

    /**
     * The in-flight move's LANDING floor — the §11 verdict probe ({@code seamPlan.floor(seamCursor)},
     * the follower plan's search-native floor of the executing step; owner ruling 2026-08-20: verdicts
     * key on where the bot is in PLAN EXECUTION, and the move in flight lands there next). {@code null}
     * in the degenerate regimes (planless / consumed / holding at a truncated terminal), where the
     * settled live floor is the truth instead. Cold: at most one {@link BlockPathPlan#floor} allocation
     * per boundary drain.
     */
    private BlockPos inFlightLanding() {
        return seamMoveInFlight && seamPlan != null && seamCursor >= 0 && seamCursor < seamPlan.size()
                ? seamPlan.floor(seamCursor) : null;
    }

    /**
     * The §11 armed-consummation tick (owner ruling 2026-08-20): while a deferred seam verdict is armed,
     * it owns every boundary — HOLD until the truncated terminal move completes ({@code seamCursor >
     * armedTerminal}, written by the follower's reached-advance, the sole completion authority), then
     * consummate: ADOPT/FAST_FORWARD install the parked result exactly like a drained one (ADOPT gated
     * on the new plan's step-0 {@link com.orebit.mod.pathfinding.blockpathfinder.Movement#entryReady}
     * against the bot's settled, centered pose — the gate's §11 re-site from verdict time); PANIC drops
     * the incumbent (null-only — the §10 rest-gated planless machinery owns the relaunch). State-based
     * disarms whenever the premise dies: the follower plan changed (an install replaced the walked
     * plan), the parked result vanished ({@code dropBlockPlan}/{@code cancel}), or the window target
     * moved (the P4 rule, applied while holding).
     *
     * @return {@code true} when the armed state consumed this boundary (held, consummated, or a
     *         premise-death drop) — {@code pollPending} must not run its other pumps this tick.
     */
    private boolean consummationTick(BlockPos actualFloor) {
        if (armedVerdict == null) {
            return false;
        }
        if (armedPlan != blockPlan || armedPlan != seamPlan) {
            disarmConsummation("the walked plan changed"); // an install/drop replaced the premise
            return false;
        }
        final boolean panic = armedVerdict == AsyncWindowSearch.SeamVerdict.PANIC;
        if (!panic) {
            if (!async.seededParked()) {
                disarmConsummation("the parked result died");
                return false;
            }
            if (!windowTargetPos.equals(async.parkedTargetCell())) {
                async.dropParked();
                disarmConsummation("the window target moved");
                return false;
            }
        }
        if (seamCursor <= armedTerminal) {
            return true; // the terminal move is still in flight — hold (the follower ends it centered)
        }
        // THE COMPLETION CLAIM. Note this is a CURSOR test, not a physical one: "the terminal move
        // completed" means only "the follower's reached-scan advanced past it". BotNavigator's
        // cursor-advance trace prints the pose that justified the advance — pair the two lines when
        // diagnosing a mid-move consummation (the r10/r12 wedge, ReplanCourse midclimb-t6..t10).
        if (Debug.ENABLED) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] seam-consummate GATE OPEN: seamCursor={} > armedTerminal={} verdict={}"
                            + " inFlight={} actualFloor=({},{},{})",
                    seamCursor, armedTerminal, armedVerdict, seamMoveInFlight,
                    actualFloor.getX(), actualFloor.getY(), actualFloor.getZ());
        }
        // The terminal move COMPLETED and this is a settled boundary — consummate.
        if (panic) {
            this.blockPlan = null;
            this.lastPlanPartial = false;
            if (status != PathStatus.RUNNING) status = PathStatus.RUNNING;
            if (Debug.ENABLED) {
                OrebitCommon.LOGGER.info(
                        "[Orebit] seam-consummate PANIC: finished move {} centered at ({},{},{}) — dropping"
                                + " the plan; rest-gated replan follows (§10 U5 / §11)",
                        armedTerminal, actualFloor.getX(), actualFloor.getY(), actualFloor.getZ());
            }
            disarmConsummation(null);
            return true;
        }
        if (armedVerdict == AsyncWindowSearch.SeamVerdict.ADOPT && seamBot != null) {
            final BlockPathPlan parked = async.parkedSeededPlan();
            if (parked != null && !parked.isEmpty()
                    && !parked.movement(0)
                            .entryReady(seamBot, seamBot.footX(), seamBot.footY(), seamBot.footZ())
                    && !climbStanceEntry(seamBot)) {
                if (Debug.ENABLED) {
                    OrebitCommon.LOGGER.info(
                            "[Orebit] seam-consummate entryReady REFUSED at ({},{},{}) — holding armed",
                            actualFloor.getX(), actualFloor.getY(), actualFloor.getZ());
                }
                return true; // stay armed/held — re-consulted next boundary (delivery-gated since 2026-08-20, §5)
            }
        }
        async.consummateSeeded(armedMatched);
        installSeededAdoption(armedVerdict == AsyncWindowSearch.SeamVerdict.ADOPT
                ? "ADOPT" : "FAST-FORWARD", actualFloor);
        disarmConsummation(null);
        return true;
    }

    /**
     * ADOPTION ENTRY FROM A CLIMBABLE STANCE (owner-ratified 2026-08-21) — the one pose the armed
     * consummation's {@code entryReady} gate refuses for a reason that does not apply to it.
     *
     * <p><b>What that gate actually tests here.</b> It is called with the bot's OWN foot cell
     * ({@code entryReady(seamBot, footX(), footY(), footZ())}), so {@link Movement#atWaypoint}'s cell
     * identity is tautological and {@link Movement#deliverable} short-circuits {@code true} while airborne.
     * All that survives is {@code atWaypoint}'s SETTLE BAND, {@code [footY.00, footY.20]} — i.e. "is your y
     * cleanly seated at the bottom of your own foot cell". That band is an ARRIVAL concept (added
     * 2026-08-05: "a move's arrival pose is the PRECONDITION of the next move"), asked at the END of a move
     * where the bot has come to rest on something.
     *
     * <p><b>Why it is wrong at an adoption on a climbable.</b> A bot topped out on a vine is held by an
     * oscillation and legitimately sits near the TOP of its foot cell, not the bottom: measured
     * {@code y=151.988} with {@code footY=151}, which the band rejects by 0.788. Yet the successor itself
     * would run happily from that pose — {@code Traverse}'s own {@code failWhen} admits anything above
     * {@code runLo - STEP_ASSIST_MAX_RISE} ({@code 151.4375} here). The gate was refusing a pose the
     * MOVEMENT considers valid, so the ADOPT held armed forever while the bot alternated between the phase
     * that satisfies the boundary and the phase that satisfies this gate — never both at once.
     *
     * <p><b>Scoped so nothing else can reach it.</b> Not grounded, and on or atop a climbable — no ground
     * adoption can satisfy either clause, and the fluid movements keep their own {@code entryReady}
     * overrides untouched ({@code Swim}, {@code SprintSwim}, {@code StartSprintSwim}, {@code EndSprintSwim},
     * {@code ExitWater}, {@code RideBubbleColumn}), because this is only ever consulted AFTER theirs has
     * already declined. {@link Movement#deliverable} is still required: the run-2 drag invariant is
     * independent of the band and stays in force.
     */
    private static boolean climbStanceEntry(BotSteering b) {
        return !b.grounded()
                && (b.onClimbable() || b.climbableBelow())
                && Movement.deliverable(b, b.footX(), b.footZ());
    }

    /** Drop the §11 armed verdict ({@code reason} non-null = a premise death worth a Debug line). */
    private void disarmConsummation(String reason) {
        if (reason != null && Debug.ENABLED && armedVerdict != null) {
            OrebitCommon.LOGGER.info("[Orebit] seam-verdict {} disarmed: {}", armedVerdict, reason);
        }
        this.armedVerdict = null;
        this.armedPlan = null;
        this.armedTerminal = -1;
        this.armedMatched = -1;
    }

    /** The shared seeded-adoption install (§5/§11): the driver's sole-writer block for a consummated
     *  (or degenerate-immediate) ADOPT / FAST-FORWARD — result fields were just filled by the mailbox. */
    private void installSeededAdoption(String label, BlockPos actualFloor) {
        this.blockPlan = async.resultPlan();
        this.blockPlanStart = async.resultStart(); // the seam — see blockPlanStart's javadoc
        this.adoptedMatchedIndex = async.resultMatchedIndex(); // FAST-FORWARD's body hit; -1 on ADOPT
        this.lastPlanPartial = async.resultPartial();
        this.status = resultStatus(blockPlan, async.resultExpansions(),
                async.resultPartial(), async.resultBudgetHit(), null, // parked plans are never null
                async.resultStart());
        if (Debug.ENABLED) {
            OrebitCommon.LOGGER.info("[Orebit] seam-adopt {}: bot=({},{},{}) seam=({},{},{})",
                    label, actualFloor.getX(), actualFloor.getY(), actualFloor.getZ(),
                    async.resultStart().getX(), async.resultStart().getY(),
                    async.resultStart().getZ());
            logBlockPlan();
        }
        snapshotPlanChunks(); // adopted a seeded result — re-baseline the plan-relevance snapshot
    }

    /**
     * The §11 truncation terminal an ARMED consummation imposes on {@code followerPlan} (owner ruling
     * 2026-08-20), or {@code -1}: the follower's terminal view reads this EXACTLY (never cursor-clamped
     * — the cursor legitimately passes it at completion, which is what consummation waits for).
     */
    public int armedSeamTerminal(BlockPathPlan followerPlan) {
        return armedVerdict != null && followerPlan != null && armedPlan == followerPlan
                ? armedTerminal : -1;
    }

    /**
     * The seam waypoint index of the seeded handoff currently in play for {@code followerPlan} — a
     * PARKED seeded result's seam, or a PENDING seeded search's seam — identity-guarded, both pathing
     * modes (sync parks inline, so its parked seam matters exactly like async's), or {@code -1}. The
     * §11 uniform truncation source: the follower ends its plan at (no earlier than) this step and
     * holds centered until the result lands and consummates — the rescoped CAUTION hold's successor
     * (DESIGN-replan-handoff.md §7/§11).
     */
    public int seededSeamFor(BlockPathPlan followerPlan) {
        if (followerPlan == null || followerPlan != blockPlan) {
            return -1;
        }
        if (async.seededParked() && async.parkedSeededFrom() == followerPlan) {
            return async.parkedSeamIndex();
        }
        return async.pendingSeededSeamFor(followerPlan);
    }

    /**
     * Whether a {@link #preplan} call would actually submit — the CHEAP gate the follower tests BEFORE
     * building the pre-plan's arguments ({@code EditSnapshot.fromRemainingSteps} walks + allocates, and
     * without this gate the sync path would pay that on every settled-boundary tick past the half-window
     * mark — review finding). One pre-plan attempt per window target: {@code preplanAttemptedTarget}
     * stops a failed/parked precompute from being re-submitted every boundary tick.
     */
    public boolean wantsPreplan() {
        return executor != null && async.quiet()
                && status == PathStatus.RUNNING && skeleton != null && windowTargetPos != null
                && !async.preplanAttempted(windowTargetPos);
    }

    /**
     * P4 pre-plan hint (DESIGN-background-pathfinding.md §7), called by the follower when the current
     * window plan is more than half consumed: precompute the NEXT boundary's search from the plan's
     * predicted end cell, seeded with the remaining unapplied edits, so arrival splices with no pause.
     * No-op unless {@link #wantsPreplan} (the follower already gated on it; re-checked for safety) and
     * the prediction differs from the cell we already planned from (nothing new to compute).
     */
    public void preplan(BlockPos predictedFloor, EditSnapshot remainingEdits, int liveMode) {
        if (!wantsPreplan()) return;
        if (predictedFloor.equals(botFloor)) return;
        this.startMode = liveMode; // same per-tick pose refresh onBotMoved does; the search seeds from it
        async.markPreplanAttempt(windowTargetPos);
        submit(predictedFloor, windowTargetPos, cuboidCapBox(windowTargetPos), remainingEdits, true, false,
                false, null, -1); // S8 unchanged (DESIGN-replan-handoff.md §4) — a P4 seed is not a seam seed
    }

    /**
     * Tick-rate poll for the PLANLESS case (review finding): adoption of a plan when {@link #blockPlan}
     * is null needs NO settled boundary — there is nothing to un-adopt mid-move, and the sync path built
     * its first plan from a floating/swimming bot too. Without this, a bot that never settles (treading
     * water) could wait forever on its FIRST plan, because {@link #onBotMoved} — the only other drain —
     * is boundary-gated by the caller. The caller additionally gates this poll on PLAN-ANCHOR stability
     * (owner ruling 2026-07-30: {@code grounded || inWater || inLava || onClimbable} — any CONTROLLED
     * medium; deliberately WIDER than the arrival test's grounded/in-water pair): a treading-water,
     * lava-borne, or climbable-hanging bot still adopts at tick rate, but a mid-fall (ballistic) bot
     * defers the few ticks to touchdown — an airborne adoption would anchor the follower and frame the
     * plan's first step from a cell the bot is falling past. Also refreshes {@link #botFloor} so a
     * rejected-seam resubmit searches from the bot's LIVE cell, not the stale ctor cell.
     *
     * <p>(A CONSUMED follower plan needs no special case here — s52: plan consumption is a first-class
     * settle event in the driver, so a consumed plan drains through the normal boundary-gated
     * {@link #onBotMoved} the same tick it settles.)
     */
    public void pollWhenPlanless(BlockPos liveFloor, boolean fluidAnchor) {
        if (executor == null || blockPlan != null) return;
        if (status == PathStatus.COMPLETE || status == PathStatus.FAILED || skeleton == null) return;
        this.botFloor = liveFloor;
        this.seamPlan = null; // planless: no incumbent to seed from (DESIGN-replan-handoff.md §4) — a
        this.seamIndex = -1;  // RETRY resubmit from here searches the bot's LIVE cell, as before
        this.seamMoveInFlight = false; // §11: planless is the degenerate no-move-in-flight case
        pollPending(liveFloor, fluidAnchor);
    }

    /** Stop caring about any in-flight search (the owner cleared/replaced this plan). */
    public void cancelPending() {
        async.cancel();
    }

    /**
     * §10 prefix-integrity drop (DESIGN-replan-handoff.md §10, the follower's {@code dropWalkedPlan}
     * twin): a world edit definitely broke the walked window plan — null it (the PANIC idiom: status
     * stays RUNNING, nothing else moves) and kill every search anchored on its waypoints. The
     * {@code async.cancel()} covers BOTH the pending slot (a seam-seeded or P4 search whose start is a
     * waypoint of the dying plan — left alive it would veto the rest-gated relaunch via the
     * pending-toward-target skip, then park a result whose seam the bot will never legitimately stand
     * on) and the parked slot (same staleness, one step later). The relaunch is owned by the rest-gated
     * planless machinery: WAIT → coast to rest → rest re-anchors the settled floor → the blocked-null
     * site seeds from the live floor.
     */
    public void dropBlockPlan() {
        this.blockPlan = null;
        this.lastPlanPartial = false;
        if (status != PathStatus.RUNNING) status = PathStatus.RUNNING;
        async.cancel(); // pending AND parked die with the plan — both were anchored on its waypoints
    }

    /** Dump the returned block plan's SHAPE (see {@link SkeletonDump#logBlockPlan}). Cold, {@link Debug#ENABLED} only. */
    private void logBlockPlan() {
        SkeletonDump.logBlockPlan(this);
    }

    /** Whether the current window's block plan is a best-effort PARTIAL (didn't reach the window target). */
    public boolean isPartialPlan() {
        return lastPlanPartial;
    }

    /**
     * The skeleton crossing the failed window search proved unrealizable — the <b>first window hop the search
     * did NOT realize</b> ({@link #blameHop}, judged against the {@link #blockedRealized} snapshot) — for the
     * cascade's online repair ({@link #repairBlocked}). When the bot is {@link PathStatus#BLOCKED} the block
     * tier explored toward the window target and failed; the crossings it realized en route exonerate the
     * hops it DID make, so the blame lands on the first hop it couldn't — not blindly on
     * {@code windowStart → windowStart+1}, which mis-blacklisted a realizable first hop whenever the search
     * died deeper in the window (the up-cliff give-up loop). Fills {@code out[0]=fromKey, out[1]=toKey} and
     * returns {@code true}; returns {@code false} when there is no onward hop to blame ({@link
     * #blamedHopIndex} = -1 — no skeleton, or no hop between the window start and its target: a genuine
     * give-up).
     */
    public boolean blockedHop(long[] out) {
        final int hop = blamedHopIndex();
        if (hop < 0) return false;
        if (RegionPathfinder.isVirtualGoal(skeleton.fragmentId(hop + 1))) {
            // The blamed hop is (approach → V). The row must be the FULL approach node key so it equals the one
            // relaxVirtualGoal checks (parity — NOTES-region-tier.md §1.1): region + fragment +
            // entry-face + from-fragment. The skeleton stores only region+fragment per step, so entry-face is
            // reconstructed geometrically and from-fragment is the previous step's fragment (VIRTUAL_START_FRAG
            // at the root). This makes (A|from=S → V) independently blameable from (A|from=staircase → V) when
            // A==G — the cliff false-give-up fix. A corner-cut predecessor stamps fromFrag=CORNER_FRAG into
            // the approach row — intended (corner-crossing R28: search-side parity holds because D's live
            // row carries the same, and the row is journey-scoped by onBlocked's virtualGoalHop guard).
            out[0] = RegionPathfinder.approachRowKeyForStep(skeleton, hop);
            out[1] = RegionPathfinder.fragmentNodeKey(skeleton.rx(hop + 1), skeleton.ry(hop + 1),
                    skeleton.rz(hop + 1), skeleton.fragmentId(hop + 1));
            return true;
        }
        collapseCornerRun(skeleton, hop, out);
        return true;
    }

    /**
     * CORNER-RUN COLLAPSE (corner-crossing §4.6, R17): the skeleton says A.f → B.CORNER [→ C.CORNER]
     * → D.f' but the invalidation must say (A, fragA) → (D, fragD). The ONE shared walk lives on
     * {@link RegionPathPlan#collapsedHopKeys} so the escalation blame
     * ({@code HierarchicalRegionPlan.blameTubeConfined}) can never drift from this block-tier site; this
     * package-private static remains as the blame-parity tests' seam and simply delegates.
     */
    static void collapseCornerRun(RegionPathPlan skeleton, int hop, long[] out) {
        skeleton.collapsedHopKeys(hop, out);
    }

    /** The hop index {@link #blockedHop} would blame right now, or {@code -1} (no onward hop) — the single
     *  dispatch both it and the driver's debug line read, so the two can't drift. */
    public int blamedHopIndex() {
        if (skeleton == null) return -1;
        if (blockedRealized == null) {
            // No realized data travelled with this BLOCKED result (defensive / legacy): positional.
            return (windowStart + 1 < skeleton.size()) ? windowStart : -1;
        }
        return blameHop(skeleton, blockedWindowStart, blockedTargetStep, blockedRealized, minY,
                blockedStartFloor == null ? NO_START_REGION : startRegionRawKey(blockedStartFloor));
    }

    /** Sentinel for "the failing search's start region is unknown" — the blame walk then keeps the
     *  historical windowStart behaviour. */
    static final long NO_START_REGION = Long.MIN_VALUE;

    /** The raw {@code cell>>4} region key of a search-start floor cell — the same key space
     *  {@link #rawRegionKey} converts skeleton steps into (the realized-set convention), so the blame
     *  walk's start-region compare happens at region granularity in one key space. */
    private static long startRegionRawKey(BlockPos floor) {
        return RegionAddress.packLevelKey(floor.getX() >> RegionAddress.LEAF_BITS,
                floor.getY() >> RegionAddress.LEAF_BITS, floor.getZ() >> RegionAddress.LEAF_BITS);
    }

    /**
     * Fix-A blame walk: the first window hop the failed search did NOT realize. Hop {@code i} is the edge
     * {@code skeleton[i] → skeleton[i+1]}; hops considered are {@code i} in {@code [windowStart,
     * targetStep-1]} — up to and including the hop INTO the step whose portal/goal was the window target,
     * never beyond (the search was not asked to reach later hops). Per hop:
     * <ul>
     *   <li>approach → V (virtual goal): realized ONLY if the goal cell itself was reached, which would have
     *       been a FOUND — on a BLOCKED result it is unrealized by definition;</li>
     *   <li>intra-region hop (fragA → fragB of one region, a dig): region-pair realization cannot see
     *       fragments — judge the observable half: no realized crossing EXITS the region ⇒ the search never
     *       got past the dig, blame it; otherwise treat as realized at this granularity and walk on;</li>
     *   <li>inter-region hop: realized iff the directed region pair is in the search's realized set.</li>
     * </ul>
     * All hops realized but the target cell unreached ⇒ blame the hop INTO the target step — reaching the
     * crossing's far side elsewhere on the face did not yield a route to its committed portal, so the
     * committed {@code (region, fragment)} hop is unrealizable as routed (and a hop is always blamed, which
     * closes the give-up loop). Returns the blamed hop index, or {@code -1} = no onward hop.
     *
     * <p><b>Start-position blind spot (the treadmill fix).</b> {@code startRegionRawKey} is the failing
     * search's START region (raw {@code cell>>4} key; {@link #NO_START_REGION} = unknown). A block search
     * that starts inside skeleton region {@code S_k} can never "realize" any hop {@code i → i+1} with
     * {@code i+1 ≤ k}: {@code collectRealizedCrossings} walks surviving cameFrom edges, which grow OUTWARD
     * from the start, so a boundary edge INTO the start's own region (or any region behind it on the
     * skeleton) never survives. Blaming such a hop recorded a PROOF row against a crossing the bot had just
     * physically walked (the wall-repro treadmill: the valid lateral {@code S0→S1} blamed every cycle while
     * the truly-unrealizable ascent was never reached). So: find {@code k} = the LAST index in
     * {@code [windowStart..hi]} whose skeleton region equals the start region and begin the walk at
     * {@code max(windowStart, k)}; hops ending at-or-before {@code k} are never blamed. {@code k == hi}
     * (the start region IS the target step's region) ⇒ every window hop ends at-or-before the start —
     * the failure is intra-region, no crossing is blamable ⇒ {@code -1} (give-up semantics, NOT the
     * hop-into-target fallback, which would blacklist a crossing behind the bot). An off-skeleton start
     * (no {@code k}) keeps the historical windowStart walk. The V-hop rule and the all-realized fallback
     * are unchanged within the (possibly shortened) walk.
     */
    static int blameHop(RegionPathPlan sk, int windowStart, int targetStep, long[] realized, int minY) {
        return blameHop(sk, windowStart, targetStep, realized, minY, NO_START_REGION);
    }

    /** See {@link #blameHop(RegionPathPlan, int, int, long[], int)} — this form carries the failing
     *  search's start-region raw key ({@link #NO_START_REGION} = unknown ⇒ identical behaviour). */
    static int blameHop(RegionPathPlan sk, int windowStart, int targetStep, long[] realized, int minY,
                        long startRegionRawKey) {
        final int hi = Math.min(targetStep, sk.size() - 1);
        if (hi <= windowStart) return -1;                       // no onward hop — genuine give-up
        int lo = windowStart;
        if (startRegionRawKey != NO_START_REGION) {
            // Start-position blind spot: begin at the LAST window step sharing the search-start's region —
            // hops ending at-or-before it are unrealizable-by-construction (see the javadoc above).
            for (int i = hi; i >= windowStart; i--) {
                // The unreached virtual goal V is NOT a physical bot position — it shares the goal region, so when
                // A==G it would falsely match the start region and anchor the walk at V (⇒ lo==hi ⇒ -1 give-up).
                // Skip virtual fragments so the anchor lands on the real start step and the V-hop below fires
                // (the A==G false-give-up fix — NOTES-region-tier.md §2). A corner-cut chain step is skipped
                // for the SAME reason (corner-crossing §4.6): it sits in a real region B the bot can never
                // occupy, so anchoring on it when the search started in B mis-scopes the walk.
                // KNOWN RESIDUAL (review 2026-08-29, dim2 F4): when the failing search STARTS inside B's
                // region (B holds real floor elsewhere; the skeleton names B only via this corner step),
                // the anchor falls back to windowStart and blame can land on A→B — unrealizable by
                // construction — collapsing to an (A, D) row whose FROM (A) is NOT the start region, so it
                // escapes startScoped and records durably against a possibly-realizable corner. The V-skip's
                // analog is journey-scoped by virtualGoalHop; no corner equivalent exists. Rare geometry,
                // the skip itself is §4.6-mandated, and §5.1's NEAR/FAR split is the instrument that would
                // surface it — recorded here so the silence is not read as an oversight.
                if (RegionPathfinder.isVirtualGoal(sk.fragmentId(i))
                        || RegionPathfinder.isCornerCut(sk.fragmentId(i))) continue;
                if (rawRegionKey(sk, i, minY) == startRegionRawKey) {
                    lo = i;
                    break;
                }
            }
            if (lo >= hi) {
                return -1; // the start region IS the target step's region — no onward crossing to blame
            }
        }
        for (int i = lo; i < hi; i++) {
            if (RegionPathfinder.isVirtualGoal(sk.fragmentId(i + 1))) return i;
            final long fromRaw = rawRegionKey(sk, i, minY);
            final boolean sameRegion = sk.rx(i) == sk.rx(i + 1) && sk.ry(i) == sk.ry(i + 1)
                    && sk.rz(i) == sk.rz(i + 1);
            if (sameRegion) {
                if (!anyExitRealized(realized, fromRaw)) return i;
                continue;
            }
            if (!containsEdge(realized, fromRaw, rawRegionKey(sk, i + 1, minY))) return i;
        }
        return hi - 1;
    }

    /** Skeleton step → the raw cell>>4 region key {@link BlockPathfinder}'s realized set uses. rx/rz are
     *  identical to region coords (plain >>4); only ry is minY-rebased, so convert via the region's base
     *  world-Y: {@code (minY + (ry << LEAF_BITS)) >> LEAF_BITS} — exact for MC's section-aligned minY. */
    private static long rawRegionKey(RegionPathPlan sk, int step, int minY) {
        final int rawRy = (minY + (sk.ry(step) << RegionAddress.LEAF_BITS)) >> RegionAddress.LEAF_BITS;
        return RegionAddress.packLevelKey(sk.rx(step), rawRy, sk.rz(step));
    }

    private static boolean containsEdge(long[] realized, long from, long to) {
        for (int i = 0; i < realized.length; i += 2) {
            if (realized[i] == from && realized[i + 1] == to) return true;
        }
        return false;
    }

    private static boolean anyExitRealized(long[] realized, long from) {
        for (int i = 0; i < realized.length; i += 2) {
            if (realized[i] == from) return true;
        }
        return false;
    }

    /**
     * Cascade online repair (HPA-CASCADE.md §6): the driver is {@link PathStatus#BLOCKED}, so feed the bot's
     * current L0 skeleton hop ({@link #blockedHop}) to the cascade, which blacklists it and <b>escalates up the
     * hierarchy</b> — re-planning each level until one routes around the dead crossing. On success the repaired L0
     * skeleton is swapped in and the block window reset; returns {@code true} (a route remains). Returns
     * {@code false} (and sets FAILED) when there is no hop to blame or every level is exhausted — the bot then
     * gives up. The cascade owns its per-level blacklists.
     */
    public boolean repairBlocked() {
        if (!blockedHop(repairHopScratch)) {
            // No onward hop to blame — the region A* found no route at all (or the bot is in the goal region
            // but can't reach the goal cell). A give-up: harvest the boxed-in proof to back it (§4.2).
            harvestBoxedInProof();
            return false;
        }
        // The 4th arg is the HONEST search start — the failing search's snapshotted from-floor
        // (blockedStartFloor; == botFloor in sync mode, possibly older in async), which scopes the cascade's
        // #5 record decision: a blame whose FROM region is the search's own start region is proven only for
        // the caps-connected component the search started in, so it stays in this plan's blacklists and is
        // never recorded to the dimension's crossing memory. botFloor stays the re-plan origin.
        final boolean rerouted = hier.onBlocked(repairHopScratch[0], repairHopScratch[1], botFloor,
                blockedStartFloor != null ? blockedStartFloor : botFloor);
        // Telemetry: the escalation re-planned regions — count a flood if its last region search tripped the
        // guard (pure observation; onBlocked ran on this tick thread, so its lastWasFlood is current).
        if (stats != null && RegionPathfinder.lastWasFlood()) stats.onRegionFlood();
        if (!rerouted) {
            // The cascade exhausted its repairs at every level — the canonical region-tier give-up. Run the
            // ONE harvest-mode full-drain flood to back the honest FAILED with a structural boxed-in proof (§4.2).
            harvestBoxedInProof();
            this.skeleton = null;
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            return false;
        }
        this.skeleton = hier.l0Skeleton();
        dumpSkeleton("rederived");
        if (skeleton == null || skeleton.isEmpty()) {
            harvestBoxedInProof();
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            return false;
        }
        resetWindow();
        replanBlock(true); // post-repair resubmit: the plan just proved wrong somewhere
        return true;
    }

    /**
     * Whether {@code goal} lies in the SAME level-0 region this plan was built for. The driver commits to a
     * skeleton and only rebuilds it (a region replan) when the goal enters a NEW region — a goal that merely
     * shuffles WITHIN its region (the owner taking a step while the bot is far away on a long walk-around)
     * must NOT recompute the skeleton, or near-equal-cost region routes flip-flop and the bot oscillates. The
     * final-window block target tracks the live goal cell via {@link #refreshWindow}, so within-region motion
     * still lands the bot on the owner without a skeleton churn.
     */
    public boolean sameGoalRegion(BlockPos goal) {
        return RegionAddress.regionX(goal.getX(), 0) == goalRX
                && RegionAddress.regionY(goal.getY(), 0, minY) == goalRY
                && RegionAddress.regionZ(goal.getZ(), 0) == goalRZ;
    }

    /** The goal FLOOR cell this plan was built for (fixed at construction — the driver compares it
     *  against the LIVE goal cell for the exact-tolerance moved-goal rebuild trigger; see
     *  {@code BotNavigator.driveToward}'s newRegionGoal condition). */
    public BlockPos goalFloor() {
        return goalFloor;
    }

    /**
     * A one-line description of the SPLICE BASELINE this plan seeds every windowed search with — diagnostic
     * only ({@code Debug} paths; never drives behavior).
     *
     * <p><b>Why it is worth printing (2026-08-04).</b> The baseline is {@code final}, set at construction and
     * documented "null for every non-spliced plan", so a {@code refreshWindow} re-search does NOT rebuild
     * it. That makes "the re-search inherited the previous plan's promised edits" a structurally impossible
     * explanation for a route change — but only if you have read this field's lifecycle, which is exactly
     * the kind of assumption that ought to be an observation instead. Printing {@code none} on every
     * ordinary plan is the point: it closes off a whole family of wrong theories at a glance, and makes the
     * rare genuinely-spliced search (the portal arc) visible when it does occur.
     */
    public String baselineSummary() {
        if (baseline == null) return "none";
        if (baseline.isEmpty()) return "empty";
        return "breaks=" + baseline.breakCount()
                + " places=" + baseline.placeCount()
                + " doors=" + baseline.doorSetCount();
    }

    /**
     * Recompute the CURRENT window's block plan from where the bot is now, WITHOUT touching the committed
     * skeleton — the block-level refresh the driver runs when its block path is consumed (advance toward the
     * same window target) or periodically (terrain changed under the window). This is the "shift the window,
     * don't replan everything" half: the skeleton is a committed S1→…→Sn route; only the local block path
     * between committed waypoints is re-searched. No-op once COMPLETE/FAILED or when no skeleton was produced.
     *
     * <p>Drops any parked precompute first — P4 or seam-seeded alike (owner ruling 2026-07-30, review
     * finding; a seeded result's staleness argument is identical — DESIGN-replan-handoff.md §5): a refresh fires
     * only on a CONSUMED or terrain-impacted plan, and this same tick's {@code onBotMoved} already gave the
     * parked plan its adoption test — so a still-parked plan here is one the settled bot can never adopt
     * (it settled off the predicted start and off the parked route) or one computed against stale terrain.
     * Without the drop, {@code replanBlock}'s parked-for-target early-return vetoes every resubmit while
     * the park slot vetoes adoption — a permanent silent WAIT. Dropping restores the seam contract's
     * recovery (never repair a rejected plan; search again from the actual floor). NOTE the drop is
     * deliberately CONSERVATIVE on the approach path: a MID-WALK refresh can fire too (the 40-tick
     * recheck + {@code planImpacted} — which the bot's OWN executed edits bump), and it discards a
     * still-healthy precompute there; the cost is one boundary re-search (the attempt guard means no
     * re-preplan for that target), never a wrong path — adopting a pre-terrain-change precompute is
     * exactly the staleness the refresh exists to eliminate.
     */
    public void refreshWindow() {
        refreshWindow(false);
    }

    /** @param suspect whether the refresh was terrain-impacted (vs a consumed-plan advance) —
     *  see {@link #replanBlock(boolean)}. */
    public void refreshWindow(boolean suspect) {
        if (skeleton == null || status == PathStatus.COMPLETE || status == PathStatus.FAILED) return;
        async.dropParked();
        replanBlock(suspect);
    }

    /**
     * The §10 U1 PROMPT plan-impacted replan (DESIGN-replan-handoff.md §10): the follower's edit-epoch
     * scan just proved a LATER step of the walking plan move-invalidated (U4), so the S5 plan-impacted
     * re-search fires THE SAME TICK instead of waiting out the 40-tick terrain-recheck debounce — the
     * exact {@link #refreshWindow refreshWindow(true)} path the debounced recheck drives, with a FRESH
     * seam handoff (the caller's U2-clamped {@code horizonSeamIndex} walk at the just-advanced epoch, so
     * the seeded search can never launch from at-or-past the broken step; the last boundary's stored
     * handoff would be stale mid-move). Afterwards the plan-relevance snapshot is re-baselined —
     * {@link #snapshotPlanChunks} — so the debounced backstop cannot double-fire on this same edit
     * (the seeded/async launch paths inside {@link #replanBlock} return before the normal install-time
     * re-baseline; on the sync non-seeded install the extra call is an idempotent no-op).
     *
     * <p>Parameters mirror {@link #onBotMoved}'s seam handoff: {@code botFloor} is the follower's
     * settled anchor (the live-anchor reader set — targeting, cap box, forward-slide tolerance — keeps
     * its existing split from the search start).
     */
    public void promptImpactedReplan(BlockPos botFloor, int startMode, BotSteering bot,
                                     BlockPathPlan followerPlan, int seamIndex, int firstUneditedStep,
                                     int followerCursor) {
        this.seamBot = bot;
        this.seamPlan = followerPlan;
        this.seamIndex = seamIndex;
        this.seamFirstUnedited = firstUneditedStep;
        if (Debug.ENABLED && armedVerdict != null && followerCursor != this.seamCursor) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] seamCursor {}→{} (promptImpactedReplan) armedTerminal={}",
                    this.seamCursor, followerCursor, armedTerminal);
        }
        this.seamCursor = followerCursor;
        this.seamMoveInFlight = true; // fired mid-steer, by construction (the U1 prompt path)
        this.botFloor = botFloor;
        this.startMode = startMode;
        refreshWindow(true);
        snapshotPlanChunks(); // re-baseline: the debounced backstop must not re-fire on this same edit
    }

    /**
     * The cuboid GROWTH-cap box for the current search (NOT a search confinement): a world AABB around the bot
     * and the window target, expanded by {@link #CUBOID_CAP_MARGIN}. Bounds how large a macro-cuboid can grow
     * (so a flat world can't grow one unbounded) while the search itself stays unconfined. The target is ≤ a
     * few regions away, so this box is always modest.
     */
    private RegionBound cuboidCapBox(BlockPos target) {
        final int m = CUBOID_CAP_MARGIN;
        final int minX = Math.min(botFloor.getX(), target.getX()) - m;
        final int maxX = Math.max(botFloor.getX(), target.getX()) + m;
        final int minBY = Math.min(botFloor.getY(), target.getY()) - m;
        final int maxBY = Math.max(botFloor.getY(), target.getY()) + m;
        final int minZ = Math.min(botFloor.getZ(), target.getZ()) - m;
        final int maxZ = Math.max(botFloor.getZ(), target.getZ()) + m;
        return new RegionBound(minX, maxX, minBY, maxBY, minZ, maxZ);
    }

    /**
     * The region-informed heuristic field for a block search toward {@code target}, rebuilt ONLY when the root
     * changed (the window target moved). Called by every search-launch site — the sync {@link #replanBlock}
     * findPath and the async {@link #submit} (boundary replan and P4 pre-plan alike) — so a search is never
     * handed a field rooted at a cell other than its own goal: a final-goal-rooted gradient made window
     * searches flood 58–67k nodes toward the wrong attractor and inch their PARTIAL commit points goalward
     * (the 2026-07-06 incident). The reverse Dijkstra is bounded to the botFloor↔target region box (+3 pad) —
     * the same box logic the old ctor build used, at window scale. ~6 µs per build at replan cadence; an
     * unchanged root is one BlockPos equals. When the goal is in-window the window target IS {@code goalFloor},
     * so that case matches the old goal-rooted behaviour by construction.
     *
     * <p><b>Thread safety</b>: {@link RegionCostField} is write-once-read-many; a rebuild constructs a NEW
     * instance and swaps the reference — never mutates the old one — so an in-flight async worker keeps
     * reading the field its {@link SearchRequest} snapshotted, safely. Build failure ⇒ {@code null} (the
     * plain-octile fallback), cached under the same root so a failing target isn't re-attempted every replan.
     */
    private RegionCostField regionFieldFor(BlockPos target) {
        if (target.equals(fieldRoot)) {
            return regionField;
        }
        RegionCostField field;
        try {
            final int brx = RegionAddress.regionX(botFloor.getX(), 0);
            final int bry = RegionAddress.regionY(botFloor.getY(), 0, minY);
            final int brz = RegionAddress.regionZ(botFloor.getZ(), 0);
            final RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(
                    brx, bry, brz,
                    RegionAddress.regionX(target.getX(), 0),
                    RegionAddress.regionY(target.getY(), 0, minY),
                    RegionAddress.regionZ(target.getZ(), 0), 3);
            field = RegionPathfinder.costToGoalField(regionGrid, minY, target, botFloor,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), regionMine,
                    regionPlace, box);
        } catch (Throwable t) {
            field = null; // any failure ⇒ octile fallback for searches toward this root
        }
        this.regionField = field;
        this.fieldRoot = target;
        return field;
    }

    /**
     * Boxed-in negative-reachability harvest at the region-tier GIVE-UP point (#4 Increment 1,
     * DESIGN-boxed-in-reachability §4.2) — the ONLY site the harvest-mode (full-drain) flood fires. The
     * cascade has exhausted its repairs, so run ONE reverse flood rooted at the current window target (the cell
     * the search failed to reach — FINDINGS §1: read the field's own window-target root, never the journey
     * goal) with the harvest flag, which suppresses the fat-skeleton early exit so the flood drains fully. If
     * the flood was CLOSED, {@link RegionCostField#markInfinite} flags every in-box built-but-unreached region
     * INFINITE; {@link #boxedInProven} then records whether the BOT'S OWN region is INFINITE — i.e. provably
     * cannot reach the target under these caps (a STRUCTURAL boxed-in, distinct from a budget artifact). The
     * harvested field is cached as {@link #regionField} so the block-A* hard reject ({@code isBlocked}) has
     * teeth on any continuation search this journey. Journey-scoped: NO RegionCrossingMemory / persistence
     * write (owner ruling 2026-07-25). Any failure is swallowed — the give-up proceeds regardless.
     */
    private void harvestBoxedInProof() {
        // Reactive backstop: root at the failing search's window target (FINDINGS §1) and PERSIST the harvested
        // field so a continuation search sees the INFINITE prune (the historical give-up behaviour, unchanged).
        harvestBoxedIn(windowTargetPos != null ? windowTargetPos : goalFloor, true);
    }

    /**
     * Run the harvest-mode (full-drain) goal-rooted flood rooted at {@code target} and record whether the
     * BOT'S OWN region is provably goal-disconnected under these caps ({@code closedFlood ∧ isBlocked(bot)});
     * returns that verdict. The reactive give-up backstop ({@link #harvestBoxedInProof}) roots it at the failing
     * search's window target (FINDINGS §1) and PERSISTS the field; the PROACTIVE check now uses the multi-level
     * {@link RegionPathfinder#isSealedWithin} scan ({@link #maybeProactiveBoxedIn}) instead of this L0 flood.
     * When {@code persistField} the harvested {@link RegionCostField} is cached as
     * {@link #regionField}/{@link #fieldRoot} so the block-A* hard reject ({@code isBlocked}) has teeth on any
     * continuation search; the PROACTIVE caller passes {@code false} so a NOT-boxed verdict leaves every
     * subsequent block search byte-identical (a persisted full-drain field with an INFINITE set would perturb
     * both the heuristic's floored-optimistic reads and the #5 reject — INV BR-3). Always records
     * {@link #boxedInProven}. Any failure is swallowed (a boxed-in proof backs a give-up, never gates it).
     */
    private boolean harvestBoxedIn(BlockPos target, boolean persistField) {
        if (target == null || botFloor == null) return false;
        try {
            final int brx = RegionAddress.regionX(botFloor.getX(), 0);
            final int bry = RegionAddress.regionY(botFloor.getY(), 0, minY);
            final int brz = RegionAddress.regionZ(botFloor.getZ(), 0);
            final RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(
                    brx, bry, brz,
                    RegionAddress.regionX(target.getX(), 0),
                    RegionAddress.regionY(target.getY(), 0, minY),
                    RegionAddress.regionZ(target.getZ(), 0), 3);
            final RegionCostField field = RegionPathfinder.costToGoalField(regionGrid, minY, target, botFloor,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), regionMine, regionPlace, box, true);
            if (persistField) {
                this.regionField = field;
                this.fieldRoot = target;
            }
            // The reverse flood is rooted at the TARGET, so a region is INFINITE iff it cannot reach the target.
            // The bot stands at botFloor: if ITS region is INFINITE, the bot is provably boxed-in from the target.
            this.boxedInProven = field != null
                    && field.isBlocked(botFloor.getX(), botFloor.getY(), botFloor.getZ());
            return this.boxedInProven;
        } catch (Throwable t) {
            // A boxed-in proof is a diagnostic backing for the give-up, never a precondition for it: swallow.
            return false;
        }
    }

    /**
     * PROACTIVE boxed-in check (#4, DESIGN-boxed-in-reachability §14 — the multi-level rework of Increment 1's
     * L0-only reactive trigger). The reactive harvest ({@link #harvestBoxedInProof}) only fires at a region-tier
     * GIVE-UP; but a goal walled off by BUILT solid with optimistic-UNBUILT terrain between never reaches a
     * give-up — the unbuilt cells read as passable AIR, so the forward block search returns a partial forever
     * and the bot wanders. This check runs a goal-rooted sealed-probe EAGERLY (at plan construction and, gated,
     * as the bot approaches) so the honest "walled off" verdict is reached without wandering.
     *
     * <p><b>Multi-level scan.</b> A tomb is not inherently level-0: an air pocket large enough to flood L0 but
     * ringed by solid is a seal visible only at a COARSER level. So the check scans {@code MAX_COARSE_LEVEL → 0},
     * flooding the goal's caps-legal component in a SMALL box centered on the goal at each level
     * ({@link RegionPathfinder#isSealedWithin}). A CLOSED flood at any level proves the goal SEALED within that
     * box. The box is centered on the GOAL, not spanned across {@code around(bot,goal)}, so the cost is
     * distance-INDEPENDENT: a 100k-away goal probes as cheaply as a near one, sidestepping the L0 bot↔goal
     * array-bill. The proof is monotone (a coarse seal admits no finer escape), so the FIRST close wins; a
     * not-closed level means the component reaches beyond its small box, so descend — a finer, smaller seal may
     * still close. L0 is the precise floor. A seal larger than the L6 box (~7k blocks) is left to the give-up
     * backstop / super-long-range handling (#8).
     *
     * <p><b>The bot's position is NOT irrelevant</b> — this Javadoc used to claim it was, and the claim cost a
     * bug (fixed 2026-08-15). Sealed means nothing crosses the seal, so it implies unreachable only for a bot
     * OUTSIDE it; a bot in the same chamber as the goal walks right over. So {@code botFloor} is threaded into
     * the probe as its inside observer and a component containing the bot never reports sealed. Cheap in the
     * right direction too: the reachable case now exits the flood the moment it touches the bot's fragment,
     * where before it drained the whole box to conclude the opposite. Before the bot's position is known
     * ({@code botFloor == null}) no probe is passed, which is the pre-fix behaviour.
     *
     * <p>Gate 1 — the goal region must be BUILT at L0 (an unbuilt goal is OPTIMISTIC, §6: never claim boxed-in
     * over unclassified terrain; MC only builds nav sections for loaded chunks). Gate 2 — re-run only when the
     * goal's sealing NEIGHBOURHOOD changed ({@link #goalNeighbourhoodBuildSignal}): the seal lives in the goal
     * column + its 8 neighbours, whose monotone versions strictly advance whenever a seal could complete, so a
     * static neighbourhood pays no flood.
     *
     * <p>On a boxed-in verdict, sets the same honest FAILED triple the reactive give-up sets (skeleton/blockPlan
     * null, status FAILED, {@link #boxedInProven}=true) and returns {@code true}; otherwise leaves the plan
     * exactly as it was and returns {@code false}. VERDICT-ONLY — no field persisted, so a NOT-boxed verdict
     * leaves every subsequent block search byte-identical (INV BR-3).
     */
    private boolean maybeProactiveBoxedIn() {
        // WHY THE DECLINE PATHS LOG (2026-08-25). Until now ONLY the proof branch was audible, and the
        // comment on it says exactly why that was wrong -- "the drifted-world vine-jungle false positive was
        // invisible precisely because this branch FAILed silently". The same reasoning applies to the other
        // side, and it bit: BoxedInCourse's `tomb` scenario reports
        // "gave up but boxedInProven=false (not a proactive seal proof)" with NOTHING in the run log, so
        // there is no way to tell whether the scan bailed at a gate, ran all seven levels and declined, or
        // threw seven times into the swallowing catch below. Three very different bugs, one silence.
        // Verbose-gated because a decline is the overwhelmingly common outcome on every ordinary journey;
        // the swallowed Throwable is NOT gated, because a hidden exception is never routine.
        if (goalFloor == null) {
            if (Debug.VERBOSE) OrebitCommon.LOGGER.info("[Orebit] boxed-in scan: skipped (no goal floor)");
            return false;
        }
        regionGrid.ensureLeaf(goalRX, goalRY, goalRZ);
        if (regionGrid.fragmentRecord(0, goalRX, goalRY, goalRZ) == null) {
            if (Debug.VERBOSE) {
                OrebitCommon.LOGGER.info("[Orebit] boxed-in scan: skipped (goal region {},{},{} UNBUILT at L0"
                        + " -- optimistic; a later build re-forces the check)", goalRX, goalRY, goalRZ);
            }
            proactiveSignalValid = false; // goal unbuilt ⇒ optimistic; a later build re-forces the first check
            return false;
        }
        final long sig = goalNeighbourhoodBuildSignal();
        if (proactiveSignalValid && sig == lastProactiveSignal) {
            if (Debug.VERBOSE) {
                OrebitCommon.LOGGER.info("[Orebit] boxed-in scan: skipped (goal neighbourhood signal {}"
                        + " unchanged -- no seal could have completed)", sig);
            }
            return false; // nothing in the goal's sealing neighbourhood changed since the last check
        }
        lastProactiveSignal = sig;
        proactiveSignalValid = true;
        // COHERENCE at the probe's anchors (owner ruling 2026-08-18 — the ReplanCourse reversal false seal).
        // The probe's two seeds (the goal anchor and the inside observer) resolve their fragment by
        // re-flooding the LIVE NavSection (containedFragment → startFragmentByFlood), while the flood walks
        // the STORED RegionFragments records — and an edit whose budgeted HpaMaintenance drain hasn't
        // reached the leaf yet leaves the two a GENERATION apart. A fresh label read against a stale face
        // table has no faces at all (touchesFace on an out-of-range index), so the flood "closes" at the
        // seed and manufactures a sealed verdict: goal frag=2 against a 2-fragment record, convicted by the
        // rtrace SEAL-VERDICT dump 2026-08-18. Maintenance stays BUDGETED for the bulk storms it exists for
        // (24 regions/chunk × a view-distance edge of chunks); THIS is bounded — exactly the two anchored
        // leaves, once per genuine probe (post-signal-gate) — so force them to the live generation here.
        regionGrid.rebuildLeaf(goalRX, goalRY, goalRZ);
        if (botFloor != null) {
            regionGrid.rebuildLeaf(RegionAddress.regionX(botFloor.getX(), 0),
                    RegionAddress.regionY(botFloor.getY(), 0, minY),
                    RegionAddress.regionZ(botFloor.getZ(), 0));
        }
        for (int lvl = RegionAddress.MAX_COARSE_LEVEL; lvl >= 0; lvl--) {
            boolean sealed;
            try {
                sealed = RegionPathfinder.isSealedWithin(regionGrid, minY, goalFloor, botFloor, lvl,
                        caps.boxedInScanRadius(),
                        caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), regionMine, regionPlace);
            } catch (Throwable t) {
                // NEVER silent: the probe backing out is fine, but doing so because it EXCEEDED is a defect
                // hiding behind a give-up that looks deliberate.
                OrebitCommon.LOGGER.warn("[Orebit] boxed-in scan: level {} THREW ({}) -- treating as"
                        + " inconclusive and descending", lvl, t.toString());
                sealed = false; // a sealed-probe backs a give-up, never gates it: inconclusive ⇒ descend/proceed
            }
            if (sealed) {
                // A proof-of-sealing is a rare, journey-terminal verdict — name it in the server log with
                // enough to audit it in one glance (the drifted-world vine-jungle false positive was
                // invisible precisely because this branch FAILed silently; owner ruling 2026-07-31).
                OrebitCommon.LOGGER.info(
                        "[Orebit] boxed-in PROVEN: goal={} sealed at level {} (radius {}, bot={}, canBreak={} "
                                + "canPlace={}) — giving up honestly",
                        goalFloor, lvl, caps.boxedInScanRadius(), botFloor, caps.canBreak(), caps.canPlace());
                this.boxedInProven = true;
                this.skeleton = null;
                this.blockPlan = null;
                this.status = PathStatus.FAILED;
                return true;
            }
        }
        if (Debug.VERBOSE) {
            OrebitCommon.LOGGER.info("[Orebit] boxed-in scan: goal={} NOT sealed at any level {}..0"
                    + " (radius {}, bot={}, canBreak={} canPlace={}) — no proof, give-up (if any) will be"
                    + " reactive", goalFloor, RegionAddress.MAX_COARSE_LEVEL, caps.boxedInScanRadius(),
                    botFloor, caps.canBreak(), caps.canPlace());
        }
        return false;
    }

    /** Sum of the {@link NavGridUpdater#chunkVersion monotone chunk-versions} of the 3×3 chunk columns centred
     *  on the goal region's column (level-0 region X/Z == chunk X/Z) — the goal's sealing-perimeter build
     *  signal for {@link #maybeProactiveBoxedIn}. Strictly increases on any nav (re)build or block edit in that
     *  neighbourhood (every mutation site bumps the column version via {@link NavGridUpdater#bumpChunk}), so an
     *  advance is a sufficient re-check trigger and a steady sum means no seal could have completed. */
    private long goalNeighbourhoodBuildSignal() {
        long sig = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                sig += NavGridUpdater.chunkVersion(level, NavStore.key(goalRX + dx, goalRZ + dz)) & 0xFFFFFFFFL;
            }
        }
        return sig;
    }

    /** Whether the last region-tier give-up harvested a boxed-in proof of the bot's own region
     *  (see {@link #boxedInProven}) — telemetry / tests (#4 Increment 1). */
    public boolean boxedInProven() {
        return boxedInProven;
    }

    /**
     * The corridor box for the current window (HPA-IMPLEMENTATION.md §9): the world-space AABB enclosing the
     * window's skeleton regions (and the start + target cells), expanded by {@link #CORRIDOR_MARGIN}
     * horizontally and {@link #CORRIDOR_VMARGIN} vertically. The block-A* rejects candidates outside it, so
     * the search stays on the skeleton (capping the pillar flood) while the one-region margin still admits a
     * beneficial dip into an adjacent region. Pure {@link RegionAddress} integer math; one small object per
     * replan (infrequent), no per-candidate allocation (the box test is six int compares — {@link RegionBound}).
     */
    private RegionBound corridorBound(BlockPos target) {
        final int last = windowLast();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minBY = Integer.MAX_VALUE, maxBY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = windowStart; i <= last; i++) {
            final int x0 = skeleton.rx(i) << RegionAddress.LEAF_BITS;
            final int z0 = skeleton.rz(i) << RegionAddress.LEAF_BITS;
            final int y0 = minY + (skeleton.ry(i) << RegionAddress.LEAF_BITS);
            if (x0 < minX) minX = x0;
            if (x0 + RegionAddress.LEAF_SIZE - 1 > maxX) maxX = x0 + RegionAddress.LEAF_SIZE - 1;
            if (z0 < minZ) minZ = z0;
            if (z0 + RegionAddress.LEAF_SIZE - 1 > maxZ) maxZ = z0 + RegionAddress.LEAF_SIZE - 1;
            if (y0 < minBY) minBY = y0;
            if (y0 + RegionAddress.LEAF_SIZE - 1 > maxBY) maxBY = y0 + RegionAddress.LEAF_SIZE - 1;
        }
        // Belt-and-suspenders: the start and target are already in the window corridor, but include them so
        // the goal tolerance (±1 horizontal, ±2 vertical of the target) is always inside the box.
        minX = Math.min(minX, Math.min(botFloor.getX(), target.getX()));
        maxX = Math.max(maxX, Math.max(botFloor.getX(), target.getX()));
        minBY = Math.min(minBY, Math.min(botFloor.getY(), target.getY()));
        maxBY = Math.max(maxBY, Math.max(botFloor.getY(), target.getY()));
        minZ = Math.min(minZ, Math.min(botFloor.getZ(), target.getZ()));
        maxZ = Math.max(maxZ, Math.max(botFloor.getZ(), target.getZ()));
        return new RegionBound(minX - CORRIDOR_MARGIN, maxX + CORRIDOR_MARGIN,
                minBY - CORRIDOR_VMARGIN, maxBY + CORRIDOR_VMARGIN,
                minZ - CORRIDOR_MARGIN, maxZ + CORRIDOR_MARGIN);
    }

    // ---------------------------------------------------------------------------------------------------
    // Region mapping helpers (pure RegionAddress integer math)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The skeleton index in {@code [lo, hi]} whose {@code (region[, fragment])} the bot occupies, or
     * {@code committedIndex} (unchanged) if none match — a forward-only search (the bot only advances).
     *
     * <p>Center-model plan: matches on level-0 region coords and returns the first such index (the original
     * behaviour, byte-for-byte). Fragment-model plan (HPA-FRAGMENTS.md §S4): requires the step whose
     * {@code (region, fragment)} BOTH equal the bot's, so two steps sharing a region but not a fragment (an
     * intra-region mine edge) are distinguished.
     *
     * <p><b>No region-only fallback</b> (owner ruling 2026-08-13). A step matching only on region is REFUSED:
     * fragments are disconnected components, so committing there declares the bot to be at a node it cannot
     * reach without digging, and {@code committedIndex} is monotone — the misidentification is permanent.
     * Holding is safe and self-correcting: the window target is a fragment FACE, so a bot taking a cheaper
     * block-tier route through an unplanned pocket must eventually enter a fragment the skeleton names, and
     * the slide happens then. Dips into unplanned fragments are expected and fine — the block tier prices
     * terrain better than the region tier does, and near a region seam stepping one cell into the next region
     * to walk around a puddle beats swimming through it. This matches the CASCADE's matcher
     * ({@code HierarchicalRegionPlan}), which already refuses region-only matches; the two commit matchers
     * now agree. Measured motivation: three commits in the flagship run advanced through this fallback with
     * the bot provably in a different fragment than the step it committed to.
     *
     * <p>RESIDUAL (watch the {@code regionOnlyRefused=true} logs): the skeleton's fragment ids are snapshotted
     * at search time, so an edit that MERGES two fragments can leave a step naming an id that no longer
     * denotes the same set. Under the old fallback that mismatch slid anyway; now it holds. If a run shows
     * the window ceasing to slide while the bot makes real progress, suspect stale ids, not this rule.
     */
    private int forwardIndexOf(BlockPos floor, int botFrag, int lo, int hi) {
        final int frx = RegionAddress.regionX(floor.getX(), 0);
        final int fry = RegionAddress.regionY(floor.getY(), 0, minY);
        final int frz = RegionAddress.regionZ(floor.getZ(), 0);
        final boolean fragModel = skeleton.isFragmentModel();
        lastMatchRegionOnly = false;
        for (int i = lo; i <= hi; i++) {
            if (skeleton.rx(i) == frx && skeleton.ry(i) == fry && skeleton.rz(i) == frz) {
                if (!fragModel || skeleton.fragmentId(i) == botFrag) {
                    return i; // exact (region[, fragment]) match
                }
                lastMatchRegionOnly = true; // diagnostic: a region-only match we deliberately refused
            }
        }
        return committedIndex; // no node of the route holds the bot — do NOT advance
    }

    /**
     * The bot's fragment <b>as the current window's skeleton labels it</b> — the fragment-model commit key
     * (HPA-FRAGMENTS.md §S4). {@code -1} when the bot's region is not one the window visits, or its cell has
     * no label there; {@link #forwardIndexOf} then matches nothing and the cursor holds.
     *
     * <p><b>Snapshot slabs, not a live probe</b> (owner ruling 2026-08-13). Membership is read from per-region
     * label slabs cut when the window was committed ({@link #ensureWindowSlabs}), not by re-flooding the live
     * world each tick. Three things fall out of that, and the first two were the actual bugs:
     *
     * <ul>
     *   <li><b>Exact, never a guess.</b> The predecessor asked {@link RegionPathfinder#fragmentOf} for the
     *       fragment whose CENTROID is nearest. A bot low in a tall winding fragment is nearer a small
     *       sibling pocket's centroid than its own fragment's face-averaged centroid, so the driver reported
     *       a fragment the bot was not in and the matcher committed through its region-only fallback into a
     *       DISCONNECTED sub-region. Measured: the last three commits before the (208,-8,58) wedge all did
     *       this ({@code botKey=(12,3,3:1)} against {@code S7(12,3,3:0)}).
     *   <li><b>Stable across edits.</b> Fragment ids are only stable while the passable mask is unchanged —
     *       {@link com.orebit.mod.worldmodel.hpa.FragmentBuilder#fragmentContaining} reproduces {@code build}'s
     *       flood over the SAME mask, so an edit that rebuilds the leaf may REASSIGN them. The skeleton's
     *       per-step ids are themselves a snapshot from when the region search ran, so a live probe drifts out
     *       of agreement with the very skeleton it is matching against. A slab cut with the window agrees with
     *       it by construction.
     *   <li><b>It IS the intra-region dig trigger.</b> When the bot digs two fragments together they become
     *       one component, and a live re-flood would report the merged id — losing the distinction the plan is
     *       built on. The slab still carries the pre-dig labels, so a bot tunnelling out of fragment A into a
     *       cell that WAS fragment B reads {@code B} and the window slides. Cells that were solid at cut time
     *       (the tunnel itself) read {@code -1}: not in any fragment, so no spurious slide while mid-dig.
     * </ul>
     *
     * <p>Cost is one flood per distinct window region per window, then an array read per tick — replacing a
     * full 16³ re-flood on every settled tick. Cells are addressed exactly as
     * {@code RegionGrid.labelRegionFragments} documents, and the feet-cell seed with floor-cell fallback
     * mirrors {@code containedFragment} verbatim (a floor at local y=15 has its feet in the region above,
     * whose ids belong to a different node; a swimming bot's floor cell is itself passable).
     */
    private int windowFragmentAt(BlockPos floor) {
        ensureWindowSlabs();
        final long key = regionSlabKey(RegionAddress.regionX(floor.getX(), 0),
                RegionAddress.regionY(floor.getY(), 0, minY),
                RegionAddress.regionZ(floor.getZ(), 0));
        for (int i = 0; i < slabCount; i++) {
            if (slabKeys[i] != key) {
                continue;
            }
            final byte[] v = slabVals[i];
            if (v == SLAB_UNIFORM) {
                return 0; // one fragment identity — every cell of the region is it
            }
            if (v == SLAB_UNKNOWN) {
                return -1; // section not resident when the slab was cut — prove nothing, advance nothing
            }
            final int lx = floor.getX() & 15, lz = floor.getZ() & 15;
            final int lyFloor = (floor.getY() - minY) & 15;
            final boolean feetInRegion = lyFloor != 15;
            int f = v[(feetInRegion ? lyFloor + 1 : lyFloor) << 8 | (lz << 4) | lx];
            if (f < 0 && feetInRegion) {
                f = v[(lyFloor << 8) | (lz << 4) | lx];
            }
            return f;
        }
        return -1; // the bot is in a region this window never visits — nothing to match
    }

    /**
     * Cut one fragment-label slab per DISTINCT level-0 region of the current window, if the window (or the
     * skeleton object) changed since the last cut. Steps sharing a region share a slab — an intra-region mine
     * edge puts two steps in one region and they differ only by fragment id, which the slab already
     * distinguishes — so this floods once per region, not once per step.
     *
     * <p>Self-validating on {@code (skeleton identity, windowStart, windowLast)} rather than an explicit
     * invalidate call at every mutation site, so a new commit/slide/extension path cannot forget to
     * invalidate. O(1) when nothing moved.
     */
    private void ensureWindowSlabs() {
        final int last = windowLast();
        if (slabSkeleton == skeleton && slabWindowStart == windowStart && slabWindowLast == last) {
            return;
        }
        slabSkeleton = skeleton;
        slabWindowStart = windowStart;
        slabWindowLast = last;
        slabCount = 0;
        if (skeleton == null || !skeleton.isFragmentModel()) {
            return;
        }
        for (int i = Math.max(0, windowStart); i <= last && slabCount < SLAB_MAX; i++) {
            final int rx = skeleton.rx(i), ry = skeleton.ry(i), rz = skeleton.rz(i);
            final long key = regionSlabKey(rx, ry, rz);
            boolean seen = false;
            for (int j = 0; j < slabCount; j++) {
                if (slabKeys[j] == key) { seen = true; break; }
            }
            if (seen) {
                continue;
            }
            regionGrid.ensureLeaf(rx, ry, rz);
            final RegionFragments rf = regionGrid.fragmentRecord(0, rx, ry, rz);
            byte[] v;
            if (rf == null || rf.isUniform() || rf.fragmentCount() <= 1) {
                v = SLAB_UNIFORM; // no flood needed: a single identity answers every cell
            } else {
                byte[] slab = slabPool[slabCount];
                if (slab == null) {
                    slab = slabPool[slabCount] = new byte[SLAB_CELLS];
                }
                v = regionGrid.labelRegionFragments(rx, ry, rz, slab) ? slab : SLAB_UNKNOWN;
            }
            slabKeys[slabCount] = key;
            slabVals[slabCount] = v;
            slabCount++;
        }
    }

    /** Pack a level-0 region address into a slab-cache key (21 bits per axis, well past the ±30M world). */
    private static long regionSlabKey(int rx, int ry, int rz) {
        return ((rx & 0x1FFFFFL) << 42) | ((ry & 0x1FFFFFL) << 21) | (rz & 0x1FFFFFL);
    }

    /**
     * The skeleton index in {@code [lo, hi]} whose level-0 region coords equal {@code pos}'s region, or
     * {@code -1} if {@code pos} maps to no skeleton region in that range. Used by {@link #committed} to test
     * whether a remaining waypoint revisits an earlier region.
     */
    private int skeletonIndexOf(BlockPos pos, int lo, int hi) {
        final int prx = RegionAddress.regionX(pos.getX(), 0);
        final int pry = RegionAddress.regionY(pos.getY(), 0, minY);
        final int prz = RegionAddress.regionZ(pos.getZ(), 0);
        for (int i = lo; i <= hi; i++) {
            if (skeleton.rx(i) == prx && skeleton.ry(i) == pry && skeleton.rz(i) == prz) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The FINAL goal's arrival tolerance — the caller's {@link #goalTolXZ}/{@link #goalTolY} (s52), mirrored
     * from the tolerance the GOAL-target block searches run with, so the driver completes exactly when the
     * goal-window block plan would. Historical callers get ±1/±2; drop collection runs 0/0.
     */
    private boolean withinGoalTolerance(BlockPos floor) {
        return Math.abs(floor.getX() - goalFloor.getX()) <= goalTolXZ
                && Math.abs(floor.getZ() - goalFloor.getZ()) <= goalTolXZ
                && Math.abs(floor.getY() - goalFloor.getY()) <= goalTolY;
    }

    /** The block tier's DEFAULT arrival tolerance (±1 horizontal, ±2 vertical) of an arbitrary target —
     *  the window-slide commit radius (intermediate window targets always use the default; only the final
     *  goal carries the caller's tolerance). */
    private static boolean withinTolerance(BlockPos floor, BlockPos target) {
        return Math.abs(floor.getX() - target.getX()) <= 1
                && Math.abs(floor.getZ() - target.getZ()) <= 1
                && Math.abs(floor.getY() - target.getY()) <= 2;
    }

    /** Chebyshev (max-axis) block distance — the "near the target" test for sliding the window on approach. */
    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }
}
