/**
 * DebugCommandHandler.java
 *
 * Main Component: debug/
 * Environment: MAIN (server-safe)
 *
 * This class registers and handles debug-only commands issued via chat or console.
 * These commands are intended for developers or server operators to test bot behavior,
 * inspect internal state, simulate edge cases, or trigger controlled debugging actions.
 * Commands may affect the virtual world, bot behavior, logging, or client overlays.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Registers debug commands via the mod's command dispatcher
 * - Handles chat-based or scripted inputs for:
 *     - Spawning/despawning test bots
 *     - Toggling simulation features (e.g., hunger, damage)
 *     - Manipulating task queues, regions, or AI states
 *     - Triggering animations, fake errors, or mock inputs
 * - May emit events, log diagnostics, or interface with visual overlays
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ChatCommandParser`, this class defines and executes commands—it does not parse them
 * - Unlike `BotDebuggerOverlay`, this class is non-visual and server-safe
 * - Unlike `CommandRegistry`, this is not user-facing—it exposes internal hooks only
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Only users with appropriate permissions (e.g., ops) will use debug commands
 * - All commands are prefixed or isolated to prevent conflict with normal commands
 * - Debug commands may expose otherwise unsafe internal APIs
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `CommandDispatcher`: Registers all debug commands
 * - `TaskExecutor`, `AIStateMachine`, `VirtualPlayerController`: May be invoked directly
 * - `BotManager`, `MemoryGraph`, `SimulationClock`: Used for introspection or mutation
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ChatCommandParser` (indirectly): Routes matching commands here
 * - Developers or automated test scripts
 * - `BotDebuggerOverlay`: May be toggled or interacted with via debug commands
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should be disabled or ignored in production servers if needed
 * - Should fail gracefully when required debug systems are unavailable
 * - May include usage hints, autocomplete support, or hidden aliases
 */
package com.orebit.mod.debug;
