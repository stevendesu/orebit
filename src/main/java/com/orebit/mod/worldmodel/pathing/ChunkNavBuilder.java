package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.platform.ChunkCoords;
import com.orebit.mod.platform.LevelBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkNavBuilder {
    private ChunkNavBuilder() {}

    public static NavSection[] buildAllSections(Level world, ChunkAccess chunk) {
        int minY = LevelBounds.minY(world);    // -64
        int maxY = minY + world.getHeight();   // 320
        int sectionCount = (maxY - minY) / 16; // 384 / 16 = 24

        NavSection[] sections = new NavSection[sectionCount];

        int chunkX = ChunkCoords.x(chunk.getPos());
        int chunkZ = ChunkCoords.z(chunk.getPos());
        LevelChunkSection[] chunkSections = chunk.getSections();

        for (int i = 0; i < chunkSections.length; i++) {
            int sectionY = minY + (i * 16);
            BlockPos origin = new BlockPos(chunkX << 4, sectionY, chunkZ << 4);
            sections[i] = NavSectionBuilder.build(
                world,
                chunkSections[i],
                origin,
                i + 1 < chunkSections.length ? chunkSections[i + 1] : null
            );
        }

        return sections;
    }
}
