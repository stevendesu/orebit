/**
 * InterruptibleState.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This interface (or abstract base class) extends the `AIState` system to support
 * *resumable behaviors*—states that can be paused when interrupted and resumed later.
 * It is used for complex actions like mining, crafting, or long walks that should not
 * be restarted from scratch when temporarily overridden by higher-priority behaviors.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines additional lifecycle methods:
 *     - `pause()` – Called when the state is suspended due to an interrupt
 *     - `resume()` – Called when the state is resumed after being paused
 * - Tracks internal progress so the state can continue where it left off
 * - Allows states to opt into pushdown automation via `StateStack`
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIState`, which assumes one-shot lifecycle (`enter → tick → exit`),
 *   this interface supports nested or layered state interruptions
 * - Unlike `StateStack`, it doesn’t manage transitions—it participates in them
 * - Unlike `AIStateMachine`, it doesn’t control which state is active—it defines a richer interface
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The AIStateMachine (or `StateStack`) is capable of saving and restoring the paused state
 * - The interrupting state will eventually complete and allow the previous state to resume
 * - Only one `InterruptibleState` can be active or paused at a time unless explicitly layered
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - Extends or implements `AIState`
 * - Used in conjunction with `StateStack`, which pushes and pops states
 * - `VirtualPlayerContext`: Provides necessary state and continuity when resuming
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `StateStack`: Pauses/resumes these states
 * - `AIStateMachine`: May transition into or out of an interruptible state
 * - Long-running task states: Mining, walking, crafting, building, etc.
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should store internal state variables (e.g., progress, target, path plan)
 * - `pause()` should clean up temporary state and pause timers or animations
 * - `resume()` should restore control and continue from the last known point
 * - Implementations must be careful not to double-advance or reset unintentionally
 */
package com.orebit.mod.ai;
