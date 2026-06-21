/**
 * GlobalSettingLimits.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This class defines global policy limits that constrain the behavior of all bots,
 * regardless of their individual settings. It is designed to enforce server-wide rules
 * such as whether bots can break blocks, how far they can pathfind, or whether they
 * are allowed to take damage. These values are typically loaded from `Config.java` at
 * startup and applied consistently throughout runtime.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores global hard limits and toggles that override per-bot behavior
 * - Defines safe defaults for behavior that server owners may want to restrict
 * - Applies enforcement logic during bot spawn, task execution, and path planning
 * - Prevents runtime configuration changes from violating global constraints
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotSettings` or `MovementSettings`, this file applies to *all bots*
 * - Unlike `SettingsManager`, this class only stores and enforces limits—it doesn't manage state
 * - Unlike `Config.java`, this file represents *interpreted policy*, not raw configuration format
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Limits are loaded from `Config.java` at server startup and cached in this class
 * - All systems applying per-bot settings check against this policy before applying them
 * - Any violation (intentional or unintentional) is blocked or logged
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Config.java`: Provides source values for these limits
 * - `SettingsManager`: May call this class to filter per-bot settings
 * - `PathfindingSettings`, `SimulationToggles`, `MovementSettings`: All affected by limits
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotManager`: Applies limits during bot spawn or reinitialization
 * - `SettingsManager`: Validates per-bot settings against this policy
 * - `Pathfinder`, `TaskExecutor`, `VirtualPlayerController`: May query limits directly
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Contains only hard limits, not current values or mutable settings
 * - May expose helper methods like `enforce(BotSettings settings)`
 * - Should be cached in memory and remain immutable during runtime unless hot-reload is enabled
 * - May include enforcement logging or policy violation tracking in the future
 */
package com.orebit.mod.config;
