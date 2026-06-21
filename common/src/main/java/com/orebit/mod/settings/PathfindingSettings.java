/**
 * PathfindingSettings.java
 *
 * Main Component: settings/
 * Environment: MAIN
 *
 * This class defines behavioral and strategic constraints used during pathfinding.
 * These settings affect what types of routes are valid for a bot, how aggressively
 * the pathfinder can mutate the world (e.g. breaking blocks), and what tradeoffs
 * are made between cost, safety, and performance.
 *
 * Unlike `MovementSettings`, which governs physical capability, this class governs
 * **decision-making** and route planning—what is *allowed* or *preferred*.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Controls path planner permissions (e.g., break blocks, build bridges)
 * - Sets limits on cost, distance, or complexity of paths
 * - Enables tuning of risk tolerance or environmental preferences
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `MovementSettings`, this affects decisions—not motion
 * - Unlike `SimulationToggles`, this affects planning—not damage
 * - Unlike `HeuristicFunction`, this affects policy—not math
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - These settings are used during the path planning stage, not execution
 * - Subsystems will respect constraints when generating or validating paths
 * - May be overridden per bot, profile, or behavior state
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly; part of `BotSettings` and `DefaultSettings`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathfinder`, `RegionPathfinder`: Query for permissions and constraints
 * - `PathReplanner`: May adjust cost thresholds or fallback policies
 * - `BehaviorProfile`: May alter based on personality (e.g., cautious vs reckless)
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May include:
 *     - `boolean allowBlockBreak`
 *     - `boolean allowBlockPlace`
 *     - `int maxBlockPathCost`
 *     - `boolean avoidHazards`
 * - Should expose methods like:
 *     - `boolean isOperationAllowed(Operation op)`
 *     - `boolean shouldAvoid(BlockNavTag tag)`
 */
package com.orebit.mod.settings;
