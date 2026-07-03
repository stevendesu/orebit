package com.orebit.mod.worldmodel.pathing;

import java.util.Arrays;

/**
 * The per-section nav grid: one packed {@code short} per cell holding both resolutions the pathfinder
 * plans at, so the fine read and the neighbour-context read come from a single resident array (no live
 * re-derivation).
 *
 * <pre>
 *   bit 15 ........ 10 | 9 ............ 0
 *        neighbour flags |     navtype
 * </pre>
 *
 * <ul>
 *   <li><b>Navtype</b> (low 10 bits): the {@link com.orebit.mod.worldmodel.navblock.NavBlock} navtype
 *       index — {@code navtype(x,y,z)} is a single {@code & 0x3FF}, and the caller turns it into the full
 *       packed geometry descriptor with one more array index. 10 bits = 1024 navtypes (measured ~590, so
 *       ~1.7× headroom); {@link NavSectionBuilder} guards against the count outgrowing this budget.
 *   <li><b>Flags</b> (high 6 bits): the precomputed neighbour-property bitmask (see {@link NavFlags}) —
 *       headroom, edit-hazard, walk-through hazard, placeable-neighbour — the multi-cell facts the
 *       movement layer would otherwise re-derive on every A* expansion. Computed once at build /
 *       block-update and read as one masked array access.
 * </ul>
 *
 * <p><b>Why store both, not re-read live</b> (favour-cpu-over-ram): a live {@code getBlockState} is a
 * palette walk + a navtype-map lookup, and the neighbour facts are an 8-cell scan; keeping both resident
 * makes each a flat masked array access. 8 KB/section is negligible on a server, and the movement layer
 * reads them constantly during A*. Keeping the cell a {@code short} (not widening to {@code int}) is
 * itself the speed win — cache residency + load time (MOVEMENT-DESIGN §8).
 */
public class TraversalGrid {
    private static final int SIZE = 16;
    private static final int BLOCK_COUNT = SIZE * SIZE * SIZE; // 4096

    private static final int FLAGS_SHIFT = 10;
    /** Low 10 bits — the navtype index. */
    public static final int NAVTYPE_MASK = 0x3FF;
    /** Number of distinct navtypes the 10-bit index can address (indices 0..1023). */
    public static final int NAVTYPE_CAPACITY = NAVTYPE_MASK + 1;
    private static final int FLAGS_MASK = 0x3F; // high 6 bits

    // ---- Depth-nibble side array (E3 floorGap + E4 runUp — PERF-DESIGN-navgrid-widening.md §3,
    //      PERF-DESIGN-runup-nibble.md). A PARALLEL byte[4096] beside the hot short[] — deliberately NOT a
    //      widened cell: widening would double the extractor's bulk-scan bytes and halve grid L1 density to
    //      serve fields read a handful of times per pop. Low nibble = floorGap (distance to the first
    //      standable cell strictly below, chunk-column-exact); high nibble = runUp (consecutive same-navtype
    //      cells strictly above). Both saturate at 14; 15 = UNKNOWN (no claim — readers legacy-scan). ----

    /** floorGap/runUp value meaning "proven at least the 14-cell window" (floorGap: proven NONE within it). */
    public static final int DEPTH_SAT = 14;
    /** floorGap/runUp value meaning "no claim" — single-section build (no column sweep); readers legacy-scan. */
    public static final int DEPTH_UNKNOWN = 15;
    /** A depth byte with both nibbles UNKNOWN — the reset/default fill. */
    public static final byte DEPTH_UNKNOWN_BYTE = (byte) 0xFF;

    private final short[] data = new short[BLOCK_COUNT];
    private final byte[] depth = new byte[BLOCK_COUNT];

    public void reset() {
        Arrays.fill(data, (short) 0); // navtype AIR (0) + no flags; always overwritten on build
        Arrays.fill(depth, DEPTH_UNKNOWN_BYTE); // no depth claims until a column build/patch writes them
    }

