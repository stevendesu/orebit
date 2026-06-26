package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     * When true, a failed search logs WHY (closest approach + what each movement offered from the
     * dead-end) — the diagnostic for "plan: none". Mirrors {@code AllyBotEntity.DEBUG_PATH}; flip both off
     * to silence. Only runs on failure (rare, and throttled by the caller's replan cadence).
     */
    public static boolean DEBUG = true;

    /**
     * When true, every search logs how long it took, how many nodes it expanded, and the derived ns/node
     * — the empirical data for sizing {@link #MAX_EXPANSIONS}. One INFO line per pathfind, so flip off once
     * you've collected data. Caveats when reading it: the first few are JIT-cold (ignore), break/place
     * paths cost more per node than plain walks (the PathEdits rebuild + edit checks), and a GC pause can
     * spike a single sample — look at the steady-state distribution, not one line.
     */
    public static boolean LOG_TIMING = true;

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

        /** Row for {@code key}, creating it (with {@code g=+inf}, unlinked) if absent. One probe. */
        int intern(long k, int cx, int cy, int cz) {
            int slot = slotFor(k, mapMask);
            for (;;) {
                int row = mapRow[slot];
                if (row == -1) {
                    row = newRow(k, cx, cy, cz);
                    mapKey[slot] = k;
                    mapRow[slot] = row;
                    if (++mapSize >= mapGrowAt) growMap();
                    return row;
                }
                if (mapKey[slot] == k) return row;
                slot = (slot + 1) & mapMask;
            }
        }

        private int newRow(long k, int cx, int cy, int cz) {
            int n = count;
            if (n == key.length) growNodes();
            key[n] = k;
            x[n] = cx; y[n] = cy; z[n] = cz;
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
        final long t0 = System.nanoTime();
        final int sx = startFloor.getX(), sy = startFloor.getY(), sz = startFloor.getZ();
        final int gx = goalFloor.getX(), gy = goalFloor.getY(), gz = goalFloor.getZ();

        // Bot must be on built ground for the grid-based search to mean anything.
        if (!grid.built(sx, sy, sz)) {
            if (LOG_TIMING) logTiming(t0, 0, false, "no-start-ground", sx, sy, sz, gx, gy, gz);
            return null;
        }

        // Read the configurable search params into search-start locals ONCE — the hot loop's budget test
        // (below) and the heuristic weight (on the Relaxer) then read a local / a final field, not a
        // BotCaps accessor per node. Guarded so a mis-configured caps can never disable the backstop:
        // a non-positive maxNodes falls back to the historical default.
        final int maxNodes = caps.maxNodes() > 0 ? caps.maxNodes() : BotCaps.DEFAULT_MAX_NODES;

        final MovementContext ctx = new MovementContext(grid, caps);
        ctx.setInventory(inventory); // null in the historical / headless / trace paths (caps-only gates)
        final long startKey = BlockPos.asLong(sx, sy, sz);

        // Macro-movement collapse (MACRO-IMPLEMENTATION.md §8): a corridor-bounded search builds a per-search
        // cuboid view over the SAME PathEdits the movements read, wires it + the goal onto the context, and
        // probes the goal's faces once for the admissible forced-build heuristic premium (§7). An unbounded
        // search (bound == null, e.g. /bot trace) gets no view → the movements emit plain micro steps.
        final NavGridCuboidsView cuboids = (MACRO_MOVES && bound != null)
                ? new NavGridCuboidsView(grid, ctx.pathEdits(), bound) : null;
        // Primary travel axis P (Option B, CUBOID-PERF-OPTIONS.md): the dominant start→goal approach axis, so
        // only the movements travelling P extract a cuboid per node (the other axes take their micro step) —
        // pinning per-node extraction to one axis instead of up to three. argmax(|dx|,|dy|,|dz|) with tie-break
        // X > Z > Y (the kept axis then also has the best linear-scan locality — X is the contiguous grid axis,
        // Y the worst). Computed once here where start + goal are both in hand.
        final int macroAxis = primaryAxis(sx, sy, sz, gx, gy, gz);
        ctx.setMacro(cuboids, gx, gy, gz, macroAxis);
        final GoalForcedCost.Forced forced = new GoalForcedCost.Forced();
        GoalForcedCost.probe(cuboids, gx, gy, gz, caps, ctx.pillarPlaceCost(), forced);

        // Reuse this thread's search state (sized to its high-water mark), wiped to empty — so a steady
        // stream of replans allocates nothing here. First call on a thread pays the initial 512/1024 sizing.
        final Nodes nodes = SEARCH.get();
        nodes.reset();
        final EditPool editPool = EDIT_POOL.get();
        editPool.reset();
        final float hWeight = caps.greedyWeight() >= 1.0f ? caps.greedyWeight() : BotCaps.DEFAULT_GREEDY_WEIGHT;
        Relaxer relaxer = new Relaxer(nodes, editPool, sx, sy, sz, gx, gy, gz, bound, forced, hWeight);

        int startRow = nodes.intern(startKey, sx, sy, sz);
        nodes.g[startRow] = 0f;
        nodes.f[startRow] = relaxer.h(sx, sy, sz);
        nodes.push(startRow);

        int expansions = 0;
        int reachedRow = -1;
        // Closest approach (min heuristic among closed nodes) + why the search stopped — the diagnostic
        // for a failed plan: where did it dead-end, and was it walled in or just out of budget?
        float bestH = Float.MAX_VALUE;
        int bestX = sx, bestY = sy, bestZ = sz;
        int bestRow = startRow;     // row of the closest-approach node — the partial-path target on budget exhaustion
        boolean budgetHit = false;

        while (nodes.heapSize > 0) {
            int current = nodes.pop();

            // Stale queue entry (a better f was found and re-pushed after this entry).
            if (nodes.poppedF > nodes.f[current]) continue;

            int cx = nodes.x[current], cy = nodes.y[current], cz = nodes.z[current];
            if (isGoal(cx, cy, cz, gx, gy, gz)) {
                reachedRow = current;
                break;
            }

            float h = relaxer.h(cx, cy, cz);
            if (h < bestH) { bestH = h; bestX = cx; bestY = cy; bestZ = cz; bestRow = current; }

            if (TRACE) trace("E " + expansions + " " + cx + " " + cy + " " + cz
                    + " g=" + nodes.g[current] + " f=" + nodes.f[current] + " via="
                    + (nodes.move[current] < 0 ? "start"
                            : MovementRegistry.TIER1.get(nodes.move[current]).getClass().getSimpleName()));

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

            relaxer.current = current;
            relaxer.currentG = nodes.g[current];
            List<Movement> tier1 = MovementRegistry.TIER1;
            for (int mi = 0, mn = tier1.size(); mi < mn; mi++) {
                relaxer.move = mi;
                tier1.get(mi).candidates(ctx, cx, cy, cz, relaxer);
            }
        }

        if (reachedRow == -1) {
            if (DEBUG) explainFailure(ctx, sx, sy, sz, gx, gy, gz, expansions, budgetHit, bestX, bestY, bestZ, bound);
            // Partial-path return: when the BUDGET (not connectivity) stopped the search and it made real
            // progress toward the goal, return the path to the closest-approach node so the bot moves forward
            // and replans from there — converging on a goal a single bounded search can't reach in one shot.
            // A search that EXHAUSTED the heap (walled in) returns null instead: the closest cell is all it can
            // reach, so moving there and replanning would just re-fail (and keeps the FAIL signal visible).
            if (PARTIAL_PATH && budgetHit && bestRow != startRow
                    && (relaxer.h(sx, sy, sz) - bestH) > PARTIAL_MIN_PROGRESS) {
                BlockPathPlan partial = reconstruct(nodes, startRow, bestRow);
                if (LOG_TIMING) logTiming(t0, expansions, relaxer.anyEdits,
                        "PARTIAL-" + partial.size() + "wp", sx, sy, sz, gx, gy, gz);
                return partial;
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

        int current;        // row being expanded
        float currentG;     // its g (read once per expansion, not per candidate)
        int move;           // MovementRegistry.TIER1 index currently emitting candidates
        boolean anyEdits;   // has any edge carried break/place edits? (gates the per-pop diff rebuild)

        Relaxer(Nodes nodes, EditPool editPool, int sx, int sy, int sz, int gx, int gy, int gz,
                RegionBound bound, GoalForcedCost.Forced forced, float hWeight) {
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
            return octile(x, y, z, gx, gy, gz, hWeight) + H_TIE * (cross * invLineLen)
                    + GoalForcedCost.premium(forced, x, y, z, gx, gy, gz);
        }

        @Override
        public void accept(int nx, int ny, int nz, float cost, EditScratch scratch) {
            // Corridor confinement (HPA-IMPLEMENTATION.md §9): never relax a candidate outside the window's
            // box. This is the single choke point through which every discovered cell passes, so one check
            // here confines the whole search without touching any movement. The start cell is interned
            // directly (not via accept), so it is always admitted even at the box edge.
            if (bound != null && !bound.allows(nx, ny, nz)) {
                if (TRACE) traceCand(nx, ny, nz, cost, "corridor");
                return;
            }
            float tentative = currentG + cost;
            long nKey = BlockPos.asLong(nx, ny, nz);
            int row = nodes.intern(nKey, nx, ny, nz);
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

    private static boolean isGoal(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) <= 1 && Math.abs(z - gz) <= 1 && Math.abs(y - gy) <= 2;
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
            if (ctx.breakable(d)) sb.append('k');
        }
        OrebitCommon.LOGGER.info("[Orebit]   {} ({},{}):{}", label, x, z, sb);
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
                waypoints.add(BlockPos.of(nodes.key[n]).above());
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
