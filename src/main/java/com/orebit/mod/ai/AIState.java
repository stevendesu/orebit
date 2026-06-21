/**
 * AIState.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This interface (or abstract base class) defines the contract for all discrete, high-level
 * AI behaviors or "modes" that a VirtualPlayer can enter. States represent focused actions
 * like wandering, mining, eating, or fighting, and are responsible for handling the logic
 * and lifecycle of those behaviors while active.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Provides the base interface for individual AI states (e.g., `CombatState`, `IdleState`)
 * - Defines lifecycle methods such as `enter()`, `tick()`, `exit()`, and `shouldInterrupt()`
 * - Encapsulates all decision-making and action logic for the duration of a single state
 * - May trigger events or enqueue animations while executing
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIStateMachine`, which manages the active state and transitions,
 *   this represents the behavior of *one specific state*
 * - Unlike `TaskNode`, this is not a declarative behavior plan—it is imperative logic
 * - Unlike `InterruptibleState`, this does not necessarily support pausing or resuming
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot can only have one AIState active at a time (unless wrapped in a parallel runner)
 * - States are short- to medium-lived, often tied to environment conditions or internal needs
 * - States should be interruptible by higher-priority concerns (e.g., danger or hunger)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayerContext`: Passed into state methods for data and control
 * - `VirtualPlayerController`: Used to move, interact, and perform actions
 * - `PathPlan`, `TaskExecutor`, `InventorySimulator`, etc., depending on the state's purpose
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: Manages which state is active and handles transitions
 * - `StateStack`: May use this as a base state type when implementing pushdown automata
 * - `InterruptibleState`: Extends or wraps this interface to support resumable states
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Common pattern is to use `enter()`, `tick()`, and `exit()` lifecycle methods
 * - `tick()` is called once per AI tick while the state is active
 * - States may return status codes, flags, or emit events to notify listeners or trigger transitions
 * - May be stateless or stateful, depending on design
 */
package com.orebit.mod.ai;
