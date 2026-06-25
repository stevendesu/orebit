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
 * {@code baseCost + }{@link #extraCost()} and hands this scratch to the {@link CandidateSink}. Only <i>after</i>
 * its relaxation gate accepts the candidate does the sink {@link #copyInto} a {@link StepEdits} drawn from
 * the search's per-search arena — so a rejected (non-improving) candidate allocates nothing, and an
 * accepted one reuses a pooled set rather than minting a fresh one. An edit-free move never reaches that
 * path at all: it stays on the plain {@link CandidateSink#accept(int, int, int, float)} call.
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
     * place just the footing; otherwise, if the footing cell is {@link MovementContext#openForPlace open}
     * and the support is {@link MovementContext#placeable placeable}, place <b>both</b> the support and the
     * footing on top of it. Else the move is impossible.
     *
     * <p>This is what lets the bot build a diagonal staircase up through open air / off a ledge: a lone
     * footing one-up-and-over has no face to attach to, but a support placed beside the floor the bot
     * stands on gives it one. That floor reads SOLID even when it's a block a <i>preceding step placed</i>,
     * because the search feeds the path's {@link PathEdits} diff into {@code descriptorAt} — so the
     * support's {@code placeable} check finds the face and the staircase <b>chains across steps</b> (without
     * the diff it would dead-end after one step, the next support looking unanchored). Two placements per
     * step, so A* prefers a natural slope or (later) a Pillar where those are cheaper.
     */
    public void requireFootingOn(int fx, int fy, int fz, int sx, int sy, int sz) {
        if (!valid) return;
        long fd = ctx.descriptorAt(fx, fy, fz);
        if (ctx.standable(fd)) return;
        if (!allowEdits) { valid = false; return; }
        if (ctx.placeable(fx, fy, fz, fd)) { // footing already has a face — one placement
            addPlace(fx, fy, fz);
            return;
        }
        // No face of the footing's own: place a support beneath it, then the footing rests on it. The
        // support's face is the floor the bot stands on, which reads solid via the PathEdits diff (real
        // terrain or a preceding step's block), so a plain placeable() check finds it — staircase chains.
        long sd = ctx.descriptorAt(sx, sy, sz);
        if (ctx.openForPlace(fd) && ctx.placeable(sx, sy, sz, sd)) {
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

    /** Whether any break or place was folded — lets a caller test for edits without {@link #snapshot()}. */
    public boolean hasEdits() {
        return breakCount != 0 || placeCount != 0;
    }

    /**
     * Load this candidate's accumulated edits into a <b>pooled</b> {@link StepEdits} drawn from the
     * search's per-search arena — the allocation-free replacement for the old {@code snapshot()} once the
     * sink has decided to keep the candidate (the rejected majority never reaches here). The pooled
     * instance reuses/grows its own buffers, so steady state touches no heap. Call only when
     * {@link #hasEdits()} (the search gates on it; an empty set should stay a plain {@code null} edge).
     */
    void copyInto(StepEdits e) {
        e.load(breaks, breakCount, places, placeCount);
    }

    private static long[] push(long[] buf, int count, int x, int y, int z) {
        if (count == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[count] = BlockPos.asLong(x, y, z);
        return buf;
    }
}
