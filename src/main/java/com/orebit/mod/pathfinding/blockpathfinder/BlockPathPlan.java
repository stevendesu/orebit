package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * A computed block-level path within the loaded nav grid: an ordered list of <b>stand positions</b>
 * (the block a bot's feet occupy at each step), in travel order, each tagged with the {@link Movement}
 * the search chose to reach it, plus the search's total cost.
 *
 * <p><b>Shape (PRD §7.1).</b> The ratified design has a {@code BlockPathPlan} as a sequence of typed
 * steps (Traverse/Ascend/Fall/… each carrying its own cost, validity, and folded interactions). This
 * is the first cut of that: a waypoint plus its movement per step. The follower reads {@link
 * #movement(int)} to execute each step correctly — jump for an {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend}, plain walk for a {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse} step-assist, etc. — instead of guessing
 * from the waypoint delta. The role is unchanged (an executable, region-local path produced by {@link
 * BlockPathfinder} and consumed by a follower), so it can keep growing toward the full operation list
 * without callers changing shape.
 */
public final class BlockPathPlan {

    private final List<BlockPos> waypoints; // feet/stand positions, start-exclusive, in travel order
    private final List<Movement> moves;     // moves.get(i) = the movement used to reach waypoints.get(i)
    private final List<StepEdits> edits;    // edits.get(i) = break/place folded into step i (null if none)
    private final float cost;

    public BlockPathPlan(List<BlockPos> waypoints, List<Movement> moves, List<StepEdits> edits, float cost) {
        this.waypoints = waypoints;
        this.moves = moves;
        this.edits = edits;
        this.cost = cost;
    }

    /** The stand position (feet block) of step {@code i}. */
    public BlockPos waypoint(int i) {
        return waypoints.get(i);
    }

    /** The movement used to reach step {@code i} (so the follower knows whether to jump, fall, …). */
    public Movement movement(int i) {
        return moves.get(i);
    }

    /**
     * The break/place edits the follower must apply to make step {@code i} traversable, or {@code null}
     * when the step is an ordinary move. The follower mines the {@link StepEdits#breakPos break cells}
     * and places the {@link StepEdits#placePos place cells} (re-validated against the live world) before
     * walking into the step.
     */
    public StepEdits edits(int i) {
        return edits.get(i);
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
