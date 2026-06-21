/**
 * PathBudgetManager.java
 *
 * Main Component: pathfinding/
 * Environment: MAIN
 *
 * This class enforces pathfinding constraints to ensure that route computation
 * remains within acceptable performance bounds. It governs how much planning
 * work can be done per bot, per tick, or globally, and it can reject or abort
 * planning tasks that would exceed configured budgets or thresholds.
 *
 * The `PathBudgetManager` plays a key role in scaling the system to support
 * multiple bots by preventing runaway planning operations. It is especially
 * relevant when bots are allowed to perform block-breaking, building, or other
 * costly operations that require deeper path analysis.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines soft and hard limits on pathfinding scope and frequency
 * - Controls when bots are allowed to plan or expand nodes
 * - Optionally provides priority or scheduling for planning requests
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `PathFinder`, this does not compute routes—it limits them
 * - Unlike `PathPlan`, this does not represent or store paths—it constrains their creation
 * - Unlike `TaskExecutor`, this does not coordinate bot behavior—it regulates planning effort
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Planning is expensive and may need to be throttled
 * - Bots may share or compete for compute resources
 * - Limits can be defined globally or per bot, depending on config
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotId` or similar identifier for per-bot budgeting
 * - `Config` / `Settings` for tuning budget parameters
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathfinder`: Queries this during search to decide when to stop
 * - `PathReplanner`: Checks before kicking off a new planning session
 * - `Debug tools`: May visualize planning limits or failed attempts
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Tracks per-tick or per-region expansion quotas
 * - May expose:
 *     - `boolean canStartPlanning(UUID botId)`
 *     - `boolean shouldAbort(UUID botId, float costSoFar)`
 *     - `void reportNodeExpanded(UUID botId)`
 * - Can be no-op initially and evolve into a full resource manager later
 */
package com.orebit.mod.pathfinding;
