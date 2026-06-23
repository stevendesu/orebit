package com.orebit.mod.worldmodel.pathing;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.ChunkCoords;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Wires chunk lifecycle to the nav-grid pipeline: on load, queue the chunk; at the end of that
 * level's tick, build its {@link NavSection}[] and store it in {@link NavStore}; on unload, drop and
 * recycle it.
 *
 * <p><b>Why defer to the tick:</b> some loaders fire chunk-load on an async worker, and the build
 * touches a non-thread-safe section pool — so building on the tick thread keeps it single-threaded
 * for now. (The classify kernel is already thread-safe for a future background build.)
 *
 * <p><b>Per-level queue:</b> a chunk must be built against the level it loaded in; the old single
 * global queue drained against whichever level happened to tick, mixing dimensions.
 *
 * <p><b>Per-tick budget:</b> a teleport can load hundreds of chunks at once; draining them all in
 * one tick would spike. We build at most {@link #MAX_BUILDS_PER_TICK} per level per tick and leave
 * the rest queued — the bot's nav data fills in over a few ticks rather than stalling the server.
 */
public final class ChunkNavLoader {

    private ChunkNavLoader() {}

    /** Cap on chunk builds per level per tick, to keep nav recompute off the frame budget. */
    private static final int MAX_BUILDS_PER_TICK = 8;

    private static final Map<ServerLevel, Queue<Long>> pending = new ConcurrentHashMap<>();

    // Diagnostics until a consumer exists: confirm the pipeline is alive without log spam.
    private static int totalBuilt = 0;
    private static int nextLogAt = 1;

    // Chunk-key packing lives on NavStore now (the owner of the key space), so the consumer
    // (NavGridView) reads entries back with the exact same packing this loader writes them with.

    public static void register(PlatformEvents events) {
        events.onChunkLoad((level, chunk) ->
                pending.computeIfAbsent(level, l -> new ConcurrentLinkedQueue<>())
                        .add(NavStore.key(ChunkCoords.x(chunk.getPos()), ChunkCoords.z(chunk.getPos()))));

        events.onWorldTickEnd(level -> {
            Queue<Long> queue = pending.get(level);
            if (queue == null) return;
            int built = 0;
            Long k;
            while (built < MAX_BUILDS_PER_TICK && (k = queue.poll()) != null) {
                ChunkAccess chunk = level.getChunk(NavStore.keyX(k), NavStore.keyZ(k));
                NavStore.put(level, k, ChunkNavBuilder.buildAllSections(level, chunk));
                built++;
            }
            if (built > 0) {
                totalBuilt += built;
                if (totalBuilt >= nextLogAt) {
                    OrebitCommon.LOGGER.info("[Orebit] nav grid active: {} chunks built ({} stored in this level)",
                            totalBuilt, NavStore.size(level));
                    nextLogAt += 1024;
                }
            }
        });

        events.onChunkUnload((level, chunk) ->
                NavStore.remove(level, NavStore.key(ChunkCoords.x(chunk.getPos()), ChunkCoords.z(chunk.getPos()))));
    }
}
