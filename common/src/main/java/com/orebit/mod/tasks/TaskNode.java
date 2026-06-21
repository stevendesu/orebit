/**
 * TaskNode.java
 *
 * Main Component: tasks/
 * Environment: MAIN
 *
 * This class defines the internal structure of a task plan. A `TaskNode` is a node
 * in a behavior tree that yields `Action`s, evaluates success/failure, and guides
 * the bot's decisions over time. It is the executable plan for how to achieve a
 * `Task`'s goal.
 *
 * TaskNodes may represent:
 * - A single action (e.g., "use item")
 * - A sequence of steps (e.g., walk → open → insert)
 * - A conditional branch (e.g., “if hungry → eat”)
 * - A retry/fallback node (e.g., try mining, else loot chest)
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines a recursive, tick-driven plan structure
 * - Provides `Action`s to the executor over time
 * - Tracks completion and failure of itself and its children
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Action`, this is meta-behavior—it doesn’t mutate the world
 * - Unlike `Task`, this is part of a plan—not the task wrapper
 * - Unlike `Condition`, this contains logic, but can also yield actions
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Trees are traversed top-down by `TaskExecutor`
 * - Nodes are stateful—tick-to-tick progression is preserved
 * - Children may be parallel, sequential, conditional, etc.
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Action`, `Condition`: Used internally as leaf nodes or guards
 * - `BotContext`, `PathFollower`, `InventorySimulator`: Queried during planning
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `Task`: Holds root `TaskNode` for each goal
 * - `TaskExecutor`: Walks the node tree and ticks it
 * - `TaskLibrary`: Uses to define built-in task behaviors
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose:
 *     - `Action tick()`
 *     - `boolean isComplete()`
 *     - `void reset()`, `void cancel()`
 * - May support:
 *     - Precondition gating
 *     - Fallbacks, interrupts, or sub-goal injection
 */
package com.orebit.mod.tasks;
