package com.orebit.mod.pathfinding.regionpathfinder;

/**
 * A dense per-region <b>cost-to-goal field</b> produced by {@link RegionPathfinder#costToGoalField} — one float
 * per level-0 region cell inside a {@link RegionPathfinder.RegionBox}, holding the goal-rooted Dijkstra {@code g}
 * (min over that region's fragments). {@link #UNREACHED} marks a cell the bounded flood never settled (walled
 * off within the box, or simply outside the reached frontier). The field is a coarse GUIDANCE surface (a block
 * cell reads its enclosing region's cost via {@link #costAt}), not an exact per-block metric.
 *
 * <p>Layout: a flat {@code float[]} indexed {@code (iy·dimZ + iz)·dimX + ix} over the box, so a horizontal
 * region row is contiguous. Immutable dimensions; {@link #record} (package-private) is written only by the
 * producing Dijkstra.
 */
public final class RegionCostField {

    /** Sentinel cost for a region the bounded Dijkstra never settled (outside the box, or walled off within it). */
    public static final float UNREACHED = 1e9f;

    private final int minRx, minRy, minRz;
    private final int dimX, dimY, dimZ;
    private final int minY;
    private final float[] cost;

    RegionCostField(RegionPathfinder.RegionBox b, int minY) {
        this.minRx = b.minRx;
        this.minRy = b.minRy;
        this.minRz = b.minRz;
        this.dimX = b.maxRx - b.minRx + 1;
        this.dimY = b.maxRy - b.minRy + 1;
        this.dimZ = b.maxRz - b.minRz + 1;
        this.minY = minY;
        this.cost = new float[dimX * dimY * dimZ];
        java.util.Arrays.fill(cost, UNREACHED);
    }

    /** Record region {@code (rx,ry,rz)}'s settled cost, keeping the minimum over that region's fragments. */
    void record(int rx, int ry, int rz, float g) {
        int i = index(rx, ry, rz);
        if (i >= 0 && g < cost[i]) cost[i] = g;
    }

    /**
     * The goal cost-to-reach for the level-0 region enclosing world cell {@code (wx,wy,wz)}, or {@link #UNREACHED}
     * if that region lies outside the field's box. Region mapping mirrors {@link com.orebit.mod.worldmodel.hpa.RegionAddress}
     * at level 0: {@code rx = wx>>4}, {@code rz = wz>>4}, {@code ry = (wy - minY)>>4}.
     */
    public float costAt(int wx, int wy, int wz) {
        int rx = wx >> 4, ry = (wy - minY) >> 4, rz = wz >> 4;
        int i = index(rx, ry, rz);
        return i >= 0 ? cost[i] : UNREACHED;
    }

    private int index(int rx, int ry, int rz) {
        int ix = rx - minRx, iy = ry - minRy, iz = rz - minRz;
        if (ix < 0 || ix >= dimX || iy < 0 || iy >= dimY || iz < 0 || iz >= dimZ) return -1;
        return (iy * dimZ + iz) * dimX + ix;
    }
}
