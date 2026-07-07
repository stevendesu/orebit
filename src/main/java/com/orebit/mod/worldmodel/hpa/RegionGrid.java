package com.orebit.mod.worldmodel.hpa;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavStore;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.server.level.ServerLevel;

/**
 * Per-{@link ServerLevel} façade over the HPA* region tier — the region-tier analog of
 * {@link com.orebit.mod.worldmodel.pathing.NavGridView NavGridView}
 * (PRD §6.3–6.5, §7.1, §10 Phase 3; HPA-IMPLEMENTATION.md §1 "RegionGrid", §5/§6).
 *
 * <h2>Role</h2>
 * One {@code RegionGrid} exists per dimension. It owns that dimension's {@link CostPyramid} and the live
 * {@code minY} floor, builds level-0 leaves lazily from the resident {@link NavStore} nav grid, and is the
 * <b>single chokepoint</b> through which the region A* ({@code RegionPathfinder},
 * HPA-IMPLEMENTATION.md §8) reads a node's face costs. It hides two policies from the planner:
 * <ul>
 *   <li><b>Lazy build</b> ({@link #ensureLeaf}): a level-0 node's {@link RegionFragments} record is computed
 *       (via {@link FragmentLeafComputer}) only when its backing chunk is actually loaded in {@link NavStore}
 *       and the node is not yet built; otherwise the node is left unbuilt and the default is used. The planner
 *       calls {@code ensureLeaf} before reading a leaf's fragments.</li>
 *   <li><b>Optimistic admissible default</b> (the fragment reads {@link #kind}/{@link #fragmentRecord},
 *       HPA-FRAGMENTS.md §6): a planner read of a node that is interned-but-{@code !built} (no nav data /
 *       unloaded) returns a uniform AIR region — the cheapest, most permissive kind. Optimism is required: a
 *       pessimistic default would make the region heuristic inadmissible and could refuse a real route; the
 *       live nav grid refines it on approach (§6).</li>
 * </ul>
 *
 * <h2>Cache</h2>
 * Instances are interned by {@code ServerLevel} in a static {@link ConcurrentHashMap}, mirroring
 * {@link NavStore}'s {@code BY_LEVEL} map, so the follower can fetch the dimension's grid with
 * {@code RegionGrid.of(level)} (HPA-IMPLEMENTATION.md §10) without threading one through. A dimension's
 * grid is created on first {@link #of} and lives until {@link #drop}/{@link #clear} (level/server unload).
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * The planner's hot read path (the fragment accessors) allocates nothing: it interns/looks up a row in the
 * {@link CostPyramid}'s open-addressed {@code long}→row map (no boxing) and returns the resident
 * {@link RegionFragments} record. The {@code minY} floor is resolved once at construction through the
 * {@link LevelBounds} platform seam, never inlined into the loop. {@code RegionGrid} holds no per-search state,
 * so it is safe to share across concurrent reads of distinct rows; the lazy {@link #ensureLeaf} build runs
 * single-threaded on the tick/planner thread.
 */
public final class RegionGrid {

    /** One grid per dimension, interned by level (mirrors {@link NavStore}'s {@code BY_LEVEL}). */
    private static final Map<ServerLevel, RegionGrid> BY_LEVEL = new ConcurrentHashMap<>();

