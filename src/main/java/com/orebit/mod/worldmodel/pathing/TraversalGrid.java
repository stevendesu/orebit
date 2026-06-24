package com.orebit.mod.worldmodel.pathing;

import java.util.Arrays;

/**
 * The per-section nav grid: one packed {@code short} per cell holding <b>both</b> resolutions the
 * pathfinder plans at, so the coarse and fine reads come from a single resident array (no live re-derivation).
 *
 * <pre>
 *   bit 15  14 | 13 ............ 0
 *       class  |     navtype
 * </pre>
 *
 * <ul>
 *   <li><b>Coarse</b> (top 2 bits): the {@link TraversalClass} for cheap pruning. Pre-shifted into the
 *       high bits so a class read is a mask, not a mask-and-shift.
 *   <li><b>Fine</b> (low 14 bits): the {@link com.orebit.mod.worldmodel.navblock.NavBlock} <b>navtype</b>
 *       index — {@code navtype(x,y,z)} is a single {@code & 0x3FFF}, and the caller turns it into the full
 *       packed geometry descriptor with one more array index. 14 bits = 16,384 navtypes of headroom
 *       (measured ~530), so the index never collides with the class bits.
 * </ul>
 *
 * <p><b>Why store the navtype, not re-read live</b> (favour-cpu-over-ram): a live {@code getBlockState}
 * is a palette walk + a navtype-map lookup; keeping the resolved navtype resident makes the fine read a
 * flat masked array access. 8 KB/section (vs. 1 KB for a 2-bit-only grid) is negligible on a server, and
 * the movement layer reads geometry constantly during A*.
 */
public class TraversalGrid {
    private static final int SIZE = 16;
    private static final int BLOCK_COUNT = SIZE * SIZE * SIZE; // 4096

    private static final int CLASS_SHIFT = 14;
    private static final int NAVTYPE_MASK = 0x3FFF; // low 14 bits
    private static final int CLASS_BITS_MASK = 0xC000; // top 2 bits

    private final short[] data = new short[BLOCK_COUNT];

    public void reset() {
        Arrays.fill(data, (short) 0); // class CLEAR (id 0) + navtype AIR (0); always overwritten on build
    }

    /** The coarse {@link TraversalClass} at this cell (top 2 bits). */
    public TraversalClass get(int x, int y, int z) {
        int classId = (data[getLinearIndex(x, y, z)] & CLASS_BITS_MASK) >>> CLASS_SHIFT;
        return TraversalClass.fromId(classId);
    }

    /** The fine {@code NavBlock} navtype index at this cell (low 14 bits) — a single mask, no shift. */
    public int navtype(int x, int y, int z) {
        return data[getLinearIndex(x, y, z)] & NAVTYPE_MASK;
    }

    /** Pack a cell's coarse class and fine navtype into its slot. */
    public void set(int x, int y, int z, TraversalClass clazz, int navtype) {
        data[getLinearIndex(x, y, z)] = (short) ((clazz.id << CLASS_SHIFT) | (navtype & NAVTYPE_MASK));
    }

    private int getLinearIndex(int x, int y, int z) {
        // Assumes standard Minecraft chunk section: 16x16x16
        return (y << 8) | (z << 4) | x; // y * 256 + z * 16 + x
    }

    public short[] raw() {
        return data;
    }
}
