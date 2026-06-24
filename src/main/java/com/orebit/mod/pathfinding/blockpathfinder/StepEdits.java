package com.orebit.mod.pathfinding.blockpathfinder;

import net.minecraft.core.BlockPos;

/**
 * The world edits a single {@link Movement} folds into one A* edge (MOVEMENT-DESIGN.md §1, decision 1):
 * the cells it must <b>break</b> (solid blocks in the body path it clears) and the cells it must
 * <b>place</b> (missing footing it bridges). Break/place are <i>modifiers</i> on an ordinary move — a
 * Traverse that walks through a leaf is just Traverse with a one-cell break-set — not their own movement
 * kinds, so the edits ride alongside the chosen {@link Movement} on the edge rather than becoming
 * separate (zero-progress) path nodes.
 *
 * <p>Immutable and allocated <b>only when an edge actually carries edits</b> (the overwhelmingly common
 * no-edit move stores {@code null}), so plain walking stays allocation-free. Cells are packed as {@link
 * BlockPos#asLong} <b>floor/world</b> positions (the same packing the search keys on), decoded by the
 * follower at execution time. A single move clears at most the cells its own geometry touches (a
 * Traverse ≤ 2 body cells); tunnelling through thickness is a <i>chain</i> of moves, each with its own
 * small edit-set — never one move mining far.
 */
public final class StepEdits {

    /** Cells to clear (mine) before/while making the move, packed {@link BlockPos#asLong}. */
    private final long[] breaks;
    /** Cells to fill (place a throwaway block on) so the move has footing, packed {@link BlockPos#asLong}. */
    private final long[] places;

    public StepEdits(long[] breaks, long[] places) {
        this.breaks = breaks;
        this.places = places;
    }

    /** Number of cells this move breaks. */
    public int breakCount() {
        return breaks.length;
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
        return places.length;
    }

    /** The {@code i}-th cell to place, as a fresh {@link BlockPos}. */
    public BlockPos placePos(int i) {
        return BlockPos.of(places[i]);
    }

    /** The {@code i}-th cell to place, as a packed {@link BlockPos#asLong} (no allocation). */
    public long placeAt(int i) {
        return places[i];
    }
}
