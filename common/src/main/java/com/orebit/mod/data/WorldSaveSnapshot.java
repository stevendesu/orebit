/**
 * WorldSaveSnapshot.java
 *
 * Main Component: data/
 * Environment: MAIN
 *
 * This class represents a snapshot of all bot-related state across the entire world.
 * It is used for saving and restoring global information that may not be tied to any
 * single bot, such as known regions, world-level memories, historical LLM context,
 * or batch-level simulation metadata. It supplements per-bot saves with world-level persistence.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores and loads world-scope data (not tied to a single bot)
 * - Persists global maps, region graphs, and environmental metadata
 * - Supports save/load hooks during world save and mod unload
 * - May eventually support full save state replays or backups
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotSaveData`, this file is *not per bot*—it contains shared world information
 * - Unlike `SaveManager`, this class holds state—it does not manage I/O
 * - Unlike `MemoryEntry`, this is not short-term experience data—it’s structural
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The world’s relevant state is serializable in a reasonable amount of time
 * - Loaded only once on world start, saved periodically or on shutdown
 * - All globally important structures are coordinated through this snapshot
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `RegionGraph`, `KnownLocationMap`, `GlobalLLMContext`, etc. (if applicable)
 * - Serialization framework (e.g., NBT, JSON, or custom binary format)
 * - `SaveManager`: May call this as part of a global save pass
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RegionManager`: May store region connectivity or known transitions
 * - `LLMInterface`: May store persistent conversation context or token history
 * - `ReplayRecorder`: May snapshot this structure as part of a replay log
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should be modular: load/save only what’s needed for active features
 * - May internally delegate to sub-snapshot classes (e.g., `RegionSnapshot`)
 * - Should include a version header for compatibility
 * - Designed to complement bot-level persistence without duplicating it
 */
package com.orebit.mod.data;
