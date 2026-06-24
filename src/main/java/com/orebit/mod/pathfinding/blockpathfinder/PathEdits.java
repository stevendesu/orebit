package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.HashMap;
import java.util.Map;

/**
 * The block edits (places + breaks) along the A* path to the node currently being expanded — a small
 * <b>diff over the nav grid</b> so a movement reads the world as it <i>will be</i> after the moves that
 * led here, not just the live grid. {@link MovementContext#descriptorAt} consults this first and falls
 * back to the grid, so every movement — present and future — sees its own (and its predecessors') planned
 * placements and removals for free, instead of each one re-deriving "is the block I'm about to stand on
 * really there." (Deliberately <i>not</i> called an "overlay" — that term is the version-portability
 * mechanism in this codebase.)
 *
 * <h2>Per-path, not global — the crux</h2>
 * A* explores a fan of competing partial paths at once and pops nodes in {@code f}-order (jumping around
 * the tree). The edits in effect at a node are only those along the <b>single path that reached it</b>; a
 * global edit set would leak a sibling branch's speculative blocks into an unrelated node. So this is
 * <b>rebuilt per pop</b> from the node's {@code cameFrom} chain (see {@link BlockPathfinder}) into one
 * reused map — one allocation for the whole search, and edit-free searches (no break/place) pay nothing
 * via the {@link #isEmpty()} fast path.
 *
 * <h2>Latest-edit-wins</h2>
 * The caller walks the chain from the node back to the start and feeds each edge's {@link StepEdits}; the
 * <b>first</b> kind recorded for a cell wins ({@link #markPlaced}/{@link #markBroken} are no-ops if the
 * cell is already set), so the edit closest to the node (the most recent) takes precedence — a
 * place-then-break (or break-then-place) on one path resolves to the later action.
 */
public final class PathEdits {

    /** No planned edit at this cell. */
    public static final int NONE = 0;
    /** The path places a (full, solid) block here — reads as the placed block's geometry. */
    public static final int PLACED = 1;
    /** The path breaks the block here — reads as air. */
    public static final int BROKEN = 2;

    private final Map<Long, Integer> edits = new HashMap<>();
    private boolean any;

    /** Clear for a fresh node expansion (keeps the map's capacity for reuse). */
    public void reset() {
        if (any) edits.clear();
        any = false;
    }

    /** True when no edits are recorded — the caller skips the diff lookup entirely (the common case). */
    public boolean isEmpty() {
        return !any;
    }

    /** Fold one edge's edits in (first-seen-wins; call while walking node → start). */
    public void add(StepEdits se) {
        if (se == null) return;
        for (int i = 0, n = se.placeCount(); i < n; i++) markPlaced(se.placeAt(i));
        for (int i = 0, n = se.breakCount(); i < n; i++) markBroken(se.breakAt(i));
    }

    /** The planned edit kind at a packed-{@code BlockPos.asLong} cell, or {@link #NONE}. */
    public int kindAt(long pos) {
        if (!any) return NONE;
        Integer k = edits.get(pos);
        return k == null ? NONE : k;
    }

    private void markPlaced(long pos) {
        if (edits.putIfAbsent(pos, PLACED) == null) any = true;
    }

    private void markBroken(long pos) {
        if (edits.putIfAbsent(pos, BROKEN) == null) any = true;
    }
}