    /**
     * The dimension's grid, creating it on first touch. Cached for the lifetime of the level
     * (HPA-IMPLEMENTATION.md §10: {@code RegionGrid.of(level)} cached per {@code ServerLevel}).
     */
    public static RegionGrid of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, RegionGrid::new);
    }

    /**
     * The dimension's grid <b>if already created</b>, else {@code null} — a non-creating probe. Used by
     * {@code HpaMaintenance} (HPA-IMPLEMENTATION.md §12): a block change fires off the worldgen thread, where
     * calling the creating {@link #of} would wrongly materialize a pyramid for a dimension nobody has planned
     * in. Maintenance dirties leaves regardless but only recomputes them when a live grid exists.
     */
    public static RegionGrid peek(ServerLevel level) {
        return BY_LEVEL.get(level);
    }

    /** Drop a dimension's grid (on level unload). */
    public static void drop(ServerLevel level) {
        BY_LEVEL.remove(level);
    }

    /** Drop every dimension's grid (on server stop). */
    public static void clear() {
        BY_LEVEL.clear();
    }

    // ---------------------------------------------------------------------------------------------------

    private final ServerLevel level;
    /**
     * Headless section backing ({@link #headless(int, ConcurrentHashMap)}): when non-null, the three section
     * resolvers ({@link #rebuildLeaf}/{@link #startFragmentByFlood}/{@link #navtypeAt}) read their
     * {@link NavSection} columns from THIS map instead of {@link NavStore} — so the live-path region tier
     * (dig-flood, start-flood, leaf build) can be driven with no {@code ServerLevel}. Always {@code null} in
     * production (the loader interns via {@link #of}) and in the record-only headless seam ({@link #headless(int)}).
     * {@link NavStore} is keyed by {@code ServerLevel} and can't take a null key, which is exactly why the
     * headless full-search seam carries its own per-grid section map rather than a synthetic level token.
     */
    private final ConcurrentHashMap<Long, NavSection[]> sections;
    private final CostPyramid pyramid;
    /**
     * The dimension's resource-tally store — a parallel SoA layer on the SAME fixed-grid octree as
     * {@link #pyramid} (find-mine-resources design §3). Same lifecycle: created with the grid, dropped with it
     * ({@link #drop}/{@link #clear} remove the whole {@code RegionGrid} from {@code BY_LEVEL}, so no separate
     * teardown is needed). Not yet written by chunk-load — that is phase 4.
     */
    private final ResourcePyramid resourcePyramid;
    /** Dimension floor, resolved once through the {@link LevelBounds} seam (overworld −64). */
    private final int minY;

    private RegionGrid(ServerLevel level) {
        this.level = level;
        this.sections = null;
        this.pyramid = new CostPyramid();
        this.resourcePyramid = new ResourcePyramid();
        this.minY = LevelBounds.minY(level);
    }

    /**
     * Headless test seam (HPA-FRAGMENTS.md §S3): a grid with <b>no backing {@link ServerLevel}</b> and an
     * explicit {@code minY}, so the fragment-model region A* can be exercised over a hand-seeded
     * {@link CostPyramid} (seed via {@link #pyramid()} + {@link CostPyramid#rowFor}/{@code ensureFragments}/
     * {@code setBuilt}) without standing up a live level under the Knot test classloader (which is impossible —
     * see {@code HpaMilestoneTest}). With a {@code null} level {@link #ensureLeaf} cannot build from the
     * {@link NavStore} and is a no-op for any row not pre-seeded (the planner then reads the §6 optimistic
     * default for that region). <b>Not used in production</b> (the loader always interns via {@link #of}).
     */
    public static RegionGrid headless(int minY) {
        return new RegionGrid(minY, null);
    }

    /**
     * Full-search headless seam (PERF-DESIGN full-search bench §8 step 1): a grid with <b>no backing
     * {@link ServerLevel}</b> but a hand-authored {@code sections} map (the same
     * {@code ConcurrentHashMap<Long, NavSection[]>} the block tier's synthetic
     * {@link com.orebit.mod.worldmodel.pathing.NavGridView#NavGridView(int, ConcurrentHashMap)} seam consumes).
     * Unlike {@link #headless(int)} — which leaves every leaf unbuilt and relies on the §6 optimistic default —
     * this variant lets {@link #rebuildLeaf} flood REAL fragments from the sections and lets the goal dig-flood /
     * start-flood engage, so the whole live-gameplay region path can be exercised headlessly (a regression the
     * record-only headless grid can't see). Feed the SAME map to {@code new NavGridView(minY, sections)} so the
     * block window search reads the same terrain. <b>Not used in production.</b>
     */
    public static RegionGrid headless(int minY, ConcurrentHashMap<Long, NavSection[]> sections) {
        return new RegionGrid(minY, sections);
    }

    private RegionGrid(int minY, ConcurrentHashMap<Long, NavSection[]> sections) {
        this.level = null;
        this.sections = sections;
        this.pyramid = new CostPyramid();
        this.resourcePyramid = new ResourcePyramid();
        this.minY = minY;
    }

    /** The dimension's cost store (for the maintenance hook — {@link PyramidMerger} / {@code HpaMaintenance}). */
    public CostPyramid pyramid() {
        return pyramid;
    }

    /**
     * The dimension's resource-tally store — the parallel {@link ResourcePyramid} on the same fixed grid
     * (find-mine-resources §3). Seeded by the phase-4 tally-on-classify hook and rolled up by
     * {@link com.orebit.mod.worldmodel.resource.ResourceMerger}; read by the drill-down query (phase 5).
     */
    public ResourcePyramid resourcePyramid() {
        return resourcePyramid;
    }

    /** The dimension floor (vertical origin for region {@code ry}; overworld −64). */
    public int minY() {
        return minY;
    }

    /** The dimension this grid backs. */
    public ServerLevel level() {
        return level;
    }

    // ---------------------------------------------------------------------------------------------------
    // Lazy leaf build (3b/3d)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Ensure the level-0 leaf at region coords {@code (rx, ry, rz)} is built, computing it lazily.
     *
     * <p>If the node is already built (its fragments were computed), this is a no-op. Otherwise, if the backing
     * chunk is loaded in {@link NavStore} <b>and</b> the {@code ry}-indexed 16³ section exists,
     * {@link FragmentLeafComputer#computeLeaf} floods the node's {@link RegionFragments} record and marks it
     * built. If the chunk/section is not loaded, the node is left unbuilt — the planner then reads the
     * optimistic admissible default (a uniform AIR region, HPA-FRAGMENTS.md §6). Cheaply rejects already-built
     * nodes via {@link CostPyramid#rowIfPresent} (no intern) before doing any {@link NavStore} lookup.
     *
     * @param rx level-0 region X (== chunk X; world {@code wx >> 4})
     * @param ry level-0 vertical region index, from the dimension floor ({@code (worldY - minY) >> 4})
     * @param rz level-0 region Z (== chunk Z)
     */
    public void ensureLeaf(int rx, int ry, int rz) {
        int row = pyramid.rowIfPresent(0, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(0, row)) {
            return; // already computed
        }
        rebuildLeaf(rx, ry, rz);
    }

    /**
     * <b>Force</b>-(re)build leaf {@code (rx,ry,rz)}'s {@link RegionFragments} record from the current
     * {@link NavStore} section — <b>ignoring any existing built flag</b>. This is the recompute seam for
     * {@link com.orebit.mod.worldmodel.hpa.HpaMaintenance}: a chunk's nav being (re)built, or a leaf marked
     * dirty by a block change, must recompute the leaf. (The lazy {@link #ensureLeaf} is just this behind an
     * already-built short-circuit.) No-op if the section isn't resident (the node stays unbuilt → the planner
     * reads the §6 optimistic default).
     */
    public void rebuildLeaf(int rx, int ry, int rz) {
        // Record-only headless seam ({@link #headless(int)}): no backing level AND no sections map, so there is
        // nothing to build from — a not-pre-seeded row stays unbuilt and the planner reads the §6 optimistic
        // default. The full-search headless seam ({@link #headless(int, ConcurrentHashMap)}) DOES have sections,
        // so it falls through and builds real fragments from them.
        if (level == null && sections == null) {
            return;
        }
        // Only build if the chunk's nav data is resident and this vertical section exists; otherwise leave the
        // node unbuilt (fragment reads fall back to the §6 default).
        NavSection[] column = columnAt(rx, rz);
        if (column == null || ry < 0 || ry >= column.length || column[ry] == null) {
            return;
        }
        // Flood-fill the section's connectivity into the row's RegionFragments record (HPA-FRAGMENTS.md §3, §5).
        int r = pyramid.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyramid.ensureFragments(0, r);
        FragmentLeafComputer.computeLeaf(column[ry], rf);
        pyramid.setBuilt(0, r, true);
    }

    /**
     * Ensure the <b>coarse</b> node at {@code (level>0, rx, ry, rz)} is built — the region-A* read seam for a
     * level the cascade touches (HPA-FRAGMENTS.md §S5).
     *
     * <p>Already-built ⇒ no-op. Otherwise recompute it <b>from its direct children</b> via
     * {@link PyramidMerger#combineFragments} (a single level — no recursion, so bounded at 8/4 child reads).
     * In production the fragment pyramid is kept built bottom-up by {@link HpaMaintenance} (every leaf (re)build
     * walks {@link PyramidMerger#mergeUpFragments} to the root), so every coarse ancestor of loaded terrain is
     * already built and this is the no-op fast path; this opportunistic build is the belt-and-suspenders for a
     * node whose children exist but whose parent was never merged, and the on-demand path for the headless
     * tests. A node with no built child stays unbuilt ⇒ the planner reads the §6 optimistic default. No-op for
     * {@code level <= 0} (use {@link #ensureLeaf}).
     */
    public void ensureLevel(int level, int rx, int ry, int rz) {
        if (level <= 0) {
            return;
        }
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return; // already merged (the maintenance-built fast path)
        }
        row = pyramid.rowFor(level, rx, ry, rz);
        PyramidMerger.combineFragments(pyramid, level, row, rx, ry, rz);
    }

    /**
     * The kept fragment id (PERF-DESIGN region §4 flood-from-bot) that <b>contains</b> world cell
     * {@code (wx,wy,wz)} in its level-0 leaf {@code (rx,ry,rz)}, reproduced by re-flooding the leaf's resident
     * {@link NavSection} ({@link FragmentLeafComputer#fragmentContaining}). Returns {@code -1} when the cell isn't
     * in an occupiable fragment, the region collapsed, or the section isn't resident (or this is a headless grid)
     * — the caller (region-A* start-fragment resolution) then falls back to nearest-centroid.
     *
     * <p>Level-0 only: coarse levels have no backing section, and the mis-assignment this fixes is a leaf-scale
     * connectivity fact (a bot standing in one component being nearer a sibling component's centroid). The caller
     * gates on {@code level == 0}.
     */
    public int startFragmentByFlood(int rx, int ry, int rz, int wx, int wy, int wz) {
        if (level == null && sections == null) {
            return -1;
        }
        NavSection[] column = columnAt(rx, rz);
        if (column == null || ry < 0 || ry >= column.length || column[ry] == null) {
            return -1;
        }
        // Section-local coords: origin is (rx<<4, minY + ry<<4, rz<<4); lx/lz wrap to the low nibble, ly measured
        // from the dimension floor so a non-16-aligned minY still lands in [0,16).
        return FragmentLeafComputer.fragmentContaining(column[ry], wx & 15, (wy - minY) & 15, wz & 15);
    }

    /** Sink for {@link #goalDigSeeds}: one dig-reachable pocket — its level-0 region {@code (rx,ry,rz)} and kept
     *  fragment id, plus the {@code digCells} of breakable rock between that pocket and the goal cell. */
    @FunctionalInterface
    public interface DigSeedSink {
        void accept(int rx, int ry, int rz, int frag, int digCells);
    }

    private static final int[] DIG_DX = { 1, -1, 0, 0, 0, 0 };
    private static final int[] DIG_DY = { 0, 0, 1, -1, 0, 0 };
    private static final int[] DIG_DZ = { 0, 0, 0, 0, 1, -1 };

    /**
     * Enumerate the occupiable pockets a <b>buried goal cell</b> can be reached from by digging — the goal-side
     * analog of {@link #startFragmentByFlood} (the s48 flood-from-bot start fix). A buried ore is a SOLID cell in
     * no fragment; {@code nearestFragment} then mis-assigns the goal to the nearest air pocket by centroid, which
     * may not be the pocket 2 blocks from the ore. Instead: BFS outward from the goal cell through <b>breakable</b>
     * solid (6-connected, ≤ {@code maxCells} deep), and every time the front touches a passable cell, report that
     * cell's pocket and the dig distance. The reverse-Dijkstra cost-to-goal field then <b>multi-source seeds</b>
     * every reported pocket at its dig cost — so a goal reachable from several pockets is modelled exactly (no
     * arbitrary "which fragment" pick), and the flood picks the bot-side-cheapest entry when the block search reads
     * the field. An <b>exposed</b> goal (already passable) reports its own fragment at {@code digCells == 0}.
     *
     * <p>Loaded-section only (near-bot level-0 pathfinding): reads the resident {@link NavSection}s per cell, so it
     * naturally crosses region boundaries and stops at any unloaded/unbreakable wall. Reports nothing (caller falls
     * back to nearest-centroid) when the goal cell's section isn't resident. Cold path (once per plan): a bounded
     * BFS over ≤ {@code maxCells} cells with small transient collections — no per-node-search cost.
     */
    public void goalDigSeeds(int gx, int gy, int gz, int maxCells, DigSeedSink sink) {
        if (level == null && sections == null) {
            return;
        }
        int goalNav = navtypeAt(gx, gy, gz);
        if (goalNav < 0) {
            return; // goal section not resident → caller falls back to nearest-centroid
        }
        if (NavBlock.isPassable(NavBlock.descriptor((short) goalNav))) {
            // Exposed goal: already in a pocket — seed that fragment at zero dig.
            int f = startFragmentByFlood(gx >> 4, (gy - minY) >> 4, gz >> 4, gx, gy, gz);
            if (f >= 0) {
                sink.accept(gx >> 4, (gy - minY) >> 4, gz >> 4, f, 0);
            }
            return;
        }
        // Buried goal: BFS through breakable-solid cells; each passable neighbour is a pocket seed at its dig
        // distance. Uniform edge cost ⇒ BFS gives the min dig distance; coords are packed relative to the goal
        // (|Δ| ≤ maxCells, so 8 bits/axis) to key the visited/dist map without boxing full world coords.
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        seen.add(relKey(0, 0, 0));
        queue.add(new int[] { gx, gy, gz, 1 }); // dist 1: breaking the goal cell itself is the first dug block
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int cx = c[0], cy = c[1], cz = c[2], d = c[3];
            for (int face = 0; face < 6; face++) {
                int nx = cx + DIG_DX[face], ny = cy + DIG_DY[face], nz = cz + DIG_DZ[face];
                if (!seen.add(relKey(nx - gx, ny - gy, nz - gz))) {
                    continue;
                }
                int nav = navtypeAt(nx, ny, nz);
                if (nav < 0) {
                    continue; // unloaded / out of bounds — treat as an impassable wall
                }
                long desc = NavBlock.descriptor((short) nav);
                if (NavBlock.isPassable(desc)) {
                    int f = startFragmentByFlood(nx >> 4, (ny - minY) >> 4, nz >> 4, nx, ny, nz);
                    if (f >= 0) {
                        sink.accept(nx >> 4, (ny - minY) >> 4, nz >> 4, f, d);
                    }
                    continue; // don't dig into air
                }
                // Solid: dig on only if the block is breakable and we're within the dig budget.
                if (NavBlock.isBreakable(desc) && d < maxCells) {
                    queue.add(new int[] { nx, ny, nz, d + 1 });
                }
            }
        }
    }

    /** The navtype index at world cell {@code (wx,wy,wz)}, or {@code -1} if its section isn't resident. */
    private int navtypeAt(int wx, int wy, int wz) {
        int rx = wx >> 4, rz = wz >> 4, ry = (wy - minY) >> 4;
        NavSection[] column = columnAt(rx, rz);
        if (column == null || ry < 0 || ry >= column.length || column[ry] == null) {
            return -1;
        }
        return column[ry].getNavtype(wx & 15, (wy - minY) & 15, wz & 15);
    }

    /**
     * Resolve the {@link NavSection} column at chunk {@code (rx,rz)} from whichever backing this grid has: the
     * hand-authored {@link #sections} map (headless full-search seam) when present, else the live {@link NavStore}
     * keyed by {@link #level}. Only reached after the caller's {@code level == null && sections == null} guard, so
     * exactly one backing is non-null. Returns {@code null} when that backing has no column for the chunk.
     */
    private NavSection[] columnAt(int rx, int rz) {
        long key = NavStore.key(rx, rz);
        return sections != null ? sections.get(key) : NavStore.get(level, key);
    }

    /** Pack a goal-relative cell offset (each in [-127,127], guaranteed by the {@code maxCells} bound) into an int. */
    private static int relKey(int dx, int dy, int dz) {
        return ((dx + 128) << 16) | ((dy + 128) << 8) | (dz + 128);
    }

    // ---------------------------------------------------------------------------------------------------
    // Fragment-model reads (HPA-FRAGMENTS.md §2, §5) — the chokepoint the region A* reads. Each applies an
    // optimistic default-on-miss policy: an interned-but-unbuilt (or never-touched) node reads as a uniform AIR
    // region — the cheapest, most permissive kind — so the region heuristic stays admissible and never refuses a
    // real route through unexplored terrain (the live nav grid refines it on approach, §6).
    // ---------------------------------------------------------------------------------------------------

    /**
     * The {@link RegionFragments} kind of node {@code (level, rx, ry, rz)}, or the optimistic
     * {@link RegionFragments#KIND_AIR} for an unbuilt/unloaded node (free-traverse default-on-miss).
     */
    public int kind(int level, int rx, int ry, int rz) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return pyramid.kind(level, row);
        }
        return RegionFragments.KIND_AIR;
    }

    /** Mean SOLID-cell hardness nibble (the mine-edge cost scale); 0 (softest) default-on-miss. */
    public int avgHardness(int level, int rx, int ry, int rz) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return pyramid.avgHardness(level, row);
        }
        return 0;
    }

    /** Passable-cell fraction nibble (collapsed/uniform crossing cost scale); 15 (fully open) default-on-miss. */
    public int passFrac(int level, int rx, int ry, int rz) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return pyramid.passFrac(level, row);
        }
        return 15;
    }

    /** Number of fragment records on this node; 0 (uniform mass) default-on-miss. */
    public int fragments(int level, int rx, int ry, int rz) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return pyramid.fragments(level, row);
        }
        return 0;
    }

    /**
     * The packed 2D-bbox footprint of {@code frag} on {@code face} of node {@code (level, rx, ry, rz)}, or
     * {@link RegionFragments#NO_FACE} if the fragment does not touch that face / the node is unbuilt. Decode
     * with the {@code RegionFragments.footprintMin/Max U/V} statics.
     */
    public int faceFootprint(int level, int rx, int ry, int rz, int frag, int face) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return pyramid.faceFootprint(level, row, frag, face);
        }
        return RegionFragments.NO_FACE;
    }

    /**
     * The built {@link RegionFragments} record of node {@code (level, rx, ry, rz)}, or {@code null} if the
     * node is unbuilt/unloaded — the object accessor the region A* uses to iterate a node's fragments without
     * a per-field call. The caller treats {@code null} as the optimistic uniform-AIR default (the per-field
     * accessors above bake that in).
     */
    public RegionFragments fragmentRecord(int level, int rx, int ry, int rz) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            return pyramid.fragmentRecord(level, row);
        }
        return null;
    }
}
