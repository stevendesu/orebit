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

    /** Verdict of {@link #pollSeededParked} — the four-case adoption pump for a parked seam-SEEDED
     *  boundary result (DESIGN-replan-handoff.md §5, R2–R4). {@code KEEP} covers case 1 (result stays
     *  parked — the bot is not at the seam yet, or beyond the walk-outrun tolerance) and the not-ours
     *  no-op; {@code ENTRY_REFUSED} is case 2's step-0 entry gate falling back to case 1. */
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
     *  implies the CURRENT plan may be invalid. Read (with {@link #pending} != null, which also covers a
     *  finished-but-undrained handle) by the follower's caution gate via
     *  {@code PathPlan.suspectSearchPending()} (DESIGN-async-step-safety.md §3). Routine searches
     *  (fresh plan, forward-slide, cascade re-derive, P4 pre-plan) submit {@code false}. */
    private boolean pendingSuspect;
    /** Whether {@link #pending} is a seam-SEEDED mid-motion boundary replan (DESIGN-replan-handoff.md
     *  §4): its start is a FUTURE settled cell of the executing plan (the horizon seam), so its result
     *  PARKS on completion — it never installs at drain — and adopts only via {@link #pollSeededParked}'s
     *  four ordered cases. */
    private boolean pendingSeeded;
    /** The seam's waypoint index on {@link #pendingSeededFrom} (the plan the §3 walk chose it on) — read
     *  by the follower's rescoped CAUTION hold ({@link #pendingSeededSeamFor}) and carried onto the
     *  parked slot as the §5 past-the-seam discriminator. */
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

    /** Whether the outstanding search (in flight OR finished-but-undrained — {@link #pending} lives
     *  until the drain classifies it, which deliberately covers the touchdown tick before a boundary
     *  drain runs) was launched from a plan-suspect site. See {@link #pendingSuspect}. */
    boolean suspectPending() {
        return pending != null && pendingSuspect;
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
     *       the searched start or on the result plan ({@link #onStartOrPlan} — the post-plan reconcile,
     *       owner ruling 2026-07-30); otherwise {@link Drain#RETRY} (the driver resubmits from the actual
     *       floor — the same recovery the escape hatches use).</li>
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
    Drain drainPending(BlockPos actualFloor, BlockPos currentTarget, int startMode, boolean fluidAnchor) {
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
        // POST-PLAN RECONCILE (owner ruling 2026-07-30): being within seam tolerance is not standing on
        // the plan. Adopt only when the bot's floor IS the searched start (the step-0 frame is exact) or
        // lies ON the result plan (the follower's reached-scan then enters mid-plan on its first steer
        // tick — the existing "advance SKIPPED n steps" mechanism, which covers a bot that walked ahead
        // along the old route while the search ran). Anything else would frame step 0 from a cell the
        // search never planned from (the silent ≤3-cell mismatch the ruling closes) → RETRY from the
        // actual floor, the same recovery a seam reject uses. A null plan (BLOCKED) carries no frame to
        // mismatch and must pass through — the repair owns it.
        if (done.plan() != null && !onStartOrPlan(done.plan(), from, actualFloor, fluidAnchor ? 1 : 0)) {
            return Drain.RETRY;
        }
        resultPlan = done.plan();
        resultPartial = done.wasPartial();
        resultExpansions = done.expansions();
        resultBudgetHit = done.wasBudgetHit();
        resultRealized = done.realizedCrossings();
        resultStart = from; // the floor the failed/successful search actually ran FROM (blame-walk input)
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
        return startMatches(searchStart, floor, yTol) || onPlanBody(plan, floor, yTol);
    }

    /** The "floor IS the searched start" arm — exact X/Z, Y within {@code yTol} (the §5 case-2 test:
     *  the adopted plan's step-0 frame is exactly the bot's stand cell). */
    private static boolean startMatches(BlockPos searchStart, BlockPos floor, int yTol) {
        return searchStart.getX() == floor.getX() && searchStart.getZ() == floor.getZ()
                && Math.abs(searchStart.getY() - floor.getY()) <= yTol;
    }

    /** The "floor lies ON the plan" arm — some step's search-native floor matches (the §5 case-3 test:
     *  the follower's reached-scan enters mid-plan there on its first steer tick). */
    private static boolean onPlanBody(BlockPathPlan plan, BlockPos floor, int yTol) {
        final int n = plan.size();
        for (int i = 0; i < n; i++) {
            final BlockPos wp = plan.waypoint(i);
            if (wp.getX() == floor.getX() && wp.getZ() == floor.getZ()
                    && Math.abs(plan.floorY(i) - floor.getY()) <= yTol) {
                return true;
            }
        }
        return false;
    }

    /**
     * The §5 four-case adoption pump for a parked seam-SEEDED boundary result
     * (DESIGN-replan-handoff.md §5, R2–R4) — the parked-P4 contract generalized. Run at every settled
     * boundary; cases in order:
     * <ol>
     *   <li><b>PARK</b> (→ {@code KEEP}): the bot has not settled at the seam — outside the
     *       budget-scaled Chebyshev tolerance (the walk-outrun bound, the same formula as
     *       {@link #drainPending}'s), or inside it but still approaching along the old plan. The result
     *       waits for a later boundary.</li>
     *   <li><b>ADOPT</b>: the bot settled AT the seam ({@code actualFloor} IS the searched start,
     *       fluid yTol ±1) — gated on the new plan's step-0 {@link Movement#entryReady} accepting the
     *       bot's LIVE pose at the feet cell it actually occupies (settled at the seam floor, that IS
     *       the plan's step-0 frame — provably always-true for the current movement set, §5; a future
     *       directional/velocity entry test is honored automatically). Refusal →
     *       {@code ENTRY_REFUSED}: stay parked one more boundary (fall back to case 1).</li>
     *   <li><b>FAST_FORWARD</b>: past the seam but the floor lies ON the plan — install; the follower's
     *       reached-scan enters mid-plan on its first steer tick ("advance SKIPPED").</li>
     *   <li><b>PANIC</b>: past the seam and NOT on the plan — the parked result is dropped here; the
     *       driver drops the incumbent plan too and resubmits from rest (R4).</li>
     * </ol>
     * A moved window target drops the stale result (the P4 rule). {@code ADOPT}/{@code FAST_FORWARD}
     * fill the result out-fields — with the seeded search's REAL expansion telemetry — and clear the
     * slot; the driver installs through {@code resultStatus} exactly like a drained result.
     */
    SeamVerdict pollSeededParked(BotSteering bot, BlockPos actualFloor, BlockPos currentTarget,
                                 int startMode, boolean fluidAnchor, boolean pastSeam) {
        if (parkedPlan == null || !parkedSeeded) {
            return SeamVerdict.KEEP;
        }
        if (!currentTarget.equals(parkedTarget)) {
            parkedPlan = null; // the window moved on while we walked — stale, drop (the P4 rule)
            return SeamVerdict.KEEP;
        }
        final int yTol = fluidAnchor ? 1 : 0;
        if (startMatches(parkedStart, actualFloor, yTol)) {
            if (bot != null && !parkedPlan.isEmpty()
                    && !parkedPlan.movement(0).entryReady(bot, bot.footX(), bot.footY(), bot.footZ())) {
                return SeamVerdict.ENTRY_REFUSED;
            }
            adoptParkedSeeded();
            return SeamVerdict.ADOPT;
        }
        final long budgetNanos = executor == null ? 0L : executor.budgetNanos();
        final int adoptTol = Math.max(SpliceSeam.DEFAULT_TOLERANCE_CHEB,
                (int) Math.ceil(budgetNanos * 1e-9 * 6.0)); // ~6 b/s — drainPending's walk-outrun bound
        if (!new SpliceSeam(parkedStart, startMode, EditSnapshot.EMPTY, adoptTol).accepts(actualFloor)) {
            return SeamVerdict.KEEP; // case 1 — not near the seam yet (or drifted wholly away)
        }
        // R3's precondition is LOAD-BEARING (owner ruling 2026-08-18): fast-forward is for a bot that has
        // advanced PAST the seam, never before it. A seam-seeded plan legitimately DOUBLES BACK through the
        // pre-seam cells the bot is still walking (the ReplanCourse reversal: seam (75), plan (74)->(73)->west
        // over a bot at (73)), so a pre-seam body entry installs a cursor-0 step whose arrival planes straddle
        // the bot mid-cell — no waypoint ever reads reached, the cursor pins at 0, and the follower stands in a
        // yaw-flapping limit cycle (convicted by exec dump 2026-08-18: ±90° alternation at x≈73.87 for 858
        // ticks). Pre-seam + on-plan = case 1: stay parked, walk the old plan to the seam, ADOPT there with the
        // full-cell reversal runway of §2.
        if (pastSeam && onPlanBody(parkedPlan, actualFloor, yTol)) {
            adoptParkedSeeded();
            return SeamVerdict.FAST_FORWARD;
        }
        if (pastSeam) {
            parkedPlan = null; // case 4 — the driver nulls the incumbent and resubmits from rest
            return SeamVerdict.PANIC;
        }
        return SeamVerdict.KEEP; // still BEFORE the seam (on the new plan's body or off it) — keep approaching
    }

    /** Fill the result out-fields from the parked SEEDED slot (real telemetry) and clear it. */
    private void adoptParkedSeeded() {
        resultPlan = parkedPlan;
        resultPartial = parkedPartial;
        resultExpansions = parkedExpansions;
        resultBudgetHit = parkedBudgetHit;
        resultRealized = null; // never null-plan ⇒ never BLOCKED ⇒ no blame input
        resultStart = parkedStart;
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

    /** The seam's waypoint index on {@link #parkedSeededFrom} — the §5 past-the-seam discriminator. */
    int parkedSeamIndex() {
        return parkedSeamIndex;
    }

    /** The executing plan the parked seeded result's seam was walked on (identity compare only). */
    BlockPathPlan parkedSeededFrom() {
        return parkedSeededFrom;
    }

    /** The seam index of the outstanding seam-SEEDED search (in flight or finished-but-undrained) whose
     *  seam was walked on {@code plan}, or {@code -1} — the follower's rescoped CAUTION hold keys on
     *  this (DESIGN-replan-handoff.md §7: the only step whose outcome a pending seeded search can
     *  change is the committing step AT the seam). */
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
