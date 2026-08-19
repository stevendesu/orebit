package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;
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
    private final int[] floorYs;            // floorYs[i] = the SEARCH-NATIVE floor-cell Y of step i (see floorY)
    private final float[] stepCosts;        // stepCosts[i] = search cost (ticks) of the edge INTO step i (see stepCost)
    private final float cost;

    public BlockPathPlan(List<BlockPos> waypoints, List<Movement> moves, List<StepEdits> edits, int[] floorYs,
                         float[] stepCosts, float cost) {
        this.waypoints = waypoints;
        this.moves = moves;
        this.edits = edits;
        this.floorYs = floorYs;
        this.stepCosts = stepCosts;
        this.cost = cost;
    }

    /**
     * Synthetic-plan convenience (tests / hand-built fixtures): derives each step's floor as {@code
     * waypoint.below()} — the full-block common case — and spreads {@code cost} uniformly across the steps
     * as the per-step costs. Production plans come from {@link BlockPathfinder}'s reconstruct, which passes
     * the search's exact per-step floor Ys and per-edge g-deltas through the primary constructor.
     */
    public BlockPathPlan(List<BlockPos> waypoints, List<Movement> moves, List<StepEdits> edits, float cost) {
        this(waypoints, moves, edits, deriveFullBlockFloors(waypoints), deriveUniformStepCosts(waypoints, cost),
                cost);
    }

    private static int[] deriveFullBlockFloors(List<BlockPos> waypoints) {
        int[] ys = new int[waypoints.size()];
        for (int i = 0; i < ys.length; i++) {
            ys[i] = waypoints.get(i).getY() - 1;
        }
        return ys;
    }

    private static float[] deriveUniformStepCosts(List<BlockPos> waypoints, float cost) {
        float[] costs = new float[waypoints.size()];
        if (costs.length > 0) {
            Arrays.fill(costs, cost / costs.length);
        }
        return costs;
    }

    /** The stand position (feet block) of step {@code i}. */
    public BlockPos waypoint(int i) {
        return waypoints.get(i);
    }

    /**
     * The Y of the <b>search-native floor cell</b> of step {@code i} — the A* node the search stood this step
     * on, carried through {@link BlockPathfinder}'s reconstruct instead of being re-derived from the feet
     * waypoint. The waypoint's X/Z equal the floor's X/Z (only Y differs — {@code floorY == waypoint(i).getY()}
     * for a bottom-partial floor, {@code waypoint(i).getY() - 1} otherwise), so {@code (wp.x, floorY(i), wp.z)}
     * is the exact floor cell every step's {@link Movement#plan} frame must anchor on. Carrying it matters
     * precisely for a <b>break-at-feet</b> step (the feet cell is solid until the step's folded break runs):
     * a follower-side inversion of the waypoint ({@code floorOf}) reads that still-solid feet cell as a
     * standable floor and drifts the whole execution frame +1, so the block to be mined becomes the FOOTING
     * and is never mined (PATHOLOGY P1B).
     */
    public int floorY(int i) {
        return floorYs[i];
    }

    /** The search-native floor cell of step {@code i} as a {@link BlockPos} — see {@link #floorY}. Allocates;
     *  cold call sites only (settle anchor, preplan seed, debug dumps). */
    public BlockPos floor(int i) {
        BlockPos wp = waypoints.get(i);
        int fy = floorYs[i];
        return fy == wp.getY() ? wp : new BlockPos(wp.getX(), fy, wp.getZ());
    }

    /**
     * The search cost, in ticks, of the move <b>arriving at</b> step {@code i} — the A* edge from step
     * {@code i - 1} (or from the search start for {@code i == 0}), carried through {@link BlockPathfinder}'s
     * reconstruct as the per-edge g-delta (DESIGN-replan-handoff.md §3). A macro edge re-expanded into
     * {@code j} waypoints carries its one edge cost divided uniformly across them. Feeds the seam-selection
     * horizon walk (which prefix-sums these against a search-budget tick count); the sum over all steps
     * equals {@link #cost()} up to float rounding across macro divisions.
     */
    public float stepCost(int i) {
        return stepCosts[i];
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
