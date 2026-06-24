package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

import net.minecraft.core.BlockPos;

/**
 * A per-pathfind, reusable accumulator a {@link Movement} fills while testing the cells its geometry
 * touches, turning blocked-but-fixable cells into a break/place edit-set with an added cost
 * (MOVEMENT-DESIGN.md §1, decision 1). It centralises the "is this cell already fine, can I break/place
 * my way through it, or is the move impossible" decision so every movement reads it through one
 * vocabulary ({@link #requireAir}, {@link #requireFloor}) instead of re-deriving the bit checks.
 *
 * <p><b>Reused, not re-allocated.</b> One scratch lives on the {@link MovementContext} (single-threaded
 * per pathfind, like the underlying grid cursor). A movement calls {@link #reset()} before each
 * candidate, requires the cells it needs, then — if still {@link #valid()} — emits the destination with
 * {@code baseCost + }{@link #extraCost()} and {@link #snapshot()}. {@code snapshot()} copies the
 * accumulated cells into an immutable {@link StepEdits} (so the reused buffers never alias what the
 * search stored), and returns {@code null} when there were no edits — keeping ordinary walking moves
 * allocation-free and on the plain {@link CandidateSink#accept(int, int, int, float)} path.
 */
public final class EditScratch {

    private final MovementContext ctx;

    // Small fixed buffers: a Tier 1 move touches ≤ ~3 body cells and places ≤ 1 floor; grown defensively.
    private long[] breaks = new long[6];
    private int breakCount;
    private long[] places = new long[3];
    private int placeCount;
    private float extraCost;
    private boolean valid;
    private boolean allowEdits;

    EditScratch(MovementContext ctx) {
        this.ctx = ctx;
    }

    /** Clear the accumulator for a fresh candidate, edits permitted; returns {@code this} for fluent use. */
    public EditScratch reset() {
        return reset(true);
    }

    /**
     * Clear the accumulator for a fresh candidate. When {@code allowEdits} is false, no break or place is
     * folded — a blocked/empty required cell makes the move <i>invalid</i> instead of editing through it.
     * Movements pass {@code false} to honour the {@code RISKY_EDIT} flag: editing this floor cell's body
     * space could release a fluid or drop a gravity block, so the bot must reach it without editing or not
     * at all. Returns {@code this} for fluent use.
     */
    public EditScratch reset(boolean allowEdits) {
        breakCount = 0;
        placeCount = 0;
        extraCost = 0f;
        valid = true;
        this.allowEdits = allowEdits;
        return this;
    }

    /**
     * Require cell {@code (x,y,z)} be clear for the bot's body. Already passable → free. Solid but
     * {@link MovementContext#breakable breakable} (and the bot may break) → fold a break in and add its
     * mining cost. Otherwise the move is impossible ({@link #valid()} goes false).
     */
    public void requireAir(int x, int y, int z) {
        if (!valid) return;
        long d = ctx.descriptorAt(x, y, z); // one read; reused by passable/breakable/breakCost below
        if (ctx.passable(d)) return;
        if (allowEdits && ctx.breakable(d)) {
            breaks = push(breaks, breakCount, x, y, z);
            breakCount++;
            extraCost += ctx.breakCost(d);
        } else {
            valid = false; // blocked, and either the bot can't break it or an edit here is forbidden (risky)
        }
    }

    /**
     * Require footing at floor cell {@code (x,y,z)}. Already {@link MovementContext#standable standable}
     * → free. Empty but {@link MovementContext#placeable placeable} (and the bot may place) → fold a
     * place in and add its cost. Otherwise the move is impossible.
     */
    public void requireFloor(int x, int y, int z) {
        if (!valid) return;
        long d = ctx.descriptorAt(x, y, z); // one read; reused by standable/placeable below
        if (ctx.standable(d)) return;
        if (allowEdits && ctx.placeable(x, y, z, d)) {
            addPlace(x, y, z);
        } else {
            valid = false; // no footing, and either the bot can't place or an edit here is forbidden (risky)
        }
    }

    /**
     * Require footing at {@code (fx,fy,fz)}, building a <b>support</b> at {@code (sx,sy,sz)} directly
     * beneath it first if the footing has nothing of its own to place against — the two-block staircase
     * step (MOVEMENT-DESIGN §2). Three cases: already {@link MovementContext#standable standable} → free;
     * directly {@link MovementContext#placeable placeable} (a face already exists — terrain or a wall) →
     * place just the footing; otherwise, if both the footing and the support cells are
     * {@link MovementContext#openForPlace open}, place <b>both</b> the support and the footing on top of it.
     *
     * <p><b>The support's face is the floor the bot stands on — an A\* invariant, not a grid read.</b> The
     * support sits beside the cell the movement is expanding from, and that cell is solid by definition:
     * the bot has footing there, whether it's real terrain or a block an <i>earlier step in this same path</i>
     * places (predecessor edits execute first, so the anchor is really there before the support goes down).
     * The world model can't show that planned block during the search, so we deliberately do <b>not</b>
     * grid-scan the support's faces — doing so would make the staircase dead-end after one step (the second
     * step's support would look unanchored because its anchor, the first footing, isn't in the grid yet).
     * The caller must pass a {@code support} cell face-adjacent to its standable source floor (Ascend does:
     * {@code support = (nx, y, nz)} beside the source {@code (x, y, z)}).
     *
     * <p>This is what lets the bot build a diagonal staircase up through open air / off a ledge. Two
     * placements per step, so A* prefers a natural slope or (later) a Pillar where those are cheaper — the
     * staircase is the general fallback, chosen only when it's the cheapest way up.
     */
    public void requireFootingOn(int fx, int fy, int fz, int sx, int sy, int sz) {
        if (!valid) return;
        long fd = ctx.descriptorAt(fx, fy, fz);
        if (ctx.standable(fd)) return;
        if (!allowEdits || !ctx.caps().canPlace()) { valid = false; return; }
        if (ctx.placeable(fx, fy, fz, fd)) { // footing already has a face — one placement
            addPlace(fx, fy, fz);
            return;
        }
        // No face for the footing of its own: place a support beneath it (anchored to the bot's current
        // floor — solid by invariant, see above), then the footing rests on that support. Both cells just
        // need to be open; we do NOT grid-check the support's anchor, so the staircase chains across steps.
        long sd = ctx.descriptorAt(sx, sy, sz);
        if (ctx.openForPlace(fd) && ctx.openForPlace(sd)) {
            addPlace(sx, sy, sz);
            addPlace(fx, fy, fz);
        } else {
            valid = false;
        }
    }

    private void addPlace(int x, int y, int z) {
        places = push(places, placeCount, x, y, z);
        placeCount++;
        extraCost += MovementContext.PLACE_COST;
    }

    /** Whether every required cell was satisfiable (directly or via an allowed break/place). */
    public boolean valid() {
        return valid;
    }

    /** The mining + placing cost to add to the move's base traversal cost. */
    public float extraCost() {
        return extraCost;
    }

    /**
     * An immutable snapshot of the accumulated edits, or {@code null} if there were none (so the caller
     * emits a plain, allocation-free move). Safe to hand to the search — it copies out of the reused
     * buffers.
     */
    public StepEdits snapshot() {
        if (breakCount == 0 && placeCount == 0) return null;
        return new StepEdits(Arrays.copyOf(breaks, breakCount), Arrays.copyOf(places, placeCount));
    }

    private static long[] push(long[] buf, int count, int x, int y, int z) {
        if (count == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[count] = BlockPos.asLong(x, y, z);
        return buf;
    }
}
