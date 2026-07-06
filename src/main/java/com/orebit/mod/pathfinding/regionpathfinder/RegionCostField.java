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
 * <p>Layout: a flat {@code float[]} indexed {@code regionIndex·MAX_FRAGMENTS + frag}, where
 * {@code regionIndex = (iy·dimZ + iz)·dimX + ix} over the box, so a region's fragment slots are contiguous.
 * Immutable dimensions; {@link #record} (package-private) is written only by the producing Dijkstra.
 */
public final class RegionCostField {

    /** Sentinel cost for a slot the bounded Dijkstra never settled (outside the box, walled off, or unused). */
    public static final float UNREACHED = 1e9f;

    /** Fragment slots per region cell (mirrors {@link RegionFragments#MAX_FRAGMENTS}). */
    private static final int MAX_FRAGMENTS = RegionFragments.MAX_FRAGMENTS;

    /** Per-thread 3-int scratch for the nearest-centroid fragment probe in {@link #costAt}. */
    private static final ThreadLocal<int[]> CENT = ThreadLocal.withInitial(() -> new int[3]);
    private static final ThreadLocal<int[]> TMP = ThreadLocal.withInitial(() -> new int[3]);

    private final int minRx, minRy, minRz;
    private final int dimX, dimY, dimZ;
    private final int minY;
    private final RegionGrid grid;
    private final float[] cost;

    RegionCostField(RegionPathfinder.RegionBox b, int minY, RegionGrid grid) {
        this.minRx = b.minRx;
        this.minRy = b.minRy;
        this.minRz = b.minRz;
        this.dimX = b.maxRx - b.minRx + 1;
        this.dimY = b.maxRy - b.minRy + 1;
        this.dimZ = b.maxRz - b.minRz + 1;
        this.minY = minY;
        this.grid = grid;
        this.cost = new float[dimX * dimY * dimZ * MAX_FRAGMENTS];
        java.util.Arrays.fill(cost, UNREACHED);
    }

    /** Record region {@code (rx,ry,rz)} fragment {@code frag}'s settled cost, keeping its minimum. */
    void record(int rx, int ry, int rz, int frag, float g) {
        int ri = regionIndex(rx, ry, rz);
        if (ri < 0) return;
        int f = frag < 0 ? 0 : (frag >= MAX_FRAGMENTS ? MAX_FRAGMENTS - 1 : frag);
        int i = ri * MAX_FRAGMENTS + f;
        if (g < cost[i]) cost[i] = g;
    }

    /**
     * The goal cost-to-reach for the level-0 region fragment enclosing world cell {@code (wx,wy,wz)}, or
     * {@link #UNREACHED} if that region lies outside the field's box. Region mapping mirrors
     * {@link com.orebit.mod.worldmodel.hpa.RegionAddress} at level 0: {@code rx = wx>>4}, {@code rz = wz>>4},
     * {@code ry = (wy - minY)>>4}. The cell's fragment is resolved by nearest-centroid membership
     * ({@link RegionPathfinder#fragmentOf}); if that specific fragment was never reached, the field falls back to
     * the minimum finite cost over the region's fragments (robust where nearest-centroid disagrees with the
     * Dijkstra's fragment ids).
     */
    public float costAt(int wx, int wy, int wz) {
        int rx = wx >> 4, ry = (wy - minY) >> 4, rz = wz >> 4;
        int ri = regionIndex(rx, ry, rz);
        if (ri < 0) return UNREACHED;
        int frag = RegionPathfinder.fragmentOf(grid, rx, ry, rz, wx, wy, wz, CENT.get(), TMP.get());
        if (frag < 0) frag = 0;
        else if (frag >= MAX_FRAGMENTS) frag = MAX_FRAGMENTS - 1;
        float c = cost[ri * MAX_FRAGMENTS + frag];
        if (c < UNREACHED) return c;
        // Fallback: the cheapest reached fragment of this region.
        float best = UNREACHED;
        int base = ri * MAX_FRAGMENTS;
        for (int i = base; i < base + MAX_FRAGMENTS; i++) {
            if (cost[i] < best) best = cost[i];
        }
        return best < UNREACHED ? best : UNREACHED;
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
                .append(" box (region,fragment) slots reached  (cost = region units;  h≈ = ×4.633 → block ticks, pre-greedy)\n");
        for (int i : order) {
            if (cost[i] >= UNREACHED) {
                break;
            }
            int ri = i / MAX_FRAGMENTS, frag = i % MAX_FRAGMENTS;
            int ix = ri % dimX, iz = (ri / dimX) % dimZ, iy = ri / (dimX * dimZ);
            sb.append(String.format("  (%d,%d,%d)#%d  cost=%.1f  h≈%.0f%n",
                    minRx + ix, minRy + iy, minRz + iz, frag, cost[i], cost[i] * 4.633f));
        }
        return sb.toString();
    }
}
