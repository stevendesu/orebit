/**
 * PolicyOverrideSource.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This class provides a mechanism for overriding global config policies at runtime,
 * typically via in-game admin commands, scripts, or automated rule systems.
 * It acts as an in-memory layer that can replace or supplement values from
 * `GlobalSettingLimits`, allowing temporary or context-sensitive adjustments to policy.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores overrides to global policies (e.g., temporarily enable block breaking)
 * - Provides accessors that merge overrides with defaults from `GlobalSettingLimits`
 * - Supports runtime updates via commands, scripts, or admin tools
 * - Enables dynamic policy shifts without restarting the server
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `GlobalSettingLimits`, which is derived from disk config, this is *runtime-editable*
 * - Unlike `HotReloadManager`, this doesn’t read files—it responds to commands or triggers
 * - Unlike `SettingsManager`, this class does not handle per-bot configuration—it overrides policy
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Overrides are applied safely and take precedence over static config
 * - Systems that consume limits query this source, not just the static policy object
 * - Admins or automation systems are allowed to trigger overrides
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `GlobalSettingLimits`: The fallback values when no override is present
 * - `SettingsManager`, `TaskExecutor`, `Pathfinder`, etc.: May query merged policies
 * - `DebugCommandHandler`: May expose commands to apply or clear overrides
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - Any system enforcing behavior restrictions (e.g., block breaking, max range)
 * - Live tuning systems, scripted scenarios, admin control panels
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use a simple map of setting keys → override values
 * - Supports clearing or resetting overrides at runtime
 * - Should log all policy changes and emit change events if needed
 * - May support scoped overrides (e.g., by dimension, by player)
 */
package com.orebit.mod.config;
