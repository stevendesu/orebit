package com.orebit.mod.pathfinding.async;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavReclaim;

/**
 * The background planner pool (DESIGN-background-pathfinding.md §3): a FIXED set of daemon threads
 * ({@code orebit-planner-N}, {@code pathing.maxThreads}, clamped {@code [1, cores − 2]}) that run
 * block-tier searches off the server tick thread. The tick thread {@link #submit}s an immutable
 * {@link SearchRequest} and polls the returned {@link PlanHandle}; adoption stays boundary-gated in
 * {@code PathPlan}. The pool is fixed at construction so each thread's ThreadLocal search scratch
 * ({@code Nodes}/{@code EditPool}) grows once and serves that thread forever — today's
 * zero-steady-state-alloc regime, times N.
 *
 * <p><b>What workers may touch.</b> A worker reads ONLY the {@code NavStore} section maps (through a
 * {@link NavGridView#background no-live-fallback} view), the immutable {@code NavBlock}/{@code
 * MiningModel} tables, and its request's own value objects. It never mutates the world model, never
 * touches {@code NavSectionPool} (tick-confined), never dereferences the level. World-model mutation
 * stays single-owner on the tick thread; N workers are N <i>readers</i>, guarded by the epoch
 * reclamation below.
 *
 * <p><b>Epoch stamps (the §4.1 reclamation guard's reader half).</b> Around every search a worker
 * writes its slot in {@link #activeSince}: the current {@link NavReclaim#epoch()} at start,
 * {@link #IDLE} at end — two uncontended volatile writes per search, nothing per-node. The tick
 * thread's {@link NavReclaim#tick} frees a retired {@code NavSection} batch only when
 * {@link #minActiveStamp()} has advanced past its retirement epoch, i.e. no search that could still
 * hold it is running. The stamp is written BEFORE the request is read, so a search that misses a
 * retirement's drain check strictly post-dates the store replacement and cannot hold the old sections.
 *
 * <p><b>Failure isolation.</b> A worker catches everything per-request (result {@code null} → the
 * BLOCKED/repair path, logged once per minute); the thread never dies with a search. Threads are
 * daemons — server shutdown needs no join because workers hold no live server objects.
 */
public final class PlanExecutor {

    /** Idle sentinel in {@link #activeSince} — {@code Long.MAX_VALUE} so idle slots never bound the min. */
    static final long IDLE = Long.MAX_VALUE;

    /** Queued-but-unstarted request cap; cancelled entries are skipped at dequeue. Bots hold at most one
     *  outstanding request each (latest-wins at the PathPlan layer), so depth ≈ bot count. */
    private static final int QUEUE_CAPACITY = 256;

    private static volatile PlanExecutor instance;

    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLongArray activeSince;
    private final long budgetNanos;
    /** Rate-limits failure logs across the tick thread + all workers (CAS so racing loggers emit once). */
    private final AtomicLong lastFailLogNanos = new AtomicLong();
    /**
     * Jobs successfully enqueued / jobs fully finished (completed or skipped). {@link #drainIdle} waits on
     * {@code finished == submitted} INSTEAD of "queue empty + all slots idle": a worker between
     * {@code queue.take()} and its {@code activeSince} stamp is invisible to both of those checks (review
     * finding — the TOCTOU window includes the cancelled-check and any preemption), but it has not
     * incremented {@code finished}, so the counter pair sees it. {@code submitted} is tick-thread-only;
     * {@code finished} is incremented by workers strictly AFTER the handle completes and the slot reads
     * IDLE again.
     */
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();

    private record Job(SearchRequest request, PlanHandle handle) {}

