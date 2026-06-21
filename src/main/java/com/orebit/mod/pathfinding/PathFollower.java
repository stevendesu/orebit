/**
 * PathFollower.java
 *
 * Main Component: pathfinding/
 * Environment: MAIN
 *
 * This interface defines the contract for any entity capable of executing a `PathPlan`.
 * A `PathFollower` is ticked regularly and is responsible for applying a sequence of
 * `BlockPathOperation` instances provided by a `PathPlan`, one at a time, to traverse
 * or modify the world in pursuit of a goal. It tracks plan progress, reports execution
 * status, and may trigger replanning if the current path becomes blocked or invalid.
 *
 * This abstraction supports a wide range of AI-controlled entities, from player bots
 * using `VirtualPlayerController` to native mobs like zombies or villagers. The interface
 * does not assume any specific movement mechanism—only that operations can be performed.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Provides a unified interface for path-following behavior
 * - Drives the execution of a `PathPlan` over time
 * - Declares supported `Operation` types (e.g., movement, block placement)
 * - Tracks current execution state and plan progress
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `PathPlan`, this is active and stateful—not a static data structure
 * - Unlike `PathReplanner`, it does not initiate replanning—it simply executes
 * - Unlike `Operation`, it is a consumer, not a provider, of behavior
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Path plans are constructed externally and passed in via `reset(...)`
 * - Each call to `tick()` represents one game tick of progression
 * - Entities may have different movement or mutation capabilities
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `PathPlan`: The unified source of region-spanning `BlockPathOperation`s
 * - `BlockPathOperation`: Each physical step the entity must perform
 * - `PathStatus`: Enum representing current follower state
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: Invokes the follower while executing movement states
 * - `TaskExecutor`: Uses the follower to fulfill physical goals
 * - `PathReplanner`: May pause or replace this follower if invalid
 * - `Debug tools`: May visualize follower state and active operation
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Common methods include:
 *     - `void tick()`
 *     - `void reset(PathPlan newPlan)`
 *     - `Operation getCurrentOperation()`
 *     - `PathStatus getStatus()`
 *     - `boolean supports(Operation op)`
 * - Implementations may use:
 *     - Virtual player input
 *     - Direct entity control
 *     - Custom physics or animation systems
 */
package com.orebit.mod.pathfinding;
