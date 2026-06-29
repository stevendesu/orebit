package com.orebit.mod.pathfinding.regionpathfinder.heuristics;

import com.orebit.mod.pathfinding.regionpathfinder.RegionHeuristic;
import com.orebit.mod.worldmodel.hpa.LeafCostComputer;

/**
 * The ratified default region-tier heuristic: Euclidean distance between region centers, in region units,
 * scaled by the minimum possible per-region crossing cost
 * (PRD §6.3–6.5, §7.4; HPA-IMPLEMENTATION.md §8.1).
 *
 * <p>Operates on <b>level-0 region coordinates</b>. The distance is the straight-line (Euclidean) span
 * between the two regions measured in <b>region units</b> (one unit = one level-0 region cell, i.e. one
 * {@code LEAF_SIZE}=16-block cube per side), multiplied by {@link #COST_PER_REGION} — the realistic cost of
 * one straight walk across a single region.
 *
 * <p><b>Why a realistic per-region cost, not the absolute floor (weighted A*):</b> the strictly-admissible
 * floor is the cheapest a single boundary crossing can <i>ever</i> cost ({@code CostCodec.dequantize(0)} = 1
 * tick — two perfectly-overlapping portal footprints). Using it made the heuristic ~16–96× weaker than real
 * per-region cost, so the region A* degenerated to a Dijkstra <b>flood</b> and exhausted its expansion budget
 * a fraction of the way to a far goal (the long-range {@code plan: NONE} bug). We instead scale by
 * {@link #COST_PER_REGION} — the cost of one straight <b>walk across</b> a region ({@code AIR_TRANSIT_TICKS} =
 * 16, the same unit the per-block walk edge cost uses), the realistic cheapest <i>meaningful</i> crossing.
 * This is mildly <b>inadmissible</b> (a rare footprint-coincident crossing can be cheaper), so the macro route
 * may be slightly suboptimal — acceptable by design: region costs are <b>ordinal</b> and the block tier is the
 * source of truth, refining the real moves within each window. The Euclidean (not Manhattan/Chebyshev) region
 * distance keeps the estimate conservative where diagonal region moves are unavailable.
 *
 * <p><b>Hot path (house style):</b> pure allocation-free arithmetic — three int subtractions, a
 * {@link Math#sqrt} of the squared distance, one multiply. Returns {@code 0} exactly when the candidate
 * region equals the goal region.
 *
 * <p>Fast and generic; works well in open-world layouts with mostly uniform regions. Richer heuristics
 * (portal-count, tag-aware, exploration-bias, verticality-penalty) remain deferred stubs.
 */
public final class SimpleRegionHeuristic implements RegionHeuristic {

    /**
     * The realistic cheapest cost to cross one region — one straight <b>walk across</b> a leaf
     * ({@link LeafCostComputer#AIR_TRANSIT_TICKS} = 16 ticks, the per-block walk cost × the 16-block side). This
     * is the heuristic scale: strong enough to guide the region A* near-straight to the goal (no Dijkstra
     * flood), at the cost of mild inadmissibility vs the absolute 1-tick floor — see the class doc.
     */
    public static final float COST_PER_REGION = LeafCostComputer.AIR_TRANSIT_TICKS;

    @Override
    public float estimate(int rx, int ry, int rz, int gx, int gy, int gz) {
        int dx = gx - rx;
        int dy = gy - ry;
        int dz = gz - rz;
        // 0 at goal (dx=dy=dz=0 → sqrt(0)=0).
        float distRegions = (float) Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
        return distRegions * COST_PER_REGION;
    }
}
