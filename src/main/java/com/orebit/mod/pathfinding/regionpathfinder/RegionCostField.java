package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

/**
 * A dense per-(region,fragment) <b>cost-to-goal field</b> produced by {@link RegionPathfinder#costToGoalField} —
 * one float per fragment slot of each level-0 region cell inside a {@link RegionPathfinder.RegionBox}, holding the
 * goal-rooted Dijkstra {@code g} of that specific fragment. {@link #UNREACHED} marks a slot the bounded flood never
 * settled (walled off within the box, outside the reached frontier, or an unused fragment slot). The field is a
 * coarse GUIDANCE surface (a block cell reads its enclosing region's fragment cost via {@link #costAt}), not an
 * exact per-block metric.
 *
 * <h2>Frontier-floor (s53, owner-ratified)</h2>
 * A query that resolves to no settled slot — an out-of-box region, or an in-box region the (possibly
 * fat-skeleton-terminated) Dijkstra never settled — returns <b>{@code max(floorCost, cheb × MIN_CROSS)}</b>
 * instead of {@link #UNREACHED}, where {@code cheb} is the Chebyshev region distance from the queried region to
 * the goal region. Both terms are provable LOWER BOUNDS on the slot's true field value:
 * <ul>
 *   <li><b>{@link #floorCost}</b> = the maximum settled cost at termination. The producing search is a plain
 *       Dijkstra ({@code f == g}, non-negative edges), which settles nodes in NONDECREASING {@code g} — so any
 *       slot left unsettled at termination would have settled later at {@code g ≥ floorCost}. (For an
 *       out-of-box region this bound is heuristic only — a path through un-modelled space could be cheaper —
 *       which is the ratified, deliberately inadmissibility-tolerant guidance semantics.)</li>
 *   <li><b>{@code cheb × }{@link #MIN_CROSS}</b> — an independent absolute bound: every relaxed edge moves at
 *       most one region per axis and costs ≥ {@link #MIN_CROSS} (the {@code relaxFrag} per-crossing floor), and
 *       every Dijkstra seed sits within Chebyshev 1 of the goal region at {@code g ≥ cheb(seed) × MIN_CROSS}
 *       (the goal's own pocket seeds at 0; a dig-pocket seed's cost carries ≥ one walk unit) — so by induction
 *       along parents any reachable slot in region {@code R} has {@code g ≥ cheb(R, goalRegion) × MIN_CROSS}.
 *       This term restores a goal-anchored distance gradient over floored space (out-of-box detours keep
 *       goal-ward pull instead of reading one flat value).</li>
 * </ul>
 * The max of two lower bounds is a lower bound. The floor is guidance, never exclusion: {@link #costAt} no
 * longer returns {@link #UNREACHED} (and never returns infinity), so the block heuristic's field term stays
 * live everywhere — the owner's latent-pathology fix for searches forced into a wide detour that previously
 * lost ALL field guidance the moment they left the box.
 *
 * <h2>Intra-region gradient (the refined HPA* heuristic)</h2>
 * The bare fragment {@code g} is a CONSTANT over the whole 16³ region — every block cell reads the same value, so
 * the block A* gets no pull toward the goalward exit and floods each region uniformly. To restore a gradient, each
 * reached slot ALSO records the fragment's <b>goalward exit opening</b> (the crossing cell toward its Dijkstra
 * parent — the goal-rooted flood makes the parent the goalward neighbour) and the <b>onward cost</b> (that
 * neighbour's own cost-to-goal, {@code g} of the parent). {@link #costAt} then returns, for a specific cell:
 * <pre>  octile(cell → exitOpening) · WALK_PER_BLOCK  +  onward     (region units)</pre>
 * a per-cell lower bound that (a) reproduces the plain block octile at the goal fragment ({@code exit = goal,
 * onward = 0}), (b) reads {@code ≈ onward} at the exit cell itself, and (c) grows with distance from the exit —
 * the gradient the constant term lost. It stays a {@code max}-combinable lower bound in the block heuristic.
 *
 * <p>Layout: parallel flat arrays indexed {@code regionIndex·MAX_FRAGMENTS + frag}, where
 * {@code regionIndex = (iy·dimZ + iz)·dimX + ix} over the box, so a region's fragment slots are contiguous.
 * Immutable dimensions; {@link #record} (package-private) is written only by the producing Dijkstra.
 */
public final class RegionCostField {

