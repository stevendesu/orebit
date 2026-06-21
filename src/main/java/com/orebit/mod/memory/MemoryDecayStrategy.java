/**
 * MemoryDecayStrategy.java
 *
 * Main Component: memory/
 * Environment: MAIN
 *
 * This interface defines a per-entry strategy for determining whether a memory
 * should be forgotten, degraded, or retained. Each `MemoryEntry` is assigned
 * a decay strategy at creation time, based on its type, context, or origin.
 * The strategy evaluates decay based on internal fields like timestamps,
 * emotional weight, proximity to anchors, or access frequency.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Declares a contract for checking whether a specific memory entry should be removed
 * - Allows memory entries to self-manage their lifecycle over time
 * - Supports multiple strategies coexisting within the same bot’s memory
 * - Enables highly customizable and realistic memory behavior
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike the previous global decay model, this is per-entry and decoupled from `BotMemory`
 * - Unlike `BotMemory`, this class doesn’t manage lists—it inspects single entries
 * - Unlike specific strategy classes (`TimeBasedDecayStrategy`, etc.), this is the abstract contract
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each `MemoryEntry` stores a reference to one `MemoryDecayStrategy`
 * - `BotMemory` calls `shouldRemove()` on each entry during cleanup
 * - Additional context (e.g., time, proximity, or profile) may be passed into the strategy
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MemoryEntry`: The subject of evaluation
 * - `SimulationClock`: Provides the current in-game time
 * - `ProximityContext`: Optional, describes spatial or relationship context relevant to the bot
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `MemoryEntry`: Delegates decay decisions to this strategy
 * - `BotMemory`: Iterates over entries and removes those flagged for decay
 * - `DecayStrategyFactory` (optional): Chooses the right strategy for new memories
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Core method:
 *     `boolean shouldRemove(MemoryEntry entry, SimulationClock clock, ProximityContext context);`
 * - May include:
 *     - Soft decay behavior (e.g., `applySoftDecay(...)`)
 *     - Priority demotion or emotional weight decay
 * - Strategies may be stateful or stateless
 * - Allows simulation, debugging, and testing of memory retention under varied conditions
 */
package com.orebit.mod.memory;
