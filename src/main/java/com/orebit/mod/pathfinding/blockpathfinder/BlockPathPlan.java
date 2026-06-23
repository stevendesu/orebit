package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * A computed block-level path within the loaded nav grid: an ordered list of <b>stand positions</b>
 * (the block a bot's feet occupy at each step), in travel order, plus the search's total cost.
 *
 * <p><b>First-pass shape (PRD §7.1).</b> The ratified design has a {@code BlockPathPlan} as a
 * sequence of {@code BlockPathOperation}s (Traverse/Ascend/Fall/… each carrying its own cost,
 * validity, and folded interactions). This demo plan is the reduced form — bare waypoints — that
 * the first nav-grid consumer needs to move the bot around obstacles. It is deliberately the same
 * <i>role</i> (an executable, region-local path produced by {@link BlockPathfinder} and consumed by
 * a follower) so it can grow into the full operation list without callers changing shape.
 */
public final class BlockPathPlan {

    private final List<BlockPos> waypoints; // feet/stand positions, start-exclusive, in travel order
    private final float cost;

    public BlockPathPlan(List<BlockPos> waypoints, float cost) {
        this.waypoints = waypoints;
        this.cost = cost;
    }

    /** The stand position (feet block) of step {@code i}. */
    public BlockPos waypoint(int i) {
        return waypoints.get(i);
    }

    /** Number of waypoints in the path. */
    public int size() {
        return waypoints.size();
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    /** Total search cost (sum of per-step traversal costs). */
    public float cost() {
        return cost;
    }
}
