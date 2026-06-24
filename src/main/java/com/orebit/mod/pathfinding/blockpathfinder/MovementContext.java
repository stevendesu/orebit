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

    /**
     * Quantized hardness value {@link NavBlock} uses for an unbreakable block (bedrock, barrier, …):
     * such a cell can never be folded into a break-set however capable the bot.
     */
    public static final int UNBREAKABLE_HARDNESS = 255;

    // ---- Break / place cost model (tick-relative, Baritone-seedable) --------------------------
    /** Flat cost of folding one break into a move, before the hardness term. */
    public static final float BREAK_BASE_COST = 2.0f;
    /**
     * Added per quantized-hardness unit (≈ {@code destroyTime*5}) of the broken block, so soft leaves
     * (hardness 1) barely cost more than a step while stone (≈8) is a real detour-or-dig trade-off. A
     * coarse stand-in for true mining ticks until tool/haste-aware timings land.
     */
    public static final float BREAK_PER_HARDNESS = 0.25f;
    /** Flat cost of folding one block placement (bridge / footing) into a move. */
    public static final float PLACE_COST = 3.0f;

    private final NavGridView grid;
    private final BotCaps caps;
    /** Reused per-move edit accumulator (single-threaded per pathfind, like the grid cursor). */
    private final EditScratch editScratch = new EditScratch(this);

    public MovementContext(NavGridView grid, BotCaps caps) {
        this.grid = grid;
        this.caps = caps;
    }

    /** The shared, reusable edit accumulator a movement fills while folding in breaks/places. */
    public EditScratch edits() {
        return editScratch;
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

    // ---- Break / place (MOVEMENT-DESIGN.md §1, decision 1) ------------------------------------

    /**
     * Can the bot clear a body-blocking cell by mining it? True only when the bot {@link
     * BotCaps#canBreak may break}, the cell has real collision worth removing (not air/plant, which is
     * already passable), isn't a fluid (water/lava aren't "broken" — swim/avoid handle those) and isn't
     * unbreakable (bedrock/barrier). Reuses the existing {@code shape}/{@code fluid}/{@code hardness}
     * facts — no new NavBlock bit. (First-cut: any breakable block is assumed tool-satisfiable; tool /
     * durability gating arrives with the inventory subsystem.)
     */
    public boolean breakable(int x, int y, int z) {
        if (!caps.canBreak()) return false;
        long d = descriptorAt(x, y, z);
        if (NavBlock.shape(d) == NavBlock.SHAPE_EMPTY) return false; // nothing solid here to break
        if (NavBlock.fluid(d) != 0) return false;                   // don't "break" water/lava
        return NavBlock.hardness(d) != UNBREAKABLE_HARDNESS;
    }

    /**
     * Can the bot create footing at an empty floor cell by placing a throwaway block? True only when the
     * bot {@link BotCaps#canPlace may place}, the cell is open ({@link NavBlock#isReplaceable} or genuinely
     * empty — so we don't try to place into a solid) and at least one orthogonal/below neighbour offers a
     * sturdy face to place the block against (so the placement is physically valid). Reuses {@code
     * replaceable}/{@code faces} — no new bit. The exact against-face is chosen by the follower at
     * execution time from whatever neighbour is still solid then.
     */
    public boolean placeable(int x, int y, int z) {
        if (!caps.canPlace()) return false;
        long d = descriptorAt(x, y, z);
        boolean open = NavBlock.isReplaceable(d) || NavBlock.shape(d) == NavBlock.SHAPE_EMPTY;
        if (!open || NavBlock.fluid(d) != 0) return false; // need a clear, non-fluid cell to fill
        // A sturdy neighbour to place against: the four sides, plus the block below.
        return standable(x, y - 1, z)
                || hasSolidCollision(x + 1, y, z) || hasSolidCollision(x - 1, y, z)
                || hasSolidCollision(x, y, z + 1) || hasSolidCollision(x, y, z - 1);
    }

    /** Tick cost to fold one break of cell {@code (x,y,z)} in — flat base plus a hardness term. */
    public float breakCost(int x, int y, int z) {
        int hardness = NavBlock.hardness(descriptorAt(x, y, z));
        return BREAK_BASE_COST + hardness * BREAK_PER_HARDNESS;
    }

    /** Whether a cell has any solid collision (a face to build against) — full block, slab, stair, … */
    private boolean hasSolidCollision(int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        int shape = NavBlock.shape(d);
        return shape != NavBlock.SHAPE_EMPTY && NavBlock.fluid(d) == 0;
    }
}
