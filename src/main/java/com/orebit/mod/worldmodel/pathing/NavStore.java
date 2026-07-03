package com.orebit.mod.worldmodel.pathing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;

/**
 * Per-level store of recomputed nav data: chunk (packed-long key) → {@link NavSection}[] (one per
 * 16³ section, indexed bottom-up as {@link ChunkNavBuilder} produces them). The nav grid is
 * recomputed on chunk load and dropped on unload — never persisted (PRD §6.2).
 *
 * <p>{@link ConcurrentHashMap} is a deliberate parallel-ready choice: today the pipeline builds on
 * the server tick thread, but the classify kernel ({@code NavSectionBuilder.classifyInto}) is
 * thread-safe, so a future background build can write here without changing the store. Replacing or
 * removing an entry returns its NavSections to {@link NavSectionPool}.
 */
public final class NavStore {

    private NavStore() {}

    // ---- Chunk-key packing (the shared key space for all nav data) ---------------------------
    // Orebit's OWN packing — never compared to vanilla's, so it sidesteps ChunkPos.toLong()/
    // new ChunkPos(long), both removed when 26.1 made ChunkPos a record. ChunkNavLoader writes
    // entries with these; consumers (NavGridView) read them back the same way. Chunk X/Z come
    // through the ChunkCoords overlay (public field vs x()/z()) at the call site.

    /** Pack a chunk's X/Z into the {@link NavStore} key. */
    public static long key(int chunkX, int chunkZ) { return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL); }

    /** Chunk X from a packed key. */
    public static int keyX(long key) { return (int) (key >> 32); }

    /** Chunk Z from a packed key. */
    public static int keyZ(long key) { return (int) key; }

    private static final Map<ServerLevel, ConcurrentHashMap<Long, NavSection[]>> BY_LEVEL = new ConcurrentHashMap<>();

    private static ConcurrentHashMap<Long, NavSection[]> levelMap(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
    }

    /** Store nav sections for a chunk, retiring any previously-stored sections for that chunk. */
    public static void put(ServerLevel level, long chunkKey, NavSection[] sections) {
        NavReclaim.retire(levelMap(level).put(chunkKey, sections));
    }

    /** The nav sections for a chunk, or {@code null} if not currently built/loaded. */
    public static NavSection[] get(ServerLevel level, long chunkKey) {
        ConcurrentHashMap<Long, NavSection[]> m = BY_LEVEL.get(level);
        return m == null ? null : m.get(chunkKey);
    }

    /**
     * The per-level chunk → sections map (or {@code null} if no nav data exists for the level yet) — the
     * backing store a single-threaded reader ({@link NavGridView}) resolves <b>once</b> so its hot per-cell
     * reads skip the {@code BY_LEVEL.get(level)} hop and box only the chunk key (and only when the chunk
     * changes), not on every cell. The returned map is the live store; the reader must not mutate it.
     */
    public static ConcurrentHashMap<Long, NavSection[]> chunksOf(ServerLevel level) {
        return BY_LEVEL.get(level);
    }

    /** Drop a chunk's nav sections (on unload), retiring them toward the pool. */
    public static void remove(ServerLevel level, long chunkKey) {
        ConcurrentHashMap<Long, NavSection[]> m = BY_LEVEL.get(level);
        if (m != null) NavReclaim.retire(m.remove(chunkKey));
    }

    /** Number of chunks currently stored for a level (diagnostics). */
    public static int size(ServerLevel level) {
        ConcurrentHashMap<Long, NavSection[]> m = BY_LEVEL.get(level);
        return m == null ? 0 : m.size();
    }

    /** Drop and retire every chunk in a level (on level/server unload). */
    public static void clear(ServerLevel level) {
        ConcurrentHashMap<Long, NavSection[]> m = BY_LEVEL.remove(level);
        if (m != null) {
            for (NavSection[] sections : m.values()) NavReclaim.retire(sections);
        }
    }

    // Displaced sections are NOT recycled inline anymore: a planner-thread search may still hold them
    // (use-after-recycle would read another chunk's cells). NavReclaim parks them until no in-flight
    // search predates the retirement, then returns them to NavSectionPool on the tick thread — see
    // DESIGN-background-pathfinding.md §4.1.
}
