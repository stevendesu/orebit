/**
 * AccessFrequencyDecayStrategy.java
 *
 * Subcomponent: memory/decay_strategies/
 * Environment: MAIN
 *
 * This decay strategy removes memory entries that are rarely accessed,
 * simulating forgetting due to disuse. Each `MemoryEntry` is assumed
 * to track an access count or timestamp, and entries that haven't
 * been recalled recently or frequently are eligible for removal.
 * This encourages retention of high-value or repeated interactions
 * while gradually forgetting "one-off" or irrelevant facts.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks memory access frequency or recency
 * - Removes entries that fall below a usage threshold (e.g., not accessed in 30 min, or fewer than 2 accesses)
 * - Can demote entries before removal to allow a “grace period”
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `TimeBasedDecayStrategy`, this strategy doesn’t care how old an entry is—only how often it’s used
 * - Unlike `EmotionalWeightDecayStrategy`, this strategy ignores importance or tags
 * - Unlike `NoDecayStrategy`, this strategy removes memory based on cognitive economy
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - `MemoryEntry` tracks last-access timestamp or a numeric access count
 * - The bot regularly recalls memory entries via `recall()` or equivalent
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotMemory`: Must support querying and pruning low-frequency entries
 * - `MemoryEntry`: Should store access metadata
 * - `SimulationClock`: For decay tied to last access time
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ProfileManager`: May assign this strategy to bots who prioritize “working memory”
 * - `BotMemory`: Can call this strategy during periodic decay
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Configurable thresholds (e.g., “expire if not accessed in X ticks”)
 * - Entries may be softly downgraded before being deleted
 * - Optionally includes a counter decay model (e.g., access count fades over time)
 */
package com.orebit.mod.memory.decay_strategies;
