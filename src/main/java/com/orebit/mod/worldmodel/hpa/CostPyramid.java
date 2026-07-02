package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;

/**
 * The per-dimension SoA cost store for the HPA* region tier
 * (PRD §6.3–6.5; HPA-IMPLEMENTATION.md §4, "3a addressing / store").
 *
 * <p>One {@code CostPyramid} instance exists per dimension. It holds the implicit-octree pyramid's node
 * costs as a stack of per-level {@link Level} tables. A node is addressed by {@code (level, rx, ry, rz)}
 * (see {@link RegionAddress}); each level has its <b>own keyspace and own open-addressed
 * {@code long}→row map</b> (key = {@link RegionAddress#packLevelKey}), so the same {@code (rx,ry,rz)} at two
 * levels never collide. A row stores the node's connectivity {@link RegionFragments} record (HPA-FRAGMENTS.md
 * §5: the 6-connected occupiable components + their per-face footprints; edge costs are derived at query, §2.2,
 * not stored).
 *
 * <p><b>Storage layout (struct-of-arrays, no per-node objects).</b> Per level:
 * <ul>
 *   <li>an open-addressed {@code long}→row map ({@code mapKey}/{@code mapRow}, murmur3-finalized slot,
 *       power-of-two capacity, {@code -1} empty marker, linear probe, grow-at-3/4) — the exact idiom of
 *       {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}'s {@code Nodes} table;</li>
 *   <li>parallel {@code int[] rx/ry/rz} of each row's region coords, so a planner reading a node's center
 *       never has to unpack the key;</li>
 *   <li>{@code RegionFragments[] frags} — the per-row connectivity record (lazily materialized by
 *       {@link #ensureFragments}, filled by {@link FragmentLeafComputer} at a leaf or {@link PyramidMerger}'s
 *       roll-up at a coarse level);</li>
 *   <li>{@code boolean[] built} — whether a node's fragments were actually computed (a real leaf/merge) vs an
 *       interned-but-default placeholder. The planner returns an optimistic admissible default (§6) for a
 *       node that is interned-but-{@code !built}.</li>
 * </ul>
 *
 * <p><b>House style (HPA-IMPLEMENTATION.md §14).</b> No heap allocation on the read/intern hot path past
 * warmup: the per-level arrays grow-and-reuse (doubling), the map uses {@code long} keys (no boxing), and
 * the slot hash is the murmur3 64-bit finalizer copied verbatim from the block tier. Levels are lazily
 * allocated: {@code levels[]} is sized {@code MAX_LEVEL+1} and a {@link Level} is created on first touch.
 *
 * <p>New rows initialize {@code frags = null} and {@code built = false} (the planner reads the §6 optimistic
 * default for an interned-but-unbuilt node).
 *
 * <p>This class owns only the store + intern/get/put primitives. The roll-up driver ({@link PyramidMerger}),
 * leaf computation ({@link FragmentLeafComputer}), and default-on-miss policy ({@code RegionGrid}) live in
 * their own files and call through this API.
 */
public final class CostPyramid {

    /** Initial per-level map capacity (power of two); grows by doubling at 3/4 load. */
    private static final int INITIAL_MAP_CAP = 256;

    /** Initial per-level row capacity; grows by doubling. */
    private static final int INITIAL_ROW_CAP = 192;

    /**
     * One per-level SoA table: an open-addressed {@code long}→row map plus parallel row arrays. Mirrors the
     * {@code BlockPathfinder.Nodes} idiom (murmur3 slot, {@code -1} empty, linear probe, grow-at-3/4) but
     * append-only across the pyramid's lifetime (no per-search reset — the pyramid persists).
     */
    static final class Level {
        // ---- key→row index (open addressing, linear probe) ----
        long[] mapKey;
        int[] mapRow;            // -1 marks an empty slot
        int mapMask;
        int mapSize;
        int mapGrowAt;

