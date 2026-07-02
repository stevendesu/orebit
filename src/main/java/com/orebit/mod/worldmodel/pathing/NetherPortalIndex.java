package com.orebit.mod.worldmodel.pathing;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Per-level index of known <b>nether-portal</b> cells — the discovery layer behind the follower's
 * portal-follow (owner changed dimension → bot paths to the nearest known portal and walks in).
 * "NetherPortal" is deliberate: {@code worldmodel.region.Portal} and {@code PathPlan.TargetKind.PORTAL}
 * mean HPA region-boundary openings, a different concept entirely.
 *
 * <h2>Shape</h2>
 * Mirrors {@link NavStore}'s per-level layout: {@code ServerLevel → ConcurrentHashMap<chunkKey, long[]>}
 * where the chunk key is {@link NavStore#key} (the shared nav key space) and the value is the packed
 * world positions of every {@code NETHER_PORTAL} cell in that chunk. Positions use this class's OWN
 * packing ({@link #pack}/{@link #unpackX}) — not {@code BlockPos.asLong} — for the same reason NavStore
 * owns its chunk key: no dependence on a vanilla encoding that may drift across 1.17→26.x.
 *
 * <h2>Feeds (all cheap / incremental)</h2>
 * <ul>
 *   <li><b>Full-section classify</b>: {@code NavSectionBuilder.classifyInto} detects portals with one
 *       bit-test per palette entry and collects cell indices only when the palette actually contains one;
 *       {@code ChunkNavLoader} then {@link #record}s the whole chunk's cells at the {@code NavStore.put}
 *       site — a wholesale replace, idempotent on rebuild.</li>
 *   <li><b>Incremental</b>: {@code NavGridUpdater} {@link #add}s/{@link #removeCell}s single cells as a
 *       live block change patches a portal in or out.</li>
 *   <li><b>Eviction</b>: {@link #remove} runs beside {@code NavStore.remove} on chunk unload;
 *       {@link #clear} is this index's counterpart to {@code NavStore.clear} for level unload (like
 *       NavStore's, currently unwired — no level-unload event seam exists yet; wire both together).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Writers ({@link #record}/{@link #add}/{@link #removeCell}/{@link #remove}) run on the server tick
 * thread today (ChunkNavLoader defers chunk builds to tick end; NavGridUpdater fires from the
 * server-side {@code setBlockState} seam), and the reader {@link #nearest} runs on the server tick
 * (once per portal-seek — never per tick, never on the A* hot path). {@link ConcurrentHashMap} plus
 * copy-on-write {@code long[]} values make the index safe for the same future background chunk build
 * NavStore is parallel-ready for, at zero structural cost.
 *
 * <h2>Memory</h2>
 * Portals are rare: almost every chunk has NO entry (an empty collect {@link #record}s nothing and
 * removes any stale entry), and a populated entry is one boxed key + a handful of longs. Entries live
 * exactly as long as the chunk's nav data does.
 */
public final class NetherPortalIndex {

    private NetherPortalIndex() {}

    private static final Map<ServerLevel, LevelIndex> BY_LEVEL = new ConcurrentHashMap<>();

    // ---- Cell packing (owned here; BlockPos.asLong-shaped but version-independent) -------------
    // x: bits 38–63 (26 bits), z: bits 12–37 (26 bits), y: bits 0–11 (12 bits) — all signed.

    /** Pack a world cell into the index's 64-bit position key. */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    /** World X from a packed cell (sign-extended). */
    public static int unpackX(long cell) { return (int) (cell >> 38); }

    /** World Z from a packed cell (sign-extended). */
    public static int unpackZ(long cell) { return (int) (cell << 26 >> 38); }

    /** World Y from a packed cell (sign-extended; ±2047 covers every supported build height). */
    public static int unpackY(long cell) { return (int) (cell << 52 >> 52); }

    // ---- Public API (the ServerLevel-keyed facade) ---------------------------------------------

    /**
     * Replace a chunk's portal cells wholesale (the full-classify feed — idempotent on rebuild).
     * {@code null}/empty cells drop the entry, so a chunk whose portals were all broken since the last
     * build self-cleans.
     */
    public static void record(ServerLevel level, long chunkKey, long[] cells) {
        if (cells == null || cells.length == 0) {
            remove(level, chunkKey);
            return;
        }
        BY_LEVEL.computeIfAbsent(level, l -> new LevelIndex()).record(chunkKey, cells);
    }

    /** Drop a chunk's portal cells (chunk unload — run beside {@code NavStore.remove}). */
    public static void remove(ServerLevel level, long chunkKey) {
        LevelIndex idx = BY_LEVEL.get(level);
        if (idx != null) idx.remove(chunkKey);
    }

    /** Drop every entry for a level (level/server unload — run beside {@code NavStore.clear}). */
    public static void clear(ServerLevel level) {
        BY_LEVEL.remove(level);
    }

    /** Incremental feed: a live block change turned this cell into a portal. */
    public static void add(ServerLevel level, int x, int y, int z) {
        BY_LEVEL.computeIfAbsent(level, l -> new LevelIndex()).add(x, y, z);
    }

    /** Incremental feed: a live block change removed the portal that was at this cell. */
    public static void removeCell(ServerLevel level, int x, int y, int z) {
        LevelIndex idx = BY_LEVEL.get(level);
        if (idx != null) idx.removeCell(x, y, z);
    }

    /**
     * The nearest known portal cell to {@code from} in {@code level}, or {@code null} if none is known.
     * Linear scan over every recorded cell — portals are rare and this runs once per portal-seek (cold),
     * so no spatial structure is warranted.
     */
    public static BlockPos nearest(ServerLevel level, BlockPos from) {
        LevelIndex idx = BY_LEVEL.get(level);
        if (idx == null) return null;
        Long cell = idx.nearestPacked(from.getX(), from.getY(), from.getZ());
        return cell == null ? null : new BlockPos(unpackX(cell), unpackY(cell), unpackZ(cell));
    }

    /** Number of known portal cells in a level (diagnostics). */
    public static int size(ServerLevel level) {
        LevelIndex idx = BY_LEVEL.get(level);
        return idx == null ? 0 : idx.size();
    }

    // ---- Collector for the chunk-build feed ----------------------------------------------------

    /**
     * Growable packed-cell buffer the chunk-build pipeline hands down as a {@link LongConsumer}
     * (see {@code ChunkNavBuilder.buildAllSections}). Allocates its backing array only on the first
     * portal cell, so the overwhelming no-portal chunk pays one null field per build.
     */
    public static final class CellBuffer implements LongConsumer {
        private long[] cells;
        private int n;

        @Override
        public void accept(long cell) {
            if (cells == null) {
                cells = new long[4];
            } else if (n == cells.length) {
                cells = Arrays.copyOf(cells, cells.length * 2);
            }
            cells[n++] = cell;
        }

        /** The collected cells right-sized, or {@code null} if none were collected. */
        public long[] toArray() {
            return n == 0 ? null : Arrays.copyOf(cells, n);
        }
    }

    // ---- Per-level index (package-private so the headless test can exercise it without a ServerLevel) ----

    /** One level's chunkKey → packed portal cells. Values are copy-on-write (replaced, never mutated). */
    static final class LevelIndex {

        private final ConcurrentHashMap<Long, long[]> byChunk = new ConcurrentHashMap<>();

        void record(long chunkKey, long[] cells) {
            if (cells == null || cells.length == 0) byChunk.remove(chunkKey);
            else byChunk.put(chunkKey, cells);
        }

        void remove(long chunkKey) {
            byChunk.remove(chunkKey);
        }

        void add(int x, int y, int z) {
            final long cell = pack(x, y, z);
            byChunk.compute(NavStore.key(x >> 4, z >> 4), (k, old) -> {
                if (old == null) return new long[] { cell };
                if (indexOf(old, cell) >= 0) return old; // already known (idempotent)
                long[] grown = Arrays.copyOf(old, old.length + 1);
                grown[old.length] = cell;
                return grown;
            });
        }

        void removeCell(int x, int y, int z) {
            final long cell = pack(x, y, z);
            byChunk.computeIfPresent(NavStore.key(x >> 4, z >> 4), (k, old) -> {
                int i = indexOf(old, cell);
                if (i < 0) return old;
                if (old.length == 1) return null; // last cell → drop the chunk entry entirely
                long[] shrunk = new long[old.length - 1];
                System.arraycopy(old, 0, shrunk, 0, i);
                System.arraycopy(old, i + 1, shrunk, i, old.length - 1 - i);
                return shrunk;
            });
        }

        /** Nearest recorded cell to {@code (fx,fy,fz)} by squared euclidean distance, or {@code null}. */
        Long nearestPacked(int fx, int fy, int fz) {
            long bestD = Long.MAX_VALUE;
            long best = 0;
            boolean found = false;
            for (long[] cells : byChunk.values()) {
                for (long c : cells) {
                    long dx = unpackX(c) - fx;
                    long dy = unpackY(c) - fy;
                    long dz = unpackZ(c) - fz;
                    long d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                        found = true;
                    }
                }
            }
            return found ? best : null;
        }

        int size() {
            int n = 0;
            for (long[] cells : byChunk.values()) n += cells.length;
            return n;
        }

        private static int indexOf(long[] cells, long cell) {
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == cell) return i;
            }
            return -1;
        }
    }
}
