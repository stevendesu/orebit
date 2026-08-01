package com.orebit.mod.pathfinding;

import com.orebit.mod.Debug;
import com.orebit.mod.NavJourneyStats;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.async.SearchRequest;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.HierarchicalRegionPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
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

    // ---- fragment-model per-tick scratch (HPA-FRAGMENTS.md §S4) ---------------------------------------
    /**
     * Reused 3-int scratch buffers for {@link RegionPathfinder#fragmentOf} (centroid + per-face temporary), so
     * resolving the bot's current fragment in {@link #botFragmentAt} every tick allocates nothing (the
     * fragment-model commit key — HOT-PATH-NO-ALLOC). Only touched when the skeleton is a fragment-model plan.
     */
    private final int[] fragScratchA = new int[3];
    private final int[] fragScratchB = new int[3];
    /** Reused 2-long scratch for the cascade's blocked-hop repair ({@link #repairBlocked}); no per-repair alloc. */
    private final long[] repairHopScratch = new long[2];

    // ---- active block plan ---------------------------------------------------------------------------
    BlockPathPlan blockPlan;
    /** Whether {@link #blockPlan} is a best-effort PARTIAL (from {@code BlockPathfinder.lastWasPartial()} /
     *  the async result). */
    boolean lastPlanPartial;
    private PathStatus status;
    /** The bot's last reported floor cell (the block-A* start for the next replan). */
    BlockPos botFloor;
    /** The bot's current movement mode ({@link BlockPathfinder#MODE_AUTO} = derive from geometry, else the
     *  live pose STANDING/PRONE) — threaded into every windowed search so a replan mid-sprint-swim keeps the
     *  prone state instead of re-deriving STANDING from a buoyancy bob and re-initiating. Updated per tick by
     *  {@link #onBotMoved}. */
    private int startMode = BlockPathfinder.MODE_AUTO;
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
    private int[] planChunkVers = new int[16];  // NavGridUpdater.chunkVersion of each, at snapshot time
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
        if (Debug.ENABLED) {
            // HPA-tier visibility: dump the whole region skeleton + per-step portal/center built-standable probe
            // (a [SOLID/buried] portal is the §6 buried-target bug). Counterpart to the block tier's /bot trace.
            OrebitCommon.LOGGER.info("[Orebit] {}", describeSkeleton());
        }
        replanBlock();
    }

    // ---------------------------------------------------------------------------------------------------
    // Public surface (HPA-IMPLEMENTATION.md §9 / §10)
    // ---------------------------------------------------------------------------------------------------

    /** The active windowed block path the follower walks; {@code null} when BLOCKED/FAILED. */
    public BlockPathPlan currentBlockPlan() {
        return blockPlan;
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
        this.botFloor = botFloor;
        this.startMode = startMode; // the bot's live pose, used by the next windowed search (keeps PRONE while swimming)

        if (status == PathStatus.COMPLETE || status == PathStatus.FAILED || skeleton == null) {
            return;
        }

        // Async result drain (DESIGN-background-pathfinding.md §5): the caller only invokes onBotMoved at
        // a settled boundary, so adopting here IS the boundary-gated adoption the design requires. No-op
        // when sync or nothing is in flight (one null compare).
        if (executor != null) {
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
        final int botFrag = fragModel ? botFragmentAt(botFloor) : 0;

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
            if ((sameRegionDig || committed(curRegion)) && !lastPlanPartial) {
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
                                    + "planWasPartial={} bot=({},{},{})",
                            committedIndex, curRegion, goalInWindow, lastPlanPartial,
                            botFloor.getX(), botFloor.getY(), botFloor.getZ());
                }
                committedIndex = curRegion;
                windowStart = curRegion;
                replanBlock();
                return;
            }
        }
        // (No debounce fallback: its inconclusive case — a null/empty block plan at commit time — no longer
        // exists. Empty plans are never produced (the forward-slide commits satisfied targets pre-search) and
        // a null plan is BLOCKED, which the online repair owns. s52: COMMIT_TICKS deleted.)

        // Terrain changed under us (BLOCKED) — recompute the current window's block plan from where we are.
        if (blockPlan == null) {
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
                windowStart = Math.max(0, windowStart - shift);
                committedIndex = Math.max(0, committedIndex - shift);
                windowTargetStep = Math.max(0, windowTargetStep - shift);
                blockedWindowStart = Math.max(0, blockedWindowStart - shift);
                blockedTargetStep = Math.max(0, blockedTargetStep - shift);
            }
            return false; // fall through: T2's commit logic replans over the now-un-clamped window
        }
        // SWAPPED — a genuine re-derive (deviation, exhaustion, flood-widen, top-collapse) or FAILED.
        // Telemetry: a re-derivation ran (L0 changed) — count a flood if its region search tripped the guard.
        if (stats != null && RegionPathfinder.lastWasFlood()) stats.onRegionFlood();
        this.skeleton = hier.l0Skeleton();
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
            planChunkVers[planChunkCount] = NavGridUpdater.chunkVersion(level, ck);
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
            return true;
        }
        if (NavGridUpdater.editEpoch(level) == planSnapshotEpoch) {
            return false; // nothing changed anywhere in the dimension since this plan's search
        }
        for (int i = 0; i < planChunkCount; i++) {
            if (NavGridUpdater.chunkVersion(level, planChunks[i]) != planChunkVers[i]) {
                return true; // a chunk the path traverses changed → the plan may be stale, re-search
            }
        }
        return false; // changes happened, but not in a chunk this plan traverses — keep following
    }

    private boolean botOnBlockPlan(BlockPos floor) {
        if (blockPlan == null || blockPlan.isEmpty()) {
            return false;
        }
        final int n = blockPlan.size();
        for (int i = 0; i < n; i++) {
            final BlockPos wp = blockPlan.waypoint(i); // the stand cell; its floor is one below
            if (Math.abs(wp.getX() - floor.getX()) <= 1
                    && Math.abs(wp.getY() - 1 - floor.getY()) <= 1
                    && Math.abs(wp.getZ() - floor.getZ()) <= 1) {
                return true;
            }
        }
        return false;
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
        if (executor != null) {
            if (async.pendingSearchToward(target)) {
                // A boundary replan toward this same target is already in flight → skip. An in-flight
                // PRE-PLAN toward it is also left alone WHILE the current plan is still walkable (the
                // 40-tick refresh timer would otherwise routinely kill the precompute — review finding;
                // the seam-reject → replan-from-actual fallback covers a stall that invalidated the
                // prediction, one round-trip later). Only a genuinely planless bot preempts a pre-plan.
                if (!async.pendingIsPreplan() || blockPlan != null) return;
            }
            if (blockPlan != null && async.parkedFor(target)) {
                return; // the precomputed result is already parked for this target — arrival adopts it
            }
            submit(botFloor, target, cuboidCap, baseline, false, suspect);
            if (status != PathStatus.RUNNING) status = PathStatus.RUNNING;
            return;
        }

        // confineBound = null (unconfined), cuboidBound = the growth cap. startMode = the bot's live pose (so a
        // replan mid-sprint-swim stays PRONE instead of re-deriving STANDING from a bob and re-initiating).
        // baseline = the splice seed (null for every non-spliced plan). The grid view is built HERE, below
        // the async branch — in async mode the worker builds its own background view, so the tick thread
        // must not pay the per-search view construction twice (SHORT-guard discipline).
        final NavGridView grid = new NavGridView(level);
        this.blockPlan = BlockPathfinder.findPath(grid, botFloor, target, caps, null, cuboidCap, inventory,
                startMode, baseline, 0L, regionFieldFor(target), tolXZFor(target), tolYFor(target));
        this.lastPlanPartial = blockPlan != null && BlockPathfinder.lastWasPartial();
        this.status = resultStatus(blockPlan, BlockPathfinder.lastExpansions(),
                BlockPathfinder.lastWasPartial(), BlockPathfinder.lastWasBudgetHit(),
                blockPlan == null ? BlockPathfinder.lastRealizedCrossings() : null, botFloor);
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
     * Whether an async window search is outstanding (in flight or finished-but-undrained) that was
     * launched from a site implying the CURRENT plan may be invalid — a terrain-impacted refresh, a
     * repair, a blocked-null resubmit, or a retry inheriting one of those. The follower's caution gate
     * (DESIGN-async-step-safety.md §3): while true, step transitions into committed moves and
     * deeper-than-safe Falls defer (stand at the settled anchor) until the drain resolves the doubt.
     * Routine searches (fresh plan, forward-slide, cascade re-derive, P4 pre-plan) never set it.
     * Always false in sync mode — a synchronous search resolves within its own tick, so no doubt
     * window exists.
     */
    public boolean suspectSearchPending() {
        return executor != null && async.suspectPending();
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
    public String blockedStartRegionDesc() {
        if (blockedStartFloor == null) return "?";
        return "(" + RegionAddress.regionX(blockedStartFloor.getX(), 0) + ","
                + RegionAddress.regionY(blockedStartFloor.getY(), 0, minY) + ","
                + RegionAddress.regionZ(blockedStartFloor.getZ(), 0) + ")";
    }

    /** Build this submission's {@link SearchRequest} and hand it to the {@link AsyncWindowSearch mailbox}
     *  (which supersedes any in-flight search and, for a boundary replan, drops the parked pre-plan). */
    private void submit(BlockPos fromFloor, BlockPos target, RegionBound cuboidCap,
                        EditSnapshot seed, boolean preplan, boolean suspect) {
        // regionFieldFor(target): the snapshot must carry the field rooted at THIS submission's target —
        // covers both the boundary replan and the P4 pre-plan (which targets windowTargetPos, so the root
        // matches the cached field from the last replanBlock and this is a cheap equals hit).
        async.submit(new SearchRequest(level, fromFloor, target, caps, inventory, startMode,
                cuboidCap, seed, executor.budgetNanos(), regionFieldFor(target),
                tolXZFor(target), tolYFor(target)), fromFloor, target, preplan, suspect);
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
     * </ul>
     */
    private void pollPending(BlockPos actualFloor, boolean fluidAnchor) {
        switch (async.drainPending(actualFloor, windowTargetPos, startMode, fluidAnchor)) {
            case RETRY:
                // Executor hiccup / drifted past seam tolerance / window moved — plan from where we
                // really are (the mailbox never decides; see AsyncWindowSearch.Drain).
                replanBlock(async.lastDrainSuspect()); // a retried suspect search stays suspect
                break;
            case RESULT:
                this.blockPlan = async.resultPlan();
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
        // Parked pre-plan adoption: the no-pause splice. Adopt only when the bot has actually arrived at
        // the predicted start (seam accept) and the window target is still the parked one.
        if (async.pollParked(actualFloor, windowTargetPos, startMode, fluidAnchor)) {
            this.blockPlan = async.resultPlan();
            this.lastPlanPartial = async.resultPartial();
            this.status = resultStatus(blockPlan, async.resultExpansions(),
                    async.resultPartial(), async.resultBudgetHit(), null, // parked plans are never null
                    async.resultStart());
            if (Debug.ENABLED) logBlockPlan();
            snapshotPlanChunks(); // adopted a parked pre-plan — re-baseline the plan-relevance snapshot
        }
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
        submit(predictedFloor, windowTargetPos, cuboidCapBox(windowTargetPos), remainingEdits, true, false);
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
        pollPending(liveFloor, fluidAnchor);
    }

    /** Stop caring about any in-flight search (the owner cleared/replaced this plan). */
    public void cancelPending() {
        async.cancel();
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
            // relaxVirtualGoal checks (parity — DESIGN-virtual-start-fragment §0.5): region + fragment +
            // entry-face + from-fragment. The skeleton stores only region+fragment per step, so entry-face is
            // reconstructed geometrically and from-fragment is the previous step's fragment (VIRTUAL_START_FRAG
            // at the root). This makes (A|from=S → V) independently blameable from (A|from=staircase → V) when
            // A==G — the cliff false-give-up fix.
            out[0] = RegionPathfinder.approachRowKeyForStep(skeleton, hop);
            out[1] = RegionPathfinder.fragmentNodeKey(skeleton.rx(hop + 1), skeleton.ry(hop + 1),
                    skeleton.rz(hop + 1), skeleton.fragmentId(hop + 1));
            return true;
        }
        out[0] = RegionPathfinder.fragmentNodeKey(skeleton.rx(hop), skeleton.ry(hop),
                skeleton.rz(hop), skeleton.fragmentId(hop));
        out[1] = RegionPathfinder.fragmentNodeKey(skeleton.rx(hop + 1), skeleton.ry(hop + 1),
                skeleton.rz(hop + 1), skeleton.fragmentId(hop + 1));
        return true;
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
                // (the A==G false-give-up fix — DESIGN-virtual-start-fragment §0.5).
                if (RegionPathfinder.isVirtualGoal(sk.fragmentId(i))) continue;
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
     * Recompute the CURRENT window's block plan from where the bot is now, WITHOUT touching the committed
     * skeleton — the block-level refresh the driver runs when its block path is consumed (advance toward the
     * same window target) or periodically (terrain changed under the window). This is the "shift the window,
     * don't replan everything" half: the skeleton is a committed S1→…→Sn route; only the local block path
     * between committed waypoints is re-searched. No-op once COMPLETE/FAILED or when no skeleton was produced.
     *
     * <p>Drops any parked P4 precompute first (owner ruling 2026-07-30, review finding): a refresh fires
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
     * box — unreachable from anywhere, so the bot's position is irrelevant (no {@code around(bot,goal)} span, so
     * the cost is distance-INDEPENDENT: a 100k-away goal probes as cheaply as a near one, sidestepping the L0
     * bot↔goal array-bill). The proof is monotone (a coarse seal admits no finer escape), so the FIRST close
     * wins; a not-closed level means the component reaches beyond its small box, so descend — a finer, smaller
     * seal may still close. L0 is the precise floor. A seal larger than the L6 box (~7k blocks) is left to the
     * give-up backstop / super-long-range handling (#8).
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
        if (goalFloor == null) return false;
        regionGrid.ensureLeaf(goalRX, goalRY, goalRZ);
        if (regionGrid.fragmentRecord(0, goalRX, goalRY, goalRZ) == null) {
            proactiveSignalValid = false; // goal unbuilt ⇒ optimistic; a later build re-forces the first check
            return false;
        }
        final long sig = goalNeighbourhoodBuildSignal();
        if (proactiveSignalValid && sig == lastProactiveSignal) {
            return false; // nothing in the goal's sealing neighbourhood changed since the last check
        }
        lastProactiveSignal = sig;
        proactiveSignalValid = true;
        for (int lvl = RegionAddress.MAX_COARSE_LEVEL; lvl >= 0; lvl--) {
            boolean sealed;
            try {
                sealed = RegionPathfinder.isSealedWithin(regionGrid, minY, goalFloor, lvl, caps.boxedInScanRadius(),
                        caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), regionMine, regionPlace);
            } catch (Throwable t) {
                sealed = false; // a sealed-probe backs a give-up, never gates it: inconclusive ⇒ descend/proceed
            }
            if (sealed) {
                // A proof-of-sealing is a rare, journey-terminal verdict — name it in the server log with
                // enough to audit it in one glance (the drifted-world vine-jungle false positive was
                // invisible precisely because this branch FAILed silently; owner ruling 2026-07-31).
                OrebitCommon.LOGGER.info(
                        "[Orebit] boxed-in PROVEN: goal={} sealed at level {} (radius {}, canBreak={} "
                                + "canPlace={}) — giving up honestly",
                        goalFloor, lvl, caps.boxedInScanRadius(), caps.canBreak(), caps.canPlace());
                this.boxedInProven = true;
                this.skeleton = null;
                this.blockPlan = null;
                this.status = PathStatus.FAILED;
                return true;
            }
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
     * behaviour, byte-for-byte). Fragment-model plan (HPA-FRAGMENTS.md §S4): prefers the step whose
     * {@code (region, fragment)} both equal the bot's — so two steps sharing a region but not a fragment (an
     * intra-region mine edge) are distinguished — and falls back to the first region-only match if no step
     * matches the bot's fragment (the nearest-centroid signal is approximate; falling back to region-only is
     * never worse than the center model and never stalls forward progress).
     */
    private int forwardIndexOf(BlockPos floor, int botFrag, int lo, int hi) {
        final int frx = RegionAddress.regionX(floor.getX(), 0);
        final int fry = RegionAddress.regionY(floor.getY(), 0, minY);
        final int frz = RegionAddress.regionZ(floor.getZ(), 0);
        final boolean fragModel = skeleton.isFragmentModel();
        int regionFallback = committedIndex;
        boolean haveRegion = false;
        for (int i = lo; i <= hi; i++) {
            if (skeleton.rx(i) == frx && skeleton.ry(i) == fry && skeleton.rz(i) == frz) {
                if (!fragModel || skeleton.fragmentId(i) == botFrag) {
                    return i; // exact (region[, fragment]) match
                }
                if (!haveRegion) { // remember the first region-only match as the fallback
                    regionFallback = i;
                    haveRegion = true;
                }
            }
        }
        return haveRegion ? regionFallback : committedIndex;
    }

    /**
     * The fragment of the bot's current level-0 region the world floor cell {@code floor} sits in (the
     * fragment-model commit key, HPA-FRAGMENTS.md §S4). Lazily ensures the leaf is built and delegates to
     * {@link RegionPathfinder#fragmentOf} with the reused {@link #fragScratchA}/{@link #fragScratchB} buffers,
     * so the per-tick resolution allocates nothing. {@code 0} for a uniform/collapsed/unbuilt region (its
     * single synthetic fragment). Only called for fragment-model skeletons.
     */
    private int botFragmentAt(BlockPos floor) {
        final int rx = RegionAddress.regionX(floor.getX(), 0);
        final int ry = RegionAddress.regionY(floor.getY(), 0, minY);
        final int rz = RegionAddress.regionZ(floor.getZ(), 0);
        regionGrid.ensureLeaf(rx, ry, rz);
        return RegionPathfinder.fragmentOf(regionGrid, rx, ry, rz,
                floor.getX(), floor.getY(), floor.getZ(), fragScratchA, fragScratchB);
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
