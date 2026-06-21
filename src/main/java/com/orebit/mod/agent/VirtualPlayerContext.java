/**
 * VirtualPlayerContext.java
 *
 * Main Component: agent/
 * Environment: MAIN
 *
 * This class acts as the centralized container for all state, references, and shared data
 * needed to operate and reason about a single VirtualPlayer. It is passed to many subsystems
 * (e.g., AI, pathing, simulation, task execution) to avoid tight coupling and provide
 * a shared interface to bot-local information.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Holds references to all key subsystems tied to the bot (AI, task queue, memory, etc.)
 * - Stores lightweight runtime state (e.g., last known position, tick counter, movement state)
 * - Allows any component operating on the bot to access the current world, bot identity,
 *   current task, inventory, and more without redundant queries
 * - May hold transient or debug-only fields (like flags for tracing or test overrides)
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotMetadata` (manager/), which stores persistent or global bot info,
 *   this class contains ephemeral *runtime-only* state
 * - Unlike `VirtualPlayerController`, it does not act—it simply provides context for others to act
 * - Not tied to specific systems—it's passed around to many subsystems for coordination
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each VirtualPlayer has exactly one context instance created at spawn
 * - This context is passed or injected into most task, AI, and planning systems
 * - Values stored in this class are reset or rebuilt when the bot despawns and respawns
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayer`: The entity this context belongs to
 * - `BotMetadata`: Source of identity and ownership info
 * - `TaskExecutor`, `AIStateMachine`, `InventorySimulator`, etc.: Stored and referenced here
 * - Potential references to `PathPlan`, `RelationshipGraph`, `BotMemory`, and other live systems
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskExecutor`: Uses context to fetch current state, inventory, and location
 * - `AIStateMachine`: Uses context to trigger decisions based on environment
 * - `MovementEvaluator`, `PathFollower`, `SimulationClock`, etc.: All may use this context
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Designed to be injected via constructor or builder pattern
 * - Should expose lightweight accessors and not act as a logic center
 * - May use lazy initialization for subsystems that aren't always used (e.g., memory or profiling)
 * - May include debug helpers like toString() or short summaries for logging
 */
package com.orebit.mod.agent;