    /** The fine {@code NavBlock} navtype index at this cell (low 10 bits) — a single mask, no shift. */
    public int navtype(int x, int y, int z) {
        return data[getLinearIndex(x, y, z)] & NAVTYPE_MASK;
    }

    /** The 6-bit neighbour-property flag bitmask at this cell (high 6 bits) — see {@link NavFlags}. */
    public int flags(int x, int y, int z) {
        return (data[getLinearIndex(x, y, z)] >>> FLAGS_SHIFT) & FLAGS_MASK;
    }

    /**
     * The whole packed slot at this cell as an unsigned 0..65535 {@code int} — flags <i>and</i> navtype in
     * one array read, so a caller that needs both (the movement prologue) resolves the slot once and derives
     * each with {@link #flagsOf}/{@link #navtypeOf} instead of paying two array reads. Masked to 16 bits so
     * the high flag bit never sign-extends.
     */
    public int packed(int x, int y, int z) {
        return data[getLinearIndex(x, y, z)] & 0xFFFF;
    }

    /** The navtype index of a slot read via {@link #packed} (low 10 bits). */
    public static int navtypeOf(int packed) {
        return packed & NAVTYPE_MASK;
    }

    /** The 6-bit {@link NavFlags} bitmask of a slot read via {@link #packed} (high 6 bits). */
    public static int flagsOf(int packed) {
        return (packed >>> FLAGS_SHIFT) & FLAGS_MASK;
    }

    /** Pack a cell's fine navtype and its neighbour-property flags into its slot. */
    public void set(int x, int y, int z, int navtype, int flags) {
        data[getLinearIndex(x, y, z)] =
                (short) (((flags & FLAGS_MASK) << FLAGS_SHIFT) | (navtype & NAVTYPE_MASK));
    }

    // ---- Depth-nibble reads/writes (see the field comment above; canonical (y<<8)|(z<<4)|x indexing) ----

    /**
     * The E3 floorGap nibble at this cell: {@code 0..13} = the first standable cell strictly below is at
     * {@code y - floorGap - 1}; {@link #DEPTH_SAT} = proven none within the 14-cell window;
     * {@link #DEPTH_UNKNOWN} = no claim (legacy-scan).
     */
    public int floorGap(int x, int y, int z) {
        return depth[getLinearIndex(x, y, z)] & 0xF;
    }

    /**
     * The E4 runUp nibble at this cell: {@code 0..13} = exactly that many consecutive same-navtype cells
     * strictly above; {@link #DEPTH_SAT} = at least 14; {@link #DEPTH_UNKNOWN} = no claim.
     */
    public int runUp(int x, int y, int z) {
        return (depth[getLinearIndex(x, y, z)] >>> 4) & 0xF;
    }

    /** Write the floorGap nibble, preserving the runUp nibble (builder/patch seam). */
    public void setFloorGap(int x, int y, int z, int gap) {
        int i = getLinearIndex(x, y, z);
        depth[i] = (byte) ((depth[i] & 0xF0) | (gap & 0xF));
    }

    /** Write the runUp nibble, preserving the floorGap nibble (builder/patch seam). */
    public void setRunUp(int x, int y, int z, int run) {
        int i = getLinearIndex(x, y, z);
        depth[i] = (byte) ((depth[i] & 0x0F) | ((run & 0xF) << 4));
    }

    /** The raw depth backing array (one byte per cell, same linear indexing as {@link #raw()}) — the
     *  bulk-scan seam for the cuboid extractor's run-chain reads and the column builder's sweeps. */
    public byte[] depthRaw() {
        return depth;
    }

    private int getLinearIndex(int x, int y, int z) {
        // Assumes standard Minecraft chunk section: 16x16x16
        return (y << 8) | (z << 4) | x; // y * 256 + z * 16 + x
    }

    public short[] raw() {
        return data;
    }
}
