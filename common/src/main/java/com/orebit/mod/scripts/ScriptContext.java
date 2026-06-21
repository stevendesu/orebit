/**
 * ScriptContext.java
 *
 * Main Component: scripts/
 * Environment: MAIN
 *
 * This class provides a dynamic runtime context for `EventScript` execution.
 * It includes references to the bot, world, task, triggering event, and other
 * environmental variables that are relevant to the currently dispatched script.
 *
 * Scripts use this context to make decisions, read state, or affect outcomes.
 * For example, a script reacting to "onDamageTaken" might access:
 * - The bot's health
 * - The attacker
 * - The cause of damage
 * - The memory system or nearby allies
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Serves as a container for runtime information during script execution
 * - Provides access to bot, task, world, region, and more
 * - Prevents scripts from needing direct access to core components
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BotContext`, this is temporary and event-scoped—not global
 * - Unlike `TaskNode`, this is reactive—not prescriptive
 * - Unlike `MemoryEntry`, this is not persisted—it exists only during script runs
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Script hooks inject a fully constructed context per invocation
 * - Scripts are sandboxed and should not mutate this directly
 * - All context fields may be null depending on the hook
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayer`, `Task`, `Region`, `MemoryEntry`, etc.
 * - Possibly entity or block references
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `EventScript`: Uses this to access runtime state
 * - `ScriptHookManager`: Builds and dispatches it
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should include:
 *     - `VirtualPlayer bot`
 *     - `Task triggeringTask`
 *     - `DamageSource cause`
 *     - `Entity target`
 *     - `BlockPos location`
 *     - `Region currentRegion`
 *     - `long gameTime`
 *     - Arbitrary metadata map (`Map<String, Object>`)
 * - Should expose:
 *     - `T get(String key, Class<T> type)`
 *     - `boolean has(String key)`
 */
package com.orebit.mod.scripts;
