/**
 * Config.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This class defines the structure of all raw configuration values loaded from disk.
 * It represents the source of truth for global mod settings, including paths, flags,
 * integration endpoints, and default system behaviors. These values are parsed from
 * a file (e.g., JSON, TOML) by `ConfigLoader`, validated by `ConfigValidator`, and
 * used to initialize runtime systems like `GlobalSettingLimits`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines fields corresponding to all configurable global options
 * - Stores values that are loaded from disk and optionally hot-reloaded
 * - Acts as the source for default limits and feature toggles
 * - May include nested structures for LLM settings, simulation, pathfinding, etc.
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `GlobalSettingLimits`, this class stores raw values—not enforced policies
 * - Unlike `BotSettings`, this file is not per-bot—it governs mod-wide defaults
 * - Unlike `ConfigLoader`, this class contains data—not file I/O
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - This file is populated via deserialization (e.g., from JSON or TOML)
 * - Defaults may be specified inline or filled by `ConfigValidator`
 * - Changes to this class may require migration support or defaulting
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - JSON/TOML/YAML deserialization system (e.g., Gson, Jackson, etc.)
 * - Used by `ConfigLoader`, `ConfigValidator`, `GlobalSettingLimits`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `GlobalSettingLimits`: Interprets these values as enforced constraints
 * - `SimulationClock`, `AILoadBalancer`, `LLMInterface`: May consume settings directly
 * - `Debug tools`: May display current config for review
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Fields should be public or use appropriate accessors for deserialization
 * - Should be serializable to support hot-reload or live inspection
 * - Field names may be defined in `ConfigKeys` to support consistent documentation
 */
package com.orebit.mod.config;
