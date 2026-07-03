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
     *
     * <p><b>Two passes over the column</b> (the vertical-overscan build — see {@link NavFlags} "Boundary
     * handling"): pass 1 classifies every section's NAVTYPES (+ portal discovery); pass 2 computes each
     * section's flags with the section above's freshly-classified grid as overscan, so the top ~3 floor
     * rows of every section get honest hazard/slow/headroom bits instead of the old air-optimistic ones.
     * Vertical neighbours always live in this same column, so pass 2 has everything in hand — no
     * cross-chunk ordering problem. The world-top section (and a section under a uniform-air one) keeps
     * the air-above fast path.
     */
    public static NavSection[] buildAllSections(Level world, ChunkAccess chunk, LongConsumer portalCells) {
        int minY = LevelBounds.minY(world);    // -64
        int maxY = minY + world.getHeight();   // 320
        int sectionCount = (maxY - minY) / 16; // 384 / 16 = 24

        NavSection[] sections = new NavSection[sectionCount];
        boolean[] allAir = new boolean[sectionCount];

        int chunkX = ChunkCoords.x(chunk.getPos());
        int chunkZ = ChunkCoords.z(chunk.getPos());
        LevelChunkSection[] chunkSections = chunk.getSections();

        // Pass 1 — navtypes (+ portal cells) for the whole column.
        for (int i = 0; i < chunkSections.length; i++) {
            int sectionY = minY + (i * 16);
            BlockPos origin = new BlockPos(chunkX << 4, sectionY, chunkZ << 4);
            NavSection nav = NavSection.create(origin);
            if (portalCells == null) {
                allAir[i] = NavSectionBuilder.classifyNavtypes(chunkSections[i], nav.getTraversalGrid(), null);
            } else {
                final int baseX = chunkX << 4, baseY = sectionY, baseZ = chunkZ << 4;
                allAir[i] = NavSectionBuilder.classifyNavtypes(chunkSections[i], nav.getTraversalGrid(),
                        cell -> portalCells.accept(NetherPortalIndex.pack(
                                baseX + (cell & 15),          // section-local index is (y<<8)|(z<<4)|x
                                baseY + (cell >>> 8),
                                baseZ + ((cell >>> 4) & 15))));
            }
            sections[i] = nav;
        }

        // Pass 2 — flags, each section reading the one above (null above = air: world top, an unfilled
        // tail slot, or a uniform-air neighbour — identical to real air data, and it preserves the
        // uniform-air fill bypass below it).
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue; // chunk shorter than the level column: nothing built
            NavSection above = i + 1 < sections.length ? sections[i + 1] : null;
            NavSectionBuilder.computeFlags(sections[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }

        return sections;
    }
}
