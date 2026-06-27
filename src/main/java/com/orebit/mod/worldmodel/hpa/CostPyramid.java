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
 * levels never collide. A row stores the node's six face→center costs (PRD §6.5: each face holds the
 * half-traversal cost from that face to the node center; the boundary between two siblings is the implicit
 * sum of the two facing halves — we store the half, never an edge).
 *
 * <p><b>Storage layout (struct-of-arrays, no per-node objects).</b> Per level:
 * <ul>
 *   <li>an open-addressed {@code long}→row map ({@code mapKey}/{@code mapRow}, murmur3-finalized slot,
 *       power-of-two capacity, {@code -1} empty marker, linear probe, grow-at-3/4) — the exact idiom of
 *       {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}'s {@code Nodes} table;</li>
 *   <li>parallel {@code int[] rx/ry/rz} of each row's region coords, so a planner reading a node's center
 *       never has to unpack the key;</li>
 *   <li>{@code byte[] face} — <b>twelve</b> buckets per row: six faces × two directions ({@link #ENTER}
 *       face→center, {@link #EXIT} center→face), FLATTENED ({@code face[row*12 + dir*6 + f]}); one
 *       {@code byte} each (12 B/node), <b>not</b> bit-packed in RAM (favour-cpu-over-ram — the nibble
 *       packing of {@link CostCodec} is only the on-disk form, §11, where it is 6 B/node). The two
 *       directions exist because a single per-face scalar can't express the asymmetry of vertical air travel
 *       (fall in cheap, pillar out expensive) — see {@link #ENTER};</li>
 *   <li>{@code boolean[] built} — whether a node's faces were actually computed (a real leaf/merge) vs an
 *       interned-but-default placeholder. The planner returns an optimistic admissible default (§6) for a
 *       node that is interned-but-{@code !built}.</li>
 * </ul>
 *
 * <p><b>House style (HPA-IMPLEMENTATION.md §14).</b> No heap allocation on the read/intern hot path past
 * warmup: the per-level arrays grow-and-reuse (doubling), the map uses {@code long} keys (no boxing), and
 * the slot hash is the murmur3 64-bit finalizer copied verbatim from the block tier. Levels are lazily
 * allocated: {@code levels[]} is sized {@code MAX_LEVEL+1} and a {@link Level} is created on first touch.
 *
 * <p>New rows initialize all six faces to {@link CostCodec#BUCKET_INF} and {@code built = false}. INF is the
 * void/out-of-world sentinel; a real leaf never emits it (everything is mineable — §5).
 *
 * <p>This class owns only the store + intern/get/put primitives. The merge driver
 * ({@link PyramidMerger}), leaf computation ({@link LeafCostComputer}), and default-on-miss policy
 * ({@code RegionGrid}) live in their own files and call through this API.
 */
public final class CostPyramid {

    /** Six faces per node (canonical face order — see {@link RegionAddress}). */
    private static final int FACES = 6;

    /**
     * Two directions stored PER FACE: {@link #ENTER} (face→center, going INTO the region through that face)
     * and {@link #EXIT} (center→face, going OUT through it). A single scalar can't express the directional
     * asymmetry of vertical air travel — falling IN through the top is cheap, pillaring OUT through it is
     * expensive — and because a boundary crossing sums two facing halves, one scalar forces up- and
     * down-crossings to be equal. For symmetric terrain (the A*-computed mixed leaves, and uniform water/
     * stone) enter == exit (the inverse walk costs the same), so they are simply set together via
     * {@link #setFaceBoth} — no second computation. Only the all-air leaf sets them apart.
     */
    public static final int ENTER = 0;
    public static final int EXIT = 1;
    private static final int DIRS = 2;
    /** Stored values per row: 6 faces × 2 directions = 12 (one {@code byte} each — RAM is cheap, §14). */
    private static final int SLOTS_PER_ROW = FACES * DIRS;

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
        byte[] face;             // 12 buckets/row, FLATTENED: face[row*12 + dir*6 + f] (dir ENTER=0/EXIT=1)
        boolean[] built;         // faces actually computed (vs interned default placeholder)
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
            face = new byte[INITIAL_ROW_CAP * SLOTS_PER_ROW];
            built = new boolean[INITIAL_ROW_CAP];
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
            int base = n * SLOTS_PER_ROW;
            for (int s = 0; s < SLOTS_PER_ROW; s++) face[base + s] = (byte) CostCodec.BUCKET_INF;
            built[n] = false;
            count = n + 1;
            return n;
        }

        private void growRows() {
            int cap = rx.length << 1;
            rx = Arrays.copyOf(rx, cap);
            ry = Arrays.copyOf(ry, cap);
            rz = Arrays.copyOf(rz, cap);
            face = Arrays.copyOf(face, cap * SLOTS_PER_ROW);
            built = Arrays.copyOf(built, cap);
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

    /** The raw bucket ({@code 0..15}) of {@code face} (0..5) in direction {@code dir} ({@link #ENTER}/{@link #EXIT}). */
    public int faceBucket(int level, int row, int face, int dir) {
        return levels[level].face[row * SLOTS_PER_ROW + dir * FACES + face] & 0xFF;
    }

    /** Set the bucket ({@code 0..15}) of {@code face} (0..5) in direction {@code dir} ({@link #ENTER}/{@link #EXIT}). */
    public void setFaceBucket(int level, int row, int face, int dir, int bucket) {
        levels[level].face[row * SLOTS_PER_ROW + dir * FACES + face] = (byte) bucket;
    }

    /**
     * Set BOTH directions of {@code face} (0..5) to the same {@code bucket} — the symmetric case (A*-computed
     * mixed leaves, uniform water/stone, defaults). The asymmetric all-air leaf uses {@link #setFaceBucket}
     * per direction instead.
     */
    public void setFaceBoth(int level, int row, int face, int bucket) {
        byte[] f = levels[level].face;
        int base = row * SLOTS_PER_ROW;
        f[base + ENTER * FACES + face] = (byte) bucket;
        f[base + EXIT * FACES + face] = (byte) bucket;
    }

    /** Dequantized tick cost of {@code face} (0..5) in direction {@code dir} ({@link CostCodec#dequantize}). */
    public float faceCost(int level, int row, int face, int dir) {
        return CostCodec.dequantize(levels[level].face[row * SLOTS_PER_ROW + dir * FACES + face] & 0xFF);
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
}
