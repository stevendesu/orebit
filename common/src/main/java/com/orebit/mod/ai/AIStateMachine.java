/**
 * AIStateMachine.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This class manages the active high-level behavior of a VirtualPlayer by tracking
 * the currently running `AIState`, handling state transitions, and coordinating
 * lifecycle events (`enter`, `tick`, `exit`). It serves as the AI’s central control loop,
 * executing once per tick and deciding how the bot should behave in real time.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Maintains a reference to the currently active `AIState`
 * - Calls `tick()` on the active state every AI tick
 * - Invokes `exit()` and `enter()` when switching between states
 * - Integrates with the `AIPrioritySystem` to determine the best behavior to run
 * - Optionally supports queued or deferred transitions (e.g., “switch next tick”)
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIState`, which contains logic for a single behavior,
 *   this class manages the overall behavioral lifecycle and orchestration
 * - Unlike `StateStack`, it does not support nested, resumable states by default
 * - Unlike `TaskExecutor`, this class is reactive and interrupt-driven rather than goal-driven
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - There is always one active AIState (though it may be idle)
 * - Priority-based decisions are made frequently (e.g., every few ticks)
 * - All states adhere to a known interface (`enter`, `tick`, `exit`)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `AIState`: The base type of all behaviors the machine may run
 * - `VirtualPlayerContext`: Supplies state and delegates actions
 * - `AIPrioritySystem`: Used to score and select candidate behaviors
 * - `TaskExecutor`: May be queried when deciding if a task-focused state is valid
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AITickScheduler`: Triggers this machine per tick for active bots
 * - `Debug tools`: May inspect current state, tick history, or transition logs
 * - `InterruptibleState`: If active, may allow temporary overrides of current state
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - State transitions must cleanly call `exit()` on the old state and `enter()` on the new one
 * - State identity may be checked using type tags, IDs, or class comparisons
 * - May include cooldowns, retry throttles, or hysteresis to avoid rapid switching
 * - Future extensions may include state duration tracking or rollback support
 */
package com.orebit.mod.ai;
