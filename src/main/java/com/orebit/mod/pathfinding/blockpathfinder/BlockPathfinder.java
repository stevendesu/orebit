package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.TraversalClass;

import net.minecraft.core.BlockPos;

/**
 * First-pass block-level A* over the recomputed nav grid (PRD §7.1, block tier). Given a start and
 * goal <b>floor cell</b> (the block a bot stands on), it searches for a walkable path and returns a
 * {@link BlockPathPlan} of stand positions, or {@code null} if none is found within the loaded nav
 * grid / expansion budget.
 *
 * <h2>What this first pass models (and what it defers)</h2>
 * The search is 4-connected horizontally with a per-step vertical tolerance of <b>step-up 1</b> /
 * <b>fall {@value #MAX_FALL}</b>, and treats <b>only {@link TraversalClass#CLEAR}</b> as walkable.
 * That is the one 2-bit class that is unambiguously "solid floor with headroom": in the coarse grid
 * {@code SLOW} conflates slippery floors, hazards-in-face, and open falls, and {@code EASY} means
 * "no floor but bridgeable" — both need the per-descriptor fine layer (and block-place/break/swim
 * movements) to use safely, which is a later increment. Diagonals, the tick-based cost model, and
 * the full {@code BlockPathOperation}/{@code Movement} vocabulary also land then; here a step costs
 * {@value #STEP_COST} plus a small vertical penalty.
 *
 * <h2>Cells, not feet</h2>
 * The search space is floor cells, matching the nav grid's convention (a {@code CLEAR} cell is the
 * block you stand <i>on</i>). The returned waypoints are stand positions — {@code floorCell.above()}
 * — so a follower can steer the bot's feet straight to them.
 *
 * <p>Stateless and allocation-bounded per call; safe to run on the server tick thread for the short
 * ranges this demo uses.
 */
public final class BlockPathfinder {

    private BlockPathfinder() {}

    /** Max blocks a step may rise (a single jump). */
    private static final int MAX_STEP_UP = 1;
    /** Max blocks a step may drop (a safe fall for the demo; the cost model refines this later). */
    private static final int MAX_FALL = 3;
    /** Node-expansion ceiling — bounds per-call cost so a long/blocked goal can't stall the tick. */
    private static final int MAX_EXPANSIONS = 4000;

    /** Flat-step base cost. */
    private static final float STEP_COST = 1.0f;
    /** Extra cost per block climbed. */
    private static final float UP_PENALTY = 1.0f;
    /** Extra cost per block dropped. */
    private static final float FALL_PENALTY = 0.5f;

    /** Sentinel for "no standable floor in this column near the current height." */
    private static final int NO_FLOOR = Integer.MIN_VALUE;

    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class Node {
        final long key;
        final int x, y, z;
        final float g;
        final float f;

        Node(long key, int x, int y, int z, float g, float f) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.f = f;
        }
    }

    /**
     * Search for a walkable path from {@code startFloor} to {@code goalFloor} (both floor cells).
     * Returns {@code null} if the bot isn't standing on built ground, or no path is found within the
     * loaded grid / expansion budget. The goal is reached when within 1 block horizontally and 2
     * vertically of {@code goalFloor} (so the owner needn't be standing on a perfectly {@code CLEAR}
     * cell for the bot to arrive next to them).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor) {
        final int sx = startFloor.getX(), sy = startFloor.getY(), sz = startFloor.getZ();
        final int gx = goalFloor.getX(), gy = goalFloor.getY(), gz = goalFloor.getZ();

        // Bot must be on built ground for the grid-based search to mean anything.
        if (grid.classAt(sx, sy, sz) == null) return null;

        final long startKey = BlockPos.asLong(sx, sy, sz);

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        Map<Long, Float> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();

        gScore.put(startKey, 0f);
        open.add(new Node(startKey, sx, sy, sz, 0f, heuristic(sx, sy, sz, gx, gy, gz)));

        int expansions = 0;
        long reachedKey = -1L;

        while (!open.isEmpty()) {
            Node current = open.poll();

            // Stale queue entry (a better g was found after this node was pushed).
            Float best = gScore.get(current.key);
            if (best == null || current.g > best) continue;

            if (isGoal(current.x, current.y, current.z, gx, gy, gz)) {
                reachedKey = current.key;
                break;
            }

            if (++expansions > MAX_EXPANSIONS) return null;

            for (int[] dir : HORIZONTAL) {
                int nx = current.x + dir[0];
                int nz = current.z + dir[1];
                int ny = standableFloor(grid, nx, nz, current.y);
                if (ny == NO_FLOOR) continue;

                int dy = ny - current.y;
                float stepCost = STEP_COST + (dy > 0 ? dy * UP_PENALTY : -dy * FALL_PENALTY);
                float tentative = current.g + stepCost;

                long nKey = BlockPos.asLong(nx, ny, nz);
                Float known = gScore.get(nKey);
                if (known != null && tentative >= known) continue;

                gScore.put(nKey, tentative);
                cameFrom.put(nKey, current.key);
                float fScore = tentative + heuristic(nx, ny, nz, gx, gy, gz);
                open.add(new Node(nKey, nx, ny, nz, tentative, fScore));
            }
        }

        if (reachedKey == -1L) return null;

        return reconstruct(cameFrom, gScore, startKey, reachedKey);
    }

    /**
     * The highest standable floor cell in column {@code (x,z)} reachable from height {@code fromY}:
     * scan from one above (a step-up) down through the fall window, taking the first {@link
     * TraversalClass#CLEAR} cell. Returns {@link #NO_FLOOR} if the column's nav data is unknown
     * ({@code null} — an unloaded chunk) or has no walkable floor in range (a wall, or a drop too
     * deep). Scanning top-down means a step-up is preferred over a deeper landing in the same column.
     */
    private static int standableFloor(NavGridView grid, int x, int z, int fromY) {
        for (int y = fromY + MAX_STEP_UP; y >= fromY - MAX_FALL; y--) {
            TraversalClass c = grid.classAt(x, y, z);
            if (c == null) return NO_FLOOR;            // outside built nav data — don't path into it
            if (c == TraversalClass.CLEAR) return y;   // solid floor + headroom
        }
        return NO_FLOOR;
    }

    private static boolean isGoal(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) <= 1 && Math.abs(z - gz) <= 1 && Math.abs(y - gy) <= 2;
    }

    /** Admissible: Manhattan horizontal + vertical, each step covering ≥1 block at ≥{@link #STEP_COST}. */
    private static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return (Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy)) * STEP_COST;
    }

    private static BlockPathPlan reconstruct(Map<Long, Long> cameFrom, Map<Long, Float> gScore,
                                             long startKey, long reachedKey) {
        List<BlockPos> waypoints = new ArrayList<>();
        long k = reachedKey;
        while (k != startKey) {
            // Stand position = the floor cell's top (feet block) — steer the bot's feet here.
            waypoints.add(BlockPos.of(k).above());
            Long prev = cameFrom.get(k);
            if (prev == null) break; // defensive; should not happen for a reached goal
            k = prev;
        }
        Collections.reverse(waypoints);
        float cost = gScore.getOrDefault(reachedKey, 0f);
        return new BlockPathPlan(waypoints, cost);
    }
}
