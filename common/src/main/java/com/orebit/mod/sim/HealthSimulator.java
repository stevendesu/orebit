/**
 * HealthSimulator.java
 *
 * Main Component: sim/
 * Environment: MAIN
 *
 * This class simulates the health and damage system for bots. Because bots are
 * not controlled by Minecraft's internal player mechanics, this class manages
 * their health points, damage intake, healing, death handling, and invincibility
 * toggles. It enables survival-style gameplay, conditional combat behavior, and
 * status-aware decision making.
 *
 * The health simulator supports custom damage types, status effects, invincibility
 * modes, and callbacks for AI transitions (e.g. when wounded or dying).
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks current health and max health
 * - Applies damage and healing based on simulation or triggers
 * - Triggers death logic, AI interruptions, or failover systems
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `HungerSimulator`, this models damage—not stamina
 * - Unlike `TaskExecutor`, this responds to world state—it doesn’t act
 * - Unlike `Entity`, this is not native Minecraft logic—it’s a parallel simulation
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All damage applications are routed through this class
 * - Health can be clamped, capped, or ignored based on settings
 * - AI or memory systems may be notified of health changes
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `SimulationToggles`: May disable health simulation entirely
 * - `BehaviorProfile`, `AIStateMachine`: May switch states based on damage
 * - `CombatSystem`, `Pathfinder`, `Memory`: May read current health
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotMemory`: May log causes of injury or death
 * - `CombatAI`, `EscapePlanner`: May check health when under threat
 * - `Debug tools`: May display health bar or damage events
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should track:
 *     - `float currentHealth`
 *     - `float maxHealth`
 *     - `boolean invincible`
 * - Should expose:
 *     - `void applyDamage(float amount, DamageSource source)`
 *     - `void heal(float amount)`
 *     - `boolean isDead()`
 *     - `void setInvincible(boolean)`
 */
package com.orebit.mod.sim;
