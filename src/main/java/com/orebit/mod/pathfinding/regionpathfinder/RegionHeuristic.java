package com.orebit.mod.pathfinding.regionpathfinder;

/**
 * Admissible heuristic for the HPA* region-tier A*
 * (PRD §6.3–6.5, §7.1, §7.4; HPA-IMPLEMENTATION.md §8.1).
 *
 * <p>The region tier plans over a {@code CostPyramid} of fixed-grid cubic regions (PRD §6.3: regions are a
 * fixed cubic-grid / implicit octree, NOT the superseded semantic {@code Region}/{@code Portal} model). Each
 * node stores six <b>face→center</b> half-traversal costs (PRD §6.5: we store the half from a face to the
 * node center, never an edge; the boundary between two adjacent regions is the implicit sum of the two
 * facing halves). The region A* uses this heuristic to bias expansion toward the goal region.
 *
 * <p>A {@code RegionHeuristic} estimates the remaining travel cost (in <b>ticks</b>, the same unit the
 * {@code CostPyramid} dequantizes to via {@link com.orebit.mod.worldmodel.hpa.CostCodec#dequantize}) from a
 * candidate region to the goal region, given both in <b>level-0 region coordinates</b>. The inputs are
 * region indices, not world block coords — the implementation reasons in region units and scales by the
 * minimum per-region crossing cost.
 *
 * <p><b>Admissibility (HARD requirement):</b> like every A* heuristic, an implementation must never
 * over-estimate the true remaining cost — otherwise the region A* could refuse a genuinely reachable route
 * (PRD §7.4: the hierarchy is what lets us stay admissible while still pruning). It must return {@code 0}
 * when the candidate region <i>is</i> the goal region. The hierarchy's optimistic defaults for
 * unbuilt/unloaded nodes (HPA-IMPLEMENTATION.md §6) keep the realized edge costs an admissible floor too.
 *
 * <p><b>Strategy, not switch (house style):</b> this is a swappable interface — concrete heuristics
 * (e.g. {@link com.orebit.mod.pathfinding.regionpathfinder.heuristics.SimpleRegionHeuristic}) register and
 * are selected by the planner, with no enum dispatch. The simple Euclidean-centers heuristic is the
 * ratified default; richer variants (portal-count, tag-aware, exploration-bias, verticality-penalty) are
 * deferred — they lean on the superseded portal/semantic model and stay stubs for now.
 *
 * <p><b>Hot path (house style):</b> {@link #estimate} is called once per popped region node during a
 * replan; it must be allocation-free pure arithmetic over the six {@code int} coords.
 */
@FunctionalInterface
public interface RegionHeuristic {

    /**
     * Estimate the remaining travel cost (in ticks) from the candidate region {@code (rx,ry,rz)} to the
     * goal region {@code (gx,gy,gz)}, all in <b>level-0 region coordinates</b>.
     *
     * <p>Must be <b>admissible</b> (never over-estimate the true cost) and return {@code 0} when the
     * candidate region equals the goal region.
     *
     * @param rx candidate region x (level-0 region coords)
     * @param ry candidate region y (level-0 region coords)
     * @param rz candidate region z (level-0 region coords)
     * @param gx goal region x (level-0 region coords)
     * @param gy goal region y (level-0 region coords)
     * @param gz goal region z (level-0 region coords)
     * @return an admissible lower bound on the remaining cost in ticks ({@code 0} at the goal)
     */
    float estimate(int rx, int ry, int rz, int gx, int gy, int gz);
}
