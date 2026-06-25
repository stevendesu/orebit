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
        // Only build if the chunk's nav data is resident and this vertical section exists; otherwise leave
        // the node unbuilt (faceCost falls back to the §6 default).
        NavSection[] column = NavStore.get(level, NavStore.key(rx, rz));
        if (column == null || ry < 0 || ry >= column.length || column[ry] == null) {
            return;
        }
        // LeafCostComputer takes (level, rx, rz, ryLevel0, pyramid) — note the rz/ry argument ORDER.
        LeafCostComputer.computeLeaf(level, rx, rz, ry, pyramid);
    }

    // ---------------------------------------------------------------------------------------------------
    // Face-cost query — the single chokepoint (3d default policy)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The dequantized tick cost of crossing from {@code face} (0..5, canonical {@link RegionAddress} order)
     * to the center of node {@code (level, rx, ry, rz)} — the <b>single chokepoint</b> the region A* reads.
     *
     * <p>Returns the built cost if the node was actually computed; otherwise the optimistic admissible
     * default (HPA-IMPLEMENTATION.md §6): {@code AIR_TRANSIT_TICKS · (side / LEAF_SIZE)}, i.e. a free walk
     * across the node, scaled by its side. The default keeps the region heuristic admissible over
     * unexplored/unloaded terrain; the live grid refines it on approach.
     *
     * <p>Allocation-free: a single {@link CostPyramid#rowIfPresent} probe (no intern, no boxing) plus a
     * dequantize, or the arithmetic default.
     */
    public float faceCost(int level, int rx, int ry, int rz, int face) {
        int row = pyramid.rowIfPresent(level, rx, ry, rz);
        if (row != -1 && pyramid.isBuilt(level, row)) {
            // A built coarse node can still carry BUCKET_INF on a face no bordering child built up
            // (PyramidMerger §7): treat that as "unknown → use the optimistic default", NOT impassable.
            // Leaf faces are never BUCKET_INF (LeafCostComputer §5), so level-0 reads never hit this.
            if (pyramid.faceBucket(level, row, face) != CostCodec.BUCKET_INF) {
                return pyramid.faceCost(level, row, face);
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
}
