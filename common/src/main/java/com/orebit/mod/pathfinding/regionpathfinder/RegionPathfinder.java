/**
 * RegionPathfinder.java
 *
 * Main Component: pathfinding/regionpathfinder/
 * Environment: MAIN
 *
 * This class computes a high-level `RegionPathPlan` representing the sequence
 * of regions a bot must traverse to reach a goal destination. It supports a
 * hierarchical region model, where regions may be composed of smaller sub-regions
 * (e.g., rooms within buildings, caves within biomes) and navigation occurs at
 * multiple layers of abstraction.
 *
 * The planner uses a recursive refinement strategy:
 * - It first finds the lowest common ancestor (LCA) super-region that contains
 *   both the source and target regions.
 * - It then computes a region-to-region path within that super-region, based
 *   on the local region graph.
 * - As the bot moves into each sub-region, new plans are generated on-demand
 *   (lazy evaluation) for only the currently active super-region layer.
 *
 * This approach reduces memory usage, improves cache locality, and avoids computing
 * unnecessary parts of the route in advance—ideal for large, dynamic Minecraft worlds.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Computes a high-level plan of region-to-region transitions
 * - Supports multi-layered, recursive region graphs via hierarchical traversal
 * - Computes region plans only within the current super-region layer
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockPathfinder`, this operates on semantic region clusters
 * - Unlike `RegionGraph`, this is a recursive planning algorithm—not a flat graph
 * - Unlike `PathReplanner`, this is used proactively for layer-local route discovery
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each region is part of a larger region hierarchy (e.g., octree or B-tree)
 * - Transitions between regions are defined via portals or boundary links
 * - The region graph for any given layer is relatively small and local
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Region`: World units composed into trees of super-regions
 * - `RegionGraph`: Provides sibling-level adjacency for the current planning layer
 * - `RegionPathPlan`: Receives the result of each planning pass
 * - `RegionHeuristic`: Optional scoring heuristic for A* search at this level
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathReplanner`: May trigger replanning as region transitions occur
 * - `PathPlan`: Uses region transitions to determine upcoming block-level targets
 * - `Debug tools`: May visualize current and planned region paths
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Planning is limited to a single layer of the region hierarchy at a time
 * - Recursive calls are triggered upon entry to a new sub-region layer
 * - A* search is used to traverse sibling regions within each super-region scope
 * - Region graph queries and portal lookups are localized to the current scope
 * - The planner does not precompute plans for all layers—only as needed
 */
package com.orebit.mod.pathfinding.regionpathfinder;
