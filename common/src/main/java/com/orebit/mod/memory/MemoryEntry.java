/**
 * MemoryEntry.java
 *
 * Main Component: memory/
 * Environment: MAIN
 *
 * This class represents a single unit of memory for a bot—an experience,
 * observation, or fact that was recorded during gameplay. Each entry stores
 * the event content itself, along with contextual metadata such as time,
 * location, emotional significance, and decay behavior. Entries are designed
 * to be self-contained and responsible for managing their own lifespan
 * through an associated `MemoryDecayStrategy`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores a specific event or fact the bot remembers
 * - Persists associated metadata:
 *     - Time of creation
 *     - Emotional weight
 *     - Tags and categories
 *     - Anchors (e.g., location, player UUID, region ID)
 * - Captures the `MemoryContext` at creation time
 * - Delegates memory decay checks to its assigned `MemoryDecayStrategy`
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotMemory`, this is a single memory—not a collection
 * - Unlike `MemoryContext`, this represents the memory—not just the context in which it was created
 * - Unlike `MemoryDecayStrategy`, this is a data record—it doesn’t contain logic beyond delegation
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Entries are immutable except for internal metadata like access timestamps or decay status
 * - Each entry knows how to determine whether it should be forgotten by calling its strategy
 * - Entries may be serialized for long-term persistence
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MemoryContext`: Captured at memory creation, used during decay evaluation
 * - `MemoryDecayStrategy`: Determines when and how the memory expires
 * - Optional: `Tag`, `Emotion`, `EntityAnchor`, etc. for classification
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotMemory`: Stores and iterates over entries
 * - `MemoryDecayStrategy`: Called by the entry during cleanup passes
 * - `LLMInterface`, `ReplayRecorder`, `Debug tools`: May inspect or visualize entries
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Core fields may include:
 *     - `String description` or `Object payload` (e.g., task, event, message)
 *     - `MemoryContext context`
 *     - `MemoryDecayStrategy strategy`
 *     - `long createdAtTick` (redundant with context.timestamp if preferred)
 *     - `float emotionalWeight` or `EnumSet<Emotion>`
 *     - `Set<String> tags`
 *     - `int accessCount`, `long lastAccessTick`
 * - Provides:
 *     - `boolean shouldBeForgotten(SimulationClock now, WorldData world)`
 *     - `void markAccessed(long tick)`
 *     - `Optional<Float> getRelevanceScore()`
 * - Must be cheap to evaluate and consistent in behavior
 */
package com.orebit.mod.memory;
