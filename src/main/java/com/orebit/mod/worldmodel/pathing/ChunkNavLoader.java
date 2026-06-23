package com.orebit.mod.worldmodel.pathing;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.orebit.mod.platform.ChunkCoords;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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

    public static void register(PlatformEvents events) {
        events.onChunkLoad((level, chunk) ->
                pending.computeIfAbsent(level, l -> new ConcurrentLinkedQueue<>())
                        .add(chunk.getPos().toLong()));

        events.onWorldTickEnd(level -> {
            Queue<Long> queue = pending.get(level);
            if (queue == null) return;
            int built = 0;
            Long key;
            while (built < MAX_BUILDS_PER_TICK && (key = queue.poll()) != null) {
                ChunkPos pos = new ChunkPos(key);
                ChunkAccess chunk = level.getChunk(ChunkCoords.x(pos), ChunkCoords.z(pos));
                NavStore.put(level, key, ChunkNavBuilder.buildAllSections(level, chunk));
                built++;
            }
        });

        events.onChunkUnload((level, chunk) -> NavStore.remove(level, chunk.getPos().toLong()));
    }
}
