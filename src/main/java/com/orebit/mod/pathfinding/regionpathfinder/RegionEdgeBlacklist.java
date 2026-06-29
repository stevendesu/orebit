package com.orebit.mod.pathfinding.regionpathfinder;

/**
 * A small, growable set of <b>forbidden directed region→region crossings</b> for one navigation — the
 * region-tier half of the "recover when stuck" repair (HPA online edge invalidation, the D-Star / LPA idea).
 *
 * <p>When the block tier <i>proves</i> it can't realize a skeleton hop for the bot's caps — a no-break,
 * no-place bot routed at a one-way drop into a cave (the irreversibility guard refuses it) or at a face it
 * can't dig through — that hop is added here, and {@link RegionPathfinder} skips it on the next replan, so the
 * region A* finds the <b>next-best route</b> (the large walk-around that only the region tier can discover
 * cheaply; the block tier would flood looking for it). If every route is blacklisted the region plan FAILs and
 * the bot gives up gracefully ("can't reach you") rather than looping.
 *
 * <p>An edge is a pair of {@link RegionPathfinder#fragmentNodeKey (region,fragment) node keys} {@code
 * (fromKey, toKey)}. The set is tiny (a handful of edges per stuck episode) and only consulted during the
 * region A* — itself a once-per-replan cold path — so a plain parallel-{@code long[]} linear scan is used: it
 * keeps the per-edge check <b>allocation-free</b> (no lookup-key object per region-A* expansion), which a
 * {@code Set<pair>} could not. Held on the bot, cleared when the goal changes.
 */
public final class RegionEdgeBlacklist {

    private long[] from = new long[8];
    private long[] to = new long[8];
    private int size;

    /** Forbid the directed crossing {@code fromKey → toKey} (no-op if already present). */
    public void add(long fromKey, long toKey) {
        if (contains(fromKey, toKey)) return;
        if (size == from.length) {
            from = java.util.Arrays.copyOf(from, size * 2);
            to = java.util.Arrays.copyOf(to, size * 2);
        }
        from[size] = fromKey;
        to[size] = toKey;
        size++;
    }

    /** Whether {@code fromKey → toKey} is forbidden — the per-region-A*-edge check (linear, tiny set). */
    public boolean contains(long fromKey, long toKey) {
        for (int i = 0; i < size; i++) {
            if (from[i] == fromKey && to[i] == toKey) return true;
        }
        return false;
    }

    /** Drop every forbidden edge (called when the navigation goal changes). */
    public void clear() {
        size = 0;
    }

    /** Number of forbidden edges — {@code > 0} after a stuck episode signals the repair has fired. */
    public int size() {
        return size;
    }
}
