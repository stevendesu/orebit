/**
 * ProximityDecayStrategy.java
 *
 * Subcomponent: memory/decay_strategies/
 * Environment: MAIN
 *
 * This strategy causes a memory to decay when the bot is no longer within
 * a specified proximity of the memory's associated anchor. Anchors may
 * represent players, locations, regions, or even entities. As long as the
 * bot remains near the anchor, the memory remains intact. Once the bot
 * leaves the configured radius, the decay logic activates and may
 * flag the memory for removal.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Prevents a memory from decaying while the bot is close to its anchor
 * - Triggers removal once the bot has moved far enough away for long enough
 * - Models real-world memory fading once stimuli or context are left behind
 * - Encourages context-aware memory that fades when it becomes irrelevant
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `TimeBasedDecayStrategy`, this does not decay purely over time
 * - Unlike `AccessFrequencyDecayStrategy`, it ignores access counts
 * - Unlike `EmotionalWeightDecayStrategy`, it focuses on physical/relational context
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The associated `MemoryEntry` includes a proximity anchor and target radius
 * - The `ProximityContext` provides information about the bot’s current position
 * - This strategy is evaluated periodically during `BotMemory` cleanup
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MemoryEntry`: Must expose its anchor and associated proximity radius
 * - `SimulationClock`: May be used to implement decay delays or cooldowns
 * - `ProximityContext`: Contains bot location and resolver logic for anchor targets
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `MemoryEntry`: Assigned this strategy when spatial relevance is key
 * - `BotMemory`: Invokes this strategy during its periodic cleanup pass
 * - `LLMInterface`, `GoalPlanner`: May preserve nearby memory for high-fidelity prompts
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - The anchor may be a:
 *     - `BlockPos` (location)
 *     - `Entity` (like a player or mob)
 *     - `RegionID`, `UUID`, or custom key
 * - Radius can be fixed or dynamic
 * - Optional: add time grace periods (e.g., “forget 10 seconds after leaving”)
 * - Optional: integrate with regions so memories persist within semantic zones
 */
package com.orebit.mod.memory.decay_strategies;
