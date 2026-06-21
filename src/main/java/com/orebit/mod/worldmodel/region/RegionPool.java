package com.orebit.mod.worldmodel.region;

import java.util.ArrayDeque;

import net.minecraft.core.BlockPos;

public final class RegionPool {
    private static final int POOL_SIZE = 256;

    // We need a separate pool for LeafRegion because it allocates additional
    // memory for block offsets.
    // This is a trade-off between memory usage and performance.
    // If we use a single pool, we would need to clear the block offsets every time
    private static final ArrayDeque<LeafRegion> leafPool = new ArrayDeque<>(POOL_SIZE);
    private static final ArrayDeque<CompositeRegion> compositePool = new ArrayDeque<>(POOL_SIZE);

    private RegionPool() {}

    public static LeafRegion getLeafRegion(long id, BlockPos origin) {
        LeafRegion region = leafPool.isEmpty() ? new LeafRegion(id, origin) : leafPool.pop();
        region.setId(id);
        region.setOrigin(origin);
        return region;
    }

    public static CompositeRegion getCompositeRegion(long id) {
        CompositeRegion region = compositePool.isEmpty() ? new CompositeRegion(id) : compositePool.pop();
        region.setId(id);
        return region;
    }

    public static void recycle(Region region) {
        region.reset();
        switch (region) {
            case LeafRegion leafRegion -> leafPool.push(leafRegion);
            case CompositeRegion compositeRegion -> compositePool.push(compositeRegion);
            default -> {
                // Throw an error. This should never happen.
                throw new IllegalArgumentException("Unknown region type: " + region.getClass().getName());
            }
        }
    }
}
