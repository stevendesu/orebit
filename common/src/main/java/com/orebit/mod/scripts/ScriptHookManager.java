/**
 * ScriptHookManager.java
 *
 * Main Component: scripts/
 * Environment: MAIN
 *
 * This class manages the registration and dispatching of `EventScript`s tied
 * to named scripting hooks. It acts as the bridge between internal game events
 * and external, user-defined script behavior. When a hook is triggered, the
 * manager executes all registered scripts that listen to that event.
 *
 * Script hooks provide a powerful customization layer—enabling server owners,
 * admins, or developers to modify bot behavior dynamically, inject tasks,
 * log events, or trigger responses based on runtime context.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Registers scripts against named event hook types
 * - Dispatches all matching scripts when an event occurs
 * - Passes `ScriptContext` to each triggered script
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `TaskExecutor`, this is reactive—not goal-driven
 * - Unlike `EventBus`, this is script-facing—not component-facing
 * - Unlike `BehaviorProfile`, this is dynamic—not hardcoded
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Scripts are registered at load time or via hot-reload
 * - Context construction is handled by the hook caller
 * - Hook names are agreed-upon by mod convention (e.g., "onTaskFailed")
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `EventScript`: The runnable logic tied to hooks
 * - `ScriptContext`: Constructed and passed to each script
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskExecutor`, `Memory`, `CombatAI`, etc.: Call `triggerHook(...)`
 * - `Debug`, `LLM`, `Managers`: May register scripts at runtime
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose:
 *     - `void register(String hook, EventScript script)`
 *     - `void unregister(String hook, EventScript script)`
 *     - `void triggerHook(String hook, ScriptContext context)`
 * - May support:
 *     - Wildcard dispatching (e.g. `onAnyTaskComplete`)
 *     - Asynchronous execution for non-blocking logic
 */
package com.orebit.mod.scripts;
