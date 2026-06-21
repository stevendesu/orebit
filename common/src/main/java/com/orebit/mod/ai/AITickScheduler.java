/**
 * AITickScheduler.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This class orchestrates the ticking of AI logic across all active bots in the world.
 * It rotates through a fixed number of `AITickBucket` instances, distributing bot updates
 * over time to smooth out CPU usage. It serves as the global entry point for triggering
 * `AIStateMachine` updates and coordinates optional performance monitoring and load balancing.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Maintains and rotates through all `AITickBucket` instances each server tick
 * - Invokes the AI tick logic for each bot in the currently active bucket
 * - Interfaces with the `AILoadBalancer` to periodically rebalance bots between buckets
 * - Optionally integrates with `AITickProfiler` to monitor performance
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIStateMachine`, this class does not implement bot behavior—it schedules it
 * - Unlike `AITickBucket`, this class coordinates all buckets and controls the global tick loop
 * - Unlike `AILoadBalancer`, it does not analyze timing or perform optimization—it delegates to those
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The number of tick buckets is fixed or configurable and remains relatively small (e.g., 4–16)
 * - Bots are evenly distributed among buckets and reassigned occasionally to maintain balance
 * - Bots in unloaded chunks or offline states are excluded from ticking
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `AITickBucket`: Holds bots and executes per-tick AI logic
 * - `AILoadBalancer`: Provides periodic rebalancing suggestions or actions
 * - `VirtualPlayerContext`: Used indirectly to access each bot’s AIStateMachine
 * - `SettingsManager`: May control tick frequency or bucket count
 * - `AITickProfiler` (optional): Gathers timing data during bucket execution
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotManager`: Registers bots with the scheduler on spawn
 * - `Debug tools`: May query current bucket distribution or tick history
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Implements a round-robin or time-windowed bucket tick strategy
 * - Rebalancing (via `AILoadBalancer`) may occur every N ticks (configurable)
 * - Bots must be added or removed from buckets explicitly to maintain consistency
 * - Should be safe against concurrent modification if worker threads are later introduced
 */
package com.orebit.mod.ai;
