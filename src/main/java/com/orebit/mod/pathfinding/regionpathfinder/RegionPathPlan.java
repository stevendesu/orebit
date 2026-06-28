package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.worldmodel.hpa.RegionAddress;

import net.minecraft.core.BlockPos;

/**
 * The coarse navigation skeleton produced by {@link RegionPathfinder}: an <b>immutable, ordered sequence
 * of level-0 region addresses</b> from the start region to the goal region
 * (PRD §6.3–6.5, §7.1; HPA-IMPLEMENTATION.md §8, "3g output").
 *
 * <h2>Ratified design — face-to-center, NOT portals</h2>
 * The region tier is a <b>fixed cubic-grid implicit octree</b> (PRD §6.3), NOT the superseded semantic
 * {@code Region}/{@code Portal} flood-fill model. A {@code RegionPathPlan} therefore carries no portals,
 * no region objects, and no per-step edge metadata — it is purely the list of <b>level-0 region cells</b>
 * (each a single 16³ {@link com.orebit.mod.worldmodel.pathing.NavSection NavSection}) the bot should walk
 * through, in travel order. Index {@code 0} is the start region; the last index is the goal region (or, in
 * the lazy-refinement scale-guard case, the end of the refined leading segment — HPA-IMPLEMENTATION.md §8).
 *
 * <p>"Which regions, in what order" is the entire contract. <b>How</b> to move within / between regions is
 * decided by the block tier ({@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}); any
 * traversable arrival into the next region is acceptable (no entrances, PRD §6.5). The
 * {@link com.orebit.mod.pathfinding.PathPlan} sliding-window driver consumes this skeleton, picking a
 * windowed block target every few regions.
 *
 * <h2>Storage (house style — HPA-IMPLEMENTATION.md §14)</h2>
 * Three parallel {@code int[]} arrays of the skeleton's level-0 region coords ({@code rxs/rys/rzs}); no
 * per-step objects, no boxing. {@link #centerOf(int)} materializes a {@link BlockPos} on demand from
 * {@link RegionAddress#centerX}/{@link RegionAddress#centerY}/{@link RegionAddress#centerZ} (level 0). The
 * arrays are sized exactly at construction and never mutated — the plan is immutable once built.
 *
 * @see RegionPathfinder
 * @see com.orebit.mod.pathfinding.PathPlan
 */
public final class RegionPathPlan {

    /** Level-0 region coords of each skeleton step, in travel order (index 0 = start region). */
    private final int[] rxs;
    private final int[] rys;
    private final int[] rzs;

    /**
     * Per-step <b>fragment id</b> under the HPA* fragment model (HPA-FRAGMENTS.md §2, §S3): which 6-connected
     * occupiable component of {@code rxs/rys/rzs[i]} this step commits to (uniform/collapsed regions ⇒ the
     * single synthetic fragment {@code 0}). {@code null} for a center-model plan (the
     * {@link com.orebit.mod.worldmodel.hpa.RegionGrid#HPA_FRAGMENTS}{@code == false} branch + the deferred-S5
     * coarse branch), where there is exactly one node per region.
     */
    private final int[] frags;

    /**
     * Per-step <b>portal cell</b> — the world-block boundary cell where this step is entered from the previous
     * one (the matched-footprint overlap center for a portal edge, the target fragment's interior rep for an
     * intra-region mine edge, or the region face center for a uniform transit). It is the reachable
     * occupiable target the {@link com.orebit.mod.pathfinding.PathPlan} sliding-window driver aims at (S4),
     * replacing the geometric {@link #centerOf} projection that landed on buried/mid-air cells (the two bugs
     * HPA-FRAGMENTS.md §6 fixes). {@link #NO_PORTAL} on the start step (index 0, no incoming edge) and on every
     * step of a center-model plan ({@code portalX == null}).
     */
    private final int[] portalX;
    private final int[] portalY;
    private final int[] portalZ;

    /** Sentinel in {@link #portalX} for a step with no portal cell (the start step, or a center-model plan). */
    public static final int NO_PORTAL = Integer.MIN_VALUE;

    /** The dimension floor, needed to recover world-Y centers from region {@code ry} (overworld −64). */
    private final int minY;

    /** Whether the skeleton's last region is the actual goal region (vs a refined leading segment end). */
    private final boolean reachedGoalRegion;

