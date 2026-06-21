/**
 * BotSettings.java
 *
 * Main Component: settings/
 * Environment: MAIN
 *
 * This class defines the full set of tunable settings that govern the behavior
 * of a single bot. These settings control how the bot moves, how it navigates,
 * which game rules it obeys or overrides, and how it interacts with the world.
 * It is the per-instance configuration equivalent of `DefaultSettings` and may
 * override or extend those values.
 *
 * Settings are designed to be toggled at runtime, reloaded from disk, or adjusted
 * via chat commands, allowing server owners or players to modify bot behavior on
 * the fly. All settings are isolated from logic and are not hardcoded into any
 * system—each setting corresponds to a specific control point in simulation.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores all configurable options for a single bot instance
 * - Encapsulates movement, pathfinding, simulation, and behavior flags
 * - Acts as a source of truth for runtime behavior tuning
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `DefaultSettings`, this is instance-specific—not global
 * - Unlike `SimulationClock`, this controls how a bot behaves—not when
 * - Unlike `Config`, this is mutable at runtime per entity
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Settings are applied per bot and may change during gameplay
 * - A `SettingsManager` manages synchronization and storage
 * - All subsystems honor their corresponding setting flags
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `DefaultSettings`: Provides fallback/default values
 * - `MovementSettings`, `PathfindingSettings`, `SimulationToggles`: Compose this object
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `VirtualPlayerController`, `PathFollower`: Use movement/pathfinding settings
 * - `SimulationSubsystems`: Use simulation toggles
 * - `Commands`: May allow user-facing modification of settings
 * - `Serialization`: Used to save and reload bot configurations
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May internally contain:
 *     - `MovementSettings movement`
 *     - `PathfindingSettings pathfinding`
 *     - `SimulationToggles simulation`
 * - Should expose:
 *     - `boolean isInvincible()`
 *     - `boolean allowBlockBreak()`
 *     - `float getMoveSpeedMultiplier()`
 *     - `void applyDefaults(DefaultSettings)`
 */
package com.orebit.mod.settings;
