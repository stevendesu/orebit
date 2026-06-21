/**
 * WorldModel.java
 *
 * Main Component: worldmodel/
 * Environment: MAIN
 *
 * This interface defines the top-level abstraction over all world-relevant data
 * used by the AI systems. It exposes unified access to both the low-level
 * blockmap (for tactical, physical movement) and the high-level region graph
 * (for strategic planning and semantic reasoning). The world model enables bots
 * to perceive, plan, and simulate their environment in a way that is efficient,
 * scalable, and context-aware.
 *
 * It may be implemented as a read-only snapshot of the live world (for planning),
 * or as a persistent evolving model updated in real-time with game state and
 * bot exploration (for memory and awareness).
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Provides a unified API for querying world state at both block and region scale
 * - Serves as the foundation for pathfinding, memory, planning, and navigation
 * - Delegates to `BlockMap`, `RegionTree`, and semantic overlays like portals
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BlockMap`, this covers the *entire* AI-relevant world
 * - Unlike `Region`, this is not a single location—it’s the full scope
 * - Unlike `Memory`, this is objective and external—not bot-specific
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All queries are spatially scoped (e.g., relative to a bot's known surroundings)
 * - Subcomponents may be loaded lazily or generated on demand
 * - May include both real-world and simulated overlays (e.g., temporary plans)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockMap`, `SubChunk`, `BlockTagMap`: For physical terrain access
 * - `Region`, `RegionTree`, `Portal`: For semantic region graph access
 * - (Optional) `RegionMemory` or `ExplorationOverlay` for cognitive state
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathfinder`, `RegionPathfinder`: Use this to perform navigation
 * - `TaskPlanner`, `RequirementSolver`: Query world conditions and affordances
 * - `Memory`, `BehaviorProfile`: Evaluate conditions like danger or accessibility
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose methods like:
 *     - `Region getRegionAt(BlockPos pos)`
 *     - `BlockMap getBlockMapFor(Region region)`
 *     - `List<Portal> getPortalsFrom(Region)`
 *     - `boolean isTraversable(BlockPos)`
 *     - `RegionTree getRegionTree()`
 *     - `BlockTagMap getTagMapFor(BlockMap)`
 * - Implementations may support:
 *     - Snapshot vs. live mode
 *     - AI-perceived vs. real-world divergence
 */
package com.orebit.mod.worldmodel;
