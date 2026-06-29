package com.orebit.mod.worldmodel.hpa;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavStore;

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
 *   <li><b>Lazy build</b> ({@link #ensureLeaf}): a level-0 node is computed (via {@link LeafCostComputer})
 *       only when its backing chunk is actually loaded in {@link NavStore} and the node is not yet built;
 *       otherwise the node is left unbuilt and the default is used. The planner calls {@code ensureLeaf}
 *       before reading a leaf's faces.</li>
 *   <li><b>Optimistic admissible default</b> ({@link #faceCost}, HPA-IMPLEMENTATION.md §6): a planner read
 *       of a node that is interned-but-{@code !built} (no nav data / unloaded) returns
 *       {@code AIR_TRANSIT_TICKS · (side / LEAF_SIZE)} — a free-walk-across estimate scaled by the node's
 *       side. Optimism is required: an over-estimate would make the region heuristic inadmissible and could
 *       refuse a real route; the live nav grid refines the cost on approach (§6).</li>
 * </ul>
 *
 * <h2>Cache</h2>
 * Instances are interned by {@code ServerLevel} in a static {@link ConcurrentHashMap}, mirroring
 * {@link NavStore}'s {@code BY_LEVEL} map, so the follower can fetch the dimension's grid with
 * {@code RegionGrid.of(level)} (HPA-IMPLEMENTATION.md §10) without threading one through. A dimension's
 * grid is created on first {@link #of} and lives until {@link #drop}/{@link #clear} (level/server unload).
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * The planner's hot read path ({@link #faceCost}) allocates nothing: it interns/looks up a row in the
 * {@link CostPyramid}'s open-addressed {@code long}→row map (no boxing) and returns a {@code float}. The
 * {@code minY} floor is resolved once at construction through the {@link LevelBounds} platform seam, never
 * inlined into the loop. {@code RegionGrid} holds no per-search state, so it is safe to share across
 * concurrent reads of distinct rows; the lazy {@link #ensureLeaf} build runs single-threaded on the
 * tick/planner thread (as {@link LeafCostComputer} requires).
 */
public final class RegionGrid {

    /**
     * Master A/B switch for the HPA* <b>fragment model</b> (HPA-FRAGMENTS.md): the connectivity-aware region
     * tier ({@link RegionFragments} / {@link FragmentBuilder} / {@link FragmentLeafComputer}) that replaces
     * the single-center-node model ({@link LeafCostComputer}'s six face→center buckets).
     *
     * <p><b>Default {@code false} ⇒ byte-for-byte the current center-model behaviour, zero regression.</b>
     * When {@code true}, the leaf build + region reads select the fragment path. The center-model path is
     * never deleted; the user flips this in-game to A/B the two models (HPA-FRAGMENTS.md §S6).
     *
     * <p>At S1 this flag has no live consumer yet (the fragment computer is additive and unwired); it is
     * read by the S2 store wiring ({@link #ensureLeaf} / {@link #faceCost}) and beyond.
     */
    public static boolean HPA_FRAGMENTS = true;

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
    /** Dimension floor, resolved once through the {@link LevelBounds} seam (overworld −64). */
    private final int minY;

    private RegionGrid(ServerLevel level) {
        this.level = level;
        this.pyramid = new CostPyramid();
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
        this.minY = minY;
    }

    /** The dimension's cost store (for the maintenance hook — {@link PyramidMerger} / {@code HpaMaintenance}). */
    public CostPyramid pyramid() {
        return pyramid;
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
     * <p>If the node is already built (its faces were computed), this is a no-op. Otherwise, if the backing
     * chunk is loaded in {@link NavStore} <b>and</b> the {@code ry}-indexed 16³ section exists,
     * {@link LeafCostComputer#computeLeaf} fills the node's six face→center costs and marks it built. If the
     * chunk/section is not loaded, the node is left unbuilt — the planner then reads the optimistic
     * admissible default from {@link #faceCost} (HPA-IMPLEMENTATION.md §6). Cheaply rejects already-built
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
     * <b>Force</b>-(re)build leaf {@code (rx,ry,rz)} from the current {@link NavStore} section, honoring
     * {@link #HPA_FRAGMENTS} — <b>ignoring any existing built flag</b>. This is the recompute seam for
     * {@link com.orebit.mod.worldmodel.hpa.HpaMaintenance}: a chunk's nav being (re)built, or a leaf marked
     * dirty by a block change, must recompute the leaf <i>under the active model</i>. (The lazy
     * {@link #ensureLeaf} is just this behind an already-built short-circuit.) No-op if the section isn't
     * resident (the node stays unbuilt → the planner reads the §6 optimistic default).
     *
     * <p><b>Why this exists:</b> the maintenance/eager-build path previously always ran the center-model
     * {@link LeafCostComputer} and marked the row built — so with {@code HPA_FRAGMENTS} on it poisoned the row
     * with a center build and <i>no</i> {@link RegionFragments} record; {@code ensureLeaf} then saw "built" and
     * skipped, leaving {@link #kind} reading the default AIR forever (a flat, free, straight-through skeleton).
     */
    public void rebuildLeaf(int rx, int ry, int rz) {
        // Headless test seam ({@link #headless}): with no backing level there is no NavStore to build from, so
        // a not-pre-seeded row stays unbuilt and the planner reads the §6 optimistic default.
        if (level == null) {
            return;
        }
        // Only build if the chunk's nav data is resident and this vertical section exists; otherwise leave the
        // node unbuilt (faceCost / fragment reads fall back to the §6 default).
        NavSection[] column = NavStore.get(level, NavStore.key(rx, rz));
        if (column == null || ry < 0 || ry >= column.length || column[ry] == null) {
            return;
        }
        if (HPA_FRAGMENTS) {
            // Fragment model (HPA-FRAGMENTS.md §3, §5): flood-fill the section's connectivity into the row's
            // RegionFragments record, stored alongside the (now-unused) center buckets. No A* per face.
            int r = pyramid.rowFor(0, rx, ry, rz);
            RegionFragments rf = pyramid.ensureFragments(0, r);
            FragmentLeafComputer.computeLeaf(column[ry], rf);
            pyramid.setBuilt(0, r, true);
            return;
        }
        // Center model (default): LeafCostComputer takes (level, rx, rz, ryLevel0, pyramid) — note the
        // rz/ry argument ORDER.
        LeafCostComputer.computeLeaf(level, rx, rz, ry, pyramid);
    }

    /**
     * Ensure the <b>coarse</b> node at {@code (level>0, rx, ry, rz)} is built under the fragment model
     * (HPA-FRAGMENTS.md §S5) — the region-A* read seam for a level the coarse scale-guard branch touches.
     *
     * <p>Already-built ⇒ no-op. Otherwise recompute it <b>from its direct children</b> via
     * {@link PyramidMerger#combineFragments} (a single level — no recursion, so bounded at 8/4 child reads).
     * In production the fragment pyramid is kept built bottom-up by {@link HpaMaintenance} (every leaf (re)build
     * walks {@link PyramidMerger#mergeUpFragments} to the root), so every coarse ancestor of loaded terrain is
     * already built and this is the no-op fast path; this opportunistic build is the belt-and-suspenders for a
     * node whose children exist but whose parent was never merged, and the on-demand path for the headless
     * tests. A node with no built child stays unbuilt ⇒ the planner reads the §6 optimistic default.
     *
     * <p>No-op for {@code level <= 0} (use {@link #ensureLeaf}) and under the center model
     * ({@code !HPA_FRAGMENTS}, where the coarse branch reads face buckets via {@link PyramidMerger#mergeLevel}).
     */
    public void ensureLevel(int level, int rx, int ry, int rz) {
        if (level <= 0 || !HPA_FRAGMENTS) {
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
    // Face-cost query — the single chokepoint (3d default policy)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The dequantized tick cost of moving between {@code face} (0..5, canonical {@link RegionAddress} order)
     * and the center of node {@code (level, rx, ry, rz)} in direction {@code dir} ({@link CostPyramid#ENTER}
     * face→center, {@link CostPyramid#EXIT} center→face) — the <b>single chokepoint</b> the region A* reads.
     * The direction matters because vertical air travel is asymmetric (cheap to fall in, expensive to pillar
     * out); a region crossing sums one node's {@code EXIT} half and the neighbour's {@code ENTER} half.
     *
     * <p>Returns the built cost if the node was actually computed; otherwise the optimistic admissible
     * default (HPA-IMPLEMENTATION.md §6): {@code AIR_TRANSIT_TICKS · (side / LEAF_SIZE)}, i.e. a free walk
     * across the node, scaled by its side — direction-agnostic, since an unexplored node's terrain is unknown
     * and optimism must hold both ways to keep the heuristic admissible.
     *
     * <p>Allocation-free: a single {@link CostPyramid#rowIfPresent} probe (no intern, no boxing) plus a
     * dequantize, or the arithmetic default.
     */
    public float faceCost(int level, int rx, int ry, int rz, int face, int dir) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            // A built coarse node can still carry BUCKET_INF on a face no bordering child built up
            // (PyramidMerger §7): treat that as "unknown → use the optimistic default", NOT impassable.
            // Leaf faces are never BUCKET_INF (LeafCostComputer §5), so level-0 reads never hit this.
            if (pyramid.faceBucket(level, row, face, dir) != CostCodec.BUCKET_INF) {
                return pyramid.faceCost(level, row, face, dir);
            }
        }
        return defaultFaceCost(level);
    }

    /**
     * The optimistic admissible default face cost at {@code level} (HPA-IMPLEMENTATION.md §6):
     * {@code AIR_TRANSIT_TICKS · (side / LEAF_SIZE)}. At level 0 this is {@code AIR_TRANSIT_TICKS} (one leaf
     * side); higher levels scale by the node's side in leaf-units. Quantize-then-dequantize so the default
     * lives on the same bucket lattice as stored costs (matches §6: {@code dequantize(quantize(...))}).
     */
    private static float defaultFaceCost(int level) {
        int sideInLeaves = 1 << level; // side / LEAF_SIZE = (LEAF_SIZE << level) / LEAF_SIZE
        float ticks = LeafCostComputer.AIR_TRANSIT_TICKS * sideInLeaves;
        return CostCodec.dequantize(CostCodec.quantize(ticks));
    }

    // ---------------------------------------------------------------------------------------------------
    // Fragment-model reads (HPA-FRAGMENTS.md §2, §5) — the chokepoint the region A* (S3) reads when
    // HPA_FRAGMENTS is on. Each applies the SAME optimistic default-on-miss policy as faceCost: an
    // interned-but-unbuilt (or never-touched) node reads as a uniform AIR region — the cheapest, most
    // permissive kind — so the region heuristic stays admissible and never refuses a real route through
    // unexplored terrain (the live nav grid refines it on approach, §6). With HPA_FRAGMENTS off these are
    // simply never called; the center-model faceCost path above is unchanged.
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
