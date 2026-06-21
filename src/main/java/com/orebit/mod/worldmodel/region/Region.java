package com.orebit.mod.worldmodel.region;

import net.minecraft.core.BlockPos;

public abstract class Region {
    protected long regionId;
    protected final RegionMetadata metadata = new RegionMetadata();
    protected final RegionBoundingBox boundingBox = new RegionBoundingBox();

    protected static final int MAX_CHILDREN = 16;
    protected final Region[] children = new Region[MAX_CHILDREN];
    protected byte childCount = 0;
    protected Region parent;

    protected static final int MAX_PORTALS = 32;
    protected final Portal[] portals = new Portal[MAX_PORTALS];
    protected byte portalCount = 0;

    protected Region(long id) {
        this.regionId = id;

        for (int i = 0; i < MAX_PORTALS; i++) {
            portals[i] = new Portal(); // no args constructor
        }
    }
    
    public void recycle() {
        RegionPool.recycle(this);
    }

    public long getId() {
        return regionId;
    }

    public void setId(long id) {
        this.regionId = id;
    }

    public RegionMetadata getMetadata() {
        return metadata;
    }

    public RegionBoundingBox getBounds() {
        return boundingBox;
    }

    public Region getParent() {
        return parent;
    }

    public Region[] getChildren() {
        return children;
    }

    public byte getChildCount() {
        return childCount;
    }

    public void addChild(Region child) {
        if (childCount < MAX_CHILDREN) {
            children[childCount++] = child;
            child.parent = this;
            boundingBox.include(child.getBounds());
            metadata.merge(child.getMetadata());
        }
    }

    public void removeChild(Region child) {
        for (int i = 0; i < childCount; i++) {
            if (children[i] == child) {
                for (int j = i; j < childCount - 1; j++) {
                    children[j] = children[j + 1];
                }
                children[--childCount] = null;
                child.parent = null;
                // Optional: recompute bounding box + metadata
                return;
            }
        }
    }

    public Region getRoot() {
        Region current = this;
        while (current.parent != null) current = current.parent;
        return current;
    }

    public static Region getCommonAncestor(Region a, Region b) {
        Region p1 = a;
        Region p2 = b;
        while (p1 != p2) {
            p1 = (p1 != null) ? p1.getParent() : b;
            p2 = (p2 != null) ? p2.getParent() : a;
        }
        return p1;
    }

    public abstract boolean contains(BlockPos pos);

    public void reset() {
        regionId = 0;
        metadata.reset();
        boundingBox.reset();
        for (int i = 0; i < childCount; i++) {
            children[i] = null;
        }
        childCount = 0;
        parent = null;
    }

    public Portal activatePortal(Region target, PortalShape shape, int cost, boolean bidirectional) {
        if (portalCount >= MAX_PORTALS) return null;
    
        Portal portal = portals[portalCount++];
        portal.configure(this, target, shape, cost, bidirectional);
        return portal;
    }

    public void deactivatePortal(int index) {
        if (index >= portalCount) return;
    
        portalCount--;
        Portal tmp = portals[index];
        portals[index] = portals[portalCount];
        portals[portalCount] = tmp;
    }

    public Portal[] getPortals() {
        return portals;
    }

    public byte getPortalCount() {
        return portalCount;
    }

    public BlockPos getCenterBlockPos() {
        RegionBoundingBox box = getBounds();
    
        int centerX = (int) Math.floor((box.minX + box.maxX) * 0.5);
        int centerY = (int) Math.floor((box.minY + box.maxY) * 0.5);
        int centerZ = (int) Math.floor((box.minZ + box.maxZ) * 0.5);
    
        return new BlockPos(centerX, centerY, centerZ);
    }
}
