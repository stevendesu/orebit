/**
 * HungerSimulator.java
 *
 * Main Component: sim/
 * Environment: MAIN
 *
 * This class simulates Minecraft's hunger system for bots, including food level,
 * saturation, starvation damage, and auto-healing. It enables survival-style
 * gameplay and intelligent self-care decisions, such as eating before combat,
 * stocking food for journeys, or retreating when starving.
 *
 * Hunger is influenced by time, movement, exertion, and simulation settings.
 * Unlike real players, bots must plan and act proactively to avoid starvation,
 * and this class integrates with both inventory and AI logic to enable that.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks food level and saturation
 * - Applies passive decay over time and with activity
 * - Triggers starvation damage or disables healing when low
 * - Integrates with inventory to consume food items
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `HealthSimulator`, this is not about direct damage—it’s metabolic
 * - Unlike `SimulationToggles`, this handles behavior—not flags
 * - Unlike `TaskExecutor`, this simulates status—it doesn’t act on its own
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Hunger is ticked regularly and responds to bot exertion
 * - Bots have access to inventory to consume food items
 * - AI state machine may adjust behavior based on hunger thresholds
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `InventorySimulator`: Supplies food items
 * - `SimulationToggles`: May disable hunger entirely
 * - `HealthSimulator`: May coordinate healing or starvation effects
 * - `BotSettings`: May influence rate of hunger loss
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: May switch to “eating” or “rest” state when hungry
 * - `TaskExecutor`: May trigger food crafting or foraging tasks
 * - `Memory`, `BehaviorProfile`: May track or prioritize food-rich regions
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should track:
 *     - `float foodLevel` (0–20)
 *     - `float saturation` (0–20)
 *     - `int starvationTickCounter`
 * - Should expose:
 *     - `void tickActivityLevel(float multiplier)`
 *     - `boolean isHungry()`
 *     - `void consumeFood(ItemStack item)`
 *     - `boolean canHealNaturally()`
 */
package com.orebit.mod.sim;
