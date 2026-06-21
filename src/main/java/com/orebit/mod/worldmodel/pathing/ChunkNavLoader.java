package com.orebit.mod.worldmodel.pathing;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

public class ChunkNavLoader {
    private static final Queue<ChunkPos> pendingChunks = new ConcurrentLinkedQueue<>();

    public static void register(PlatformEvents events) {
        events.onChunkLoad((world, chunk) -> {
            // Defer build to next tick
            pendingChunks.add(chunk.getPos());
        });

        events.onWorldTickEnd(serverWorld -> {
            while (!pendingChunks.isEmpty()) {
                ChunkPos pos = pendingChunks.poll();
                ChunkAccess chunk = serverWorld.getChunk(pos.x, pos.z);

                long start = System.nanoTime();
                NavSection[] sections = ChunkNavBuilder.buildAllSections(serverWorld, chunk);
                long elapsed = System.nanoTime() - start;

                // System.out.println("[NavSection] Built for chunk " + pos + " in " + (elapsed / 1_000_000) + " ms");
            }
        });
    }
}
