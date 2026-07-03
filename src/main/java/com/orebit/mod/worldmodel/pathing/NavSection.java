package com.orebit.mod.worldmodel.pathing;

import net.minecraft.core.BlockPos;

public class NavSection {
    public static final int SIZE = 16;

    private final TraversalGrid grid = new TraversalGrid();

    private BlockPos origin = BlockPos.ZERO; // min corner of the section

    public static NavSection create(BlockPos origin) {
        return NavSectionPool.get(origin);
    }

    public void recycle() {
        NavSectionPool.recycle(this);
    }

    public void reset(BlockPos newOrigin) {
        this.origin = newOrigin;
        grid.reset();
    }

    /** The precomputed neighbour-property flag bitmask at this cell — see {@link NavFlags}. */
    public int getFlags(int x, int y, int z) {
        return grid.flags(x, y, z);
    }

    /** The resident {@code NavBlock} navtype index at this cell — for the fine geometry read. */
    public int getNavtype(int x, int y, int z) {
        return grid.navtype(x, y, z);
    }

    /** The whole packed slot (flags + navtype) at this cell — read once, derive both. */
    public int getPacked(int x, int y, int z) {
        return grid.packed(x, y, z);
    }

    /** The E3 floorGap nibble at this cell (see {@link TraversalGrid#floorGap}). */
    public int getFloorGap(int x, int y, int z) {
        return grid.floorGap(x, y, z);
    }

    /** The E4 runUp nibble at this cell (see {@link TraversalGrid#runUp}). */
    public int getRunUp(int x, int y, int z) {
        return grid.runUp(x, y, z);
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public TraversalGrid getTraversalGrid() {
        return grid;
    }
}
