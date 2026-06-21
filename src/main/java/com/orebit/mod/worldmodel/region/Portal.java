package com.orebit.mod.worldmodel.region;

public class Portal {
    private Region source;
    private Region target;
    private PortalShape shape;
    private int cost;
    private boolean bidirectional;

    public Portal() {
        this.source = null;
        this.target = null;
        this.shape = null;
        this.cost = 0;
        this.bidirectional = false;
    }

    public void configure(Region source, Region target, PortalShape shape, int cost, boolean bidirectional) {
        this.source = source;
        this.target = target;
        this.shape = shape;
        this.cost = cost;
        this.bidirectional = bidirectional;
    }

    public Region getSource() {
        return source;
    }

    public Region getTarget() {
        return target;
    }

    public PortalShape getShape() {
        return shape;
    }

    public int getCost() {
        return cost;
    }

    public boolean isBidirectional() {
        return bidirectional;
    }

    public Region getOther(Region current) {
        return current == source ? target : source;
    }
}
