/**
 * EmotionalWeightDecayStrategy.java
 *
 * Subcomponent: memory/decay_strategies/
 * Environment: MAIN
 *
 * This strategy uses emotional significance or "weight" to guide memory decay.
 * Memory entries with low emotional impact are discarded more quickly,
 * while those with high impact (e.g., betrayal, praise, near-death experiences)
 * are retained longer or indefinitely. This models the way real memories
 * are influenced by emotional salience.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Applies different decay rates depending on an entry’s weight or emotion tag
 * - Keeps “high-impact” events (e.g., trauma, strong trust, betrayal) longer than neutral ones
 * - May downgrade but not delete significant events (for narrative consistency)
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `TimeBasedDecayStrategy`, this model is non-linear and content-sensitive
 * - Unlike `AccessFrequencyDecayStrategy`, this strategy ignores access history
 * - Unlike `NoDecayStrategy`, this actively manages memory to emphasize "important" events
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - `MemoryEntry` includes an emotional weight or category tag (e.g., JOY, FEAR, TRUST, ANGER)
 * - Memory weight decays slowly over time, or not at all above a threshold
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MemoryEntry`: Must include a score or tag indicating emotional weight
 * - `SimulationClock`: Used to apply weight decay over time
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - Bots with narrative identity, long-term motivations, or adaptive relationships
 * - LLM systems: Use this to explain why a bot remembers something
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Entries with `weight >= threshold` are retained indefinitely
 * - Others decay linearly or exponentially until below discard threshold
 * - May support emotional tag sets (`Set<Tag> protectedTags`)
 * - Designed for bots with personality, history, or long memory arcs
 */
package com.orebit.mod.memory.decay_strategies;
