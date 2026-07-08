package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.orebit.mod.Debug;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.GoalForcedCost;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;
import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;

/**
 * Block-level A* over the nav grid (PRD §7.1, block tier). Given a start and goal <b>floor cell</b>
 * (the block a bot stands on), it searches for a walkable path and returns a {@link BlockPathPlan} of
 * stand positions — each tagged with the {@link Movement} that produced it — or {@code null} if none is
 * found within the loaded nav grid / expansion budget.
 *
 * <h2>Movement-driven expansion</h2>
 * A node is expanded by iterating {@link MovementRegistry#TIER1}: each {@link Movement} reads the live
 * geometry of the cells it touches (via {@link MovementContext}) and emits its own valid destination
 * cells with a tick cost. The coarse 2-bit grid is used only as the cheap "is this cell built/loaded"
 * gate ({@link MovementContext#built}); the precise per-move checks (head clearance for a jump, the
 * drop column for a fall, the step-assist threshold for a slab) read the descriptor. So the grid finds
 * candidates and live geometry decides moves — which is what catches the classifier's approximations
 * (the "head-in-block" class) precisely at the move level. Adding a capability is adding a movement to
 * the registry; this search loop doesn't change.
 *
 * <h2>Cells, not feet</h2>
 * The search space is floor cells, matching the nav grid's convention (you stand <i>on</i> a floor
 * cell). The returned waypoints are stand positions — {@code floorCell.above()} — so a follower can
 * steer the bot's feet straight to them and ask the step's {@link Movement} how to execute it.
 *
 * <p>Stateless and allocation-bounded per call; safe to run on the server tick thread for the short
 * ranges this consumer uses.
 */
public final class BlockPathfinder {

    private BlockPathfinder() {}

    /**
     * When true, every search logs how long it took, how many nodes it expanded, and the derived ns/node
     * — the empirical data for sizing {@link #MAX_EXPANSIONS}. One INFO line per pathfind, so flip off once
     * you've collected data. Caveats when reading it: the first few are JIT-cold (ignore), break/place
     * paths cost more per node than plain walks (the PathEdits rebuild + edit checks), and a GC pause can
     * spike a single sample — look at the steady-state distribution, not one line.
     */
    public static boolean LOG_TIMING = true;