    /** Sentinel cost for a slot the bounded Dijkstra never settled (outside the box, walled off, or unused).
     *  Internal to the {@code cost[]} array (and the {@code dump()} diagnostic): since the s53 frontier-floor,
     *  {@link #costAt} never RETURNS it — unsettled queries read {@code max(floorCost, cheb × MIN_CROSS)}. */
    public static final float UNREACHED = 1e9f;

    /**
     * The TRUE minimum cost of one region crossing in the field's native units ({@code WALK_PER_BLOCK = 1}
     * region unit ≈ one block-walk tick — the same units {@code cost[]}/{@code onward[]} carry and the block
     * heuristic scales by {@code H_STRAIGHT}). Derivation: the producing Dijkstra relaxes every edge through
     * {@code RegionPathfinder.relaxFrag}, which floors the edge at {@code Math.max(edge, WALK_PER_BLOCK)} "so
     * every boundary crossing costs ≥ one tick" — including the otherwise-cheaper faces (the free unbuilt
     * transit, near-aligned portal walks, and the all-air fall chute, whose raw {@code FALL_PER_BLOCK_FIELD}
     * ≈ 0.54/block cost is floored per crossing). The old center-model directional ENTER/EXIT face costs
     * ({@code LeafCostComputer}/{@code CostPyramid}) do not participate in this level-0 fragment-edge field.
     * So 1.0 is the provable per-crossing minimum, and {@code cheb × MIN_CROSS} is an absolute lower bound on
     * any goal-to-region path cost (see the class Javadoc).
     */
    public static final float MIN_CROSS = RegionPathfinder.WALK_PER_BLOCK;

    /** Fragment slots per region cell (mirrors {@link RegionFragments#MAX_FRAGMENTS}). */
    private static final int MAX_FRAGMENTS = RegionFragments.MAX_FRAGMENTS;

    /** √2, the octile diagonal factor for the intra-region distance-to-exit term (mirrors {@link RegionPathfinder}). */
    private static final float SQRT2 = 1.4142135f;

    /** Per-thread 3-int scratch for the nearest-centroid fragment probe in {@link #costAt}. */
    private static final ThreadLocal<int[]> CENT = ThreadLocal.withInitial(() -> new int[3]);
    private static final ThreadLocal<int[]> TMP = ThreadLocal.withInitial(() -> new int[3]);

    private final int minRx, minRy, minRz;
    private final int dimX, dimY, dimZ;
    private final int minY;
    private final RegionGrid grid;
    private final float[] cost;
    // Per-slot goalward exit opening (world cell of the crossing toward the Dijkstra parent) + onward cost (that
    // parent's cost-to-goal). Only meaningful where cost[i] < UNREACHED; the gradient term reads them in costAt.
    private final int[] exitX, exitY, exitZ;
    private final float[] onward;
    // The goal's level-0 region cell — the anchor of the cheb × MIN_CROSS distance term (class Javadoc).
    private final int goalRx, goalRy, goalRz;
    /**
     * The frontier floor: the maximum settled cost at the producing Dijkstra's termination — a provable lower
     * bound on every unsettled in-box slot's true field value (Dijkstra settles in nondecreasing {@code g};
     * see the class Javadoc). Written once by {@link #setFloor} at build termination; {@code 0} (the trivial
     * bound) until then / when nothing settled.
     */
    private float floorCost;
    /**
     * Diagnostic (package-private, tests + trace): the fat-skeleton chain the producing Dijkstra terminated
     * on, as {@code (rx,ry,rz)} triplets goal→start along best predecessors — {@code null} when the build ran
     * to exhaustion (no early exit). One small array per build; not read on any hot path.
     */
    int[] chainRegions;

    RegionCostField(RegionPathfinder.RegionBox b, int minY, RegionGrid grid, int goalRx, int goalRy, int goalRz) {
        this.minRx = b.minRx;
        this.minRy = b.minRy;
        this.minRz = b.minRz;
        this.dimX = b.maxRx - b.minRx + 1;
        this.dimY = b.maxRy - b.minRy + 1;
        this.dimZ = b.maxRz - b.minRz + 1;
        this.minY = minY;
        this.grid = grid;
        this.goalRx = goalRx;
        this.goalRy = goalRy;
        this.goalRz = goalRz;
        int slots = dimX * dimY * dimZ * MAX_FRAGMENTS;
        this.cost = new float[slots];
        this.exitX = new int[slots];
        this.exitY = new int[slots];
        this.exitZ = new int[slots];
        this.onward = new float[slots];
        java.util.Arrays.fill(cost, UNREACHED);
    }

