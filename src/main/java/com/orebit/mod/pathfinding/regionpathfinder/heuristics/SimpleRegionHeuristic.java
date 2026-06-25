package com.orebit.mod.pathfinding.regionpathfinder.heuristics;

import com.orebit.mod.pathfinding.regionpathfinder.RegionHeuristic;
import com.orebit.mod.worldmodel.hpa.CostCodec;

/**
 * The ratified default region-tier heuristic: Euclidean distance between region centers, in region units,
 * scaled by the minimum possible per-region crossing cost
 * (PRD §6.3–6.5, §7.4; HPA-IMPLEMENTATION.md §8.1).
 *
 * <p>Operates on <b>level-0 region coordinates</b>. The distance is the straight-line (Euclidean) span
 * between the two regions measured in <b>region units</b> (one unit = one level-0 region cell, i.e. one
 * {@code LEAF_SIZE}=16-block cube per side), multiplied by {@link #MIN_COST_PER_REGION} — the cheapest a
 * single region can ever cost to cross.
 *
 * <p><b>Why it is admissible (PRD §7.4):</b> any real path from the candidate region to the goal region
 * must traverse at least as many regions as the straight-line region distance, and each traversed region
 * costs <i>at least</i> {@link #MIN_COST_PER_REGION} (the dequantized value of the cheapest cost bucket,
 * {@code CostCodec.dequantize(0)}). So {@code distance · MIN_COST_PER_REGION} can never over-estimate the
 * true remaining cost — the heuristic is an admissible lower bound, and the region A* stays optimal. The
 * Euclidean (not Manhattan/Chebyshev) form keeps it a conservative under-estimate even when diagonal
 * region-to-region moves are not directly available.
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
     * The cheapest a single region can cost to cross — the dequantized value of cost bucket {@code 0}
     * ({@link CostCodec#dequantize(int) CostCodec.dequantize(0)} = {@link CostCodec#BASE_TICKS}). Using the
     * floor (not an average) is what guarantees admissibility: no region ever costs less than this.
     */
    public static final float MIN_COST_PER_REGION = CostCodec.dequantize(0);

    @Override
    public float estimate(int rx, int ry, int rz, int gx, int gy, int gz) {
        int dx = gx - rx;
        int dy = gy - ry;
        int dz = gz - rz;
        // 0 at goal (dx=dy=dz=0 → sqrt(0)=0), admissible everywhere else.
        float distRegions = (float) Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
        return distRegions * MIN_COST_PER_REGION;
    }
}
