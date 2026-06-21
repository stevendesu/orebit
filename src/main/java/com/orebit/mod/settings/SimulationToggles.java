/**
 * SimulationToggles.java
 *
 * Main Component: settings/
 * Environment: MAIN
 *
 * This class defines which game systems are simulated for a given bot. These
 * settings control whether the bot is affected by hunger, damage, drowning,
 * fall damage, or similar mechanics. Server owners or behavior profiles can
 * use these toggles to simplify the simulation, improve performance, or test
 * logic in isolation from survival constraints.
 *
 * This provides fine-grained control over bot vulnerability and realism, and
 * is especially useful for cosmetic bots, performance-sensitive deployments,
 * or creative-mode servers.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Specifies whether survival mechanics are enabled for a bot
 * - Used to selectively disable hunger, health, and similar systems
 * - Enables invincibility, infinite stamina, or sandbox behavior
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `MovementSettings`, this affects damage—not mobility
 * - Unlike `PathfindingSettings`, this affects internal state—not navigation
 * - Unlike `Config`, this is per-bot, not global
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Systems will respect these toggles before applying status changes
 * - Used during both tick updates and simulation routines
 * - Can be updated live via commands or hot reload
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly; included in `BotSettings` and `DefaultSettings`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `HealthSimulator`, `HungerSimulator`, `StatusEffectHandler`
 * - `TaskExecutor`, `CombatSystem`, `EnvironmentalHazardLogic`
 * - `Debug tools`: May indicate toggled systems in overlays
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May include:
 *     - `boolean simulateHealth`
 *     - `boolean simulateHunger`
 *     - `boolean simulateFallDamage`
 *     - `boolean simulateBreathing`
 * - Should expose:
 *     - `boolean isImmuneTo(String subsystem)`
 *     - `boolean isSurvivalEnabled()`
 */
package com.orebit.mod.settings;
