/**
 * TaskQueue.java
 *
 * Main Component: tasks/
 * Environment: MAIN
 *
 * This class manages a prioritized queue of `Task` objects for a bot. It controls
 * the order in which tasks are executed, supports task replacement or interruption,
 * and serves as a persistent plan buffer. The queue is used by the `TaskExecutor`
 * to select what to work on next and allows behavior systems to inject or reorder
 * goals dynamically.
 *
 * It serves as a simple but powerful long-term intent manager for bots.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores and prioritizes upcoming `Task`s
 * - Manages which task should run next
 * - Supports task cancellation, promotion, and reordering
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `TaskExecutor`, this does not tick tasks—it only schedules them
 * - Unlike `TaskLibrary`, this holds instances—not definitions
 * - Unlike `AIState`, this stores behavior-specific goals—not raw states
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - One task is active at a time
 * - Task priorities or timestamps may affect ordering
 * - TaskQueue is queried regularly by `TaskExecutor`
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Task`: The actual queued items
 * - `TaskExecutor`: Pulls from the queue
 * - `BehaviorProfile`, `LLM`, `Commands`: May enqueue tasks
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: Triggers queue flushes or switches
 * - `TaskExecutor`: Ticks the head of the queue
 * - `Debug tools`: May visualize or edit task priority
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should support:
 *     - `void enqueue(Task task)`
 *     - `Task peek()`
 *     - `Task poll()`
 *     - `void cancel(Task task)`
 * - May support:
 *     - Priority sorting
 *     - Max task depth
 *     - Sticky or persistent tasks
 */
package com.orebit.mod.tasks;
