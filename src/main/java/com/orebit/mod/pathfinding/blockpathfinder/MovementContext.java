package com.orebit.mod.pathfinding.blockpathfinder;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.TraversalClass;

/**
 * The world-and-bot context a {@link Movement} reads while expanding a node: the {@link NavGridView}
 * (coarse 2-bit class for the cheap "is it built" gate + live per-cell geometry) and the {@link
 * BotCaps}. It also hosts the small set of geometry <i>predicates</i> every Tier 1 movement shares —
 * {@link #passable}, {@link #standable}, {@link #topYOf} — so each movement reads facts through one
 * vocabulary rather than re-deriving bit extraction. (These live on the context, a per-pathfind smart
 * object, not a static helper class.)
 *
 * <p>Single-threaded per pathfind (the underlying {@link NavGridView} reuses a cursor), matching the
 * search.
 */
public final class MovementContext {

    /**
     * A floor cell whose collision top is at or below this many sixteenths is a <b>low step</b> — a
     * slab / single snow layer / stair lip the player auto-steps onto (~0.6 blocks) without jumping.
     * Above it, gaining the cell needs a real jump (Ascend). 10/16 ≈ 0.625, just past the auto-step.
     */
    public static final int STEP_ASSIST_MAX_TOP_Y = 10;

    private final NavGridView grid;
    private final BotCaps caps;

    public MovementContext(NavGridView grid, BotCaps caps) {
        this.grid = grid;
        this.caps = caps;
    }

    public BotCaps caps() {
        return caps;
    }

    /** The coarse 2-bit class, or {@code null} where that chunk's nav data isn't built. */
    public TraversalClass classAt(int x, int y, int z) {
        return grid.classAt(x, y, z);
    }

    /**
     * Whether cell {@code (x,y,z)} has built nav data — the cheap gate that keeps the search inside the
     * loaded radius (so the bot never plans into chunks it can't see). The precise checks below read
     * live geometry; this only answers "is it loaded enough to trust."
     */
    public boolean built(int x, int y, int z) {
        return grid.classAt(x, y, z) != null;
    }

    /** Live packed {@link NavBlock} descriptor for the cell (fine geometry, always fresh). */
    public long descriptorAt(int x, int y, int z) {
        return grid.descriptorAt(x, y, z);
    }

    /**
     * Can the bot's body occupy this cell? True only for non-colliding cells (air / plants) that hold
     * no fluid. Excludes water/lava (swimming is Tier 2) and any partial collision, so it's the
     * conservative "this cell is genuinely clear for feet or head" test the Tier 1 moves need.
     */
    public boolean passable(int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        return NavBlock.shape(d) == NavBlock.SHAPE_EMPTY && NavBlock.fluid(d) == 0;
    }

    /**
     * Can the bot stand on top of this cell? True for any solid-topped shape (full / slab / stair /
     * layer / low partial) that isn't a fluid and isn't damaging (lava, magma, cactus, fire). Excludes
     * {@link NavBlock#SHAPE_OTHER} (fences/walls/panes — you don't get a clean footing on those) and
     * {@link NavBlock#SHAPE_EMPTY} (no floor at all).
     */
    public boolean standable(int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        int shape = NavBlock.shape(d);
        if (shape == NavBlock.SHAPE_EMPTY || shape == NavBlock.SHAPE_OTHER) return false;
        if (NavBlock.fluid(d) != 0) return false;
        if (NavBlock.isDamaging(d)) return false;
        return true;
    }

    /** The collision top of the cell in sixteenths (0..31); 16 = full block, 8 = slab. */
    public int topYOf(int x, int y, int z) {
        return NavBlock.topY(descriptorAt(x, y, z));
    }

    /** True if standing on the cell incurs a slow surface (soul sand / honey / cobweb / slime). */
    public boolean isSlow(int x, int y, int z) {
        return NavBlock.surface(descriptorAt(x, y, z)) == 1; // SURFACE_SLOW
    }
}
