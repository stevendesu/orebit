package com.orebit.mod.worldmodel.pathing;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChunkNavLoader {
    private static final Queue<ChunkPos> pendingChunks = new ConcurrentLinkedQueue<>();

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!(world instanceof ServerLevel)) return;

            // Defer build to next tick
            pendingChunks.add(chunk.getPos());
        });

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (!(world instanceof ServerLevel serverWorld)) return;

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
