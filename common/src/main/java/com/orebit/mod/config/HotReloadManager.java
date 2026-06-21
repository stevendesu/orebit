/**
 * HotReloadManager.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This class enables live reloading of configuration files at runtime. It monitors the
 * config directory (or a specific file) for changes and, when detected, triggers a reload
 * of the mod's `Config` object. It optionally propagates updated settings to dependent
 * systems such as `GlobalSettingLimits`, pathfinding behavior, and LLM integration.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Watches the config file for external changes (via polling or filesystem events)
 * - Reloads and revalidates the configuration using `ConfigLoader` and `ConfigValidator`
 * - Updates in-memory references such as `GlobalSettingLimits` with the new values
 * - May emit events or logs when reloading occurs
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ConfigLoader`, which loads on demand, this class actively monitors the file
 * - Unlike `GlobalSettingLimits`, it does not interpret policy—it updates it
 * - Unlike `PolicyOverrideSource`, it doesn’t layer overrides—it updates the base config
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - File watching is supported on the server platform or simulated with polling
 * - Reloading is safe and will not disrupt active tasks or bots
 * - Invalid config changes are rejected or logged without crashing
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `ConfigLoader`, `ConfigValidator`: Used to rehydrate and clean the new config
 * - `GlobalSettingLimits`: Updated when a new config is successfully loaded
 * - `PerformanceMonitor`, `SettingsManager`, etc.: Optional subscribers for updates
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - Any system that allows runtime config tuning
 * - Admin commands or debug tools that invoke reload manually
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use a file watcher (e.g., Java NIO) or periodic hash check
 * - Should debounce multiple rapid writes
 * - Should log success/failure with clear diff reporting if possible
 * - May emit a `ConfigReloadedEvent` for system-wide notification
 */
package com.orebit.mod.config;
