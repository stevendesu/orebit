/**
 * BotMemory.java
 *
 * Main Component: memory/
 * Environment: MAIN
 *
 * This class represents the full memory system for a single bot, containing all
 * the `MemoryEntry` objects that reflect its long-term observations, experiences,
 * and decisions. It provides APIs for recording new memories, recalling past ones,
 * tagging, filtering, and evaluating entries for decay. The decay process is
 * decentralized—each memory entry contains its own `MemoryDecayStrategy` and
 * determines individually whether it should be forgotten based on stored context.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores and manages the full list of a bot’s memory entries
 * - Adds new entries and tags them with decay strategies and contextual metadata
 * - Periodically evaluates all entries for decay by delegating to their strategies
 * - Provides high-level APIs for recall, summarization, and debugging
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `MemoryEntry`, this class handles a collection—not individual memories
 * - Unlike a central `MemoryDecayStrategy`, this class does not contain decay logic—it delegates it
 * - Unlike `SaveManager`, this class handles runtime state, not persistent serialization
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot owns one instance of `BotMemory`
 * - Memory decay occurs periodically (e.g., every N ticks or on low-memory triggers)
 * - The memory system is bounded in size and subject to pruning or compression
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MemoryEntry`: The unit of memory stored in this container
 * - `MemoryContext`: Stored on each entry to describe creation context
 * - `SimulationClock`: Used during decay and scoring
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskPlanner`: May query relevant memories to inform task trees
 * - `LLMInterface`: May extract and summarize entries for prompt context
 * - `ReplayRecorder`: May snapshot memory for visualization or analysis
 * - `Debug tools`: May inspect, sort, and step through entries
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Maintains:
 *     - A list or map of `MemoryEntry` objects
 *     - Optional indexes or categories for filtering
 * - Exposes:
 *     - `void remember(MemoryEntry entry)`
 *     - `List<MemoryEntry> recall(Predicate<MemoryEntry> filter)`
 *     - `void decay(SimulationClock now, WorldData world)`
 * - During `decay(...)`, calls:
 *     `if (entry.shouldBeForgotten(now, world)) forget(entry);`
 * - May track memory pressure, recent recalls, or forget stats for introspection
 */
package com.orebit.mod.memory;
