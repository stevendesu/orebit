/**
 * SettingsManager.java
 *
 * Main Component: settings/
 * Environment: MAIN
 *
 * This class manages all bot configuration settings on the server. It acts as
 * the authoritative source for `DefaultSettings`, manages the lifecycle of
 * per-bot `BotSettings`, and applies runtime changes from config files, commands,
 * or API hooks. It provides safe, consistent access to the current configuration
 * of any bot at any time.
 *
 * The `SettingsManager` also supports hot-reloading of configuration, global
 * updates to default values, and fallback handling when settings are incomplete
 * or out of sync with disk.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks and applies per-bot `BotSettings`
 * - Stores and loads `DefaultSettings` from disk
 * - Exposes APIs for settings queries, updates, and resets
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BotSettings`, this is not bound to a single bot—it manages many
 * - Unlike `Config`, this is not general-purpose—it’s behavior-specific
 * - Unlike `ProfileManager`, this manages rules—not personalities
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot has an associated `BotSettings` object
 * - Defaults are applied when no per-bot overrides exist
 * - Settings are mutable at runtime and may be hot-reloaded
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotSettings`: The per-entity configuration model
 * - `DefaultSettings`: The fallback global template
 * - `Config`, `HotReloadManager`: May trigger changes or reloads
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotManager`, `VirtualPlayerController`: Apply per-bot configuration
 * - `Commands`: May read or modify settings via user commands
 * - `Pathfinder`, `Simulation`, `TaskExecutor`: Query policies on-the-fly
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should support:
 *     - `BotSettings getSettingsFor(BotId id)`
 *     - `void applyDefaultSettings(BotSettings target)`
 *     - `void reloadDefaultsFromDisk()`
 *     - `void setGlobal(PathfindingSettings newDefaults)`
 */
package com.orebit.mod.settings;
