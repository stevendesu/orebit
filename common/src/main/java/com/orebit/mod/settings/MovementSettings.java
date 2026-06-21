/**
 * MovementSettings.java
 *
 * Main Component: settings/
 * Environment: MAIN
 *
 * This class defines all tunable parameters that affect a bot's physical movement.
 * These parameters do not alter Minecraft's core physics engine, but are used by
 * simulation systems and path-following logic to determine movement cost, speed,
 * constraints, or action availability.
 *
 * Movement settings may include multipliers for walking speed, toggles for jump
 * capability, fall behavior, sprinting permissions, and other physically grounded
 * options that alter how a bot navigates the world.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Configures movement multipliers, toggles, and constraints
 * - Modifies how a bot applies or interprets `BlockPathOperation`s
 * - Allows per-bot or default control of locomotion behavior
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `SimulationToggles`, this affects navigation—not survivability
 * - Unlike `PathfindingSettings`, this affects capability—not planning
 * - Unlike `VirtualPlayerController`, this does not directly execute movement
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - These settings are used only by bots—not by real players
 * - All operations must respect these constraints during simulation
 * - External systems (like pathfinding) may query these settings per bot
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly, but referenced by:
 *     - `BotSettings`
 *     - `DefaultSettings`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathFollower`, `VirtualPlayerController`: Use to guide movement logic
 * - `Pathfinding heuristics`: May use for cost estimation
 * - `Debug tools`: May display movement bounds or limits
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May include fields like:
 *     - `float walkSpeedMultiplier`
 *     - `boolean canSprint`
 *     - `boolean canJump`
 *     - `boolean allowFallDamage`
 * - May expose:
 *     - `float getSpeedFor(Operation op)`
 *     - `boolean canPerform(Operation op)`
 */
package com.orebit.mod.settings;
