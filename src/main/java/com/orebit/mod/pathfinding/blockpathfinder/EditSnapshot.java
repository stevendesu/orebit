package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

/**
 * An immutable, <b>unpooled</b> flat copy of planned world edits — the splice primitive's baseline
 * (DESIGN-background-pathfinding.md §7, DESIGN-portal-route-layer.md §4.3): when a later plan is
 * computed while an earlier plan is still being walked, the earlier plan's not-yet-applied edits are
 * invisible to the live nav grid, so the later search must be seeded with them or it prices phantom
 * walls and misses real footings. This is that seed: the cells the earlier plan will have BROKEN
 * (read as air) and PLACED (read as solid footing) by the time the bot reaches the splice boundary.
 *
 * <p><b>Why a copy, not a reference.</b> A plan's per-step {@link StepEdits} on the search side live
 * in the per-search {@code EditPool} arena and are wiped between searches; even the returned plan's
 * arena-independent copies belong to a plan the follower may discard at any window swap. A baseline
 * outlives both (it is handed to a DIFFERENT search, possibly on a different thread), so it copies the
 * packed cells out into its own exact-size arrays — one small, cold allocation per splice (tens of
 * longs; splices happen at plan boundaries, never inside a search).
 *
 * <p><b>Latest-wins folding.</b> {@link #fromRemainingSteps} walks the plan's remaining steps
 * <i>last-to-first</i> with first-seen-wins per cell — the same reversed-iteration trick
 * {@link PathEdits} uses — so a cell edited twice resolves to the edit of the LATER step (the world
 * state after executing all remaining steps in order). Within one step, places are folded before
 * breaks, mirroring {@link PathEdits#add}'s per-edge ordering. A cell therefore appears in exactly one
 * of the two lists.
 *
 * <p>Consumed by {@link PathEdits#addSnapshot} on the seeded search's per-pop rebuild, appended AFTER
 * the {@code cameFrom}-chain walk so the in-search path's own edits shadow the baseline (the baseline
 * is the oldest history). {@code null} baseline (every non-spliced search) costs one compare per pop.
 */
public final class EditSnapshot {

    /** The no-edits baseline — seeding with this is exactly a non-seeded search (lazy-splice mode). */
    public static final EditSnapshot EMPTY = new EditSnapshot(new long[0], new long[0]);

    /** Cells the earlier plan breaks (packed {@code BlockPos.asLong}) — the seeded search reads air. */
    private final long[] breaks;
    /** Cells the earlier plan places — the seeded search reads solid footing. */
    private final long[] places;

    private EditSnapshot(long[] breaks, long[] places) {
        this.breaks = breaks;
        this.places = places;
    }

    /**
     * Fold the not-yet-applied suffix of {@code plan} — steps {@code fromStep..size-1}, i.e. everything
     * the follower has not executed yet — into a baseline, latest-step-wins per cell. {@code fromStep}
     * is typically the follower's next unapplied step ({@code lastEditedIndex + 1}). Returns
     * {@link #EMPTY} when nothing remains (or {@code plan} is {@code null}), so lazy-mode callers need
     * no special case. Cold: runs at splice/submit time, never inside a search.
     */
    public static EditSnapshot fromRemainingSteps(BlockPathPlan plan, int fromStep) {
        if (plan == null) return EMPTY;
        int first = Math.max(fromStep, 0);
        if (first >= plan.size()) return EMPTY;

        // Tiny accumulators (a window plan is ≤ ~48 steps of a few cells each) — linear-scan dedup is
        // cheaper and simpler than any map at this size, and this is cold code.
        long[] brk = new long[8];
        int brkN = 0;
        long[] plc = new long[8];
        int plcN = 0;

        for (int i = plan.size() - 1; i >= first; i--) {          // last-to-first = latest-wins
            StepEdits se = plan.edits(i);
            if (se == null) continue;
            for (int j = 0, n = se.placeCount(); j < n; j++) {    // places before breaks, as PathEdits.add
                long cell = se.placeAt(j);
                if (!contains(brk, brkN, cell) && !contains(plc, plcN, cell)) {
                    if (plcN == plc.length) plc = Arrays.copyOf(plc, plcN << 1);
                    plc[plcN++] = cell;
                }
            }
            for (int j = 0, n = se.breakCount(); j < n; j++) {
                long cell = se.breakAt(j);
                if (!contains(brk, brkN, cell) && !contains(plc, plcN, cell)) {
                    if (brkN == brk.length) brk = Arrays.copyOf(brk, brkN << 1);
                    brk[brkN++] = cell;
                }
            }
        }
        if (brkN == 0 && plcN == 0) return EMPTY;
        return new EditSnapshot(Arrays.copyOf(brk, brkN), Arrays.copyOf(plc, plcN));
    }

    private static boolean contains(long[] a, int n, long v) {
        for (int i = 0; i < n; i++) {
            if (a[i] == v) return true;
        }
        return false;
    }

    /** True when the snapshot carries no edits (== {@link #EMPTY} semantics). */
    public boolean isEmpty() {
        return breaks.length == 0 && places.length == 0;
    }

    /** Number of cells the baseline breaks. */
    public int breakCount() {
        return breaks.length;
    }

    /** The {@code i}-th broken cell, packed {@code BlockPos.asLong}. */
    public long breakAt(int i) {
        return breaks[i];
    }

    /** Number of cells the baseline places. */
    public int placeCount() {
        return places.length;
    }

    /** The {@code i}-th placed cell, packed {@code BlockPos.asLong}. */
    public long placeAt(int i) {
        return places[i];
    }
}
