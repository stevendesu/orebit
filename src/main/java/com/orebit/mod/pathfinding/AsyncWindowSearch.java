package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.async.PlanHandle;
import com.orebit.mod.pathfinding.async.SearchRequest;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.splice.SpliceSeam;

import net.minecraft.core.BlockPos;

/**
 * The async search <b>mailbox</b> for {@link PathPlan} (DESIGN-background-pathfinding.md §5/§7) — the
 * in-flight {@code pending} handle, the parked P4 pre-plan result, and the one-attempt-per-target
 * pre-plan churn guard, extracted verbatim from the driver.
 *
 * <p><b>Ownership / seam</b>: this class owns ONLY the mailbox state and its transitions (submit /
 * supersede / drain / park / adopt-test / cancel). Every DECISION about what a drained result means —
 * adopting a plan, flipping {@code status} to RUNNING/BLOCKED, resubmitting via {@code replanBlock()} —
 * stays in {@link PathPlan}: {@link #drainPending} classifies the finished handle into a {@link Drain}
 * verdict the driver switches on, and {@link #pollParked} answers "adopt the parked pre-plan now?"; the
 * adopted plan + partial flag are exposed through {@link #resultPlan()}/{@link #resultPartial()}, which
 * the driver reads immediately (tick-thread-confined, so plain out-fields are safe — no allocation for
 * a result object). All state here is meaningful only when the plan runs with a non-null executor; the
 * object exists (empty) in sync mode so {@code cancel()} is always safe.
 */
final class AsyncWindowSearch {

    /** Outcome of {@link #drainPending} — the mailbox's verdict on the finished boundary search. */
    enum Drain {
        /** Nothing for the driver to act on: no finished search, a rejected pre-plan (attempt flag
         *  cleared internally so another may run), or a pre-plan result that was PARKED/dropped. */
        NONE,
        /** The result is unusable — an executor hiccup (queue full / worker threw / cancelled-skip,
         *  NOT a search verdict: never map to BLOCKED, that blacklists a real skeleton hop — review
         *  finding) or a boundary result whose seam rejected / window target moved. The driver should
         *  {@code replanBlock()} from the bot's actual floor. */
        RETRY,
        /** A boundary-replan result was seam-accepted: adopt {@link #resultPlan()} /
         *  {@link #resultPartial()}. A {@code null} plan = BLOCKED, exactly the sync path's semantics. */
        RESULT
    }

    // ---- async in-flight state (all tick-thread-confined; meaningful only when executor != null) ----
    /** The outstanding search, or {@code null}. At most one per plan (latest-wins: superseders cancel). */
    private PlanHandle pending;
    /** The floor cell {@link #pending} was searched FROM — the seam's predicted start. */
    private BlockPos pendingStart;
    /** The window target {@link #pending} was searched TOWARD (adoption re-checks it's still current). */
    private BlockPos pendingTarget;
    /** Whether {@link #pending} is a P4 pre-plan (predicted future start) vs a boundary replan (started
     *  from the bot's actual floor). A pre-plan result PARKS until the bot arrives; a replan result whose
     *  seam rejects resubmits immediately. */
    private boolean pendingPreplan;
    // Parked pre-plan result: computed-but-not-yet-adopted (the bot hasn't reached the predicted start).
    private BlockPathPlan parkedPlan;
    private boolean parkedPartial;
    private BlockPos parkedStart;
    private BlockPos parkedTarget;
    /** The window target the last pre-plan was attempted for — one attempt per target (churn guard). */
    private BlockPos preplanAttemptedTarget;

    /** The plan (may be {@code null} ⇒ BLOCKED) / partial flag of the last {@link Drain#RESULT} drain or
     *  accepted {@link #pollParked} — out-fields the driver reads immediately after the call. */
    private BlockPathPlan resultPlan;
    private boolean resultPartial;
    private int resultExpansions;

    private final PlanExecutor executor;

    /** @param executor the background planner pool, or {@code null} = sync mode (mailbox stays empty). */
    AsyncWindowSearch(PlanExecutor executor) {
        this.executor = executor;
    }

    /**
     * Submit {@code request} and record the mailbox bookkeeping: any in-flight search is superseded
     * (latest-wins cancel), and a boundary replan ({@code !preplan}) drops the parked precompute — the
     * parked plan was predicated on the PREVIOUS plan's end cell and remaining edits, both stale once a
     * new search replaces that plan. Without this, a stale parked plan could later overwrite the fresh
     * adoption (review finding).
     */
    void submit(SearchRequest request, BlockPos fromFloor, BlockPos target, boolean preplan) {
        if (pending != null) pending.cancel();
        if (!preplan) parkedPlan = null;
        pending = executor.submit(request);
        pendingStart = fromFloor;
        pendingTarget = target;
        pendingPreplan = preplan;
    }

