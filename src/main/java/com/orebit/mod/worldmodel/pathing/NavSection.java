package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.worldmodel.region.Region;
import net.minecraft.core.BlockPos;

import java.util.Arrays;

public class NavSection {
    public static final int SIZE = 16;
    public static final int MAX_REGIONS = 8; // limit for fixed-size array

    private final TraversalGrid grid = new TraversalGrid();
    private final Region[] candidateRegions = new Region[MAX_REGIONS];
    private byte regionCount = 0;

    private BlockPos origin = BlockPos.ZERO; // min corner of the section

    public static NavSection create(BlockPos origin) {
        return NavSectionPool.get(origin);
    }

    public void recycle() {
        NavSectionPool.recycle(this);
    }

    public void reset(BlockPos newOrigin) {
        this.origin = newOrigin;
        this.regionCount = 0;
        Arrays.fill(candidateRegions, null);
        grid.reset();
    }

    public void addRegion(Region region) {
        if (regionCount >= MAX_REGIONS) return;
        candidateRegions[regionCount++] = region;
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

    public Region[] getCandidateRegions() {
        return candidateRegions;
    }

    public byte getRegionCount() {
        return regionCount;
    }

    public Region getRegionFor(int x, int y, int z) {
        BlockPos pos = origin.offset(x, y, z);
        for (int i = 0; i < regionCount; i++) {
            Region region = candidateRegions[i];
            if (region.contains(pos)) return region;
        }
        return null;
    }

    public TraversalGrid getTraversalGrid() {
        return grid;
    }
}
