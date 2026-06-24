package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

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
 * reused open-addressing table — capacity reused across the whole search, and edit-free searches (no
 * break/place) pay nothing via the {@link #isEmpty()} fast path.
 *
 * <h2>Latest-edit-wins</h2>
 * The caller walks the chain from the node back to the start and feeds each edge's {@link StepEdits}; the
 * <b>first</b> kind recorded for a cell wins ({@link #markPlaced}/{@link #markBroken} are no-ops if the
 * cell is already set), so the edit closest to the node (the most recent) takes precedence — a
 * place-then-break (or break-then-place) on one path resolves to the later action.
 */
public final class PathEdits {

    /** No planned edit at this cell — also the empty-slot marker in the table (so it's never stored). */
    public static final int NONE = 0;
    /** The path places a (full, solid) block here — reads as the placed block's geometry. */
    public static final int PLACED = 1;
    /** The path breaks the block here — reads as air. */
    public static final int BROKEN = 2;

    // Open-addressing long→kind table (linear probing, power-of-two capacity). The kind itself is the
    // occupancy marker: a slot is empty iff its value is NONE (0), which PLACED/BROKEN never are — so no
    // key sentinel is needed and reset() is one Arrays.fill, no per-touch boxing of the long key (the sin
    // the HashMap<Long,Integer> committed ~100× per edit-bearing node). One table for the whole search.
    private static final int INITIAL_CAPACITY = 64; // 16³-section locality; grows on demand
    private long[] keys = new long[INITIAL_CAPACITY];
    private byte[] kinds = new byte[INITIAL_CAPACITY];
    private int mask = INITIAL_CAPACITY - 1;
    private int size;
    private int growAt = INITIAL_CAPACITY * 3 / 4;

    /** Clear for a fresh node expansion (keeps the table's capacity for reuse). */
    public void reset() {
        if (size != 0) {
            Arrays.fill(kinds, (byte) NONE);
            size = 0;
        }
    }

    /** True when no edits are recorded — the caller skips the diff lookup entirely (the common case). */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Fold one edge's edits in (first-seen-wins; call while walking node → start). */
    public void add(StepEdits se) {
        if (se == null) return;
        for (int i = 0, n = se.placeCount(); i < n; i++) markIfAbsent(se.placeAt(i), (byte) PLACED);
        for (int i = 0, n = se.breakCount(); i < n; i++) markIfAbsent(se.breakAt(i), (byte) BROKEN);
    }

    /** The planned edit kind at a packed-{@code BlockPos.asLong} cell, or {@link #NONE}. */
    public int kindAt(long pos) {
        if (size == 0) return NONE;
        int slot = slotFor(pos);
        for (;;) {
            byte k = kinds[slot];
            if (k == NONE) return NONE;       // empty slot → not present
            if (keys[slot] == pos) return k;
            slot = (slot + 1) & mask;
        }
    }

    /** Record {@code kind} at {@code pos}, first-seen-wins (no-op if the cell already has any kind). */
    private void markIfAbsent(long pos, byte kind) {
        int slot = slotFor(pos);
        for (;;) {
            byte k = kinds[slot];
            if (k == NONE) {                  // empty → claim it
                keys[slot] = pos;
                kinds[slot] = kind;
                if (++size >= growAt) grow();
                return;
            }
            if (keys[slot] == pos) return;    // already set — first kind wins
            slot = (slot + 1) & mask;
        }
    }

    private int slotFor(long pos) {
        // Murmur3 64-bit finalizer — spreads BlockPos-packed longs (whose low bits are y, then z) across
        // the table so clustered cells in one section don't pile into one probe chain.
        long h = pos;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    private void grow() {
        long[] oldKeys = keys;
        byte[] oldKinds = kinds;
        int cap = oldKeys.length << 1;
        keys = new long[cap];
        kinds = new byte[cap];
        mask = cap - 1;
        growAt = cap * 3 / 4;
        for (int i = 0; i < oldKinds.length; i++) {
            byte k = oldKinds[i];
            if (k == NONE) continue;
            long pos = oldKeys[i];
            int slot = slotFor(pos);
            while (kinds[slot] != NONE) slot = (slot + 1) & mask;
            keys[slot] = pos;
            kinds[slot] = k;
        }
    }
}
