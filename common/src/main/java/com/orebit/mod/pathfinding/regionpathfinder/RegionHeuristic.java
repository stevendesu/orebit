/**
 * RegionHeuristic.java
 *
 * Main Component: pathfinding/regionpathfinder/heuristics/
 * Environment: MAIN
 *
 * This interface defines a heuristic function used by region-level pathfinding
 * algorithms (such as A*) to estimate the cost between two abstract `Region`
 * nodes. It allows the planner to prioritize promising high-level paths across
 * the region graph, without resolving low-level block navigation.
 *
 * Heuristics may incorporate region metadata, topology, travel cost estimates,
 * or even semantic tags (e.g., danger zones, dead ends) to inform the score.
 * Like all A* heuristics, they must remain admissible (not overestimate) to
 * guarantee optimal path selection.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines an interface for estimating travel cost between two regions
 * - Allows `RegionPathfinder` to apply scoring logic beyond raw adjacency
 * - Supports swappable strategies based on performance, layout, or intent
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockHeuristic`, this operates at a coarse, symbolic level
 * - Unlike `RegionGraph`, this does not define topology—only scoring
 * - Unlike `PortalTraversalPlanner`, this does not determine movement rules
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Both regions are valid nodes within the same planning scope (e.g., layer)
 * - Cost estimation reflects approximated travel effort, not exact metrics
 * - Implementations are efficient and consistent
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Region`: Semantic unit of world structure from `worldmodel/region/`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RegionPathfinder`: Calls this during A* planning across region graphs
 * - `PathReplanner`: May select heuristics based on context or urgency
 * - `Debug tools`: May render region scores or path heatmaps
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Functional interface:
 *     - `float estimate(Region from, Region to)`
 * - Should return 0 if `from.equals(to)`
 * - Can use region center distance, tag matching, transition cost estimates, etc.
 */
package com.orebit.mod.pathfinding.regionpathfinder;