    /**
     * Drain the in-flight search if it finished (tick thread) and classify it — the mailbox half of the
     * splice contract's accept step (DESIGN-background-pathfinding.md §5/§7):
     * <ul>
     *   <li><b>Boundary replan</b> result: {@link Drain#RESULT} if the bot is still within seam tolerance
     *       of the cell the search started from AND the window target hasn't moved; otherwise
     *       {@link Drain#RETRY} (the driver resubmits from the actual floor — the same recovery the
     *       escape hatches use).</li>
     *   <li><b>Pre-plan</b> result (P4): PARK it — the bot hasn't reached the predicted start yet. A
     *       failed ({@code null}) pre-plan is dropped — and {@code preplanAttemptedTarget} stays set, so
     *       we don't re-attempt the same doomed precompute every boundary tick; the boundary replan
     *       searches for real when the bot arrives. Either way {@link Drain#NONE}.</li>
     *   <li><b>Rejected</b> handle (executor hiccup): {@link Drain#RETRY} for a boundary replan; for a
     *       pre-plan the attempt flag is cleared (the attempt didn't run; allow another) → NONE.</li>
     * </ul>
     */
    Drain drainPending(BlockPos actualFloor, BlockPos currentTarget, int startMode) {
        if (pending == null || !pending.isDone()) {
            return Drain.NONE;
        }
        final PlanHandle done = pending;
        pending = null;
        final boolean preplan = pendingPreplan;
        final BlockPos from = pendingStart;
        final BlockPos toward = pendingTarget;
        if (done.wasRejected()) {
            if (preplan) {
                preplanAttemptedTarget = null; // the attempt didn't run; allow another
                return Drain.NONE;
            }
            return Drain.RETRY;
        }
        if (preplan) {
            if (done.plan() != null) {
                parkedPlan = done.plan();
                parkedPartial = done.wasPartial();
                parkedStart = from;
                parkedTarget = toward;
            }
            return Drain.NONE;
        }
        final SpliceSeam seam = new SpliceSeam(from, startMode, EditSnapshot.EMPTY);
        if (!seam.accepts(actualFloor) || !toward.equals(currentTarget)) {
            return Drain.RETRY; // drifted past tolerance / window moved — plan from where we really are
        }
        resultPlan = done.plan();
        resultPartial = done.wasPartial();
        resultExpansions = done.expansions();
        return Drain.RESULT;
    }

    /**
     * Parked pre-plan adoption test — the no-pause splice. {@code true} (and {@link #resultPlan()} /
     * {@link #resultPartial()} are set, the park slot cleared) only when the bot has actually arrived at
     * the predicted start (seam accept) and the window target is still the parked one; a moved window
     * target drops the stale precompute (the window moved on while we walked).
     */
    boolean pollParked(BlockPos actualFloor, BlockPos currentTarget, int startMode) {
        if (parkedPlan == null) {
            return false;
        }
        if (!currentTarget.equals(parkedTarget)) {
            parkedPlan = null; // the window moved on while we walked — stale precompute, drop it
            return false;
        }
        if (!new SpliceSeam(parkedStart, startMode, EditSnapshot.EMPTY).accepts(actualFloor)) {
            return false;
        }
        resultPlan = parkedPlan;
        resultPartial = parkedPartial;
        resultExpansions = Integer.MAX_VALUE; // a parked plan is never null — expansions are irrelevant
        parkedPlan = null;
        return true;
    }

    /** The plan of the last {@link Drain#RESULT} / accepted {@link #pollParked} ({@code null} ⇒ BLOCKED). */
    BlockPathPlan resultPlan() {
        return resultPlan;
    }

    /** The partial flag paired with {@link #resultPlan()}. */
    boolean resultPartial() {
        return resultPartial;
    }

    /** The expansion count paired with {@link #resultPlan()} — a {@code null} plan with ≤1 expansion is a
     *  START-DEAD search (the start cell itself emitted no candidates: buried bot), which must never
     *  repair-blacklist a skeleton hop (s52b). */
    int resultExpansions() {
        return resultExpansions;
    }

    /** Whether an unfinished search toward {@code target} is already in flight (skip-resubmit guard). */
    boolean pendingSearchToward(BlockPos target) {
        return pending != null && !pending.isDone() && target.equals(pendingTarget);
    }

    /** Whether the in-flight search is a P4 pre-plan (only meaningful after {@link #pendingSearchToward}). */
    boolean pendingIsPreplan() {
        return pendingPreplan;
    }

    /** Whether a precomputed result is already parked for {@code target} (arrival adopts it). */
    boolean parkedFor(BlockPos target) {
        return parkedPlan != null && target.equals(parkedTarget);
    }

    /** Mailbox-idle half of the {@code wantsPreplan} gate: nothing in flight and nothing parked. */
    boolean quiet() {
        return pending == null && parkedPlan == null;
    }

    /** Whether a pre-plan was already attempted for {@code target} (one attempt per target — churn guard). */
    boolean preplanAttempted(BlockPos target) {
        return target.equals(preplanAttemptedTarget);
    }

    /** Record the one pre-plan attempt for {@code target} (before submitting it). */
    void markPreplanAttempt(BlockPos target) {
        preplanAttemptedTarget = target;
    }

    /** Stop caring about any in-flight search and drop the parked pre-plan (the owner cleared/replaced
     *  this plan). Safe in sync mode (everything is already {@code null}). */
    void cancel() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        parkedPlan = null;
    }
}
