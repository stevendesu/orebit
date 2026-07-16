package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

import net.minecraft.core.BlockPos;

/**
 * The world edits a single {@link Movement} folds into one A* edge (MOVEMENT-DESIGN.md §1, decision 1):
 * the cells it must <b>break</b> (solid blocks in the body path it clears) and the cells it must
 * <b>place</b> (missing footing it bridges). Break/place are <i>modifiers</i> on an ordinary move — a
 * Traverse that walks through a leaf is just Traverse with a one-cell break-set — not their own movement
 * kinds, so the edits ride alongside the chosen {@link Movement} on the edge rather than becoming
 * separate (zero-progress) path nodes. Cells are packed as {@link BlockPos#asLong} <b>floor/world</b>
 * positions (the same packing the search keys on), decoded by the follower at execution time.
 *
 * <p><b>Pooled during search, copied out for a returned plan.</b> A build-heavy search expands thousands
 * of edit-bearing edges, so allocating a fresh set per accepted edge was the dominant remaining allocator
 * (≈97% of it — see docs/Optimizations/05_pathfinding_hot_path.md). Instead the search hands each accepted
 * edge a reusable instance from a per-search arena ({@link BlockPathfinder}'s {@code EditPool}) and
 * {@link #load}s its cells from the movement's accumulator — zero allocation in steady state. The count is
 * a field, not the array length, precisely so the backing buffers can be reused/grown. The few edits on
 * the FINAL returned path outlive the arena (the follower replays them over many ticks while later
 * searches reuse it), so {@code reconstruct} takes an arena-independent {@link #copy} of each.
 */
public final class StepEdits {

    /** Cells to clear (mine), packed {@link BlockPos#asLong}; only the first {@link #breakCount} are live. */
    private long[] breaks;
    private int breakCount;
    /** Cells to fill (place a throwaway block on), packed {@link BlockPos#asLong}; first {@link #placeCount}. */
    private long[] places;
    private int placeCount;
    /**
     * Cells whose (hand-toggleable) door this move OPENS/CLOSES (DOORS P2), packed {@link BlockPos#asLong};
     * only the first {@link #doorSetCount} are live, and {@link #doorSetOpen} at the same index says whether
     * the target state is OPEN ({@code true}) or CLOSED ({@code false}). Distinct from break/place because a
     * door-set is an <b>absolute-SET</b> edit resolved against the door's own facing/hinge, not a constant
     * geometry (see {@link PathEdits#SET_OPEN}). Almost always empty (a door crossing is rare), so the extra
     * pair of buffers costs nothing on the common edge.
     */
    private long[] doorSets;
    private boolean[] doorSetOpen;
    private int doorSetCount;

    /** A poolable, initially-empty edit set; {@link #load} fills it (growing the buffers as needed). */
    StepEdits() {
        this.breaks = new long[4];
        this.places = new long[2];
        this.doorSets = new long[2];
        this.doorSetOpen = new boolean[2];
    }

    private StepEdits(long[] breaks, int breakCount, long[] places, int placeCount,
                      long[] doorSets, boolean[] doorSetOpen, int doorSetCount) {
        this.breaks = breaks;
        this.breakCount = breakCount;
        this.places = places;
        this.placeCount = placeCount;
        this.doorSets = doorSets;
        this.doorSetOpen = doorSetOpen;
        this.doorSetCount = doorSetCount;
    }

    /**
     * Overwrite this (pooled) set from a movement accumulator's buffers, growing the backing arrays only
     * when a longer edit-set than ever before turns up. Steady state touches no heap.
     */
    void load(long[] srcBreaks, int bn, long[] srcPlaces, int pn,
              long[] srcDoors, boolean[] srcDoorOpen, int dn) {
        if (breaks.length < bn) breaks = new long[Math.max(bn, breaks.length << 1)];
        System.arraycopy(srcBreaks, 0, breaks, 0, bn);
        breakCount = bn;
        if (places.length < pn) places = new long[Math.max(pn, places.length << 1)];
        System.arraycopy(srcPlaces, 0, places, 0, pn);
        placeCount = pn;
        if (doorSets.length < dn) {
            doorSets = new long[Math.max(dn, doorSets.length << 1)];
            doorSetOpen = new boolean[doorSets.length];
        }
        System.arraycopy(srcDoors, 0, doorSets, 0, dn);
        System.arraycopy(srcDoorOpen, 0, doorSetOpen, 0, dn);
        doorSetCount = dn;
    }

    /** An arena-independent, exact-size copy — for the final path's edits handed to a {@link BlockPathPlan}. */
    StepEdits copy() {
        return new StepEdits(Arrays.copyOf(breaks, breakCount), breakCount,
                             Arrays.copyOf(places, placeCount), placeCount,
                             Arrays.copyOf(doorSets, doorSetCount), Arrays.copyOf(doorSetOpen, doorSetCount),
                             doorSetCount);
    }

    /** Number of cells this move breaks. */
    public int breakCount() {
        return breakCount;
    }

    /** The {@code i}-th cell to break, as a fresh {@link BlockPos}. */
    public BlockPos breakPos(int i) {
        return BlockPos.of(breaks[i]);
    }

    /** The {@code i}-th cell to break, as a packed {@link BlockPos#asLong} (no allocation). */
    public long breakAt(int i) {
        return breaks[i];
    }

    /** Number of cells this move places. */
    public int placeCount() {
        return placeCount;
    }

    /** The {@code i}-th cell to place, as a fresh {@link BlockPos}. */
    public BlockPos placePos(int i) {
        return BlockPos.of(places[i]);
    }

    /** The {@code i}-th cell to place, as a packed {@link BlockPos#asLong} (no allocation). */
    public long placeAt(int i) {
        return places[i];
    }

    /** Number of doors this move opens/closes (DOORS P2) — almost always 0. */
    public int doorSetCount() {
        return doorSetCount;
    }

    /** The {@code i}-th door cell this move opens/closes, packed {@link BlockPos#asLong} (no allocation). */
    public long doorSetAt(int i) {
        return doorSets[i];
    }

    /** Whether the {@code i}-th door-set targets the OPEN ({@code true}) or CLOSED ({@code false}) state. */
    public boolean doorSetOpenAt(int i) {
        return doorSetOpen[i];
    }
}
