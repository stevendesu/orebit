package com.orebit.mod.worldmodel.resource;

import java.util.Arrays;

import com.orebit.mod.worldmodel.hpa.RegionAddress;

/**
 * The per-dimension SoA resource-tally store — a <b>parallel</b> layer to
 * {@link com.orebit.mod.worldmodel.hpa.CostPyramid CostPyramid} on the <b>same</b> fixed-grid implicit octree
 * ({@link RegionAddress}; find-mine-resources design §3). Where {@code CostPyramid} stores nav-cost/connectivity
 * per region, this stores <b>how many of each indexed resource</b> a region holds, as a log₂ histogram
 * ({@link Log2Codec}) — one byte per indexed column ({@link ResourceClasses#COLUMN_COUNT} = 23).
 *
 * <p>One {@code ResourcePyramid} instance exists per dimension ({@code RegionGrid} owns it alongside its
 * {@code CostPyramid}). A row is addressed by {@code (level, rx, ry, rz)} (see {@link RegionAddress}); each level
 * has its own keyspace + open-addressed {@code long}→row map (key = {@link RegionAddress#packLevelKey}). Level 0
 * is one 16³ {@code NavSection} (one tally row).
 *
 * <p><b>Sparsity is the win (§3).</b> A row is interned <b>only</b> when {@link #rowFor} is called for it —
 * phase 4 calls {@code rowFor} only for sections that actually hold ≥1 indexed block, and the roll-up
 * ({@link ResourceMerger}) creates only ancestors of those. Air/dirt/stone sections never create a row → zero
 * storage. {@link #rowIfPresent} returns {@code -1} for the empty common case with <b>no allocation and no level
 * creation</b>. The pyramid itself never auto-creates a row on a read.
 *
 * <p><b>Storage layout (struct-of-arrays, no per-row objects).</b> Per level:
 * <ul>
 *   <li>an open-addressed {@code long}→row map ({@code mapKey}/{@code mapRow}, murmur3-finalized slot,
 *       power-of-two capacity, {@code -1} empty marker, linear probe, grow-at-3/4) — the exact idiom of
 *       {@code CostPyramid.Level};</li>
 *   <li>parallel {@code int[] rx/ry/rz} of each row's region coords + a {@code boolean[] built} flag;</li>
 *   <li>a <b>flat row-major</b> {@code byte[] cols} sized {@code rowCap * COLUMNS} — a row's whole 23-byte
 *       column vector is contiguous at {@code row*COLUMNS}, so the {@link ResourceMerger} roll-up reads a
 *       child's vector in one {@code arraycopy} and the payload lives together in cache. Index a single cell as
 *       {@code cols[row*COLUMNS + col]}.</li>
 * </ul>
 *
 * <p><b>House style (favour-cpu-over-ram / hot-path-no-alloc).</b> No heap allocation on the read/intern hot
 * path past warmup: the per-level arrays grow-and-reuse (doubling), the map uses {@code long} keys (no boxing),
 * and the slot hash is the murmur3 64-bit finalizer copied verbatim from the block tier. Levels are lazily
 * allocated. A new row's column vector defaults to all-zero ("no resource present").
 */
public final class ResourcePyramid {

    /** Indexed pyramid columns per row (23). Compile-time constant ⇒ referencing it does not force
     *  {@link ResourceClasses} class-init (which touches the MC registry), so the pure data tests stay MC-free. */
    static final int COLUMNS = ResourceClasses.COLUMN_COUNT;

    /** Initial per-level map capacity (power of two); grows by doubling at 3/4 load. */
    private static final int INITIAL_MAP_CAP = 256;

    /** Initial per-level row capacity; grows by doubling. (Package-visible so {@code ResourcePyramidTest} can
     *  intern past it to exercise growth without hard-coding the value.) */
    static final int INITIAL_ROW_CAP = 192;

