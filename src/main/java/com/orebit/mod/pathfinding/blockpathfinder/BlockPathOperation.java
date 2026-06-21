/**
 * BlockPathOperation.java
 *
 * Main Component: pathfinding/blockpathfinder/
 * Environment: MAIN
 *
 * This interface represents a single atomic operation that can be performed
 * by a bot as part of a `BlockPathPlan`. These operations may include
 * simple movements (e.g., walking or jumping) or interactions with the world
 * (e.g., placing or breaking a block). Together, they form the steps that a
 * bot will follow to physically reach a destination within a region.
 *
 * Each implementation of this interface defines its own validity conditions,
 * execution behavior, and associated traversal cost. These operations are
 * generated and scored during the pathfinding process by the `BlockPathfinder`
 * and executed one by one by a `PathFollower`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Serves as the base interface for all physical pathing actions
 * - Encapsulates logic for checking validity, computing cost, and applying effects
 * - Allows the pathfinder to treat movement and world interaction uniformly
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockPathPlan`, this represents a single step, not a sequence
 * - Unlike `PathFollower`, this defines what an operation *is*, not how to apply it in sequence
 * - Unlike `TaskStep`, this is physical and world-local, not goal-oriented
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The world and entity context are available at planning and execution time
 * - Operations are self-contained and do not rely on external mutable state
 * - Cost and validity must be consistent between planning and execution
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `World`, `BlockPos`, or similar types for environmental queries
 * - `MovementContext` or equivalent stateful input
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathPlan`: Holds an ordered list of these operations
 * - `BlockPathfinder`: Generates and scores them during search
 * - `PathFollower`: Executes them incrementally at runtime
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Typical methods include:
 *     - `boolean isValid(Context ctx)`
 *     - `float getCost(Context ctx)`
 *     - `void apply(BotController controller)`
 * - Operations may also expose source and destination positions
 * - World-modifying operations (e.g., `PlaceBlockOperation`) may require
 *   inventory access or write permissions
 */
package com.orebit.mod.pathfinding.blockpathfinder;
