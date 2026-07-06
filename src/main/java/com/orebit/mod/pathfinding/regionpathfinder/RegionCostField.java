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

    /** Sentinel cost for a slot the bounded Dijkstra never settled (outside the box, walled off, or unused). */
    public static final float UNREACHED = 1e9f;

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

    RegionCostField(RegionPathfinder.RegionBox b, int minY, RegionGrid grid) {
        this.minRx = b.minRx;
        this.minRy = b.minRy;
        this.minRz = b.minRz;
        this.dimX = b.maxRx - b.minRx + 1;
        this.dimY = b.maxRy - b.minRy + 1;
        this.dimZ = b.maxRz - b.minRz + 1;
        this.minY = minY;
        this.grid = grid;
        int slots = dimX * dimY * dimZ * MAX_FRAGMENTS;
        this.cost = new float[slots];
        this.exitX = new int[slots];
        this.exitY = new int[slots];
        this.exitZ = new int[slots];
        this.onward = new float[slots];
        java.util.Arrays.fill(cost, UNREACHED);
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
     * The goal cost-to-reach for the level-0 region fragment enclosing world cell {@code (wx,wy,wz)}, or
     * {@link #UNREACHED} if that region lies outside the field's box. Region mapping mirrors
     * {@link com.orebit.mod.worldmodel.hpa.RegionAddress} at level 0: {@code rx = wx>>4}, {@code rz = wz>>4},
     * {@code ry = (wy - minY)>>4}. The cell's fragment is resolved by nearest-centroid membership
     * ({@link RegionPathfinder#fragmentOf}); if that specific fragment was never reached, the field falls back to
     * the cheapest reached fragment of the region (robust where nearest-centroid disagrees with the Dijkstra's
     * fragment ids). The returned value is the {@link #record recorded} slot's {@code onward} cost PLUS the octile
     * distance from the cell to that slot's goalward exit opening — the intra-region gradient, in region units.
     */
    public float costAt(int wx, int wy, int wz) {
        int rx = wx >> 4, ry = (wy - minY) >> 4, rz = wz >> 4;
        int ri = regionIndex(rx, ry, rz);
        if (ri < 0) return UNREACHED;
        int frag = RegionPathfinder.fragmentOf(grid, rx, ry, rz, wx, wy, wz, CENT.get(), TMP.get());
        if (frag < 0) frag = 0;
        else if (frag >= MAX_FRAGMENTS) frag = MAX_FRAGMENTS - 1;
        int slot = ri * MAX_FRAGMENTS + frag;
        if (cost[slot] >= UNREACHED) {
            // Fallback: the cheapest reached fragment of this region (nearest-centroid disagreed with the flood).
            slot = cheapestReachedSlot(ri);
            if (slot < 0) return UNREACHED;
        }
        // Intra-region gradient: distance to the goalward exit opening + that exit's onward cost-to-goal.
        return octileToExit(wx - exitX[slot], wy - exitY[slot], wz - exitZ[slot]) + onward[slot];
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