    /** Set the frontier floor (the maximum settled cost) — written once by the producing Dijkstra at termination. */
    void setFloor(float floor) {
        this.floorCost = floor;
    }

    /** The frontier floor (package-private — the invariant unit tests read it; consumers go through {@link #costAt}). */
    float floor() {
        return floorCost;
    }

    /**
     * Record region {@code (rx,ry,rz)} fragment {@code frag}'s settled cost, keeping its minimum. When the cost
     * improves, also captures the fragment's goalward exit opening {@code (ex,ey,ez)} (the crossing cell toward
     * the Dijkstra parent) and the {@code onward} cost (that parent's cost-to-goal) so {@link #costAt} can build
     * the intra-region distance-to-exit gradient.
     */
    void record(int rx, int ry, int rz, int frag, float g, int ex, int ey, int ez, float onwardCost) {
        int ri = regionIndex(rx, ry, rz);
        if (ri < 0) return;
        int f = frag < 0 ? 0 : (frag >= MAX_FRAGMENTS ? MAX_FRAGMENTS - 1 : frag);
        int i = ri * MAX_FRAGMENTS + f;
        if (g < cost[i]) {
            cost[i] = g;
            exitX[i] = ex; exitY[i] = ey; exitZ[i] = ez;
            onward[i] = onwardCost;
        }
    }

    /**
     * The goal cost-to-reach for the level-0 region fragment enclosing world cell {@code (wx,wy,wz)}. Region
     * mapping mirrors {@link com.orebit.mod.worldmodel.hpa.RegionAddress} at level 0: {@code rx = wx>>4},
     * {@code rz = wz>>4}, {@code ry = (wy - minY)>>4}. The cell's fragment is resolved by nearest-centroid
     * membership ({@link RegionPathfinder#fragmentOf}); if that specific fragment was never reached, the field
     * falls back to the cheapest reached fragment of the region (robust where nearest-centroid disagrees with
     * the Dijkstra's fragment ids). The returned value is the {@link #record recorded} slot's {@code onward}
     * cost PLUS the octile distance from the cell to that slot's goalward exit opening — the intra-region
     * gradient, in region units.
     *
     * <p>When no settled slot resolves — the region lies outside the field's box, or the (possibly
     * fat-skeleton-terminated) Dijkstra never settled any of its fragments — the query returns the
     * frontier-floor bound {@code max(floorCost, cheb × MIN_CROSS)} (class Javadoc) instead of the old
     * {@link #UNREACHED} sentinel. Never infinity: the floor is guidance, not exclusion.
     */
    public float costAt(int wx, int wy, int wz) {
        int rx = wx >> 4, ry = (wy - minY) >> 4, rz = wz >> 4;
        int ri = regionIndex(rx, ry, rz);
        if (ri < 0) return floorAt(rx, ry, rz);
        int frag = RegionPathfinder.fragmentOf(grid, rx, ry, rz, wx, wy, wz, CENT.get(), TMP.get());
        if (frag < 0) frag = 0;
        else if (frag >= MAX_FRAGMENTS) frag = MAX_FRAGMENTS - 1;
        int slot = ri * MAX_FRAGMENTS + frag;
        if (cost[slot] >= UNREACHED) {
            // Fallback: the cheapest reached fragment of this region (nearest-centroid disagreed with the flood).
            slot = cheapestReachedSlot(ri);
            if (slot < 0) return floorAt(rx, ry, rz);
        }
        // Intra-region gradient: distance to the goalward exit opening + that exit's onward cost-to-goal.
        return octileToExit(wx - exitX[slot], wy - exitY[slot], wz - exitZ[slot]) + onward[slot];
    }

    /**
     * The frontier-floor read for a region with no settled slot: {@code max(floorCost, cheb(R, goalRegion) ×
     * MIN_CROSS)} — the max of the two lower bounds derived in the class Javadoc. The {@code cheb} term keeps a
     * goal-anchored gradient over floored space (out-of-box included), so a search forced off the field still
     * feels goal-ward pull instead of one flat value.
     */
    private float floorAt(int rx, int ry, int rz) {
        int dx = Math.abs(rx - goalRx), dy = Math.abs(ry - goalRy), dz = Math.abs(rz - goalRz);
        int cheb = Math.max(dx, Math.max(dy, dz));
        float distBound = cheb * MIN_CROSS;
        return distBound > floorCost ? distBound : floorCost;
    }

