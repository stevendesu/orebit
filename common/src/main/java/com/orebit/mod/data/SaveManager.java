/**
 * SaveManager.java
 *
 * Main Component: data/
 * Environment: MAIN
 *
 * This class is responsible for managing the persistence of all bot-related data.
 * It handles saving and loading `BotSaveData` for every active or remembered bot,
 * coordinating disk I/O, serialization, versioning, and runtime reconstruction.
 * It acts as the central authority for reading and writing bot state between sessions.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks active and historical bot save files
 * - Serializes `BotSaveData` objects to disk on shutdown or interval
 * - Loads and deserializes bot data on server startup or bot spawn
 * - Ensures compatibility across versions and supports schema evolution
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotSaveData`, this class operates on *many bots*, not just one
 * - Unlike `WorldSaveSnapshot`, this class handles persistent, bot-specific state
 * - Unlike `BotManager`, it does not manage live entities—it manages disk state
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Bots are identified by UUID or world-relative ID
 * - Save data is stored in a mod-specific subfolder under `world/data/` or equivalent
 * - The system must be able to recover cleanly from partial or missing saves
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotSaveData`: Core data structure for persistence
 * - File I/O and serialization framework (e.g., NBT, JSON, custom format)
 * - `BotManager`: Provides runtime bots that need saving or rehydration
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotManager`: Loads saved bots when players log in or when village bots spawn
 * - `WorldSaveSnapshot`: May call this to coordinate global snapshots
 * - `Debug tools`: May use this to inspect or dump saved bot data
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should support atomic save operations (e.g., temp file + replace)
 * - May throttle or defer writes using a save queue or debounce timer
 * - Should expose both batch and single-bot save/load methods
 * - May use version tags in serialized data to support backward compatibility
 */
package com.orebit.mod.data;
