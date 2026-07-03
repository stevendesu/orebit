package com.orebit.mod.worldmodel.hpa;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.platform.LevelBounds;
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
        return new RegionGrid(minY);
    }

    private RegionGrid(int minY) {
        this.level = null;
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
        // Headless test seam ({@link #headless}): with no backing level there is no NavStore to build from, so
        // a not-pre-seeded row stays unbuilt and the planner reads the §6 optimistic default.
        if (level == null) {
            return;
        }
        // Only build if the chunk's nav data is resident and this vertical section exists; otherwise leave the
        // node unbuilt (fragment reads fall back to the §6 default).
        NavSection[] column = NavStore.get(level, NavStore.key(rx, rz));
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
