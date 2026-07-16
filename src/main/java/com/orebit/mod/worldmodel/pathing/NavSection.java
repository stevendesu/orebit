package com.orebit.mod.worldmodel.pathing;

import net.minecraft.core.BlockPos;

public class NavSection {
    public static final int SIZE = 16;

    private final TraversalGrid grid = new TraversalGrid();

    private BlockPos origin = BlockPos.ZERO; // min corner of the section

    /**
     * Per-section indexed-resource tally, log₂-encoded ({@link com.orebit.mod.worldmodel.resource.Log2Codec}),
     * one byte per indexed column ({@link com.orebit.mod.worldmodel.resource.ResourceClasses#COLUMN_COUNT}).
     * <b>Nullable — {@code null} means the section held no indexed resource</b> (the common case → zero storage,
     * the sparsity win). Produced by {@link ChunkNavBuilder} from the classify-pass tally; consumed by
     * {@code HpaMaintenance.onChunkNavBuilt} to write the level-0 resource-pyramid row.
     */
    private byte[] resourceTally;

    public static NavSection create(BlockPos origin) {
        return NavSectionPool.get(origin);
    }

    public void recycle() {
        NavSectionPool.recycle(this);
    }

    public void reset(BlockPos newOrigin) {
        this.origin = newOrigin;
        this.resourceTally = null; // pooled reuse: never carry a prior section's tally
        grid.reset();
    }

    /** The per-section log₂-encoded resource tally, or {@code null} if the section held no indexed resource. */
    public byte[] resourceTally() {
        return resourceTally;
    }

    /** Attach the per-section resource tally ({@code null} for a resource-free section). */
    public void setResourceTally(byte[] tally) {
        this.resourceTally = tally;
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

    /** Whether any cell in this section is a door — the per-pop exit-door gate prefilter (see
     *  {@link TraversalGrid#anyDoor}). */
    public boolean anyDoor() {
        return grid.anyDoor();
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public TraversalGrid getTraversalGrid() {
        return grid;
    }
}
