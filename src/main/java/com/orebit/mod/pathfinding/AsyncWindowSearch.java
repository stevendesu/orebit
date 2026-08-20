package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.async.PlanHandle;
import com.orebit.mod.pathfinding.async.SearchRequest;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
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
        RESULT,
        /** A seam-SEEDED boundary result finished and was PARKED (DESIGN-replan-handoff.md §5) — nothing
         *  installs at drain time; adoption runs through {@link #pollSeededParked}'s four ordered cases
         *  at settled boundaries. */
        PARKED
    }

    /** Verdict of {@link #pollSeededParked} — the §11 execution-edge adoption pump for a parked
     *  seam-SEEDED boundary result (DESIGN-replan-handoff.md §5 as amended by §11, owner ruling
     *  2026-08-20: the seam is a position in PLAN EXECUTION, not space). {@code KEEP} covers the
     *  before-seam park, the walk-outrun sanity bound, and the not-ours no-op;
     *  {@code ADOPT}/{@code FAST_FORWARD}/{@code PANIC} are either IMMEDIATE (the degenerate
     *  no-move-in-flight arms — result fields filled / slot cleared inside the call) or DEFERRED to the
     *  in-flight move's completion ({@link #verdictDeferred} {@code true} — the driver arms a
     *  consummation; the parked slot stays filled until {@link #consummateSeeded}, except PANIC, which
     *  drops it at verdict time); {@code ENTRY_REFUSED} is the immediate-ADOPT step-0 entry gate falling
     *  back to the park (a deferred ADOPT consults it at consummation time instead). */
    enum SeamVerdict { KEEP, ADOPT, FAST_FORWARD, ENTRY_REFUSED, PANIC }

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
    /** Whether {@link #pending} was launched from a plan-SUSPECT site — a terrain-impacted refresh,
     *  a repair, a blocked-null resubmit, or a retry inheriting one of those — i.e. its very existence
     *  implies the CURRENT plan may be invalid. Since §11 (owner ruling 2026-08-20) its only consumer
     *  is {@link #lastDrainSuspect} — a RETRY resubmit inherits the drained search's suspicion (the old
     *  follower CAUTION gate that read it live was subsumed by the uniform seam truncation + SEAM-PAUSE
     *  hold). Routine searches (fresh plan, forward-slide, cascade re-derive, P4 pre-plan) submit
     *  {@code false}. */
    private boolean pendingSuspect;
    /** Whether {@link #pending} is a seam-SEEDED mid-motion boundary replan (DESIGN-replan-handoff.md
     *  §4): its start is a FUTURE settled cell of the executing plan (the horizon seam), so its result
     *  PARKS on completion — it never installs at drain — and adopts only via {@link #pollSeededParked}'s
     *  four ordered cases. */
    private boolean pendingSeeded;
    /** The seam's waypoint index on {@link #pendingSeededFrom} (the plan the §3 walk chose it on) — read
     *  by the follower's §11 seam truncation ({@link #pendingSeededSeamFor} via
     *  {@code PathPlan.seededSeamFor}) and carried onto the parked slot as the trichotomy's seam. */
    private int pendingSeamIndex = -1;
    private BlockPathPlan pendingSeededFrom;
    /** The suspect flag of the last handle {@link #drainPending} popped — a RETRY resubmit inherits it
     *  (a retried suspect search stays suspect; a retried routine one stays routine). */
    private boolean lastDrainSuspect;
    // Parked result: computed-but-not-yet-adopted (the bot hasn't reached the predicted start). ONE slot,
    // two tenants discriminated by parkedSeeded: a P4 pre-plan (adopted via pollParked) or a seam-SEEDED
    // boundary result (adopted via pollSeededParked's four cases — DESIGN-replan-handoff.md §5).
    private BlockPathPlan parkedPlan;
    private boolean parkedPartial;
    private BlockPos parkedStart;
    private BlockPos parkedTarget;
    /** Parked slot holds a §5 seeded boundary result, not a P4 pre-plan. */
    private boolean parkedSeeded;
    /** The seeded search's REAL telemetry, carried through to {@code resultStatus} at adoption (a P4
     *  park keeps its historical {@code Integer.MAX_VALUE}/false fills in {@link #pollParked}). */
    private int parkedExpansions;
    private boolean parkedBudgetHit;
    /** The seam's index on {@link #parkedSeededFrom} — the §5 case-4 past-the-seam discriminator. */
    private int parkedSeamIndex = -1;
    private BlockPathPlan parkedSeededFrom;
    /** The window target the last pre-plan was attempted for — one attempt per target (churn guard). */
    private BlockPos preplanAttemptedTarget;

    /** The plan (may be {@code null} ⇒ BLOCKED) / partial flag of the last {@link Drain#RESULT} drain or
     *  accepted {@link #pollParked} — out-fields the driver reads immediately after the call. */
    private BlockPathPlan resultPlan;
    private boolean resultPartial;
    private int resultExpansions;
    private boolean resultBudgetHit;
    private long[] resultRealized;
    private BlockPos resultStart;
    /** The waypoint index of {@link #resultPlan} the bot's floor matched on a FAST_FORWARD adoption
     *  ({@link #pollSeededParked} case 3 — the {@link #planBodyIndex} hit), {@code -1} on every other
     *  fill (at-the-seam ADOPT, drained boundary/pre-plan results). Carried to the follower so its
     *  install seed starts the cursor PAST the already-executed steps instead of re-running them
     *  (DESIGN-replan-handoff.md §5/R3; the 2026-08-19 run-5 stale-frame Pillar wedge). */
    private int resultMatchedIndex = -1;

    // ---- §11 verdict out-fields (owner ruling 2026-08-20) — read immediately after pollSeededParked --
    /** Whether the last {@link #pollSeededParked} verdict DEFERS to a move-completion consummation (the
     *  in-execution trichotomy fired: the driver arms it and calls {@link #consummateSeeded} when the
     *  truncated terminal move completes). {@code false} = the degenerate no-move-in-flight arms
     *  installed/dropped immediately, exactly the pre-§11 contract. */
    private boolean verdictDeferred;
    /** The §11 truncation terminal on the FOLLOWER plan a deferred verdict rides: the seam's waypoint
     *  index (at-seam ADOPT) or the in-flight move's index (beyond-seam FAST_FORWARD/PANIC — "the plan
     *  will now END when the current movement ends"). Valid only on a deferred verdict. */
    private int verdictTerminal = -1;
    /** The FAST_FORWARD body-match index of the in-flight move's LANDING floor on the parked plan —
     *  carried to {@link #consummateSeeded} so the install seed starts the cursor past the matched step.
     *  {@code -1} on ADOPT. Valid only on a deferred verdict. */
    private int verdictMatched = -1;

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
     * adoption (review finding). A seam-SEEDED boundary replan ({@code seeded}) rides the normal pending
     * slot (DESIGN-replan-handoff.md §4) — the drop-parked rule above is exactly the "a parked P4 for the
     * same target is dropped when a seeded boundary replan is submitted" interaction §4 names.
     *
     * @param seeded     whether this is a seam-seeded mid-motion replan (result PARKS — §5)
     * @param seededFrom the executing plan the seam was walked on ({@code null} unless seeded)
     * @param seamIndex  the seam's waypoint index on {@code seededFrom} ({@code -1} unless seeded)
     */
    void submit(SearchRequest request, BlockPos fromFloor, BlockPos target, boolean preplan,
                boolean suspect, boolean seeded, BlockPathPlan seededFrom, int seamIndex) {
        if (pending != null) pending.cancel();
        if (!preplan) parkedPlan = null;
        pending = executor.submit(request);
        pendingStart = fromFloor;
        pendingTarget = target;
        pendingPreplan = preplan;
        pendingSuspect = suspect;
        pendingSeeded = seeded;
        pendingSeededFrom = seededFrom;
        pendingSeamIndex = seamIndex;
    }

    /** Whether ANY search is outstanding (in flight or finished-but-undrained), routine or suspect,
     *  boundary or P4 pre-plan. The follower's caution gate keys the committed-move deferral on this
     *  (owner 2026-07-31: even a routine window slide may change the route's direction — a committed
     *  jump launched into that unresolved future can land the bot off the adopted plan mid-air).
     *  A PARKED pre-plan result does not count: its plan is finished and waiting at a known seam. */
    boolean searchPending() {
        return pending != null;
    }

    /** See {@link #lastDrainSuspect}. */
    boolean lastDrainSuspect() {
        return lastDrainSuspect;
    }

    /**
     * Drain the in-flight search if it finished (tick thread) and classify it — the mailbox half of the
     * splice contract's accept step (DESIGN-background-pathfinding.md §5/§7):
     * <ul>
     *   <li><b>Boundary replan</b> result: {@link Drain#RESULT} if the bot is still within seam tolerance
     *       of the cell the search started from AND the window target hasn't moved AND the bot's floor is
     *       the searched start or its EXECUTION position — the in-flight move's landing cell
     *       ({@code landingFloor}), falling back to the live floor only when planless — lies on the
     *       result plan ({@link #startMatches}/{@link #planBodyIndex} — the post-plan reconcile, owner
     *       ruling 2026-07-30, §11-amended 2026-08-20: a body hit carries {@link #resultMatchedIndex}
     *       so the install seed skips the executed prefix); otherwise {@link Drain#RETRY} (the driver
     *       resubmits from the actual floor — the same recovery the escape hatches use).</li>
     *   <li><b>Pre-plan</b> result (P4): PARK it — the bot hasn't reached the predicted start yet. A
     *       failed ({@code null}) pre-plan is dropped — and {@code preplanAttemptedTarget} stays set, so
     *       we don't re-attempt the same doomed precompute every boundary tick; the boundary replan
     *       searches for real when the bot arrives. Either way {@link Drain#NONE}.</li>
     *   <li><b>Rejected</b> handle (executor hiccup): {@link Drain#RETRY} for a boundary replan; for a
     *       pre-plan the attempt flag is cleared (the attempt didn't run; allow another) → NONE.</li>
     *   <li><b>Seam-seeded</b> boundary result (DESIGN-replan-handoff.md §5): a found plan PARKS
     *       ({@link Drain#PARKED}) for {@link #pollSeededParked}'s four-case adoption; a moved window
     *       target → RETRY; a {@code null} plan → RESULT (BLOCKED — the repair owns it).</li>
     * </ul>
     */
    Drain drainPending(BlockPos actualFloor, BlockPos landingFloor, BlockPos currentTarget,
                       int startMode, boolean fluidAnchor) {
        if (pending == null || !pending.isDone()) {
            return Drain.NONE;
        }
        final PlanHandle done = pending;
        pending = null;
        final boolean preplan = pendingPreplan;
        final BlockPos from = pendingStart;
        final BlockPos toward = pendingTarget;
        final boolean seeded = pendingSeeded;
        final int seamIdx = pendingSeamIndex;
        final BlockPathPlan seededFrom = pendingSeededFrom;
        lastDrainSuspect = pendingSuspect; // a RETRY resubmit inherits the drained search's suspicion
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
                parkedSeeded = false; // a P4 pre-plan park — pollParked owns it, as ever
            }
            return Drain.NONE;
        }
        if (seeded) {
            // SEAM-SEEDED boundary result (DESIGN-replan-handoff.md §5): the search started from a FUTURE
            // settled cell (the horizon seam), so the walk-outrun drift test below does not apply — the
            // bot is legitimately still BEHIND the seam at drain time. A found plan PARKS; adoption runs
            // through pollSeededParked's four ordered cases at settled boundaries. A moved window target
            // makes the seed stale → RETRY (plan from where we really are, the standard recovery). A
            // null plan carries no frame to park and passes through as a normal BLOCKED result — the
            // repair owns it, exactly the sync path's semantics.
            if (!toward.equals(currentTarget)) {
                return Drain.RETRY;
            }
            if (done.plan() == null) {
                resultPlan = null;
                resultPartial = done.wasPartial();
                resultExpansions = done.expansions();
                resultBudgetHit = done.wasBudgetHit();
                resultRealized = done.realizedCrossings();
                resultStart = from;
                resultMatchedIndex = -1;
                return Drain.RESULT;
            }
            parkedPlan = done.plan();
            parkedPartial = done.wasPartial();
            parkedStart = from;
            parkedTarget = toward;
            parkedSeeded = true;
            parkedExpansions = done.expansions();
            parkedBudgetHit = done.wasBudgetHit();
            parkedSeamIndex = seamIdx;
            parkedSeededFrom = seededFrom;
            return Drain.PARKED;
        }
        // Adoption-seam OUTER box scaled to the configured search budget (owner 2026-07-31): a bot that
        // keeps walking the old route during a long search legitimately covers ~sprint-speed × budget
        // blocks, and the fixed Chebyshev-3 box would RETRY every such finished plan (the walk-outruns-
        // the-seam storm — each retry pays a full budget and the next result is outrun again). The box is
        // only the sanity bound; ADOPTION still requires the exact on-plan membership below, so widening
        // it merely lets the mid-plan-entry mechanism consider plans the bot has walked along.
        final long budgetNanos = executor == null ? 0L : executor.budgetNanos();
        final int adoptTol = Math.max(SpliceSeam.DEFAULT_TOLERANCE_CHEB,
                (int) Math.ceil(budgetNanos * 1e-9 * 6.0)); // ~6 b/s: sprint 5.6 + margin
        final SpliceSeam seam = new SpliceSeam(from, startMode, EditSnapshot.EMPTY, adoptTol);
        if (!seam.accepts(actualFloor) || !toward.equals(currentTarget)) {
            return Drain.RETRY; // drifted past tolerance / window moved — plan from where we really are
        }
        // POST-PLAN RECONCILE (owner ruling 2026-07-30, §11-amended 2026-08-20): being within seam
        // tolerance is not standing on the plan. Adopt only when the bot's floor IS the searched start
        // (the step-0 frame is exact) or the plan's body contains the bot's EXECUTION position — under
        // §11 that is the in-flight move's LANDING cell ({@code landingFloor}, the follower's
        // path.floor(cursor)), never the instantaneous mid-move floor: verdicts key on where the bot is
        // in PLAN EXECUTION. A body hit carries its matched index out (resultMatchedIndex) so the
        // follower's install seed starts the cursor PAST the executed prefix — the cursor-0 mid-plan
        // install this arm used to make (the run-5 stale-frame class, second mouth) is gone. A planless
        // drain (pollWhenPlanless) has no landing and probes the live floor — the degenerate
        // no-move-in-flight case, unchanged. Anything else → RETRY from the actual floor, the same
        // recovery a seam reject uses. A null plan (BLOCKED) carries no frame to mismatch and must pass
        // through — the repair owns it.
        int matched = -1;
        if (done.plan() != null) {
            final int yTol = fluidAnchor ? 1 : 0;
            if (!startMatches(from, actualFloor, yTol)) {
                final BlockPos probe = landingFloor != null ? landingFloor : actualFloor;
                matched = planBodyIndex(done.plan(), probe, yTol);
                if (matched < 0) {
                    return Drain.RETRY;
                }
            }
        }
        resultPlan = done.plan();
        resultPartial = done.wasPartial();
        resultExpansions = done.expansions();
        resultBudgetHit = done.wasBudgetHit();
        resultRealized = done.realizedCrossings();
        resultStart = from; // the floor the failed/successful search actually ran FROM (blame-walk input)
        resultMatchedIndex = matched; // §11: the landing's body hit (or -1 at the exact start) — the seed skips the executed prefix
        return Drain.RESULT;
    }

    /**
     * Parked pre-plan adoption test — the no-pause splice. {@code true} (and {@link #resultPlan()} /
     * {@link #resultPartial()} are set, the park slot cleared) only when the bot has actually arrived at
     * the predicted start (seam accept) and the window target is still the parked one; a moved window
     * target drops the stale precompute (the window moved on while we walked).
     */
    boolean pollParked(BlockPos actualFloor, BlockPos currentTarget, int startMode, boolean fluidAnchor) {
        if (parkedPlan == null) {
            return false;
        }
        if (parkedSeeded) {
            return false; // a §5 seeded result adopts only via pollSeededParked's four ordered cases
        }
        if (!currentTarget.equals(parkedTarget)) {
            parkedPlan = null; // the window moved on while we walked — stale precompute, drop it
            return false;
        }
        if (!new SpliceSeam(parkedStart, startMode, EditSnapshot.EMPTY).accepts(actualFloor)) {
            return false;
        }
        // POST-PLAN RECONCILE (owner ruling 2026-07-30, same test as drainPending): within tolerance but
        // not standing on the parked plan's start or route → keep it parked; a later boundary tick may
        // still arrive properly (the target-moved drop above owns staleness).
        if (!onStartOrPlan(parkedPlan, parkedStart, actualFloor, fluidAnchor ? 1 : 0)) {
            return false;
        }
        resultPlan = parkedPlan;
        resultPartial = parkedPartial;
        resultExpansions = Integer.MAX_VALUE; // a parked plan is never null — expansions are irrelevant
        resultBudgetHit = false; // a parked (adopted precompute) plan is a real path, not a cap-bound result
        resultRealized = null;   // never null-plan ⇒ never BLOCKED ⇒ no blame input
        resultStart = parkedStart;
        resultMatchedIndex = -1; // a P4 pre-plan adopts AT its predicted start — never a mid-body entry
        parkedPlan = null;
        return true;
    }

    /**
     * The adoption-membership half of the post-plan reconcile (owner ruling 2026-07-30): {@code true}
     * when {@code floor} is the cell the search ran FROM (so the adopted plan's step-0 frame is exactly
     * the bot's stand cell) or matches some step's search-native floor cell (so the follower's
     * reached-scan self-advances the cursor there on its first steer tick — the existing mid-plan
     * entry). Floor-frame equality is the same idiom the settle anchor uses ({@code BotNavigator}:
     * {@code settledFloor = path.floor(j)} compared against {@code floorOf(blockPosition)}); a
     * waypoint's X/Z equal its floor's X/Z, so only the carried {@code floorY} differs.
     *
     * <p>{@code yTol} is 0 on a ground anchor (exact), 1 when the bot is bodily in fluid: a floating
     * bot's floor cell is BOB-QUANTIZED — the buoyancy bob flips {@code floorOf(blockPosition)} between
     * adjacent Ys at steady state, so exact-Y membership would RETRY a perfectly adoptable swim plan
     * every drain (the same reason the swim follower's {@code reached} uses a continuous vertical
     * window rather than exact block-Y).
     */
    private static boolean onStartOrPlan(BlockPathPlan plan, BlockPos searchStart, BlockPos floor, int yTol) {
        return startMatches(searchStart, floor, yTol) || planBodyIndex(plan, floor, yTol) >= 0;
    }

    /** The "floor IS the searched start" arm — exact X/Z, Y within {@code yTol} (the §5 case-2 test:
     *  the adopted plan's step-0 frame is exactly the bot's stand cell). */
    private static boolean startMatches(BlockPos searchStart, BlockPos floor, int yTol) {
        return searchStart.getX() == floor.getX() && searchStart.getZ() == floor.getZ()
                && Math.abs(searchStart.getY() - floor.getY()) <= yTol;
    }

    /** The "floor lies ON the plan" arm — the waypoint index whose search-native floor matches
     *  {@code floor} (the §5 case-3 test: the follower's reached-scan enters mid-plan there on its first
     *  steer tick), or {@code -1} when the floor is not on the body. The plan's SEARCH START is not a
     *  waypoint (reconstruct is start-exclusive), so the seam floor itself never matches here — that is
     *  {@link #startMatches}'s arm. Returns the INDEX (not a boolean) so a FAST_FORWARD adoption can
     *  carry the matched step to the follower's install seed instead of forcing a re-scan
     *  (DESIGN-replan-handoff.md §5/R3). */
    static int planBodyIndex(BlockPathPlan plan, BlockPos floor, int yTol) {
        final int n = plan.size();
        for (int i = 0; i < n; i++) {
            final BlockPos wp = plan.waypoint(i);
            if (wp.getX() == floor.getX() && wp.getZ() == floor.getZ()
                    && Math.abs(plan.floorY(i) - floor.getY()) <= yTol) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The §11 execution-edge adoption pump for a parked seam-SEEDED boundary result
     * (DESIGN-replan-handoff.md §5 as amended by §11, owner ruling 2026-08-20: "the seam shouldn't be
     * about the bot's LOCATION, but about where it is in the plan execution — plans swap between moves").
     * Run at every settled boundary. Two regimes:
     *
     * <p><b>IN-EXECUTION</b> ({@code moveInFlight}, the follower walking {@code followerPlan} with the
     * move at {@code followerCursor} in flight) — the verdict is the INDEX TRICHOTOMY against the seam's
     * waypoint index, and every non-KEEP verdict is DEFERRED ({@link #verdictDeferred}) to that move's
     * completion (no plan ever swaps mid-move):
     * <ol>
     *   <li><b>before-seam</b> ({@code cursor < seamIndex}) → {@code KEEP}: park, keep executing the old
     *       plan — regardless of geometry (a reversing plan's body legitimately contains the pre-seam
     *       cells the bot is still walking; the 2026-08-18 reversal limit-cycle conviction stands,
     *       subsumed structurally: pre-seam NEVER installs).</li>
     *   <li><b>at-seam</b> ({@code cursor == seamIndex} — the move ENDING at the seam is in flight) →
     *       deferred {@code ADOPT}: the driver truncates the old plan at this move
     *       ({@link #verdictTerminal}) and consummates when it completes, settled at the seam.</li>
     *   <li><b>beyond</b> ({@code cursor > seamIndex}) → the in-flight move's LANDING floor
     *       ({@code landingFloor} = the follower plan's {@code floor(cursor)}) is tested against the new
     *       plan: the seam itself → deferred {@code ADOPT}; a body cell ({@link #planBodyIndex}, fluid
     *       yTol ±1) → deferred {@code FAST_FORWARD} carrying {@link #verdictMatched}; neither →
     *       deferred {@code PANIC} — the slot drops NOW (the result answers a premise the bot has left),
     *       the incumbent finishes its move centered and drops at consummation. The budget-scaled
     *       walk-outrun Chebyshev box is kept as a SANITY bound on the live floor: wholly away from the
     *       seam → {@code KEEP} (never a long-range PANIC).</li>
     * </ol>
     *
     * <p><b>DEGENERATE</b> (no move in flight: planless, a consumed incumbent, or the follower holding
     * at a truncated terminal) — the verdict IS the consummation, immediately, with {@code actualFloor}
     * as the settled cell (the one regime where the live floor is still the truth): the searched start →
     * {@code ADOPT} (gated on the new plan's step-0 {@link Movement#entryReady} — refusal →
     * {@code ENTRY_REFUSED}, stay parked one more boundary); past the seam by cursor, on the body →
     * {@code FAST_FORWARD}; past and off (inside the sanity box) → {@code PANIC}; else {@code KEEP}.
     * {@code ADOPT}/{@code FAST_FORWARD} here fill the result out-fields — with the seeded search's REAL
     * expansion telemetry — and clear the slot; the driver installs through {@code resultStatus} exactly
     * like a drained result.
     *
     * <p>A moved window target drops the stale result (the P4 rule). A {@code followerPlan} that is no
     * longer the plan the seam was walked on ({@code parkedSeededFrom} identity) drops the result too —
     * its seam index is meaningless against a different execution, and a location-lucky install from a
     * dead premise is exactly what §11 removes.
     */
    SeamVerdict pollSeededParked(BotSteering bot, BlockPos actualFloor, BlockPos landingFloor,
                                 BlockPos currentTarget, int startMode, boolean fluidAnchor,
                                 BlockPathPlan followerPlan, int followerCursor, boolean moveInFlight) {
        verdictDeferred = false;
        verdictTerminal = -1;
        verdictMatched = -1;
        if (parkedPlan == null || !parkedSeeded) {
            return SeamVerdict.KEEP;
        }
        if (!currentTarget.equals(parkedTarget)) {
            parkedPlan = null; // the window moved on while we walked — stale, drop (the P4 rule)
            return SeamVerdict.KEEP;
        }
        if (followerPlan != null && parkedSeededFrom != followerPlan) {
            parkedPlan = null; // seeded off a plan that no longer executes — dead premise, drop
            return SeamVerdict.KEEP;
        }
        final int yTol = fluidAnchor ? 1 : 0;
        final long budgetNanos = executor == null ? 0L : executor.budgetNanos();
        final int adoptTol = Math.max(SpliceSeam.DEFAULT_TOLERANCE_CHEB,
                (int) Math.ceil(budgetNanos * 1e-9 * 6.0)); // ~6 b/s — drainPending's walk-outrun bound
        final boolean inExecution = moveInFlight && followerPlan != null
                && followerCursor >= 0 && followerCursor < followerPlan.size() && parkedSeamIndex >= 0;
        if (!inExecution) {
            // DEGENERATE — no move in flight: consummation is immediate, on the settled live floor.
            if (startMatches(parkedStart, actualFloor, yTol)) {
                if (bot != null && !parkedPlan.isEmpty()
                        && !parkedPlan.movement(0).entryReady(bot, bot.footX(), bot.footY(), bot.footZ())) {
                    return SeamVerdict.ENTRY_REFUSED;
                }
                consummateSeeded(-1); // at the seam — the plan runs from its step 0
                return SeamVerdict.ADOPT;
            }
            if (!new SpliceSeam(parkedStart, startMode, EditSnapshot.EMPTY, adoptTol).accepts(actualFloor)) {
                return SeamVerdict.KEEP; // not near the seam (or drifted wholly away) — stay parked
            }
            final boolean pastSeam = followerPlan != null && followerCursor > parkedSeamIndex
                    && parkedSeamIndex >= 0;
            final int matched = pastSeam ? planBodyIndex(parkedPlan, actualFloor, yTol) : -1;
            if (matched >= 0) {
                consummateSeeded(matched);
                return SeamVerdict.FAST_FORWARD;
            }
            if (pastSeam) {
                parkedPlan = null; // settled past the seam, off the plan — drop; the driver nulls + relaunches
                return SeamVerdict.PANIC;
            }
            return SeamVerdict.KEEP; // planless approach — wait for the seam (or the stale drop)
        }
        // IN-EXECUTION — the §11 index trichotomy; every non-KEEP verdict defers to move completion.
        if (followerCursor < parkedSeamIndex) {
            return SeamVerdict.KEEP; // before-seam: park, keep executing (geometry deliberately unread)
        }
        if (followerCursor == parkedSeamIndex) {
            verdictDeferred = true;
            verdictTerminal = parkedSeamIndex; // truncate at the seam move; consummate at its completion
            return SeamVerdict.ADOPT;
        }
        // beyond: the in-flight move's landing decides — sanity-bounded on the live floor.
        if (!new SpliceSeam(parkedStart, startMode, EditSnapshot.EMPTY, adoptTol).accepts(actualFloor)) {
            return SeamVerdict.KEEP; // wholly away from the seam — never a long-range PANIC
        }
        if (landingFloor == null) {
            return SeamVerdict.KEEP; // no landing derivable (defensive) — stay parked
        }
        if (startMatches(parkedStart, landingFloor, yTol)) {
            verdictDeferred = true;
            verdictTerminal = followerCursor; // the move lands ON the seam — at-seam-equivalent
            return SeamVerdict.ADOPT;
        }
        final int matched = planBodyIndex(parkedPlan, landingFloor, yTol);
        if (matched >= 0) {
            verdictDeferred = true;
            verdictTerminal = followerCursor;
            verdictMatched = matched;
            return SeamVerdict.FAST_FORWARD;
        }
        verdictDeferred = true;
        verdictTerminal = followerCursor;
        parkedPlan = null; // PANIC: the result is useless now; the PLAN drop waits for the move to finish
        return SeamVerdict.PANIC;
    }

    /** See {@link #verdictDeferred}. */
    boolean verdictDeferred() {
        return verdictDeferred;
    }

    /** See {@link #verdictTerminal}. */
    int verdictTerminal() {
        return verdictTerminal;
    }

    /** See {@link #verdictMatched}. */
    int verdictMatched() {
        return verdictMatched;
    }

    /** Consummate the parked SEEDED result (§11): fill the result out-fields from the slot (real
     *  telemetry) and clear it. {@code matchedIndex} is the FAST_FORWARD body match of the terminal
     *  move's landing ({@code -1} on an ADOPT) — carried out so the follower's install seed never
     *  re-scans the plan. Called by the degenerate arms above (immediate) and by the driver's armed
     *  consummation when the truncated terminal move completes (deferred). */
    void consummateSeeded(int matchedIndex) {
        resultPlan = parkedPlan;
        resultPartial = parkedPartial;
        resultExpansions = parkedExpansions;
        resultBudgetHit = parkedBudgetHit;
        resultRealized = null; // never null-plan ⇒ never BLOCKED ⇒ no blame input
        resultStart = parkedStart;
        resultMatchedIndex = matchedIndex;
        parkedPlan = null;
    }

    /**
     * Park a SYNC seeded result (DESIGN-replan-handoff.md §5): sync mode has no pending handle — the
     * driver computed the plan inline — but a seeded result must still install only via the four ordered
     * adoption cases at a settled boundary, never inline mid-move (the same-tick install under a bot
     * still moving inside the settled cell IS the §1 wedge). So it enters the same parked slot the async
     * drain fills. Null plans never come here (no frame to park — the driver installs them as BLOCKED
     * inline, mirroring {@link #drainPending}'s null-plan pass-through).
     */
    void parkSeededResult(BlockPathPlan plan, boolean partial, int expansions, boolean budgetHit,
                          BlockPos fromFloor, BlockPos target, BlockPathPlan seededFrom, int seamIndex) {
        parkedPlan = plan;
        parkedPartial = partial;
        parkedStart = fromFloor;
        parkedTarget = target;
        parkedSeeded = true;
        parkedExpansions = expansions;
        parkedBudgetHit = budgetHit;
        parkedSeamIndex = seamIndex;
        parkedSeededFrom = seededFrom;
    }

    /** Whether the parked slot holds a §5 seeded boundary result (vs a P4 pre-plan). */
    boolean seededParked() {
        return parkedPlan != null && parkedSeeded;
    }

    /** The parked result's searched-from cell (the seam) — diagnostic read for the park log line. */
    BlockPos parkedStartCell() {
        return parkedStart;
    }

    /** The parked result's window target — the driver's armed §11 consummation re-checks staleness
     *  against it each boundary (the P4 moved-target rule, applied while holding). */
    BlockPos parkedTargetCell() {
        return parkedTarget;
    }

    /** The parked seeded PLAN itself — the armed §11 ADOPT consummation consults its step-0
     *  {@code entryReady} at completion time (the gate moved from verdict time, owner ruling
     *  2026-08-20). Never exposed for mutation; {@code null} when nothing is parked. */
    BlockPathPlan parkedSeededPlan() {
        return parkedSeeded ? parkedPlan : null;
    }

    /** The seam's waypoint index on {@link #parkedSeededFrom} — the §5 past-the-seam discriminator. */
    int parkedSeamIndex() {
        return parkedSeamIndex;
    }

    /** The executing plan the parked seeded result's seam was walked on (identity compare only). */
    BlockPathPlan parkedSeededFrom() {
        return parkedSeededFrom;
    }

    /** The seam index of the outstanding seam-SEEDED search (in flight or finished-but-undrained) whose
     *  seam was walked on {@code plan}, or {@code -1} — the follower's §11 seam truncation keys on this
     *  (via {@code PathPlan.seededSeamFor}): the plan ENDS at the seam while the search runs, and the
     *  bot holds centered there until the result lands (DESIGN-replan-handoff.md §7/§11). */
    int pendingSeededSeamFor(BlockPathPlan plan) {
        return pending != null && pendingSeeded && plan != null && pendingSeededFrom == plan
                ? pendingSeamIndex : -1;
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

    /** Whether the last drained/adopted result was bound by its node/time cap ({@code budgetHit}) — telemetry
     *  paired with {@link #resultPlan()}. */
    boolean resultBudgetHit() {
        return resultBudgetHit;
    }

    /** The realized region-crossing pairs paired with a {@code null} {@link #resultPlan()} (the Fix-A blame
     *  input, carried from the worker's failed search); {@code null} when the plan is non-null. */
    long[] resultRealized() {
        return resultRealized;
    }

    /** The floor cell the drained/adopted search ran FROM ({@link SearchRequest}'s start) — paired with
     *  {@link #resultPlan()}; the blame walk's start-region input on a {@code null} plan. */
    BlockPos resultStart() {
        return resultStart;
    }

    /** See {@link #resultMatchedIndex} — paired with {@link #resultPlan()}; {@code -1} unless the last
     *  fill was a FAST_FORWARD adoption. */
    int resultMatchedIndex() {
        return resultMatchedIndex;
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

    /** Drop the parked P4 precompute (window refresh — a consumed/terrain-impacted plan whose parked
     *  result already failed this tick's adoption test can never be adopted by the settled bot; keeping
     *  it would veto every {@code replanBlock} resubmit toward its target — review finding 2026-07-30). */
    void dropParked() {
        parkedPlan = null;
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
