/**
 * BlockHeuristic.java
 *
 * Main Component: pathfinding/blockpathfinder/heuristics/
 * Environment: MAIN
 *
 * This interface defines a heuristic function used by block-level pathfinding
 * algorithms (such as A*) to estimate the cost between two positions in the world.
 * It serves as a guide for exploring the most promising paths first by scoring
 * each node's estimated total cost (current path cost + heuristic).
 *
 * Block heuristics are used only during the planning phase—they do not affect
 * actual execution—and must be consistent and admissible to ensure that the
 * shortest path is found (when optimality is desired).
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines a pluggable cost estimation function for block-level A* search
 * - Provides structure for heuristics such as Manhattan, Euclidean, etc.
 * - Enables tuning between performance and precision in pathfinding
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockPathOperation`, this does not represent actions—only estimates
 * - Unlike `PathBudgetManager`, this does not constrain execution—only guides planning
 * - Unlike `RegionHeuristic`, this is specific to block-level nodes, not abstract regions
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Inputs represent two valid, loaded positions in the same dimension
 * - Implementations are stateless and performant (called frequently)
 * - Heuristics are admissible (never overestimate) if shortest path is desired
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockPos` or equivalent for world position coordinates
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathfinder`: Uses this to score and prioritize nodes
 * - `PathReplanner`: May swap heuristics to favor speed or safety
 * - `Debug tools`: May visualize heuristic surfaces or step counts
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Functional interface:
 *     - `float estimate(BlockPos from, BlockPos to)`
 * - Should return 0 if `from.equals(to)`
 * - Common implementations include Manhattan, Euclidean, or custom biasing
 */
package com.orebit.mod.pathfinding.blockpathfinder;
