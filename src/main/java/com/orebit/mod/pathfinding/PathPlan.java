package com.orebit.mod.pathfinding;

import com.orebit.mod.Debug;
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
import com.orebit.mod.worldmodel.pathing.NavGridView;

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

        this.hier = HierarchicalRegionPlan.build(regionGrid, minY, startFloor, goalFloor, caps, mine);
        this.skeleton = hier.l0Skeleton();
        this.windowStart = 0;
        this.committedIndex = 0;

        if (skeleton == null || skeleton.isEmpty()) {
            // No coarse route at all (no built ground at the start region). Leave the block plan null and the
            // status FAILED so AllyBotEntity gives up visibly (HOLD + a chat line — pathological failures
            // stay visible, HPA-IMPLEMENTATION.md §10).
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            return;
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
    public void onBotMoved(BlockPos botFloor, int startMode) {
        this.botFloor = botFloor;
        this.startMode = startMode; // the bot's live pose, used by the next windowed search (keeps PRONE while swimming)

        if (status == PathStatus.COMPLETE || status == PathStatus.FAILED || skeleton == null) {
            return;
        }

        // Async result drain (DESIGN-background-pathfinding.md §5): the caller only invokes onBotMoved at
        // a settled boundary, so adopting here IS the boundary-gated adoption the design requires. No-op
        // when sync or nothing is in flight (one null compare).
        if (executor != null) {
            pollPending(botFloor);
        }

        // Goal tolerance check (the block tier reaches the goal within 1 horizontally, 2 vertically —
        // mirror that here so the driver completes exactly when the follower's block plan would).
        if (withinGoalTolerance(botFloor)) {
            status = PathStatus.COMPLETE;
            return;
        }

        // Cascade step (HPA-CASCADE.md §5): advance the per-level commits and re-derive only the suffix the bot
        // exited. When the L0 segment changed, swap it in and reset the block window from the bot's region;
        // otherwise fall through to the block-window slide over the unchanged skeleton.
        if (stepCascade()) {
            return;
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
            if (sameRegionDig || committed(curRegion)) {
                // A real forward step: the path no longer revisits any region in [committedIndex, curRegion).
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
            replanBlock();
        }
    }

    /**
     * One step of the region-tier cascade (HPA-CASCADE.md §5): advance the per-level commits and re-derive only
     * the suffix the bot exited. Returns {@code true} (and {@link #onBotMoved} stops) when the level-0 skeleton
     * changed — we swap it in, reset the block window from the bot's region, and replan the block path; or when
     * the cascade ran out of route (→ FAILED). Returns {@code false} when L0 is unchanged, so the caller proceeds
     * with the normal block-window slide over the same skeleton. Only called when {@link #hier} is present.
     */
    private boolean stepCascade() {
        // onRoute: the block plan vouches for the bot's position (it stands at/near a plan waypoint), so an
        // off-window excursion (a fall-lineup clip into an adjacent region) is intentional, not a deviation —
        // the cascade only re-derives for a bot that is off-window AND off its plan (s52; replaced the old
        // BOUNDARY_CLIP_CHEB spatial tolerance with asking the plan).
        if (!hier.onBotMoved(botFloor, botOnBlockPlan(botFloor))) {
            return false; // still within every level's window — slide the block window over the unchanged L0
        }
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
    private void replanBlock() {
        WindowTargeting.Result choice = targeting.target(skeleton, windowStart, windowLast());
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
            choice = targeting.target(skeleton, windowStart, windowLast());
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
            submit(botFloor, target, cuboidCap, baseline, false);
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
        this.status = resultStatus(blockPlan, BlockPathfinder.lastExpansions());
        if (Debug.ENABLED && blockPlan != null) {
            logBlockPlan();
        }
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
    private PathStatus resultStatus(BlockPathPlan plan, int expansions) {
        if (plan != null) {
            startDead = false;
            return PathStatus.RUNNING;
        }
        startDead = expansions <= 1;
        if (!startDead) blockedGeneration++;
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

    /** Whether the last BLOCKED came from a START-DEAD search (≤1 expansion — see {@link #resultStatus}).
     *  The follower branches to self-rescue instead of hop repair. Cleared by any adopted plan. */
    public boolean startDead() {
        return startDead;
    }

    private int blockedGeneration;
    private boolean startDead;

    /** Build this submission's {@link SearchRequest} and hand it to the {@link AsyncWindowSearch mailbox}
     *  (which supersedes any in-flight search and, for a boundary replan, drops the parked pre-plan). */
    private void submit(BlockPos fromFloor, BlockPos target, RegionBound cuboidCap,
                        EditSnapshot seed, boolean preplan) {
        // regionFieldFor(target): the snapshot must carry the field rooted at THIS submission's target —
        // covers both the boundary replan and the P4 pre-plan (which targets windowTargetPos, so the root
        // matches the cached field from the last replanBlock and this is a cheap equals hit).
        async.submit(new SearchRequest(level, fromFloor, target, caps, inventory, startMode,
                cuboidCap, seed, executor.budgetNanos(), regionFieldFor(target),
                tolXZFor(target), tolYFor(target)), fromFloor, target, preplan);
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
     *       search started from AND the window target hasn't moved; otherwise resubmit from the actual
     *       floor (the same recovery the escape hatches use). A {@code null} result = BLOCKED, exactly the
     *       sync path's semantics. An executor-rejected handle also retries — NOT a search verdict, never
     *       BLOCKED (that blacklists a real skeleton hop — review finding).</li>
     *   <li><b>Pre-plan</b> result (P4): PARK it — the bot hasn't reached the predicted start yet. Each
     *       boundary visit re-tests the parked seam; on accept it's adopted with no search pause at all,
     *       on target-change it's dropped (the window moved on).</li>
     * </ul>
     */
    private void pollPending(BlockPos actualFloor) {
        switch (async.drainPending(actualFloor, windowTargetPos, startMode)) {
            case RETRY:
                // Executor hiccup / drifted past seam tolerance / window moved — plan from where we
                // really are (the mailbox never decides; see AsyncWindowSearch.Drain).
                replanBlock();
                break;
            case RESULT:
                this.blockPlan = async.resultPlan();
                this.lastPlanPartial = blockPlan != null && async.resultPartial();
                this.status = resultStatus(blockPlan, async.resultExpansions());
                if (Debug.ENABLED && blockPlan != null) logBlockPlan();
                break;
            default: // NONE — nothing finished / pre-plan parked or dropped internally
                break;
        }
        // Parked pre-plan adoption: the no-pause splice. Adopt only when the bot has actually arrived at
        // the predicted start (seam accept) and the window target is still the parked one.
        if (async.pollParked(actualFloor, windowTargetPos, startMode)) {
            this.blockPlan = async.resultPlan();
            this.lastPlanPartial = async.resultPartial();
            this.status = resultStatus(blockPlan, async.resultExpansions()); // parked plans are never null
            if (Debug.ENABLED) logBlockPlan();
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
        submit(predictedFloor, windowTargetPos, cuboidCapBox(windowTargetPos), remainingEdits, true);
    }

    /**
     * Tick-rate poll for the PLANLESS case (review finding): adoption of a plan when {@link #blockPlan}
     * is null needs NO settled boundary — there is nothing to un-adopt mid-move, and the sync path built
     * its first plan from a floating/swimming bot too. Without this, a bot that never settles (treading
     * water, long fall) could wait forever on its FIRST plan, because {@link #onBotMoved} — the only
     * other drain — is boundary-gated by the caller. Also refreshes {@link #botFloor} so a
     * rejected-seam resubmit searches from the bot's LIVE cell, not the stale ctor cell.
     *
     * <p>(A CONSUMED follower plan needs no special case here — s52: plan consumption is a first-class
     * settle event in the driver, so a consumed plan drains through the normal boundary-gated
     * {@link #onBotMoved} the same tick it settles.)
     */
    public void pollWhenPlanless(BlockPos liveFloor) {
        if (executor == null || blockPlan != null) return;
        if (status == PathStatus.COMPLETE || status == PathStatus.FAILED || skeleton == null) return;
        this.botFloor = liveFloor;
        pollPending(liveFloor);
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
     * The onward skeleton crossing the bot is stuck on — {@code windowStart → windowStart+1} — for the cascade's
     * online repair ({@link #repairBlocked}). When the bot is {@link PathStatus#BLOCKED} the block tier couldn't
     * leave the bot's committed region ({@code windowStart}, the window's leading step) toward the next
     * skeleton step, so <i>that</i> hop is the unrealizable one; the bot blacklists it and the next region
     * replan routes around it. Using {@code windowStart} (not a hardcoded 0) makes this correct whether the
     * plan is freshly built ({@code windowStart == 0}) or a committed skeleton the bot has already advanced
     * along. Fills {@code out[0]=fromKey, out[1]=toKey} and returns {@code true}; returns {@code false} when
     * there is no onward hop (no skeleton, or the bot is in the final skeleton region but can't reach the goal
     * cell — a genuine give-up, no edge to blame).
     */
    public boolean blockedHop(long[] out) {
        if (skeleton == null) return false;
        final int from = windowStart;
        final int to = windowStart + 1;
        if (to >= skeleton.size()) return false;
        out[0] = RegionPathfinder.fragmentNodeKey(skeleton.rx(from), skeleton.ry(from), skeleton.rz(from),
                skeleton.fragmentId(from));
        out[1] = RegionPathfinder.fragmentNodeKey(skeleton.rx(to), skeleton.ry(to), skeleton.rz(to),
                skeleton.fragmentId(to));
        return true;
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
            return false;
        }
        if (!hier.onBlocked(repairHopScratch[0], repairHopScratch[1], botFloor)) {
            this.skeleton = null;
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            return false;
        }
        this.skeleton = hier.l0Skeleton();
        if (skeleton == null || skeleton.isEmpty()) {
            this.blockPlan = null;
            this.status = PathStatus.FAILED;
            return false;
        }
        resetWindow();
        replanBlock();
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

    /**
     * Recompute the CURRENT window's block plan from where the bot is now, WITHOUT touching the committed
     * skeleton — the block-level refresh the driver runs when its block path is consumed (advance toward the
     * same window target) or periodically (terrain changed under the window). This is the "shift the window,
     * don't replan everything" half: the skeleton is a committed S1→…→Sn route; only the local block path
     * between committed waypoints is re-searched. No-op once COMPLETE/FAILED or when no skeleton was produced.
     */
    public void refreshWindow() {
        if (skeleton == null || status == PathStatus.COMPLETE || status == PathStatus.FAILED) return;
        replanBlock();
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