    /**
     * Build the immutable skeleton from parallel coord arrays in travel order. The arrays are taken by
     * reference (the caller in {@link RegionPathfinder} hands over freshly-sized arrays it does not retain);
     * callers must not mutate them afterward.
     *
     * @param rxs   level-0 region X per step, index 0 = start
     * @param rys   level-0 region Y per step
     * @param rzs   level-0 region Z per step
     * @param size  number of valid leading entries (the arrays may be exactly this length)
     * @param minY  dimension floor (for {@link #centerOf})
     * @param reachedGoalRegion whether the last entry is the true goal region
     */
    public RegionPathPlan(int[] rxs, int[] rys, int[] rzs, int size, int minY, boolean reachedGoalRegion) {
        // Trim to the valid prefix so size()/indexing is exact and the plan is truly immutable.
        if (rxs.length != size) {
            int[] tx = new int[size];
            int[] ty = new int[size];
            int[] tz = new int[size];
            System.arraycopy(rxs, 0, tx, 0, size);
            System.arraycopy(rys, 0, ty, 0, size);
            System.arraycopy(rzs, 0, tz, 0, size);
            this.rxs = tx;
            this.rys = ty;
            this.rzs = tz;
        } else {
            this.rxs = rxs;
            this.rys = rys;
            this.rzs = rzs;
        }
        this.frags = null;          // center-model plan: one node per region, no fragments / portals
        this.portalX = null;
        this.portalY = null;
        this.portalZ = null;
        this.minY = minY;
        this.reachedGoalRegion = reachedGoalRegion;
    }

    /**
     * Build the immutable <b>fragment-model</b> skeleton (HPA-FRAGMENTS.md §S3): the same level-0 region coords
     * plus, per step, the committed {@code fragmentId} and the {@code portalCell} it is entered through. All
     * seven arrays are parallel and trimmed to {@code size}; the caller hands over freshly-sized arrays it does
     * not retain. Portal coords of {@link #NO_PORTAL} mark a step with no incoming edge (the start step).
     */
    public RegionPathPlan(int[] rxs, int[] rys, int[] rzs, int[] frags,
                          int[] portalX, int[] portalY, int[] portalZ,
                          int size, int minY, boolean reachedGoalRegion) {
        this.rxs = trim(rxs, size);
        this.rys = trim(rys, size);
        this.rzs = trim(rzs, size);
        this.frags = trim(frags, size);
        this.portalX = trim(portalX, size);
        this.portalY = trim(portalY, size);
        this.portalZ = trim(portalZ, size);
        this.minY = minY;
        this.reachedGoalRegion = reachedGoalRegion;
    }

    /** Trim {@code a} to exactly {@code size} (returns it unchanged when already that length). */
    private static int[] trim(int[] a, int size) {
        if (a.length == size) {
            return a;
        }
        int[] t = new int[size];
        System.arraycopy(a, 0, t, 0, size);
        return t;
    }

    /** Number of skeleton regions (0 for an empty/failed plan). */
    public int size() {
        return rxs.length;
    }

    /** {@code true} iff this plan has no regions. */
    public boolean isEmpty() {
        return rxs.length == 0;
    }

    /** Whether the final skeleton region is the true goal region (vs the end of a refined leading segment). */
    public boolean reachedGoalRegion() {
        return reachedGoalRegion;
    }

    /** Level-0 region X of skeleton step {@code i}. */
    public int rx(int i) {
        return rxs[i];
    }

    /** Level-0 region Y of skeleton step {@code i}. */
    public int ry(int i) {
        return rys[i];
    }

    /** Level-0 region Z of skeleton step {@code i}. */
    public int rz(int i) {
        return rzs[i];
    }

    /** {@code true} iff this is a fragment-model plan (carries per-step {@code fragmentId} + {@code portalCell}). */
    public boolean isFragmentModel() {
        return frags != null;
    }

    /**
     * The committed fragment id of skeleton step {@code i} (HPA-FRAGMENTS.md §2): which 6-connected occupiable
     * component of region {@code i} the path passes through. Always {@code 0} for a center-model plan (one node
     * per region) and for uniform/collapsed regions (a single synthetic fragment).
     */
    public int fragmentId(int i) {
        return frags == null ? 0 : frags[i];
    }

    /** Whether step {@code i} has a portal cell (false on the start step and on every center-model step). */
    public boolean hasPortal(int i) {
        return portalX != null && portalX[i] != NO_PORTAL;
    }

    /**
     * The world-block <b>portal cell</b> step {@code i} is entered through (HPA-FRAGMENTS.md §6) — a reachable
     * occupiable boundary cell, the fragment-model replacement for the geometric {@link #centerOf} projection.
     * {@code null} when {@link #hasPortal(int)} is false (the start step / a center-model plan); the driver
     * falls back to {@link #centerOf} there.
     */
    public BlockPos portalCell(int i) {
        return hasPortal(i) ? new BlockPos(portalX[i], portalY[i], portalZ[i]) : null;
    }

    /**
     * The world-block center of skeleton region {@code i} (level 0). Materializes a {@link BlockPos} from
     * {@link RegionAddress}'s level-0 center math; the driver projects this to a standable floor cell to use
     * it as a windowed block target (HPA-IMPLEMENTATION.md §9).
     */
    public BlockPos centerOf(int i) {
        int cx = RegionAddress.centerX(0, rxs[i]);
        int cy = RegionAddress.centerY(0, rys[i], minY);
        int cz = RegionAddress.centerZ(0, rzs[i]);
        return new BlockPos(cx, cy, cz);
    }
}
