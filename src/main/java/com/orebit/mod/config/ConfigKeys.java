/**
 * ConfigKeys.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This class defines constant string keys for all known config fields used in
 * the serialized config file. It provides a central source of truth for key names
 * used in documentation, deserialization, validation, and config generation tools.
 * This class helps avoid duplication and typos across systems interacting with config.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Declares string constants for every recognized config key
 * - Enables consistency between the file format, loader, and validation logic
 * - Can be used to auto-generate documentation or config templates
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `Config.java`, this class does not store values—it declares keys
 * - Unlike `ConfigLoader`, it doesn’t read config—it provides field names
 * - Unlike `GlobalSettingLimits`, this class plays no role in runtime enforcement
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Keys are stable and consistent across mod versions
 * - Used across parsing, validation, and documentation layers
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly—used statically by `ConfigLoader`, `ConfigValidator`, etc.
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ConfigLoader`: May use these keys for field mapping or migration
 * - `ConfigValidator`: May use keys to check and warn about missing fields
 * - Doc generation or config GUI (if added later)
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Constant naming convention should match config structure (e.g., `LLM_ENABLED`, `MAX_BOTS`)
 * - May be grouped by subsystem (e.g., LLM, AI, pathfinding)
 * - Should be documented to enable easier config writing by users
 */
package com.orebit.mod.config;
