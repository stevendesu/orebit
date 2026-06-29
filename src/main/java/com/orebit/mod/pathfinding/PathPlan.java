package com.orebit.mod.pathfinding;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.HierarchicalRegionPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;
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
 * never lower {@code committedIndex} and never replan. If the waypoint scan is inconclusive (e.g. the block
 * plan is null), the fallback is a debounce: advance only after the bot's floor sits inside
 * {@code skeleton[j]} for {@link #COMMIT_TICKS} consecutive ticks.
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
 *   <li><b>Window target</b> ({@link #windowTarget()}): the far step's {@link RegionPathPlan#portalCell portal
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

    /** Number of consecutive skeleton regions the window spans (HPA-IMPLEMENTATION.md §9). */
    public static final int WINDOW = 3;

    /**
     * Debounce: when the wiggle waypoint scan is inconclusive (e.g. a null block plan), advance the window to
     * {@code skeleton[j]} only after the bot's floor has been inside that region for this many consecutive
     * ticks (HPA-IMPLEMENTATION.md §9).
     */
    public static final int COMMIT_TICKS = 3;

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
     * Replan / slide the window when the bot comes within this many blocks (Chebyshev) of the window target,
     * rather than requiring exact arrival or a region-boundary crossing. A portal centroid can be slightly off
     * (buried / mid-air); committing on APPROACH avoids forcing the bot to pillar up to an imperfect centroid
     * only to drop back down, and breaks the boundary-straddle bob (the target sits 1 cell into the next region,
     * which the ±1 block tolerance "reaches" without the bot ever entering that region). Kept small (a
     * {@code 2N+1} cube): at 5 it was a 11³ box — nearly a whole region — that could slide the window while the
     * bot is still in a parallel tunnel. 3 ⇒ a 7³ box, enough to break the 1–2 block boundary straddle without
     * reaching across a wall.
     */
    public static final int REPLAN_NEAR_TARGET = 3;

    // ---- immutable inputs ----------------------------------------------------------------------------
    private final ServerLevel level;
    private final RegionGrid regionGrid;
    private final BlockPos goalFloor;
    private final BotCaps caps;
    /**
     * The live bot's per-pathfind inventory feasibility snapshot (PRD §10 Phase 1b/1c), passed straight to
     * each windowed {@link BlockPathfinder#findPath} so the break/place gates account for the bot's REAL
     * carried tools + blocks. {@code null} when no bot supplied one (the existing single-arg constructor,
     * headless callers), leaving the gates in their historical caps-only mode.
     */
    private final MovementContext.InventoryView inventory;
    private final int minY;
    /** The goal's level-0 region coords (so we can test "goal in window" by index). */
    private final int goalRX, goalRY, goalRZ;

    // ---- skeleton + window state ---------------------------------------------------------------------
    /**
     * The level-0 region skeleton the block window drives — sourced from {@link #hier} and <b>swapped</b>
     * whenever the cascade re-derives a fresh L0 segment (HPA-CASCADE.md §5). Non-final for that swap; all the
     * window/commit/target readers below treat it as the current skeleton.
     */
    private RegionPathPlan skeleton;
    /**
     * The region-tier nested-skeleton cascade (HPA-CASCADE.md) — the self-refreshing <b>source</b> of
     * {@link #skeleton}: {@link #onBotMoved} steps it first and, on an L0 change, swaps {@link #skeleton} +
     * resets the block window; {@link #repairBlocked} drives its blocked-hop escalation. It owns the per-level
     * blacklists, so PathPlan keeps none of its own.
     */
    private final HierarchicalRegionPlan hier;
    /** Index into the skeleton of the window's leading (start) region. */
    private int windowStart;
    /** Furthest skeleton index the bot has committed to (HPA-IMPLEMENTATION.md §9, the wiggle anchor). */
    private int committedIndex;

    // ---- debounce fallback state ---------------------------------------------------------------------
    /** The skeleton index the bot's floor currently sits in, for the consecutive-ticks debounce. */
    private int debounceIndex = -1;
    private int debounceTicks;

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
    private BlockPathPlan blockPlan;
    /** Whether {@link #blockPlan} is a best-effort PARTIAL (set from {@code BlockPathfinder.LAST_WAS_PARTIAL}). */
    private boolean lastPlanPartial;
    private PathStatus status;
    /** The bot's last reported floor cell (the block-A* start for the next replan). */
    private BlockPos botFloor;
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
     * How {@link #windowTarget} chose the current target — surfaced so the debug chat can explain a movement
     * choice (a target adjusted for caps, or the window extended down a fall). {@code GOAL} = the real goal;
     * {@code PORTAL} = the stored portal centroid as-is; {@code SNAPPED} = the centroid was unusable (buried
     * or a mid-air cell the bot can't/shouldn't stand at) so it was snapped to a real standable cell in the
     * footprint; {@code EXTENDED} = the whole window was air, so the horizon was extended DOWN the skeleton to
     * the first standable landing (a free fall); {@code CENTER} = last-resort region-center projection.
     */
    public enum TargetKind { GOAL, PORTAL, SNAPPED, EXTENDED, CENTER }
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
        this.level = level;
        this.regionGrid = regionGrid;
        this.goalFloor = goalFloor;
        this.caps = caps;
        this.inventory = inventory;
        this.minY = regionGrid.minY();
        this.botFloor = startFloor;

        this.goalRX = RegionAddress.regionX(goalFloor.getX(), 0);
        this.goalRY = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        this.goalRZ = RegionAddress.regionZ(goalFloor.getZ(), 0);

        // Region tier: the nested-skeleton cascade (HPA-CASCADE.md) re-derives its L0 segment as the bot moves
        // and owns its per-level blacklists; PathPlan just drives the L0 segment it hands back.
        this.hier = HierarchicalRegionPlan.build(regionGrid, minY, startFloor, goalFloor, caps);
        this.skeleton = hier.l0Skeleton();
        this.windowStart = 0;
        this.committedIndex = 0;

        if (skeleton == null || skeleton.isEmpty()) {
            // No coarse route at all (no built ground at the start region). Leave the block plan null and the
            // status FAILED so AllyBotEntity falls back to its visible straight-line steer (the milestone
            // wants pathological failures visible, HPA-IMPLEMENTATION.md §10).
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

    /** The skeleton step the current window is heading toward ({@link #windowTarget} aims at it). */
    public int windowTargetStepIndex() {
        return windowTargetStep;
    }

    /**
     * A multi-line dump of the coarse region skeleton — the HPA-tier counterpart to the block tier's
     * {@code /bot trace}. Per step it prints the region coords, committed fragment, region {@code kind}, the
     * <b>portal cell</b> it is entered through, and the geometric center — each annotated with a built/standable
     * <b>probe</b> ({@code [stand]} = a real floor, {@code [air-no-floor]} = passable but nothing to stand on,
     * {@code [SOLID/buried]} = inside rock, {@code [unbuilt]} = unloaded). A {@code [SOLID/buried]} portal is the
     * §6 buried-target bug made legible. The {@code *TARGET} marker flags the step {@link #windowTarget} aims at.
     * Cold path (builds a fresh {@link NavGridView}); call only on replan / trace under {@link Debug#ENABLED}.
     */
    public String describeSkeleton() {
        if (skeleton == null || skeleton.isEmpty()) {
            return "skeleton: NONE (no coarse route — no built ground at start, or region A* FAILed)";
        }
        final NavGridView grid = new NavGridView(level);
        final StringBuilder sb = new StringBuilder();
        sb.append("skeleton ").append(skeleton.size()).append(" steps  fragmentModel=")
                .append(skeleton.isFragmentModel()).append("  committed=").append(committedIndex)
                .append("  window=[").append(windowStart).append("..").append(windowLast())
                .append("]  goalRegion=(").append(goalRX).append(',').append(goalRY).append(',').append(goalRZ)
                .append(")  reachedGoal=").append(skeleton.reachedGoalRegion());
        // Cascade stack (HPA-CASCADE.md): when the nested-skeleton cascade drives this plan, dump every coarse
        // level above L0 — its skeleton, the committed cursor (*), and each cell's portal/center probe — so the
        // L1/L2 macro route + which regions are built/standable/water is legible (the L0 detail follows below).
        if (hier != null) {
            sb.append("\n  CASCADE top=L").append(hier.topLevel())
                    .append(hier.isFailed() ? " FAILED" : "");
            for (int L = hier.topLevel(); L >= 1; L--) {
                final RegionPathPlan sk = hier.skeletonAt(L);
                if (sk == null) {
                    sb.append("\n  L").append(L).append(": (none)");
                    continue;
                }
                sb.append("\n  L").append(L).append(' ').append(sk.size()).append(" steps committed=")
                        .append(hier.committedAt(L)).append(" reachedGoal=").append(sk.reachedGoalRegion());
                for (int i = 0; i < sk.size(); i++) {
                    sb.append("\n    L").append(L).append('.').append(i)
                            .append(i == hier.committedAt(L) ? "*" : " ")
                            .append(" region=(").append(sk.rx(i)).append(',').append(sk.ry(i)).append(',')
                            .append(sk.rz(i)).append(") frag=").append(sk.fragmentId(i));
                    if (sk.hasPortal(i)) {
                        final BlockPos p = sk.portalCell(i);
                        sb.append(" portal=").append(compactPos(p)).append(probe(grid, p));
                    }
                    final BlockPos cc = sk.centerOf(i);
                    sb.append(" center=").append(compactPos(cc)).append(probe(grid, cc));
                }
            }
            sb.append("\n  L0 (driven):");
        }
        final int last = windowLast();
        // The step windowTarget() actually aims at: the farthest window portal that's non-buried, else (all
        // buried) the farthest portal (it gets snapped). Mark THAT, not just windowLast — the two differ when
        // the far portal is buried, which is exactly when this dump matters.
        int targetStep = -1;
        for (int i = last; i > windowStart; i--) {
            if (skeleton.hasPortal(i) && notBuried(grid, skeleton.portalCell(i))) { targetStep = i; break; }
        }
        if (targetStep == -1) {
            for (int i = last; i > windowStart; i--) {
                if (skeleton.hasPortal(i)) { targetStep = i; break; }
            }
        }
        for (int i = 0; i < skeleton.size(); i++) {
            final int rx = skeleton.rx(i), ry = skeleton.ry(i), rz = skeleton.rz(i);
            final String tag = (i == targetStep) ? "*TARGET"
                    : (i == last) ? "far    "
                    : (i >= windowStart && i <= last) ? "win    " : "       ";
            // Force a build attempt so kind reflects what ensureLeaf produces NOW (the planner already did this
            // during the search; re-doing it is idempotent). navSection = is the underlying NavStore section
            // even present? If navSection=built but kind=AIR, ensureLeaf failed to classify a present section
            // (a region/NavStore bug); if navSection=unbuilt, the chunk nav simply isn't loaded there.
            final BlockPos c = skeleton.centerOf(i);
            regionGrid.ensureLeaf(rx, ry, rz);
            final boolean navSection = grid.built(c.getX(), c.getY(), c.getZ());
            sb.append("\n  S").append(i).append(' ').append(tag)
                    .append(" region=(").append(rx).append(',').append(ry).append(',').append(rz).append(')')
                    .append(" frag=").append(skeleton.fragmentId(i))
                    .append(" kind=").append(kindName(regionGrid.kind(0, rx, ry, rz)))
                    .append(" navSection=").append(navSection ? "built" : "UNBUILT");
            if (skeleton.hasPortal(i)) {
                final BlockPos p = skeleton.portalCell(i);
                sb.append("  portal=").append(compactPos(p)).append(probe(grid, p));
            } else {
                sb.append("  portal=none");
            }
            sb.append("  center=").append(compactPos(c)).append(probe(grid, c));
        }
        return sb.toString();
    }

    /**
     * Occupiability annotation for a <b>floor</b> cell {@code p} (the convention for skeleton targets — the
     * solid block the bot stands ON). {@code [stand]} = a real floor with ≥2 passable cells above; {@code
     * [buried]} = a standable block sealed by rock above (the §6 buried-target tell — what looked like
     * {@code [stand]} before this headroom check); {@code [air-no-floor]} = passable but not a floor;
     * {@code [SOLID]} = solid non-floor; {@code [unbuilt]} = no nav data.
     */
    private static String probe(NavGridView grid, BlockPos p) {
        final int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!grid.built(x, y, z)) {
            return "[unbuilt]";
        }
        final long d = grid.descriptorAt(x, y, z);
        if (NavBlock.isStandable(d)) {
            // Standable bit = "solid top you could stand on", but it ignores headroom: a buried rock block has
            // it set. Require 2 passable cells above (feet + head) for a genuinely occupiable floor.
            final boolean feet = NavBlock.isPassable(grid.descriptorAt(x, y + 1, z));
            final boolean head = NavBlock.isPassable(grid.descriptorAt(x, y + 2, z));
            return (feet && head) ? "[stand]" : "[buried]";
        }
        // Swimmable water is an occupiable target — distinguish it from dry mid-air so a [water] portal is
        // legible as REACHABLE in the dump (vs an [air-no-floor] cell that needs a climb intent to target). A
        // waterlogged solid (water fluid but a collision shape) is NOT swimmable → it falls through to [SOLID].
        if (NavBlock.isSwimmableWater(d)) {
            return "[water]";
        }
        if (NavBlock.isLava(d)) {
            return "[LAVA]";
        }
        return NavBlock.isPassable(d) ? "[air-no-floor]" : "[SOLID]";
    }

    private static String compactPos(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static String kindName(int kind) {
        switch (kind) {
            case RegionFragments.KIND_SOLID: return "SOLID";
            case RegionFragments.KIND_AIR:   return "AIR";
            case RegionFragments.KIND_WATER: return "WATER";
            default:                         return "MIXED";
        }
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
    public void onBotMoved(BlockPos botFloor) {
        this.botFloor = botFloor;

        if (status == PathStatus.COMPLETE || status == PathStatus.FAILED || skeleton == null) {
            return;
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

        // Came WITHIN REPLAN_NEAR_TARGET of the window target → COMMIT to its step and slide the window, even if
        // the bot is not yet physically inside the target's region. Committing on APPROACH (not exact arrival)
        // (a) breaks the boundary-straddle bob — a portal sits 1 cell into the next region, which the block
        // tier "reaches" 1 short, leaving the bot in the previous region so the region-based commit never fires;
        // and (b) avoids forcing the bot onto an imperfect (buried/mid-air) portal centroid. (Skip when the
        // target IS the goal region — handled by the goal-tolerance check above.)
        if (windowTargetPos != null && windowTargetStep > committedIndex
                && chebyshev(botFloor, windowTargetPos) <= REPLAN_NEAR_TARGET) {
            committedIndex = windowTargetStep;
            windowStart = windowTargetStep;
            debounceIndex = -1;
            debounceTicks = 0;
            replanBlock();
            return;
        }

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
                debounceIndex = -1;
                debounceTicks = 0;
                replanBlock();
                return;
            }
        }

        // Debounce fallback: when the waypoint scan is inconclusive (curRegion found but not yet committed,
        // typically because the block plan is null/exhausted), advance on COMMIT_TICKS consecutive ticks
        // inside the same forward region.
        if (curRegion > committedIndex && (blockPlan == null || blockPlan.isEmpty())) {
            if (curRegion == debounceIndex) {
                if (++debounceTicks >= COMMIT_TICKS) {
                    committedIndex = curRegion;
                    windowStart = curRegion;
                    debounceIndex = -1;
                    debounceTicks = 0;
                    replanBlock();
                    return;
                }
            } else {
                debounceIndex = curRegion;
                debounceTicks = 1;
            }
        } else {
            debounceIndex = -1;
            debounceTicks = 0;
        }

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
        if (!hier.onBotMoved(botFloor)) {
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

    /** Reset the sliding window + debounce to the head of a freshly-swapped skeleton (cascade L0 change). */
    private void resetWindow() {
        this.windowStart = 0;
        this.committedIndex = 0;
        this.debounceIndex = -1;
        this.debounceTicks = 0;
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
     * <p>Inconclusive (no block plan / empty) → {@code false}; the {@link #onBotMoved} debounce fallback then
     * gates the advance on {@link #COMMIT_TICKS} consecutive ticks instead.
     */
    private boolean committed(int j) {
        if (blockPlan == null || blockPlan.isEmpty()) {
            return false; // inconclusive — defer to the debounce fallback
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
    private int windowLast() {
        return Math.min(windowStart + WINDOW - 1, skeleton.size() - 1);
    }

    /**
     * Replan the current window's block path (HPA-IMPLEMENTATION.md §9): pick the window's block target —
     * the real {@code goalFloor} when the goal's region is at or before the window's far index, else the
     * far region center projected to a standable floor column — and run {@link BlockPathfinder#findPath}
     * over a fresh full {@link NavGridView}. Sets {@link #status} to RUNNING (found) or BLOCKED (null).
     */
    private void replanBlock() {
        final BlockPos target = windowTarget();
        final NavGridView grid = new NavGridView(level);
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
        }

        // confineBound = null (unconfined), cuboidBound = the growth cap.
        this.blockPlan = BlockPathfinder.findPath(grid, botFloor, target, caps, null, cuboidCap, inventory);
        this.lastPlanPartial = blockPlan != null && BlockPathfinder.LAST_WAS_PARTIAL;
        this.status = (blockPlan != null) ? PathStatus.RUNNING : PathStatus.BLOCKED;
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

    /**
     * The current window's block target (HPA-IMPLEMENTATION.md §9; HPA-FRAGMENTS.md §6/§S4). If the goal's
     * level-0 region is within the window (goal in window), the real {@code goalFloor} (never projected). Else,
     * for a <b>fragment-model</b> skeleton, the <b>farthest window step that yields a usable target cell</b>,
     * walking far→near. A portal is only the bbox-center of a fragment's face footprint, so the centroid can
     * land in solid rock (the A→B bounce) <i>or</i> in mid-air with no floor (an {@code air-no-floor} portal —
     * the descent/ascent flood: the block tier can't stand at a point it only falls through, so it floods the
     * open cave). A centroid is used directly only when {@link #isUsableTarget usable}; otherwise we
     * {@link #snapInFootprint snap} to a real standable cell within the portal's footprint bbox. Whether a
     * mid-air cell is acceptable is {@link #airTargetOk caps + direction}-aware (a place-capable bot climbing
     * upward may target air; everyone else needs standable ground). For a <b>center-model</b> skeleton (coarse
     * branch) or when no window step yields a cell, the far region's center projected to a standable floor.
     */
    private BlockPos windowTarget() {
        final int last = windowLast();
        // Goal in window? The goal region is the skeleton's tail iff reachedGoalRegion; treat "goal region
        // index ≤ last" by checking whether any window region equals the goal region.
        if (skeleton.reachedGoalRegion()) {
            for (int i = windowStart; i <= last; i++) {
                if (skeleton.rx(i) == goalRX && skeleton.ry(i) == goalRY && skeleton.rz(i) == goalRZ) {
                    windowTargetStep = i;
                    windowTargetKind = TargetKind.GOAL;
                    return goalFloor;
                }
            }
        }
        // Fragment model: aim at the FARTHEST window portal that is occupiable. A portal is only the stored
        // bbox CENTROID (lossy) — when it lands in rock we don't give up, because the real opening is still
        // recoverable from the nav grid: snapToOccupiable scans the step's region for the nearest real cell.
        // So we prefer, far→near: (1) a non-buried centroid, else (2) its snapped real cell. Only if NO window
        // step yields either do we fall back to the center projection.
        if (skeleton.isFragmentModel()) {
            final NavGridView grid = new NavGridView(level);
            BlockPos snappedFallback = null; // a snapped cell from the farthest unusable portal, if any
            int snappedStep = last;
            for (int i = last; i > windowStart; i--) {
                if (!skeleton.hasPortal(i)) {
                    continue;
                }
                final BlockPos p = skeleton.portalCell(i);
                final boolean airOK = airTargetOk(i);
                if (isUsableTarget(grid, p, airOK)) {
                    windowTargetStep = i;
                    windowTargetKind = TargetKind.PORTAL;
                    return p; // the stored centroid is itself a usable target — best case
                }
                // Not usable (buried in rock, or a mid-air cell we can't / shouldn't stand at): recover a real
                // cell by scanning ONLY this portal's footprint bbox (§S4) — clamped to the fragment opening,
                // so it's fast and never snaps into a different fragment. Require a STANDABLE cell unless an
                // airborne target is acceptable here (airOK ⇒ place-capable + climbing on, §thought-3).
                if (snappedFallback == null) {
                    BlockPos snapped = snapInFootprint(grid, i, p, !airOK);
                    if (snapped != null) {
                        snappedFallback = snapped;
                        snappedStep = i;
                    }
                }
            }
            if (snappedFallback != null) {
                windowTargetStep = snappedStep;
                windowTargetKind = TargetKind.SNAPPED;
                return snappedFallback;
            }
            // FREE-FALL EXTENSION (HPA-FRAGMENTS.md §S4): the whole window held no standable cell — typically an
            // all-air column (step off a ledge and drop through empty regions to the ground far below). There is
            // no useful place to stop mid-fall, but the bot can just FALL the whole way, so we extend the horizon
            // DOWN the committed skeleton past the window to the first standable cell — the landing — and aim
            // there. The block tier's Fall reaches it in (essentially) one move AND enforces the bot's own
            // maxFallDistance, so a drop too deep to survive simply fails here (→ repair) rather than being
            // wrongly committed. Gated on STRICTLY descending: you can only cheaply fall to a far target going
            // down, so we stop the instant the skeleton stops dropping (never aim the search at a far up/lateral
            // cell). Iterative, bounded by the skeleton length and the first floor found.
            for (int i = last + 1; i < skeleton.size() && skeleton.ry(i) < skeleton.ry(i - 1); i++) {
                if (!skeleton.hasPortal(i)) {
                    continue;
                }
                final BlockPos p = skeleton.portalCell(i);
                if (isUsableTarget(grid, p, false)) { // standable / unbuilt (no air target mid-fall)
                    windowTargetStep = i;
                    windowTargetKind = TargetKind.EXTENDED;
                    return p;
                }
                final BlockPos snapped = snapInFootprint(grid, i, p, true); // require the standable landing floor
                if (snapped != null) {
                    windowTargetStep = i;
                    windowTargetKind = TargetKind.EXTENDED;
                    return snapped;
                }
            }
        }
        windowTargetStep = last;
        windowTargetKind = TargetKind.CENTER;
        BlockPos center = skeleton.centerOf(last);
        BlockPos floor = projectToStandableFloor(center);
        return (floor != null) ? floor : center;
    }

    /**
     * Whether an AIRBORNE (non-standable) window target is acceptable at skeleton step {@code i}: only when the
     * bot can <b>place</b> (so it can pillar up to / hold an air cell) AND a climb is coming — either the next
     * crossing rises ({@code ry(i+1) > ry(i)}, immediate climb) OR the one after it does ({@code ry(i+2) > ry(i)},
     * the 45° staircase: lateral now, up next), so the airborne height feeds that climb (§thought-3). A no-place
     * bot can't reach an air cell at all (and never has an upward-air hop — the region tier excludes those), and a
     * purely lateral/downward stretch gets no benefit from being airborne, so both prefer a standable target.
     *
     * <p>No separate "not descending next" guard is needed: regions are 6-connected, so {@code ry} changes by at
     * most ±1 per step, which means {@code ry(i+2) > ry(i)} can only hold when {@code ry(i+1) >= ry(i)} (you can't
     * climb two regions of height in one move) — so a {@code ry(i+1) < ry(i)} (descend-next) step makes <i>both</i>
     * disjuncts false. Keeping the {@code ry(i+1)} disjunct (rather than {@code ry(i+2)} alone) preserves the
     * up-then-back-down "transit layer" case (rise to swap to a disjoint lower fragment). Lookahead reads the
     * committed skeleton beyond the window; the bounds checks make it false near the skeleton's tail.
     */
    private boolean airTargetOk(int i) {
        if (!caps.canPlace()) {
            return false;
        }
        final int n = skeleton.size();
        final int ryi = skeleton.ry(i);
        return (i + 1 < n && skeleton.ry(i + 1) > ryi)   // immediate climb
                || (i + 2 < n && skeleton.ry(i + 2) > ryi); // staircase: lateral now, climb next
    }

    /**
     * Whether {@code p} is directly usable as a block-A* target. A real <b>standable</b> floor (with headroom)
     * always is; a <b>water</b> cell always is (the bot can swim there — no floor or capability needed); a bare
     * <b>passable</b> (air) cell is usable only when {@code airOK} (we intend to climb up to it); an
     * <b>unbuilt</b> cell counts as usable (optimistic frontier — the block tier resolves real geometry on
     * approach). Buried-in-rock and (when {@code !airOK}) dry mid-air cells are NOT usable → the caller snaps.
     */
    private boolean isUsableTarget(NavGridView grid, BlockPos p, boolean airOK) {
        final int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!grid.built(x, y, z)) {
            return true;
        }
        final long desc = grid.descriptorAt(x, y, z);
        if (NavBlock.isStandable(desc) && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z))) {
            return true;
        }
        // A swimmable WATER cell is occupiable — every bot can swim (no place/break/climb needed), so an
        // underwater opening (passable but not standable, i.e. "air-no-floor" under the surface) is a perfectly
        // good target, NOT a mid-air cell to reject. Without this the window target snapped down to the seafloor
        // (or flooded the center fallback), making the block tier dive/flood instead of swimming to the portal.
        // SWIMMABLE water specifically (full water + no collision), not merely "water present" — a waterlogged
        // solid (water fluid but a collision shape, e.g. a waterlogged fence) is an obstacle you can't float
        // through, so it must NOT count as a target.
        if (NavBlock.isSwimmableWater(desc)) {
            return true;
        }
        return airOK && NavBlock.isPassable(desc);
    }

    /**
     * Recover a real target cell for a portal whose centroid isn't usable (HPA-FRAGMENTS.md §S4). Scans <b>only
     * the portal's footprint bbox</b> — the two in-face axes clamped to the stored footprint (so we never snap
     * into a different fragment, and it's far fewer cells than the whole 16³ region), while the perpendicular
     * axis scans the full region height so an air opening's standable floor BELOW it is found. Returns the cell
     * nearest the centroid that is a standable floor with headroom; when {@code !requireStandable} (an airborne
     * target is allowed here) it falls back to the nearest passable cell. {@code null} when the bbox holds no
     * such cell (the caller then tries a nearer step, else the center projection). Cold path, one bbox scan per
     * unusable target on replan.
     */
    private BlockPos snapInFootprint(NavGridView grid, int step, BlockPos near, boolean requireStandable) {
        final int s = RegionAddress.LEAF_SIZE;
        final int x0 = skeleton.rx(step) << RegionAddress.LEAF_BITS;
        final int y0 = minY + (skeleton.ry(step) << RegionAddress.LEAF_BITS);
        final int z0 = skeleton.rz(step) << RegionAddress.LEAF_BITS;
        // The footprint of this step's entrance fragment, on the face it's entered from.
        final int face = entranceFace(step);
        final RegionFragments rf = regionGrid.fragmentRecord(0, skeleton.rx(step), skeleton.ry(step),
                skeleton.rz(step));
        final int packed = (rf != null && !rf.isUniform())
                ? rf.footprint(skeleton.fragmentId(step), face) : RegionFragments.NO_FACE;
        int uMin = 0, uMax = s - 1, vMin = 0, vMax = s - 1;
        if (packed != RegionFragments.NO_FACE) {
            uMin = RegionFragments.footprintMinU(packed); uMax = RegionFragments.footprintMaxU(packed);
            vMin = RegionFragments.footprintMinV(packed); vMax = RegionFragments.footprintMaxV(packed);
        }
        // Map the (U,V) in-face axes to world ranges per face; the perpendicular axis scans the full region.
        // In-face axes (RegionFragments): ±X → (Y,Z); ±Y → (X,Z); ±Z → (X,Y).
        int xMin = x0, xMax = x0 + s - 1, yMin = y0, yMax = y0 + s - 1, zMin = z0, zMax = z0 + s - 1;
        switch (face >> 1) {
            case 0: yMin = y0 + uMin; yMax = y0 + uMax; zMin = z0 + vMin; zMax = z0 + vMax; break; // ±X (perp X)
            case 1: xMin = x0 + uMin; xMax = x0 + uMax; zMin = z0 + vMin; zMax = z0 + vMax; break; // ±Y (perp Y)
            default: xMin = x0 + uMin; xMax = x0 + uMax; yMin = y0 + vMin; yMax = y0 + vMax; break; // ±Z (perp Z)
        }
        final int nx = near.getX(), ny = near.getY(), nz = near.getZ();
        int bx = 0, by = 0, bz = 0;
        long bestStand = Long.MAX_VALUE;
        BlockPos bestPass = null;
        long bestPassD = Long.MAX_VALUE;
        for (int y = yMin; y <= yMax; y++) {
            for (int z = zMin; z <= zMax; z++) {
                for (int x = xMin; x <= xMax; x++) {
                    if (!grid.built(x, y, z)) {
                        continue;
                    }
                    final long d = sq(x - nx) + sq(y - ny) + sq(z - nz);
                    final long desc = grid.descriptorAt(x, y, z);
                    if (NavBlock.isStandable(desc) && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z))) {
                        if (d < bestStand) {
                            bestStand = d; bx = x; by = y; bz = z;
                        }
                    } else if (!requireStandable && NavBlock.isPassable(desc) && d < bestPassD) {
                        bestPassD = d; bestPass = new BlockPos(x, y, z);
                    }
                }
            }
        }
        if (bestStand != Long.MAX_VALUE) {
            return new BlockPos(bx, by, bz);
        }
        return requireStandable ? null : bestPass;
    }

    /**
     * The face of skeleton step {@code i}'s region that faces the previous step {@code i-1} — the side the bot
     * ENTERS this region from, where its portal footprint lives. Face encoding (matching {@link RegionAddress}):
     * {@code 0=−X, 1=+X, 2=−Y, 3=+Y, 4=−Z, 5=+Z}. (Adjacent skeleton regions differ by ±1 on exactly one axis.)
     */
    private int entranceFace(int i) {
        final int dx = skeleton.rx(i) - skeleton.rx(i - 1);
        final int dy = skeleton.ry(i) - skeleton.ry(i - 1);
        final int dz = skeleton.rz(i) - skeleton.rz(i - 1);
        if (dx > 0) return 0;
        if (dx < 0) return 1;
        if (dy > 0) return 2;
        if (dy < 0) return 3;
        if (dz > 0) return 4;
        return 5;
    }

    private static long sq(int v) {
        return (long) v * v;
    }

    /**
     * Whether {@code p} is a usable block-A* target — i.e. NOT buried in solid rock. A fragment portal cell is
     * the bbox center of a face footprint and can land in solid when the opening is non-convex (§9); aiming the
     * block tier at a sealed cell is the A→B bounce. Usable = built and either passable itself (an air /
     * fall-through cell) or a standable floor with a passable cell just above (room to stand). An <b>unbuilt</b>
     * cell counts as usable (optimistic — the block tier resolves real geometry on approach via its live read).
     */
    private boolean notBuried(NavGridView grid, BlockPos p) {
        final int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!grid.built(x, y, z)) {
            return true;
        }
        if (NavBlock.isPassable(grid.descriptorAt(x, y, z))) {
            return true;
        }
        return NavBlock.isStandable(grid.descriptorAt(x, y, z))
                && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z));
    }

    /**
     * Project a region center down to a standable floor cell within that center's level-0 vertical region
     * (HPA-IMPLEMENTATION.md §9). Scans the 16-tall region column straddling {@code center.y}, nearest-first,
     * for a built cell whose descriptor is {@link NavBlock#isStandable standable}. Returns {@code null} if the
     * column has no standable floor (the caller then uses the raw center). Reads through a fresh
     * {@link NavGridView}; allocation here is bounded (one view, one scan) and happens only on replan.
     */
    private BlockPos projectToStandableFloor(BlockPos center) {
        final NavGridView grid = new NavGridView(level);
        final int cx = center.getX();
        final int cz = center.getZ();
        // The level-0 region this center belongs to, and that region's vertical block span [y0, y0+16).
        final int ry = RegionAddress.regionY(center.getY(), 0, minY);
        final int y0 = minY + (ry << RegionAddress.LEAF_BITS);
        final int y1 = y0 + RegionAddress.LEAF_SIZE;
        // Search outward from the center y so we land on the closest standable floor.
        final int cy = center.getY();
        for (int d = 0; d < RegionAddress.LEAF_SIZE; d++) {
            int yDown = cy - d;
            if (yDown >= y0 && yDown < y1 && grid.built(cx, yDown, cz)
                    && NavBlock.isStandable(grid.descriptorAt(cx, yDown, cz))) {
                return new BlockPos(cx, yDown, cz);
            }
            int yUp = cy + d;
            if (d != 0 && yUp >= y0 && yUp < y1 && grid.built(cx, yUp, cz)
                    && NavBlock.isStandable(grid.descriptorAt(cx, yUp, cz))) {
                return new BlockPos(cx, yUp, cz);
            }
        }
        return null;
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
     * The block tier's goal tolerance (BlockPathfinder.findPath: within 1 block horizontally, 2 vertically of
     * {@code goalFloor}). Mirrored here so the driver completes exactly when the windowed block plan would.
     */
    private boolean withinGoalTolerance(BlockPos floor) {
        return withinTolerance(floor, goalFloor);
    }

    /** The block tier's arrival tolerance (±1 horizontal, ±2 vertical) of an arbitrary target — used for the
     *  final goal and for committing the sliding window when the bot reaches the window target. */
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
