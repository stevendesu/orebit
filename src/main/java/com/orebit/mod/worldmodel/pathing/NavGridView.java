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
 *   <li><b>Coarse:</b> {@link #classAt} — the {@link TraversalClass} for cheap pruning ("tunnel
 *       passable, obsidian wall not"), read from the top 2 bits of the resident grid.
 *   <li><b>Fine:</b> {@link #descriptorAt} — the full {@link NavBlock} geometry for a cell. The grid
 *       stores the resolved navtype per cell (the low 14 bits of the same packed {@code short} — see
 *       {@link TraversalGrid}), so this is a flat masked array read plus one descriptor-table index — no
 *       live {@code getBlockState} palette walk, no navtype-map lookup. (Favour-cpu-over-ram: the
 *       movement layer reads geometry constantly during A*, so the navtype is kept resident rather than
 *       re-derived; the +7 KB/section is negligible.) Cells <i>outside</i> the built grid fall back to a
 *       live read so a probe just past the loaded radius still returns real geometry.
 * </ul>
 *
 * <p>The nav grid is stored per-16³ {@link NavSection} (one array per chunk), but a path spans
 * sections and chunks, so a lookup finds the right chunk's section array, the section within it, and the
 * cell within that. Bound to a level and its min-Y (read once), so a lookup is a couple of shifts plus a
 * map get. {@link #classAt} returns {@code null} where that chunk's nav data isn't built (unloaded radius
 * / out of vertical bounds) — the pathfinder treats that as unknown.
 *
 * <p><b>Freshness:</b> because both reads come from the stored grid (not the live world), runtime block
 * edits — including the bot's own break/place — are reflected via the {@code LevelChunk.setBlockState}
 * mixin ({@link com.orebit.mod.platform.BlockChangeEvents} → {@link NavGridUpdater}), which patches the
 * affected cell of every tracked section as it changes. So a replan reads current terrain with no
 * per-replan rebuild (the old {@code refreshNavData} shim is retired).
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
        NavSection section = sectionAt(x, y, z);
        return section == null ? null : section.getTraversalClass(x & 15, y & 15, z & 15);
    }

    /**
     * The packed {@link NavBlock} descriptor (full geometry: shape, faces, fluid, hardness, …) for
     * world cell {@code (x,y,z)}. For a built cell this is the resident navtype turned into its
     * descriptor — a flat array read, no live block lookup. This is the fine-movement seam — the
     * movement layer reads it to decide jump clearance, stair half-steps, parkour gaps, swim, etc.
     * (the 2-bit class only says a cell is "passable," not whether a specific move through it works).
     * Outside the built grid it falls back to a live read so a probe just past the loaded radius still
     * returns real geometry.
     */
    public long descriptorAt(int x, int y, int z) {
        NavSection section = sectionAt(x, y, z);
        if (section != null) {
            return NavBlock.descriptor((short) section.getNavtype(x & 15, y & 15, z & 15));
        }
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        return NavBlock.descriptorFor(state);
    }

    /** The {@link NavSection} covering world cell {@code (x,y,z)}, or {@code null} if it isn't built. */
    private NavSection sectionAt(int x, int y, int z) {
        int sectionIndex = (y - minY) >> 4;
        if (sectionIndex < 0) return null;
        NavSection[] sections = NavStore.get(level, NavStore.key(x >> 4, z >> 4));
        if (sections == null || sectionIndex >= sections.length) return null;
        return sections[sectionIndex];
    }
}