    /**
     * One per-level SoA table: an open-addressed {@code long}→row map plus parallel row arrays and the flat
     * row-major column store. Mirrors {@code CostPyramid.Level} (murmur3 slot, {@code -1} empty, linear probe,
     * grow-at-3/4); append-only across the pyramid's lifetime (rows are never freed/reused).
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
        boolean[] built;         // tally actually written (vs interned-but-empty placeholder)
        byte[] cols;             // flat row-major: cols[row*COLUMNS + col] = log₂ count of that column
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
            cols = new byte[INITIAL_ROW_CAP * COLUMNS];
        }

        /** Row for {@code (cx,cy,cz)} keyed by {@code k}, creating it (all-zero column vector, !built) if absent. */
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
            // Append-only ⇒ this slice was never written, but clear it explicitly so a row always starts empty.
            Arrays.fill(cols, n * COLUMNS, n * COLUMNS + COLUMNS, (byte) 0);
            count = n + 1;
            return n;
        }

        private void growRows() {
            int cap = rx.length << 1;
            rx = Arrays.copyOf(rx, cap);
            ry = Arrays.copyOf(ry, cap);
            rz = Arrays.copyOf(rz, cap);
            built = Arrays.copyOf(built, cap);
            // Row-major: existing rows keep their row*COLUMNS offsets when the flat array is extended.
            cols = Arrays.copyOf(cols, cap * COLUMNS);
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

        /** Murmur3 64-bit finalizer → slot; copied verbatim from {@code CostPyramid.Level.slotFor}. */
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

    public ResourcePyramid() {}

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
    // Intern / lookup (mirrors CostPyramid)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Intern the row {@code (level, rx, ry, rz)}, returning its stable row index. Creates the row (all-zero
     * column vector, {@code built=false}) if absent, allocating the level's table on first use of that level.
     */
    public int rowFor(int level, int rx, int ry, int rz) {
        return level(level).intern(RegionAddress.packLevelKey(rx, ry, rz), rx, ry, rz);
    }

    /**
     * Row index of {@code (level, rx, ry, rz)} if already interned, else {@code -1} — <b>no create, no level
     * allocation</b>, no alloc (the empty-common-case fast path §3).
     */
    public int rowIfPresent(int level, int rx, int ry, int rz) {
        Level l = levels[level];
        if (l == null) return -1;
        return l.rowIfPresent(RegionAddress.packLevelKey(rx, ry, rz));
    }

    /** Whether this row has had a tally written (a real leaf/merge) vs an interned-but-empty placeholder. */
    public boolean isBuilt(int level, int row) {
        return levels[level].built[row];
    }

    /** Mark an interned row built / not-built. */
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
    // Column payload (the log₂ tally)
    // ---------------------------------------------------------------------------------------------------

    /** The log₂-encoded count ({@link Log2Codec}) of column {@code col} (0..22) on an interned row. */
    public byte getLog2(int level, int row, int col) {
        return levels[level].cols[row * COLUMNS + col];
    }

    /** Set the log₂-encoded count of column {@code col} (0..22) on an interned row. */
    public void setLog2(int level, int row, int col, byte v) {
        levels[level].cols[row * COLUMNS + col] = v;
    }

    /**
     * Write a whole {@link #COLUMNS}-wide column vector onto an interned row (the phase-4 tally write and the
     * {@link ResourceMerger} roll-up write). {@code src} must be at least {@code COLUMNS} long.
     */
    public void setRow(int level, int row, byte[] src) {
        System.arraycopy(src, 0, levels[level].cols, row * COLUMNS, COLUMNS);
    }

    /**
     * Read an interned row's whole {@link #COLUMNS}-wide column vector into {@code out} (the roll-up child
     * gather). {@code out} must be at least {@code COLUMNS} long.
     */
    public void readRow(int level, int row, byte[] out) {
        System.arraycopy(levels[level].cols, row * COLUMNS, out, 0, COLUMNS);
    }
}
