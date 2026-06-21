/**
 * PathReplanner.java
 *
 * Main Component: pathfinding/
 * Environment: MAIN
 *
 * This class serves as the centralized system for regenerating a full `PathPlan`
 * when the current one becomes invalid. It monitors both block-level execution
 * failures (e.g., blocked path, changed terrain) and high-level transitions (e.g.,
 * missing portals or changed region layouts) to determine when a full path
 * recalculation is necessary.
 *
 * The `PathReplanner` owns the logic for re-invoking the `RegionPathfinder` and
 * `BlockPathfinder` as needed, and it produces a brand new `PathPlan` that can be
 * swapped in by a `PathFollower`, `TaskExecutor`, or AI system. This component
 * also enforces global planning constraints such as cost ceilings or resource
 * limits.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Detects and responds to path invalidation conditions
 * - Rebuilds a complete `PathPlan` from current position to final goal
 * - Coordinates both region and block-level pathfinding layers
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockPathfinder`, this is not a planner itself—it orchestrates planning
 * - Unlike `RegionPathPlan`, this is not a data structure—it manages recovery
 * - Unlike `PathFollower`, this is not runtime behavior—it is plan synthesis
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The bot’s current state and goal are known
 * - Block path failures are reported externally (e.g. by `PathFollower`)
 * - The world model reflects a reasonably current view of terrain and regions
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockPathfinder`: Used to regenerate current or next block path
 * - `RegionPathfinder`: Used to rebuild high-level route if necessary
 * - `PathPlan`: The output of the replanning process
 * - `WorldModel` (indirect): Used for environmental lookups
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathFollower`: May request a replan if blocked
 * - `AIStateMachine`, `TaskExecutor`: Monitor for changes in plan availability
 * - `Debug tools`: May trigger, log, or visualize replans
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Accepts inputs like:
 *     - Current bot position
 *     - Final goal position
 *     - World/region context
 *     - Flags or failure reasons
 * - Produces a new `PathPlan`, or null/exception if unreachable
 * - May be designed to run on a background thread if planning is expensive
 * - Can enforce constraints from `PathBudgetManager` or server settings
 */
package com.orebit.mod.pathfinding;
