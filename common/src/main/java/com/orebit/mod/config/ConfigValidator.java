/**
 * ConfigValidator.java
 *
 * Main Component: config/
 * Environment: MAIN
 *
 * This utility class ensures that a loaded `Config` object is valid and safe to use.
 * It performs range checks, default-filling, field validation, and normalization
 * to protect the system from invalid or dangerous configuration values.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Applies validation rules to a `Config` object
 * - Fills in missing or invalid values with safe defaults
 * - Logs warnings for any improper or deprecated config entries
 * - Ensures that downstream systems (like GlobalSettingLimits) receive clean input
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ConfigLoader`, this class does not read files—it cleans the result
 * - Unlike `Config.java`, this class is stateless—it operates on input values
 * - Unlike `GlobalSettingLimits`, this does not apply logic—it ensures validity
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Validation is invoked immediately after loading raw config
 * - Invalid fields can be fixed or replaced without crashing
 * - Some defaults may come from `DefaultSettings` or hardcoded policies
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Config.java`: The object being validated
 * - `DefaultSettings`, `GlobalSettingLimits`: May be referenced to fill in values
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ConfigLoader`: Always runs validator before returning the config
 * - `Debug tools`: May call this to verify config in live environments
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use helper methods like `clampInt`, `requireNonNull`, `warnIfDeprecated`
 * - Should separate validation concerns by section (e.g., LLM, pathfinding, limits)
 * - Designed to be idempotent—calling it repeatedly produces the same result
 */
package com.orebit.mod.config;
