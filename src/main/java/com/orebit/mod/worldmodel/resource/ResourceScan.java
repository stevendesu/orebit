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

    // Local live-scan volume around an anchor (in sections), shared by {@link #nearestLoadedCell}: horizontal
    // ± chunks, plus a downward-biased vertical band (ore sits below). Cold — the /bot find non-persisted
    // fallback (a one-shot diagnostic), never a hot path.
    private static final int SCAN_RADIUS_CHUNKS = 3;
    private static final int SCAN_DOWN_SECTIONS = 6;
    private static final int SCAN_UP_SECTIONS = 2;

    /**
     * Scan the level-0 region {@code (rx,ry,rz)}'s LIVE 16³ blocks for every cell whose block maps to the
     * locatable {@code resourceId} ({@link ResourceClasses#resourceForBlock}). Returns {@code null} if the
     * region's chunk isn't {@link NavStore}-resident (see the class javadoc), otherwise the matching cells in
     * scan order (possibly empty). Cold — called at most once per gather PATH tick / per find hit, never on a
     * hot path.
     */
    public static List<BlockPos> exactCells(ServerLevel level, int rx, int ry, int rz, int resourceId) {
        if (NavStore.get(level, NavStore.key(rx, rz)) == null) return null; // region chunk not resident
        final List<BlockPos> found = new ArrayList<>();
        final int ox = rx << 4, oz = rz << 4;
        final int oy = RegionGrid.of(level).minY() + (ry << 4);
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dy = 0; dy < 16; dy++) {
                for (int dz = 0; dz < 16; dz++) {
                    m.set(ox + dx, oy + dy, oz + dz);
                    if (ResourceClasses.resourceForBlock(level.getBlockState(m).getBlock()) == resourceId) {
                        found.add(m.immutable());
                    }
                }
            }
        }
        return found;
    }

    /**
     * A bounded LOCAL live-scan for a NON-persisted (locatable-only) resource — the {@code /bot find} fallback
     * for resources that have no pyramid compass ({@link ResourceClasses#columnForResource} {@code < 0}, e.g.
     * stone/wood). Sweeps the loaded sections in a small volume around {@code anchor} via {@link #exactCells}
     * and returns the single matching cell nearest to {@code anchor}, or {@code null} if none is loaded nearby.
     * Cold — a one-shot command diagnostic (not throttled; the gatherer keeps its own multi-tick scan).
     */
    public static BlockPos nearestLoadedCell(ServerLevel level, BlockPos anchor, int resourceId) {
        final int bcx = anchor.getX() >> 4;
        final int bcz = anchor.getZ() >> 4;
        final int bsy = (anchor.getY() - RegionGrid.of(level).minY()) >> 4;
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (int dcx = -SCAN_RADIUS_CHUNKS; dcx <= SCAN_RADIUS_CHUNKS; dcx++) {
            for (int dcz = -SCAN_RADIUS_CHUNKS; dcz <= SCAN_RADIUS_CHUNKS; dcz++) {
                for (int dsy = -SCAN_DOWN_SECTIONS; dsy <= SCAN_UP_SECTIONS; dsy++) {
                    final int ry = bsy + dsy;
                    if (ry < 0) continue; // below the world column
                    final List<BlockPos> cells = exactCells(level, bcx + dcx, ry, bcz + dcz, resourceId);
                    if (cells == null) continue; // section not resident
                    for (BlockPos c : cells) {
                        final long ddx = c.getX() - anchor.getX();
                        final long ddy = c.getY() - anchor.getY();
                        final long ddz = c.getZ() - anchor.getZ();
                        final long d = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d < bestD) { bestD = d; best = c; }
                    }
                }
            }
        }
        return best;
    }
}
