/**
 * ConfigLoader.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This class is responsible for loading the mod’s configuration from disk.
 * It reads the serialized config file (e.g., JSON, TOML, YAML), deserializes it
 * into a `Config` object, applies validation and normalization (via `ConfigValidator`),
 * and makes the result available for use throughout the system.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Locates and opens the configuration file (e.g., config/virtualplayermod.json)
 * - Parses the raw contents into a structured `Config` object
 * - Passes the result through a validation layer before returning it
 * - Optionally supports fallback to defaults or generation of missing files
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `Config.java`, this class doesn’t hold config data—it loads it
 * - Unlike `GlobalSettingLimits`, this does not enforce policy—it provides the source
 * - Unlike `ConfigValidator`, this class does not define validation rules—it invokes them
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The config file format and location are fixed or configurable
 * - Errors in config parsing are recoverable (e.g., defaults can be applied)
 * - This class may be invoked at startup or during live reload
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Config.java`: The target object that holds structured values
 * - `ConfigValidator`: Used to clean, correct, or reject invalid values
 * - JSON/TOML/YAML parsing library (e.g., Gson, Jackson, etc.)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `Main mod entrypoint`: Loads config during startup
 * - `GlobalSettingLimits`: Receives validated values
 * - `HotReloadManager` (optional): May call this on file change
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should catch and log errors gracefully, with fallback to defaults
 * - May support versioning or migration in the future
 * - Could support environment overrides (e.g., dev vs. prod config)
 */
package com.orebit.mod.config;
