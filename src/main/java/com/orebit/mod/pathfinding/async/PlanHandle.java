package com.orebit.mod.pathfinding.async;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;

/**
 * The tick thread's view of one in-flight {@link SearchRequest}: a one-slot, single-producer /
 * single-consumer mailbox. The worker {@link #complete}s it exactly once; the tick thread polls
 * {@link #isDone} (a volatile read) and then reads the result — the volatile {@code done} write is
 * ordered LAST on the worker, so a {@code true} poll happens-before the result fields are read
 * (the standard safe-publication idiom; no locks, nothing per-tick beyond one volatile read).
 *
 * <p>{@link #cancel} is advisory latest-wins (DESIGN-background-pathfinding.md §3.1): a superseded
 * request still queued is skipped by the worker; one already in flight runs to completion and its
 * result is simply never adopted (the canceller stopped holding the handle). Cancellation is how the
 * per-bot "at most one outstanding search" invariant is kept — the owner (PathPlan) cancels its old
 * handle before submitting a new one.
 */
public final class PlanHandle {

    /** Worker-checked skip flag (latest-wins). Volatile: written by the tick thread, read by workers. */
    volatile boolean cancelled;

    private volatile boolean done;
    private BlockPathPlan plan;      // plain fields: published by the volatile done write below
    private boolean partial;
    private boolean rejected;
    private int expansions;

    /** Tick thread: stop caring about this request (a newer one supersedes it, or the plan was cleared). */
    public void cancel() {
        cancelled = true;
    }

    /** Whether the result is ready to read ({@code true} also for a cancelled-but-completed search). */
    public boolean isDone() {
        return done;
    }

    /** The computed plan, or {@code null} (search failed / walled in / rejected) — read after {@link #isDone}. */
    public BlockPathPlan plan() {
        return plan;
    }

    /** Whether the plan is a best-effort PARTIAL — read after {@link #isDone}. */
    public boolean wasPartial() {
        return partial;
    }

    /** The search's node count (diagnostics) — read after {@link #isDone}. */
    public int expansions() {
        return expansions;
    }

    /**
     * Whether this handle completed WITHOUT running the search — an executor failure (queue full,
     * worker threw), NOT a search that proved no path exists. The two must stay distinguishable
     * (review finding): a genuine null result means BLOCKED → the cascade blacklists the skeleton hop,
     * which is exactly wrong for a transient executor hiccup — rejected results are simply retried at
     * the next boundary. Read after {@link #isDone}.
     */
    public boolean wasRejected() {
        return rejected;
    }

    /** Worker: publish the result. The volatile {@code done} write must stay LAST. */
    void complete(BlockPathPlan plan, boolean partial, int expansions) {
        this.plan = plan;
        this.partial = partial;
        this.expansions = expansions;
        this.done = true;
    }

    /** Executor: complete WITHOUT a search (queue full / worker threw / cancelled-skip) — retryable. */
    void completeRejected() {
        this.rejected = true;
        this.done = true;
    }
}
