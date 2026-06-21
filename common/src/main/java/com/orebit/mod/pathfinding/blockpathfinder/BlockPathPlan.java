/**
 * BlockPathPlan.java
 *
 * Main Component: pathfinding/blockpathfinder/
 * Environment: MAIN
 *
 * This class represents a complete, precomputed sequence of `BlockPathOperation`
 * instances that a bot must perform to move from its current location to a
 * target location **within a single region**. It is the output of the block-level
 * pathfinding algorithm (`BlockPathfinder`) and is consumed incrementally by a
 * `PathFollower` at runtime.
 *
 * Unlike higher-level plans such as `RegionPathPlan`, this plan operates at
 * the granularity of individual blocks and includes both movement and world
 * mutation steps (e.g., jumping, placing, or breaking blocks).
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Holds a linear list of `BlockPathOperation`s that form a complete path
 * - Provides access to individual operations for execution or inspection
 * - Encapsulates bot-scale navigation in a single region of the world
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `RegionPathPlan`, this does not span multiple regions or dimensions
 * - Unlike `PathFollower`, this plan is passive and immutable after construction
 * - Unlike `TaskStep`, this plan has no semantic intent—it is purely navigational
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All operations were valid at the time of plan generation
 * - The world may change after creation, requiring plan invalidation or replanning
 * - Execution occurs in order from start to finish
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockPathOperation`: The atomic units of movement and interaction
 * - `BlockPathfinder`: Responsible for generating this plan
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathFollower`: Uses this plan to drive real-world execution
 * - `PathReplanner`: May replace this plan if conditions change
 * - `Debug tools`: May render this plan for diagnostics or overlays
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally stores an ordered list of `BlockPathOperation`s
 * - Should be immutable once constructed
 * - May expose utility methods like:
 *     - `BlockPathOperation getStep(int index)`
 *     - `int size()`
 *     - `boolean isEmpty()`
 *     - `List<BlockPathOperation> getOperations()`
 */
package com.orebit.mod.pathfinding.blockpathfinder;
