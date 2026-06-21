package com.orebit.mod.worldmodel.pathing;

public class TraversalGrid {
    private static final int SIZE = 16;
    private static final int BLOCK_COUNT = SIZE * SIZE * SIZE;
    private static final int BITS_PER_ENTRY = 2;
    private static final int ENTRIES_PER_BYTE = 8 / BITS_PER_ENTRY; // 4
    private static final int BYTE_COUNT = BLOCK_COUNT / ENTRIES_PER_BYTE; // 1024

    private final byte[] data = new byte[BYTE_COUNT];

    public void reset() {
        for (int i = 0; i < BYTE_COUNT; i++) {
            data[i] = 0;
        }
    }

    public TraversalClass get(int x, int y, int z) {
        int index = getLinearIndex(x, y, z);
        int byteIndex = index / ENTRIES_PER_BYTE;
        int offset = (index % ENTRIES_PER_BYTE) * BITS_PER_ENTRY;
        int value = (data[byteIndex] >>> offset) & 0b11;
        return TraversalClass.fromId(value);
    }

    public void set(int x, int y, int z, TraversalClass value) {
        int index = getLinearIndex(x, y, z);
        int byteIndex = index / ENTRIES_PER_BYTE;
        int offset = (index % ENTRIES_PER_BYTE) * BITS_PER_ENTRY;

        // Clear existing 2 bits
        data[byteIndex] &= ~(0b11 << offset);
        // Set new value
        data[byteIndex] |= (value.id & 0b11) << offset;
    }

    private int getLinearIndex(int x, int y, int z) {
        // Assumes standard Minecraft chunk section: 16x16x16
        return (y << 8) | (z << 4) | x; // y * 256 + z * 16 + x
    }

    public byte[] raw() {
        return data;
    }
}
