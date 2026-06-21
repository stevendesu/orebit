/**
 * StateStack.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This class implements a pushdown automaton-like structure for managing `AIState` instances,
 * allowing temporary state overrides (such as reacting to danger) without losing progress
 * on the current long-term behavior. It supports stacking and resuming states, and integrates
 * closely with `InterruptibleState` for seamless interruption and continuation.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Maintains a stack of active AI states, with only the topmost state ticking each frame
 * - Pushes temporary override states (e.g., `CombatState`) when triggered by interrupts
 * - Pops the override state when it completes, resuming the previous state
 * - Manages lifecycle events (`pause()`, `resume()`, `exit()`, `enter()`) for smooth transitions
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIStateMachine`, which replaces states, this class allows *layered* state transitions
 * - Unlike `AIState`, this is a management structure—it does not define behavior itself
 * - Unlike `AITickScheduler`, this only operates on the currently running bot’s state stack
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - States must implement `InterruptibleState` to support proper pause/resume behavior
 * - The stack is shallow—typically only 1–2 layers deep at a time
 * - Transitions are triggered by internal logic or external event/priority changes
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `AIState`: All stack entries must implement this interface
 * - `InterruptibleState`: Required for any state that may be paused/resumed
 * - `VirtualPlayerContext`: Provides external state used during evaluation and transition
 * - `AIPrioritySystem`: May trigger interruptions requiring a new push
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: Delegates to this class when nested state handling is required
 * - `CombatState`, `EvadeState`, or other high-priority states: Frequently pushed temporarily
 * - `TaskExecutor`: May resume a task-driven state after a temporary diversion
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - When a new state is pushed:
 *     - The current state (if interruptible) is paused
 *     - The new state is entered and begins ticking
 * - When a state finishes or exits:
 *     - It is popped, and the previous state is resumed
 * - Stack corruption (e.g., non-interruptible states in the middle) must be handled defensively
 * - May expose peek(), size(), and inspection methods for debugging or profiling
 */
package com.orebit.mod.ai;