        // ---- row table (append-only; row index is stable) ----
        int[] rx, ry, rz;        // region coords per row (planning never unpacks the key)
        boolean[] built;         // fragments actually computed (vs interned default placeholder)
        /**
         * Per-row connectivity record for the HPA* fragment model (HPA-FRAGMENTS.md §5). {@code null} until the
         * row's leaf is built; lazily materialized by {@link CostPyramid#ensureFragments} and filled by
         * {@link FragmentLeafComputer}. Parallel to the row arrays (favour-cpu-over-ram §14: one small convenient
         * object per built region rather than re-deriving the flood on every read).
         */
        RegionFragments[] frags;
        int count;

        Level() {
            mapKey = new long[INITIAL_MAP_CAP];
            mapRow = new int[INITIAL_MAP_CAP];
            Arrays.fill(mapRow, -1);
            mapMask = INITIAL_MAP_CAP - 1;
            mapGrowAt = INITIAL_MAP_CAP * 3 / 4;

            rx = new int[INITIAL_ROW_CAP];
            ry = new int[INITIAL_ROW_CAP];
            rz = new int[INITIAL_ROW_CAP];
            built = new boolean[INITIAL_ROW_CAP];
            frags = new RegionFragments[INITIAL_ROW_CAP];
        }

        /** Row for {@code (cx,cy,cz)} keyed by {@code k}, creating it (faces=INF, !built) if absent. */
        int intern(long k, int cx, int cy, int cz) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) {
                    row = newRow(cx, cy, cz);
                    mapKey[slot] = k;
                    mapRow[slot] = row;
                    if (++mapSize >= mapGrowAt) growMap();
                    return row;
                }
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        /** Row for {@code k}, or {@code -1} if absent (no create). */
        int rowIfPresent(long k) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) return -1;
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        private int newRow(int cx, int cy, int cz) {
            int n = count;
            if (n == rx.length) growRows();
            rx[n] = cx; ry[n] = cy; rz[n] = cz;
            built[n] = false;
            frags[n] = null;
            count = n + 1;
            return n;
        }

        private void growRows() {
            int cap = rx.length << 1;
            rx = Arrays.copyOf(rx, cap);
            ry = Arrays.copyOf(ry, cap);
            rz = Arrays.copyOf(rz, cap);
            built = Arrays.copyOf(built, cap);
            frags = Arrays.copyOf(frags, cap);
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

    /** Lazily-allocated per-level tables, index = level (0..{@link RegionAddress#MAX_LEVEL}). */
    private final Level[] levels = new Level[RegionAddress.MAX_LEVEL + 1];

    public CostPyramid() {}

    /** The level table, allocating it on first touch. {@code level} must be {@code 0..MAX_LEVEL}. */
    private Level level(int level) {
        Level l = levels[level];
        if (l == null) {
            l = new Level();
            levels[level] = l;
        }
        return l;
    }

    // ---------------------------------------------------------------------------------------------------
    // Public API (HPA-IMPLEMENTATION.md §4)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Intern the node {@code (level, rx, ry, rz)}, returning its stable row index. Creates the row (all six
     * faces = {@link CostCodec#BUCKET_INF}, {@code built=false}) if absent. Allocates the level's table on
     * first use of that level.
     */
    public int rowFor(int level, int rx, int ry, int rz) {
        return level(level).intern(RegionAddress.packLevelKey(rx, ry, rz), rx, ry, rz);
    }

    /**
     * Row index of {@code (level, rx, ry, rz)} if already interned, else {@code -1} — no create, no level
     * allocation if the level is untouched.
     */
    public int rowIfPresent(int level, int rx, int ry, int rz) {
        Level l = levels[level];
        if (l == null) return -1;
        return l.rowIfPresent(RegionAddress.packLevelKey(rx, ry, rz));
    }

    /**
     * Whether the node at {@code (level, row)} had its faces actually computed (a real leaf or merge), as
     * opposed to an interned-but-default placeholder. The planner returns an optimistic admissible default
     * (§6) for an interned-but-{@code !built} node.
     */
    public boolean isBuilt(int level, int row) {
        return levels[level].built[row];
    }

    /** Mark an interned row built / not-built (set by {@link LeafCostComputer} / {@link PyramidMerger}). */
    public void setBuilt(int level, int row, boolean value) {
        levels[level].built[row] = value;
    }

    // ---------------------------------------------------------------------------------------------------
    // Row-coord accessors (planning reads centers without unpacking the key)
    // ---------------------------------------------------------------------------------------------------

    /** Region X stored for an interned row. */
    public int rowRX(int level, int row) {
        return levels[level].rx[row];
    }

    /** Region Y stored for an interned row. */
    public int rowRY(int level, int row) {
        return levels[level].ry[row];
    }

    /** Region Z stored for an interned row. */
    public int rowRZ(int level, int row) {
        return levels[level].rz[row];
    }

    /** Number of interned rows at {@code level} (0 if the level is untouched). */
    public int rowCount(int level) {
        Level l = levels[level];
        return l == null ? 0 : l.count;
    }

    // ---------------------------------------------------------------------------------------------------
    // Fragment-model store (HPA-FRAGMENTS.md §5). The fragment model is now the ONLY model: the
    // center-model face buckets and the RegionGrid.HPA_FRAGMENTS dispatch flag were deleted in the
    // s36 cleanup, so this store is written and read unconditionally.
    // ---------------------------------------------------------------------------------------------------

    /**
     * The per-row {@link RegionFragments} record, lazily materializing it on first use of this row so
     * {@link FragmentLeafComputer} can fill it in place (no per-leaf throwaway copy). The row must already be
     * interned ({@link #rowFor}). Mirrors the face-bucket store's "intern then write" shape.
     */
    public RegionFragments ensureFragments(int level, int row) {
        Level l = levels[level];
        RegionFragments rf = l.frags[row];
        if (rf == null) {
            rf = new RegionFragments();
            l.frags[row] = rf;
        }
        return rf;
    }

    /**
     * The per-row connectivity record, or {@code null} if this row has no fragment record yet (never built
     * under the fragment path). The region A* (S3) reads footprints/kind through this object;
     * {@link RegionGrid} applies the optimistic default-on-miss policy.
     */
    public RegionFragments fragmentRecord(int level, int row) {
        return levels[level].frags[row];
    }

    /**
     * Region kind ({@link RegionFragments#KIND_MIXED}/{@code KIND_SOLID}/{@code KIND_AIR}/{@code KIND_WATER})
     * of a built row. A {@code null}/absent record reads as the optimistic {@link RegionFragments#KIND_AIR}
     * (free-traverse) — NPE safety; the real default-on-miss lives in {@link RegionGrid}.
     */
    public int kind(int level, int row) {
        RegionFragments rf = levels[level].frags[row];
        return rf == null ? RegionFragments.KIND_AIR : rf.kind();
    }

    /** Mean SOLID-cell hardness nibble (the mine-edge cost scale); 0 for an absent record. */
    public int avgHardness(int level, int row) {
        RegionFragments rf = levels[level].frags[row];
        return rf == null ? 0 : rf.avgSolidHardness();
    }

    /** Passable-cell fraction nibble (the collapsed/uniform crossing cost scale); 15 (open) for an absent record. */
    public int passFrac(int level, int row) {
        RegionFragments rf = levels[level].frags[row];
        return rf == null ? 15 : rf.passFrac();
    }

    /** Number of fragment records on this row (0 ⇒ uniform/collapsed mass, or an absent record). */
    public int fragments(int level, int row) {
        RegionFragments rf = levels[level].frags[row];
        return rf == null ? 0 : rf.fragmentCount();
    }

    /**
     * The packed 2D-bbox footprint of {@code frag} on {@code face} (0..5), or {@link RegionFragments#NO_FACE}
     * if the fragment does not touch that face (or the record is absent). Decode with the
     * {@code RegionFragments.footprintMin/Max U/V} statics.
     */
    public int faceFootprint(int level, int row, int frag, int face) {
        RegionFragments rf = levels[level].frags[row];
        return rf == null ? RegionFragments.NO_FACE : rf.footprint(frag, face);
    }
}
