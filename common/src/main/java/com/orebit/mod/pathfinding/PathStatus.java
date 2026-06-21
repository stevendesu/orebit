/**
 * PathStatus.java
 *
 * Main Component: pathfinding/
 * Environment: MAIN
 *
 * This enum represents the current execution state of a `PathFollower` as it
 * progresses through a `PathPlan`. It is used to monitor path lifecycle, detect
 * blockages, and report completion or failure. It allows higher-level systems
 * (such as the AI state machine or replanning logic) to react accordingly.
 *
 * Path status transitions occur during regular `tick()` updates and reflect
 * whether the bot is still moving, has completed its goal, or encountered an
 * obstruction that invalidated the plan.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines discrete states for path execution progress
 * - Helps determine when to replan, pause, or terminate movement
 * - Used by `PathFollower` and observed by upstream systems
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `PathPlan`, this contains no movement or logic—just state
 * - Unlike `Operation`, this does not represent an action—only a result
 * - Unlike `TaskStatus`, this is purely physical, not goal-related
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Transitions are updated once per tick
 * - Observers will react to changes (e.g., trigger replanning on `BLOCKED`)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly; used within `PathFollower` implementations
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathFollower`: Sets and exposes current status
 * - `AIStateMachine`, `TaskExecutor`: May check status to drive decisions
 * - `Debug tools`: May visualize status transitions
 *
 * ---------------------------
 * Enum Values:
 * ---------------------------
 * - `IDLE` – No path assigned or waiting to begin
 * - `RUNNING` – Currently executing a path
 * - `COMPLETE` – Successfully reached destination
 * - `BLOCKED` – Movement is temporarily invalid or obstructed
 * - `FAILED` – Path is unrecoverable and must be replaced
 */
package com.orebit.mod.pathfinding;
