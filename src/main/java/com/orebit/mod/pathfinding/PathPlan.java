package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
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

    /** When true, log the region skeleton + window target + corridor box per block replan (mirrors
     *  {@code BlockPathfinder.DEBUG}); the HPA*-tier visibility for "what is the driver asking the block
     *  tier to do". Flip off to ship. */
    public static boolean DEBUG = true;

    // ---- immutable inputs ----------------------------------------------------------------------------
    private final ServerLevel level;
    private final RegionGrid regionGrid;
    private final BlockPos goalFloor;
    private final BotCaps caps;
    private final int minY;
    /** The goal's level-0 region coords (so we can test "goal in window" by index). */
    private final int goalRX, goalRY, goalRZ;

    // ---- skeleton + window state ---------------------------------------------------------------------
    private final RegionPathPlan skeleton;
    /** Index into the skeleton of the window's leading (start) region. */
    private int windowStart;
    /** Furthest skeleton index the bot has committed to (HPA-IMPLEMENTATION.md §9, the wiggle anchor). */
    private int committedIndex;

    // ---- debounce fallback state ---------------------------------------------------------------------
    /** The skeleton index the bot's floor currently sits in, for the consecutive-ticks debounce. */
    private int debounceIndex = -1;
    private int debounceTicks;

    // ---- active block plan ---------------------------------------------------------------------------
    private BlockPathPlan blockPlan;
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
    private RegionBound windowCorridor;

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
        this.level = level;
        this.regionGrid = regionGrid;
        this.goalFloor = goalFloor;
        this.caps = caps;
        this.minY = regionGrid.minY();
        this.botFloor = startFloor;

        this.goalRX = RegionAddress.regionX(goalFloor.getX(), 0);
        this.goalRY = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        this.goalRZ = RegionAddress.regionZ(goalFloor.getZ(), 0);

        this.skeleton = RegionPathfinder.plan(level, regionGrid, startFloor, goalFloor);
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

        final int last = windowLast();
        final int curRegion = forwardIndexOf(botFloor, committedIndex, last);

        if (curRegion > committedIndex && committed(curRegion)) {
            // A real forward step: the path no longer revisits any region in [committedIndex, curRegion).
            committedIndex = curRegion;
            windowStart = curRegion;
            debounceIndex = -1;
            debounceTicks = 0;
            replanBlock();
            return;
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
        final RegionBound bound = corridorBound(target);
        this.windowTargetPos = target; // expose this window's target + corridor for /bot trace
        this.windowCorridor = bound;

        if (DEBUG) {
            OrebitCommon.LOGGER.info(
                    "[Orebit] HPA window: skeleton={}regions committed={} window=[{}..{}] of {} "
                            + "bot=({},{},{}) target=({},{},{}) goalInWindow={} corridor={}",
                    skeleton.size(), committedIndex, windowStart, windowLast(), skeleton.size() - 1,
                    botFloor.getX(), botFloor.getY(), botFloor.getZ(),
                    target.getX(), target.getY(), target.getZ(), target.equals(goalFloor), bound);
        }

        BlockPathPlan plan = BlockPathfinder.findPath(grid, botFloor, target, caps, bound);
        if (plan == null) {
            // Widen-on-failure (HPA-IMPLEMENTATION.md §9): a too-tight corridor can wall off a real crossing
            // (e.g. the skeleton bent more than the margin, or the only ascent is a wide pillar base). Widen
            // once before giving up, so the bound never permanently traps a solvable path. Still null after
            // the widen → genuinely BLOCKED (terrain changed / no route), and AllyBotEntity's visible
            // straight-line fallback takes over (the milestone wants pathological failure visible).
            plan = BlockPathfinder.findPath(grid, botFloor, target, caps,
                    bound.widened(CORRIDOR_MARGIN, CORRIDOR_VMARGIN));
        }
        this.blockPlan = plan;
        this.status = (plan != null) ? PathStatus.RUNNING : PathStatus.BLOCKED;
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
     * The current window's block target (HPA-IMPLEMENTATION.md §9). If the goal's level-0 region index is ≤
     * the window's far index (goal in window), the real {@code goalFloor}. Otherwise the far window region's
     * world center, projected down to a standable floor cell in that region's column; if no standable cell is
     * found, the raw center (block-A* will still approach it — any traversable arrival is fine, no entrances).
     */
    private BlockPos windowTarget() {
        final int last = windowLast();
        // Goal in window? The goal region is the skeleton's tail iff reachedGoalRegion; treat "goal region
        // index ≤ last" by checking whether any window region equals the goal region.
        if (skeleton.reachedGoalRegion()) {
            for (int i = windowStart; i <= last; i++) {
                if (skeleton.rx(i) == goalRX && skeleton.ry(i) == goalRY && skeleton.rz(i) == goalRZ) {
                    return goalFloor;
                }
            }
        }
        BlockPos center = skeleton.centerOf(last);
        BlockPos floor = projectToStandableFloor(center);
        return (floor != null) ? floor : center;
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
     * The skeleton index in {@code [lo, hi]} whose level-0 region coords equal {@code floor}'s region, or
     * {@code committedIndex} (unchanged) if none match — a forward-only search (the bot only advances).
     */
    private int forwardIndexOf(BlockPos floor, int lo, int hi) {
        final int frx = RegionAddress.regionX(floor.getX(), 0);
        final int fry = RegionAddress.regionY(floor.getY(), 0, minY);
        final int frz = RegionAddress.regionZ(floor.getZ(), 0);
        for (int i = lo; i <= hi; i++) {
            if (skeleton.rx(i) == frx && skeleton.ry(i) == fry && skeleton.rz(i) == frz) {
                return i;
            }
        }
        return committedIndex;
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
        return Math.abs(floor.getX() - goalFloor.getX()) <= 1
                && Math.abs(floor.getZ() - goalFloor.getZ()) <= 1
                && Math.abs(floor.getY() - goalFloor.getY()) <= 2;
    }
}
