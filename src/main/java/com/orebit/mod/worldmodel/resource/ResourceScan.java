package com.orebit.mod.worldmodel.resource;

import java.util.ArrayList;
import java.util.List;

import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The exact-cell counterpart to {@link ResourceQuery} (find-mine-resources design §6/§7). Where
 * {@code ResourceQuery} drills the {@link ResourcePyramid} down to a level-0 (16³) region — proving a resource
 * is <i>in</i> a section but not <i>where</i> — this reads that section's LIVE blocks to yield the exact block
 * positions. It's the shared read used by both {@code /bot find}'s live-scan diagnostic and {@code /bot
 * gather}'s MINE hand-off, so the residency gate + scan live in one place.
 *
 * <h2>Residency gate</h2>
 * A level-0 region is exactly one 16-aligned chunk column ({@code rx == chunkX}, {@code rz == chunkZ}), which
 * may not be loaded — the pyramid remembers regions from earlier exploration. The scan is therefore gated on
 * {@link NavStore} residency (the nav column is stored on chunk-build and dropped on unload, so a present
 * column ⇒ the chunk is loaded <i>and</i> nav-built ⇒ its live {@code getBlockState} is safe to read without
 * force-loading). The three outcomes are distinguished by the return value so callers can act on each:
 * <ul>
 *   <li>{@code null} — the region isn't resident; the caller must not read live blocks there (e.g. keep
 *       approaching until the chunk loads).</li>
 *   <li>empty list — resident but holding no such block: the load-populated tally is stale (the vein was
 *       mined out since the chunk built, §8.5).</li>
 *   <li>non-empty — the matching cells, in scan order; the caller sorts by its own reference point.</li>
 * </ul>
 */
public final class ResourceScan {

    private ResourceScan() {}

    /**
     * Scan the level-0 region {@code (rx,ry,rz)}'s LIVE 16³ blocks for every cell whose block maps to the
     * indexed resource {@code column} ({@link ResourceClasses#columnForBlock}). Returns {@code null} if the
     * region's chunk isn't {@link NavStore}-resident (see the class javadoc), otherwise the matching cells in
     * scan order (possibly empty). Cold — called at most once per gather PATH tick / per find hit, never on a
     * hot path.
     */
    public static List<BlockPos> exactCells(ServerLevel level, int rx, int ry, int rz, int column) {
        if (NavStore.get(level, NavStore.key(rx, rz)) == null) return null; // region chunk not resident
        final List<BlockPos> found = new ArrayList<>();
        final int ox = rx << 4, oz = rz << 4;
        final int oy = RegionGrid.of(level).minY() + (ry << 4);
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dy = 0; dy < 16; dy++) {
                for (int dz = 0; dz < 16; dz++) {
                    m.set(ox + dx, oy + dy, oz + dz);
                    if (ResourceClasses.columnForBlock(level.getBlockState(m).getBlock()) == column) {
                        found.add(m.immutable());
                    }
                }
            }
        }
        return found;
    }
}
