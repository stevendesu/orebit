package com.orebit.mod.pathfinding.regionpathfinder;

import java.util.Arrays;

import com.orebit.mod.Debug;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.regionpathfinder.heuristics.SimpleRegionHeuristic;
import com.orebit.mod.worldmodel.hpa.CostCodec;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.LeafCostComputer;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
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
 *
 * <h2>Fragment model (HPA-FRAGMENTS.md §S3, flag-gated)</h2>
 * When {@link RegionGrid#HPA_FRAGMENTS} is on, the abstract node is {@code (region, fragment)} — one node per
 * 6-connected occupiable component of a region (usually 1; a handful in caves; a single synthetic fragment
 * {@code 0} for a uniform SOLID/AIR/WATER or over-cap collapsed region). The search (see
 * {@link #planLevel0Fragments}) reads connectivity through {@link RegionGrid}'s fragment accessors instead of
 * the face buckets, and <b>derives every edge cost</b> from geometry + universal constants (no stored cost
 * buckets — HPA-FRAGMENTS.md §2.2):
 * <ul>
 *   <li><b>Portal edges</b> (cheap, inter-region): two fragments whose face footprints <b>overlap</b> on the
 *       shared face. Cost = the §2.2 walk formula (octile horizontal + directional Δy: dear
 *       {@link #PILLAR_PER_BLOCK} up, cheap {@link #FALL_PER_BLOCK} down) between the two openings' centers.</li>
 *   <li><b>Mine edges</b> (expensive, intra-region): to every <i>other</i> fragment of the same region — dig
 *       through the wall. Cost = Manhattan span × {@link #MINE_PER_BLOCK} × {@code avgSolidHardness/STONE_REF}.</li>
 *   <li><b>Uniform-kind transit</b>: into a SOLID (mine across), AIR (one-way down chute), WATER (symmetric
 *       swim), or collapsed (passability-weighted mass) neighbour — folding in the leaf fast paths.</li>
 * </ul>
 * The graph is therefore always fully connected (a sealed region routes through an expensive mine edge — no
 * disconnected FAIL). The <b>center-model</b> direct branch (flag off) is byte-for-byte unchanged. S5 (pyramid
 * fragment merge / roll-up) is not built; the coarse scale-guard branch stays center-model/optimistic even
 * under the flag (noted at its call site).
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

    // ---------------------------------------------------------------------------------------------------
    // Derived edge-cost constants (HPA-FRAGMENTS.md §2.2) — universal, per-block, NOT stored. Each is the
    // per-block ticks of one motion; an edge cost is the geometry (octile / Manhattan span / Δy) times these.
    // Sourced from the leaf computer / fragment builder so the fragment tier shares ONE source of truth with
    // the center model's uniform fast paths (air chute, solid mine, swim).
    // ---------------------------------------------------------------------------------------------------

    /** Leaf side in blocks (16); the region span a uniform-kind transit / mine-across covers. */
    private static final int LEAF = RegionAddress.LEAF_SIZE;

    /** √2, the octile diagonal factor (a diagonal walk step costs {@code √2 × WALK}). */
    private static final float SQRT2 = 1.4142135f;

    /** Per-block horizontal WALK ticks: an all-air leaf transit (16) over its side ⇒ 1 tick/block. */
    public static final float WALK_PER_BLOCK = LeafCostComputer.AIR_TRANSIT_TICKS / LEAF;          // 1.0

    /** Per-block upward PILLAR ticks (dear): the leaf air-climb cost over its side ⇒ ~place base cost/block. */
    public static final float PILLAR_PER_BLOCK = LeafCostComputer.AIR_CLIMB_TICKS / LEAF;          // 6.0

    /** Per-block downward FALL ticks (cheap): falling is near-free; charged ~one walk-tick/block of descent. */
    public static final float FALL_PER_BLOCK = WALK_PER_BLOCK;                                     // 1.0

    /** Per-block MINE ticks at the reference (stone) hardness; scaled by {@code avgSolidHardness/STONE_REF}. */
    public static final float MINE_PER_BLOCK = LeafCostComputer.MINE_PER_BLOCK;                    // 3.0

    /** The hardness nibble at which the mine cost is unscaled (stone) — see {@link FragmentBuilder}. */
    public static final int STONE_REF_NIBBLE = FragmentBuilder.STONE_HARDNESS_NIBBLE;             // 4

    /**
     * Default intra-region mine span (blocks) when the two fragments' footprint-derived centroids coincide or
     * sit on the same boundary — the §2.2 "~half-region span". Keeps a same-region dig from reading as free.
     */
    private static final int HALF_REGION = LEAF / 2;                                              // 8

    /**
     * Wall thickness (blocks) charged for a non-overlapping inter-region opening reached by mining (the
     * fragment touches the shared face but its footprint does not line up with ours). Ordinal, S5-tunable.
     */
    private static final int WALL_MINE_BLOCKS = 4;

    /**
     * Per-excess-block penalty for a vertical region transit a {@code !canPlace} bot cannot realize — a drop
     * deeper than {@code safeFall} (a cliff it would take fall damage on) or a rise it cannot pillar. Dear
     * enough (≈ a place/mine per block) that the region A* prefers a <b>gradual</b> route — one that spreads its
     * climb/descent horizontally into small per-transit {@code |dy|} — over a single big-{@code |dy|} cliff/wall
     * the block tier would dead-end on. Ordinal, S5-tunable.
     */
    private static final float UNSAFE_VERTICAL_PENALTY = 16f;

    /** Sentinel for "no portal cell" in the SoA portal arrays (the start node). */
    private static final int NO_PORTAL = RegionPathPlan.NO_PORTAL;

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
                                      BlockPos goalFloor, BotCaps caps) {
        return plan(level, grid, startFloor, goalFloor, caps, null);
    }

    /**
     * As {@link #plan(ServerLevel, RegionGrid, BlockPos, BlockPos, BotCaps)}, additionally honouring a {@link
     * RegionEdgeBlacklist} of region→region crossings the block tier has proven unrealizable for these caps:
     * the fragment A* skips any blacklisted edge, so it returns the <b>next-best</b> route (the walk-around).
     * {@code null} = no blacklist (the historical behaviour; used by {@code /bot trace} and the tests).
     */
    public static RegionPathPlan plan(ServerLevel level, RegionGrid grid, BlockPos startFloor,
                                      BlockPos goalFloor, BotCaps caps, RegionEdgeBlacklist blacklist) {
        final int minY = grid.minY();

        final int srx = RegionAddress.regionX(startFloor.getX(), 0);
        final int sry = RegionAddress.regionY(startFloor.getY(), 0, minY);
        final int srz = RegionAddress.regionZ(startFloor.getZ(), 0);

        final int grx = RegionAddress.regionX(goalFloor.getX(), 0);
        final int gry = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        final int grz = RegionAddress.regionZ(goalFloor.getZ(), 0);

        // Fragment model (HPA-FRAGMENTS.md §S3): node = (region, fragment); edges derived, not stored. It owns
        // its own trivial / scale-guard handling because "same region" is no longer "same node".
        if (RegionGrid.HPA_FRAGMENTS) {
            return planFragments(grid, minY, startFloor, goalFloor, srx, sry, srz, grx, gry, grz,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), blacklist);
        }

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

        final int startRow = nodes.intern(RegionAddress.packLevelKey(srx, sry, srz), srx, sry, srz, 0);
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

                // Directional boundary sum (PRD §6.5): leaving N through `face` is N's EXIT half; entering M
                // through the opposite face is M's ENTER half. The two differ for air (pillar-out expensive,
                // fall-in cheap), which is what stops the region A* from treating air as a cheap up/over highway.
                final float out = grid.faceCost(0, crx, cry, crz, face, CostPyramid.EXIT);
                final float in = grid.faceCost(0, nrx, nry, nrz, RegionAddress.opposite(face), CostPyramid.ENTER);
                final float edge = out + in;
                // INF boundary → impassable; never relax across it.
                if (edge >= CostCodec.COST_INF) continue;

                final float tentative = gCur + edge;
                final int row = nodes.intern(RegionAddress.packLevelKey(nrx, nry, nrz), nrx, nry, nrz, 0);
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

    // ===================================================================================================
    // Fragment-model A* (HPA-FRAGMENTS.md §S3) — node = (region, fragment); every edge cost DERIVED (§2.2)
    // ===================================================================================================

    /**
     * Plan a fragment-model skeleton (HPA-FRAGMENTS.md §S3). Resolves the start/goal <b>fragment</b> within
     * each region (nearest occupiable component to the floor cell; {@code 0} for uniform/collapsed), short-
     * circuits the same-region-same-fragment case, applies the scale guard, and otherwise runs the level-0
     * fragment A*.
     *
     * <p><b>Coarse branch (S5 deferred):</b> beyond {@link #LEVEL0_DIRECT_CAP} this falls back to the
     * center-model {@link #planCoarseRefined} — the pyramid fragment merge ({@code PyramidMerger}, §7) that
     * would roll fragments up to coarse levels is not built, so the long-reach branch stays center-model/
     * optimistic for now. The milestone exercises the direct branch.
     */
    private static RegionPathPlan planFragments(RegionGrid grid, int minY, BlockPos startFloor,
                                                BlockPos goalFloor, int srx, int sry, int srz,
                                                int grx, int gry, int grz,
                                                boolean canBreak, boolean canPlace, int safeFall,
                                                RegionEdgeBlacklist blacklist) {
        grid.ensureLeaf(srx, sry, srz);
        grid.ensureLeaf(grx, gry, grz);
        final int startFrag = nearestFragment(grid, srx, sry, srz, startFloor);
        final int goalFrag = nearestFragment(grid, grx, gry, grz, goalFloor);

        // Trivial: same region AND same fragment ⇒ a one-step plan (no portal). Different fragments in the
        // same region is NOT trivial — it must route through an intra-region mine edge (run the A*).
        if (srx == grx && sry == gry && srz == grz && startFrag == goalFrag) {
            int[] rx = {srx}, ry = {sry}, rz = {srz}, fr = {startFrag};
            int[] px = {NO_PORTAL}, py = {NO_PORTAL}, pz = {NO_PORTAL};
            return new RegionPathPlan(rx, ry, rz, fr, px, py, pz, 1, minY, true);
        }

        int cheb = Math.max(Math.abs(grx - srx), Math.max(Math.abs(gry - sry), Math.abs(grz - srz)));
        if (cheb > LEVEL0_DIRECT_CAP) {
            // S5 deferred: no fragment roll-up yet → the long-reach branch is center-model/optimistic.
            return planCoarseRefined(grid, minY, srx, sry, srz, grx, gry, grz);
        }

        return planLevel0Fragments(grid, minY, srx, sry, srz, startFrag, grx, gry, grz, goalFrag, true,
                canBreak, canPlace, safeFall, blacklist);
    }

    /**
     * Level-0 fragment A* over {@code (region, fragment)} nodes. Expands each node into derived portal edges
     * (footprint-overlap on a shared face), intra-region mine edges (to sibling fragments), and uniform-kind
     * transit edges (into SOLID/AIR/WATER/collapsed neighbours) — HPA-FRAGMENTS.md §2. Terminates on the
     * specific {@code (goal region, goalFrag)} node so a sealed goal is forced through its mine edge.
     *
     * <p>When {@code canBreak} is false (a no-break bot), <b>every mining-based edge is dropped</b>: intra-region
     * sibling digs, the non-overlapping-footprint "mine to the nearest fragment" fallback, and transits into a
     * uniform SOLID neighbour. Only real walkable connectivity remains — overlapping-footprint portals plus
     * AIR/WATER/unbuilt transits — so the graph is <b>no longer guaranteed connected</b> and the search can
     * legitimately FAIL (the goal sits behind rock the bot cannot remove). This is the fix for a no-break bot
     * being routed at unmineable rock and then thrashing when the block tier can't dig (the {@code noBreakCap}
     * dead-end).
     */
    private static RegionPathPlan planLevel0Fragments(RegionGrid grid, int minY,
                                                      int srx, int sry, int srz, int startFrag,
                                                      int grx, int gry, int grz, int goalFrag,
                                                      boolean reachedGoalRegion,
                                                      boolean canBreak, boolean canPlace, int safeFall,
                                                      RegionEdgeBlacklist blacklist) {
        final Nodes nodes = SEARCH.get();
        nodes.reset();

        // Per-search world-coord scratch (allocated once per plan, reused every edge — no per-edge alloc).
        final int[] wa = new int[3]; // our (fragA) boundary-opening center
        final int[] wb = new int[3]; // neighbour (fragB) boundary-opening center / centroid
        final int[] wc = new int[3]; // scratch for centroid accumulation

        final int startRow = nodes.intern(fragmentKey(srx, sry, srz, startFrag), srx, sry, srz, startFrag);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = HEURISTIC.estimate(srx, sry, srz, grx, gry, grz);
        nodes.portalX[startRow] = NO_PORTAL;
        nodes.portalY[startRow] = NO_PORTAL;
        nodes.portalZ[startRow] = NO_PORTAL;
        nodes.push(startRow);

        int expansions = 0;
        int reachedRow = -1;
        boolean budgetHit = false;
        int bestRow = startRow;       // closest-to-goal node seen (min heuristic) — for the FAIL diagnostic
        float bestH = Float.MAX_VALUE;

        while (nodes.heapSize > 0) {
            int current = nodes.pop();
            if (nodes.poppedF > nodes.f[current]) continue; // stale heap entry

            final int crx = nodes.x[current], cry = nodes.y[current], crz = nodes.z[current];
            final int fragA = nodes.frag[current];
            if (crx == grx && cry == gry && crz == grz && fragA == goalFrag) {
                reachedRow = current;
                break;
            }
            final float hCur = nodes.f[current] - nodes.g[current];
            if (hCur < bestH) { bestH = hCur; bestRow = current; }
            if (++expansions > MAX_REGION_EXPANSIONS) { budgetHit = true; break; }

            grid.ensureLeaf(crx, cry, crz);
            final RegionFragments rfN = grid.fragmentRecord(0, crx, cry, crz);
            final boolean uniformN = isUniformNode(rfN);
            final int countN = uniformN ? 1 : rfN.fragmentCount();
            final float gCur = nodes.g[current];

            // (A) Intra-region MINE edges to sibling fragments (dig through the wall) — MIXED, ≥2 fragments.
            // Dropped entirely for a no-break bot: it cannot dig between two disconnected pockets of a region.
            if (canBreak && !uniformN && countN > 1) {
                for (int fragC = 0; fragC < countN; fragC++) {
                    if (fragC == fragA) continue;
                    float edge = mineCost(rfN, fragA, fragC, minY, crx, cry, crz, wa, wb, wc);
                    // wb now holds fragC's centroid (its interior rep) — the mine-edge portal target.
                    relaxFrag(nodes, current, gCur, edge, crx, cry, crz, fragC,
                            wb[0], wb[1], wb[2], grx, gry, grz, blacklist);
                }
            }

            // (B) Inter-region edges across each face fragA reaches (a uniform node reaches all six).
            for (int f = 0; f < 6; f++) {
                if (!uniformN && !rfN.touchesFace(fragA, f)) continue;
                final int mrx = RegionAddress.neighborRX(crx, f);
                final int mry = RegionAddress.neighborRY(cry, f);
                final int mrz = RegionAddress.neighborRZ(crz, f);
                grid.ensureLeaf(mrx, mry, mrz);
                final RegionFragments rfM = grid.fragmentRecord(0, mrx, mry, mrz);
                final int oppF = RegionAddress.opposite(f);

                // Our opening center on face f (full-face for a uniform node).
                final int packedA = uniformN ? RegionFragments.NO_FACE : rfN.footprint(fragA, f);
                footprintCenterWorld(minY, crx, cry, crz, f, packedA, wa);

                if (isUniformNode(rfM)) {
                    // A uniform SOLID neighbour is reachable only by mining → impassable for a no-break bot
                    // (an unbuilt/AIR/WATER neighbour is still a real walk/fall/swim, so those remain).
                    if (!canBreak && rfM != null && rfM.kind() == RegionFragments.KIND_SOLID) continue;
                    // Symmetric caps-correctness for a no-PLACE bot: a uniform-AIR neighbour is a floorless
                    // shaft, and a no-place bot cannot pillar, so the ONLY way it can enter one is by FALLING
                    // straight DOWN into it (face 2 = −Y, neighbour below). An upward (face 3) or sideways
                    // crossing into open air is physically impossible for it — so drop those edges, exactly as
                    // the no-break/solid rule above drops mine edges. This stops the region tier routing a
                    // no-place bot UP through the open cave void (the lip-ascent flood); it then finds a
                    // walkable ramp instead. (A place-capable bot keeps the dear PILLAR cost and may climb.)
                    if (!canPlace && rfM != null && rfM.kind() == RegionFragments.KIND_AIR && f != 2) continue;
                    // Uniform / collapsed / unbuilt neighbour: a single transit edge into its fragment 0.
                    float edge = uniformTransitCost(rfM, f, canPlace, safeFall);
                    footprintCenterWorld(minY, mrx, mry, mrz, oppF, RegionFragments.NO_FACE, wb);
                    relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, 0,
                            wb[0], wb[1], wb[2], grx, gry, grz, blacklist);
                    continue;
                }

                // MIXED neighbour: portal edge to every fragment whose footprint OVERLAPS ours on the shared
                // face (a real walkable opening, always allowed); if none overlaps, a mine edge to the nearest
                // touching fragment (or its fragment 0 if solid at this face) — keeping the graph fully
                // connected (§2, no FAIL). The mine fallback is gated on canBreak below: a no-break bot only
                // gets the overlapping-portal edges, so a face with no real opening is simply impassable.
                boolean emitted = false;
                int bestFrag = -1;
                long bestDist = Long.MAX_VALUE;
                final int countM = rfM.fragmentCount();
                for (int fb = 0; fb < countM; fb++) {
                    if (!rfM.touchesFace(fb, oppF)) continue;
                    int packedB = rfM.footprint(fb, oppF);
                    footprintCenterWorld(minY, mrx, mry, mrz, oppF, packedB, wb);
                    if (footprintsOverlap(packedA, packedB)) {
                        float edge = walkCost(wb[0] - wa[0], wb[1] - wa[1], wb[2] - wa[2], canPlace, safeFall);
                        relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, fb,
                                wb[0], wb[1], wb[2], grx, gry, grz, blacklist);
                        emitted = true;
                    } else {
                        long d = Math.abs(wb[0] - wa[0]) + Math.abs(wb[1] - wa[1]) + Math.abs(wb[2] - wa[2]);
                        if (d < bestDist) { bestDist = d; bestFrag = fb; }
                    }
                }
                if (!emitted && canBreak) {
                    float hardFactor = hardnessFactor(rfM.avgSolidHardness());
                    if (bestFrag != -1) {
                        int packedB = rfM.footprint(bestFrag, oppF);
                        footprintCenterWorld(minY, mrx, mry, mrz, oppF, packedB, wb);
                        float edge = walkCost(wb[0] - wa[0], wb[1] - wa[1], wb[2] - wa[2], canPlace, safeFall)
                                + WALL_MINE_BLOCKS * MINE_PER_BLOCK * hardFactor;
                        relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, bestFrag,
                                wb[0], wb[1], wb[2], grx, gry, grz, blacklist);
                    } else {
                        // Neighbour is MIXED but solid at this face → mine straight in to its fragment 0.
                        footprintCenterWorld(minY, mrx, mry, mrz, oppF, RegionFragments.NO_FACE, wb);
                        float edge = LEAF * MINE_PER_BLOCK * hardFactor;
                        relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, 0,
                                wb[0], wb[1], wb[2], grx, gry, grz, blacklist);
                    }
                }
            }
        }

        if (reachedRow == -1) {
            if (Debug.ENABLED) {
                final int cheb = Math.max(Math.abs(grx - srx),
                        Math.max(Math.abs(gry - sry), Math.abs(grz - srz)));
                OrebitCommon.LOGGER.info("[Orebit] region plan FAIL start=({},{},{}) goal=({},{},{}) cheb={}regions"
                        + " — {} after {} expansions; closest region=({},{},{}) h={} (heuristic {} ticks/region);"
                        + " heapLeft={}",
                        srx, sry, srz, grx, gry, grz, cheb,
                        budgetHit ? "HIT EXPANSION BUDGET" : "heap drained (disconnected?)", expansions,
                        nodes.x[bestRow], nodes.y[bestRow], nodes.z[bestRow], bestH,
                        SimpleRegionHeuristic.COST_PER_REGION, nodes.heapSize);
            }
            return null;
        }
        return reconstructFragments(nodes, startRow, reachedRow, minY, reachedGoalRegion);
    }

    /**
     * Relax the edge into {@code (mrx,mry,mrz,mFrag)} via {@code curRow}. The edge cost is floored at
     * {@link #WALK_PER_BLOCK} so every boundary crossing costs ≥ one tick — so g grows monotonically even for
     * perfectly-aligned portals (and for the free unbuilt transit), keeping the search well-ordered.
     */
    private static void relaxFrag(Nodes nodes, int curRow, float gCur, float edge,
                                  int mrx, int mry, int mrz, int mFrag,
                                  int px, int py, int pz, int grx, int gry, int grz,
                                  RegionEdgeBlacklist blacklist) {
        long k = fragmentKey(mrx, mry, mrz, mFrag);
        // Online repair (RegionEdgeBlacklist): skip a crossing the block tier proved unrealizable for these
        // caps, so the region A* routes around it (the walk-around) instead of re-offering the dead end.
        if (blacklist != null
                && blacklist.contains(fragmentKey(nodes.x[curRow], nodes.y[curRow], nodes.z[curRow],
                        nodes.frag[curRow]), k)) {
            return;
        }
        float tentative = gCur + Math.max(edge, WALK_PER_BLOCK);
        int row = nodes.intern(k, mrx, mry, mrz, mFrag);
        if (tentative >= nodes.g[row]) return; // new rows start at +inf → first visit admitted
        nodes.g[row] = tentative;
        nodes.f[row] = tentative + HEURISTIC.estimate(mrx, mry, mrz, grx, gry, grz);
        nodes.parent[row] = curRow;
        nodes.frag[row] = mFrag;
        nodes.portalX[row] = px;
        nodes.portalY[row] = py;
        nodes.portalZ[row] = pz;
        nodes.push(row);
    }

    /**
     * Walk {@code parent} back from {@code reachedRow} to {@code startRow}, emitting region coords + fragment id
     * + portal cell per step in travel order (the fragment-model {@link #reconstruct}).
     */
    private static RegionPathPlan reconstructFragments(Nodes nodes, int startRow, int reachedRow, int minY,
                                                       boolean reachedGoalRegion) {
        int len = 0;
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            len++;
            if (n == startRow) break;
        }
        int[] rxs = new int[len], rys = new int[len], rzs = new int[len], frags = new int[len];
        int[] px = new int[len], py = new int[len], pz = new int[len];
        int i = len - 1;
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            rxs[i] = nodes.x[n];
            rys[i] = nodes.y[n];
            rzs[i] = nodes.z[n];
            frags[i] = nodes.frag[n];
            px[i] = nodes.portalX[n];
            py[i] = nodes.portalY[n];
            pz[i] = nodes.portalZ[n];
            i--;
            if (n == startRow) break;
        }
        return new RegionPathPlan(rxs, rys, rzs, frags, px, py, pz, len, minY, reachedGoalRegion);
    }

    // ---------------------------------------------------------------------------------------------------
    // Fragment-model node key + geometry / cost helpers (pure; alloc only into caller-supplied scratch)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The {@code (region, fragment)} node key: {@link RegionAddress#packLevelKey} XOR the 6-bit fragment id
     * shifted into bits 50..55. {@code packLevelKey} uses only bits 0..49 (rx[28..49] | rz[6..27] | ry[0..5]),
     * so placing the fragment in the free high bits keeps the XOR collision-free — a low-bit XOR would clash
     * with the packed {@code ry} field. Fragment {@code 0} leaves the key == {@code packLevelKey}, so a
     * single-fragment / uniform region keys exactly as the center model does.
     */
    private static long fragmentKey(int rx, int ry, int rz, int frag) {
        return RegionAddress.packLevelKey(rx, ry, rz) ^ ((long) (frag & 0x3F) << 50);
    }

    /**
     * The {@code (region, fragment)} node key for an arbitrary caller — the public face of {@link
     * #fragmentKey} so {@link com.orebit.mod.pathfinding.PathPlan} can name a skeleton hop's endpoints when
     * feeding a blocked crossing to a {@link RegionEdgeBlacklist}. Pure; matches the keys the fragment A* uses.
     */
    public static long fragmentNodeKey(int rx, int ry, int rz, int frag) {
        return fragmentKey(rx, ry, rz, frag);
    }

    /** A node with no real fragment records: a {@code null}/unbuilt record, a uniform kind, or collapsed mass. */
    private static boolean isUniformNode(RegionFragments rf) {
        return rf == null || rf.isUniform() || rf.fragmentCount() == 0;
    }

    /** The mine-cost hardness scale {@code avgSolidHardness / STONE_REF}, floored at a softest-nonzero block. */
    private static float hardnessFactor(int avgSolidHardnessNibble) {
        return Math.max(avgSolidHardnessNibble, 1) / (float) STONE_REF_NIBBLE;
    }

    /** 2D octile distance over the two horizontal in-face axes ({@code (max-min) + √2·min}). */
    private static float octile(int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        int hi = Math.max(ax, az), lo = Math.min(ax, az);
        return (hi - lo) + SQRT2 * lo;
    }

    /**
     * The §2.2 walk cost between two openings: octile over the horizontal offset × {@link #WALK_PER_BLOCK},
     * plus the directional Δy term — dear {@link #PILLAR_PER_BLOCK} going up, cheap {@link #FALL_PER_BLOCK}
     * going down (the air-chute asymmetry, recovered from geometry rather than stored buckets).
     *
     * <p><b>Caps-honest:</b> {@code |dy|} is the footprint Y-gap across this transit. A bot that {@code
     * !canPlace} cannot pillar up a wall, and can only safely drop {@code safeFall} blocks; a gap beyond that
     * is a cliff/wall it cannot realize, so it gets {@link #UNSAFE_VERTICAL_PENALTY} per excess block. A
     * gradual route spreads its rise/drop horizontally (small {@code |dy|} per transit, no penalty) and so is
     * preferred over a single big-{@code |dy|} cliff the block tier would dead-end on.
     */
    private static float walkCost(int dx, int dy, int dz, boolean canPlace, int safeFall) {
        float c = octile(dx, dz) * WALK_PER_BLOCK;
        if (dy > 0) {
            c += dy * PILLAR_PER_BLOCK;
            // No-place bot can't pillar a wall taller than a step — a big net rise must be existing terrain
            // (gradual stairs ⇒ small dy); penalize the excess so the search prefers the gradual route.
            if (!canPlace && dy > safeFall) {
                c += (dy - safeFall) * UNSAFE_VERTICAL_PENALTY;
            }
        } else if (dy < 0) {
            final int drop = -dy;
            c += drop * FALL_PER_BLOCK;
            // Damage cost for a fall past the safe window — applies to EVERY bot (falling needs no placing,
            // unlike a wall-climb), so it biases the region A* toward gradual descents (small per-transit drop)
            // over cliffs, matching the block tier's Fall cost-not-blocker model. Not gated on canPlace.
            if (drop > safeFall) {
                c += (drop - safeFall) * UNSAFE_VERTICAL_PENALTY;
            }
        }
        return c;
    }

    /**
     * Cost of an intra-region mine edge between two fragments (§2.2): the Manhattan span between their
     * footprint-derived centroids (floored at {@link #HALF_REGION} — interior fragments share no face, so their
     * centroid is the region center) × {@link #MINE_PER_BLOCK} × the hardness scale. Fills {@code centC} with
     * fragC's centroid (the mine-edge portal target). {@code centA}/{@code centC}/{@code tmp} are caller scratch.
     */
    private static float mineCost(RegionFragments rf, int fragA, int fragC, int minY, int rx, int ry, int rz,
                                  int[] centA, int[] centC, int[] tmp) {
        fragmentCentroidWorld(rf, fragA, minY, rx, ry, rz, centA, tmp);
        fragmentCentroidWorld(rf, fragC, minY, rx, ry, rz, centC, tmp);
        int span = Math.abs(centA[0] - centC[0]) + Math.abs(centA[1] - centC[1]) + Math.abs(centA[2] - centC[2]);
        span = Math.max(span, HALF_REGION);
        return span * MINE_PER_BLOCK * hardnessFactor(rf.avgSolidHardness());
    }

    /**
     * Cost of a uniform-kind transit into neighbour {@code rfM} across our exit face {@code f} (§2.3):
     * <ul>
     *   <li><b>SOLID</b> / unbuilt-as-solid → mine across a full leaf side, hardness-scaled.</li>
     *   <li><b>AIR</b> → one-way down chute: cheap ({@link #FALL_PER_BLOCK}) only when exiting downward
     *       (face {@code 2} = −Y); dear ({@link #PILLAR_PER_BLOCK}) every other direction.</li>
     *   <li><b>WATER</b> → symmetric swim ({@link LeafCostComputer#WATER_TRANSIT_TICKS}).</li>
     *   <li><b>collapsed MIXED</b> → passability-weighted mass: {@code airWalk·passFrac + solidMine·(1−passFrac)}.</li>
     * </ul>
     * A {@code null}/unbuilt (unloaded) record is <b>FREE</b> — perfectly optimistic, distinct from a genuine
     * built {@link RegionFragments#KIND_AIR KIND_AIR} region (which keeps the directional pillar/fall chute).
     */
    private static float uniformTransitCost(RegionFragments rfM, int f, boolean canPlace, int safeFall) {
        // UNBUILT / unloaded (null record): we don't know what's there, so assume the best possible — free
        // passage (a "teleporter" through the unknown). Returning ~0 keeps g flat across unloaded space, so the
        // region A* degenerates to greedy-best-first and BEELINES at the goal instead of flooding the expansion
        // budget (the long-range plan:NONE bug). The relaxFrag WALK floor still charges ~1 tick/region, so ties
        // resolve toward the shorter optimistic route. Chunks load on approach → the leaf builds → a replan
        // corrects to the real terrain (§6 online optimism). NOTE: this is deliberately MORE optimistic than a
        // built all-air region below — unknown ≠ known-air; known air really does cost a pillar to climb.
        if (rfM == null) {
            return 0f;
        }
        if (rfM.kind() == RegionFragments.KIND_MIXED) {
            // Collapsed mass (count == 0): more air ⇒ cheaper to cross.
            float passFrac = rfM.passFrac() / 15f;
            float solidMine = LEAF * MINE_PER_BLOCK * hardnessFactor(rfM.avgSolidHardness());
            return LEAF * WALK_PER_BLOCK * passFrac + solidMine * (1f - passFrac);
        }
        switch (rfM.kind()) {
            case RegionFragments.KIND_SOLID:
                return LEAF * MINE_PER_BLOCK * hardnessFactor(rfM.avgSolidHardness());
            case RegionFragments.KIND_WATER:
                return LeafCostComputer.WATER_TRANSIT_TICKS;
            case RegionFragments.KIND_AIR:
            default:
                // Face 2 = −Y exit (falling out the bottom) is the only cheap air motion; all else is dear.
                // But a uniform-AIR region is a full LEAF-tall shaft: a no-place bot falling through it drops
                // LEAF blocks, unsafe past safeFall, so even the down-chute is penalized for it (prefer a
                // gradual MIXED descent with real floors over a free-fall shaft).
                if (f == 2) {
                    float fall = LEAF * FALL_PER_BLOCK;
                    if (!canPlace && LEAF > safeFall) {
                        fall += (LEAF - safeFall) * UNSAFE_VERTICAL_PENALTY;
                    }
                    return fall;
                }
                return LEAF * PILLAR_PER_BLOCK;
        }
    }

    /** Whether two packed face footprints overlap on both in-face axes (a {@link RegionFragments#NO_FACE} ⇒ full face). */
    private static boolean footprintsOverlap(int packedA, int packedB) {
        int minUA, maxUA, minVA, maxVA, minUB, maxUB, minVB, maxVB;
        if (packedA == RegionFragments.NO_FACE) {
            minUA = 0; maxUA = LEAF - 1; minVA = 0; maxVA = LEAF - 1;
        } else {
            minUA = RegionFragments.footprintMinU(packedA); maxUA = RegionFragments.footprintMaxU(packedA);
            minVA = RegionFragments.footprintMinV(packedA); maxVA = RegionFragments.footprintMaxV(packedA);
        }
        if (packedB == RegionFragments.NO_FACE) {
            minUB = 0; maxUB = LEAF - 1; minVB = 0; maxVB = LEAF - 1;
        } else {
            minUB = RegionFragments.footprintMinU(packedB); maxUB = RegionFragments.footprintMaxU(packedB);
            minVB = RegionFragments.footprintMinV(packedB); maxVB = RegionFragments.footprintMaxV(packedB);
        }
        return Math.max(minUA, minUB) <= Math.min(maxUA, maxUB)
                && Math.max(minVA, minVB) <= Math.min(maxVA, maxVB);
    }

    /**
     * The world-block center of a fragment's opening on {@code face} of region {@code (rx,ry,rz)} — the
     * midpoint of the packed footprint bbox on the face plane (a {@link RegionFragments#NO_FACE} ⇒ the full
     * face center). Per-face in-face axes follow {@link RegionFragments}: ±X → (Y,Z), ±Y → (X,Z), ±Z → (X,Y).
     * Writes {@code out[0..2] = wx,wy,wz}.
     */
    private static void footprintCenterWorld(int minY, int rx, int ry, int rz, int face, int packed, int[] out) {
        int minU, maxU, minV, maxV;
        if (packed == RegionFragments.NO_FACE) {
            minU = 0; maxU = LEAF - 1; minV = 0; maxV = LEAF - 1;
        } else {
            minU = RegionFragments.footprintMinU(packed); maxU = RegionFragments.footprintMaxU(packed);
            minV = RegionFragments.footprintMinV(packed); maxV = RegionFragments.footprintMaxV(packed);
        }
        int cu = (minU + maxU) >> 1;
        int cv = (minV + maxV) >> 1;
        int ox = rx << RegionAddress.LEAF_BITS;
        int oy = minY + (ry << RegionAddress.LEAF_BITS);
        int oz = rz << RegionAddress.LEAF_BITS;
        switch (face) {
            case 0: out[0] = ox;            out[1] = oy + cu;     out[2] = oz + cv;     break; // -X: u=Y v=Z
            case 1: out[0] = ox + LEAF - 1; out[1] = oy + cu;     out[2] = oz + cv;     break; // +X
            case 2: out[0] = ox + cu;       out[1] = oy;          out[2] = oz + cv;     break; // -Y: u=X v=Z
            case 3: out[0] = ox + cu;       out[1] = oy + LEAF - 1; out[2] = oz + cv;   break; // +Y
            case 4: out[0] = ox + cu;       out[1] = oy + cv;     out[2] = oz;          break; // -Z: u=X v=Y
            default: out[0] = ox + cu;      out[1] = oy + cv;     out[2] = oz + LEAF - 1; break; // +Z
        }
    }

    /**
     * A fragment's representative interior point: the average of its touched-face opening centers, or — for an
     * interior fragment that reaches no face — the region center. Writes {@code out[0..2]}; {@code tmp} is
     * caller scratch for the per-face center.
     */
    private static void fragmentCentroidWorld(RegionFragments rf, int frag, int minY, int rx, int ry, int rz,
                                              int[] out, int[] tmp) {
        long sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (int f = 0; f < 6; f++) {
            if (rf.touchesFace(frag, f)) {
                footprintCenterWorld(minY, rx, ry, rz, f, rf.footprint(frag, f), tmp);
                sx += tmp[0]; sy += tmp[1]; sz += tmp[2];
                n++;
            }
        }
        if (n == 0) {
            out[0] = (rx << RegionAddress.LEAF_BITS) + LEAF / 2;
            out[1] = minY + (ry << RegionAddress.LEAF_BITS) + LEAF / 2;
            out[2] = (rz << RegionAddress.LEAF_BITS) + LEAF / 2;
        } else {
            out[0] = (int) (sx / n);
            out[1] = (int) (sy / n);
            out[2] = (int) (sz / n);
        }
    }

    /**
     * The fragment of region {@code (rx,ry,rz)} whose centroid is nearest the world floor cell {@code floor}
     * (Manhattan) — the search's start/goal fragment. {@code 0} for a uniform/collapsed/unbuilt region (its
     * single synthetic fragment). Cold (called twice per {@link #plan}); a few small scratch arrays are fine.
     */
    private static int nearestFragment(RegionGrid grid, int rx, int ry, int rz, BlockPos floor) {
        return fragmentOf(grid, rx, ry, rz, floor.getX(), floor.getY(), floor.getZ(),
                new int[3], new int[3]);
    }

    /**
     * The fragment of region {@code (rx,ry,rz)} whose centroid is nearest the world floor cell
     * {@code (fx,fy,fz)} (Manhattan); {@code 0} for a uniform/collapsed/unbuilt region (its single synthetic
     * fragment). The <b>allocation-free</b> form (the two caller-supplied 3-int scratch buffers carry the
     * per-candidate centroid + its per-face temporary) so the {@link com.orebit.mod.pathfinding.PathPlan}
     * driver can resolve the bot's current fragment <i>per tick</i> (S4 — the {@code (region,fragment)}
     * commit/wiggle key) without per-call garbage. The same nearest-centroid membership signal the planner
     * uses for the start/goal fragment, so the driver and the skeleton agree on which fragment a cell is in.
     */
    public static int fragmentOf(RegionGrid grid, int rx, int ry, int rz,
                                 int fx, int fy, int fz, int[] cent, int[] tmp) {
        RegionFragments rf = grid.fragmentRecord(0, rx, ry, rz);
        if (isUniformNode(rf)) return 0;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int f = 0; f < rf.fragmentCount(); f++) {
            fragmentCentroidWorld(rf, f, grid.minY(), rx, ry, rz, cent, tmp);
            long d = Math.abs(cent[0] - fx) + Math.abs(cent[1] - fy) + Math.abs(cent[2] - fz);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
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
        int[] frag;             // fragment id within (x,y,z) — 0 for center-model / uniform nodes
        int[] portalX, portalY, portalZ; // world portal cell of the parent edge (NO_PORTAL on the start node)
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
            frag = new int[nodeHint];
            portalX = new int[nodeHint];
            portalY = new int[nodeHint];
            portalZ = new int[nodeHint];
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

        /** Row for {@code k}, creating it ({@code g=+inf}, unlinked) if absent. One probe. {@code cf} = fragment id. */
        int intern(long k, int cx, int cy, int cz, int cf) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) {
                    row = newRow(k, cx, cy, cz, cf);
                    mapKey[slot] = k;
                    mapRow[slot] = row;
                    if (++mapSize >= mapGrowAt) growMap();
                    return row;
                }
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        private int newRow(long k, int cx, int cy, int cz, int cf) {
            int n = count;
            if (n == key.length) growNodes();
            key[n] = k;
            x[n] = cx; y[n] = cy; z[n] = cz;
            frag[n] = cf;
            portalX[n] = NO_PORTAL; portalY[n] = NO_PORTAL; portalZ[n] = NO_PORTAL;
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
            frag = Arrays.copyOf(frag, cap);
            portalX = Arrays.copyOf(portalX, cap);
            portalY = Arrays.copyOf(portalY, cap);
            portalZ = Arrays.copyOf(portalZ, cap);
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
