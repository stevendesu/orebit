package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * An additive per-level-5-shard row index maintained by a {@link CostPyramid}/{@code ResourcePyramid} at intern
 * time, so the periodic persistence flush ({@link com.orebit.mod.worldmodel.persistence.RegionPersistence}) can
 * gather ONE shard's rows without re-scanning the whole dimension.
 *
 * <h2>Why it exists</h2>
 * The Stage-1 periodic flush used to {@code bucketShards} the ENTIRE dimension (every level-0..5 row, re-packed)
 * once per draining tick — {@code O(rows)} even when a single dirty shard is being written, and re-run every tick
 * of a multi-tick drain. This index turns a shard write into {@code O(the shard's own rows)}: for a shard key it
 * holds, per shard-level 0..{@link RegionAddress#SHARD_LEVEL}, the list of that level's row indices whose region
 * coords fall in the shard.
 *
 * <h2>Additive only — never remove</h2>
 * The pyramids' rows are append-only (row indices are stable; the open-addressed map is never compacted), and
 * {@code dropRow} (cold-shard eviction) keeps a row interned with {@code built=false} rather than removing it. The
 * flush's write-time built-filter ({@code !isBuilt || fragmentRecord==null} skip) already handles evicted rows, so
 * this index only ever GROWS — an entry is added exactly once, when a row is first created ({@code rowFor} interns
 * a brand-new row). An evicted-then-rebuilt row re-interns to the SAME stable index (the map entry was kept), so it
 * is never double-added.
 *
 * <h2>Byte-identity (the invariant the round-trip test guards)</h2>
 * Row indices are added in ascending order (a new row's index is the level's old {@code count}, which strictly
 * increases), which is exactly the order a full-dimension scan
 * ({@code CostPyramidCodec.collectColumns}/{@code bucketShards}) encounters a shard's rows. So an index-driven
 * per-shard gather feeds the same rows in the same order into the same {@code HashMap}-keyed column grouping, and
 * the encoded shard bytes are identical to the scanning {@code encodeShard} path.
 *
 * <h2>Threading</h2>
 * Tick-thread only — every pyramid intern ({@code CostPyramid}/{@code ResourcePyramid} {@code rowFor}) runs on the
 * tick thread (chunk build, coarse merge, disk decode/lazy-load); only {@code BlockPathfinder.findPath} — which
 * reads {@code NavSection}s, never a pyramid — runs on the planner pool. So a plain {@link HashMap} is safe, matching
 * the pyramids' own single-threaded mutation model. Cold path (once per row creation); normal allocation is fine.
 */
public final class ShardRowIndex {

    /** Shard levels 0..{@link RegionAddress#SHARD_LEVEL} inclusive. */
    private static final int LEVELS = RegionAddress.SHARD_LEVEL + 1;

    /** shardKey → per-shard-level growable row-index lists (index {@code sl} = the shard level 0..SHARD_LEVEL). */
    private final Map<Long, Entry> byShard = new HashMap<>();

    /** One shard's per-level growable row-index lists + their live counts. */
    private static final class Entry {
        final int[][] rows = new int[LEVELS][];
        final int[] counts = new int[LEVELS];
    }

    /**
     * Append a newly-interned row (its stable index) at shard-level {@code sl} (0..{@link RegionAddress#SHARD_LEVEL})
     * to shard {@code shardKey}. Call ONCE per row, at creation. Rows arrive in ascending index order per level.
     */
    public void add(long shardKey, int sl, int row) {
        Entry e = byShard.get(shardKey);
        if (e == null) {
            e = new Entry();
            byShard.put(shardKey, e);
        }
        int[] arr = e.rows[sl];
        int n = e.counts[sl];
        if (arr == null) {
            arr = new int[4];
            e.rows[sl] = arr;
        } else if (n == arr.length) {
            arr = Arrays.copyOf(arr, n << 1);
            e.rows[sl] = arr;
        }
        arr[n] = row;
        e.counts[sl] = n + 1;
    }

    /** Number of indexed rows for {@code shardKey} at shard-level {@code sl} (0 if the shard has none there). */
    public int rowCount(long shardKey, int sl) {
        Entry e = byShard.get(shardKey);
        return e == null ? 0 : e.counts[sl];
    }

    /** The {@code i}-th indexed row (ascending intern order) for {@code shardKey} at shard-level {@code sl}. */
    public int rowAt(long shardKey, int sl, int i) {
        return byShard.get(shardKey).rows[sl][i];
    }
}
