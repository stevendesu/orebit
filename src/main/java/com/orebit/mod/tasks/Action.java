/**
 * Action.java
 *
 * Main Component: tasks/
 * Environment: MAIN
 *
 * This interface represents a single, atomic unit of execution in the bot's
 * task system. An `Action` is a low-level behavior that is applied one tick
 * at a time, such as walking, using an item, opening an inventory, or breaking
 * a block. It is the concrete "do this right now" element within a higher-level
 * `TaskNode`.
 *
 * Actions are executed by the `TaskExecutor`, and may run over multiple ticks
 * before reporting success, failure, or cancellation. They may also yield
 * intermediate status (e.g., "still in progress") and support interrupt logic.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines the contract for in-world, tick-based actions
 * - Executes real behavior on behalf of the bot
 * - Reports success/failure to the task engine
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `TaskNode`, this is an executable primitive—not a tree
 * - Unlike `PathOperation`, this may affect inventory, entities, or GUIs
 * - Unlike `Condition`, this is active—not evaluative
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Called once per tick while active
 * - May require access to controller, pathfinder, or simulated systems
 * - May fail due to world conditions (e.g., target moved, resource missing)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayerController`, `PathFollower`, `InventorySimulator`, etc.
 * - (Optionally) `TaskContext` or bot access for decision-making
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskExecutor`: Executes and manages active actions
 * - `TaskNode`: Yields one or more actions during planning
 * - `Debug`: May display current or last executed action
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose:
 *     - `ActionStatus tick()`
 *     - `void onCancel()`
 *     - `boolean isInterruptible()`
 * - May include metadata:
 *     - Type, origin task, priority, estimated duration, etc.
 */
package com.orebit.mod.tasks;
