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
    // Door OPEN/CLOSE sets (DOORS P2) — a crossing folds a SET on each of a door's two body cells; almost
    // always empty. The parallel {@code doorOpens} says whether each target state is OPEN (true) or CLOSED.
    private long[] doors = new long[2];
    private boolean[] doorOpens = new boolean[2];
    private int doorCount;
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
        doorCount = 0;
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
        foldBreakOrFail(x, y, z, d);
    }

    /**
     * {@link #requireAir} made DOOR-AWARE for a horizontal crossing (P1): a blocked body cell that is an
     * ALREADY-OPEN door not blocking {@code entryEdge} (the cardinal ordinal of the edge the move crosses to
     * enter this column) is passed FREE — no break, no cost — instead of mined. Every other cell behaves exactly
     * as {@link #requireAir} (the door test is one predictable, almost-always-false {@link
     * MovementContext#doorEntryClear} branch inserted between the passable check and the break fold). Used only by
     * {@link MovementContext#requireBodyClearToward}.
     */
    public void requireAirToward(int x, int y, int z, int entryEdge) {
        if (!valid) return;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.passable(d)) return;
        if (ctx.doorEntryClear(d, entryEdge)) return; // already-open door, not blocking our entry → free passage
        // P2: a hand-toggleable door blocking our entry edge — fold a cheap OPEN/CLOSE SET (prefer over smashing)
        // when doors.toggle is on. Toggling always moves the blocked panel to the perpendicular edge, so the
        // OTHER state frees this entry edge (see MovementContext.doorSetClears). Iron / non-toggleable doors and
        // the flag-off case fall through to the P1 break fold unchanged.
        if (ctx.doorSetClears(d, entryEdge)) { setDoor(x, y, z, ctx.doorToggledOpen(d)); return; }
        foldBreakOrFail(x, y, z, d);
    }

    /** Shared tail of {@link #requireAir}/{@link #requireAirToward}: fold a break of a breakable blocked cell
     *  (adding its mining cost) when edits are allowed, else invalidate the move. */
    private void foldBreakOrFail(int x, int y, int z, long d) {
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
        extraCost += ctx.placeCost(x, y, z); // real ticks-to-place (+ inventory premium when consuming) — 1d
    }

    /**
     * Fold an OPEN/CLOSE of the (hand-toggleable) door at cell {@code (x,y,z)} to {@code targetOpen} (DOORS P2)
     * — the "right-click the door" alternative to smashing it (or to skipping the direction). The caller has
     * already proven, via {@link MovementContext#doorSetClears}, that the door is toggleable, that {@code
     * doors.toggle} is on, and that reaching {@code targetOpen} clears the crossing edge.
     *
     * <p><b>One interaction, one cost — even though a door is two body cells.</b> A crossing folds a SET on
     * BOTH the door's cells (feet + head) so every downstream {@code descriptorAt} of the door reads the same
     * state; but the toggle is a single right-click, so the {@link MovementContext#DOOR_TOGGLE_COST} is charged
     * only for the FIRST cell of a door — the second (vertically adjacent, same target) is recognised as the
     * other half and folded free. Re-folding the exact same cell is a no-op. This keeps the g-cost honest (one
     * toggle ≈ 6 ticks ≪ breaking both halves) without needing to know which half is the lower one.
     */
    void setDoor(int x, int y, int z, boolean targetOpen) {
        long cell = BlockPos.asLong(x, y, z);
        boolean sameDoor = false;
        for (int i = 0; i < doorCount; i++) {
            if (doors[i] == cell) return; // already folded this exact cell
        }
        long below = BlockPos.asLong(x, y - 1, z), above = BlockPos.asLong(x, y + 1, z);
        for (int i = 0; i < doorCount; i++) {
            if ((doors[i] == below || doors[i] == above) && doorOpens[i] == targetOpen) { sameDoor = true; break; }
        }
        if (doorCount == doors.length) {
            doors = Arrays.copyOf(doors, doors.length * 2);
            doorOpens = Arrays.copyOf(doorOpens, doorOpens.length * 2);
        }
        doors[doorCount] = cell;
        doorOpens[doorCount] = targetOpen;
        doorCount++;
        if (!sameDoor) extraCost += MovementContext.DOOR_TOGGLE_COST; // one right-click per door, not per half
    }

    /**
     * Fold a <b>break-through</b> of a PASSABLE hazard/through-slow body cell (berry bush, cobweb, fire —
     * cells {@link #requireAir} leaves alone because nothing blocks) at a caller-computed cost — the
     * "punch the bush and walk through" option. The caller ({@link MovementContext#bodyTransitCost(EditScratch,
     * int, int, int, int)}) has already gated on {@link MovementContext#breakableThrough} and proven the
     * break cheaper than transiting the cell intact; {@code cost} is the real mining ticks plus the
     * {@code mining.breakBaseCost} surcharge, charged here in place of the transit surcharge the cell
     * would otherwise add. Package-private: only the context's transit vocabulary emits these.
     */
    void breakThrough(int x, int y, int z, float cost) {
        breaks = push(breaks, breakCount, x, y, z);
        breakCount++;
        extraCost += cost;
    }

    /**
     * Whether this candidate may fold edits at all — {@code reset(false)} (a {@code RISKY_EDIT} floor)
     * forbids them, and an already-invalid scratch has nothing to gain. The gate the context's
     * break-through fold checks before recording a break (mirrors {@link #requireAir}'s own gate).
     */
    boolean editsAllowed() {
        return allowEdits && valid;
    }

    /** Whether every required cell was satisfiable (directly or via an allowed break/place). */
    public boolean valid() {
        return valid;
    }

    /** The mining + placing cost to add to the move's base traversal cost. */
    public float extraCost() {
        return extraCost;
    }

    /** Whether any break, place, or door-set was folded — lets a caller test for edits without {@link #snapshot()}. */
    public boolean hasEdits() {
        return breakCount != 0 || placeCount != 0 || doorCount != 0;
    }

    /**
     * Load this candidate's accumulated edits into a <b>pooled</b> {@link StepEdits} drawn from the
     * search's per-search arena — the allocation-free replacement for the old {@code snapshot()} once the
     * sink has decided to keep the candidate (the rejected majority never reaches here). The pooled
     * instance reuses/grows its own buffers, so steady state touches no heap. Call only when
     * {@link #hasEdits()} (the search gates on it; an empty set should stay a plain {@code null} edge).
     */
    void copyInto(StepEdits e) {
        e.load(breaks, breakCount, places, placeCount, doors, doorOpens, doorCount);
    }

    private static long[] push(long[] buf, int count, int x, int y, int z) {
        if (count == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[count] = BlockPos.asLong(x, y, z);
        return buf;
    }
}
