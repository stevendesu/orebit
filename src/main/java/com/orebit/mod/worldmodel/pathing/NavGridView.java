package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.platform.LevelBounds;

import net.minecraft.server.level.ServerLevel;

/**
 * Read view over the recomputed nav grid for one level: resolves a world {@code (x,y,z)} to its
 * 2-bit {@link TraversalClass} by indexing {@link NavStore}. This is the consumer seam the
 * pathfinder sits on.
 *
 * <p>The nav grid is stored per-16³ {@link NavSection} (one array per chunk), but a path spans
 * sections and chunks, so a single coordinate lookup has to find the right chunk's section array,
 * the section within it, and the cell within that — this view does exactly that and nothing more.
 *
 * <p>Bound to a level and its {@link LevelBounds#minY(net.minecraft.world.level.Level) min-Y} (read
 * once at construction), so each per-cell lookup is a couple of shifts plus a map get — no repeated
 * world access. {@link #classAt} returns {@code null} where that chunk's nav data is not currently
 * built/loaded (outside the bot's loaded radius, or out of vertical bounds); the pathfinder treats
 * {@code null} as "unknown terrain" and does not expand into it.
 */
public final class NavGridView {

    private final ServerLevel level;
    private final int minY;

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
}
