/**
 * RegionBuilder.java
 *
 * Main Component: worldmodel/regionmodel/
 * Environment: MAIN
 *
 * This class is responsible for constructing `Region` instances from raw
 * block-level data. It performs the geometric and semantic analysis needed
 * to identify contiguous navigable areas, detect portals (transitions), and
 * assign meaningful boundaries to regions within the world model.
 *
 * The builder may be invoked during world load, exploration, or simulation.
 * It is the source of truth for generating the region graph used by
 * region-level pathfinding and AI reasoning. It supports initial creation,
 * updates, re-partitioning, and merging/splitting regions when world state
 * changes significantly.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Scans blockmaps to identify discrete regions
 * - Detects and constructs `Portal` instances between regions
 * - Outputs region metadata, hierarchy, and spatial bounds
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Region`, this is not a data model—it is an active constructor
 * - Unlike `RegionTree`, this is not a structure—it generates region nodes
 * - Unlike `BlockMap`, this analyzes rather than stores block data
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Region boundaries are formed by navigability constraints (e.g., walls, drops, gaps)
 * - Portals exist where traversal between regions is physically possible
 * - Input data is from a `BlockMap`, possibly via `SubChunk`s
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockMap` and `BlockTagMap`: Used to scan navigability and features
 * - `Region`, `Portal`, `PortalShape`: Constructed and returned as output
 * - `RegionTree`: May be updated or extended during build
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `WorldModel`: May invoke this to (re)build parts of the region graph
 * - `RegionPathfinder`: Relies on builder output for graph correctness
 * - `Debug tools`: May visualize boundaries and portal detection
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Likely uses a flood-fill or navigation-surface-aware scan
 * - May define:
 *     - `List<Region> buildFrom(BlockMap map)`
 *     - `Region updateRegion(...)`
 *     - `boolean isPortalCandidate(BlockPos pos)`
 * - May optionally:
 *     - Group regions into hierarchical clusters
 *     - Assign tags based on contents (e.g., "dangerous", "resource-rich")
 */
package com.orebit.mod.worldmodel.region;
