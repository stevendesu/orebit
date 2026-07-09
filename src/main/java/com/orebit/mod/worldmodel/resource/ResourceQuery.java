package com.orebit.mod.worldmodel.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Best-first drill-down over the {@link ResourcePyramid} — the read side of the find-mine-resources arc
 * (design §6). Given a resource column, an anchor point and a quantity threshold, it answers "where near me
 * is this resource?" as an ordered list of level-0 (16³) region hits, nearest-first. Read-only: it never
 * interns a pyramid row (only {@link ResourcePyramid#rowIfPresent}) and mutates no state.
 *
 * <h2>Algorithm (design §6)</h2>
 * <ol>
 *   <li><b>Ascend the anchor's neighbourhood.</b> Walk levels 0..{@link ResourcePyramid#RESOURCE_TOP_LEVEL},
 *       at each looking at the anchor's cell <i>and its ±1 neighbours per axis</i> (a 3×3×3 box); stop at the
 *       tightest level where some neighbour cell has a decoded count {@code >= minCount} for the column, and
 *       seed the descend from every such cell. Reading the neighbourhood — not just the single containing cell —
 *       lets the search cross a region boundary the anchor abuts, the <b>world origin included</b> (region 0 and
 *       region -1 share no ancestor). If no level up to the top holds enough, return empty ("none known
 *       nearby").</li>
 *   <li><b>Best-first descend</b> from the seeded cells with a min-heap keyed by <b>squared distance from the
 *       region center to the anchor</b> (nearest first — the "near me / near P" ranking; {@code minCount} is
 *       the quantity filter). Pop the nearest node: at level 0 emit a {@link ResourceHit} (decoded count +
 *       the region's center block); otherwise push each child that has an interned row and a decoded count
 *       {@code >= minCount}. Continue until {@code maxResults} hits or the heap drains.</li>
 * </ol>
 *
 * <p>The descend is a pure octree/quadtree tree walk. The seed cells are distinct cells at one level, so their
 * subtrees are disjoint and every node still has exactly one parent — no node is reachable twice and <b>no
 * visited set is needed</b>. The octree&rarr;quadtree child-count transition is handled by {@link
 * RegionAddress#childCount} / {@link RegionAddress#childRY} (8 children at/below {@link RegionAddress#OCTREE_TOP},
 * 4 above), matching {@link ResourceMerger}.
 *
 * <p><b>Ordering is approximate-nearest.</b> A coarse region's center distance is not a strict lower bound
 * on its contained level-0 centers (a child near the anchor edge can be nearer than the parent center), so
 * hits are emitted in a best-first-by-center order rather than a strictly sorted one. Because the search stops
 * at the tightest level whose neighbourhood holds the resource, the seeded box is only a few cells across and
 * the approximation is tight in practice.
 *
 * <p><b>Scratch.</b> The priority queue is a <b>pooled per-thread struct-of-arrays binary min-heap</b>
 * ({@link Heap}, {@code long} keys + parallel {@code int} coord arrays, grow-by-doubling) held in a
 * {@link ThreadLocal} and cleared per call — no per-node allocation past warmup. The query runs on the tick
 * thread for milestone 1 (single-threaded, safe alongside the tick-thread pyramid writes); moving it to a
 * planner thread later needs the async-nav epoch discipline (design §8.4).
 *
 * <h2>Reach (design §8.2)</h2>
 * The neighbourhood ascend climbs to {@link ResourcePyramid#RESOURCE_TOP_LEVEL} (= {@link
 * RegionAddress#MAX_LEVEL}, a ~67M-block cell), so the drill-down reaches <b>true-global</b>: a resource the
 * bot classified anywhere — including <b>across the world-origin split</b>, in a sibling top cell (the 3×3
 * neighbourhood straddles the origin at every level, so region 0 and region -1 are searched together) — is
 * found, not only resources within the anchor's own 1024-block {@link RegionAddress#MAX_COARSE_LEVEL} ancestor.
 * Prospecting into <i>unloaded</i> chunks (walk-and-scan) remains a separate later arc — the compass only knows
 * loaded/classified regions.
 */
public final class ResourceQuery {

    private ResourceQuery() {}

    /** One level-0 (16³ region) drill-down result: the region coords, its approximate resource count
     *  (decoded from the log₂ tally) and the center block of the region (a coarse "go here" target — exact
     *  block cells come from an on-arrival section scan, design §7). */
    public record ResourceHit(int rx, int ry, int rz, int approxCount, BlockPos center) {}

    /**
     * Find the nearest level-0 regions holding {@code >= minCount} of {@code column} near {@code anchor} in
     * {@code level}'s dimension. Resolves the dimension's {@link ResourcePyramid} + {@code minY} and delegates
     * to the headless core {@link #find(ResourcePyramid, int, int, int, int, int, int, int)}.
     *
     * @param column an indexed resource column (0..{@link ResourceClasses#COLUMN_COUNT}-1), e.g. from
     *               {@link ResourceClasses#columnForName}
     * @return up to {@code maxResults} hits, nearest-first; empty if none known/loaded near the anchor
     */
    public static List<ResourceHit> find(ServerLevel level, int column, BlockPos anchor,
                                         int minCount, int maxResults) {
        RegionGrid grid = RegionGrid.of(level);
        return find(grid.resourcePyramid(), grid.minY(), column,
                anchor.getX(), anchor.getY(), anchor.getZ(), minCount, maxResults);
    }

    /**
     * Headless core — the drill-down over a {@link ResourcePyramid} with an explicit {@code minY} and
     * primitive anchor coords (no Minecraft world), so the algorithm is unit-testable off the game (mirrors
     * the MC-free phase 1-4 data tests). Validates {@code column} against {@link ResourcePyramid#COLUMNS}
     * (a compile-time constant, so this does not force {@link ResourceClasses} class-init / the block
     * registry — the pure data path stays MC-free).
     */
    public static List<ResourceHit> find(ResourcePyramid p, int minY, int column,
                                         int ax, int ay, int az, int minCount, int maxResults) {
        List<ResourceHit> hits = new ArrayList<>();
        if (p == null || column < 0 || column >= ResourcePyramid.COLUMNS || maxResults <= 0) return hits;
        final int need = Math.max(1, minCount);

        // 1. Ascend the anchor's NEIGHBOURHOOD (its cell ± 1 per axis — a 3×3×3 box) level by level, stopping at
        //    the tightest level where some neighbour cell holds >= need, and seed the descend from ALL such
        //    neighbour cells. Reading a neighbourhood rather than only the single containing cell is what lets
        //    the search cross a region boundary the anchor sits against — the WORLD ORIGIN included, where region
        //    0 and region -1 share NO ancestor at all (0 is a grid boundary at every level), so a single-ancestor
        //    ascend could never reach a resource just across it. The 3×3 straddles any nearby boundary, and
        //    ascending grows it, so farther resources are still reached (2-cells-away at level L is within ±1 at
        //    L+1). Ascend to RESOURCE_TOP_LEVEL (true-global). The seeds are distinct cells at one level ⇒
        //    disjoint subtrees ⇒ no duplicate hits (no visited set needed).
        final Heap heap = HEAP.get();
        boolean found = false;
        for (int lvl = 0; lvl <= ResourcePyramid.RESOURCE_TOP_LEVEL; lvl++) {
            heap.clear();
            if (seedNeighborhood(p, heap, lvl, ax, ay, az, minY, column, need)) { found = true; break; }
        }
        if (!found) return hits; // none known/loaded near the anchor (caller reports it)

        // 2. Best-first descend from the seeded neighbour cells, nearest-center-first.
        while (heap.size > 0 && hits.size() < maxResults) {
            heap.popMin();
            final int lvl = heap.oLevel, rx = heap.oRx, ry = heap.oRy, rz = heap.oRz;

            if (lvl == 0) {
                final int row = p.rowIfPresent(0, rx, ry, rz);
                if (row < 0) continue; // defensive (a level-0 node is only ever pushed with a present row)
                final int approx = Log2Codec.decode(p.getLog2(0, row, column));
                hits.add(new ResourceHit(rx, ry, rz, approx, new BlockPos(
                        RegionAddress.centerX(0, rx),
                        RegionAddress.centerY(0, ry, minY),
                        RegionAddress.centerZ(0, rz))));
                continue;
            }

            final int childLevel = lvl - 1;
            final int children = RegionAddress.childCount(lvl);
            for (int i = 0; i < children; i++) {
                final int crx = RegionAddress.childRX(rx, i);
                final int crz = RegionAddress.childRZ(rz, i);
                final int cry = RegionAddress.childRY(ry, i, lvl);
                final int row = p.rowIfPresent(childLevel, crx, cry, crz);
                if (row < 0) continue;                                        // absent child (no resource)
                if (Log2Codec.decode(p.getLog2(childLevel, row, column)) < need) continue; // quantity filter
                heap.push(distSq(childLevel, crx, cry, crz, minY, ax, ay, az), childLevel, crx, cry, crz);
            }
        }
        return hits;
    }

    /**
     * Push every cell in the anchor's 3×3×3 neighbourhood at {@code level} that holds {@code >= need} of
     * {@code column} onto {@code heap} (keyed by its center-distance to the anchor), returning whether any was
     * pushed. The neighbourhood — the anchor's cell ± 1 on each axis — is what makes {@link #find}
     * boundary-crossing: a resource just over a region edge (the world origin included) sits in a neighbour
     * cell, not the anchor's own. Vertical neighbours are clamped to the dimension's region rows
     * ({@link RegionAddress#verticalRegions}); at/above {@code OCTREE_TOP} there is a single vertical row so only
     * {@code ry == 0} is visited. Absent cells (no interned row) are skipped.
     */
    private static boolean seedNeighborhood(ResourcePyramid p, Heap heap, int level,
            int ax, int ay, int az, int minY, int column, int need) {
        final int aRx = RegionAddress.regionX(ax, level);
        final int aRz = RegionAddress.regionZ(az, level);
        final int aRy = RegionAddress.regionY(ay, level, minY);
        final int ryLo = Math.max(0, aRy - 1);
        final int ryHi = Math.min(RegionAddress.verticalRegions(level) - 1, aRy + 1);
        boolean any = false;
        for (int rx = aRx - 1; rx <= aRx + 1; rx++) {
            for (int rz = aRz - 1; rz <= aRz + 1; rz++) {
                for (int ry = ryLo; ry <= ryHi; ry++) {
                    final int row = p.rowIfPresent(level, rx, ry, rz);
                    if (row < 0) continue;
                    if (Log2Codec.decode(p.getLog2(level, row, column)) < need) continue;
                    heap.push(distSq(level, rx, ry, rz, minY, ax, ay, az), level, rx, ry, rz);
                    any = true;
                }
            }
        }
        return any;
    }

    /**
     * The <b>true-global</b> log₂ tally of {@code column} — the {@link Log2Codec#merge} fold of that column over
     * <b>every</b> interned row at {@link ResourcePyramid#RESOURCE_TOP_LEVEL}. Because a level-22 cell is
     * ~67M blocks, the ±30M world border spans at most 4 such top cells (straddling the origin), so this reads
     * only a handful of rows — the "everything the compass has seen anywhere" number, independent of where the
     * anchor is. Returns the raw log₂ byte (decode with {@link Log2Codec#decode}); {@code 0} if nothing known.
     */
    public static byte globalLog2(ResourcePyramid p, int column) {
        if (p == null || column < 0 || column >= ResourcePyramid.COLUMNS) return 0;
        final int top = ResourcePyramid.RESOURCE_TOP_LEVEL;
        final int rows = p.rowCount(top);
        byte acc = 0;
        for (int row = 0; row < rows; row++) {
            acc = Log2Codec.merge(acc, p.getLog2(top, row, column));
        }
        return acc;
    }

    /**
     * The approximate log₂ tally of {@code column} in a <b>box of half-width {@code radius} centered on
     * {@code (px,pz)}</b>, summed over the <b>full vertical column</b> — the "how much is near me" reading the
     * {@code /bot report} compass shows at each scale. It folds ({@link Log2Codec#merge}) every interned cell at
     * {@code level} that overlaps the horizontal box, across every vertical region index, so ores far below the
     * player still count.
     *
     * <h2>Why a box-sum, not the single containing cell</h2>
     * Reading the one grid cell the anchor sits in makes the number lurch every time the anchor crosses a cell
     * boundary — most jarringly at the world origin, where standing on {@code (1,1)} vs {@code (−1,−1)} lands in
     * different cells two blocks apart. Because a fixed grid <i>always</i> has that discontinuity, we don't try
     * to re-center the grid (a centered quadtree cannot nest cleanly — a cell centered on 0 must split at 0);
     * instead the <b>query</b> is centered on the player. A box centered on {@code (px,pz)} shifts smoothly with
     * the player, so two anchors a few blocks apart cover almost the same cells and report almost the same count
     * — stable near the origin, where players spend most of their time. The window spans a few cells per axis, so
     * a boundary crossing swaps only a fraction of the coverage rather than the whole answer.
     *
     * <p>Approximate by design (log₂ buckets, and the box is snapped to the cell grid so its true coverage is
     * within a cell of {@code radius}). Cold (command cadence); absent cells are skipped with no allocation.
     * Returns the raw log₂ byte (decode with {@link Log2Codec#decode}); {@code 0} if nothing known in range.
     */
    public static byte windowLog2(ResourcePyramid p, int px, int pz, int level, int radius, int column) {
        if (p == null || column < 0 || column >= ResourcePyramid.COLUMNS) return 0;
        final int rxLo = RegionAddress.regionX(px - radius, level);
        final int rxHi = RegionAddress.regionX(px + radius, level);
        final int rzLo = RegionAddress.regionZ(pz - radius, level);
        final int rzHi = RegionAddress.regionZ(pz + radius, level);
        final int vert = RegionAddress.verticalRegions(level); // full dimension height (1 at/above OCTREE_TOP)
        byte acc = 0;
        for (int rx = rxLo; rx <= rxHi; rx++) {
            for (int rz = rzLo; rz <= rzHi; rz++) {
                for (int ry = 0; ry < vert; ry++) {
                    final int row = p.rowIfPresent(level, rx, ry, rz);
                    if (row >= 0) acc = Log2Codec.merge(acc, p.getLog2(level, row, column));
                }
            }
        }
        return acc;
    }

    /** Squared distance from region {@code (level,rx,ry,rz)}'s center block to the anchor (long, no overflow). */
    private static long distSq(int level, int rx, int ry, int rz, int minY, int ax, int ay, int az) {
        final long dx = (long) RegionAddress.centerX(level, rx) - ax;
        final long dy = (long) RegionAddress.centerY(level, ry, minY) - ay;
        final long dz = (long) RegionAddress.centerZ(level, rz) - az;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------------------------------------------------------------------------------------------------
    // Pooled struct-of-arrays binary min-heap (long key = squared distance; parallel node-coord arrays).
    // ---------------------------------------------------------------------------------------------------

    private static final ThreadLocal<Heap> HEAP = ThreadLocal.withInitial(Heap::new);

    /** A reused binary min-heap over {@code (dist, level, rx, ry, rz)} tuples; grow-by-doubling, no boxing. */
    private static final class Heap {
        private long[] dist = new long[64];
        private int[] level = new int[64];
        private int[] rx = new int[64];
        private int[] ry = new int[64];
        private int[] rz = new int[64];
        private int size = 0;

        // Fields of the last popMin() (avoids returning an object / boxing).
        private int oLevel, oRx, oRy, oRz;

        void clear() { size = 0; }

        void push(long d, int lv, int x, int y, int z) {
            if (size == dist.length) grow();
            int i = size++;
            dist[i] = d; level[i] = lv; rx[i] = x; ry[i] = y; rz[i] = z;
            while (i > 0) {                       // sift up
                int parent = (i - 1) >> 1;
                if (dist[parent] <= dist[i]) break;
                swap(i, parent);
                i = parent;
            }
        }

        void popMin() {
            oLevel = level[0]; oRx = rx[0]; oRy = ry[0]; oRz = rz[0];
            int last = --size;
            if (last > 0) {
                dist[0] = dist[last]; level[0] = level[last];
                rx[0] = rx[last]; ry[0] = ry[last]; rz[0] = rz[last];
                int i = 0;                        // sift down
                for (;;) {
                    int l = 2 * i + 1, r = 2 * i + 2, m = i;
                    if (l < size && dist[l] < dist[m]) m = l;
                    if (r < size && dist[r] < dist[m]) m = r;
                    if (m == i) break;
                    swap(i, m);
                    i = m;
                }
            }
        }

        private void swap(int a, int b) {
            long td = dist[a]; dist[a] = dist[b]; dist[b] = td;
            int t;
            t = level[a]; level[a] = level[b]; level[b] = t;
            t = rx[a]; rx[a] = rx[b]; rx[b] = t;
            t = ry[a]; ry[a] = ry[b]; ry[b] = t;
            t = rz[a]; rz[a] = rz[b]; rz[b] = t;
        }

        private void grow() {
            int cap = dist.length << 1;
            dist = Arrays.copyOf(dist, cap);
            level = Arrays.copyOf(level, cap);
            rx = Arrays.copyOf(rx, cap);
            ry = Arrays.copyOf(ry, cap);
            rz = Arrays.copyOf(rz, cap);
        }
    }
}
