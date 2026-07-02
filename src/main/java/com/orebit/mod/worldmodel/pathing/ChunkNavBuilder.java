package com.orebit.mod.worldmodel.pathing;

import java.util.function.LongConsumer;

import com.orebit.mod.platform.ChunkCoords;
import com.orebit.mod.platform.LevelBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkNavBuilder {
    private ChunkNavBuilder() {}

    public static NavSection[] buildAllSections(Level world, ChunkAccess chunk) {
        return buildAllSections(world, chunk, null);
    }

    /**
     * As {@link #buildAllSections(Level, ChunkAccess)}, additionally reporting every nether-portal cell to
     * {@code portalCells} (nullable) as a {@link NetherPortalIndex#pack packed world position} — this layer
     * owns the section origins, so it converts the classifier's section-local indices to world cells. The
     * per-section adapter lambda allocates only on this cold chunk-build path, never per cell.
     */
    public static NavSection[] buildAllSections(Level world, ChunkAccess chunk, LongConsumer portalCells) {
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
            if (portalCells == null) {
                sections[i] = NavSectionBuilder.build(chunkSections[i], origin);
            } else {
                final int baseX = chunkX << 4, baseY = sectionY, baseZ = chunkZ << 4;
                sections[i] = NavSectionBuilder.build(chunkSections[i], origin,
                        cell -> portalCells.accept(NetherPortalIndex.pack(
                                baseX + (cell & 15),          // section-local index is (y<<8)|(z<<4)|x
                                baseY + (cell >>> 8),
                                baseZ + ((cell >>> 4) & 15))));
            }
        }

        return sections;
    }
}
