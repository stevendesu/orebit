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
 *   <li><b>Ascend from the anchor's leaf.</b> Walk levels 0..{@link RegionAddress#MAX_COARSE_LEVEL} of the
 *       region <i>containing the anchor</i>; stop at the tightest level whose region has a decoded count
 *       {@code >= minCount} for the column. If no level up to the coarsest ancestor holds enough (or the
 *       column is empty there), return empty — the caller reports "none known nearby".</li>
 *   <li><b>Best-first descend</b> from that ancestor with a min-heap keyed by <b>squared distance from the
 *       region center to the anchor</b> (nearest first — the "near me / near P" ranking; {@code minCount} is
 *       the quantity filter). Pop the nearest node: at level 0 emit a {@link ResourceHit} (decoded count +
 *       the region's center block); otherwise push each child that has an interned row and a decoded count
 *       {@code >= minCount}. Continue until {@code maxResults} hits or the heap drains.</li>
 * </ol>
 *
 * <p>The descend is a pure octree/quadtree tree walk from a single ancestor — every node has exactly one
 * parent, so no node is reachable twice and <b>no visited set is needed</b>. The octree&rarr;quadtree
 * child-count transition is handled by {@link RegionAddress#childCount} / {@link RegionAddress#childRY}
 * (8 children at/below {@link RegionAddress#OCTREE_TOP}, 4 above), matching {@link ResourceMerger}.
 *
 * <p><b>Ordering is approximate-nearest.</b> A coarse region's center distance is not a strict lower bound
 * on its contained level-0 centers (a child near the anchor edge can be nearer than the parent center), so
 * hits are emitted in a best-first-by-center order rather than a strictly sorted one. Because milestone 1
 * confines the search to the anchor's own coarse ancestor the approximation is tight in practice.
 *
 * <p><b>Scratch.</b> The priority queue is a <b>pooled per-thread struct-of-arrays binary min-heap</b>
 * ({@link Heap}, {@code long} keys + parallel {@code int} coord arrays, grow-by-doubling) held in a
 * {@link ThreadLocal} and cleared per call — no per-node allocation past warmup. The query runs on the tick
 * thread for milestone 1 (single-threaded, safe alongside the tick-thread pyramid writes); moving it to a
 * planner thread later needs the async-nav epoch discipline (design §8.4).
 *
 * <h2>Milestone-1 bound (design §8.2)</h2>
 * The search is confined to the {@link RegionAddress#MAX_COARSE_LEVEL} ancestor(s) that <i>contain the
 * anchor</i> — a level-6 quadtree cell is {@code 16 << 6 = 1024} blocks/axis — i.e. resources near the
 * anchor within loaded/known (built) regions. Wider global prospecting (unloaded-chunk scanning,
 * walk-and-scan) is a later arc.
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

        // 1. Ascend from the anchor's leaf to the TIGHTEST ancestor (containing the anchor) holding >= need.
        int sLevel = -1, sRx = 0, sRy = 0, sRz = 0;
        for (int lvl = 0; lvl <= RegionAddress.MAX_COARSE_LEVEL; lvl++) {
            final int rx = RegionAddress.regionX(ax, lvl);
            final int rz = RegionAddress.regionZ(az, lvl);
            final int ry = RegionAddress.regionY(ay, lvl, minY);
            final int row = p.rowIfPresent(lvl, rx, ry, rz);
            if (row < 0) continue; // no built region here — try one level coarser
            if (Log2Codec.decode(p.getLog2(lvl, row, column)) >= need) {
                sLevel = lvl; sRx = rx; sRy = ry; sRz = rz;
                break;
            }
        }
        if (sLevel < 0) return hits; // none known/loaded near the anchor (caller reports it)

        // 2. Best-first descend from that ancestor, nearest-center-first.
        final Heap heap = HEAP.get();
        heap.clear();
        heap.push(distSq(sLevel, sRx, sRy, sRz, minY, ax, ay, az), sLevel, sRx, sRy, sRz);

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
