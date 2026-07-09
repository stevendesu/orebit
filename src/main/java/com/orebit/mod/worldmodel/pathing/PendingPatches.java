package com.orebit.mod.worldmodel.pathing;

import java.util.Arrays;

/**
 * The per-level dirty-cell queue behind the deferred block-edit drain
 * (PERF-DESIGN-navgrid-edit-batching.md §4.2): every tracked, grid-visible block change is recorded
 * here by {@link NavGridUpdater#onBlockChanged} instead of being patched inline, and the whole set is
 * drained through {@link NavSectionBuilder#patchCells} at the next flush barrier (a sync search's
 * {@link NavGridView} construction, a bot's tick start, or the world-tick-end catch-all). One instance
 * per {@code ServerLevel}, held in {@code NavGridUpdater}'s {@code WeakHashMap} exactly like
 * {@code EDIT_EPOCH}, and tick-thread confined by the same argument (the mixin fires on the server
 * thread; every barrier runs on the server thread) — no synchronization.
 *
 * <p><b>Structure</b> (the Hot-Path-No-Heap-Alloc rule — no {@code Map<Long,Short>} boxing at
 * per-cell storm volume): an open-addressed {@code long[] keys} / {@code short[] vals} table
 * (power-of-two, linear probe, grow ×2 at load 1/2, start {@value #START_CAP}), key =
 * {@code BlockPos.asLong()} (26+26+12 bits — one long, no chunk+cell packing problem), value = the
 * cell's pending navtype. A re-fired cell overwrites its pending navtype in place —
 * <b>last-state-wins dedup for free</b> (the navtype is a pure function of the block state, so
 * overwriting the stored short is exactly "the deferred patch sees the final state"). A parallel
 * append-order {@code order} array records each key once, at first insert, for drain iteration; the
 * drain's sort/group scratch lives here too so a drain allocates nothing. All arrays are cleared
 * (never freed) at drain.
 *
 * <p><b>No {@code NavSection}/{@code BlockState} refs are ever stored</b> (invariant §4.6-4): keys
 * are packed positions, values are navtype shorts; the drain resolves sections fresh from
 * {@link NavStore} (chunk unloaded since enqueue ⇒ the entry is dropped), so the queue can never
 * fight {@link NavReclaim} retirement or pin a state.
 */
final class PendingPatches {

    private static final int START_CAP = 1024;

    private long[] keys = new long[START_CAP];
    private short[] vals = new short[START_CAP];
    private byte[] used = new byte[START_CAP];
    private long[] order = new long[START_CAP];
    private int mask = START_CAP - 1;
    private int count;

    // Drain scratch (NavGridUpdater.drain): sort keys + per-section-group cell/navtype buffers, grown
    // to the drain size on demand and reused forever after — never allocated per drain.
    private long[] sortScratch = new long[START_CAP];
    private short[] cellScratch = new short[START_CAP];
    private short[] navScratch = new short[START_CAP];

    /**
     * Insert-or-overwrite the pending navtype for {@code key}. Returns {@code true} when the key is
     * NEW (the caller's global pending count tracks first inserts only — an overwrite changes the
     * stored value, not the queue size).
     */
    boolean put(long key, short navtype) {
        int slot = slot(key);
        while (used[slot] != 0) {
            if (keys[slot] == key) {
                vals[slot] = navtype;
                return false;
            }
            slot = (slot + 1) & mask;
        }
        used[slot] = 1;
        keys[slot] = key;
        vals[slot] = navtype;
        order[count++] = key;
        if (count > (mask + 1) >> 1) grow();
        return true;
    }

    /** The pending navtype for {@code key} as an unsigned int ({@code 0..65535}), or {@code -1} if clean. */
    int get(long key) {
        int slot = slot(key);
        while (used[slot] != 0) {
            if (keys[slot] == key) return vals[slot] & 0xFFFF;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    /** Number of distinct dirty cells (O(1) — the "is the queue empty" test every barrier pays). */
    int count() {
        return count;
    }

    /** The {@code i}-th dirty cell's packed position, in first-insert order ({@code 0 <= i < count()}). */
    long keyAt(int i) {
        return order[i];
    }

    /** Empty the queue — slots cleared, arrays kept (the cleared-not-freed drain contract). */
    void clear() {
        Arrays.fill(used, (byte) 0);
        count = 0;
    }

    /** The drain's sort scratch, grown to hold at least {@code n} entries. */
    long[] sortScratch(int n) {
        if (sortScratch.length < n) sortScratch = new long[capFor(n)];
        return sortScratch;
    }

    /** The drain's per-group packed-cell buffer, grown to hold at least {@code n} entries. */
    short[] cellScratch(int n) {
        if (cellScratch.length < n) cellScratch = new short[capFor(n)];
        return cellScratch;
    }

    /** The drain's per-group navtype buffer, grown to hold at least {@code n} entries. */
    short[] navScratch(int n) {
        if (navScratch.length < n) navScratch = new short[capFor(n)];
        return navScratch;
    }

    private static int capFor(int n) {
        return Integer.highestOneBit(Math.max(n - 1, START_CAP)) << 1;
    }

    private void grow() {
        final int cap = (mask + 1) << 1;
        final long[] oldKeys = keys;
        final short[] oldVals = vals;
        final byte[] oldUsed = used;
        keys = new long[cap];
        vals = new short[cap];
        used = new byte[cap];
        order = Arrays.copyOf(order, cap);
        mask = cap - 1;
        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i] == 0) continue;
            int slot = slot(oldKeys[i]);
            while (used[slot] != 0) slot = (slot + 1) & mask;
            used[slot] = 1;
            keys[slot] = oldKeys[i];
            vals[slot] = oldVals[i];
        }
    }

    /** Murmur3 64-bit finalizer → table slot (the same spreader as {@code NavGridView}'s chunk cache). */
    private int slot(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k & mask;
    }
}
