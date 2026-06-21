/**
 * PathCache.java
 *
 * Main Component: pathfinding/
 * Environment: MAIN
 *
 * This class provides a short-lived memory for previously computed path segments,
 * allowing the system to avoid redundant work when bots attempt to navigate between
 * the same or similar positions. It stores mappings between (start → goal) pairs
 * and their corresponding `BlockPathPlan`, typically scoped to individual regions.
 *
 * The cache is useful for bots traveling similar routes, repeating tasks (e.g.,
 * return to chest, fetch item, return again), or executing reactive behaviors
 * that cause them to briefly abandon and resume a path.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores and retrieves previously computed `BlockPathPlan`s
 * - Allows reuse of valid plans across bots or tasks
 * - Can reduce planning load in clustered or repetitive movement scenarios
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `PathPlan`, this does not execute or compose paths—it only stores them
 * - Unlike `PathBudgetManager`, this does not enforce constraints—it bypasses them
 * - Unlike `PathReplanner`, this does not build new paths—it returns cached ones
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Paths are only valid if world conditions have not changed
 * - Cache keys must include enough context to detect mismatches
 * - Cached entries may be short-lived or scoped to region/tick/session
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockPathPlan`: The actual object being cached
 * - `Region` or `ChunkPos`: May be used to scope cache entries
 * - `PathPlanKey`: A composite key (e.g., start + goal + settings hash)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathfinder`: May query this before computing a new plan
 * - `PathReplanner`: May fallback to cached results for known transitions
 * - `Debug tools`: May visualize cache hit/miss stats
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Stores plans keyed by:
 *     - Start and goal position
 *     - Region or chunk scope
 *     - Optional config hash or bot ID
 * - May use:
 *     - LRU eviction
 *     - Time-based expiration
 *     - Region-scoped invalidation
 * - Cache should be invalidated on significant world changes (e.g. explosion, door opened)
 */
package com.orebit.mod.pathfinding;