    /**
     * Node count of the most-recently-finished {@link #findPath} <b>on this thread</b> — an instrumentation
     * seam so a caller (the HPA* {@code LeafCostComputer}) can attribute per-search expansions without
     * parsing the timing log. ThreadLocal, NOT a plain static (DESIGN-background-pathfinding.md §10.6): with
     * planner-pool threads searching concurrently, a shared static could be overwritten between a caller's
     * {@code findPath} return and its read — every consumer reads its own thread's just-finished search, so
     * per-thread storage is exactly the contract. Holder array so per-search sets never box. Read via
     * {@link #lastExpansions()}.
     */
    private static final ThreadLocal<int[]> LAST_EXPANSIONS_TL = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Whether this thread's most-recently-finished {@link #findPath} returned a <b>PARTIAL</b> (best-effort,
     * budget-exhausted) path rather than a full route — same per-thread seam as {@link #lastExpansions()},
     * read by the driver to surface "taking a partial path" progress. {@code false} for a full FOUND path or
     * a null FAIL. Read via {@link #lastWasPartial()}.
     */
    private static final ThreadLocal<boolean[]> LAST_PARTIAL_TL = ThreadLocal.withInitial(() -> new boolean[1]);

    /** Node count of this thread's most-recently-finished {@link #findPath}. */
    public static int lastExpansions() {
        return LAST_EXPANSIONS_TL.get()[0];
    }

    /** Whether this thread's most-recently-finished {@link #findPath} returned a best-effort PARTIAL. */
    public static boolean lastWasPartial() {
        return LAST_PARTIAL_TL.get()[0];
    }

    /**
     * Pre-touch this thread's search scratch ({@code Nodes}/{@code EditPool} ThreadLocals) — the planner
     * pool's boot warm task (DESIGN-background-pathfinding.md §4.6, amended): JIT warmth is JVM-global (the
     * tick-thread {@code NavWarmup} provides it), but the scratch arrays are per-thread, so each pool thread
     * pays its initial sizing here at server start instead of on its first real search. Cold, boot-only.
     */
    public static void warmThreadScratch() {
        SEARCH.get();
        EDIT_POOL.get();
        LAST_EXPANSIONS_TL.get();
        LAST_PARTIAL_TL.get();
    }

    /**
     * Step-by-step search trace sink — when non-null AND {@link #TRACE} is on, every node expansion and every
     * candidate (with its accept/reject reason) is written here, one line each, for OFFLINE analysis of why a
     * search explores what it does (the open-air-pillar investigation). Format (space-separated, greppable):
     * <pre>
     *   E &lt;seq&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; g=&lt;g&gt; f=&lt;f&gt; via=&lt;Movement|start&gt;
     *     C &lt;Movement&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; cost=&lt;c&gt; &lt;OK|worse|corridor&gt;
     * </pre>
     * An {@code E} line is one pop (in expansion order); the indented {@code C} lines under it are that node's
     * emitted candidates — {@code OK} = relaxed onto the open set, {@code worse} = not an improvement, {@code
     * corridor} = rejected by the {@link RegionBound}. Writing per node is slow (file I/O on the tick thread),
     * so this is a deliberate one-shot debug path driven by a command, never on in normal play.
     */
    public static java.io.Writer TRACE_OUT;

    /** Gate for {@link #TRACE_OUT}: emit the step-by-step trace. Off in normal play (the trace is huge + slow). */
    public static boolean TRACE = false;

    /**
     * Optional per-search rollover hook for a LONG-LIVED trace sink (the headless autotest, which leaves
     * {@link #TRACE} on for a whole run of many searches): when non-null AND {@link #TRACE} is on, invoked
     * once at {@code findPath} entry (after the start-ground check, before the first {@code E} line) with
     * the search's start and goal floors, so the sink can rotate {@link #TRACE_OUT} to a fresh per-search
     * file and write its own header. Null in normal play AND for {@code /bot trace} (which opens and owns
     * its single file itself) — both stay byte-identical. Cold: consulted once per SEARCH (behind the
     * {@code TRACE} gate), never per node. Single-sink like {@code TRACE_OUT} itself, so a long-lived
     * consumer needs sync pathing ({@code pathing.async=false}) — concurrent planner-thread searches
     * would interleave through one rotating writer.
     */
    public static java.util.function.BiConsumer<BlockPos, BlockPos> TRACE_SEARCH_START;

    /**
     * The node ceiling of a TIME-capped search ({@code budgetNanos != 0}) — a pure MEMORY backstop
     * (DESIGN-background-pathfinding.md §6): time is the binding limit by design, so the configured
     * sync {@code pathing.syncSearchBudgetNodes} (default 10k, which a 250 ms budget would never outlast) is
     * deliberately NOT consulted in time mode. 256k rows bounds the per-thread Nodes SoA at ~10 MB — the
     * favour-cpu-over-ram ceiling the design priced per planner thread.
     */
    static final int TIME_MODE_NODE_BACKSTOP = 262_144;

    private static void trace(String line) {
        java.io.Writer w = TRACE_OUT;
        if (w == null) return;
        try { w.write(line); w.write('\n'); } catch (java.io.IOException ignored) { }
    }

    // The node-expansion ceiling and heuristic weight are NO LONGER compile-time constants here: they are
    // owner config, carried on BotCaps ({@link BotCaps#maxNodes} / {@link BotCaps#greedyWeight}) and read
    // ONCE into a search-start local (maxNodes below) / onto the Relaxer (its hWeight field), so the hot
    // loop still reads a local, never a field — no per-node cost for making them configurable. Their
    // historical values (10000 / 2.0) live as BotCaps.DEFAULT_MAX_NODES / DEFAULT_GREEDY_WEIGHT, which the
    // DEFAULT/BREAK_PLACE test configs and the config defaults both carry, so behaviour is unchanged until
    // the owner edits orebit.properties.

    /**
     * Master switch for partial-path return — <b>ON.</b> A budget-exhausted search that made real progress
     * toward the goal returns the path to its closest-approach node (not {@code null}), so the bot walks as
     * far as it got and replans from there, converging on a goal a single bounded search can't reach in one
     * shot. A search that EXHAUSTED the heap (genuinely walled in) still returns {@code null} — the closest
     * cell is all it can reach, so committing to it would just re-fail, and that keeps a real connectivity
     * FAIL visible.
     *
     * <p><b>Why on now (the design call):</b> the heuristic (the goal-forced-cost premium) is the strong lever
     * for the open-air pillar, but ANY admissible heuristic has terrain blind spots — a single floating block
     * inside the goal cuboid's slab caps the forced-cost premium, re-opening the horizontal cone (diagnosed +
     * reproduced: {@code docs/Optimizations/cuboid_macro_movements.md}, the offset-pillar case). Such blind
     * spots are a generic, infinite class (overhangs, canopies, floating islands), not an exotic corner, so
     * patching the heuristic per-terrain is whack-a-mole. Partial-path is the structural net that makes the
     * search robust to all of them at once (the Baritone model: time-/budget-boxed best-so-far + replan). It
     * no longer "papers over" a bug we are mid-diagnosis on — that diagnosis is complete; this is the chosen
     * fallback. A stronger forced-cost premium and dominance/symmetry pruning remain future work that make the
     * floods rarer and the partial paths better, but they COMPOSE with this net rather than replace it.
     */
    public static boolean PARTIAL_PATH = true;

    /**
     * Irreversibility guard for PARTIAL paths — <b>ON.</b> A budget-exhausted partial walks the bot to a
     * closest-approach cell <i>off</i> the region skeleton (the search never reached the window target, so the
     * region tier never vouched for that cell). If getting there crosses an <b>irreversible</b> move — a drop
     * deeper than the bot can climb back without placing ({@code !canPlace} and the rise back exceeds the jump
     * budget, measured between the two floors' REAL tops in sixteenths — a drop onto a slab is half a block
     * deeper than its block-level delta; see {@code lastReversibleRow}) — committing it can ratchet the bot into a one-way trap
     * it then can't leave (the deep cave-descent stranding: ~48 blocks of one-way falls toward a portal that
     * turned out block-unreachable). So a partial is truncated to the <b>last cell before its first irreversible
     * move</b>; if that makes no real progress the partial is suppressed and the bot stays put — never the
     * Baritone "drop into a 1×2 hole and give up." A FULL path to the window target is exempt (reaching the
     * target lands the bot ON the skeleton, which vouches downstream), and a {@code canPlace} bot is exempt
     * entirely (it can always pillar back out, so nothing is irreversible for it). Composes with {@link
     * #PARTIAL_PATH}: it only ever <i>shortens</i> a partial, never creates one.
     */
    public static boolean IRREVERSIBLE_GUARD = true;

    /**
     * Master switch for macro-movement collapse (MACRO-IMPLEMENTATION.md §8.3) — <b>ON by default</b>. When
     * on (and the search supplied a corridor {@link RegionBound}, so {@link MovementContext#cuboids()} is
     * non-null), the macro-aware movements ({@link com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar},
     * {@code MineDown}, {@code Traverse}) collapse a long uniform run into ONE jump candidate instead of one
     * per block — the cone-collapse that makes the open-air pillar reachable. When OFF (or the search is
     * unbounded, e.g. {@code /bot trace}), every movement emits its plain single micro step, reproducing the
     * pre-macro search exactly. A clean, revertible layer over the verified micro search — flip off to A/B or
     * to isolate a regression.
     */
    public static boolean MACRO_MOVES = true;

    // NOTE (E1/E2, 2026-07): a per-pop edit-bbox relevance gate was implemented, measured, and DELETED
    // per protocol (design doc deleted post-refutation; rationale in PERF-RESULTS-2026-07-03.md §E1/E2):
    // a counter probe put the envelope-disjoint pop
    // fraction at p = 0.000 in every scenario — the pillar-flood pop stands ON its own placed block, so
    // "edits trail behind the path" is false for every shape the search produces and both gate variants
    // measured FLAT. Any future gate needs per-row/incremental chain bboxes or a recent-edits-only
    // overlay, not a whole-chain bbox. See PERF-RESULTS-2026-07-03.md §E1/E2.

    /**
     * Minimum heuristic improvement (how much closer to the goal the closest-approach node is than the start)
     * for a <b>budget-exhausted</b> search to return a PARTIAL path (only when {@link #PARTIAL_PATH}). Below
     * this the progress is noise and committing to it would just churn the replanner. In TICK units (PRD §10
     * Phase 1d) the heuristic is now ~{@link Traverse#FLAT_COST} per block of straight-line progress, so this
     * threshold is ≈ one walk block — the old "2 units" was ~2 blocks of the dimensionless heuristic, so it is
     * rescaled to ~one block of the tick heuristic to keep the same "real progress" intent.
     */
    private static final float PARTIAL_MIN_PROGRESS = Traverse.FLAT_COST;

    // Heuristic = 3D octile distance to the goal, times an inflation WEIGHT. Octile is the true shortest
    // path on a grid that can move straight, face-diagonal (√2, two axes at once) or corner-diagonal (√3,
    // three axes at once): spend the shortest axis on corner-diagonals, the next on face-diagonals, the rest
    // straight. All THREE axes are weighted EQUALLY — verticality carries no special penalty: an
    // Ascend/Descend is a step that happens to also change height (base cost = a walk step, same as a
    // Traverse), and climbing a pre-existing staircase is no dearer than walking. The real cost of going
    // up/down in open air is the block place/break, which lives on the MOVE (counted in g) and is therefore
    // already paid for and naturally avoided — it does not belong in the heuristic. The WEIGHT (>1) makes the
    // search greedy toward the goal: it stops fanning across the equal-cost plateau and beelines, trading
    // guaranteed-optimal paths (fine for a follow-bot) for far fewer expanded nodes.
    //
    // UNITS (PRD §10 Phase 1d): g is now in REAL TICKS (a walk block ≈ Traverse.FLAT_COST ≈ 4.633 ticks), so
    // the octile MUST be in ticks too, or h would be a few percent of g and the search would collapse toward
    // Dijkstra (h negligible → enormous node counts). The per-axis-block weights are therefore the WALK tick
    // cost (FLAT_COST) and its √2 / √3 multiples — i.e. octile estimates "ticks to walk the straight-line grid
    // distance," the same unit as g. Deriving them from FLAT_COST keeps them auto-consistent if the walk ruler
    // is retuned, and keeps the greedyWeight (config, default 2.0) meaning exactly what it did before the
    // rescale (it multiplies a tick-unit octile, so the same weight gives the same greediness). CAVEAT
    // unchanged: a goal reachable ONLY by building straight up is under-estimated here (h sees distance, not
    // the forced placements counted in g) — that residual is what GoalForcedCost.premium corrects.
    private static final float H_STRAIGHT = Traverse.FLAT_COST;               // one axis  (walk ticks/block)
    private static final float H_FACE     = Traverse.FLAT_COST * 1.41421356f; // two axes at once (√2)
    private static final float H_CORNER   = Traverse.FLAT_COST * 1.7320508f;  // three axes at once (√3)
    // The greediness weight is now config (BotCaps.greedyWeight); the Relaxer reads it once into its
    // hWeight field at search start, so octile() multiplies by a per-search field, not a global constant.
    // Straight-line tie-break weight. On open ground an off-axis goal has a huge equal-cost VOLUME of
    // paths (the correct count of diagonals can be placed anywhere) — a plateau the WEIGHT can't break,
    // because all those paths share g and h. Adding a microscopic term proportional to the node's
    // perpendicular distance from the start→goal line (full 3D — see Relaxer.h) picks the on-line
    // arrangement, collapsing that volume to a ~1-wide corridor (O(D) expanded nodes instead of O(D³)).
    // 3D, not just horizontal, so it also breaks the pillar-then-ascend vs ascend-then-pillar symmetry —
    // a node that climbs early is off the line and loses the tie to one that climbs in step. Kept tiny so
    // it only ORDERS otherwise-equal-f nodes and never overrides a real cost difference. Tunable.
    // UNITS: the tie term is H_TIE × (perpendicular distance in BLOCKS, ~0..D) — block units, not ticks. At
    // 0.001 × blocks it stays a sub-tick nudge, far below the ~4.6-tick cost of any real move difference, so
    // the tick rescale of the octile (above) leaves it correctly negligible — it still only orders ties.
    private static final float H_TIE      = 0.001f;

    /**
     * One reusable {@link Nodes} search-state per thread, {@link Nodes#reset() reset} at the top of each
     * {@link #findPath} instead of allocated fresh. A short follow search is dominated by the dozen-plus
     * arrays a fresh {@code Nodes} allocates (and re-grows) amortized over only a few hundred nodes;
     * reusing one drives the steady state to zero per-search allocation. {@link ThreadLocal}, not a
     * static singleton, so a future off-tick background pathfinding thread gets its own with no contention.
     */
    // NOTE (E5, 2026-07): the PERF-DESIGN-warmup-searches.md §5 eager-size one-liner (512,1024 → 8192,8192)
    // was implemented, measured, and REVERTED per protocol: Nodes.reset() clears the map by Arrays.fill
    // over its CAPACITY, so an eager 8192-slot map costs every flood-free search ~+28 KB of fill —
    // a confirmed +4-7% on the pinned SHORT guard (13.08/13.48 vs 12.66/12.54 us, interleaved fresh-JVM
    // pairs). The boot-time NavWarmup flood grows this scratch to high-water on the tick thread anyway.
    private static final ThreadLocal<Nodes> SEARCH = ThreadLocal.withInitial(() -> new Nodes(512, 1024));

    /**
     * Per-search arena of reusable {@link StepEdits} for the (many) accepted edit-bearing edges — the
     * allocation-free replacement for {@code new StepEdits(...)} per edge, which the allocation profiler
     * pinned as ~97% of a build-heavy search's remaining garbage. {@link #take} bump-allocates the next
     * slot (creating a {@code StepEdits} only the first time a slot is reached on this thread, ever);
     * {@link #reset} rewinds to 0 at the start of each search so the slots are reused. {@link ThreadLocal}
     * like {@link #SEARCH}, so a future off-tick search gets its own with no contention.
     *
     * <p>A re-relaxation of a row takes a fresh slot and abandons the row's previous one (left for the next
     * {@code reset} to reclaim), so the high-water mark is total accepted relaxations, not distinct rows —
     * a one-time RAM cost we accept for the simpler take-only path (favour CPU over RAM). The edits on the
     * returned path are {@link StepEdits#copy copied out} of the arena in {@link #reconstruct}, since they
     * outlive the search.
     */
    private static final class EditPool {
        private StepEdits[] slots = new StepEdits[256];
        private int next;

        /** Rewind to empty for a fresh search; the slot instances (and their grown buffers) are kept. */
        void reset() {
            next = 0;
        }

        /** The next reusable edit set, allocating a slot instance only the first time it's reached. */
        StepEdits take() {
            if (next == slots.length) slots = Arrays.copyOf(slots, slots.length << 1);
            StepEdits e = slots[next];
            if (e == null) { e = new StepEdits(); slots[next] = e; }
            next++;
            return e;
        }
    }

    private static final ThreadLocal<EditPool> EDIT_POOL = ThreadLocal.withInitial(EditPool::new);

    /**
     * The entire mutable search state as primitive arrays — the allocation-free replacement for the old
     * {@code new Node(...)}-per-candidate + four {@code HashMap<Long,…>} design (whose every {@code get}/
     * {@code put} boxed the packed-pos {@code long} key past the {@code Long} cache, ~100× per node).
     *
     * <p>Three structures, indices stable across the search so {@code parent} can reference them:
     * <ul>
     *   <li><b>Node table</b> — append-only parallel arrays ({@code key/x/y/z/g/f/parent/move/edits}),
     *       one row per discovered cell. {@code move} is the index into {@link MovementRegistry#TIER1};
     *       {@code parent} is the predecessor row ({@code -1} at the start). Doubles on fill.
     *   <li><b>Index</b> — open-addressing {@code long→row} map (linear probe, power-of-two). Empty slot is
     *       marked by {@code idx == -1}, so no key sentinel is needed; the {@code long} key is never boxed.
     *   <li><b>Open set</b> — a binary min-heap of row indices keyed by the {@code f} snapshot at push time
     *       ({@code heapF}). Lazy decrease-key: a relaxed-lower node is pushed again, and a pop whose
     *       snapshot exceeds the row's current {@code f} is stale and skipped (the array-form of the old
     *       {@code current.g > gScore.get(key)} check; equivalent because {@code f = g + h} with {@code h}
     *       fixed per cell). All per-call sizing; reused across nodes, not reallocated per node.
     * </ul>
     */
    private static final class Nodes {
        // ---- Node table (append-only; row index is stable, parent[] points at it) ----
        long[] key;
        int[] x, y, z;
        float[] g, f;
        int[] parent;          // predecessor row, -1 at the start
        int[] move;            // MovementRegistry.TIER1 index, -1 at the start
        int[] mode;            // movement mode at this node (MovementContext.MODE_STANDING / MODE_PRONE)
        StepEdits[] edits;     // edit-set on the edge into this row (null when the move breaks/places nothing)
        int count;

        // ---- key→row index (open addressing, linear probe) ----
        long[] mapKey;
        int[] mapRow;          // -1 marks an empty slot
        int mapMask;
        int mapSize;
        int mapGrowAt;

        // ---- open set (binary min-heap, ordered by f then by larger g) ----
        // Ties in f are broken toward the LARGER g (the node nearer the goal, i.e. smaller h). On the
        // big equal-f plateaus a follow-bot hits on open ground, this dives at the goal instead of
        // fanning out, popping far fewer nodes — and it's optimality-neutral (it only orders ties).
        int[] heap;
        float[] heapF;
        float[] heapG;         // g snapshot at push, parallel to heapF — the tie-breaker
        int heapSize;
        float poppedF;         // f snapshot of the last pop() — the caller's staleness test

        Nodes(int nodeHint, int mapCap) {
            key = new long[nodeHint];
            x = new int[nodeHint];
            y = new int[nodeHint];
            z = new int[nodeHint];
            g = new float[nodeHint];
            f = new float[nodeHint];
            parent = new int[nodeHint];
            move = new int[nodeHint];
            mode = new int[nodeHint];
            edits = new StepEdits[nodeHint];
            mapKey = new long[mapCap];
            mapRow = new int[mapCap];
            Arrays.fill(mapRow, -1);
            mapMask = mapCap - 1;
            mapGrowAt = mapCap * 3 / 4;
            heap = new int[nodeHint];
            heapF = new float[nodeHint];
            heapG = new float[nodeHint];
        }

        /**
         * Clear for the next pathfind, keeping every array at its grown high-water-mark capacity so a
         * reused instance stops allocating after warmup. Only the index needs wiping (its {@code -1}
         * empty marker); the node/heap arrays are written by index from 0 as the search fills them, and
         * stale slots past {@code count}/{@code heapSize}, or where {@code mapRow==-1}, are never read.
         */
        void reset() {
            count = 0;
            heapSize = 0;
            mapSize = 0;
            Arrays.fill(mapRow, -1);
        }

        /** Row for {@code key} (which encodes {@code cmode}), creating it (with {@code g=+inf}, unlinked) if
         *  absent. The key carries the mode, so the same cell in two modes hashes to two distinct rows. */
        int intern(long k, int cx, int cy, int cz, int cmode) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) {
                    row = newRow(k, cx, cy, cz, cmode);
                    mapKey[slot] = k;
                    mapRow[slot] = row;
                    if (++mapSize >= mapGrowAt) growMap();
                    return row;
                }
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        private int newRow(long k, int cx, int cy, int cz, int cmode) {
            int n = count;
            if (n == key.length) growNodes();
            key[n] = k;
            x[n] = cx; y[n] = cy; z[n] = cz;
            mode[n] = cmode;
            g[n] = Float.POSITIVE_INFINITY;
            f[n] = Float.POSITIVE_INFINITY;
            parent[n] = -1;
            move[n] = -1;
            edits[n] = null;
            count = n + 1;
            return n;
        }

        /** Push row {@code n} onto the open set with its current {@code f}/{@code g} snapshots. */
        void push(int n) {
            if (heapSize == heap.length) {
                heap = Arrays.copyOf(heap, heapSize << 1);
                heapF = Arrays.copyOf(heapF, heapSize << 1);
                heapG = Arrays.copyOf(heapG, heapSize << 1);
            }
            int i = heapSize++;
            float fv = f[n], gv = g[n];
            while (i > 0) {
                int p = (i - 1) >> 1;
                // Stop once the parent should stay above the new node: smaller f, or equal f and
                // g no smaller (so equal-f ties bubble the larger-g node up).
                if (heapF[p] < fv || (heapF[p] == fv && heapG[p] >= gv)) break;
                heap[i] = heap[p]; heapF[i] = heapF[p]; heapG[i] = heapG[p];
                i = p;
            }
            heap[i] = n; heapF[i] = fv; heapG[i] = gv;
        }

        /** Remove and return the best row (min f, ties to larger g); sets {@link #poppedF} to its f snapshot. */
        int pop() {
            int top = heap[0];
            poppedF = heapF[0];
            int last = --heapSize;
            if (last > 0) {
                int n = heap[last];
                float fv = heapF[last], gv = heapG[last];
                int i = 0;
                for (;;) {
                    int l = (i << 1) + 1;
                    if (l >= last) break;
                    int r = l + 1;
                    // Pick the better child: smaller f, or equal f and larger g.
                    int c = (r < last && (heapF[r] < heapF[l]
                            || (heapF[r] == heapF[l] && heapG[r] > heapG[l]))) ? r : l;
                    // Stop once the sifted node is at least as good as that child.
                    if (fv < heapF[c] || (fv == heapF[c] && gv >= heapG[c])) break;
                    heap[i] = heap[c]; heapF[i] = heapF[c]; heapG[i] = heapG[c];
                    i = c;
                }
                heap[i] = n; heapF[i] = fv; heapG[i] = gv;
            }
            return top;
        }

        private void growNodes() {
            int cap = key.length << 1;
            key = Arrays.copyOf(key, cap);
            x = Arrays.copyOf(x, cap);
            y = Arrays.copyOf(y, cap);
            z = Arrays.copyOf(z, cap);
            g = Arrays.copyOf(g, cap);
            f = Arrays.copyOf(f, cap);
            parent = Arrays.copyOf(parent, cap);
            move = Arrays.copyOf(move, cap);
            mode = Arrays.copyOf(mode, cap);
            edits = Arrays.copyOf(edits, cap);
        }

        private void growMap() {
            long[] oldKey = mapKey;
            int[] oldRow = mapRow;
            int cap = oldKey.length << 1;
            mapKey = new long[cap];
            mapRow = new int[cap];
            Arrays.fill(mapRow, -1);
            mapMask = cap - 1;
            mapGrowAt = cap * 3 / 4;
            for (int i = 0; i < oldRow.length; i++) {
                int row = oldRow[i];
                if (row == -1) continue;
                long k = oldKey[i];
                int slot = slotFor(k, mapMask);
                while (mapRow[slot] != -1) slot = (slot + 1) & mapMask;
                mapKey[slot] = k;
                mapRow[slot] = row;
            }
        }

        /** Murmur3 64-bit finalizer → slot; spreads BlockPos-packed longs (low bits = y, then z). */
        private static int slotFor(long k, int mask) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            k *= 0xc4ceb9fe1a85ec53L;
            k ^= k >>> 33;
            return (int) k & mask;
        }
    }

    /**
     * Search a walkable path from {@code startFloor} to {@code goalFloor} (both floor cells) for the
     * default {@link BotCaps}. See {@link #findPath(NavGridView, BlockPos, BlockPos, BotCaps)}.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor) {
        return findPath(grid, startFloor, goalFloor, BotCaps.DEFAULT, null);
    }

    /**
     * Search for a walkable path from {@code startFloor} to {@code goalFloor} (both floor cells) given the
     * bot's {@code caps}, unbounded over the loaded grid. See
     * {@link #findPath(NavGridView, BlockPos, BlockPos, BotCaps, RegionBound)}.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps) {
        return findPath(grid, startFloor, goalFloor, caps, null);
    }

    /**
     * Search for a walkable path from {@code startFloor} to {@code goalFloor} (both floor cells) given
     * the bot's {@code caps}. Returns {@code null} if the bot isn't standing on built ground, or no path
     * is found within the loaded grid / expansion budget. The goal is reached when within 1 block
     * horizontally and 2 vertically of {@code goalFloor} (so the owner needn't be standing on a perfectly
     * walkable cell for the bot to arrive next to them).
     *
     * <p>When {@code bound} is non-null the search is confined to that corridor (HPA-IMPLEMENTATION.md §9):
     * a candidate cell outside the box is never relaxed, so the region tier's sliding window can keep the
     * block-A* from flooding off the skeleton (the pillar fix — see {@link RegionBound}). The {@code bound}
     * does NOT gate the start cell (interned directly) and the caller must keep the goal/target inside the
     * box, or the search can never reach it. {@code null} bound = the historical unbounded behaviour.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound bound) {
        return findPath(grid, startFloor, goalFloor, caps, bound, null);
    }

    /**
     * As {@link #findPath(NavGridView, BlockPos, BlockPos, BotCaps, RegionBound)}, additionally threading the
     * live bot's per-pathfind inventory feasibility snapshot {@code inventory} (PRD §10 Phase 1b/1c) into the
     * {@link MovementContext} — so the break/place gates also account for the bot's REAL carried tools +
     * blocks (see {@link MovementContext#setInventory}). {@code null} (every existing caller, the headless
     * benchmarks, {@code /bot trace}) leaves the gates in their historical caps-only mode, so behaviour is
     * unchanged until a bot supplies a snapshot. The snapshot is read ONCE here into the context before the
     * loop; the hot path then reads its primitives, never the live inventory (HOT-PATH-NO-ALLOC).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound bound,
                                         MovementContext.InventoryView inventory) {
        // Confinement and the cuboid growth-cap default to the SAME bound (the historical corridor double-duty).
        return findPath(grid, startFloor, goalFloor, caps, bound, bound, inventory);
    }

    /**
     * As above, but with the search <b>confinement</b> ({@code confineBound}) decoupled from the cuboid
     * <b>growth cap</b> ({@code cuboidBound}). {@code confineBound == null} runs an UNCONFINED full-grid search
     * — the region tier's sliding-window targets are near (~3 regions), so distance + the node budget bound it
     * without a corridor, and removing the corridor lets the block-A* take the REAL route to the target even
     * when it must wander a few regions off the coarse skeleton (the skeleton is a hint, not a cage). A non-null
     * {@code cuboidBound} still caps macro-cuboid growth so a flat world can't grow one unbounded. Passing the
     * two equal reproduces the old corridor behaviour; both {@code null} = unbounded + no macros (bench/trace).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory) {
        return findPath(grid, startFloor, goalFloor, caps, confineBound, cuboidBound, inventory, MODE_AUTO);
    }

    /** {@link #findPath} start-mode override sentinel: derive the start mode from the start cell's geometry
     *  (2-deep water ⇒ {@code MODE_PRONE}, else {@code MODE_STANDING}). Pass an explicit
     *  {@link MovementContext#MODE_STANDING}/{@link MovementContext#MODE_PRONE} to override — the live driver
     *  passes the bot's ACTUAL pose ({@code isSwimming()} ⇒ PRONE) so a replan mid-sprint-swim doesn't lose the
     *  prone state and try to re-initiate (which a bob to 1-deep would otherwise trigger, or worse, get stuck). */
    public static final int MODE_AUTO = -1;

    /**
     * As above, but with an explicit {@code startModeOverride}: when {@code >= 0} it is used as the start
     * node's mode instead of the geometry-derived one ({@link #MODE_AUTO} = derive). The live two-tier driver
     * threads the bot's real pose here; the headless/region-cost/trace callers pass {@code MODE_AUTO}.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory, int startModeOverride) {
        return findPath(grid, startFloor, goalFloor, caps, confineBound, cuboidBound, inventory,
                startModeOverride, null);
    }

    /**
     * As above, additionally seeding the search with a splice {@code baseline} ({@link EditSnapshot}) —
     * the not-yet-applied edits of an EARLIER plan this search's result will be spliced after
     * (DESIGN-background-pathfinding.md §7 / DESIGN-portal-route-layer.md §4.3). The baseline is folded
     * into the per-pop {@link PathEdits} rebuild AFTER the {@code cameFrom}-chain walk, so the in-search
     * path's own edits shadow it (latest-wins) and every movement — and the cuboid extractor, which reads
     * the same {@link PathEdits} — prices the world as it will be at the splice boundary. {@code null}
     * (every non-spliced caller) is byte-identical to the overload above: one perfectly-predicted compare
     * per pop, nothing on any per-read path. Deliberately NOT visible to the one-shot
     * {@link GoalForcedCost#probe} / cuboid-view construction before the loop (heuristic-premium inputs
     * only — a baseline near the goal skews the premium, never correctness).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory, int startModeOverride,
                                         EditSnapshot baseline) {
        return findPath(grid, startFloor, goalFloor, caps, confineBound, cuboidBound, inventory,
                startModeOverride, baseline, 0L, null);
    }

    /**
     * As above, additionally threading the region-informed cost-to-goal heuristic field ({@code regionField},
     * {@link com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder#costToGoalField} — a topology-aware
     * lower bound the {@code Relaxer} {@code max}es against the octile so the search is pulled along the region
     * skeleton and out of dead-end floods). {@code null} (headless / bench / non-region callers) is byte-identical
     * to the overload above. Built on the tick thread (it reads the region grid / nav sections) and carried
     * read-only onto the async {@link com.orebit.mod.pathfinding.async.SearchRequest}.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory, int startModeOverride,
                                         EditSnapshot baseline,
                                         com.orebit.mod.pathfinding.regionpathfinder.RegionCostField regionField) {
        return findPath(grid, startFloor, goalFloor, caps, confineBound, cuboidBound, inventory,
                startModeOverride, baseline, 0L, regionField);
    }

    /**
     * As above, additionally bounding the search by <b>wall-clock</b> ({@code budgetNanos > 0}) — the
     * time-based cap (DESIGN-background-pathfinding.md §6, the Baritone model). The deadline is checked
     * every 256 pops (one mask+branch per pop, a {@code nanoTime} call 1/256 pops); on expiry the search
     * takes the exact budget-exhausted path the node cap takes today (best-so-far PARTIAL via
     * {@code PARTIAL_PATH} + the irreversibility guard, or null). {@code caps.maxNodes} still applies as
     * the memory backstop, and {@code 0} (every sync/bench/test caller) is byte-identical node-cap-only
     * behaviour — which keeps benchmarks and unit tests timing-independent (a COMPLETED search under
     * deadline is identical to an uncapped one; only where a partial truncates is timing-dependent, and
     * partials are replanned at the next boundary by construction).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory, int startModeOverride,
                                         EditSnapshot baseline, long budgetNanos) {
        return findPath(grid, startFloor, goalFloor, caps, confineBound, cuboidBound, inventory,
                startModeOverride, baseline, budgetNanos, null);
    }

    /**
     * As above, additionally threading the region-informed cost-to-goal heuristic {@code regionField} (see the
     * {@code (…, baseline, regionField)} overload). {@code null} {@code regionField} is byte-identical to the
     * pre-region-heuristic search. Uses the {@link #DEFAULT_GOAL_TOL_XZ default} goal tolerance.
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory, int startModeOverride,
                                         EditSnapshot baseline, long budgetNanos,
                                         com.orebit.mod.pathfinding.regionpathfinder.RegionCostField regionField) {
        return findPath(grid, startFloor, goalFloor, caps, confineBound, cuboidBound, inventory,
                startModeOverride, baseline, budgetNanos, regionField,
                DEFAULT_GOAL_TOL_XZ, DEFAULT_GOAL_TOL_Y);
    }

    /** The historical goal-arrival tolerance: within 1 block horizontally / 2 vertically counts as reached —
     *  right for "get NEAR this cell" goals (window portals, follow, mining reach). Callers whose "done" is
     *  standing ON the exact cell (drop pickup) pass 0/0 instead (s52: the tolerance is the CALLER's
     *  definition of done, not the search's — the reached-vs-done decoupling). */
    public static final int DEFAULT_GOAL_TOL_XZ = 1;
    public static final int DEFAULT_GOAL_TOL_Y = 2;

    /**
     * As above with an explicit goal-arrival tolerance ({@code goalTolXZ}/{@code goalTolY} — the Chebyshev
     * box around {@code goalFloor} whose entry ends the search). This is the deepest overload — every other
     * delegates here. {@code (1, 2)} reproduces the historical behaviour byte-for-byte; {@code (0, 0)} makes
     * the search land ON the exact goal cell (drop collection — standing adjacent leaves the item outside
     * the pickup box, the stare-at-the-drop bug).
     */
    public static BlockPathPlan findPath(NavGridView grid, BlockPos startFloor, BlockPos goalFloor,
                                         BotCaps caps, RegionBound confineBound, RegionBound cuboidBound,
                                         MovementContext.InventoryView inventory, int startModeOverride,
                                         EditSnapshot baseline, long budgetNanos,
                                         com.orebit.mod.pathfinding.regionpathfinder.RegionCostField regionField,
                                         int goalTolXZ, int goalTolY) {
        final long t0 = System.nanoTime();
        LAST_EXPANSIONS_TL.get()[0] = 0; // reset the instrumentation seam (covers the early no-start-ground return)
        LAST_PARTIAL_TL.get()[0] = false;
        final int sx = startFloor.getX(), sy = startFloor.getY(), sz = startFloor.getZ();
        final int gx = goalFloor.getX(), gy = goalFloor.getY(), gz = goalFloor.getZ();

        // Bot must be on built ground for the grid-based search to mean anything.
        if (!grid.built(sx, sy, sz)) {
            if (LOG_TIMING) logTiming(t0, 0, false, "no-start-ground", sx, sy, sz, gx, gy, gz);
            return null;
        }

        // Long-lived trace sinks (the headless autotest) rotate TRACE_OUT here, once per search — see
        // TRACE_SEARCH_START. Null for /bot trace and in normal play; the whole block is TRACE-gated.
        if (TRACE) {
            java.util.function.BiConsumer<BlockPos, BlockPos> roll = TRACE_SEARCH_START;
            if (roll != null) roll.accept(startFloor, goalFloor);
        }

        // Read the configurable search params into search-start locals ONCE — the hot loop's budget test
        // (below) and the heuristic weight (on the Relaxer) then read a local / a final field, not a
        // BotCaps accessor per node. Guarded so a mis-configured caps can never disable the backstop:
        // a non-positive maxNodes falls back to the historical default. In TIME mode (budgetNanos != 0)
        // the wall clock is the binding limit and the node cap becomes the pure MEMORY backstop — the
        // config's maxNodes (default 10k ≈ 4–15 ms warm) would otherwise always bind BEFORE a 40 ms
        // budget, silently reducing the time cap to the node cap (review finding).
        final int maxNodes = budgetNanos != 0L ? TIME_MODE_NODE_BACKSTOP
                : (caps.maxNodes() > 0 ? caps.maxNodes() : BotCaps.DEFAULT_MAX_NODES);

        final MovementContext ctx = new MovementContext(grid, caps);
        ctx.setInventory(inventory); // null in the historical / headless / trace paths (caps-only gates)
        // Start mode: the caller's explicit override wins (the live driver passes the bot's real pose, so a
        // replan mid-sprint-swim stays PRONE instead of re-deriving STANDING from a bob and re-initiating).
        // MODE_AUTO falls back to geometry: PRONE only if the start cell is 2-deep water (feet + head water),
        // where the bot would already be submerged-swimming; otherwise STANDING.
        final int startMode = startModeOverride >= 0 ? startModeOverride
                : ((ctx.built(sx, sy + 1, sz) && ctx.water(sx, sy + 1, sz)
                        && ctx.built(sx, sy + 2, sz) && ctx.water(sx, sy + 2, sz))
                    ? MovementContext.MODE_PRONE : MovementContext.MODE_STANDING);
        final long startKey = key(sx, sy, sz, startMode);

        // Macro-movement collapse (MACRO-IMPLEMENTATION.md §8): builds a per-search cuboid view over the SAME
        // PathEdits the movements read (capped by cuboidBound so a flat world can't grow one unbounded), wires
        // it + the goal onto the context, and probes the goal's faces once for the admissible forced-build
        // heuristic premium (§7). A null cuboidBound (bench / raw trace) gets no view → plain micro steps. NOTE
        // cuboidBound is independent of confineBound: an UNCONFINED search (confineBound == null) still gets
        // capped macros.
        final NavGridCuboidsView cuboids = (MACRO_MOVES && cuboidBound != null)
                ? new NavGridCuboidsView(grid, ctx.pathEdits(), cuboidBound) : null;
        // Primary travel axis P (Option B, CUBOID-PERF-OPTIONS.md): the dominant start→goal approach axis, so
        // only the movements travelling P extract a cuboid per node (the other axes take their micro step) —
        // pinning per-node extraction to one axis instead of up to three. argmax(|dx|,|dy|,|dz|) with tie-break
        // X > Z > Y (the kept axis then also has the best linear-scan locality — X is the contiguous grid axis,
        // Y the worst). Computed once here where start + goal are both in hand.
        final int macroAxis = primaryAxis(sx, sy, sz, gx, gy, gz);
        ctx.setMacro(cuboids, gx, gy, gz, macroAxis);
        final GoalForcedCost.Forced forced = new GoalForcedCost.Forced();
        // Start coords let the probe exclude the goal face on the FAR side of the goal along the dominant
        // start→goal axis (only approachable after passing the goal — a standable far face would zero the
        // premium and re-open the ground flood). The probe re-derives the dominant axis itself (same
        // argmax + X>Z>Y tie-break as primaryAxis above) rather than taking macroAxis, keeping the two
        // consumers' semantics independent.
        GoalForcedCost.probe(cuboids, sx, sy, sz, gx, gy, gz, caps, ctx.pillarPlaceCost(),
                ctx.breakBaseCost(), forced);

        // Reuse this thread's search state (sized to its high-water mark), wiped to empty — so a steady
        // stream of replans allocates nothing here. First call on a thread pays the initial 512/1024 sizing.
        final Nodes nodes = SEARCH.get();
        nodes.reset();
        final EditPool editPool = EDIT_POOL.get();
        editPool.reset();
        final float hWeight = caps.greedyWeight() >= 1.0f ? caps.greedyWeight() : BotCaps.DEFAULT_GREEDY_WEIGHT;
        Relaxer relaxer = new Relaxer(nodes, editPool, sx, sy, sz, gx, gy, gz, confineBound, forced, hWeight,
                regionField);

        int startRow = nodes.intern(startKey, sx, sy, sz, startMode);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = relaxer.h(sx, sy, sz);
        nodes.push(startRow);

        // Root-node diagnostic (LOG_TIMING-gated, so it fires only for the driver's window searches, not the
        // region tier's leaf-cost mini-searches): enumerate the start cell's candidates with each move's g, the
        // heuristic decomposed into octile + forced-build premium, and f = g + h — so WHY a first move is chosen
        // (e.g. a MineDown out-scoring a Pillar, or a 0 premium far below the goal) is legible. Passive sink, so
        // the open set is untouched.
        if (LOG_TIMING) logRoot(ctx, relaxer, forced, sx, sy, sz, startMode, hWeight, gx, gy, gz);

        int expansions = 0;
        int reachedRow = -1;
        // Closest approach (min heuristic among closed nodes) + why the search stopped — the diagnostic
        // for a failed plan: where did it dead-end, and was it walled in or just out of budget?
        float bestH = Float.MAX_VALUE;
        int bestX = sx, bestY = sy, bestZ = sz;
        int bestRow = startRow;     // row of the closest-approach node — the partial-path target on budget exhaustion
        boolean budgetHit = false;
        // End (exclusive) of the START's direct-successor row range, captured after the first expansion —
        // the partial-commit seed set (see the budget-hit block below). -1 = never captured (budget blew
        // before the start even expanded, or start==goal).
        int childrenEnd = -1;

        while (nodes.heapSize > 0) {
            int current = nodes.pop();

            // Stale queue entry (a better f was found and re-pushed after this entry).
            if (nodes.poppedF > nodes.f[current]) continue;

            int cx = nodes.x[current], cy = nodes.y[current], cz = nodes.z[current];
            if (isGoal(cx, cy, cz, gx, gy, gz, goalTolXZ, goalTolY)) {
                reachedRow = current;
                break;
            }

            float h = relaxer.h(cx, cy, cz);
            if (h < bestH) { bestH = h; bestX = cx; bestY = cy; bestZ = cz; bestRow = current; }

            if (TRACE) trace("E " + expansions + " " + cx + " " + cy + " " + cz
                    + " g=" + nodes.g[current] + " f=" + nodes.f[current] + " via="
                    + (nodes.move[current] < 0 ? "start"
                            : MovementRegistry.TIER1.get(nodes.move[current]).getClass().getSimpleName()));

            // Wall-clock deadline (async searches only; budgetNanos == 0 elsewhere): checked every 256 pops,
            // with the loop-invariant budgetNanos test FIRST so the sync path pays one register compare per
            // pop (loop-unswitchable by the JIT) and never evaluates the mask; nanoTime is paid 1/256 pops
            // on timed searches only. Same budget-exhausted semantics as the node cap below (PARTIAL/null).
            if (budgetNanos != 0L && (expansions & 255) == 0 && System.nanoTime() - t0 > budgetNanos) {
                budgetHit = true;
                break;
            }
            if (++expansions > maxNodes) { budgetHit = true; break; }

            // Rebuild the planned-edit diff for the path to THIS node, so the movements below read the
            // world as it will be when the bot stands here (the blocks the preceding moves place/break),
            // not just the live grid. Per-path, not global (see PathEdits); skipped wholesale until the
            // search has produced any edit, so plain follows pay nothing.
            PathEdits pathEdits = ctx.pathEdits();
            pathEdits.reset();
            if (relaxer.anyEdits) {
                for (int n = current; n != -1; n = nodes.parent[n]) {
                    pathEdits.add(nodes.edits[n]);
                }
            }
            // Splice baseline seed (DESIGN-background-pathfinding.md §7): appended AFTER the chain walk so
            // the path's own edits shadow it, and OUTSIDE the anyEdits gate — a seeded search must see the
            // baseline before it has produced any edit of its own. Null (every non-spliced search) = one
            // perfectly-predicted compare per pop.
            if (baseline != null) pathEdits.addSnapshot(baseline);

            relaxer.current = current;
            relaxer.currentG = nodes.g[current];
            int curMode = nodes.mode[current];
            relaxer.currentMode = curMode; // default dest mode for ordinary (mode-preserving) accepts
            ctx.setMode(curMode);          // surfaced to each movement's candidates() for mode-gating
            List<Movement> tier1 = MovementRegistry.TIER1;
            for (int mi = 0, mn = tier1.size(); mi < mn; mi++) {
                relaxer.move = mi;
                tier1.get(mi).candidates(ctx, cx, cy, cz, relaxer);
            }

            // Capture the START's direct-successor row range for the partial-commit seed (below). The first
            // expansion is necessarily startRow (the heap held only it, and a sole entry can't be stale), and
            // the node table is APPEND-ONLY (rows are never recycled/compacted), so every row appended by the
            // expansion just above — (startRow, count) right now — is a direct start successor. Cost model:
            // one register compare per pop, perfectly predicted after the first (same budget as the
            // budgetNanos test above); the capture itself runs once.
            if (expansions == 1) childrenEnd = nodes.count;
        }

        LAST_EXPANSIONS_TL.get()[0] = expansions; // instrumentation seam — the just-finished search's node count

        if (reachedRow == -1) {
            if (Debug.ENABLED) explainFailure(ctx, sx, sy, sz, gx, gy, gz, expansions, budgetHit, bestX, bestY, bestZ, confineBound);
            // Partial-path return: when the BUDGET (not connectivity) stopped the search and it made real
            // progress toward the goal, return the path to the closest-approach node so the bot moves forward
            // and replans from there — converging on a goal a single bounded search can't reach in one shot.
            // A search that EXHAUSTED the heap (walled in) returns null instead: the closest cell is all it can
            // reach, so moving there and replanning would just re-fail (and keeps the FAIL signal visible).
            //
            // Commit-point seeding from the start's direct successors (the 2026-07-06 buried-target incident):
            // bestRow updates only at POP time, so a relaxed-but-never-popped start neighbour — e.g. the one
            // expensive dig toward a buried window target, whose g (35–160 ticks of mining) keeps it behind
            // thousands of cheap walk relaxations for the whole budget — can NEVER become the commit point,
            // and the partial inches along whatever cheap flood the heuristic favoured instead of taking the
            // one real block of progress. Evaluate h once per captured start child and adopt the overall min
            // STRICTLY below the popped bestH (a tie keeps today's popped choice); the winner then flows
            // through the SAME min-progress + irreversibility checks as a popped commit point — no forked
            // logic. O(branching factor) work, once, only on the budget-hit path: the hot loop is untouched
            // and FOUND results stay byte-identical. The parent==-1 skip is defensive (every first visit is
            // relaxed, g starts +inf) — reconstruct() needs a parent chain, so never commit to a row without
            // one. NOTE a seeded child's parent may since have been REWIRED to a cheaper deeper node; that
            // chain is still valid (and cheaper), so reconstruct/lastReversibleRow handle it unchanged.
            boolean seeded = false;
            if (PARTIAL_PATH && budgetHit && childrenEnd > 0) {
                for (int r = startRow + 1; r < childrenEnd; r++) {
                    if (nodes.parent[r] == -1) continue;
                    float rh = relaxer.h(nodes.x[r], nodes.y[r], nodes.z[r]);
                    if (rh < bestH) { bestH = rh; bestRow = r; seeded = true; }
                }
            }
            if (PARTIAL_PATH && budgetHit && bestRow != startRow
                    && (relaxer.h(sx, sy, sz) - bestH) > PARTIAL_MIN_PROGRESS) {
                // Irreversibility guard: don't commit a partial past a move the bot can't undo (§IRREVERSIBLE_GUARD).
                // Truncate to the last cell before the first unclimbable drop, then re-check that the (shorter)
                // commit still makes real progress — if not, suppress the partial entirely (the bot stays put).
                int commitRow = IRREVERSIBLE_GUARD
                        ? lastReversibleRow(ctx, nodes, startRow, bestRow, caps) : bestRow;
                if (commitRow != startRow) {
                    float commitH = relaxer.h(nodes.x[commitRow], nodes.y[commitRow], nodes.z[commitRow]);
                    if ((relaxer.h(sx, sy, sz) - commitH) > PARTIAL_MIN_PROGRESS) {
                        BlockPathPlan partial = reconstruct(nodes, startRow, commitRow);
                        LAST_PARTIAL_TL.get()[0] = true;
                        // Tag: "-seed" = the commit point is a seeded never-popped start child; "-irrev" = the
                        // guard truncated to an ancestor (ancestors were all popped, so the two are exclusive).
                        if (LOG_TIMING) logTiming(t0, expansions, relaxer.anyEdits,
                                "PARTIAL-" + partial.size() + "wp"
                                        + (commitRow != bestRow ? "-irrev" : (seeded ? "-seed" : "")),
                                sx, sy, sz, gx, gy, gz);
                        return partial;
                    }
                }
            }
            if (LOG_TIMING) logTiming(t0, expansions, relaxer.anyEdits,
                    budgetHit ? "FAIL-budget" : "FAIL-exhausted", sx, sy, sz, gx, gy, gz);
            return null;
        }

        BlockPathPlan plan = reconstruct(nodes, startRow, reachedRow);
        if (LOG_TIMING) logTiming(t0, expansions, relaxer.anyEdits,
                "FOUND-" + plan.size() + "wp", sx, sy, sz, gx, gy, gz);
        return plan;
    }

    /**
     * One INFO line of search timing: nodes expanded, wall-clock spent, the derived ns/node, whether the
     * path carried any break/place edits ({@code +edits}, which cost more per node), the result, and the
     * endpoints. {@code System.nanoTime()} captures the whole search incl. the per-call map allocations.
     */
    private static void logTiming(long t0, int expansions, boolean edits, String result,
                                  int sx, int sy, int sz, int gx, int gy, int gz) {
        long ns = System.nanoTime() - t0;
        String perNode = expansions > 0 ? String.format("%.0f", (double) ns / expansions) : "-";
        OrebitCommon.LOGGER.info(
                "[Orebit] path TIMING: {} nodes in {} us ({} ns/node){} -> {}  ({},{},{})->({},{},{})",
                expansions, String.format("%.1f", ns / 1000.0), perNode, edits ? " +edits" : "",
                result, sx, sy, sz, gx, gy, gz);
    }

    /**
     * The {@link CandidateSink} the search hands each movement: it relaxes every emitted destination
     * cell against the open set, tagging the edge with the movement currently expanding so the plan can
     * carry the chosen move per step.
     */
    private static final class Relaxer implements CandidateSink {
        private final Nodes nodes;
        private final EditPool editPool; // per-search arena for accepted edges' StepEdits (no per-edge alloc)
        private final int sx, sy, sz;   // start (for the straight-line tie-break)
        private final int gx, gy, gz;   // goal
        private final float invLineLen; // 1 / |goal-start| in full 3D (0 when start==goal)
        private final RegionBound bound; // corridor confinement (null = unbounded); rejects out-of-box candidates
        private final GoalForcedCost.Forced forced; // once-per-search goal-cuboid premium (extent 0 = no correction)
        private final float hWeight; // greediness (BotCaps.greedyWeight), read once at search start
        // PROTOTYPE region-informed heuristic (null = off): per-region cost-to-goal, added to h as a topology-aware
        // lower bound so the sky (far-from-goal regions) is deprioritised instead of flooded.
        private final com.orebit.mod.pathfinding.regionpathfinder.RegionCostField regionField;

        int current;        // row being expanded
        float currentG;     // its g (read once per expansion, not per candidate)
        int currentMode;    // its mode (read once per expansion) — the default dest mode for a plain accept
        int move;           // MovementRegistry.TIER1 index currently emitting candidates
        boolean anyEdits;   // has any edge carried break/place edits? (gates the per-pop diff rebuild)

        Relaxer(Nodes nodes, EditPool editPool, int sx, int sy, int sz, int gx, int gy, int gz,
                RegionBound bound, GoalForcedCost.Forced forced, float hWeight,
                com.orebit.mod.pathfinding.regionpathfinder.RegionCostField regionField) {
            this.nodes = nodes;
            this.editPool = editPool;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.bound = bound;
            this.forced = forced;
            this.hWeight = hWeight;
            this.regionField = regionField;
            double dlen = Math.sqrt((double) (gx - sx) * (gx - sx)
                    + (double) (gy - sy) * (gy - sy)
                    + (double) (gz - sz) * (gz - sz));
            this.invLineLen = dlen > 1e-6 ? (float) (1.0 / dlen) : 0f;
        }

        /**
         * The search heuristic: weighted 3D-octile distance to the goal (see {@link #octile}) plus the tiny
         * straight-line deviation tie-break. The deviation is the <b>full 3D</b> perpendicular distance from
         * the start→goal line — {@code |(node−start) × (goal−start)| / |goal−start|}, the magnitude of the
         * 3-component cross product — scaled by {@link #H_TIE} so it only orders otherwise-equal-{@code f}
         * nodes (collapsing the open-ground equal-cost plateau, and the vertical pillar-then-ascend vs
         * ascend-then-pillar symmetry the old horizontal-only form left untouched).
         *
         * <p>Finally adds the admissible goal-cuboid forced-build premium (MACRO-IMPLEMENTATION.md §7): the
         * extra cost the octile under-counts when the goal can only be reached by building/digging through a
         * forced uniform region (the principled vertical premium). It is {@code 0} when no face is forced
         * ({@code forced.extent == 0}), so it never perturbs an ordinary search.
         */
        float h(int x, int y, int z) {
            float px = x - sx, py = y - sy, pz = z - sz;       // node relative to start
            float dx = gx - sx, dy = gy - sy, dz = gz - sz;    // goal relative to start (line direction)
            float cx = py * dz - pz * dy;                      // (node−start) × (goal−start)
            float cy = pz * dx - px * dz;
            float cz = px * dy - py * dx;
            float cross = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            float base = octile(x, y, z, gx, gy, gz, hWeight);
            if (regionField != null) {
                float rc = regionField.costAt(x, y, z);
                if (rc < com.orebit.mod.pathfinding.regionpathfinder.RegionCostField.UNREACHED) {
                    // rc is this cell's intra-region gradient value — octile(cell→goalward exit) + onward — in
                    // region per-block units (WALK_PER_BLOCK = 1/block); ×H_STRAIGHT (Traverse.FLAT_COST) → block
                    // ticks, ×hWeight to stay commensurate with the greedy-weighted octile. max() keeps the tighter,
                    // topology-aware lower bound, so a sky cell whose region loops back to the goal reads HIGH and
                    // is deprioritised instead of flooded — and the gradient pulls the search toward the exit
                    // rather than flooding the whole region at a flat per-region cost.
                    float hr = hWeight * rc * H_STRAIGHT;
                    if (hr > base) base = hr;
                }
            }
            return base + H_TIE * (cross * invLineLen)
                    + GoalForcedCost.premium(forced, x, y, z, gx, gy, gz);
        }

        /** Mode-preserving accept (every ordinary move): the destination keeps the expanding node's mode. */
        @Override
        public void accept(int nx, int ny, int nz, float cost, EditScratch scratch) {
            relax(nx, ny, nz, currentMode, cost, scratch);
        }

        /** Mode-transition accept (StartSprintSwim / Surface): the destination lands in {@code destMode}. */
        @Override
        public void accept(int nx, int ny, int nz, float cost, EditScratch scratch, int destMode) {
            relax(nx, ny, nz, destMode, cost, scratch);
        }

        private void relax(int nx, int ny, int nz, int destMode, float cost, EditScratch scratch) {
            // Corridor confinement (HPA-IMPLEMENTATION.md §9): never relax a candidate outside the window's
            // box. This is the single choke point through which every discovered cell passes, so one check
            // here confines the whole search without touching any movement. The start cell is interned
            // directly (not via accept), so it is always admitted even at the box edge.
            if (bound != null && !bound.allows(nx, ny, nz)) {
                if (TRACE) traceCand(nx, ny, nz, cost, "corridor");
                return;
            }
            float tentative = currentG + cost;
            long nKey = key(nx, ny, nz, destMode);     // mode is part of the identity → distinct row per mode
            int row = nodes.intern(nKey, nx, ny, nz, destMode);
            if (tentative >= nodes.g[row]) { // new rows start at +inf, so this also admits first visits
                if (TRACE) traceCand(nx, ny, nz, cost, "worse");
                return;
            }
            if (TRACE) traceCand(nx, ny, nz, cost, "OK");

            nodes.g[row] = tentative;
            nodes.f[row] = tentative + h(nx, ny, nz);
            nodes.parent[row] = current;
            nodes.move[row] = move;
            // Record the edits ONLY now that this candidate is an improvement we're keeping — the rejected
            // majority above touched no edit storage at all. Draw a reusable set from the per-search arena
            // and load this candidate's cells into it (no allocation in steady state). Clear any stale set
            // left by a costlier edge so the follower never mines/places blocks the winning move didn't.
            if (scratch != null && scratch.hasEdits()) {
                StepEdits e = editPool.take();
                scratch.copyInto(e);
                nodes.edits[row] = e;
                anyEdits = true;
            } else {
                nodes.edits[row] = null;
            }
            nodes.push(row);
        }

        /** Trace one emitted candidate + its outcome (only when {@link #TRACE}). */
        private void traceCand(int x, int y, int z, float cost, String outcome) {
            trace("  C " + MovementRegistry.TIER1.get(move).getClass().getSimpleName()
                    + " " + x + " " + y + " " + z + " cost=" + String.format("%.2f", cost) + " " + outcome);
        }
    }

    private static boolean isGoal(int x, int y, int z, int gx, int gy, int gz, int tolXZ, int tolY) {
        return Math.abs(x - gx) <= tolXZ && Math.abs(z - gz) <= tolXZ && Math.abs(y - gy) <= tolY;
    }

    /** Bias added to Y before packing it into {@link #key} so the (often-negative) world Y lands in the
     *  unsigned 10-bit field. Covers the full vanilla Y range (−64..320 ⊂ −512..511). */
    private static final int Y_BIAS = 512;

    /**
     * Pack {@code (x,y,z,mode)} into the open-addressed map's 64-bit identity key. The node table stores
     * x/y/z/mode in parallel arrays, so this key is ONLY a hash identity — never decoded back to coordinates.
     * Layout: x in 26 bits | z in 26 bits | (y + {@link #Y_BIAS}) in 10 bits | mode in 2 bits = 64. 26-bit
     * x/z covers the world border (±33M); the biased 10-bit y covers all vanilla Y. Collision-free, so the
     * same cell in two modes maps to two distinct keys (hence two rows), which is the whole point of the
     * {@code (x,y,z,mode)} node.
     */
    private static long key(int x, int y, int z, int mode) {
        return ((long) (x & 0x3FFFFFF) << 38)
             | ((long) (z & 0x3FFFFFF) << 12)
             | ((long) ((y + Y_BIAS) & 0x3FF) << 2)
             | (mode & 0x3);
    }

    /**
     * The search's primary travel axis {@code P} (Option B, {@code CUBOID-PERF-OPTIONS.md}): the axis with the
     * largest start→goal delta — the dominant approach direction whose long uniform runs actually warrant macro
     * collapse. Returns one of {@link Axes#AXIS_X}/{@link Axes#AXIS_Y}/{@link Axes#AXIS_Z}. <b>Tie-break order
     * X &gt; Z &gt; Y</b>: on equal deltas the kept axis also has the best linear-scan locality (X is the
     * contiguous grid axis, Z next, Y the worst), so a tie picks X first, then Z, then Y. Computed once per
     * search; only movements travelling this axis extract a cuboid per node.
     */
    private static int primaryAxis(int sx, int sy, int sz, int gx, int gy, int gz) {
        int dx = Math.abs(gx - sx), dy = Math.abs(gy - sy), dz = Math.abs(gz - sz);
        // Start at X, prefer Z then Y only on a STRICTLY larger delta (so equal deltas keep the X>Z>Y order).
        int axis = Axes.AXIS_X, best = dx;
        if (dz > best) { axis = Axes.AXIS_Z; best = dz; }
        if (dy > best) { axis = Axes.AXIS_Y; best = dy; }
        return axis;
    }

    /**
     * Weighted 3D octile distance to the goal (the base heuristic, before the straight-line tie-break added
     * in {@link Relaxer#h}). Sort the three axis gaps; spend the smallest on corner-diagonal moves ({@link
     * #H_CORNER}, all three axes at once), the next on face-diagonals ({@link #H_FACE}), and the largest
     * remainder on straight steps ({@link #H_STRAIGHT}); inflate by {@code hWeight} (the config greediness,
     * {@link BotCaps#greedyWeight}, read once per search). All axes are weighted equally — see the constants
     * above for why verticality carries no extra penalty.
     */
    private static float octile(int x, int y, int z, int gx, int gy, int gz, float hWeight) {
        int a = Math.abs(x - gx), b = Math.abs(y - gy), c = Math.abs(z - gz);
        // sort a <= b <= c (three compares)
        if (a > b) { int t = a; a = b; b = t; }
        if (b > c) { int t = b; b = c; c = t; }
        if (a > b) { int t = a; a = b; b = t; }
        return hWeight * (a * H_CORNER + (b - a) * H_FACE + (c - b) * H_STRAIGHT);
    }

    /**
     * Log <i>why</i> a search failed: how far it got, the closest cell it reached (where it dead-ended),
     * and what every movement offers from that cell. Turns a bare "plan: none" into a concrete missing
     * capability — e.g. "closest = the cliff base, and from there every movement emitted nothing" points
     * straight at the absent place-to-ascend (staircase) move. Re-running the movements from one cell is
     * cheap and only happens on failure.
     */
    private static void explainFailure(MovementContext ctx, int sx, int sy, int sz, int gx, int gy, int gz,
                                       int expansions, boolean budgetHit, int bx, int by, int bz,
                                       RegionBound bound) {
        int remaining = Math.abs(bx - gx) + Math.abs(by - gy) + Math.abs(bz - gz);
        OrebitCommon.LOGGER.info(
                "[Orebit] path FAIL start=({},{},{}) goal=({},{},{}) — {} after {} expansions; "
                        + "closest=({},{},{}), still {} blocks away.{} Moves from the dead-end:",
                sx, sy, sz, gx, gy, gz,
                budgetHit ? "hit expansion budget" : "search exhausted (nowhere left to go)",
                expansions, bx, by, bz, remaining,
                bound == null ? " (no corridor)" : "");
        for (Movement m : MovementRegistry.TIER1) {
            StringBuilder sb = new StringBuilder();
            int[] count = {0};
            m.candidates(ctx, bx, by, bz, (cx, cy, cz, cost, edits) -> {
                count[0]++;
                boolean hasEdit = edits != null && edits.hasEdits();
                sb.append(String.format(" (%d,%d,%d)c=%.1f%s", cx, cy, cz, cost, hasEdit ? "+edit" : ""));
            });
            OrebitCommon.LOGGER.info("[Orebit]   {} -> {}", m.getClass().getSimpleName(),
                    count[0] == 0 ? "(nothing)" : sb.toString());
        }
        // Column geometry up from the dead-end toward the goal: the most useful "why" for a vertical stall —
        // shows whether the cell Pillar/Ascend would climb into is air (climbable), solid (needs mining), or
        // unbuilt, and whether the corridor bound is capping the reach. Tag per cell: u=unbuilt, o=open/place-
        // able, s=standable(floor), p=passable, k=breakable, X=outside corridor.
        dumpColumn(ctx, bound, "dead-end col", bx, bz, by - 1, Math.min(by + 14, gy + 3));
        if (bx != gx || bz != gz) {
            dumpColumn(ctx, bound, "goal col", gx, gz, Math.min(by, gy) - 1, gy + 3);
        }
    }

    /**
     * Log the per-cell nav state of a vertical column {@code (x,z)} over {@code [y0, y1]} — the diagnostic
     * for a vertical stall (the open-air pillar / mine-up case). Each cell is tagged with what the movements
     * see there, so a "Pillar -> (nothing)" is explained as "the cell above is solid (s, not openForPlace)"
     * or "unbuilt (u)" or "outside the corridor (X)".
     *
     * <p>Per-cell tags: {@code u}=unbuilt, {@code X}=outside corridor, {@code s}=standable, {@code o}=open
     * for place, {@code p}=passable, {@code .}=blocked. A breakable solid is suffixed {@code k}; a solid the
     * search WON'T dig is suffixed with {@link MovementContext#breakBlockedReason the reason} in parens
     * (e.g. {@code s(unbreakable)}) — the diagnostic for a "mine-up walled by a ceiling the search thinks it
     * can't break". A cell whose body-space edit is hazardous ({@code RISKY_EDIT} — a fluid/gravity cascade,
     * which disables Pillar/place edits there) is suffixed {@code r}.
     */
    private static void dumpColumn(MovementContext ctx, RegionBound bound, String label, int x, int z,
                                   int y0, int y1) {
        StringBuilder sb = new StringBuilder();
        for (int y = y0; y <= y1; y++) {
            sb.append(' ').append(y).append(':');
            if (bound != null && !bound.allows(x, y, z)) { sb.append('X'); continue; }
            if (!ctx.built(x, y, z)) { sb.append('u'); continue; }
            long d = ctx.descriptorAt(x, y, z);
            if (ctx.standable(d)) sb.append('s');
            else if (ctx.openForPlace(d)) sb.append('o');
            else if (ctx.passable(d)) sb.append('p');
            else sb.append('.');
            if (ctx.breakable(d)) {
                sb.append('k');
            } else {
                String why = ctx.breakBlockedReason(d); // null unless a solid the search refuses to dig
                if (why != null) sb.append('(').append(why).append(')');
            }
            if (MovementContext.risksEdit(ctx.flagsAt(x, y, z))) sb.append('r');
        }
        OrebitCommon.LOGGER.info("[Orebit]   {} ({},{}):{}", label, x, z, sb);
    }

    /**
     * The furthest node along the partial path {@code start → best} the bot can reach WITHOUT crossing an
     * irreversible move — the irreversibility guard's commit point ({@link #IRREVERSIBLE_GUARD}). A move is
     * irreversible when the bot can't climb back up it unaided and {@code !canPlace} (so it can't pillar
     * back up). "Climb back" is measured in <b>real surface heights, in sixteenths</b> (the block-height
     * canon), not whole block levels: each node's standing surface is {@code y·16 + topY(floor)} (a slab
     * floor is 8/16 lower than a full block), and the edge is irreversible when the rise BACK up it
     * exceeds the jump budget {@code jumpHeight·16 + (JUMP_RISE − 16)} — i.e. {@code jumpHeight} whole
     * blocks plus the 4/16 apex margin ({@link MovementContext#JUMP_RISE} = 20 for the vanilla
     * {@code jumpHeight == 1}). So a one-block-level drop ONTO a slab is a 24/16 (1.5-block) drop whose
     * return jump needs 24 > 20 — irreversible for a no-place bot — while the same drop between full
     * blocks is 16 ≤ 20 (reversible, exactly the old whole-block behaviour: for full tops the test
     * reduces to {@code drop > jumpHeight}).
     *
     * <p>Returns {@code startRow} when the very first edge is irreversible (⇒ suppress the partial, stay
     * put), or {@code bestRow} when the whole partial is reversible (or the bot can place, so nothing is
     * irreversible).
     *
     * <p>Cold path: one walk per budget-exhausted partial, never on the search hot loop — the per-node
     * {@code floorSurface} reads (two per edge, cached-section resolves) are fine here. The {@code ctx}
     * reads see whatever {@link PathEdits} diff the search last loaded, not necessarily this chain's; for
     * a {@code !canPlace} bot (the only kind that reaches the walk) the diff holds only BREAKS, and a
     * stale-broken floor cell reads as air → {@code floorSurface} 16, i.e. that node degrades to the
     * historical whole-block arithmetic — never a NEW kind of wrong answer. A macro Fall/MineDown carries
     * its full collapsed drop on one edge, so it is correctly seen as one big irreversible edge and the
     * commit stops before it (not mid-drop).
     */
    private static int lastReversibleRow(MovementContext ctx, Nodes nodes, int startRow, int bestRow,
                                         BotCaps caps) {
        if (caps.canPlace()) return bestRow; // a placing bot can always pillar back out — nothing is one-way
        // Collect the start→best chain forward, then walk to the first unclimbable drop.
        List<Integer> rows = new ArrayList<>();
        for (int n = bestRow; n != -1; n = nodes.parent[n]) {
            rows.add(n);
            if (n == startRow) break;
        }
        Collections.reverse(rows);
        // The rise (sixteenths) one jump regains: jumpHeight whole blocks + the 4/16 apex margin the
        // vanilla 1.25-block jump has over its 1-block gain (JUMP_RISE = 20 = 16 + 4). Derived, not
        // hardcoded, so a jump-boosted bot (jumpHeight 2 → 36) scales the same way.
        final int jumpRise = caps.jumpHeight() * 16 + (MovementContext.JUMP_RISE - 16);
        for (int i = 1; i < rows.size(); i++) {
            int p = rows.get(i - 1), n = rows.get(i);
            // Rise back up this edge between the two REAL standing surfaces (block level ·16 + the
            // effective floor surface: floorSurface reads a standable floor's topY, and 16 for a
            // non-standable float "floor" — a swim node's water floor — so all-water chains keep the
            // whole-block arithmetic they had before).
            int riseBack = nodes.y[p] * 16 + ctx.floorSurface(nodes.x[p], nodes.y[p], nodes.z[p])
                    - (nodes.y[n] * 16 + ctx.floorSurface(nodes.x[n], nodes.y[n], nodes.z[n]));
            if (riseBack > jumpRise) return p;  // stop just before the first drop the bot can't climb back
        }
        return bestRow; // whole partial is reversible
    }

    /**
     * Root-node candidate breakdown (LOG_TIMING) — see the call site. Enumerates the start cell's emitted moves,
     * each with its per-step {@code g}, the heuristic decomposed into weighted-octile + forced-build premium, and
     * {@code f = g + h}, so a chosen first move is explained (does a MineDown really out-score a Pillar; is the
     * premium 0 far below the goal). Passive: it never relaxes, so the search is unaffected.
     */
    private static void logRoot(MovementContext ctx, Relaxer relaxer, GoalForcedCost.Forced forced,
                                int sx, int sy, int sz, int startMode, float hWeight, int gx, int gy, int gz) {
        ctx.setMode(startMode); // match the real root expansion's mode so the emitted candidates are the same set
        final float sOct = octile(sx, sy, sz, gx, gy, gz, hWeight);
        final float sPrem = GoalForcedCost.premium(forced, sx, sy, sz, gx, gy, gz);
        final StringBuilder sb = new StringBuilder();
        sb.append("[Orebit] ROOT (").append(sx).append(',').append(sy).append(',').append(sz)
                .append(") mode=").append(startMode).append(" -> goal(").append(gx).append(',').append(gy)
                .append(',').append(gz).append(") W=").append(String.format("%.2f", hWeight))
                .append("  startH=").append(String.format("%.1f", relaxer.h(sx, sy, sz)))
                .append(" [oct=").append(String.format("%.1f", sOct))
                .append(" prem=").append(String.format("%.1f", sPrem)).append(']');
        // Column probe: the built-status + classification of the cells directly above the bot. Pillar/Ascend
        // gate on the footing cell (y+1, openForPlace) and the head cell (y+3); if any reads UNBUILT they emit
        // nothing — the "no up-move at a section boundary" signature we're hunting. S=standable .=passable
        // #=blocked, (p)=openForPlace.
        sb.append("\n  col:");
        for (int dy = 0; dy <= 4; dy++) {
            final int yy = sy + dy;
            sb.append(" y").append(yy).append('=');
            if (!ctx.built(sx, yy, sz)) { sb.append("UNBUILT"); continue; }
            final long d = ctx.descriptorAt(sx, yy, sz);
            sb.append(ctx.standable(d) ? "S" : (ctx.passable(d) ? "." : "#"));
            if (ctx.openForPlace(d)) sb.append("(p)");
        }
        final RootSink sink = new RootSink(relaxer, forced, hWeight, gx, gy, gz, sb);
        for (Movement m : MovementRegistry.TIER1) {
            sink.move = m.getClass().getSimpleName();
            m.candidates(ctx, sx, sy, sz, sink);
        }
        OrebitCommon.LOGGER.info(sb.toString());
    }

    /** Passive {@link CandidateSink} for {@link #logRoot}: appends each emitted candidate's g / h-decomposition /
     *  f to a buffer without relaxing anything (the open set is untouched). */
    private static final class RootSink implements CandidateSink {
        private final Relaxer relaxer;
        private final GoalForcedCost.Forced forced;
        private final float hWeight;
        private final int gx, gy, gz;
        private final StringBuilder sb;
        private String move = "?";

        RootSink(Relaxer relaxer, GoalForcedCost.Forced forced, float hWeight, int gx, int gy, int gz,
                 StringBuilder sb) {
            this.relaxer = relaxer;
            this.forced = forced;
            this.hWeight = hWeight;
            this.gx = gx; this.gy = gy; this.gz = gz;
            this.sb = sb;
        }

        @Override
        public void accept(int x, int y, int z, float cost, EditScratch edits) {
            log(x, y, z, cost, edits, -1);
        }

        @Override
        public void accept(int x, int y, int z, float cost, EditScratch edits, int mode) {
            log(x, y, z, cost, edits, mode);
        }

        private void log(int x, int y, int z, float cost, EditScratch edits, int mode) {
            final float oct = octile(x, y, z, gx, gy, gz, hWeight);
            final float prem = GoalForcedCost.premium(forced, x, y, z, gx, gy, gz);
            final float h = relaxer.h(x, y, z);
            sb.append("\n  ").append(move).append(" ->(").append(x).append(',').append(y).append(',').append(z)
                    .append(") g=").append(String.format("%.1f", cost))
                    .append(" h=").append(String.format("%.1f", h))
                    .append(" [oct=").append(String.format("%.1f", oct))
                    .append(" prem=").append(String.format("%.1f", prem)).append("] f=")
                    .append(String.format("%.1f", cost + h));
            if (edits != null) sb.append(" +edits");
            if (mode >= 0) sb.append(" mode=").append(mode);
        }
    }

    private static BlockPathPlan reconstruct(Nodes nodes, int startRow, int reachedRow) {
        List<Movement> tier1 = MovementRegistry.TIER1;

        // Collect the node rows from start to reached, in FORWARD order (the start cell itself is not a
        // waypoint — the bot is already there). Not hot (one build per pathfind), so the boxing is fine.
        List<Integer> rows = new ArrayList<>();
        for (int n = reachedRow; n != -1; n = nodes.parent[n]) {
            rows.add(n);
            if (n == startRow) break;
        }
        Collections.reverse(rows);

        List<BlockPos> waypoints = new ArrayList<>();
        List<Movement> moves = new ArrayList<>();
        List<StepEdits> edits = new ArrayList<>();

        // Each consecutive (p -> n) pair is one A* edge. A MACRO edge (Pillar/MineDown/Traverse collapsed a
        // uniform run of >1 step into one node, MACRO-IMPLEMENTATION.md §8) is re-expanded HERE into its N
        // intermediate stand positions, each carrying just the per-step edit at its own cell — so the
        // follower (steerAlongPath/applyEdits) sees an ordinary block-by-block path and is UNCHANGED (§9). A
        // micro edge — or a Fall/Descend whose multi-cell drop the follower interpolates under gravity —
        // stays a single waypoint exactly as before.
        for (int i = 1; i < rows.size(); i++) {
            int p = rows.get(i - 1);
            int n = rows.get(i);
            Movement move = tier1.get(nodes.move[n]);
            StepEdits edge = nodes.edits[n];

            int px = nodes.x[p], py = nodes.y[p], pz = nodes.z[p];
            int nx = nodes.x[n], ny = nodes.y[n], nz = nodes.z[n];
            int j = macroSteps(move, nx - px, ny - py, nz - pz);

            if (j <= 1) {
                // Single waypoint (the historical behaviour): floor cell's top = the bot's stand position.
                // Read coords from the node arrays (the key now packs mode and no longer round-trips to a pos).
                waypoints.add(new BlockPos(nx, ny + 1, nz));
                moves.add(move);
                // Copy out of the per-search arena: these edits ride home in the BlockPathPlan and are
                // replayed by the follower over many ticks, while later searches reuse the arena slots.
                edits.add(edge == null ? null : edge.copy());
                continue;
            }

            // Macro edge: re-expand to j stand positions, slicing the folded edit-set per step by position.
            int ux = Integer.signum(nx - px), uy = Integer.signum(ny - py), uz = Integer.signum(nz - pz);
            for (int k = 1; k <= j; k++) {
                int fx = px + ux * k, fy = py + uy * k, fz = pz + uz * k; // floor cell of step k
                waypoints.add(new BlockPos(fx, fy + 1, fz));             // floor.above() = stand position
                moves.add(move);
                edits.add(edge == null ? null : sliceStep(edge, fx, fy, fz));
            }
        }
        return new BlockPathPlan(waypoints, moves, edits, nodes.g[reachedRow]);
    }

    /**
     * The number of waypoints a (p→n) edge expands to. A macro edge is one of the three axis-aligned macro
     * movements collapsing a uniform run of {@code >1} step along its own axis ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar} +Y, {@code MineDown} −Y, {@code Traverse}
     * a horizontal cardinal); everything else (a micro step, or a Fall/Descend the follower interpolates) is
     * {@code 1}. Gating by the movement identity is what keeps a multi-block <i>Fall</i> (also a {@code >1}
     * −Y delta) a single follower-handled waypoint rather than a per-block expansion.
     */
    private static int macroSteps(Movement move, int dx, int dy, int dz) {
        if (move == MovementRegistry.PILLAR)    return (dx == 0 && dz == 0 && dy > 1)  ? dy  : 1;
        if (move == MovementRegistry.MINE_DOWN) return (dx == 0 && dz == 0 && dy < -1) ? -dy : 1;
        if (move == MovementRegistry.TRAVERSE) {
            if (dy == 0 && dz == 0 && Math.abs(dx) > 1) return Math.abs(dx);
            if (dy == 0 && dx == 0 && Math.abs(dz) > 1) return Math.abs(dz);
        }
        return 1;
    }

    /** Empty buffer for an edit-set with no breaks/places on one of its axes. */
    private static final long[] NO_CELLS = new long[0];

    /**
     * Slice one expanded waypoint's edit-set out of a macro edge's folded {@link StepEdits}, by position: the
     * step landing on floor {@code (fx,fy,fz)} owns the placement AT that floor cell (a Pillar/Traverse
     * footing) and the breaks at its two body cells {@code (fx,fy+1,fz)} / {@code (fx,fy+2,fz)} (a MineDown
     * descends by breaking the cell just above the new floor; a Traverse clears its body; a Pillar's top
     * head-clearance break lands on the last step's body). Every folded edit of the three macro movements is
     * owned by exactly one step under this rule, so nothing is dropped or double-applied. Returns {@code null}
     * when the step owns no edit (the plain-step fast path the follower expects).
     */
    private static StepEdits sliceStep(StepEdits all, int fx, int fy, int fz) {
        long floorPos = BlockPos.asLong(fx, fy, fz);
        long body1 = BlockPos.asLong(fx, fy + 1, fz);
        long body2 = BlockPos.asLong(fx, fy + 2, fz);

        long[] pl = null;
        int pn = 0;
        for (int i = 0, c = all.placeCount(); i < c; i++) {
            if (all.placeAt(i) == floorPos) {
                if (pl == null) pl = new long[c];
                pl[pn++] = floorPos;
            }
        }
        long[] bk = null;
        int bn = 0;
        for (int i = 0, c = all.breakCount(); i < c; i++) {
            long pos = all.breakAt(i);
            if (pos == body1 || pos == body2) {
                if (bk == null) bk = new long[c];
                bk[bn++] = pos;
            }
        }
        if (pn == 0 && bn == 0) return null;
        StepEdits s = new StepEdits();
        s.load(bk == null ? NO_CELLS : bk, bn, pl == null ? NO_CELLS : pl, pn);
        return s;
    }
}
