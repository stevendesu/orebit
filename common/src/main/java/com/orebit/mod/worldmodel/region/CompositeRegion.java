package com.orebit.mod.worldmodel.region;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;

public class CompositeRegion extends Region {
    private int depth = 0;

    private static final int MAX_SUMMARY_ATTEMPT = 8;

    public CompositeRegion(long id) {
        super(id);
    }

    public static CompositeRegion create(long id) {
        return RegionPool.getCompositeRegion(id);
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getDepth() {
        return depth;
    }

    @Override
    public boolean contains(BlockPos pos) {
        if (!boundingBox.contains(pos)) return false;

        for (int i = 0; i < childCount; i++) {
            if (children[i].contains(pos)) return true;
        }

        return false;
    }

    public void summarizePortals() {
        Set<Region> seenNeighbors = new HashSet<>();

        for (int i = 0; i < childCount; i++) {
            Region child = children[i];

            for (int j = 0; j < child.getPortalCount(); j++) {
                Portal portal = child.getPortals()[j];
                Region neighbor = portal.getOther(child);

                Region topNeighbor = neighbor.getRoot();
                if (topNeighbor == this) continue;
                if (seenNeighbors.contains(topNeighbor)) continue;

                PortalShape shape = portal.getShape();
                int cost = portal.getCost();
                boolean bidirectional = portal.isBidirectional();

                if (this.portalCount < MAX_PORTALS) {
                    this.activatePortal(topNeighbor, shape, cost, bidirectional);
                    seenNeighbors.add(topNeighbor);

                    if (bidirectional && topNeighbor.getPortalCount() < MAX_PORTALS) {
                        topNeighbor.activatePortal(this, shape, cost, true);
                    }
                }
            }
        }
    }

    public static CompositeRegion autoCompress(long regionId, Region[] children, int childCount, int depthHint) {
        CompositeRegion composite = CompositeRegion.create(regionId);
        composite.setDepth(depthHint);

        for (int i = 0; i < childCount; i++) {
            composite.addChild(children[i]);
        }

        composite.summarizePortals();

        if (composite.getPortalCount() <= MAX_PORTALS) {
            return composite;
        }

        // ❌ Too many portals — deepen the tree
        RegionPool.recycle(composite);

        int numGroups = (int) Math.ceil((double) childCount / MAX_SUMMARY_ATTEMPT);
        CompositeRegion parent = CompositeRegion.create(regionId);
        parent.setDepth(depthHint);

        for (int g = 0; g < numGroups; g++) {
            int start = g * MAX_SUMMARY_ATTEMPT;
            int end = Math.min(start + MAX_SUMMARY_ATTEMPT, childCount);
            int groupSize = end - start;

            Region[] subgroup = new Region[groupSize];
            System.arraycopy(children, start, subgroup, 0, groupSize);

            CompositeRegion subComposite = autoCompress(regionId + g + 1, subgroup, groupSize, depthHint + 1);
            parent.addChild(subComposite);
        }

        parent.summarizePortals();
        return parent;
    }

    @Override
    public void reset() {
        super.reset();
        depth = 0;
    }
}
