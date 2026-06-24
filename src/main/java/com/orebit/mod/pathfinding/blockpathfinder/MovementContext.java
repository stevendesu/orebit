package com.orebit.mod.pathfinding.blockpathfinder;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;
import com.orebit.mod.worldmodel.pathing.NavGridView;

/**
 * The world-and-bot context a {@link Movement} reads while expanding a node: the {@link NavGridView}
 * (the cheap "is it built" gate + live per-cell geometry) and the {@link
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

    /**
     * Whether cell {@code (x,y,z)} has built nav data — the cheap gate that keeps the search inside the
     * loaded radius (so the bot never plans into chunks it can't see). The precise checks below read
     * live geometry; this only answers "is it loaded enough to trust."
     */
    public boolean built(int x, int y, int z) {
        return grid.built(x, y, z);
    }

    /**
     * Packed {@link NavBlock} descriptor for the cell (fine geometry) — a flat read from the resident
     * navtype grid (a live block read only as a fallback outside the built area).
     */
    public long descriptorAt(int x, int y, int z) {
        return grid.descriptorAt(x, y, z);
    }

    // ---- Neighbour-property flags (the precomputed NavFlags bitmask) --------------------------
    // These let a movement read multi-cell facts (body clearance, edit-hazard) in ONE grid access
    // instead of probing each cell with descriptorAt. Read the raw bitmask once per cell, decode both.

    /** {@code HEADROOM} level: ≥2 clear body cells (room to stand). */
    public static final int HEADROOM_WALK = NavFlags.HEADROOM_WALK;
    /** {@code HEADROOM} level: ≥3 clear body cells (room to jump up). */
    public static final int HEADROOM_JUMP = NavFlags.HEADROOM_JUMP;

    /** The raw 6-bit {@link NavFlags} bitmask at floor cell {@code (x,y,z)} (0 where unbuilt). */
    public int flagsAt(int x, int y, int z) {
        return grid.flagsAt(x, y, z);
    }

    /** Walkable clearance above the floor encoded in {@code flags} (none/crawl/walk/jump). */
    public static int headroom(int flags) {
        return NavFlags.headroom(flags);
    }

    /** Whether editing this floor's body space risks a fluid flow / gravity cascade (from {@code flags}). */
    public static boolean risksEdit(int flags) {
        return NavFlags.risksEdit(flags);
    }

    /**
     * Whether the resident HEADROOM bit <b>proves</b> floor {@code (.,y,.)} has at least {@code need}
     * walkable clearance ({@link #HEADROOM_WALK} / {@link #HEADROOM_JUMP}) — letting the caller skip the
     * per-cell {@code descriptorAt} probes entirely.
     *
     * <p>Two facts make this exact (MOVEMENT-DESIGN §8). (1) <b>The OOB bias is one-directional:</b>
     * out-of-section cells read as air, so a missing neighbour can only <i>inflate</i> the clearance
     * count, never hide a block — hence a {@code < need} reading is always trustworthy and is handled by
     * the {@code requireAir} fallback (which short-circuits on the first blocked in-section cell, so a
     * non-breaking bot rejects with no cross-section read; a breaking bot reads on to fold its breaks).
     * (2) <b>The trust threshold is per level:</b> clearance {@code N} is read from cells {@code y+1..y+N},
     * so the top cell needed is {@code y+need}; it stays inside the floor's own 16-tall section exactly
     * when {@code (y&15) + need <= 15}. So a WALK proof is exact up to {@code (y&15) <= 13} and only a JUMP
     * proof tightens to {@code <= 12} — verifying one fewer layer for the common walk case. A claims-clear
     * reading nearer the top face returns {@code false} here, so the caller verifies the real cells.
     */
    public boolean headroomProves(int flags, int y, int need) {
        return headroom(flags) >= need && (y & 15) + need <= 15;
    }

    /**
     * Ensure floor {@code (fx,fy,fz)}'s two body cells (feet + head) are clear for a walker, recording any
     * needed breaks on {@code e}. Fast path: the HEADROOM bit {@link #headroomProves proves} ≥ WALK
     * clearance in one grid read — no per-cell probes, no edits. Otherwise read the real cells via {@code
     * requireAir} (which folds breaks under {@code e}'s edit gate, or invalidates if blocked and the bot
     * can't/may-not break). {@code flags} is the cell's already-read {@link NavFlags} bitmask.
     */
    public void requireBodyClear(EditScratch e, int fx, int fy, int fz, int flags) {
        if (headroomProves(flags, fy, HEADROOM_WALK)) return;
        e.requireAir(fx, fy + 1, fz);
        e.requireAir(fx, fy + 2, fz);
    }

    /**
     * Can the bot's body occupy this cell? True only for non-colliding cells (air / plants) that hold
     * no fluid. Excludes water/lava (swimming is Tier 2) and any partial collision, so it's the
     * conservative "this cell is genuinely clear for feet or head" test the Tier 1 moves need.
     */
    public boolean passable(int x, int y, int z) {
        return passable(descriptorAt(x, y, z));
    }

    /**
     * {@link #passable(int, int, int)} on an already-read descriptor — the read-once form callers use
     * when they've already fetched the cell's descriptor (each {@code descriptorAt} still costs a section
     * lookup + array read — a live palette read outside the built grid — so re-reading the same cell
     * across predicates is wasteful).
     */
    public boolean passable(long d) {
        return NavBlock.shape(d) == NavBlock.SHAPE_EMPTY && NavBlock.fluid(d) == 0;
    }

    /**
     * Can the bot stand on top of this cell? True for any solid-topped shape (full / slab / stair /
     * layer / low partial) that isn't a fluid and isn't damaging (lava, magma, cactus, fire). Excludes
     * {@link NavBlock#SHAPE_OTHER} (fences/walls/panes — you don't get a clean footing on those) and
     * {@link NavBlock#SHAPE_EMPTY} (no floor at all).
     */
    public boolean standable(int x, int y, int z) {
        return standable(descriptorAt(x, y, z));
    }

    /** {@link #standable(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean standable(long d) {
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
        return breakable(descriptorAt(x, y, z));
    }

    /** {@link #breakable(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean breakable(long d) {
        if (!caps.canBreak()) return false;
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
        return placeable(x, y, z, descriptorAt(x, y, z));
    }

    /**
     * {@link #placeable(int, int, int)} on the cell's already-read descriptor {@code d} (read-once form;
     * the neighbour cells are still read on demand — they're distinct cells, each read once).
     */
    public boolean placeable(int x, int y, int z, long d) {
        if (!caps.canPlace()) return false;
        if (!openForPlace(d)) return false;        // need a clear, non-fluid cell to fill
        // A sturdy neighbour to place against: the four sides, plus the block below.
        return standable(x, y - 1, z)
                || hasSolidCollision(x + 1, y, z) || hasSolidCollision(x - 1, y, z)
                || hasSolidCollision(x, y, z + 1) || hasSolidCollision(x, y, z - 1);
    }

    /**
     * Whether {@code d} is an open target a placed block could fill — replaceable or genuinely empty, and
     * holding no fluid. This is the "is the cell free" half of {@link #placeable}, split out because a
     * staircase step places a footing whose face comes from a freshly-placed <i>support</i> beneath it
     * (not from the footing's own neighbours), so {@code EditScratch} needs the open test on its own.
     */
    public boolean openForPlace(long d) {
        return (NavBlock.isReplaceable(d) || NavBlock.shape(d) == NavBlock.SHAPE_EMPTY) && NavBlock.fluid(d) == 0;
    }

    /** Tick cost to fold one break of cell {@code (x,y,z)} in — flat base plus a hardness term. */
    public float breakCost(int x, int y, int z) {
        return breakCost(descriptorAt(x, y, z));
    }

    /** {@link #breakCost(int, int, int)} on an already-read descriptor (read-once form). */
    public float breakCost(long d) {
        return BREAK_BASE_COST + NavBlock.hardness(d) * BREAK_PER_HARDNESS;
    }

    /** Whether a cell has any solid collision (a face to build against) — full block, slab, stair, … */
    private boolean hasSolidCollision(int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        int shape = NavBlock.shape(d);
        return shape != NavBlock.SHAPE_EMPTY && NavBlock.fluid(d) == 0;
    }
}
