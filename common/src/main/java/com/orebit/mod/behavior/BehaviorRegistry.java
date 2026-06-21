/**
 * BehaviorRegistry.java
 *
 * Main Component: behavior/
 * Environment: MAIN
 *
 * This class maintains a global, read-only registry of all known `BehaviorProfile`
 * definitions available in the mod. It is the authoritative source for retrieving
 * predefined bot personalities—whether built-in, configured via data pack, or
 * dynamically registered by other mods or plugins.
 *
 * Profiles in the registry are immutable, shareable across bots, and uniquely
 * identified by keys or IDs. The registry enables spawning bots with consistent
 * behavior, assigning personality templates at world generation, or selecting
 * from predefined "roles" in scripted scenarios.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores a collection of globally available `BehaviorProfile`s
 * - Allows profiles to be looked up by name, key, or tag
 * - Supports registration of custom or mod-provided profiles
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BehaviorManager`, this is static—not per-bot or per-session
 * - Unlike `BehaviorSettings`, this stores templates—not instance data
 * - Unlike `profiles/`, this does not define profile logic—only stores it
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All profiles in the registry are immutable and thread-safe
 * - Profile keys are globally unique
 * - Registration happens at startup or world load—not during runtime behavior
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BehaviorProfile`: The core personality definition type
 * - (Optionally) `Identifier`, `ResourceLocation`, or a string key system
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BehaviorManager`: Loads and assigns profiles from this registry
 * - `WorldGen`, `Scripting`, or `LLM Integration`: May look up by role/tag
 * - `Debug Tools`: May display or inspect registry contents
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Likely implemented as a map: `Map<String, BehaviorProfile>`
 * - May support tag-based queries (e.g., "any friendly explorer")
 * - Should offer:
 *     - `register(key, profile)`
 *     - `get(key)`
 *     - `listAll()`
 */
package com.orebit.mod.behavior;
