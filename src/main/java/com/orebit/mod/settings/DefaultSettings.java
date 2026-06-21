/**
 * DefaultSettings.java
 *
 * Main Component: settings/
 * Environment: MAIN
 *
 * This class defines the global default configuration used when initializing
 * new bots. All bots will copy these settings unless explicitly overridden.
 * Server operators can edit this configuration (potentially via config files
 * or commands) to enforce uniform behavior across all bots on the server.
 *
 * Unlike `BotSettings`, this object is not associated with a specific bot
 * instance and is instead managed by the `SettingsManager`. It may also
 * provide default values for runtime reversion or debugging.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores the default values for bot movement, simulation, and pathfinding
 * - Serves as the initializer for new bots' `BotSettings`
 * - Defines the server-wide baseline behavior
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BotSettings`, this is global—not per-entity
 * - Unlike `Config`, this is behavioral—not system-wide mod logic
 * - Unlike `SettingsManager`, this is passive—not authoritative
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Settings here apply to all bots unless explicitly overridden
 * - Values are loaded from disk or hardcoded as safe fallbacks
 * - Changes to this object do not retroactively affect bots already spawned
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MovementSettings`, `PathfindingSettings`, `SimulationToggles`: Provide structure
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `SettingsManager`: Uses this to initialize and reset bot settings
 * - `BotSettings`: May reference or apply values from here
 * - `Config`: May support hot-reloading of these values
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally mirrors the structure of `BotSettings`
 * - Should support:
 *     - `static DefaultSettings loadFromFile(...)`
 *     - `MovementSettings getMovementDefaults()`
 *     - `boolean isBlockBreakAllowedByDefault()`
 */
package com.orebit.mod.settings;
