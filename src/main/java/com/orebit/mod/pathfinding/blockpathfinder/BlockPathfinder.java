package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;

/**
 * Block-level A* over the nav grid (PRD §7.1, block tier). Given a start and goal <b>floor cell</b>
 * (the block a bot stands on), it searches for a walkable path and returns a {@link BlockPathPlan} of
 * stand positions — each tagged with the {@link Movement} that produced it — or {@code null} if none is
 * found within the loaded nav grid / expansion budget.
 *
 * <h2>Movement-driven expansion</h2>
 * A node is expanded by iterating {@link MovementRegistry#TIER1}: each {@link Movement} reads the live
 * geometry of the cells it touches (via {@link MovementContext}) and emits its own valid destination
 * cells with a tick cost. The coarse 2-bit grid is used only as the cheap "is this cell built/loaded"
 * gate ({@link MovementContext#built}); the precise per-move checks (head clearance for a jump, the
 * drop column for a fall, the step-assist threshold for a slab) read the descriptor. So the grid finds
 * candidates and live geometry decides moves — which is what catches the classifier's approximations
 * (the "head-in-block" class) precisely at the move level. Adding a capability is adding a movement to
 * the registry; this search loop doesn't change.
 *
 * <h2>Cells, not feet</h2>
 * The search space is floor cells, matching the nav grid's convention (you stand <i>on</i> a floor
 * cell). The returned waypoints are stand positions — {@code floorCell.above()} — so a follower can
 * steer the bot's feet straight to them and ask the step's {@link Movement} how to execute it.
 *
 * <p>Stateless and allocation-bounded per call; safe to run on the server tick thread for the short
 * ranges this consumer uses.
 */
public final class BlockPathfinder {

    private BlockPathfinder() {}

    /** Node-expansion ceiling — bounds per-call cost so a long/blocked goal can't stall the tick. */
    private static final int MAX_EXPANSIONS = 4000;

    /** The search's minimum per-step cost; the heuristic uses it as the per-block lower bound. */
    private static final float MIN_STEP_COST = 1.0f;

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
     * Search a walkable path from {@code startFloor} to {@code goalFloor} (both floor cells) for the
     * default {@link BotCaps}. See {@link #findPath(NavGridView, BlockPos, BlockPos, BotCaps)}.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor) {
        return findPath(grid, startFloor, goalFloor, BotCaps.DEFAULT);
    }

    /**
     * Search for a walkable path from {@code startFloor} to {@code goalFloor} (both floor cells) given
     * the bot's {@code caps}. Returns {@code null} if the bot isn't standing on built ground, or no path
     * is found within the loaded grid / expansion budget. The goal is reached when within 1 block
     * horizontally and 2 vertically of {@code goalFloor} (so the owner needn't be standing on a perfectly
     * walkable cell for the bot to arrive next to them).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps) {
        final int sx = startFloor.getX(), sy = startFloor.getY(), sz = startFloor.getZ();
        final int gx = goalFloor.getX(), gy = goalFloor.getY(), gz = goalFloor.getZ();

        // Bot must be on built ground for the grid-based search to mean anything.
        if (grid.classAt(sx, sy, sz) == null) return null;

        final MovementContext ctx = new MovementContext(grid, caps);
        final long startKey = BlockPos.asLong(sx, sy, sz);

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        Map<Long, Float> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Movement> cameFromMove = new HashMap<>();

        Relaxer relaxer = new Relaxer(open, gScore, cameFrom, cameFromMove, gx, gy, gz);

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

            relaxer.current = current;
            for (Movement m : MovementRegistry.TIER1) {
                relaxer.move = m;
                m.candidates(ctx, current.x, current.y, current.z, relaxer);
            }
        }

        if (reachedKey == -1L) return null;

        return reconstruct(cameFrom, cameFromMove, gScore, startKey, reachedKey);
    }

    /**
     * The {@link CandidateSink} the search hands each movement: it relaxes every emitted destination
     * cell against the open set, tagging the edge with the movement currently expanding so the plan can
     * carry the chosen move per step.
     */
    private static final class Relaxer implements CandidateSink {
        private final PriorityQueue<Node> open;
        private final Map<Long, Float> gScore;
        private final Map<Long, Long> cameFrom;
        private final Map<Long, Movement> cameFromMove;
        private final int gx, gy, gz;

        Node current;   // node being expanded
        Movement move;  // movement currently emitting candidates

        Relaxer(PriorityQueue<Node> open, Map<Long, Float> gScore, Map<Long, Long> cameFrom,
                Map<Long, Movement> cameFromMove, int gx, int gy, int gz) {
            this.open = open;
            this.gScore = gScore;
            this.cameFrom = cameFrom;
            this.cameFromMove = cameFromMove;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
        }

        @Override
        public void accept(int nx, int ny, int nz, float cost) {
            float tentative = current.g + cost;
            long nKey = BlockPos.asLong(nx, ny, nz);
            Float known = gScore.get(nKey);
            if (known != null && tentative >= known) return;

            gScore.put(nKey, tentative);
            cameFrom.put(nKey, current.key);
            cameFromMove.put(nKey, move);
            float fScore = tentative + heuristic(nx, ny, nz, gx, gy, gz);
            open.add(new Node(nKey, nx, ny, nz, tentative, fScore));
        }
    }

    private static boolean isGoal(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) <= 1 && Math.abs(z - gz) <= 1 && Math.abs(y - gy) <= 2;
    }

    /** Admissible: Manhattan, each step covering ≥1 block at ≥{@link #MIN_STEP_COST}. */
    private static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return (Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy)) * MIN_STEP_COST;
    }

    private static BlockPathPlan reconstruct(Map<Long, Long> cameFrom, Map<Long, Movement> cameFromMove,
                                             Map<Long, Float> gScore, long startKey, long reachedKey) {
        List<BlockPos> waypoints = new ArrayList<>();
        List<Movement> moves = new ArrayList<>();
        long k = reachedKey;
        while (k != startKey) {
            // Stand position = the floor cell's top (feet block) — steer the bot's feet here.
            waypoints.add(BlockPos.of(k).above());
            moves.add(cameFromMove.get(k));
            Long prev = cameFrom.get(k);
            if (prev == null) break; // defensive; should not happen for a reached goal
            k = prev;
        }
        Collections.reverse(waypoints);
        Collections.reverse(moves);
        float cost = gScore.getOrDefault(reachedKey, 0f);
        return new BlockPathPlan(waypoints, moves, cost);
    }
}
