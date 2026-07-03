package com.orebit.mod.worldmodel.pathing;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Epoch-based deferred reclamation for displaced {@link NavSection}s (DESIGN-background-pathfinding.md
 * §4.1) — the ONE crash-class fix async pathing needs. {@link NavStore} used to recycle a replaced /
 * unloaded chunk's sections into the (tick-thread-confined) {@link NavSectionPool} immediately; a
 * planner-thread search still holding them via its {@code NavGridView} chunk cache would then read a
 * zero-filled-and-refilled section — another chunk's cells, silently insane paths. So retirement is now
 * deferred: sections park here, stamped with the current {@link #epoch()}, and return to the pool only
 * once no search that started before the retirement can still be running.
 *
 * <p><b>Ownership.</b> {@link #retire} and {@link #tick} run on the tick thread ONLY (the grace deque is
 * deliberately unsynchronized — same confinement as the pool it feeds). Planner threads participate only
 * by reading {@link #epoch()} into their {@code activeSince} slot at search start; the epoch counter is
 * the single cross-thread cell (an {@link AtomicLong}).
 *
 * <p><b>Why this is sound.</b> A batch retired at epoch {@code E} is freed only when
 * {@code minActive > E} — i.e. every in-flight search started at an epoch {@code > E}, which is after the
 * tick that retired the batch, which is after the {@code NavStore} map replacement — so a fresh chunk
 * lookup can only find the NEW sections. A search that stamped {@code ≤ E} may hold the old sections,
 * and exactly that stamp blocks the free until it finishes. With ms-scale searches the grace is a tick
 * or two; with async off, {@code minActive == Long.MAX_VALUE} and everything drains on the next tick
 * (one-tick deferral, behaviourally invisible). Cost: one deque append per displaced chunk (rare), one
 * bounded drain per tick — nothing on any per-read or per-pop path.
 */
public final class NavReclaim {

    private NavReclaim() {}

    /** Retirement clock: bumped by {@link #tick}; sampled by planner threads at search start. Starts at 1
     *  so a pre-first-tick retirement (epoch 1) can be freed by the first tick's bump (minActive 2+). */
    private static final AtomicLong EPOCH = new AtomicLong(1);

    /** Tick-confined FIFO of retired batches (epochs are monotonic, so the head is always the oldest). */
    private static final ArrayDeque<Retired> GRACE = new ArrayDeque<>();

    private record Retired(long epoch, NavSection[] sections) {}

    /** The current retirement epoch — planner threads stamp this into their active slot at search start. */
    public static long epoch() {
        return EPOCH.get();
    }

    /**
     * Park a displaced chunk's sections until reclamation is provably safe (tick thread only; called by
     * {@link NavStore} where it used to recycle inline). {@code null} (no previous entry) is a no-op.
     */
    public static void retire(NavSection[] sections) {
        if (sections == null) return;
        GRACE.add(new Retired(EPOCH.get(), sections));
    }

    /**
     * Advance the epoch and recycle every batch no in-flight search can still hold (tick thread, once per
     * level-tick — multiple calls per server tick just advance the clock faster, which is harmless).
     * {@code minActive} is {@code PlanExecutor.minActiveStamp()}: the oldest in-flight search's start
     * epoch, or {@code Long.MAX_VALUE} when idle/off.
     */
    public static void tick(long minActive) {
        EPOCH.incrementAndGet();
        while (!GRACE.isEmpty() && GRACE.peekFirst().epoch < minActive) {
            for (NavSection s : GRACE.pollFirst().sections) {
                if (s != null) s.recycle();
            }
        }
    }

    /** Parked batch count (diagnostics/tests). */
    public static int pending() {
        return GRACE.size();
    }
}
