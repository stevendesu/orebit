/**
 * BlockPathfinder.java
 *
 * Main Component: pathfinding/blockpathfinder/
 * Environment: MAIN
 *
 * This class performs low-level pathfinding over block data to generate a
 * `BlockPathPlan`, which is a sequence of `BlockPathOperation` instances
 * that move a bot from a starting position to a target location within a
 * single region. The algorithm uses A* or another graph search strategy
 * to evaluate and construct the most efficient traversal path based on
 * environmental constraints and bot capabilities.
 *
 * The BlockPathfinder evaluates terrain walkability, jumpability, hazards,
 * and possible world mutations (e.g., block placement or breaking). It is
 * highly performance-sensitive and must operate within bounded time and
 * memory constraints.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Computes a list of movement and world-interaction operations needed to
 *   reach a destination at the block level
 * - Applies heuristics and movement costs to guide search efficiency
 * - Queries bot capabilities (via `PathFollower.supports(...)`) to filter
 *   valid operations during planning
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockPathPlan`, this class builds plans—it does not store or execute them
 * - Unlike `RegionPathfinder`, this class operates over individual blocks, not semantic regions
 * - Unlike `PathFollower`, this is an offline planner, not a runtime system
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - A block-level representation of the region is available (typically from `worldmodel/`)
 * - Movement costs and operation logic are consistent with `BlockPathOperation` definitions
 * - The bot has a way to report supported movement/mutation types
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockPathOperation`: The atomic steps that compose a plan
 * - `MovementContext`: (or similar) used to provide terrain and state info
 * - `PathBudgetManager`: May be used to bound path length or cost
 * - `CostEstimator` (in `heuristics/`): Supplies heuristic function for A* search
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathReplanner`: May invoke this when a plan is invalidated
 * - `TaskExecutor`: Requests physical plans to reach task-related targets
 * - `AIStateMachine`: May trigger path planning for reactive movement states
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Typically uses A* search over a grid or graph of block positions
 * - Each search node represents a position and accumulated cost
 * - Expands by generating supported `BlockPathOperation`s from current position
 * - Can be adapted for async or batched planning in performance-constrained environments
 */
package com.orebit.mod.pathfinding.blockpathfinder;