    /**
     * Raw settled cost of slot {@code (rx,ry,rz,frag)} — {@link #UNREACHED} when unsettled or out of box.
     * Package-private test seam (the fat-skeleton invariant tests compare exhaustive vs early-exit builds
     * slot-by-slot); production reads go through {@link #costAt}.
     */
    float rawCost(int rx, int ry, int rz, int frag) {
        int ri = regionIndex(rx, ry, rz);
        if (ri < 0 || frag < 0 || frag >= MAX_FRAGMENTS) return UNREACHED;
        return cost[ri * MAX_FRAGMENTS + frag];
    }

    /** The cheapest reached fragment slot of region row {@code ri}, or {@code -1} if none was settled. */
    private int cheapestReachedSlot(int ri) {
        int base = ri * MAX_FRAGMENTS;
        int best = -1;
        float bestCost = UNREACHED;
        for (int i = base; i < base + MAX_FRAGMENTS; i++) {
            if (cost[i] < bestCost) { bestCost = cost[i]; best = i; }
        }
        return best;
    }

    /**
     * Octile distance (region units, {@code WALK_PER_BLOCK = 1}/block) from a cell to a goalward exit opening:
     * a 2D horizontal octile plus {@code |dy|}. A pure lower bound on intra-region travel — the block heuristic
     * {@code max}es it against the plain block octile, so the exact vertical weighting is not critical here.
     */
    private static float octileToExit(int dx, int dy, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        int lo = Math.min(ax, az), hi = Math.max(ax, az);
        return hi + (SQRT2 - 1f) * lo + Math.abs(dy);
    }

    /** The region-slot base index (region cost row), or {@code -1} if the region lies outside the box. */
    private int regionIndex(int rx, int ry, int rz) {
        int ix = rx - minRx, iy = ry - minRy, iz = rz - minRz;
        if (ix < 0 || ix >= dimX || iy < 0 || iy >= dimY || iz < 0 || iz >= dimZ) return -1;
        return (iy * dimZ + iz) * dimX + ix;
    }

    /**
     * Diagnostic dump of every reached (region,fragment), sorted cheapest-first: its region-unit cost-to-goal and
     * the block-tick heuristic contribution it produces ({@code cost × Traverse.FLAT_COST}, before the greedy
     * weight). Written into the {@code /bot trace} region dump so the field values are inspectable — in
     * particular whether pillar/fall-heavy routes are over-priced vs the block tier, and whether the goal
     * region reads 0 (the same-region blindness).
     */
    public String dump() {
        int reached = 0;
        for (float c : cost) {
            if (c < UNREACHED) reached++;
        }
        Integer[] order = new Integer[cost.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(cost[a], cost[b]));
        StringBuilder sb = new StringBuilder();
        sb.append("region cost-to-goal field: ").append(reached).append(" of ").append(cost.length)
                .append(" box (region,fragment) slots reached  (cost = region units;  h≈ = ×4.633 → block ticks, pre-greedy;")
                .append("  onward = parent cost-to-goal;  exit = goalward opening for the intra-region gradient)\n");
        sb.append(String.format("  frontier floor=%.1f  (unsettled/out-of-box reads = max(floor, cheb×%.1f));  %s%n",
                floorCost, MIN_CROSS,
                chainRegions == null ? "exhaustive build (no fat-skeleton exit)"
                        : "fat-skeleton early exit, chain=" + (chainRegions.length / 3) + " steps"));
        for (int i : order) {
            if (cost[i] >= UNREACHED) {
                break;
            }
            int ri = i / MAX_FRAGMENTS, frag = i % MAX_FRAGMENTS;
            int ix = ri % dimX, iz = (ri / dimX) % dimZ, iy = ri / (dimX * dimZ);
            sb.append(String.format("  (%d,%d,%d)#%d  cost=%.1f  h≈%.0f  onward=%.1f  exit=(%d,%d,%d)%n",
                    minRx + ix, minRy + iy, minRz + iz, frag, cost[i], cost[i] * 4.633f,
                    onward[i], exitX[i], exitY[i], exitZ[i]));
        }
        return sb.toString();
    }
}