    private PlanExecutor(int threads, long budgetNanos) {
        this.activeSince = new AtomicLongArray(threads);
        this.budgetNanos = budgetNanos;
        for (int i = 0; i < threads; i++) {
            activeSince.set(i, IDLE);
            final int idx = i;
            Thread t = new Thread(() -> workerLoop(idx), "orebit-planner-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // yield to the tick thread under contention
            t.start();
        }
    }

    /** The running executor, or {@code null} when async pathing is off / the server hasn't started. */
    public static PlanExecutor instance() {
        return instance;
    }

    /**
     * Start the pool once per server (idempotent; SERVER_STARTED, after config load). {@code maxThreads}
     * is clamped to {@code [1, availableProcessors − 2]} (never below 1); {@code searchBudgetMs} becomes
     * every request's default wall-clock budget. Also pre-warms each thread's search scratch and
     * round-trips one no-op per thread, so the submit/complete pipe is exercised before any real search.
     */
    public static synchronized void start(int maxThreads, int searchBudgetMs) {
        if (instance != null) {
            // Integrated-server world re-open in the same JVM: the pool (and its budget) is fixed for the
            // JVM's lifetime. A changed maxThreads/searchBudgetMs needs a full restart — say so once.
            if (instance.budgetNanos != searchBudgetMs * 1_000_000L) {
                OrebitCommon.LOGGER.warn("[Orebit] planner pool already running with the previous world's "
                        + "settings — restart the game to apply changed pathing.maxThreads/asyncSearchBudgetMs");
            }
            return;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, Math.min(maxThreads, Math.max(1, cores - 2)));
        instance = new PlanExecutor(threads, searchBudgetMs * 1_000_000L);
        OrebitCommon.LOGGER.info("[Orebit] planner pool started: {} thread(s), search budget {} ms",
                threads, searchBudgetMs);
    }

    /** The per-search wall-clock budget (nanos) requests built for this executor should carry. */
    public long budgetNanos() {
        return budgetNanos;
    }

    /**
     * Enqueue a search (tick thread). Never blocks: a full queue completes the handle immediately with
     * {@code null} (the caller's BLOCKED/repair machinery treats it as a failed search and retries) —
     * with per-bot latest-wins the queue can only fill under a pathological bot count, so this is a
     * back-pressure valve, not a working path.
     */
    public PlanHandle submit(SearchRequest request) {
        PlanHandle handle = new PlanHandle();
        if (queue.offer(new Job(request, handle))) {
            submitted.incrementAndGet();
        } else {
            handle.completeRejected(); // retryable executor failure — NOT a "no path exists" result
            if (shouldLogFailure()) {
                OrebitCommon.LOGGER.warn("[Orebit] planner queue full ({}) — search rejected "
                        + "(too many bots for pathing.maxThreads?)", QUEUE_CAPACITY);
            }
        }
        return handle;
    }

    /** One failure log per minute across all threads (CAS'd so a racing pair emits once, not twice). */
    private boolean shouldLogFailure() {
        long now = System.nanoTime();
        long last = lastFailLogNanos.get();
        return now - last > 60_000_000_000L && lastFailLogNanos.compareAndSet(last, now);
    }

    /**
     * The oldest epoch any in-flight search started at, or {@link Long#MAX_VALUE} when all workers are
     * idle — the reclamation bound {@link NavReclaim#tick} frees retired sections against. Static so the
     * tick hook needs no null-dance: no executor = no concurrent readers = everything reclaimable.
     */
    public static long minActiveStamp() {
        PlanExecutor e = instance;
        if (e == null) return Long.MAX_VALUE;
        long min = Long.MAX_VALUE;
        for (int i = 0, n = e.activeSince.length(); i < n; i++) {
            long s = e.activeSince.get(i);
            if (s < min) min = s;
        }
        return min;
    }

    /**
     * Wait (tick thread, cold — {@code /bot config reload}) until every queued job is taken and every
     * worker is idle, or {@code timeoutMs} elapses. Callers drain before mutating the cold shared tables
     * ({@code NavBlock.applyProtected}, {@code MiningModel.buildTable}) — the §4.4 alternative to making
     * those reads volatile. Returns whether the pool actually drained.
     */
    public static boolean drainIdle(long timeoutMs) {
        PlanExecutor e = instance;
        if (e == null) return true;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        for (;;) {
            // finished == submitted ⇒ every job ever enqueued has fully completed. Reading finished FIRST
            // makes the equality conservative, and the caller (tick thread) is the only submitter, so no
            // new job can appear between the reads. This closes the take()-to-stamp TOCTOU a queue-empty +
            // slots-idle check cannot see.
            long fin = e.finished.get();
            if (fin == e.submitted.get()) return true;
            if (System.nanoTime() > deadline) return false;
            try {
                Thread.sleep(1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void workerLoop(int idx) {
        // Boot warm (§4.6, amended): size this thread's ThreadLocal search scratch now, off any tick.
        BlockPathfinder.warmThreadScratch();
        for (;;) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException ie) {
                return; // daemon shutdown
            }
            if (job.handle.cancelled) {
                job.handle.completeRejected(); // never read — the canceller stopped holding the handle
                finished.incrementAndGet();
                continue;
            }
            // Stamp BEFORE touching the request/world (ordering is load-bearing — see class doc).
            activeSince.set(idx, NavReclaim.epoch());
            try {
                SearchRequest req = job.request;
                NavGridView grid = NavGridView.background(req.level());
                BlockPathPlan plan = BlockPathfinder.findPath(grid, req.startFloor(), req.target(),
                        req.caps(), null, req.cuboidCap(), req.inventory(), req.startMode(),
                        req.baseline(), req.budgetNanos(), req.field(),
                        req.goalTolXZ(), req.goalTolY());
                job.handle.complete(plan, plan != null && BlockPathfinder.lastWasPartial(),
                        BlockPathfinder.lastExpansions());
            } catch (Throwable t) {
                job.handle.completeRejected(); // retried at the next boundary; never blacklists a hop
                if (shouldLogFailure()) {
                    OrebitCommon.LOGGER.error("[Orebit] planner search threw (will retry)", t);
                }
            } finally {
                activeSince.set(idx, IDLE);
                finished.incrementAndGet(); // strictly after the handle completed + the slot reads IDLE
            }
        }
    }
}
