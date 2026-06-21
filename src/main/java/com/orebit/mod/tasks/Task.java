/**
 * Task.java
 *
 * Main Component: tasks/
 * Environment: MAIN
 *
 * This class represents a long-term goal or intention that the bot is trying
 * to fulfill. It serves as the top-level container for the task tree (`TaskNode`)
 * and coordinates the execution, status, and lifecycle of the plan. A `Task` is
 * a durable, named unit of purpose—such as "mine diamonds", "craft an anvil",
 * or "explore the nether".
 *
 * Tasks may be created by player commands, LLM goals, behavior profiles, or AI
 * self-preservation logic. They are queued, monitored, and executed by the
 * `TaskExecutor`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Encapsulates a full goal with name, plan, and status
 * - Wraps a `TaskNode` tree and coordinates its progression
 * - Provides status reporting, interruption, and completion logic
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `TaskNode`, this represents the goal—not the plan
 * - Unlike `Action`, this is long-term—not tick-based
 * - Unlike `Condition`, this is active and stateful
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Tasks are managed by a central queue (e.g., `TaskQueue`)
 * - Each task has a single root node that generates behavior
 * - Tasks may run in series or be interrupted by priority shifts
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `TaskNode`: Defines the actual behavior tree
 * - `BotContext`, `TaskExecutor`, `AIState`: May influence runtime behavior
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskExecutor`: Responsible for ticking and managing the current task
 * - `TaskQueue`: Stores active and upcoming tasks
 * - `LLM`, `BehaviorProfile`, `PlayerCommands`: May inject new tasks
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose:
 *     - `TaskStatus tick()`
 *     - `String getName()`
 *     - `void cancel()`, `boolean isFinished()`
 *     - `TaskNode getRoot()`
 * - May include:
 *     - Task priority
 *     - Timeouts or expiration
 *     - Associated memory/intent info
 */
package com.orebit.mod.tasks;
