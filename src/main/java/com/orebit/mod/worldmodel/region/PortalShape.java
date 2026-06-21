/**
 * PortalShape.java
 *
 * Main Component: worldmodel/regionmodel/
 * Environment: MAIN
 *
 * This class defines the physical geometry of a portal space connecting two
 * regions in the world model. While a `Portal` defines a logical adjacency
 * between regions, the `PortalShape` specifies which blocks constitute the
 * transitional zone and how agents can detect, enter, or path toward it.
 *
 * The shape is typically represented as a cuboid, voxel list, or bounding box,
 * and may describe areas such as doorways, tunnels, stairwells, Nether portals,
 * or vertical shafts. `PortalShape` allows both AI and pathfinding systems to
 * reason about reachable goal zones at the block level when approaching a region
 * transition.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Describes the physical space of a portal as a shape in the world
 * - Supports goal-matching for block-level navigation
 * - May define traversal constraints (e.g., minimum clearance or slope)
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Portal`, this has no semantic or graph information—only geometry
 * - Unlike `Region`, this is not a container—only a boundary or entry zone
 * - Unlike `BlockMap`, this is sparse and high-level, not exhaustive
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Portal shapes are localized and small enough for efficient queries
 * - Each shape defines a valid transition between two regions
 * - A shape may contain multiple valid goal positions
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BlockPos` or internal coordinate type
 * - (Optional) World context for validation or collision
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BlockPathfinder`: Uses this to target entry points
 * - `Portal`: Stores this to associate shape with region transition
 * - `RegionBuilder`: Detects shapes based on geometry patterns
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May define:
 *     - `Set<BlockPos> getAllBlocks()`
 *     - `boolean contains(BlockPos)`
 *     - `BlockPos getNearestEntryTo(BlockPos)`
 *     - `BoundingBox getBounds()`
 * - May also expose:
 *     - `getEntryDirection()` (optional for alignment)
 *     - `isNavigableBy(EntityType)`
 */
package com.orebit.mod.worldmodel.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.Set;
import java.util.HashSet;

public class PortalShape {
    private final Set<BlockPos> entryBlocks; // All valid portal entry points
    private final AABB boundingBox;

    public PortalShape(Set<BlockPos> entryBlocks) {
        this.entryBlocks = new HashSet<>(entryBlocks);
        this.boundingBox = computeBoundingBox(entryBlocks);
    }

    private AABB computeBoundingBox(Set<BlockPos> positions) {
        if (positions.isEmpty()) return new AABB(0, 0, 0, 0, 0, 0);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1); // inclusive range
    }

    public Set<BlockPos> getAllBlocks() {
        return entryBlocks;
    }

    public boolean contains(BlockPos pos) {
        return entryBlocks.contains(pos);
    }

    public AABB getBounds() {
        return boundingBox;
    }

    public BlockPos getNearestEntryTo(BlockPos from) {
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (BlockPos entry : entryBlocks) {
            double distSq = from.distSqr(entry);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry;
            }
        }

        return nearest;
    }

//    public boolean isNavigableBy(EntityType<?> entityType) {
//        // Placeholder — can later check bounding clearance, water/lava, etc.
//        return true;
//    }
}

