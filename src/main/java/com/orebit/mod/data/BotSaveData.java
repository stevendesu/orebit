/**
 * BotSaveData.java
 *
 * Main Component: data/
 * Environment: MAIN
 *
 * This class contains all persistable data specific to a single bot instance.
 * It represents the long-term save format for a bot’s identity, inventory, task state,
 * relationships, and more. It is serialized to disk and reloaded when the server starts
 * or the bot respawns, ensuring continuity across sessions.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores bot-specific data such as:
 *     - UUID, name, and ownership info
 *     - Active task queue or goals
 *     - Personality profile and settings
 *     - Inventory snapshot and equipment
 *     - Relationship scores and memory state
 * - Provides methods for serialization and deserialization
 * - May support versioning or schema migration over time
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `SaveManager`, this class handles data *for one bot*, not the whole world
 * - Unlike `VirtualPlayerContext`, this is not a runtime object—it represents persistent state
 * - Unlike `WorldSaveSnapshot`, it does not track the environment—only the bot
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - One `BotSaveData` exists per persistent bot
 * - The save/load format is consistent and backward-compatible
 * - Complex fields (inventory, memory, etc.) are delegated to their own serializers
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `InventorySimulator`, `TaskExecutor`, `BotMemory`, `RelationshipGraph`: Provide data to be saved
 * - Minecraft's NBT, JSON, or custom serialization utilities
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `SaveManager`: Loads and stores all `BotSaveData` instances
 * - `BotManager`: Uses this data when respawning or restoring bots
 * - `Debug tools`: May dump this structure for inspection or testing
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Likely stored in a file keyed by UUID or world-relative ID
 * - Should cleanly separate persistent fields from volatile runtime state
 * - May include timestamps for last update, last seen, or last interaction
 * - Optional schema versioning helps migrate save formats between mod versions
 */
package com.orebit.mod.data;
