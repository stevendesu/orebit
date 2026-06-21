/**
 * PathPlan.java
 *
 * Main Component: pathfinding/
 * Environment: MAIN
 *
 * This class represents a complete, unified navigation plan that spans multiple
 * regions and encapsulates both high-level and low-level movement. It coordinates
 * a `RegionPathPlan` and a series of `BlockPathPlan`s to enable long-distance
 * navigation through complex terrain while only computing block-level paths
 * as needed.
 *
 * To its consumers—such as a `PathFollower` or `TaskExecutor`—the `PathPlan`
 * appears as a single cohesive sequence of operations. Internally, it maintains
 * region boundaries, portal transitions, and staged block plans to ensure the
 * bot always has the next step ready without over-committing to distant paths.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Provides a unified view of the bot’s current and upcoming path steps
 * - Manages transitions between regions during traversal
 * - Lazily or asynchronously computes block-level paths as needed
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `RegionPathPlan`, this is intended for execution, not strategy
 * - Unlike `BlockPathPlan`, this spans multiple regions
 * - Unlike `PathFollower`, this is the source of operations, not the consumer
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Region and block-level paths were valid at the time of planning
 * - Block plans may be regenerated if a region transition occurs
 * - `PathFollower` will call into this one step at a time
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `RegionPathPlan`: Determines region-to-region travel order
 * - `BlockPathPlan`: Encapsulates actual movement and interaction operations
 * - `BlockPathfinder`: Used to generate new block plans as needed
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathFollower`: Consumes operations from this plan
 * - `PathReplanner`: Replaces this plan entirely if invalidated
 * - `AIStateMachine`, `TaskExecutor`: May query or monitor plan progress
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally tracks current region, current block plan, and position in path
 * - May preload the next region’s `BlockPathPlan` while executing the current one
 * - Exposes utility methods such as:
 *     - `BlockPathOperation getNextStep()`
 *     - `boolean isComplete()`
 *     - `float getTotalEstimatedCost()`
 * - Should be immutable to the outside and mutated only via controlled transitions
 */
package com.orebit.mod.pathfinding;
