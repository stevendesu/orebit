/**
 * EventScript.java
 *
 * Main Component: scripts/
 * Environment: MAIN
 *
 * This class represents a script that is triggered in response to an event.
 * Each `EventScript` is tied to a specific `ScriptHook` (such as "onTaskComplete"
 * or "onEnterRegion") and contains logic—written in a supported scripting language
 * or DSL—that is executed when the hook fires.
 *
 * Scripts may influence bot behavior, trigger commands, alter relationships, or
 * modify memory. They provide a lightweight and dynamic way to inject logic into
 * the system without requiring hardcoded Java changes.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores a script tied to a specific event hook
 * - Executes script logic when the corresponding event occurs
 * - Provides access to context such as bot, world, task, etc.
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Task`, this does not represent long-term goals—just reactions
 * - Unlike `TaskNode`, this is not a plan—it’s a side-effect trigger
 * - Unlike `BehaviorProfile`, this is dynamic and server-defined—not hardcoded
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Scripts are tied to named `ScriptHook`s
 * - Scripts run in a sandboxed or declarative environment
 * - Scripts may reference a `ScriptContext` for variables and state
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `ScriptHookManager`: Dispatches the script at runtime
 * - `ScriptContext`: Supplies environment variables for execution
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `Server admin configuration`: Defines or loads custom event scripts
 * - `ScriptHookManager`: Registers and invokes these
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should track:
 *     - Hook name / event type
 *     - Code block or script body
 *     - Optional filters or preconditions
 * - Should expose:
 *     - `void execute(ScriptContext context)`
 */
package com.orebit.mod.scripts;
