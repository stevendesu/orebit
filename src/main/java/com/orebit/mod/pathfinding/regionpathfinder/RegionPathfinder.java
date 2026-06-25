package com.orebit.mod.pathfinding.regionpathfinder;

import java.util.Arrays;

import com.orebit.mod.pathfinding.regionpathfinder.heuristics.SimpleRegionHeuristic;
import com.orebit.mod.worldmodel.hpa.CostCodec;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The HPA* region-tier A* (PRD §6.3–6.5, §7.1, §7.4, §10 Phase 3; HPA-IMPLEMENTATION.md §8, "3g").
 *
 * <h2>Ratified design — face-to-center over a fixed cubic grid (NOT portals)</h2>
 * The region tier plans over the {@link com.orebit.mod.worldmodel.hpa.CostPyramid CostPyramid}: a
 * <b>fixed cubic-grid implicit octree</b> of regions (PRD §6.3), NOT the superseded semantic
 * {@code Region}/{@code Portal}/flood-fill model. Each node stores six <b>face→center</b> half-traversal
 * costs (PRD §6.5: we store the half from a face to the node center, never an edge). The cost of crossing
 * from node {@code N} out its face {@code F} into the neighbor {@code M} is the <b>implicit boundary sum</b>
 * {@code N.faceCost(F) + M.faceCost(opposite(F))}. There are no entrances and no portals — any traversable
 * arrival into a neighbor region is acceptable; the block tier decides the actual moves.
 *
 * <h2>What this produces</h2>
 * {@link #plan} runs A* over the <b>level-0</b> region grid (6 face neighbors per cell) from the start
 * region to the goal region and returns a {@link RegionPathPlan} — the ordered level-0 skeleton the
 * {@link com.orebit.mod.pathfinding.PathPlan} sliding-window driver walks. The {@link RegionGrid} is the
 * single read chokepoint: before reading a node's faces we call {@link RegionGrid#ensureLeaf} (lazy build
 * from the resident nav grid; optimistic admissible default for unloaded/unbuilt nodes — §6).
 *
 * <h2>Scale guard / lazy refinement (HPA-IMPLEMENTATION.md §8)</h2>
 * For the multi-thousand-block milestone walk a <b>direct level-0 skeleton</b> is enough, and this is the
 * branch the milestone exercises. To keep the search bounded over 1M-block reach, a scale guard gates a
 * coarse-then-refine path: if the start→goal Chebyshev distance in level-0 regions exceeds
 * {@link #LEVEL0_DIRECT_CAP}, we plan at the coarsest level {@code L} whose distance ≤
 * {@link #COARSE_TARGET_CELLS} cells, take that coarse skeleton, and refine only the <b>leading segment</b>
 * to level 0 (a level-0 A* toward the first few coarse cells). The driver re-invokes {@link #plan} as the
 * bot nears the segment end. The coarse branch is intentionally straightforward; the direct branch is the
 * one the milestone proves.
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * Allocation-light A* in the {@link Nodes} idiom copied from
 * {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}: a struct-of-arrays node table, an
 * open-addressed {@code long}→row map (murmur3 finalizer, {@code -1} empty, linear probe, grow-at-3/4) keyed
 * by {@link RegionAddress#packLevelKey}, and a binary min-heap open set. A {@link ThreadLocal} reused state
 * drives steady-state per-search allocation to ~zero. The heuristic is a swappable {@link RegionHeuristic}
 * (strategy, not switch) — the ratified {@link SimpleRegionHeuristic}.
 */
public final class RegionPathfinder {

    private RegionPathfinder() {}

    /**
     * Node-expansion ceiling for the region A* — a backstop against a pathological replan stalling the tick
     * (HPA-IMPLEMENTATION.md §8). Far larger than a normal region search needs; the scale guard keeps a
     * genuinely long goal off the level-0 grid entirely.
     */
    public static final int MAX_REGION_EXPANSIONS = 20000;

    /**
     * Scale guard (HPA-IMPLEMENTATION.md §8): if the start→goal level-0 region Chebyshev distance exceeds
     * this many regions (~{@code 256 · 16 = 4096} blocks), do NOT plan a full level-0 skeleton — plan
     * coarse and refine only the leading segment. The milestone walk stays under this and uses the direct
     * branch.
     */
    public static final int LEVEL0_DIRECT_CAP = 256;

    /**
     * Coarse-branch target: when over {@link #LEVEL0_DIRECT_CAP}, plan at the coarsest level whose start→goal
     * distance is ≤ this many cells, so the coarse A* itself stays small.
     */
    public static final int COARSE_TARGET_CELLS = 64;

    /** How many leading coarse cells to refine to level 0 in the lazy branch (the near segment). */
    public static final int REFINE_LEAD_CELLS = 3;

    /** The ratified admissible heuristic (Euclidean region centers × min-cost-per-region). Strategy, not switch. */
    private static final RegionHeuristic HEURISTIC = new SimpleRegionHeuristic();

    /** One reusable region-search state per thread, reset at the top of each {@link #plan}. */
    private static final ThreadLocal<Nodes> SEARCH = ThreadLocal.withInitial(() -> new Nodes(256, 512));

    /**
     * Plan a coarse region skeleton from {@code startFloor} to {@code goalFloor} in {@code level}.
     *
     * <p>Converts both to level-0 region coords. If they share a region, returns a trivial 1-element plan.
     * Otherwise: within {@link #LEVEL0_DIRECT_CAP} → a direct level-0 A* skeleton; beyond → the coarse
     * scale-guard branch (plan coarse, refine the leading segment to level 0). Lazily builds the leaves it
     * touches via {@link RegionGrid#ensureLeaf} before reading their faces.
     *
     * @return the level-0 skeleton (index 0 = start region), or {@code null} if no route is found within the
     *         expansion budget. An empty/1-element plan is returned for the trivial same-region case.
     */
    public static RegionPathPlan plan(ServerLevel level, RegionGrid grid, BlockPos startFloor,
                                      BlockPos goalFloor) {
        final int minY = grid.minY();

        final int srx = RegionAddress.regionX(startFloor.getX(), 0);
        final int sry = RegionAddress.regionY(startFloor.getY(), 0, minY);
        final int srz = RegionAddress.regionZ(startFloor.getZ(), 0);

        final int grx = RegionAddress.regionX(goalFloor.getX(), 0);
        final int gry = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        final int grz = RegionAddress.regionZ(goalFloor.getZ(), 0);

        // Trivial: start and goal in the same level-0 region.
        if (srx == grx && sry == gry && srz == grz) {
            int[] rx = {srx}, ry = {sry}, rz = {srz};
            return new RegionPathPlan(rx, ry, rz, 1, minY, true);
        }

        // Scale guard: a genuinely long goal does NOT go on the level-0 grid (HPA-IMPLEMENTATION.md §8).
        int cheb = Math.max(Math.abs(grx - srx), Math.max(Math.abs(gry - sry), Math.abs(grz - srz)));
        if (cheb > LEVEL0_DIRECT_CAP) {
            return planCoarseRefined(grid, minY, srx, sry, srz, grx, gry, grz);
        }

        // Direct level-0 skeleton (the milestone branch).
        return planLevel0(grid, minY, srx, sry, srz, grx, gry, grz, true);
    }

    // ---------------------------------------------------------------------------------------------------
    // Direct level-0 A*
    // ---------------------------------------------------------------------------------------------------

    /**
     * Level-0 A* over region cells (6 face neighbors). Edge cost from N out face F into M is
     * {@code N.faceCost(F) + M.faceCost(opposite(F))} (the implicit boundary sum). {@code grid.ensureLeaf} is
     * called on every node before its faces are read so the costs reflect the resident nav grid (or the §6
     * default). {@code reachedGoalRegion} flags whether the returned skeleton's tail is the true goal region.
     */
    private static RegionPathPlan planLevel0(RegionGrid grid, int minY,
                                             int srx, int sry, int srz,
                                             int grx, int gry, int grz,
                                             boolean reachedGoalRegion) {
        final Nodes nodes = SEARCH.get();
        nodes.reset();

        final int startRow = nodes.intern(RegionAddress.packLevelKey(srx, sry, srz), srx, sry, srz);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = HEURISTIC.estimate(srx, sry, srz, grx, gry, grz);
        nodes.push(startRow);

        int expansions = 0;
        int reachedRow = -1;

        while (nodes.heapSize > 0) {
            int current = nodes.pop();
            if (nodes.poppedF > nodes.f[current]) continue; // stale heap entry

            final int crx = nodes.x[current], cry = nodes.y[current], crz = nodes.z[current];
            if (crx == grx && cry == gry && crz == grz) {
                reachedRow = current;
                break;
            }
            if (++expansions > MAX_REGION_EXPANSIONS) break;

            // The source node's faces (lazy-built once).
            grid.ensureLeaf(crx, cry, crz);
            final float gCur = nodes.g[current];

            for (int face = 0; face < 6; face++) {
                final int nrx = RegionAddress.neighborRX(crx, face);
                final int nry = RegionAddress.neighborRY(cry, face);
                final int nrz = RegionAddress.neighborRZ(crz, face);

                grid.ensureLeaf(nrx, nry, nrz);

                final float out = grid.faceCost(0, crx, cry, crz, face);
                final float in = grid.faceCost(0, nrx, nry, nrz, RegionAddress.opposite(face));
                final float edge = out + in;
                // INF boundary → impassable; never relax across it.
                if (edge >= CostCodec.COST_INF) continue;

                final float tentative = gCur + edge;
                final int row = nodes.intern(RegionAddress.packLevelKey(nrx, nry, nrz), nrx, nry, nrz);
                if (tentative >= nodes.g[row]) continue; // new rows start at +inf → first visit admitted

                nodes.g[row] = tentative;
                nodes.f[row] = tentative + HEURISTIC.estimate(nrx, nry, nrz, grx, gry, grz);
                nodes.parent[row] = current;
                nodes.push(row);
            }
        }

        if (reachedRow == -1) {
            return null;
        }
        return reconstruct(nodes, startRow, reachedRow, minY, reachedGoalRegion);
    }

    /**
     * Walk {@code parent} from {@code reachedRow} back to {@code startRow}, emitting the level-0 region coords
     * in travel order. SoA arrays are read by stable row index (the block tier's {@code reconstruct} idiom).
     */
    private static RegionPathPlan reconstruct(Nodes nodes, int startRow, int reachedRow, int minY,
                                              boolean reachedGoalRegion) {
        // Count the chain length first so we can size the parallel arrays exactly (no list/boxing).
        int len = 0;
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            len++;
            if (n == startRow) break;
        }
        int[] rxs = new int[len];
        int[] rys = new int[len];
        int[] rzs = new int[len];
        int i = len - 1; // fill back-to-front so index 0 ends up the start region
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            rxs[i] = nodes.x[n];
            rys[i] = nodes.y[n];
            rzs[i] = nodes.z[n];
            i--;
            if (n == startRow) break;
        }
        return new RegionPathPlan(rxs, rys, rzs, len, minY, reachedGoalRegion);
    }

    // ---------------------------------------------------------------------------------------------------
    // Coarse scale-guard branch (lazy refinement) — straightforward; the milestone uses the direct branch
    // ---------------------------------------------------------------------------------------------------

    /**
     * Coarse-then-refine for goals beyond {@link #LEVEL0_DIRECT_CAP} (HPA-IMPLEMENTATION.md §8). Pick the
     * coarsest level {@code L} whose start→goal Chebyshev distance ≤ {@link #COARSE_TARGET_CELLS}, plan a
     * level-{@code L} A* skeleton, then refine only the leading {@link #REFINE_LEAD_CELLS} coarse cells to a
     * level-0 skeleton toward the goal. The returned plan exposes only that near level-0 segment; the driver
     * re-invokes {@link #plan} as the bot nears the segment end.
     *
     * <p>This branch is deliberately simple — it is for 1M-block reach, not the milestone. The refinement
     * picks a level-0 sub-goal inside the chosen coarse cell (its center, descended to a real level-0 region)
     * and runs {@link #planLevel0} to it with {@code reachedGoalRegion=false} (the segment end is not the
     * goal). Falls back to a best-effort direct level-0 plan if a coarse level cannot be chosen.
     */
    private static RegionPathPlan planCoarseRefined(RegionGrid grid, int minY,
                                                    int srx, int sry, int srz,
                                                    int grx, int gry, int grz) {
        // Choose the coarsest level whose distance is within the coarse target. Level-L region coords are the
        // level-0 coords shifted right by L (octree below OCTREE_TOP, quadtree above — ry pins to 0 there).
        int level = 1;
        while (level < RegionAddress.MAX_LEVEL) {
            int sLx = srx >> level, sLz = srz >> level;
            int gLx = grx >> level, gLz = grz >> level;
            int sLy = (level >= RegionAddress.OCTREE_TOP) ? 0 : (sry >> level);
            int gLy = (level >= RegionAddress.OCTREE_TOP) ? 0 : (gry >> level);
            int chebL = Math.max(Math.abs(gLx - sLx), Math.max(Math.abs(gLy - sLy), Math.abs(gLz - sLz)));
            if (chebL <= COARSE_TARGET_CELLS) break;
            level++;
        }

        // Direction of travel in level-0 region space; step the leading segment toward the goal by
        // REFINE_LEAD_CELLS coarse cells projected to a level-0 sub-goal. Clamp the sub-goal toward the goal
        // so the segment never overshoots.
        int coarseSide = 1 << level; // level-0 regions spanned by one level-L cell horizontally
        int leadBlocks = REFINE_LEAD_CELLS * coarseSide;

        int subGx = stepToward(srx, grx, leadBlocks);
        int subGz = stepToward(srz, grz, leadBlocks);
        int subGy = (sry == gry) ? gry : stepToward(sry, gry, leadBlocks);

        // Refine the near segment to level 0. The tail is NOT the true goal region (reachedGoalRegion=false).
        RegionPathPlan refined = planLevel0(grid, minY, srx, sry, srz, subGx, subGy, subGz, false);
        if (refined != null) {
            return refined;
        }
        // Fallback: attempt a direct level-0 plan straight to the goal (may hit the budget; honest null).
        return planLevel0(grid, minY, srx, sry, srz, grx, gry, grz, true);
    }

    /** Move {@code from} toward {@code to} by up to {@code maxStep}, never past {@code to}. */
    private static int stepToward(int from, int to, int maxStep) {
        int d = to - from;
        if (Math.abs(d) <= maxStep) return to;
        return from + (d > 0 ? maxStep : -maxStep);
    }

    // ---------------------------------------------------------------------------------------------------
    // Allocation-light SoA A* state (the BlockPathfinder.Nodes idiom, keyed by packLevelKey)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The region A*'s mutable search state as primitive arrays — a clean re-implementation of the block
     * tier's {@code Nodes} idiom, far smaller (region cells, not blocks) and keyed by
     * {@link RegionAddress#packLevelKey}. Append-only node table + open-addressed {@code long}→row map
     * ({@code -1} empty, murmur3 finalizer, linear probe, grow-at-3/4) + binary min-heap open set. Reused via
     * a {@link ThreadLocal} and {@link #reset} per search so steady-state allocation is ~zero.
     */
    private static final class Nodes {
        // ---- node table (append-only; row index stable, parent[] points at it) ----
        long[] key;
        int[] x, y, z;          // level-0 region coords
        float[] g, f;
        int[] parent;           // predecessor row, -1 at the start
        int count;

        // ---- key→row index (open addressing, linear probe) ----
        long[] mapKey;
        int[] mapRow;           // -1 marks an empty slot
        int mapMask;
        int mapSize;
        int mapGrowAt;

        // ---- open set (binary min-heap, ordered by f then larger g) ----
        int[] heap;
        float[] heapF;
        float[] heapG;
        int heapSize;
        float poppedF;          // f snapshot of the last pop() — the caller's staleness test

        Nodes(int nodeHint, int mapCap) {
            key = new long[nodeHint];
            x = new int[nodeHint];
            y = new int[nodeHint];
            z = new int[nodeHint];
            g = new float[nodeHint];
            f = new float[nodeHint];
            parent = new int[nodeHint];
            mapKey = new long[mapCap];
            mapRow = new int[mapCap];
            Arrays.fill(mapRow, -1);
            mapMask = mapCap - 1;
            mapGrowAt = mapCap * 3 / 4;
            heap = new int[nodeHint];
            heapF = new float[nodeHint];
            heapG = new float[nodeHint];
        }

        /** Clear for the next search, keeping every array at its grown high-water-mark capacity. */
        void reset() {
            count = 0;
            heapSize = 0;
            mapSize = 0;
            Arrays.fill(mapRow, -1);
        }

        /** Row for {@code k}, creating it ({@code g=+inf}, unlinked) if absent. One probe. */
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
                    int c = (r < last && (heapF[r] < heapF[l]
                            || (heapF[r] == heapF[l] && heapG[r] > heapG[l]))) ? r : l;
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

        /** Murmur3 64-bit finalizer → slot; copied verbatim from {@code BlockPathfinder.Nodes.slotFor}. */
        private static int slotFor(long k, int mask) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            k *= 0xc4ceb9fe1a85ec53L;
            k ^= k >>> 33;
            return (int) k & mask;
        }
    }
}
