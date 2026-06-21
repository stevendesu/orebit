/**
 * NoDecayStrategy.java
 *
 * Subcomponent: memory/decay_strategies/
 * Environment: MAIN
 *
 * This is a debug or fallback strategy that disables memory decay entirely.
 * All memories are retained indefinitely, regardless of age, usage, or importance.
 * This strategy is useful for testing memory retrieval logic, replaying complex
 * scenarios, or observing long-term bot behavior without pruning.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Implements `MemoryDecayStrategy` but does nothing when invoked
 * - Prevents any removal or mutation of stored memory entries
 * - Useful for debugging, debugging, and test cases
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike all other strategies, this performs **no** decay
 * - Unlike `BotMemory` itself, this never initiates pruning
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Bots may accumulate very large memory states
 * - This is not suitable for production-scale runtime
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly; called by `BotMemory` like other strategies
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - Developers, testers, or debug tools
 * - Bots under LLM or scenario simulation conditions
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Method `applyDecay(...)` is a no-op
 * - May log or emit a warning if memory size exceeds a threshold
 */
package com.orebit.mod.memory.decay_strategies;
