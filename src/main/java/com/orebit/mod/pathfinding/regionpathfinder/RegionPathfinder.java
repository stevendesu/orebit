package com.orebit.mod.pathfinding.regionpathfinder;

import java.util.Arrays;

import com.orebit.mod.Debug;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.regionpathfinder.heuristics.SimpleRegionHeuristic;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.LeafCostComputer;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The HPA* region-tier A* (PRD §6.3–6.5, §7.1; HPA-FRAGMENTS.md §S3) — the <b>connectivity-aware fragment
 * model</b> over a fixed cubic-grid implicit octree.
 *
 * <h2>The model — node = (region, fragment)</h2>
 * The abstract node is {@code (region, fragment)} — one node per 6-connected occupiable component of a region
 * (usually 1; a handful in caves; a single synthetic fragment {@code 0} for a uniform SOLID/AIR/WATER or
 * over-cap collapsed region). The search ({@link #planLevelFragments}) reads connectivity through
 * {@link RegionGrid}'s fragment accessors and <b>derives every edge cost</b> from geometry + universal
 * constants (no stored cost buckets — HPA-FRAGMENTS.md §2.2):
 * <ul>
 *   <li><b>Portal edges</b> (cheap, inter-region): two fragments whose face footprints <b>overlap</b> on the
 *       shared face. Cost = the §2.2 walk formula (octile horizontal + directional Δy: dear
 *       {@link #PILLAR_PER_BLOCK} up, cheap {@link #FALL_PER_BLOCK} down) between the two openings' <b>floor</b>
 *       crossing cells ({@link #footprintCenterWorld}, the standable-Δy anchor — PERF-DESIGN region §3).</li>
 *   <li><b>Mine edges</b> (expensive, intra-region): to every <i>other</i> fragment of the same region — dig
 *       through the wall. Cost = the {@link #digCost two-term walk + dig}: you WALK the tunnel span PLUS break
 *       the 2-tall body in the way, tool-aware via {@link RegionMineModel} (PERF-DESIGN region §5).</li>
 *   <li><b>Uniform-kind transit</b>: into a SOLID (mine across), AIR (one-way down chute), WATER (symmetric
 *       swim), or collapsed (passability-weighted mass) neighbour — folding in the leaf fast paths.</li>
 * </ul>
 * The graph is therefore always fully connected for a digging bot (a sealed region routes through an expensive
 * mine edge — no disconnected FAIL); a no-break bot drops the mine edges and can legitimately FAIL.
 *
 * <h2>What this produces</h2>
 * {@link #plan} runs a <b>direct level-0</b> fragment A* from the start region to the goal region and returns a
 * {@link RegionPathPlan}. {@link #planLevelFragments} is level-parameterized ({@code 0} = leaves; {@code >0} =
 * the rolled-up coarse fragment pyramid, {@link com.orebit.mod.worldmodel.hpa.PyramidMerger}), and
 * {@link #planWithin} is the windowed per-level entry the {@link HierarchicalRegionPlan} cascade calls at every
 * level of its stack to reach out to million-block goals (HPA-CASCADE.md). {@link RegionGrid} is the single
 * read chokepoint: {@link RegionGrid#ensureLeaf}/{@link RegionGrid#ensureLevel} lazily build a node before it is
 * read (optimistic admissible default for unloaded/unbuilt nodes — §6).
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * Allocation-light A* in the {@link Nodes} idiom copied from
 * {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}: a struct-of-arrays node table, an
 * open-addressed {@code long}→row map (murmur3 finalizer, {@code -1} empty, linear probe, grow-at-3/4) keyed
 * by {@link RegionAddress#packLevelKey} XOR the fragment id, and a binary min-heap open set. A
 * {@link ThreadLocal} reused state drives steady-state per-search allocation to ~zero. The heuristic is a
 * swappable {@link RegionHeuristic} (strategy, not switch) — the ratified {@link SimpleRegionHeuristic}.
 */
public final class RegionPathfinder {

    private RegionPathfinder() {}

    /**
     * Node-expansion ceiling for the region A* — a backstop against a pathological replan stalling the tick.
     * Far larger than a normal (cap-safe, windowed) region search needs; see {@link #CAP_SAFE_NODES}.
     */
    public static final int MAX_REGION_EXPANSIONS = 20000;

    /**
     * Cap-safe search-box budget (HPA-FRAGMENTS.md §S5 / cap-safety). The cascade picks the FINEST region level
     * whose start→goal box (horizontal area × vertical depth) fits within this many nodes, so a per-level
     * fragment A* cannot flood the {@link #MAX_REGION_EXPANSIONS} backstop by area. Sized well under the backstop
     * (~2.4×) to absorb A* detours/walk-arounds (which explore outside the start→goal box) and the
     * {@code (region,fragment)} node multiplier.
     */
    public static final int CAP_SAFE_NODES = 8192;

    /**
     * Whether a <b>budget-hit</b> region search returns the best-so-far PARTIAL skeleton (toward the goal)
     * instead of {@code null} — the region analog of the block tier's partial-path / Baritone best-so-far. The
     * cap-safe level selection makes a flood rare; this makes the rare one (a maze-detour flood the box-bound
     * can't prevent) graceful: the bot makes progress and re-plans, rather than giving up. A genuine heap-drain
     * (disconnected for these caps) still returns {@code null} — that is a real no-route, not a budget hit.
     */
    public static boolean REGION_PARTIAL_ON_BUDGET = true;

    /**
     * Step-by-step region-search trace sink — when non-null AND {@link #TRACE} is on, every node expansion and
     * every candidate edge (kind, cost, crossing cell, accept/reject) is written here for OFFLINE analysis of
     * WHY the region A* builds the skeleton it does (the down→over→up cavern-drop investigation — the region
     * counterpart of {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder#TRACE_OUT}). Format
     * (space-separated, greppable):
     * <pre>
     *   E &lt;seq&gt; L&lt;level&gt; region=x,y,z frag=&lt;f&gt; g=&lt;g&gt; f=&lt;f&gt;
     *     C &lt;kind&gt; -&gt; x,y,z frag=&lt;f&gt; cost=&lt;c&gt; crossing=wx,wy,wz &lt;OK|worse&gt;
     * </pre>
     * {@code kind} ∈ {walk, air-fall, air-pillar, solid-mine, water-swim, collapsed, unbuilt, mine-sibling,
     * mine-fallback, mine-solid}. An {@code E} line is one pop (expansion order); the indented {@code C} lines
     * under it are the edges it emitted ({@code OK} = relaxed onto the open set, {@code worse} = not an
     * improvement / blacklisted). Writing per node does file I/O on the calling thread — a one-shot debug path
     * driven by {@code /bot rtrace}, never on in normal play (the trace is huge + slow).
     */
    public static java.io.Writer TRACE_OUT;

    /** Gate for {@link #TRACE_OUT}: emit the step-by-step region trace. Off in normal play (huge + slow). */
    public static boolean TRACE = false;

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

    // Block-honest PILLAR/FALL ratios for the cost-to-goal FIELD only (the heuristic must be commensurate with
    // block ticks). The region A* keeps its behavioral compressed costs above (PILLAR 6 / FALL 1 — self-consistent
    // within the region search); but as a BLOCK heuristic (rc × WALK_REAL_TICKS ≈ block ticks) those ratios are
    // wrong — the block tier prices pillar ≈ 10.6 t and a fall drop ≈ 2.5 t/block against a 4.633 t walk. So the
    // field uses the block RATIOS (PERF-DESIGN region §5's "honest ratios, compressed scale"): keep WALK=1 (↔4.633
    // t) and set FALL = 2.5/4.633 ≈ 0.54. This de-inflates fall-heavy routes (the walk-around) in the heuristic so
    // it stops reading far above the dig-down. The PILLAR ratio is now supplied per-search by RegionPlaceModel
    // (the bot's real place base + removal premium ÷ a walk tick); PILLAR_PER_BLOCK_FIELD is the fallback stand-in
    // it reproduces for a block-less / headless bot (10.6/4.633 ≈ 2.29 = Pillar.COST 4.633 + placeBaseCost 6).
    static final float PILLAR_PER_BLOCK_FIELD = 2.29f;
    static final float FALL_PER_BLOCK_FIELD = 0.54f;

    /** Per-block MINE ticks at the reference (stone) hardness; scaled by {@code avgSolidHardness/STONE_REF}. */
    public static final float MINE_PER_BLOCK = LeafCostComputer.MINE_PER_BLOCK;                    // 3.0

    /** The hardness nibble at which the mine cost is unscaled (stone) — see {@link FragmentBuilder}. */
    public static final int STONE_REF_NIBBLE = FragmentBuilder.STONE_HARDNESS_NIBBLE;             // 4

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

    // ---------------------------------------------------------------------------------------------------
    // Entry-face node identity (PERF-DESIGN-region-dig-through.md §2). The SEARCH node is
    // (region, fragment, entryFace) — the face (0..5) through which the node was entered, plus two sentinels.
    // Making entry part of the key keeps the §4 entry→exit walk cost a FIXED edge cost (consistent A*): a
    // fragment "entered from north" and "…from south" are distinct rows. Folded into the free high bits 56..58
    // of the physical fragmentKey; CONSUMERS stay physical (see fragmentNodeKey) — entryFace never leaks out.
    // ---------------------------------------------------------------------------------------------------

    /** Entry-face sentinel: the start node (no incoming crossing; hop-0 traversal anchors on the bot's cell). */
    private static final int ENTRY_START = 6;

    /** Entry-face sentinel: reached by an intra-region mine edge (entry opening is the sibling centroid, no face). */
    private static final int ENTRY_INTERIOR = 7;

    /** Fold a 3-bit {@code entryFace} (0..5 face, or {@link #ENTRY_START}/{@link #ENTRY_INTERIOR}) into the key. */
    private static long searchKey(long physKey, int entryFace) {
        return physKey ^ ((long) (entryFace & 0x7) << 56);
    }

    /** One reusable region-search state per thread, reset at the top of each {@link #plan}. */
    private static final ThreadLocal<Nodes> SEARCH = ThreadLocal.withInitial(() -> new Nodes(256, 512));

    /**
     * A SECOND reusable region-search state per thread, for the goal-rooted {@link #costToGoalField} Dijkstra —
     * kept distinct from {@link #SEARCH} because a block-tier search may drive a cost-field build while a region
     * A* is still in flight on the same thread (they must not clobber each other's {@link Nodes} table).
     */
    private static final ThreadLocal<Nodes> FIELD_SEARCH = ThreadLocal.withInitial(() -> new Nodes(256, 512));

    /**
     * Plan a <b>direct level-0 fragment skeleton</b> from {@code startFloor} to {@code goalFloor} (HPA-FRAGMENTS.md
     * §S3): node = {@code (region, fragment)}, edges derived not stored. Resolves the start/goal fragment, short-
     * circuits the trivial same-region-same-fragment case, and otherwise runs the level-0 fragment A* to the goal.
     * Long-range routing is the {@link HierarchicalRegionPlan} cascade's job (it plans per level via
     * {@link #planWithin}); this entry is the single-search level-0 form used by {@code /bot trace} and the unit
     * tests. Lazily builds the leaves it touches via {@link RegionGrid#ensureLeaf}.
     *
     * @return the level-0 skeleton (index 0 = start region), or {@code null} if no route is found within the
     *         expansion budget / for these caps (a best-so-far partial on a budget hit, per
     *         {@link #REGION_PARTIAL_ON_BUDGET}).
     */
    public static RegionPathPlan plan(ServerLevel level, RegionGrid grid, BlockPos startFloor,
                                      BlockPos goalFloor, BotCaps caps) {
        return plan(level, grid, startFloor, goalFloor, caps, RegionMineModel.DEFAULT);
    }

    /**
     * As {@link #plan(ServerLevel, RegionGrid, BlockPos, BlockPos, BotCaps)}, with the bot's tool-aware region
     * dig-cost model ({@link RegionMineModel}, PERF-DESIGN region §5). The live follower passes a model built
     * from its real inventory; the no-arg overload uses the stone-tier {@link RegionMineModel#DEFAULT} (headless
     * / tests / {@code /bot trace}).
     */
    public static RegionPathPlan plan(ServerLevel level, RegionGrid grid, BlockPos startFloor,
                                      BlockPos goalFloor, BotCaps caps, RegionMineModel mine) {
        final int minY = grid.minY();

        final int srx = RegionAddress.regionX(startFloor.getX(), 0);
        final int sry = RegionAddress.regionY(startFloor.getY(), 0, minY);
        final int srz = RegionAddress.regionZ(startFloor.getZ(), 0);

        final int grx = RegionAddress.regionX(goalFloor.getX(), 0);
        final int gry = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        final int grz = RegionAddress.regionZ(goalFloor.getZ(), 0);

        return planFragments(grid, minY, startFloor, goalFloor, srx, sry, srz, grx, gry, grz,
                caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), mine);
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
     * Plan a direct level-0 fragment-model skeleton (HPA-FRAGMENTS.md §S3). Resolves the start/goal
     * <b>fragment</b> within each region (nearest occupiable component to the floor cell; {@code 0} for
     * uniform/collapsed), short-circuits the same-region-same-fragment case, and otherwise runs the level-0
     * fragment A* straight to the goal. Long-range scale is the {@link HierarchicalRegionPlan} cascade's job
     * (it plans per level via {@link #planWithin}); this is the single-search level-0 entry behind {@link #plan}.
     */
    private static RegionPathPlan planFragments(RegionGrid grid, int minY, BlockPos startFloor,
                                                BlockPos goalFloor, int srx, int sry, int srz,
                                                int grx, int gry, int grz,
                                                boolean canBreak, boolean canPlace, int safeFall,
                                                RegionMineModel mine) {
        grid.ensureLeaf(srx, sry, srz);
        grid.ensureLeaf(grx, gry, grz);
        final int startFrag = startFragment(grid, 0, srx, sry, srz, startFloor);
        final int goalFrag = nearestFragment(grid, 0, grx, gry, grz, goalFloor);

        // Trivial: same region AND same fragment ⇒ a one-step plan (no portal). Different fragments in the
        // same region is NOT trivial — it must route through an intra-region mine edge (run the A*).
        if (srx == grx && sry == gry && srz == grz && startFrag == goalFrag) {
            int[] rx = {srx}, ry = {sry}, rz = {srz}, fr = {startFrag};
            int[] px = {NO_PORTAL}, py = {NO_PORTAL}, pz = {NO_PORTAL};
            return new RegionPathPlan(rx, ry, rz, fr, px, py, pz, 1, minY, true);
        }

        return planLevelFragments(0, grid, minY, srx, sry, srz, startFrag,
                startFloor.getX(), startFloor.getY(), startFloor.getZ(),
                grx, gry, grz, goalFrag, true, canBreak, canPlace, safeFall, null, mine);
    }

    // ---------------------------------------------------------------------------------------------------
    // Cap-safe level selection (HPA-FRAGMENTS.md §S5) — pick the finest level whose search box fits the budget
    // ---------------------------------------------------------------------------------------------------

    /**
     * The cap-safe one-axis Chebyshev radius at {@code level}: {@code ½·√(CAP_SAFE_NODES / verticalRegions(L))}.
     * A search confined to ±this many cells per horizontal axis explores at most {@code (2·r)² × vert ≤
     * CAP_SAFE_NODES} cells — so it cannot flood the {@link #MAX_REGION_EXPANSIONS} backstop by area. At the
     * quadtree levels ({@code vert==1}) this is {@code ½·√CAP_SAFE_NODES}; lower (octree) levels shrink it by
     * the vertical depth.
     */
    static int maxChebAtLevel(int level) {
        double r = Math.sqrt((double) CAP_SAFE_NODES / RegionAddress.verticalRegions(level)) / 2.0;
        int ri = (int) Math.floor(r);
        return ri < 1 ? 1 : ri;
    }

    /** Start→goal Chebyshev distance in level-{@code level} regions (vertical pinned to 0 above OCTREE_TOP). */
    private static int chebAtLevel(int level, int srx, int sry, int srz, int grx, int gry, int grz) {
        int sLx = srx >> level, sLz = srz >> level;
        int gLx = grx >> level, gLz = grz >> level;
        int sLy = (level >= RegionAddress.OCTREE_TOP) ? 0 : (sry >> level);
        int gLy = (level >= RegionAddress.OCTREE_TOP) ? 0 : (gry >> level);
        return Math.max(Math.abs(gLx - sLx), Math.max(Math.abs(gLy - sLy), Math.abs(gLz - sLz)));
    }

    /**
     * The finest level whose start→goal box is cap-safe (Chebyshev ≤ {@link #maxChebAtLevel}); capped at
     * {@link RegionAddress#MAX_COARSE_LEVEL} (no world root). For a goal beyond even that level's cap-safe reach
     * the {@link HierarchicalRegionPlan} cascade clamps its top-level search toward it and re-plans on approach
     * — the pyramid is never made taller.
     */
    static int chooseCapSafeLevel(int srx, int sry, int srz, int grx, int gry, int grz) {
        for (int L = 0; L < RegionAddress.MAX_COARSE_LEVEL; L++) {
            if (chebAtLevel(L, srx, sry, srz, grx, gry, grz) <= maxChebAtLevel(L)) {
                return L;
            }
        }
        return RegionAddress.MAX_COARSE_LEVEL;
    }

    /**
     * Plan a <b>windowed</b> level-{@code level} fragment skeleton from the bot toward a handed-down sub-goal —
     * the per-level search the {@link HierarchicalRegionPlan} cascade invokes at every level of its stack
     * (HPA-CASCADE.md §S6.2): clamp the sub-goal to {@link #maxChebAtLevel} of the bot's cell, then run the
     * level-parameterized {@link #planLevelFragments}.
     *
     * <p>The {@code subGoalWorld} is converted to level-{@code level} region coords and <b>clamped to
     * {@link #maxChebAtLevel}</b> of the bot's cell, so even a far hand-down stays cap-safe by construction
     * (HPA-CASCADE.md §4, §8). {@code reachedGoalRegion} is set iff that clamped cell is the {@code realGoal}'s
     * own cell at this level — so a skeleton flags goal-reached only when it genuinely ends at the true goal
     * region (an intermediate near segment correctly reports {@code false}). The start/goal fragments are
     * resolved by nearest centroid, exactly as {@link #plan} does for the leaf.
     *
     * @param level        the pyramid level to plan at (0 = leaves)
     * @param botFloor      the bot's world floor cell (the search start, mapped to this level)
     * @param subGoalWorld  the world sub-goal handed down from the level above (clamped here)
     * @param realGoal      the navigation's true goal cell (only used to set {@code reachedGoalRegion})
     * @param blacklist     this level's forbidden crossings (may be {@code null})
     * @return the level-tagged fragment skeleton, or {@code null} if no route within the budget/caps
     */
    public static RegionPathPlan planWithin(int level, RegionGrid grid, int minY,
                                            BlockPos botFloor, BlockPos subGoalWorld, BlockPos realGoal,
                                            BotCaps caps, RegionEdgeBlacklist blacklist) {
        return planWithin(level, grid, minY, botFloor, subGoalWorld, realGoal, caps, blacklist,
                RegionMineModel.DEFAULT);
    }

    /**
     * As {@link #planWithin(int, RegionGrid, int, BlockPos, BlockPos, BlockPos, BotCaps, RegionEdgeBlacklist)},
     * with the bot's tool-aware region dig-cost model ({@link RegionMineModel}, §5). The cascade
     * ({@link HierarchicalRegionPlan}) threads the model built once from the bot's inventory.
     */
    public static RegionPathPlan planWithin(int level, RegionGrid grid, int minY,
                                            BlockPos botFloor, BlockPos subGoalWorld, BlockPos realGoal,
                                            BotCaps caps, RegionEdgeBlacklist blacklist, RegionMineModel mine) {
        final int sx = RegionAddress.regionX(botFloor.getX(), level);
        final int sz = RegionAddress.regionZ(botFloor.getZ(), level);
        final int sy = RegionAddress.regionY(botFloor.getY(), level, minY);

        // Clamp the sub-goal to the cap-safe radius of the bot's cell (binds only for a far hand-down / the
        // capped top level); a clamped search ends at a waypoint the cascade slides toward on the next exit.
        final int m = maxChebAtLevel(level);
        final int gx = stepToward(sx, RegionAddress.regionX(subGoalWorld.getX(), level), m);
        final int gz = stepToward(sz, RegionAddress.regionZ(subGoalWorld.getZ(), level), m);
        final int gy = stepToward(sy, RegionAddress.regionY(subGoalWorld.getY(), level, minY), m);

        final boolean reached = gx == RegionAddress.regionX(realGoal.getX(), level)
                && gz == RegionAddress.regionZ(realGoal.getZ(), level)
                && gy == RegionAddress.regionY(realGoal.getY(), level, minY);

        ensureNode(grid, level, sx, sy, sz);
        ensureNode(grid, level, gx, gy, gz);
        final int sFrag = startFragment(grid, level, sx, sy, sz, botFloor);
        final int gFrag = nearestFragment(grid, level, gx, gy, gz, subGoalWorld);

        return planLevelFragments(level, grid, minY, sx, sy, sz, sFrag,
                botFloor.getX(), botFloor.getY(), botFloor.getZ(),
                gx, gy, gz, gFrag, reached, caps.canBreak(), caps.canPlace(), caps.safeFallDistance(),
                blacklist, mine);
    }

    /**
     * Fragment A* over {@code (region, fragment)} nodes at an arbitrary {@code level} (0 = leaves, the direct
     * branch; {@code >0} = the rolled-up coarse pyramid, the scale-guard branch). All geometry/cost is
     * level-aware (region span {@link RegionAddress#sideOf}, footprint world-projection, octree/quadtree
     * vertical) so the identical edge model applies at every resolution. Expands each node into derived portal
     * edges (footprint-overlap on a shared face), intra-region mine edges (to sibling fragments), and
     * uniform-kind transit edges (into SOLID/AIR/WATER/collapsed neighbours) — HPA-FRAGMENTS.md §2. Terminates
     * on the specific {@code (goal region, goalFrag)} node so a sealed goal is forced through its mine edge.
     *
     * <p>When {@code canBreak} is false (a no-break bot), <b>every mining-based edge is dropped</b>: intra-region
     * sibling digs, the non-overlapping-footprint "mine to the nearest fragment" fallback, and transits into a
     * uniform SOLID neighbour. Only real walkable connectivity remains — overlapping-footprint portals plus
     * AIR/WATER/unbuilt transits — so the graph is <b>no longer guaranteed connected</b> and the search can
     * legitimately FAIL (the goal sits behind rock the bot cannot remove). This is the fix for a no-break bot
     * being routed at unmineable rock and then thrashing when the block tier can't dig (the {@code noBreakCap}
     * dead-end).
     */
    private static RegionPathPlan planLevelFragments(int level, RegionGrid grid, int minY,
                                                      int srx, int sry, int srz, int startFrag,
                                                      int startWx, int startWy, int startWz,
                                                      int grx, int gry, int grz, int goalFrag,
                                                      boolean reachedGoalRegion,
                                                      boolean canBreak, boolean canPlace, int safeFall,
                                                      RegionEdgeBlacklist blacklist, RegionMineModel mine) {
        final Nodes nodes = SEARCH.get();
        nodes.reset();

        // Heuristic scale: SimpleRegionHeuristic is calibrated per LEVEL-0 region (COST_PER_REGION = one leaf
        // walk). At level L the derived edge costs scale with sideOf(L) = LEAF<<L, so scale h the same (×2^L)
        // to keep g and h in the same units — otherwise the coarse search is ~2^L under-guided and floods loaded
        // terrain (the level-0 Dijkstra-flood failure mode, re-expressed at coarse resolution). Level 0 ⇒ ×1.
        final float hScale = 1 << level;

        final int startRow = nodes.intern(searchKey(fragmentKey(srx, sry, srz, startFrag), ENTRY_START),
                srx, sry, srz, startFrag, ENTRY_START);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = HEURISTIC.estimate(srx, sry, srz, grx, gry, grz) * hScale;
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
                if (TRACE) trace("GOAL reached region=" + crx + "," + cry + "," + crz + " frag=" + fragA
                        + " g=" + nodes.g[current]);
                reachedRow = current;
                break;
            }
            final float hCur = nodes.f[current] - nodes.g[current];
            if (hCur < bestH) { bestH = hCur; bestRow = current; }
            if (++expansions > MAX_REGION_EXPANSIONS) { budgetHit = true; break; }

            // Forward A* (dijkstra=false) keeps its behavioral PILLAR_PER_BLOCK; the pillarField arg is read only
            // on the reverse field edges, so the constant here is an inert placeholder.
            expandNode(nodes, current, expansions, grid, level, minY, grx, gry, grz,
                    startWx, startWy, startWz, canBreak, canPlace, safeFall, blacklist, mine,
                    PILLAR_PER_BLOCK_FIELD, hScale, null, false);
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
            // Best-so-far PARTIAL on a BUDGET hit (a maze-detour flood that the cap-safe box-bound can't fully
            // prevent): return the skeleton to the closest-to-goal node so the bot makes progress and re-plans,
            // instead of giving up. A heap-drain (no budgetHit) is a genuine no-route for these caps → null.
            if (budgetHit && REGION_PARTIAL_ON_BUDGET && bestRow != startRow) {
                return reconstructFragments(nodes, startRow, bestRow, minY, level, false);
            }
            return null;
        }
        return reconstructFragments(nodes, startRow, reachedRow, minY, level, reachedGoalRegion);
    }

    // ===================================================================================================
    // Goal-rooted region Dijkstra (PROTOTYPE) — a per-region cost-to-goal FIELD over the same edge model
    // ===================================================================================================

    /**
     * Build a <b>goal-rooted, bounded Dijkstra cost-to-goal field</b> over the level-0 fragment graph: seed the
     * goal region/fragment at {@code g=0} and exhaust the heap confined to {@code bound}, recording the
     * min-over-fragments settled {@code g} per region into a {@link RegionCostField}. It reuses the SAME derived
     * edge model as the A* ({@link #expandNode}) — the ONLY differences are (a) the search is rooted at the goal
     * and never goal-tests (it floods the whole box), and (b) the heuristic is suppressed ({@code dijkstra=true},
     * so {@code f == g}) making the settle order a true shortest-first Dijkstra. Runs on the dedicated
     * {@link #FIELD_SEARCH} state so it can coexist with an in-flight region A* on the same thread.
     *
     * <p>NOTE: because the fragment edge model prices a crossing by the FROM-node's geometry (entry→exit walk,
     * our-rock dig-through), the field is an admissible-ish approximation of "cost to reach the goal FROM here",
     * not an exact reverse metric; it is intended as a coarse guidance field, not a substitute for the A*.
     */
    public static RegionCostField costToGoalField(RegionGrid grid, int minY, BlockPos goalFloor,
                                                  boolean canBreak, boolean canPlace, int safeFall,
                                                  RegionMineModel mine, RegionPlaceModel place, RegionBox bound) {
        // Capability-aware pillar cost for the field's upward-climb term (place-side sibling of the mine model);
        // replaces the hardcoded PILLAR_PER_BLOCK_FIELD stand-in. Only the reverse (field) edges read it.
        final float pillarField = place.pillarPerBlock();
        final int grx = RegionAddress.regionX(goalFloor.getX(), 0);
        final int gry = RegionAddress.regionY(goalFloor.getY(), 0, minY);
        final int grz = RegionAddress.regionZ(goalFloor.getZ(), 0);
        grid.ensureLeaf(grx, gry, grz);
        final int goalFrag = nearestFragment(grid, 0, grx, gry, grz, goalFloor);

        final Nodes nodes = FIELD_SEARCH.get();
        nodes.reset();
        final int startRow = nodes.intern(searchKey(fragmentKey(grx, gry, grz, goalFrag), ENTRY_START),
                grx, gry, grz, goalFrag, ENTRY_START);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = 0f;
        nodes.portalX[startRow] = NO_PORTAL;
        nodes.portalY[startRow] = NO_PORTAL;
        nodes.portalZ[startRow] = NO_PORTAL;
        nodes.push(startRow);

        final RegionCostField field = new RegionCostField(bound, minY, grid);
        int expansions = 0;
        while (nodes.heapSize > 0) {
            int current = nodes.pop();
            if (nodes.poppedF > nodes.f[current]) continue; // stale heap entry
            final int crx = nodes.x[current], cry = nodes.y[current], crz = nodes.z[current];
            final int fragA = nodes.frag[current];
            // Goalward exit opening = the crossing cell toward this node's Dijkstra parent (the parent is goalward
            // in the goal-rooted flood); onward = that parent's cost-to-goal. The goal's own start node has no
            // parent/portal, so its exit is the goal floor and onward is 0 — costAt then reproduces the plain
            // block octile at the goal fragment (no double-count under the block heuristic's max()).
            final int parent = nodes.parent[current];
            final float onward = parent >= 0 ? nodes.g[parent] : 0f;
            int ex = nodes.portalX[current], ey = nodes.portalY[current], ez = nodes.portalZ[current];
            if (ex == NO_PORTAL) { ex = goalFloor.getX(); ey = goalFloor.getY(); ez = goalFloor.getZ(); }
            field.record(crx, cry, crz, fragA, nodes.g[current], ex, ey, ez, onward); // per-(region,fragment) cost + gradient
            if (++expansions > MAX_REGION_EXPANSIONS) break;
            expandNode(nodes, current, expansions, grid, 0, minY, grx, gry, grz,
                    goalFloor.getX(), goalFloor.getY(), goalFloor.getZ(),
                    canBreak, canPlace, safeFall, null, mine, pillarField, 1.0f, bound, true);
        }
        return field;
    }

    /**
     * A tiny immutable region-coordinate bounding box (level-0 regions) that confines a {@link #costToGoalField}
     * Dijkstra to a finite volume — the field's dimensions come straight from it. {@link #contains} is the
     * per-relax admission test in {@link #relaxFrag}; {@link #around} builds the bbox of two region cells padded
     * by a margin on every axis (the usual "start↔goal box plus slack" the caller wants a field over).
     */
    public static final class RegionBox {
        public final int minRx, minRy, minRz, maxRx, maxRy, maxRz;

        RegionBox(int minRx, int minRy, int minRz, int maxRx, int maxRy, int maxRz) {
            this.minRx = minRx; this.minRy = minRy; this.minRz = minRz;
            this.maxRx = maxRx; this.maxRy = maxRy; this.maxRz = maxRz;
        }

        /** Whether region cell {@code (rx,ry,rz)} lies within (inclusive) this box. */
        public boolean contains(int rx, int ry, int rz) {
            return rx >= minRx && rx <= maxRx
                    && ry >= minRy && ry <= maxRy
                    && rz >= minRz && rz <= maxRz;
        }

        /** The bbox of region cells {@code a} and {@code b}, expanded by {@code pad} on each axis. */
        public static RegionBox around(int arx, int ary, int arz, int brx, int bry, int brz, int pad) {
            return new RegionBox(
                    Math.min(arx, brx) - pad, Math.min(ary, bry) - pad, Math.min(arz, brz) - pad,
                    Math.max(arx, brx) + pad, Math.max(ary, bry) + pad, Math.max(arz, brz) + pad);
        }
    }

    /**
     * The per-pop edge-emission body of the fragment search — the block-(A) intra-region mine-sibling edges and
     * the block-(B) six-face inter-region edges (dig-through / uniform-transit / portal-walk / mine-fallback /
     * mine-solid), each ending in a {@link #relaxFrag}. Extracted VERBATIM from {@link #planLevelFragments}'s
     * loop so the goal-rooted Dijkstra ({@link #costToGoalField}) can reuse the identical edge model. It
     * recomputes its own copies of the pop-node locals from {@code nodes[current]} (cheap); the A* loop keeps its
     * own {@code crx/cry/crz/fragA} for the goal test. {@code bound} (nullable) confines the search to a region
     * bbox and {@code dijkstra} suppresses the heuristic (both threaded straight through to {@link #relaxFrag}) —
     * with {@code bound == null, dijkstra == false} this is byte-identical to the original inline body.
     */
    private static void expandNode(Nodes nodes, int current, int seq, RegionGrid grid, int level, int minY,
                                   int grx, int gry, int grz, int startWx, int startWy, int startWz,
                                   boolean canBreak, boolean canPlace, int safeFall,
                                   RegionEdgeBlacklist blacklist, RegionMineModel mine, float pillarField,
                                   float hScale, RegionBox bound, boolean dijkstra) {
        final int crx = nodes.x[current], cry = nodes.y[current], crz = nodes.z[current];
        final int fragA = nodes.frag[current];
        final int[] wa = nodes.wa; // our (fragA) boundary-opening center
        final int[] wb = nodes.wb; // neighbour (fragB) boundary-opening center / centroid
        final int[] wc = nodes.wc; // scratch for centroid accumulation

        ensureNode(grid, level, crx, cry, crz);
        final RegionFragments rfN = grid.fragmentRecord(level, crx, cry, crz);
        final boolean uniformN = isUniformNode(rfN);
        final int countN = uniformN ? 1 : rfN.fragmentCount();
        final float gCur = nodes.g[current];
        // Entry opening of THIS node — where we crossed INTO it (the §4 entry→exit walk anchor). The start
        // node has no incoming crossing, so hop-0 traversal anchors on the bot's actual start floor cell
        // (honester than any synthetic opening). An intra-region-mine (INTERIOR) entry stored the sibling
        // centroid as its portal, so this reads that centroid for it — no special-casing needed.
        int entX = nodes.portalX[current], entY = nodes.portalY[current], entZ = nodes.portalZ[current];
        if (entX == NO_PORTAL) { entX = startWx; entY = startWy; entZ = startWz; }
        if (TRACE) trace("E " + seq + " L" + level + " region=" + crx + "," + cry + "," + crz
                + " frag=" + fragA + " g=" + gCur + " f=" + nodes.f[current]
                + (uniformN ? " [" + kindLabel(rfN) + "]" : " [mixed frags=" + countN + "]"));

        // (A) Intra-region MINE edges to sibling fragments (dig through the wall) — MIXED, ≥2 fragments.
        // Dropped entirely for a no-break bot: it cannot dig between two disconnected pockets of a region.
        if (canBreak && !uniformN && countN > 1) {
            for (int fragC = 0; fragC < countN; fragC++) {
                if (fragC == fragA) continue;
                float edge = mineCost(level, rfN, fragA, fragC, minY, crx, cry, crz, wa, wb, wc, mine);
                // wa/wb now hold fragA/fragC centroids; wb is the mine-edge portal target.
                boolean ok = relaxFrag(nodes, current, gCur, edge, crx, cry, crz, fragC,
                        wb[0], wb[1], wb[2], grx, gry, grz, hScale, blacklist, ENTRY_INTERIOR, false, bound, dijkstra);
                if (TRACE) {
                    traceCand("mine-sibling", crx, cry, crz, fragC, edge, wb[0], wb[1], wb[2], ok);
                    mineSpans(wa[0], wa[1], wa[2], wb[0], wb[1], wb[2], level, wc);
                    traceBreakdown("mine-sibling: " + digBreakdown(wc[0], wc[1],
                            mine.unitsPerBlock(rfN.avgSolidHardness())) + " = " + edge + " ticks");
                }
            }
        }

        // (B) Inter-region edges across each face fragA reaches (a uniform node reaches all six).
        for (int f = 0; f < 6; f++) {
            if (!uniformN && !rfN.touchesFace(fragA, f)) {
                // DIG-THROUGH (PERF-DESIGN §3): SOLID sits between fragA's air pocket and this face, so no
                // walk/fall/pillar opening exists — the one connectivity hole. A break-capable bot tunnels
                // OUR region's own rock to the sealed face and crosses into the neighbour: emit exactly one
                // edge into the neighbour's fragment 0 / oppF-face centre, priced by fragA's distance to that
                // face × our rock's hardness. Emitted DIRECTLY (never falling through to the walk path, which
                // would read packedA = NO_FACE = full face and spawn spurious walk edges). A no-break bot
                // emits nothing — it cannot dig — and the block tier's RegionEdgeBlacklist is the source of
                // truth for a dig it still cannot realize. Vertical faces above the octree→quadtree transition
                // have no ±Y neighbour, so skip them exactly as the touched-face branch below does.
                if (canBreak && !(level >= RegionAddress.OCTREE_TOP && (f == 2 || f == 3))) {
                    final int dmx = RegionAddress.neighborRX(crx, f);
                    final int dmy = RegionAddress.neighborRY(cry, f);
                    final int dmz = RegionAddress.neighborRZ(crz, f);
                    ensureNode(grid, level, dmx, dmy, dmz);
                    final int dOpp = RegionAddress.opposite(f);
                    // The dig edge is emitted for EVERY neighbour kind (full capability-connectivity: there is
                    // always our own rock to tunnel through to reach the sealed face). But only a uniform-SOLID
                    // neighbour yields a genuinely BURIED crossing cell that the block tier must dig to — so only
                    // then tag dig=true (windowTarget forwards a dig cell unfiltered). For an AIR/WATER/MIXED/
                    // unbuilt neighbour the oppF face centre is open (or ambiguous); leave dig=false so
                    // windowTarget snaps it like any other crossing rather than aiming the block search at a
                    // floorless mid-air cell.
                    final RegionFragments rfDig = grid.fragmentRecord(level, dmx, dmy, dmz);
                    final boolean buried = rfDig != null && rfDig.kind() == RegionFragments.KIND_SOLID;
                    // wa = fragA's interior centroid (the tunnel start); wb = the neighbour's oppF face centre.
                    fragmentCentroidWorld(level, rfN, fragA, minY, crx, cry, crz, wa, wc);
                    footprintCenterWorld(level, minY, dmx, dmy, dmz, dOpp, RegionFragments.NO_FACE, wb);
                    mineSpans(wa[0], wa[1], wa[2], wb[0], wb[1], wb[2], level, wc);
                    float edge = digCost(wc[0], wc[1], mine.unitsPerBlock(rfN.avgSolidHardness()));
                    boolean ok = relaxFrag(nodes, current, gCur, edge, dmx, dmy, dmz, 0,
                            wb[0], wb[1], wb[2], grx, gry, grz, hScale, blacklist, dOpp, buried, bound, dijkstra);
                    if (TRACE) {
                        traceCand("dig-through", dmx, dmy, dmz, 0, edge, wb[0], wb[1], wb[2], ok);
                        traceBreakdown("dig-through: " + digBreakdown(wc[0], wc[1],
                                mine.unitsPerBlock(rfN.avgSolidHardness())) + " = " + edge + " ticks");
                    }
                }
                continue;
            }
            // Above the octree→quadtree transition the vertical extent is ONE padded slab spanning the whole
            // dimension, so there is no ±Y neighbour region (ry is pinned to 0 — RegionAddress.regionY).
            // Skip the vertical faces there; neighborRY would otherwise key a phantom ry=±1 region.
            if (level >= RegionAddress.OCTREE_TOP && (f == 2 || f == 3)) continue;
            final int mrx = RegionAddress.neighborRX(crx, f);
            final int mry = RegionAddress.neighborRY(cry, f);
            final int mrz = RegionAddress.neighborRZ(crz, f);
            ensureNode(grid, level, mrx, mry, mrz);
            final RegionFragments rfM = grid.fragmentRecord(level, mrx, mry, mrz);
            final int oppF = RegionAddress.opposite(f);

            // Our opening center on face f (full-face for a uniform node).
            final int packedA = uniformN ? RegionFragments.NO_FACE : rfN.footprint(fragA, f);
            footprintCenterWorld(level, minY,crx, cry, crz, f, packedA, wa);

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
                float edge = uniformTransitCost(level, rfM, f, canPlace, safeFall, mine, pillarField, dijkstra);
                footprintCenterWorld(level, minY,mrx, mry, mrz, oppF, RegionFragments.NO_FACE, wb);
                boolean ok = relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, 0,
                        wb[0], wb[1], wb[2], grx, gry, grz, hScale, blacklist, oppF, false, bound, dijkstra);
                if (TRACE) traceCand(uniformKindLabel(rfM, f), mrx, mry, mrz, 0, edge,
                        wb[0], wb[1], wb[2], ok);
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
                footprintCenterWorld(level, minY,mrx, mry, mrz, oppF, packedB, wb);
                if (footprintsOverlap(packedA, packedB)) {
                    // §4 entry→exit: charge the traversal ACROSS this region (entry opening → our exit
                    // opening wa) plus the ~1 boundary hop (wa → neighbour opening wb). The entry→exit leg
                    // is the missing "moving within a 16-block region isn't free" term that made lateral
                    // walks cost ~1 and turned open caverns near-free. entryFace in the node key (§2) makes
                    // this a FIXED edge cost (the entry opening is pinned per node), keeping A* consistent.
                    float edge = walkCost(wa[0] - entX, wa[1] - entY, wa[2] - entZ, canPlace, safeFall, dijkstra, pillarField)
                            + walkCost(wb[0] - wa[0], wb[1] - wa[1], wb[2] - wa[2], canPlace, safeFall, dijkstra, pillarField);
                    boolean ok = relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, fb,
                            wb[0], wb[1], wb[2], grx, gry, grz, hScale, blacklist, oppF, false, bound, dijkstra);
                    if (TRACE) {
                        traceCand("walk", mrx, mry, mrz, fb, edge, wb[0], wb[1], wb[2], ok);
                        traceBreakdown("traverse[" + walkBreakdown(wa[0] - entX, wa[1] - entY, wa[2] - entZ,
                                canPlace, safeFall) + "]  +  cross[" + walkBreakdown(wb[0] - wa[0],
                                wb[1] - wa[1], wb[2] - wa[2], canPlace, safeFall) + "]");
                    }
                    emitted = true;
                } else {
                    long d = Math.abs(wb[0] - wa[0]) + Math.abs(wb[1] - wa[1]) + Math.abs(wb[2] - wa[2]);
                    if (d < bestDist) { bestDist = d; bestFrag = fb; }
                }
            }
            if (!emitted && canBreak) {
                final float mineUnit = mine.unitsPerBlock(rfM.avgSolidHardness());
                if (bestFrag != -1) {
                    int packedB = rfM.footprint(bestFrag, oppF);
                    footprintCenterWorld(level, minY,mrx, mry, mrz, oppF, packedB, wb);
                    // Approach walk to the wall + tunnel a fixed WALL_MINE_BLOCKS-thick horizontal hole.
                    float edge = walkCost(wb[0] - wa[0], wb[1] - wa[1], wb[2] - wa[2], canPlace, safeFall, dijkstra, pillarField)
                            + digCost(WALL_MINE_BLOCKS, 0, mineUnit);
                    boolean ok = relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, bestFrag,
                            wb[0], wb[1], wb[2], grx, gry, grz, hScale, blacklist, oppF, false, bound, dijkstra);
                    if (TRACE) {
                        traceCand("mine-fallback", mrx, mry, mrz, bestFrag, edge, wb[0], wb[1], wb[2], ok);
                        traceBreakdown("walk[" + walkBreakdown(wb[0] - wa[0], wb[1] - wa[1], wb[2] - wa[2],
                                canPlace, safeFall) + "] + wall " + digBreakdown(WALL_MINE_BLOCKS, 0, mineUnit)
                                + " = " + edge);
                    }
                } else {
                    // Neighbour is MIXED but solid at this face → mine straight in to its fragment 0.
                    footprintCenterWorld(level, minY,mrx, mry, mrz, oppF, RegionFragments.NO_FACE, wb);
                    float edge = digCost(RegionAddress.sideOf(level), 0, mineUnit);
                    boolean ok = relaxFrag(nodes, current, gCur, edge, mrx, mry, mrz, 0,
                            wb[0], wb[1], wb[2], grx, gry, grz, hScale, blacklist, oppF, false, bound, dijkstra);
                    if (TRACE) traceCand("mine-solid", mrx, mry, mrz, 0, edge, wb[0], wb[1], wb[2], ok);
                }
            }
        }
    }

    /**
     * Relax the edge into {@code (mrx,mry,mrz,mFrag)} via {@code curRow}. The edge cost is floored at
     * {@link #WALK_PER_BLOCK} so every boundary crossing costs ≥ one tick — so g grows monotonically even for
     * perfectly-aligned portals (and for the free unbuilt transit), keeping the search well-ordered.
     */
    private static boolean relaxFrag(Nodes nodes, int curRow, float gCur, float edge,
                                     int mrx, int mry, int mrz, int mFrag,
                                     int px, int py, int pz, int grx, int gry, int grz,
                                     float hScale, RegionEdgeBlacklist blacklist,
                                     int entryFace, boolean dig, RegionBox bound, boolean dijkstra) {
        // Bounded search (goal-rooted Dijkstra field): reject a target outside the search box BEFORE interning,
        // so out-of-box nodes never enter the table / heap (the field is confined to its bbox).
        if (bound != null && !bound.contains(mrx, mry, mrz)) return false;
        // The blacklist keys crossings PHYSICALLY (region, fragment) — a dead crossing is unrealizable
        // regardless of how the FROM region was entered (§2), so the online-repair probe uses the plain
        // physical key, NOT the entry-augmented search key.
        long physKey = fragmentKey(mrx, mry, mrz, mFrag);
        // Online repair (RegionEdgeBlacklist): skip a crossing the block tier proved unrealizable for these
        // caps, so the region A* routes around it (the walk-around) instead of re-offering the dead end.
        if (blacklist != null
                && blacklist.contains(fragmentKey(nodes.x[curRow], nodes.y[curRow], nodes.z[curRow],
                        nodes.frag[curRow]), physKey)) {
            return false;
        }
        float tentative = gCur + Math.max(edge, WALK_PER_BLOCK);
        // The SEARCH node folds entryFace into the key (§2) so "fragment entered from north" ≠ "…from south".
        int row = nodes.intern(searchKey(physKey, entryFace), mrx, mry, mrz, mFrag, entryFace);
        if (tentative >= nodes.g[row]) return false; // new rows start at +inf → first visit admitted
        nodes.g[row] = tentative;
        nodes.f[row] = dijkstra ? tentative : tentative + HEURISTIC.estimate(mrx, mry, mrz, grx, gry, grz) * hScale;
        nodes.parent[row] = curRow;
        nodes.frag[row] = mFrag;
        nodes.portalX[row] = px;
        nodes.portalY[row] = py;
        nodes.portalZ[row] = pz;
        nodes.dig[row] = dig;
        nodes.push(row);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // Region trace sink helpers (only reached under TRACE — a /bot rtrace one-shot; see TRACE_OUT).
    // ---------------------------------------------------------------------------------------------------

    private static void trace(String line) {
        java.io.Writer w = TRACE_OUT;
        if (w == null) return;
        try { w.write(line); w.write('\n'); } catch (java.io.IOException ignored) { }
    }

    /** Trace one emitted candidate edge + its accept/reject outcome (only when {@link #TRACE}). */
    private static void traceCand(String kind, int rx, int ry, int rz, int frag, float cost,
                                  int px, int py, int pz, boolean ok) {
        trace("  C " + kind + " -> " + rx + "," + ry + "," + rz + " frag=" + frag
                + " cost=" + cost + " crossing=" + px + "," + py + "," + pz + (ok ? " OK" : " worse"));
    }

    /** TRACE only: an indented cost-breakdown line under the last candidate, so the owner can see WHY a walk/dig
     *  edge costs what it does (in ticks) — WALK/PILLAR/FALL/MINE per-block rates and the spans they multiply. */
    private static void traceBreakdown(String detail) {
        trace("      ~ " + detail);
    }

    /** Decompose one {@link #walkCost} into its horizontal-octile and directional-Δy tick components (TRACE). */
    private static String walkBreakdown(int dx, int dy, int dz, boolean canPlace, int safeFall) {
        final float oct = octile(dx, dz);
        final StringBuilder sb = new StringBuilder();
        sb.append("horiz=").append(oct * WALK_PER_BLOCK)
                .append(" (octile ").append(oct).append("×").append(WALK_PER_BLOCK).append("/blk)");
        if (dy > 0) {
            sb.append(" + up=").append(dy * PILLAR_PER_BLOCK)
                    .append(" (").append(dy).append("×PILLAR ").append(PILLAR_PER_BLOCK).append("/blk)");
            if (!canPlace && dy > safeFall) sb.append(" +cliff=").append((dy - safeFall) * UNSAFE_VERTICAL_PENALTY);
        } else if (dy < 0) {
            final int drop = -dy;
            sb.append(" + down=").append(drop * FALL_PER_BLOCK)
                    .append(" (").append(drop).append("×FALL ").append(FALL_PER_BLOCK).append("/blk)");
            if (drop > safeFall) sb.append(" +cliff=").append((drop - safeFall) * UNSAFE_VERTICAL_PENALTY);
        }
        return sb.toString();
    }

    /** The own-kind label of the expanded node (the {@code E} line tag). */
    private static String kindLabel(RegionFragments rf) {
        if (rf == null) return "unbuilt";
        switch (rf.kind()) {
            case RegionFragments.KIND_SOLID: return "solid";
            case RegionFragments.KIND_AIR:   return "air";
            case RegionFragments.KIND_WATER: return "water";
            default:                         return "collapsed";
        }
    }

    /** The trace label for a uniform/collapsed/unbuilt transit edge across face {@code f} (only under TRACE). */
    private static String uniformKindLabel(RegionFragments rfM, int f) {
        if (rfM == null) return "unbuilt";
        switch (rfM.kind()) {
            case RegionFragments.KIND_SOLID: return "solid-mine";
            case RegionFragments.KIND_WATER: return "water-swim";
            case RegionFragments.KIND_AIR:   return f == 2 ? "air-fall" : "air-pillar";
            default:                         return "collapsed";
        }
    }

    /**
     * Walk {@code parent} back from {@code reachedRow} to {@code startRow}, emitting region coords + fragment id
     * + portal cell per step in travel order (the fragment-model {@link #reconstruct}).
     */
    private static RegionPathPlan reconstructFragments(Nodes nodes, int startRow, int reachedRow, int minY,
                                                       int level, boolean reachedGoalRegion) {
        int len = 0;
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            len++;
            if (n == startRow) break;
        }
        int[] rxs = new int[len], rys = new int[len], rzs = new int[len], frags = new int[len];
        int[] px = new int[len], py = new int[len], pz = new int[len];
        boolean[] digs = new boolean[len];
        int i = len - 1;
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            rxs[i] = nodes.x[n];
            rys[i] = nodes.y[n];
            rzs[i] = nodes.z[n];
            frags[i] = nodes.frag[n];
            px[i] = nodes.portalX[n];
            py[i] = nodes.portalY[n];
            pz[i] = nodes.portalZ[n];
            digs[i] = nodes.dig[n]; // §5: this step's portal cell is a buried dig-through crossing
            i--;
            if (n == startRow) break;
        }
        return new RegionPathPlan(rxs, rys, rzs, frags, px, py, pz, digs, len, minY, level, reachedGoalRegion);
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

    /**
     * The <b>two-term walk + dig</b> region mine cost (PERF-DESIGN region §5). You still WALK the tunnel you dig
     * ({@code span × WALK_PER_BLOCK} — an always-present term, so a dig can never undercut a walk for any tool),
     * PLUS you break the 2-tall body in the way: tunnelling one block <i>horizontally</i> breaks two blocks
     * (feet + head), mining straight <i>down</i> breaks ~one — {@code (2·horizSpan + vertSpan)}. {@code mineUnit}
     * is the tool-aware per-block mine cost in {@link #WALK_PER_BLOCK} units ({@link RegionMineModel}).
     */
    private static float digCost(int horizSpan, int vertSpan, float mineUnit) {
        return (horizSpan + vertSpan) * WALK_PER_BLOCK + (2 * horizSpan + vertSpan) * mineUnit;
    }

    /**
     * Horizontal / vertical spans of a mine between two world points at {@code level}, floored at a half-region
     * horizontal dig (interior fragments share no face, so their centroid is the region center and the raw span
     * collapses to ~0). Writes {@code out[0] = horizSpan, out[1] = vertSpan}.
     */
    private static void mineSpans(int ax, int ay, int az, int bx, int by, int bz, int level, int[] out) {
        int hs = Math.abs(ax - bx) + Math.abs(az - bz);
        int vs = Math.abs(ay - by);
        final int floor = RegionAddress.sideOf(level) / 2;
        if (hs + vs < floor) { hs = floor; vs = 0; }
        out[0] = hs; out[1] = vs;
    }

    /** TRACE-only human breakdown of a {@link #digCost} (walk term + 2-tall dig term). */
    private static String digBreakdown(int horizSpan, int vertSpan, float mineUnit) {
        return "walk=" + ((horizSpan + vertSpan) * WALK_PER_BLOCK) + " (" + (horizSpan + vertSpan) + " blk)"
                + " + dig=" + ((2 * horizSpan + vertSpan) * mineUnit) + " ((2·" + horizSpan + "+" + vertSpan
                + ") blk × mine=" + mineUnit + "/blk)";
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
    private static float walkCost(int dx, int dy, int dz, boolean canPlace, int safeFall, boolean reverse,
                                  float pillarField) {
        // Field mode (reverse): use the block-honest FALL ratio and the CAPABILITY-AWARE pillar cost
        // (pillarField, from RegionPlaceModel — the bot's place base + removal premium) so the cost-to-goal
        // heuristic matches the block tier's real build economy; the forward A* keeps its compressed behavioral
        // costs. pillarField defaults to the PILLAR_PER_BLOCK_FIELD stand-in for a block-less/headless bot.
        final float pillar = reverse ? pillarField : PILLAR_PER_BLOCK;
        final float fall = reverse ? FALL_PER_BLOCK_FIELD : FALL_PER_BLOCK;
        float c = octile(dx, dz) * WALK_PER_BLOCK;
        // Reverse edge (goal-rooted cost-TO-goal Dijkstra): the ONLY asymmetry is vertical — traversing this edge
        // in the opposite direction swaps up (dear PILLAR) and down (cheap FALL). Negating dy yields the reverse
        // cost (octile horizontal is symmetric). Forward A* passes reverse=false → identical.
        if (reverse) dy = -dy;
        if (dy > 0) {
            c += dy * pillar;
            // No-place bot can't pillar a wall taller than a step — a big net rise must be existing terrain
            // (gradual stairs ⇒ small dy); penalize the excess so the search prefers the gradual route.
            if (!canPlace && dy > safeFall) {
                c += (dy - safeFall) * UNSAFE_VERTICAL_PENALTY;
            }
        } else if (dy < 0) {
            final int drop = -dy;
            c += drop * fall;
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
     * Cost of an intra-region mine edge between two fragments (§2.2): the {@link #digCost two-term walk + dig}
     * over the horizontal/vertical spans between their footprint-derived centroids (floored at a half-region
     * horizontal dig — interior fragments share no face, so their centroid is the region center), tool-aware via
     * {@code mine}. Fills {@code centC} with fragC's centroid (the mine-edge portal target); {@code centA}/{@code
     * centC}/{@code tmp} are caller scratch (this also leaves the spans in {@code tmp[0..1]} — unused by callers).
     */
    private static float mineCost(int level, RegionFragments rf, int fragA, int fragC, int minY,
                                  int rx, int ry, int rz, int[] centA, int[] centC, int[] tmp, RegionMineModel mine) {
        fragmentCentroidWorld(level, rf, fragA, minY, rx, ry, rz, centA, tmp);
        fragmentCentroidWorld(level, rf, fragC, minY, rx, ry, rz, centC, tmp);
        mineSpans(centA[0], centA[1], centA[2], centC[0], centC[1], centC[2], level, tmp);
        return digCost(tmp[0], tmp[1], mine.unitsPerBlock(rf.avgSolidHardness()));
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
    private static float uniformTransitCost(int level, RegionFragments rfM, int f, boolean canPlace, int safeFall,
                                            RegionMineModel mine, float pillarField, boolean reverse) {
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
        // Spans scale with the level: a transit / mine-across covers the node's full side (sideH); an air shaft
        // is the node's full vertical extent (sideH in the octree, the PAD_HEIGHT slab in the quadtree).
        final int sideH = RegionAddress.sideOf(level);
        final int vExtent = (level < RegionAddress.OCTREE_TOP) ? sideH : RegionAddress.PAD_HEIGHT;
        if (rfM.kind() == RegionFragments.KIND_MIXED) {
            // Collapsed mass (count == 0): more air ⇒ cheaper to cross. The solid share is the two-term dig.
            float passFrac = rfM.passFrac() / 15f;
            float solidMine = digCost(sideH, 0, mine.unitsPerBlock(rfM.avgSolidHardness()));
            return sideH * WALK_PER_BLOCK * passFrac + solidMine * (1f - passFrac);
        }
        switch (rfM.kind()) {
            case RegionFragments.KIND_SOLID:
                return digCost(sideH, 0, mine.unitsPerBlock(rfM.avgSolidHardness()));
            case RegionFragments.KIND_WATER:
                return LeafCostComputer.WATER_TRANSIT_TICKS;
            case RegionFragments.KIND_AIR:
            default:
                // Face 2 = −Y exit (falling out the bottom) is the only cheap air motion; all else is dear.
                // A uniform-AIR region is a full vExtent-tall shaft: a no-place bot falling through it drops
                // vExtent blocks, unsafe past safeFall, so even the down-chute is penalized for it (prefer a
                // gradual MIXED descent with real floors over a free-fall shaft).
                // Reverse (cost-TO-goal Dijkstra): traversing this transit the other way swaps its vertical sense,
                // so decide up/down on the OPPOSITE face — reverse of "up into air" (dear) is "fall out" (cheap).
                final int ef = reverse ? RegionAddress.opposite(f) : f;
                if (ef == 2) {
                    float fall = vExtent * (reverse ? FALL_PER_BLOCK_FIELD : FALL_PER_BLOCK);
                    if (!canPlace && vExtent > safeFall) {
                        fall += (vExtent - safeFall) * UNSAFE_VERTICAL_PENALTY;
                    }
                    return fall;
                }
                return vExtent * (reverse ? pillarField : PILLAR_PER_BLOCK);
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
     * The world-block <b>crossing cell</b> of a fragment's opening on {@code face} of region {@code (rx,ry,rz)}
     * — the midpoint of the packed footprint bbox along the face's <i>horizontal</i> in-face axis, but the
     * <b>floor</b> (bottom of the opening's vertical span) along the vertical axis (a {@link RegionFragments#NO_FACE}
     * ⇒ the full face). Per-face in-face axes follow {@link RegionFragments}: ±X → (Y,Z), ±Y → (X,Z), ±Z → (X,Y).
     * Writes {@code out[0..2] = wx,wy,wz}.
     *
     * <p><b>Standable-Δy anchor (Fix 1, PERF-DESIGN region §3).</b> A fragment floods the <i>air</i> above its
     * floor, so the opening on a horizontal face spans the whole air column and its vertical <i>midpoint</i> sits
     * well above the walkable surface. Measuring a walk's Δy against that mid-air centroid billed a phantom
     * {@code Δy × PILLAR_PER_BLOCK} climb the bot never makes on a flat crossing — the cave-repro dive. Anchoring
     * the vertical coordinate to the opening's bottom ({@code minU} for ±X, {@code minV} for ±Z — the lowest
     * passable cell, i.e. the standing height) makes a flat surface crossing Δy≈0 while a genuine step-up / window
     * still reads the real rise. The ±Y faces keep their region-boundary pin (a vertical crossing's endpoints ARE
     * the floor/ceiling). The horizontal octile axis keeps the midpoint (already honest, §4).
     */
    private static void footprintCenterWorld(int level, int minY, int rx, int ry, int rz, int face, int packed,
                                             int[] out) {
        int minU, maxU, minV, maxV;
        if (packed == RegionFragments.NO_FACE) {
            minU = 0; maxU = 15; minV = 0; maxV = 15;          // footprints are always 16-bucket face-relative
        } else {
            minU = RegionFragments.footprintMinU(packed); maxU = RegionFragments.footprintMaxU(packed);
            minV = RegionFragments.footprintMinV(packed); maxV = RegionFragments.footprintMaxV(packed);
        }
        final int cu = (minU + maxU) >> 1;
        final int cv = (minV + maxV) >> 1;
        // Level-aware: a bucket (0..15) maps to a world offset = bucket × (side / 16). Horizontal side doubles
        // per level (hScale = 1<<level); the vertical extent is the same in the octree but pins to the
        // PAD_HEIGHT slab in the quadtree (level ≥ OCTREE_TOP, ry == 0).
        final int shift = RegionAddress.shift(level);
        final int sideH = 1 << shift;
        final int hScale = 1 << level;
        final boolean octree = level < RegionAddress.OCTREE_TOP;
        final int vExtent = octree ? sideH : RegionAddress.PAD_HEIGHT;
        final int vScale = vExtent >> 4;
        final int ox = rx << shift;
        final int oy = octree ? (minY + (ry << shift)) : minY;
        final int oz = rz << shift;
        // Vertical FLOOR anchors (bottom of the opening's vertical span): u=Y on ±X faces, v=Y on ±Z faces.
        final int floorU = oy + minU * vScale;
        final int floorV = oy + minV * vScale;
        switch (face) {
            case 0: out[0] = ox;             out[1] = floorU;            out[2] = oz + cv * hScale; break; // -X u=Y v=Z
            case 1: out[0] = ox + sideH - 1; out[1] = floorU;            out[2] = oz + cv * hScale; break; // +X
            case 2: out[0] = ox + cu * hScale; out[1] = oy;             out[2] = oz + cv * hScale; break; // -Y u=X v=Z
            case 3: out[0] = ox + cu * hScale; out[1] = oy + vExtent-1; out[2] = oz + cv * hScale; break; // +Y
            case 4: out[0] = ox + cu * hScale; out[1] = floorV;          out[2] = oz;               break; // -Z u=X v=Y
            default:out[0] = ox + cu * hScale; out[1] = floorV;          out[2] = oz + sideH - 1;   break; // +Z
        }
    }

    /**
     * A fragment's representative interior point: the average of its touched-face opening centers, or — for an
     * interior fragment that reaches no face — the region center. Writes {@code out[0..2]}; {@code tmp} is
     * caller scratch for the per-face center.
     */
    private static void fragmentCentroidWorld(int level, RegionFragments rf, int frag, int minY,
                                              int rx, int ry, int rz, int[] out, int[] tmp) {
        long sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (int f = 0; f < 6; f++) {
            if (rf.touchesFace(frag, f)) {
                footprintCenterWorld(level, minY, rx, ry, rz, f, rf.footprint(frag, f), tmp);
                sx += tmp[0]; sy += tmp[1]; sz += tmp[2];
                n++;
            }
        }
        if (n == 0) {
            out[0] = RegionAddress.centerX(level, rx);
            out[1] = RegionAddress.centerY(level, ry, minY);
            out[2] = RegionAddress.centerZ(level, rz);
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
    private static int nearestFragment(RegionGrid grid, int level, int rx, int ry, int rz, BlockPos floor) {
        return fragmentOfLevel(grid, level, rx, ry, rz, floor.getX(), floor.getY(), floor.getZ(),
                new int[3], new int[3]);
    }

    /**
     * The <b>start</b> fragment for the search: at level 0, the fragment that actually <b>contains</b> the bot's
     * floor cell (flood-from-bot, PERF-DESIGN region §4) rather than the one whose centroid is nearest. A bot at
     * the bottom of a tall fragment is closer to a small sibling pocket's centroid than to the giant mass's (high)
     * centroid, so {@link #nearestFragment} mis-assigns it — and the search then prices/emits the wrong region's
     * face crossings (the cave repro's phantom {@code +X} dig-through). Falls back to nearest-centroid when the
     * flood is inconclusive (cell not in an occupiable fragment, region collapsed, section not resident) or at
     * coarse levels (no backing section — flood only resolves leaf-scale membership).
     */
    private static int startFragment(RegionGrid grid, int level, int rx, int ry, int rz, BlockPos floor) {
        if (level == 0) {
            int f = grid.startFragmentByFlood(rx, ry, rz, floor.getX(), floor.getY(), floor.getZ());
            if (f >= 0) {
                return f;
            }
        }
        return nearestFragment(grid, level, rx, ry, rz, floor);
    }

    /** Ensure the node {@code (level, rx,ry,rz)} is built: leaf build at level 0, fragment merge above. */
    private static void ensureNode(RegionGrid grid, int level, int rx, int ry, int rz) {
        if (level == 0) {
            grid.ensureLeaf(rx, ry, rz);
        } else {
            grid.ensureLevel(level, rx, ry, rz);
        }
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
        return fragmentOfLevel(grid, 0, rx, ry, rz, fx, fy, fz, cent, tmp);
    }

    /**
     * Level-aware {@link #fragmentOf}: the fragment of node {@code (level, rx,ry,rz)} whose centroid is nearest
     * the world cell {@code (fx,fy,fz)}. {@code 0} for a uniform/collapsed/unbuilt node. The coarse scale-guard
     * branch resolves the start/goal fragment at the coarse level through this; the public level-0 face is the
     * driver's per-tick membership probe.
     */
    private static int fragmentOfLevel(RegionGrid grid, int level, int rx, int ry, int rz,
                                       int fx, int fy, int fz, int[] cent, int[] tmp) {
        RegionFragments rf = grid.fragmentRecord(level, rx, ry, rz);
        if (isUniformNode(rf)) return 0;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int f = 0; f < rf.fragmentCount(); f++) {
            fragmentCentroidWorld(level, rf, f, grid.minY(), rx, ry, rz, cent, tmp);
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
        int[] entryFace;        // face (0..5) / ENTRY_START / ENTRY_INTERIOR this node was entered through (§2)
        boolean[] dig;          // parent edge is a Fix-1 dig-through (buried crossing cell) — §5 consumer tag
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

        // ---- reusable per-search world-coord scratch (hoisted out of planLevelFragments; no per-plan alloc) ----
        final int[] wa = new int[3];
        final int[] wb = new int[3];
        final int[] wc = new int[3];

        Nodes(int nodeHint, int mapCap) {
            key = new long[nodeHint];
            x = new int[nodeHint];
            y = new int[nodeHint];
            z = new int[nodeHint];
            frag = new int[nodeHint];
            entryFace = new int[nodeHint];
            dig = new boolean[nodeHint];
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

        /**
         * Row for {@code k}, creating it ({@code g=+inf}, unlinked) if absent. One probe. {@code cf} = fragment
         * id; {@code cface} = the entry face (§2), stored on first creation (it is part of {@code k}, so a row's
         * entry face is fixed for its lifetime).
         */
        int intern(long k, int cx, int cy, int cz, int cf, int cface) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) {
                    row = newRow(k, cx, cy, cz, cf, cface);
                    mapKey[slot] = k;
                    mapRow[slot] = row;
                    if (++mapSize >= mapGrowAt) growMap();
                    return row;
                }
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        private int newRow(long k, int cx, int cy, int cz, int cf, int cface) {
            int n = count;
            if (n == key.length) growNodes();
            key[n] = k;
            x[n] = cx; y[n] = cy; z[n] = cz;
            frag[n] = cf;
            entryFace[n] = cface;
            dig[n] = false;
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
            entryFace = Arrays.copyOf(entryFace, cap);
            dig = Arrays.copyOf(dig, cap);
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
