package com.orebit.mod.worldmodel.pathing;

public enum TraversalClass {
    CLEAR(0),     // Solid ground with 2+ air above — ideal pathing
    EASY(1),      // Jumpable gaps, breakable blocks, leaf-like blocks
    SLOW(2),      // Water, cobwebs, vines — traversable but slow
    BLOCKED(3);   // Lava, bedrock, obsidian, fire — high-cost, avoid unless necessary

    public final int id;

    TraversalClass(int id) {
        this.id = id;
    }

    public static TraversalClass fromId(int id) {
        return switch (id) {
            case 0 -> CLEAR;
            case 1 -> EASY;
            case 2 -> SLOW;
            case 3 -> BLOCKED;
            default -> throw new IllegalArgumentException("Invalid TraversalClass ID: " + id);
        };
    }

    public int cost() {
        return switch (this) {
            case CLEAR -> 1;
            case EASY -> 3;
            case SLOW -> 8;
            case BLOCKED -> 64;
        };
    }
}
