/**
 * RegionPathPlan.java
 *
 * Main Component: pathfinding/regionpathfinder/
 * Environment: MAIN
 *
 * This class represents a strategic, high-level navigation plan composed of
 * a sequence of regions that a bot must traverse in order to reach a target
 * destination. It is the output of the `RegionPathfinder` and is consumed by
 * the `PathPlan`, which coordinates both region-level and block-level
 * movement planning.
 *
 * Each region in the plan is assumed to be connected to the next via one or
 * more shared portal blocks or transition boundaries, as defined in the region
 * graph maintained by the world model. The `RegionPathPlan` does not describe
 * how to move within each region—only which regions must be visited and in what
 * order.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores a sequence of region transitions between source and destination
 * - May include portal information, estimated costs, or metadata per step
 * - Acts as the long-range "skeleton" for physical path planning
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockPathPlan`, this plan is abstract and symbolic
 * - Unlike `RegionTree` (provided by worldmodel), this is a traversal result, not a graph structure
 * - Unlike `PathPlan`, this does not contain block-level details
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each consecutive pair of regions is adjacent or connected via a known portal
 * - The plan will be refined into one or more `BlockPathPlan`s at runtime
 * - The world model will remain consistent for the duration of plan execution
 * - Transitions between regions are always traversable by at least one `BlockPathOperation`
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Region`: Represents each node in the plan (from `worldmodel/region/`)
 * - (Optional) `RegionPortal`: May describe the boundary between two regions
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathPlan`: Uses this to determine the next region goal
 * - `BlockPathfinder`: May use this to identify goal portals for block-level planning
 * - `Debug tools`: May visualize planned region route
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally stores an ordered list of `Region` transitions
 * - May expose methods like:
 *     - `Region getStart()`
 *     - `Region getNextRegion()`
 *     - `boolean isComplete()`
 *     - `List<Region> getPath()`
 * - Should be immutable once constructed by `RegionPathfinder`
 */
package com.orebit.mod.pathfinding.regionpathfinder;
