/**
 * TimeBasedDecayStrategy.java
 *
 * Subcomponent: memory/decay_strategies/
 * Environment: MAIN
 *
 * This implementation of `MemoryDecayStrategy` removes or degrades memory entries
 * based purely on how long ago they were recorded. Each `MemoryEntry` is timestamped,
 * and entries older than a configurable threshold are either removed or marked as
 * low priority. This is the simplest form of decay, simulating short-term memory loss
 * over time regardless of access frequency or content relevance.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Applies memory decay based on age relative to the simulation clock
 * - Removes entries that exceed a maximum lifetime (e.g., 1 hour of play time)
 * - Optionally applies a soft decay before removal (e.g., demoting priority)
 * - Tracks and logs the number of entries expired per pass (optional)
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AccessFrequencyDecayStrategy`, this strategy ignores usage patterns
 * - Unlike `EmotionalWeightDecayStrategy`, this strategy treats all memories equally
 * - Unlike `NoDecayStrategy`, this actively removes content over time
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each memory entry includes a timestamp (real or simulated)
 * - The `SimulationClock` is a consistent source of time across all bots
 * - Expired entries are no longer useful to the bot and can be discarded
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MemoryDecayStrategy`: This is a concrete implementation
 * - `BotMemory`: Supplies the list of entries and removal APIs
 * - `MemoryEntry`: Must expose creation time or equivalent timestamp
 * - `SimulationClock`: Used to compute age at time of decay
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotMemory`: May use this strategy for default decay
 * - `ProfileManager`: May assign this strategy by profile or role
 * - `Debug tools`: May visualize age-based memory loss
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Configurable max age (e.g., 30 minutes, 3600 seconds)
 * - May expose a method like `setMaxAge(Duration duration)`
 * - Entries may be:
 *     - Immediately removed (hard expiration)
 *     - Softly marked as “stale” before removal
 * - Designed to run in constant or amortized linear time
 */
package com.orebit.mod.memory.decay_strategies;
