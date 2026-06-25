package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.orebit.mod.OrebitCommon;
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

    /**
     * When true, a failed search logs WHY (closest approach + what each movement offered from the
     * dead-end) — the diagnostic for "plan: none". Mirrors {@code AllyBotEntity.DEBUG_PATH}; flip both off
     * to silence. Only runs on failure (rare, and throttled by the caller's replan cadence).
     */
    public static boolean DEBUG = true;

    /**
     * When true, every search logs how long it took, how many nodes it expanded, and the derived ns/node
     * — the empirical data for sizing {@link #MAX_EXPANSIONS}. One INFO line per pathfind, so flip off once
     * you've collected data. Caveats when reading it: the first few are JIT-cold (ignore), break/place
     * paths cost more per node than plain walks (the PathEdits rebuild + edit checks), and a GC pause can
     * spike a single sample — look at the steady-state distribution, not one line.
     */
    public static boolean LOG_TIMING = true;

    /**
     * Node-expansion ceiling — bounds per-call cost so a long/blocked goal can't stall the tick. A
     * backstop, NOT the primary throttle: the per-axis heuristic below is what keeps a normal search far
     * under this. (Pathing measures instant; 10k leaves headroom for genuinely long routes.)
     */
    private static final int MAX_EXPANSIONS = 10000;

    // Heuristic = 3D octile distance to the goal, times an inflation WEIGHT. Octile is the true shortest
    // path on a grid that can move straight (cost 1), face-diagonal (√2, two axes at once) or corner-
    // diagonal (√3, three axes at once): spend the shortest axis on corner-diagonals, the next on
    // face-diagonals, the rest straight. All THREE axes are weighted EQUALLY — verticality carries no
    // special penalty: an Ascend/Descend is a step that happens to also change height (base cost 1.0, same
    // as a Traverse), and climbing a pre-existing staircase is no dearer than walking. The real cost of
    // going up/down in open air is the block place/break, which lives on the MOVE (counted in g) and is
    // therefore already paid for and naturally avoided — it does not belong in the heuristic. The WEIGHT
    // (>1) makes the search greedy toward the goal: it stops fanning across the equal-cost plateau and
    // beelines, trading guaranteed-optimal paths (fine for a follow-bot) for far fewer expanded nodes.
    // CAVEAT: a goal reachable ONLY by building straight up (a pillar in open air) is under-estimated here
    // (h sees distance, not the forced placements counted in g), so that one case can still over-explore.
    private static final float H_STRAIGHT = 1.0f;        // one axis
    private static final float H_FACE     = 1.41421356f; // two axes at once (√2)
    private static final float H_CORNER   = 1.7320508f;  // three axes at once (√3)
    private static final float H_WEIGHT   = 2.0f;        // greediness; 1.0 = admissible, higher = faster/greedier

    /**
     * One reusable {@link Nodes} search-state per thread, {@link Nodes#reset() reset} at the top of each
     * {@link #findPath} instead of allocated fresh. A short follow search is dominated by the dozen-plus
     * arrays a fresh {@code Nodes} allocates (and re-grows) amortized over only a few hundred nodes;
     * reusing one drives the steady state to zero per-search allocation. {@link ThreadLocal}, not a
     * static singleton, so a future off-tick background pathfinding thread gets its own with no contention.
     */
    private static final ThreadLocal<Nodes> SEARCH = ThreadLocal.withInitial(() -> new Nodes(512, 1024));

    /**
     * The entire mutable search state as primitive arrays — the allocation-free replacement for the old
     * {@code new Node(...)}-per-candidate + four {@code HashMap<Long,…>} design (whose every {@code get}/
     * {@code put} boxed the packed-pos {@code long} key past the {@code Long} cache, ~100× per node).
     *
     * <p>Three structures, indices stable across the search so {@code parent} can reference them:
     * <ul>
     *   <li><b>Node table</b> — append-only parallel arrays ({@code key/x/y/z/g/f/parent/move/edits}),
     *       one row per discovered cell. {@code move} is the index into {@link MovementRegistry#TIER1};
     *       {@code parent} is the predecessor row ({@code -1} at the start). Doubles on fill.
     *   <li><b>Index</b> — open-addressing {@code long→row} map (linear probe, power-of-two). Empty slot is
     *       marked by {@code idx == -1}, so no key sentinel is needed; the {@code long} key is never boxed.
     *   <li><b>Open set</b> — a binary min-heap of row indices keyed by the {@code f} snapshot at push time
     *       ({@code heapF}). Lazy decrease-key: a relaxed-lower node is pushed again, and a pop whose
     *       snapshot exceeds the row's current {@code f} is stale and skipped (the array-form of the old
     *       {@code current.g > gScore.get(key)} check; equivalent because {@code f = g + h} with {@code h}
     *       fixed per cell). All per-call sizing; reused across nodes, not reallocated per node.
     * </ul>
     */
    private static final class Nodes {
        // ---- Node table (append-only; row index is stable, parent[] points at it) ----
        long[] key;
        int[] x, y, z;
        float[] g, f;
        int[] parent;          // predecessor row, -1 at the start
        int[] move;            // MovementRegistry.TIER1 index, -1 at the start
        StepEdits[] edits;     // edit-set on the edge into this row (null when the move breaks/places nothing)
        int count;

        // ---- key→row index (open addressing, linear probe) ----
        long[] mapKey;
        int[] mapRow;          // -1 marks an empty slot
        int mapMask;
        int mapSize;
        int mapGrowAt;

        // ---- open set (binary min-heap, ordered by f then by larger g) ----
        // Ties in f are broken toward the LARGER g (the node nearer the goal, i.e. smaller h). On the
        // big equal-f plateaus a follow-bot hits on open ground, this dives at the goal instead of
        // fanning out, popping far fewer nodes — and it's optimality-neutral (it only orders ties).
        int[] heap;
        float[] heapF;
        float[] heapG;         // g snapshot at push, parallel to heapF — the tie-breaker
        int heapSize;
        float poppedF;         // f snapshot of the last pop() — the caller's staleness test

        Nodes(int nodeHint, int mapCap) {
            key = new long[nodeHint];
            x = new int[nodeHint];
            y = new int[nodeHint];
            z = new int[nodeHint];
            g = new float[nodeHint];
            f = new float[nodeHint];
            parent = new int[nodeHint];
            move = new int[nodeHint];
            edits = new StepEdits[nodeHint];
            mapKey = new long[mapCap];
            mapRow = new int[mapCap];
            Arrays.fill(mapRow, -1);
            mapMask = mapCap - 1;
            mapGrowAt = mapCap * 3 / 4;
            heap = new int[nodeHint];
            heapF = new float[nodeHint];
            heapG = new float[nodeHint];
        }

        /**
         * Clear for the next pathfind, keeping every array at its grown high-water-mark capacity so a
         * reused instance stops allocating after warmup. Only the index needs wiping (its {@code -1}
         * empty marker); the node/heap arrays are written by index from 0 as the search fills them, and
         * stale slots past {@code count}/{@code heapSize}, or where {@code mapRow==-1}, are never read.
         */
        void reset() {
            count = 0;
            heapSize = 0;
            mapSize = 0;
            Arrays.fill(mapRow, -1);
        }

        /** Row for {@code key}, creating it (with {@code g=+inf}, unlinked) if absent. One probe. */
        int intern(long k, int cx, int cy, int cz) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) {
                    row = newRow(k, cx, cy, cz);
                    mapKey[slot] = k;
                    mapRow[slot] = row;
                    if (++mapSize >= mapGrowAt) growMap();
                    return row;
                }
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        private int newRow(long k, int cx, int cy, int cz) {
            int n = count;
            if (n == key.length) growNodes();
            key[n] = k;
            x[n] = cx; y[n] = cy; z[n] = cz;
            g[n] = Float.POSITIVE_INFINITY;
            f[n] = Float.POSITIVE_INFINITY;
            parent[n] = -1;
            move[n] = -1;
            edits[n] = null;
            count = n + 1;
            return n;
        }

        /** Push row {@code n} onto the open set with its current {@code f}/{@code g} snapshots. */
        void push(int n) {
            if (heapSize == heap.length) {
                heap = Arrays.copyOf(heap, heapSize << 1);
                heapF = Arrays.copyOf(heapF, heapSize << 1);
                heapG = Arrays.copyOf(heapG, heapSize << 1);
            }
            int i = heapSize++;
            float fv = f[n], gv = g[n];
            while (i > 0) {
                int p = (i - 1) >> 1;
                // Stop once the parent should stay above the new node: smaller f, or equal f and
                // g no smaller (so equal-f ties bubble the larger-g node up).
                if (heapF[p] < fv || (heapF[p] == fv && heapG[p] >= gv)) break;
                heap[i] = heap[p]; heapF[i] = heapF[p]; heapG[i] = heapG[p];
                i = p;
            }
            heap[i] = n; heapF[i] = fv; heapG[i] = gv;
        }

        /** Remove and return the best row (min f, ties to larger g); sets {@link #poppedF} to its f snapshot. */
        int pop() {
            int top = heap[0];
            poppedF = heapF[0];
            int last = --heapSize;
            if (last > 0) {
                int n = heap[last];
                float fv = heapF[last], gv = heapG[last];
                int i = 0;
                for (;;) {
                    int l = (i << 1) + 1;
                    if (l >= last) break;
                    int r = l + 1;
                    // Pick the better child: smaller f, or equal f and larger g.
                    int c = (r < last && (heapF[r] < heapF[l]
                            || (heapF[r] == heapF[l] && heapG[r] > heapG[l]))) ? r : l;
                    // Stop once the sifted node is at least as good as that child.
                    if (fv < heapF[c] || (fv == heapF[c] && gv >= heapG[c])) break;
                    heap[i] = heap[c]; heapF[i] = heapF[c]; heapG[i] = heapG[c];
                    i = c;
                }
                heap[i] = n; heapF[i] = fv; heapG[i] = gv;
            }
            return top;
        }

        private void growNodes() {
            int cap = key.length << 1;
            key = Arrays.copyOf(key, cap);
            x = Arrays.copyOf(x, cap);
            y = Arrays.copyOf(y, cap);
            z = Arrays.copyOf(z, cap);
            g = Arrays.copyOf(g, cap);
            f = Arrays.copyOf(f, cap);
            parent = Arrays.copyOf(parent, cap);
            move = Arrays.copyOf(move, cap);
            edits = Arrays.copyOf(edits, cap);
        }

        private void growMap() {
            long[] oldKey = mapKey;
            int[] oldRow = mapRow;
            int cap = oldKey.length << 1;
            mapKey = new long[cap];
            mapRow = new int[cap];
            Arrays.fill(mapRow, -1);
            mapMask = cap - 1;
            mapGrowAt = cap * 3 / 4;
            for (int i = 0; i < oldRow.length; i++) {
                int row = oldRow[i];
                if (row == -1) continue;
                long k = oldKey[i];
                int slot = slotFor(k, mapMask);
                while (mapRow[slot] != -1) slot = (slot + 1) & mapMask;
                mapKey[slot] = k;
                mapRow[slot] = row;
            }
        }

        /** Murmur3 64-bit finalizer → slot; spreads BlockPos-packed longs (low bits = y, then z). */
        private static int slotFor(long k, int mask) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            k *= 0xc4ceb9fe1a85ec53L;
            k ^= k >>> 33;
            return (int) k & mask;
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
        final long t0 = System.nanoTime();
        final int sx = startFloor.getX(), sy = startFloor.getY(), sz = startFloor.getZ();
        final int gx = goalFloor.getX(), gy = goalFloor.getY(), gz = goalFloor.getZ();

        // Bot must be on built ground for the grid-based search to mean anything.
        if (!grid.built(sx, sy, sz)) {
            if (LOG_TIMING) logTiming(t0, 0, false, "no-start-ground", sx, sy, sz, gx, gy, gz);
            return null;
        }

        final MovementContext ctx = new MovementContext(grid, caps);
        final long startKey = BlockPos.asLong(sx, sy, sz);

        // Reuse this thread's search state (sized to its high-water mark), wiped to empty — so a steady
        // stream of replans allocates nothing here. First call on a thread pays the initial 512/1024 sizing.
        final Nodes nodes = SEARCH.get();
        nodes.reset();
        Relaxer relaxer = new Relaxer(nodes, gx, gy, gz);

        int startRow = nodes.intern(startKey, sx, sy, sz);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = heuristic(sx, sy, sz, gx, gy, gz);
        nodes.push(startRow);

        int expansions = 0;
        int reachedRow = -1;
        // Closest approach (min heuristic among closed nodes) + why the search stopped — the diagnostic
        // for a failed plan: where did it dead-end, and was it walled in or just out of budget?
        float bestH = Float.MAX_VALUE;
        int bestX = sx, bestY = sy, bestZ = sz;
        boolean budgetHit = false;

        while (nodes.heapSize > 0) {
            int current = nodes.pop();

            // Stale queue entry (a better f was found and re-pushed after this entry).
            if (nodes.poppedF > nodes.f[current]) continue;

            int cx = nodes.x[current], cy = nodes.y[current], cz = nodes.z[current];
            if (isGoal(cx, cy, cz, gx, gy, gz)) {
                reachedRow = current;
                break;
            }

            float h = heuristic(cx, cy, cz, gx, gy, gz);
            if (h < bestH) { bestH = h; bestX = cx; bestY = cy; bestZ = cz; }

            if (++expansions > MAX_EXPANSIONS) { budgetHit = true; break; }

            // Rebuild the planned-edit diff for the path to THIS node, so the movements below read the
            // world as it will be when the bot stands here (the blocks the preceding moves place/break),
            // not just the live grid. Per-path, not global (see PathEdits); skipped wholesale until the
            // search has produced any edit, so plain follows pay nothing.
            PathEdits pathEdits = ctx.pathEdits();
            pathEdits.reset();
            if (relaxer.anyEdits) {
                for (int n = current; n != -1; n = nodes.parent[n]) {
                    pathEdits.add(nodes.edits[n]);
                }
            }

            relaxer.current = current;
            relaxer.currentG = nodes.g[current];
            List<Movement> tier1 = MovementRegistry.TIER1;
            for (int mi = 0, mn = tier1.size(); mi < mn; mi++) {
                relaxer.move = mi;
                tier1.get(mi).candidates(ctx, cx, cy, cz, relaxer);
            }
        }

        if (reachedRow == -1) {
            if (DEBUG) explainFailure(ctx, sx, sy, sz, gx, gy, gz, expansions, budgetHit, bestX, bestY, bestZ);
            if (LOG_TIMING) logTiming(t0, expansions, relaxer.anyEdits,
                    budgetHit ? "FAIL-budget" : "FAIL-exhausted", sx, sy, sz, gx, gy, gz);
            return null;
        }

        BlockPathPlan plan = reconstruct(nodes, startRow, reachedRow);
        if (LOG_TIMING) logTiming(t0, expansions, relaxer.anyEdits,
                "FOUND-" + plan.size() + "wp", sx, sy, sz, gx, gy, gz);
        return plan;
    }

    /**
     * One INFO line of search timing: nodes expanded, wall-clock spent, the derived ns/node, whether the
     * path carried any break/place edits ({@code +edits}, which cost more per node), the result, and the
     * endpoints. {@code System.nanoTime()} captures the whole search incl. the per-call map allocations.
     */
    private static void logTiming(long t0, int expansions, boolean edits, String result,
                                  int sx, int sy, int sz, int gx, int gy, int gz) {
        long ns = System.nanoTime() - t0;
        String perNode = expansions > 0 ? String.format("%.0f", (double) ns / expansions) : "-";
        OrebitCommon.LOGGER.info(
                "[Orebit] path TIMING: {} nodes in {} us ({} ns/node){} -> {}  ({},{},{})->({},{},{})",
                expansions, String.format("%.1f", ns / 1000.0), perNode, edits ? " +edits" : "",
                result, sx, sy, sz, gx, gy, gz);
    }

    /**
     * The {@link CandidateSink} the search hands each movement: it relaxes every emitted destination
     * cell against the open set, tagging the edge with the movement currently expanding so the plan can
     * carry the chosen move per step.
     */
    private static final class Relaxer implements CandidateSink {
        private final Nodes nodes;
        private final int gx, gy, gz;

        int current;        // row being expanded
        float currentG;     // its g (read once per expansion, not per candidate)
        int move;           // MovementRegistry.TIER1 index currently emitting candidates
        boolean anyEdits;   // has any edge carried break/place edits? (gates the per-pop diff rebuild)

        Relaxer(Nodes nodes, int gx, int gy, int gz) {
            this.nodes = nodes;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
        }

        @Override
        public void accept(int nx, int ny, int nz, float cost, StepEdits edits) {
            float tentative = currentG + cost;
            long nKey = BlockPos.asLong(nx, ny, nz);
            int row = nodes.intern(nKey, nx, ny, nz);
            if (tentative >= nodes.g[row]) return; // new rows start at +inf, so this also admits first visits

            nodes.g[row] = tentative;
            nodes.f[row] = tentative + heuristic(nx, ny, nz, gx, gy, gz);
            nodes.parent[row] = current;
            nodes.move[row] = move;
            // Keep the edit-set attached to the same (cheapest) edge as the move; clear any stale set
            // left by a costlier edge so the follower never mines/places blocks the winning move didn't.
            if (edits != null) { nodes.edits[row] = edits; anyEdits = true; }
            else nodes.edits[row] = null;
            nodes.push(row);
        }
    }

    private static boolean isGoal(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) <= 1 && Math.abs(z - gz) <= 1 && Math.abs(y - gy) <= 2;
    }

    /**
     * 3D octile distance to the goal, inflated by {@link #H_WEIGHT}. Sort the three axis gaps; spend the
     * smallest on corner-diagonal moves ({@link #H_CORNER}, all three axes at once), the next on
     * face-diagonals ({@link #H_FACE}), and the largest remainder on straight steps ({@link #H_STRAIGHT}).
     * All axes are weighted equally — see the constants above for why verticality carries no extra penalty.
     * The weight makes A* greedy toward the goal (trading optimality for far fewer nodes).
     */
    private static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int a = Math.abs(x - gx), b = Math.abs(y - gy), c = Math.abs(z - gz);
        // sort a <= b <= c (three compares)
        if (a > b) { int t = a; a = b; b = t; }
        if (b > c) { int t = b; b = c; c = t; }
        if (a > b) { int t = a; a = b; b = t; }
        float octile = a * H_CORNER + (b - a) * H_FACE + (c - b) * H_STRAIGHT;
        return H_WEIGHT * octile;
    }

    /**
     * Log <i>why</i> a search failed: how far it got, the closest cell it reached (where it dead-ended),
     * and what every movement offers from that cell. Turns a bare "plan: none" into a concrete missing
     * capability — e.g. "closest = the cliff base, and from there every movement emitted nothing" points
     * straight at the absent place-to-ascend (staircase) move. Re-running the movements from one cell is
     * cheap and only happens on failure.
     */
    private static void explainFailure(MovementContext ctx, int sx, int sy, int sz, int gx, int gy, int gz,
                                       int expansions, boolean budgetHit, int bx, int by, int bz) {
        int remaining = Math.abs(bx - gx) + Math.abs(by - gy) + Math.abs(bz - gz);
        OrebitCommon.LOGGER.info(
                "[Orebit] path FAIL start=({},{},{}) goal=({},{},{}) — {} after {} expansions; "
                        + "closest=({},{},{}), still {} blocks away. Moves from the dead-end:",
                sx, sy, sz, gx, gy, gz,
                budgetHit ? "hit expansion budget" : "search exhausted (nowhere left to go)",
                expansions, bx, by, bz, remaining);
        for (Movement m : MovementRegistry.TIER1) {
            StringBuilder sb = new StringBuilder();
            int[] count = {0};
            m.candidates(ctx, bx, by, bz, (cx, cy, cz, cost, edits) -> {
                count[0]++;
                sb.append(String.format(" (%d,%d,%d)c=%.1f%s", cx, cy, cz, cost, edits == null ? "" : "+edit"));
            });
            OrebitCommon.LOGGER.info("[Orebit]   {} -> {}", m.getClass().getSimpleName(),
                    count[0] == 0 ? "(nothing)" : sb.toString());
        }
    }

    private static BlockPathPlan reconstruct(Nodes nodes, int startRow, int reachedRow) {
        List<Movement> tier1 = MovementRegistry.TIER1;
        List<BlockPos> waypoints = new ArrayList<>();
        List<Movement> moves = new ArrayList<>();
        List<StepEdits> edits = new ArrayList<>();
        for (int n = reachedRow; n != startRow; n = nodes.parent[n]) {
            // Stand position = the floor cell's top (feet block) — steer the bot's feet here.
            waypoints.add(BlockPos.of(nodes.key[n]).above());
            moves.add(tier1.get(nodes.move[n]));
            edits.add(nodes.edits[n]); // null where the step breaks/places nothing
            if (nodes.parent[n] == -1) break; // defensive; should not happen for a reached goal
        }
        Collections.reverse(waypoints);
        Collections.reverse(moves);
        Collections.reverse(edits);
        return new BlockPathPlan(waypoints, moves, edits, nodes.g[reachedRow]);
    }
}
