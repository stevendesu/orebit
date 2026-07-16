package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

import net.minecraft.core.BlockPos;

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
    /**
     * The path OPENS a (hand-toggleable) door here (DOORS P2) — an <b>absolute-SET</b> edit: unlike {@link
     * #PLACED}/{@link #BROKEN} (which resolve to a constant geometry — cobblestone / air), a door-set resolves
     * to THAT door's own facing/hinge forced into the target OPEN state ({@link MovementContext#descriptorAt}
     * reads the grid door and applies {@link com.orebit.mod.worldmodel.navblock.NavBlock#withDoorOpen}). Folds
     * through the same latest-wins machinery ({@link #markIfAbsent}) with no parity/XOR: a {@code SET_OPEN} then
     * {@code SET_CLOSED} on one cell (the hallway-corner double-toggle) resolves — walking node→start,
     * first-seen-wins — to the edit CLOSEST to the node, exactly like a place-then-break.
     */
    public static final int SET_OPEN = 3;
    /** The path CLOSES a (hand-toggleable) door here (DOORS P2) — the absolute-SET counterpart of {@link
     *  #SET_OPEN}; resolves the door forced into the target CLOSED state. */
    public static final int SET_CLOSED = 4;

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

    // Insertion-order list of every edited cell (packed BlockPos), parallel to the table — the DENSE
    // iteration seam. A consumer that needs "which edits lie inside this box" (the cuboid edit-shrink)
    // iterates these {@code size} entries directly instead of hash-probing every cell of its scan volume
    // (which on a tall pillar column was O(volume) murmur probes per shrink pass — ~9% of TOWER search CPU).
    // No duplicates by construction: an entry is appended only when {@link #markIfAbsent} claims an empty
    // slot (first-seen-wins already dedups). Capacity mirrors the table's (grown in lockstep; the table
    // grows at 3/4 load so the list never overflows first). reset() just zeroes {@code size}.
    private long[] editList = new long[INITIAL_CAPACITY];

    // Inclusive axis-aligned bounding box of every edited cell currently in the table — the cheap reject the
    // per-cell {@link #kindAt(int, int, int)} read uses BEFORE hashing. A search's edits cluster in a tiny
    // region (a pillar is one column; a dug step is a few cells), yet each expanded node reads ~100 cells
    // spread around it, almost all far from any edit. Six int compares against this box short-circuit those
    // far reads to NONE for the cost of a hash + probe each — the bulk of the per-node descriptor cost on an
    // edit-heavy search (it was ~25% of the TOWER profile). Empty box (min>max) when {@code size==0}, so the
    // {@code size==0} guard already rejects everything before the box is consulted.
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    /** Clear for a fresh node expansion (keeps the table's capacity for reuse). */
    public void reset() {
        if (size != 0) {
            Arrays.fill(kinds, (byte) NONE);
            size = 0;
            minX = minY = minZ = Integer.MAX_VALUE;
            maxX = maxY = maxZ = Integer.MIN_VALUE;
        }
    }

    /** True when no edits are recorded — the caller skips the diff lookup entirely (the common case). */
    public boolean isEmpty() {
        return size == 0;
    }

    // Inclusive bounding box of every edited cell — exposed so a consumer can scan only the box ∩ edits
    // intersection instead of its own (possibly huge) volume (the cuboid edit-shrink, MACRO-IMPLEMENTATION
    // §5: "check the handful of current-path edits against the one box"). Meaningful only when !isEmpty().
    public int editMinX() { return minX; }
    public int editMinY() { return minY; }
    public int editMinZ() { return minZ; }
    public int editMaxX() { return maxX; }
    public int editMaxY() { return maxY; }
    public int editMaxZ() { return maxZ; }

    /** Number of edited cells — the bound for {@link #editAt} iteration. */
    public int editCount() { return size; }

    /**
     * The {@code i}-th edited cell (packed {@code BlockPos.asLong}), in insertion order, {@code 0 <= i <
     * editCount()}. Every listed cell has a non-{@link #NONE} kind (the list is fed only when a cell is
     * first claimed), so an "is any edit inside this box" consumer needs no per-entry {@link #kindAt} call.
     */
    public long editAt(int i) { return editList[i]; }

    /** Fold one edge's edits in (first-seen-wins; call while walking node → start). */
    public void add(StepEdits se) {
        if (se == null) return;
        for (int i = 0, n = se.placeCount(); i < n; i++) markIfAbsent(se.placeAt(i), (byte) PLACED);
        for (int i = 0, n = se.breakCount(); i < n; i++) markIfAbsent(se.breakAt(i), (byte) BROKEN);
        for (int i = 0, n = se.doorSetCount(); i < n; i++)
            markIfAbsent(se.doorSetAt(i), (byte) (se.doorSetOpenAt(i) ? SET_OPEN : SET_CLOSED));
    }

    /**
     * Fold a splice {@linkplain EditSnapshot baseline} in — the edits an EARLIER, still-executing plan
     * will have applied by the time the bot stands here (the splice primitive's seed,
     * DESIGN-background-pathfinding.md §7). Call AFTER the {@code cameFrom}-chain {@link #add} walk:
     * first-seen-wins then makes the in-search path's own edits correctly shadow the baseline (the
     * baseline is the oldest history), the exact mirror of latest-edit-wins along one path.
     */
    public void addSnapshot(EditSnapshot s) {
        if (s == null) return;
        for (int i = 0, n = s.placeCount(); i < n; i++) markIfAbsent(s.placeAt(i), (byte) PLACED);
        for (int i = 0, n = s.breakCount(); i < n; i++) markIfAbsent(s.breakAt(i), (byte) BROKEN);
        for (int i = 0, n = s.doorSetCount(); i < n; i++)
            markIfAbsent(s.doorSetAt(i), (byte) (s.doorSetOpenAt(i) ? SET_OPEN : SET_CLOSED));
    }

    /**
     * The planned edit kind at cell {@code (x,y,z)}, or {@link #NONE} — the read-once form the movement
     * layer uses. Rejects cells outside the edits' {@linkplain #minX bounding box} with six int compares
     * before paying the hash + probe of {@link #kindAt(long)}; since a search's edits cluster tightly while
     * a node's reads fan out around it, most reads reject here. Equivalent to {@code kindAt(BlockPos.asLong(
     * x,y,z))} for in-box cells, and to {@link #NONE} (the same answer) for out-of-box ones.
     */
    public int kindAt(int x, int y, int z) {
        if (size == 0) return NONE;
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return NONE;
        return kindAt(BlockPos.asLong(x, y, z));
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
                editList[size] = pos;         // dense iteration list — first claim only, so no duplicates
                int cx = BlockPos.getX(pos), cy = BlockPos.getY(pos), cz = BlockPos.getZ(pos);
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                if (cz < minZ) minZ = cz;
                if (cz > maxZ) maxZ = cz;
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
        editList = Arrays.copyOf(editList, cap); // lockstep with the table so the append never overflows
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
