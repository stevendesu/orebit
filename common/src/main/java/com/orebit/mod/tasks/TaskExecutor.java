/**
 * TaskExecutor.java
 *
 * Main Component: tasks/
 * Environment: MAIN
 *
 * This class is responsible for executing the bot's current task. It drives the
 * lifecycle of a `Task`, evaluates and progresses through its `TaskNode` tree,
 * handles conditional branching, and executes `Action`s one tick at a time.
 *
 * The `TaskExecutor` is effectively the task engine for the bot—it applies plans
 * to the world, reacts to failures or interrupts, and determines what happens
 * when a task completes or breaks down.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Executes the active task’s `TaskNode` plan
 * - Steps through `Action`s tick-by-tick
 * - Manages task transitions, completion, and interruption
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Task`, this is runtime logic—not metadata or goal
 * - Unlike `Action`, this schedules and manages actions—it doesn’t define them
 * - Unlike `AIStateMachine`, this runs inside the current state—not across states
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - One task is active at a time, managed by a `TaskQueue`
 * - TaskNode and Action trees are well-formed and non-cyclic
 * - Ticks may involve zero or one active action at a time
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Task`: The current top-level goal
 * - `TaskNode`: Current subtree of planned actions
 * - `Action`: The currently executing primitive
 * - `BotContext`, `PathFollower`, `InventorySimulator`, etc.
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIState`: Calls this during execution state
 * - `BehaviorProfile`: May inject or cancel tasks
 * - `Debug tools`: May inspect current task status
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May expose:
 *     - `void tick()`
 *     - `void start(Task task)`
 *     - `void cancelCurrentTask()`
 *     - `TaskStatus getStatus()`
 * - Internally manages:
 *     - Current `Task`
 *     - Current `TaskNode`
 *     - Current `Action`
 */
package com.orebit.mod.tasks;
