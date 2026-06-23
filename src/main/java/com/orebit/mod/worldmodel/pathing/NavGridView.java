package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read view over the world model for one level — the seam the pathfinder sits on. It serves the two
 * resolutions the engine plans at:
 *
 * <ul>
 *   <li><b>Coarse:</b> {@link #classAt} — the cached 2-bit {@link TraversalClass} from {@link
 *       NavStore} (recomputed per chunk, used for cheap pruning: "tunnel passable, obsidian wall not").
 *   <li><b>Fine:</b> {@link #descriptorAt} — the full {@link NavBlock} geometry for a cell, read
 *       <i>on demand</i> from the live chunk. The 2-bit grid is a recomputed cache, and the blocks it
 *       summarises are already resident in the loaded section, so we do NOT store a parallel per-cell
 *       navtype layer (that would be ~8× the 2-bit grid's memory, duplicating the chunk's own data).
 *       Instead the movement layer reads geometry only for the handful of cells along a candidate move
 *       — a bounded number of ~tens-of-ns reads per pathfind, always fresh. (If profiling ever shows
 *       these reads hot, cache the navtype per cell, packing the 2-bit class into the navtype short's
 *       spare bits — but not before measurement justifies the memory.)
 * </ul>
 *
 * <p>The nav grid is stored per-16³ {@link NavSection} (one array per chunk), but a path spans
 * sections and chunks, so {@link #classAt} finds the right chunk's section array, the section within
 * it, and the cell within that. Bound to a level and its min-Y (read once), so a class lookup is a
 * couple of shifts plus a map get. {@link #classAt} returns {@code null} where that chunk's nav data
 * isn't built (unloaded radius / out of vertical bounds) — the pathfinder treats that as unknown.
 */
public final class NavGridView {

    private final ServerLevel level;
    private final int minY;
    // Reused for the on-demand geometry reads; safe because a view is used single-threaded per pathfind.
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public NavGridView(ServerLevel level) {
        this.level = level;
        this.minY = LevelBounds.minY(level);
    }

    /**
     * The {@link TraversalClass} at world cell {@code (x,y,z)}, or {@code null} if that chunk's nav
     * data isn't currently built (unloaded radius) or {@code y} is out of the level's vertical range.
     */
    public TraversalClass classAt(int x, int y, int z) {
        int sectionIndex = (y - minY) >> 4;
        if (sectionIndex < 0) return null;
        NavSection[] sections = NavStore.get(level, NavStore.key(x >> 4, z >> 4));
        if (sections == null || sectionIndex >= sections.length) return null;
        NavSection section = sections[sectionIndex];
        if (section == null) return null;
        return section.getTraversalClass(x & 15, y & 15, z & 15);
    }

    /**
     * The packed {@link NavBlock} descriptor (full geometry: shape, faces, fluid, hardness, …) for
     * world cell {@code (x,y,z)}, read live from the loaded chunk. This is the fine-movement seam — the
     * movement layer reads it to decide jump clearance, stair half-steps, parkour gaps, swim, etc.
     * (the 2-bit class only says a cell is "passable," not whether a specific move through it works).
     */
    public long descriptorAt(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        return NavBlock.descriptorFor(state);
    }
}
