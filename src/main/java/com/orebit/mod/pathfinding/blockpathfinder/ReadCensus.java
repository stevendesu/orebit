package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-pop NavGrid read census — the measurement instrument behind the read-reduction arc
 * ({@code internal_docs/INVENTORY-per-move-cell-reads.md}).
 *
 * <h2>Why this exists</h2>
 * The inventory is a <b>hand-encoded upper envelope</b>: it enumerates every documented read SITE and
 * assumes every arm of every movement is reached (972 calls / 276 cells / 3.52x). No real pop does that —
 * arms {@code continue} past each other, mode-gating halves the movement set, and most movements early-out
 * in the first cell or two. The envelope answers "which cells does the search CONTEND over"; it cannot
 * answer "what does an AVERAGE pop actually read", and every design decision downstream (prism sizing,
 * which flags earn a bit, whether scattering pays) turns on the average, not the worst case.
 *
 * <p>This class closes that gap by counting the real thing on a real world.
 *
 * <h2>What it is NOT</h2>
 * <b>This measures COUNTS, never TIME.</b> With the census on, every read pays a bucket computation and
 * several array increments, so wall-clock numbers from a census run are meaningless — do not quote ns/node
 * from one. Timing stays with JMH ({@code PathfinderBenchmark}) under the paired-interleaved A/B protocol.
 * The two instruments answer different questions and are not interchangeable.
 *
 * <h2>Zero cost when off</h2>
 * {@link #ENABLED} is a {@code static final} initialised from a system property, so with the property
 * absent the JIT constant-folds every {@code if (ReadCensus.ENABLED)} guard and its body away entirely —
 * the shipped read path is bit-identical to the pre-census code. This is deliberately NOT a mutable
 * {@code static boolean} like {@link BlockPathfinder#TRACE}: {@code TRACE} is tested once per pop and once
 * per emitted candidate, which is affordable; a census hook fires on EVERY read, and a per-read load+branch
 * is exactly the cost class this project has repeatedly measured as a real regression (see the reverted
 * neighbour-prefetch stencil in {@code CLAUDE.md}'s performance model). Enable with:
 *
 * <pre>  JAVA_TOOL_OPTIONS=-Dorebit.readcensus=true</pre>
 *
 * <p>(an env var rather than a run-config edit, because the run configs are era-owned build scripts and
 * this instrument is meant to work unchanged on both eras). Then drive the scenario and dump with
 * {@code /bot census dump}.
 *
 * <h2>What is counted</h2>
 * The hooks sit on {@link MovementContext}'s SIX primitive read entry points — {@code built}, {@code
 * packedAt}, {@code descriptorAt}, {@code descriptorOf}, {@code flagsAt}, {@code floorGapAt}. Everything
 * else the movement layer calls ({@code passable}, {@code standable}, {@code breakable}, {@code placeable},
 * {@code topYOf}, …) is derived from those six, so hooking them catches every movement-facing grid read
 * exactly once, with no double-counting through the vocabulary helpers.
 *
 * <p><b>Deliberately excluded:</b> the bulk seams {@code NavGridView.sectionRawAt}/{@code depthRawAt}. Those
 * resolve a section ONCE and then index the raw array directly — a different cost class from a per-cell
 * accessor call, and their consumer (the cuboid extractor) runs mostly at search setup rather than per pop.
 * Counting them here would inflate the per-pop numbers with reads that the prism could never eliminate.
 *
 * <h2>The three questions it answers</h2>
 * <ol>
 *   <li><b>Is the premise true?</b> reads/pop, and the REPEAT TAX — what share of reads are the 2nd, 3rd,
 *       …, Nth touch of a cell already read in the same pop. That share is the ceiling on what any
 *       read-once scheme (prism or memo) can recover, and the inventory's 3.52x is its upper bound.
 *   <li><b>How big should the prism be?</b> The per-offset table carries both {@code calls/pop} (how much
 *       traffic that offset generates) and {@code popCover%} (the fraction of pops that touch it AT ALL).
 *       An offset with high traffic and high coverage is worth prefetching; one with high traffic but low
 *       coverage is a memo candidate, not a prefetch candidate, because prefetching it is wasted on the
 *       pops that never ask. {@link #PRISMS} scores candidate envelopes directly against both.
 *   <li><b>What does the edit layer actually cost?</b> The split of reads by whether the path-edit diff was
 *       empty, bbox-rejected, or probed — the 2-3x edit-path multiplier decomposed into the three tests
 *       {@link PathEdits#kindAt} performs.
 * </ol>
 *
 * <h2>Threading</h2>
 * Async pathing runs the block tier on {@code PlanExecutor}'s planner threads, so all state is per-thread
 * ({@link #TL}) with no sharing on the hot path; {@link #dump} merges every registered thread's counters.
 * A per-thread instance registers itself in {@link #ALL} once, on first touch.
 */
public final class ReadCensus {

    /**
     * Master gate. {@code static final} so the JIT erases every guarded hook when off — see the class
     * note. Set {@code -Dorebit.readcensus=true} (e.g. via {@code JAVA_TOOL_OPTIONS}) to arm it.
     */
    public static final boolean ENABLED = Boolean.getBoolean("orebit.readcensus");

    // ---- accessor kinds (index into Census.kindCalls) ---------------------------------------------
    public static final int K_BUILT = 0;
    public static final int K_PACKED = 1;
    public static final int K_DESC_AT = 2;
    public static final int K_DESC_OF = 3;
    public static final int K_FLAGS = 4;
    public static final int K_FLOORGAP = 5;
    private static final int NKIND = 6;

    private static final String[] KIND_NAMES =
            { "built", "packedAt", "descriptorAt", "descriptorOf", "flagsAt", "floorGapAt" };

    // ---- edit-layer outcomes (index into Census.editCalls) ----------------------------------------
    /** The diff was empty — {@code size == 0}, one perfectly-predicted compare and out. */
    private static final int E_EMPTY = 0;
    /** Diff non-empty but the cell fell outside its bounding box — 6 compares, no probe. */
    private static final int E_BBOX_MISS = 1;
    /** Diff non-empty, cell inside the box, murmur probe ran and found nothing. */
    private static final int E_PROBE_MISS = 2;
    /** Diff non-empty, probe HIT — the read was actually served from the edit layer. */
    private static final int E_PROBE_HIT = 3;
    private static final int NEDIT = 4;

    private static final String[] EDIT_NAMES =
            { "no edits (size==0)", "bbox reject", "probe miss", "probe HIT" };

    // ---- bounded offset space, relative to the popped cell ----------------------------------------
    // Wide enough to hold every offset the inventory documents (Parkour reaches +-4 laterally; Fall and the
    // MineDown/Pillar macro runs scan well below), with an out-of-range bucket so nothing is silently lost.
    private static final int DX_MIN = -8, DX_MAX = 8;
    private static final int DY_MIN = -24, DY_MAX = 12;
    private static final int DZ_MIN = -8, DZ_MAX = 8;
    private static final int NX = DX_MAX - DX_MIN + 1;   // 17
    private static final int NY = DY_MAX - DY_MIN + 1;   // 37
    private static final int NZ = DZ_MAX - DZ_MIN + 1;   // 17
    private static final int NOFF = NX * NY * NZ;        // 10693

    /** Reads-per-pop / distinct-per-pop histogram width; anything larger clamps into the last slot. */
    private static final int POP_HIST = 512;
    /** Repeat-depth histogram width: slot i = cells read exactly i+1 times in one pop (last slot = "or more"). */
    private static final int REPEAT_HIST = 24;
    /** Movement slots: index 0 is the shared per-pop prologue, 1..n are {@code MovementRegistry.TIER1} + 1. */
    private static final int NMOVE = 40;

    /**
     * Candidate prefetch envelopes, scored by {@link #dump}. Each is {@code {name-index, rx, yLo, yHi, rz}}
     * where {@code rx}/{@code rz} are lateral radii and {@code yLo..yHi} the vertical span, all relative to
     * the popped cell. Chosen to bracket the shapes under discussion: the 3x4x3 and 3x5x3 prisms, the
     * contended core the inventory's 3D view showed (dy -1..+2), a jump-tall variant, and a 5-wide box as
     * the upper bracket.
     */
    private static final int[][] PRISMS = {
            { 1, -1, 2, 1 },   // 3x4x3  — the proposed envelope
            { 1, -1, 3, 1 },   // 3x5x3  — jump-tall
            { 1, -2, 3, 1 },   // 3x6x3  — plus one below (Descend/Fall lip)
            { 1, -1, 1, 1 },   // 3x3x3  — minimal core
            { 2, -2, 3, 2 },   // 5x6x5  — upper bracket
    };

    private static final ConcurrentLinkedQueue<Census> ALL = new ConcurrentLinkedQueue<>();

    private static final ThreadLocal<Census> TL = ThreadLocal.withInitial(() -> {
        Census c = new Census();
        ALL.add(c);
        return c;
    });

    private ReadCensus() { }

    /** Per-thread counters. Plain fields, no synchronisation — only the owning thread writes. */
    private static final class Census {
        // Per-offset totals across the whole run.
        final long[] offCalls = new long[NOFF];   // total read calls landing on this offset
        final long[] offPops = new long[NOFF];    // pops in which this offset was read at least once
        long outOfRangeCalls;

        // Per-pop scratch: cellCount is cleared by walking `touched`, so clearing is O(distinct), not O(NOFF).
        final int[] cellCount = new int[NOFF];
        final int[] touched = new int[NOFF];
        int nTouched;

        final long[] kindCalls = new long[NKIND];
        final long[] editCalls = new long[NEDIT];
        final long[] moveCalls = new long[NMOVE];
        final long[] readsPerPop = new long[POP_HIST];
        final long[] distinctPerPop = new long[POP_HIST];
        final long[] repeatDepth = new long[REPEAT_HIST];

        long pops;
        long popsWithEdits;
        long totalCalls;
        long totalDistinct;
        long searches;

        // Live pop state.
        int px, py, pz;      // popped cell
        int move;            // current movement slot (0 = prologue)
        int callsThisPop;
        boolean inPop;
    }

    // ---- hot-path hooks ---------------------------------------------------------------------------

    /**
     * Open a pop. Called from {@link BlockPathfinder}'s expansion loop BEFORE the shared prologue, so the
     * prologue's two descriptor reads are attributed to slot 0 rather than to whichever movement runs first.
     */
    public static void beginPop(int x, int y, int z, boolean hasEdits) {
        Census c = TL.get();
        if (c.inPop) endPop(); // defensive: a search that broke out mid-pop (goal hit / budget) left one open
        c.px = x;
        c.py = y;
        c.pz = z;
        c.move = 0;
        c.callsThisPop = 0;
        c.nTouched = 0;
        c.inPop = true;
        c.pops++;
        if (hasEdits) c.popsWithEdits++;
    }

    /** Attribute subsequent reads to {@code MovementRegistry.TIER1[index]}. */
    public static void move(int index) {
        Census c = TL.get();
        c.move = index + 1 < NMOVE ? index + 1 : NMOVE - 1;
    }

    /**
     * Record one movement-facing grid read of cell {@code (x,y,z)} through accessor {@code kind}, with the
     * edit-layer outcome {@code editOutcome} (one of the {@code E_*} constants, resolved by the caller
     * because only it knows which of the three {@link PathEdits#kindAt} tests actually ran).
     */
    private static void read(int kind, int x, int y, int z, int editOutcome) {
        Census c = TL.get();
        c.kindCalls[kind]++;
        c.editCalls[editOutcome]++;
        c.moveCalls[c.move]++;
        c.totalCalls++;
        if (!c.inPop) return; // a read from outside the expansion loop (setup, HPA leaf cost) — kind-counted only
        c.callsThisPop++;
        int b = bucket(x - c.px, y - c.py, z - c.pz);
        if (b < 0) {
            c.outOfRangeCalls++;
            return;
        }
        c.offCalls[b]++;
        int prior = c.cellCount[b];
        if (prior == 0) c.touched[c.nTouched++] = b;
        c.cellCount[b] = prior + 1;
    }

    /** Close a pop: fold the per-cell counts into the distinct/repeat histograms and clear the scratch. */
    public static void endPop() {
        Census c = TL.get();
        if (!c.inPop) return;
        c.inPop = false;
        int distinct = c.nTouched;
        c.totalDistinct += distinct;
        c.readsPerPop[Math.min(c.callsThisPop, POP_HIST - 1)]++;
        c.distinctPerPop[Math.min(distinct, POP_HIST - 1)]++;
        for (int i = 0; i < distinct; i++) {
            int b = c.touched[i];
            int n = c.cellCount[b];
            c.repeatDepth[Math.min(n - 1, REPEAT_HIST - 1)]++;
            c.offPops[b]++;
            c.cellCount[b] = 0;
        }
        c.nTouched = 0;
    }

    /** Count one completed block-A* search (context for the per-pop averages). */
    public static void endSearch() {
        TL.get().searches++;
    }

    // ---- the six accessor entry points ------------------------------------------------------------
    // Each takes the edit-layer outcome its caller observed, so the report can decompose the edit tax
    // without the census re-running kindAt (which would change what it measures).

    public static void readBuilt(int x, int y, int z) {
        read(K_BUILT, x, y, z, E_EMPTY); // built() never consults the diff
    }

    public static void readPacked(int x, int y, int z) {
        read(K_PACKED, x, y, z, E_EMPTY); // packedAt() never consults the diff
    }

    public static void readFlags(int x, int y, int z) {
        read(K_FLAGS, x, y, z, E_EMPTY); // flagsAt() never consults the diff — the staleness of Issue 1
    }

    public static void readFloorGap(int x, int y, int z) {
        read(K_FLOORGAP, x, y, z, E_EMPTY); // floorGapAt() never consults the diff
    }

    public static void readDescriptorAt(int x, int y, int z, int editOutcome) {
        read(K_DESC_AT, x, y, z, editOutcome);
    }

    public static void readDescriptorOf(int x, int y, int z, int editOutcome) {
        read(K_DESC_OF, x, y, z, editOutcome);
    }

    /**
     * Classify what the path-edit diff actually did for a read of {@code (x,y,z)} — the decomposition of
     * the 2-3x edit-path multiplier into {@link PathEdits#kindAt}'s three tests. Only ever called from a
     * {@link #ENABLED}-guarded site, so its cost never reaches a shipped build.
     */
    public static int editOutcome(PathEdits edits, int x, int y, int z, int kindFound) {
        if (edits.isEmpty()) return E_EMPTY;
        if (!edits.boxContains(x, y, z)) return E_BBOX_MISS;
        return kindFound == PathEdits.NONE ? E_PROBE_MISS : E_PROBE_HIT;
    }

    private static int bucket(int dx, int dy, int dz) {
        if (dx < DX_MIN || dx > DX_MAX || dy < DY_MIN || dy > DY_MAX || dz < DZ_MIN || dz > DZ_MAX) return -1;
        return ((dy - DY_MIN) * NZ + (dz - DZ_MIN)) * NX + (dx - DX_MIN);
    }

    // ---- reporting --------------------------------------------------------------------------------

    /** Discard every thread's counters (start a fresh measurement window). */
    public static void reset() {
        for (Census c : ALL) {
            java.util.Arrays.fill(c.offCalls, 0L);
            java.util.Arrays.fill(c.offPops, 0L);
            java.util.Arrays.fill(c.cellCount, 0);
            java.util.Arrays.fill(c.kindCalls, 0L);
            java.util.Arrays.fill(c.editCalls, 0L);
            java.util.Arrays.fill(c.moveCalls, 0L);
            java.util.Arrays.fill(c.readsPerPop, 0L);
            java.util.Arrays.fill(c.distinctPerPop, 0L);
            java.util.Arrays.fill(c.repeatDepth, 0L);
            c.outOfRangeCalls = 0;
            c.pops = c.popsWithEdits = c.totalCalls = c.totalDistinct = c.searches = 0;
            c.nTouched = 0;
            c.inPop = false;
        }
    }

    /** Total pops recorded so far, across every planner thread (for a one-line status reply). */
    public static long pops() {
        long n = 0;
        for (Census c : ALL) n += c.pops;
        return n;
    }

    /**
     * Merge every thread's counters and write the report to {@code file}. Returns a one-line summary for
     * command feedback. The merge is a plain read of live per-thread arrays — a planner thread mid-search
     * can skew the last few counts, which is immaterial at the sample sizes this instrument is used at.
     */
    public static String dump(java.io.File file) {
        // Broad catch on purpose: this is a diagnostic invoked from a command on the server thread, and a
        // formatting or arithmetic slip in the (cold, run-once) report must never take the server down
        // mid-session. The counting hooks above are the code that has to be right; this is reporting.
        try {
            Census m = merge();
            StringBuilder sb = new StringBuilder(1 << 16);
            writeReport(sb, m);
            try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
                w.write(sb.toString());
            }
            double perPop = m.pops == 0 ? 0 : (double) m.totalCalls / m.pops;
            double amp = m.totalDistinct == 0 ? 0 : (double) m.totalCalls / m.totalDistinct;
            return String.format("%d pops / %d reads (%.1f per pop, %.2fx amplification) -> %s",
                    m.pops, m.totalCalls, perPop, amp, file.getName());
        } catch (Throwable t) {
            return "census dump FAILED: " + t;
        }
    }

    private static Census merge() {
        Census m = new Census();
        for (Census c : ALL) {
            for (int i = 0; i < NOFF; i++) {
                m.offCalls[i] += c.offCalls[i];
                m.offPops[i] += c.offPops[i];
            }
            for (int i = 0; i < NKIND; i++) m.kindCalls[i] += c.kindCalls[i];
            for (int i = 0; i < NEDIT; i++) m.editCalls[i] += c.editCalls[i];
            for (int i = 0; i < NMOVE; i++) m.moveCalls[i] += c.moveCalls[i];
            for (int i = 0; i < POP_HIST; i++) {
                m.readsPerPop[i] += c.readsPerPop[i];
                m.distinctPerPop[i] += c.distinctPerPop[i];
            }
            for (int i = 0; i < REPEAT_HIST; i++) m.repeatDepth[i] += c.repeatDepth[i];
            m.outOfRangeCalls += c.outOfRangeCalls;
            m.pops += c.pops;
            m.popsWithEdits += c.popsWithEdits;
            m.totalCalls += c.totalCalls;
            m.totalDistinct += c.totalDistinct;
            m.searches += c.searches;
        }
        return m;
    }

    private static void writeReport(StringBuilder sb, Census m) {
        long pops = Math.max(m.pops, 1);
        sb.append("# Orebit NavGrid read census\n");
        sb.append("# Counts only -- timings from a census run are meaningless (see ReadCensus javadoc).\n");
        sb.append("# Hooks: MovementContext's six primitive readers. Bulk seams (sectionRawAt/depthRawAt) excluded.\n\n");

        sb.append("searches            : ").append(m.searches).append('\n');
        sb.append("pops                : ").append(m.pops).append('\n');
        sb.append("pops with edits     : ").append(m.popsWithEdits)
          .append(pct(m.popsWithEdits, pops)).append('\n');
        sb.append("read calls          : ").append(m.totalCalls).append('\n');
        sb.append("distinct cell reads : ").append(m.totalDistinct).append('\n');
        sb.append(String.format("calls / pop         : %.2f%n", (double) m.totalCalls / pops));
        sb.append(String.format("distinct / pop      : %.2f%n", (double) m.totalDistinct / pops));
        sb.append(String.format("amplification       : %.2fx   (inventory upper envelope: 3.52x)%n",
                m.totalDistinct == 0 ? 0 : (double) m.totalCalls / m.totalDistinct));
        sb.append("out-of-range calls  : ").append(m.outOfRangeCalls)
          .append(pct(m.outOfRangeCalls, Math.max(m.totalCalls, 1))).append('\n');

        // --- the repeat tax: the ceiling on what read-once can recover -----------------------------
        sb.append("\n## Repeat tax (share of reads that are the Nth touch of a cell already read this pop)\n");
        long firstTouch = 0, repeatTouch = 0;
        for (int i = 0; i < REPEAT_HIST; i++) {
            long cells = m.repeatDepth[i];
            if (cells == 0) continue;
            int depth = i + 1;
            firstTouch += cells;
            repeatTouch += cells * (long) (depth - 1);
        }
        sb.append("first touches       : ").append(firstTouch).append('\n');
        sb.append("repeat touches      : ").append(repeatTouch)
          .append(pct(repeatTouch, Math.max(m.totalCalls, 1)))
          .append("   <-- the read-once ceiling\n");
        sb.append("\ncells read exactly N times in one pop:\n");
        for (int i = 0; i < REPEAT_HIST; i++) {
            if (m.repeatDepth[i] == 0) continue;
            sb.append(String.format("  x%-3s %12d%s%n",
                    (i == REPEAT_HIST - 1 ? (i + 1) + "+" : String.valueOf(i + 1)),
                    m.repeatDepth[i], pct(m.repeatDepth[i], Math.max(firstTouch, 1))));
        }

        // --- distributions -------------------------------------------------------------------------
        sb.append("\n## Reads per pop\n");
        appendPercentiles(sb, m.readsPerPop, pops);
        sb.append("\n## Distinct cells per pop\n");
        appendPercentiles(sb, m.distinctPerPop, pops);

        // --- accessor + edit-layer breakdown -------------------------------------------------------
        sb.append("\n## By accessor\n");
        for (int i = 0; i < NKIND; i++) {
            sb.append(String.format("  %-14s %12d%s  %6.2f/pop%n", KIND_NAMES[i], m.kindCalls[i],
                    pct(m.kindCalls[i], Math.max(m.totalCalls, 1)), (double) m.kindCalls[i] / pops));
        }

        sb.append("\n## Path-edit layer (what kindAt actually did, per read)\n");
        for (int i = 0; i < NEDIT; i++) {
            sb.append(String.format("  %-20s %12d%s%n", EDIT_NAMES[i], m.editCalls[i],
                    pct(m.editCalls[i], Math.max(m.totalCalls, 1))));
        }

        // --- per-movement --------------------------------------------------------------------------
        sb.append("\n## By movement (attribution of every read to the movement that issued it)\n");
        sb.append(String.format("  %-22s %12s %8s %9s%n", "movement", "calls", "share", "per pop"));
        sb.append(String.format("  %-22s %12d %7s %9.2f%n", "<prologue>", m.moveCalls[0],
                pctBare(m.moveCalls[0], Math.max(m.totalCalls, 1)), (double) m.moveCalls[0] / pops));
        java.util.List<Movement> tier1 = MovementRegistry.TIER1;
        Integer[] order = new Integer[tier1.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(m.moveCalls[b + 1], m.moveCalls[a + 1]));
        for (int idx : order) {
            long n = m.moveCalls[idx + 1];
            sb.append(String.format("  %-22s %12d %7s %9.2f%n", tier1.get(idx).getClass().getSimpleName(),
                    n, pctBare(n, Math.max(m.totalCalls, 1)), (double) n / pops));
        }

        // --- prism scoring: the sizing answer -------------------------------------------------------
        sb.append("\n## Prefetch-envelope scoring\n");
        sb.append("# 'calls covered' = share of all read calls that land inside the envelope (what a prism could serve).\n");
        sb.append("# 'fill' = cells filled per pop if filled eagerly; 'saved' = calls served per pop.\n");
        sb.append("# An envelope only pays when saved > fill, and the margin has to beat the accessor cost it replaces.\n");
        sb.append(String.format("  %-12s %6s %10s %10s %10s %10s%n",
                "envelope", "cells", "covered", "fill/pop", "saved/pop", "ratio"));
        for (int[] p : PRISMS) {
            int rx = p[0], yLo = p[1], yHi = p[2], rz = p[3];
            long covered = 0;
            int cells = 0;
            for (int dy = yLo; dy <= yHi; dy++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    for (int dx = -rx; dx <= rx; dx++) {
                        cells++;
                        int b = bucket(dx, dy, dz);
                        if (b >= 0) covered += m.offCalls[b];
                    }
                }
            }
            double savedPerPop = (double) covered / pops;
            String name = (2 * rx + 1) + "x" + (yHi - yLo + 1) + "x" + (2 * rz + 1)
                    + "@" + yLo;
            sb.append(String.format("  %-12s %6d %9s %10d %10.2f %10.2f%n", name, cells,
                    pctBare(covered, Math.max(m.totalCalls, 1)), cells, savedPerPop,
                    savedPerPop / cells));
        }

        // --- per-offset table ----------------------------------------------------------------------
        sb.append("\n## Per-offset reads (sorted by call count; popCover% = share of pops touching it at all)\n");
        sb.append("# High calls + high popCover -> prefetch candidate.\n");
        sb.append("# High calls + LOW popCover  -> memo candidate, not prefetch (the fill is wasted on most pops).\n");
        sb.append(String.format("  %4s %4s %4s %12s %9s %10s %9s%n",
                "dx", "dy", "dz", "calls", "per pop", "popCover%", "per touch"));
        Integer[] offs = topOffsets(m, 120);
        for (Integer b : offs) {
            int rem = b;
            int dx = rem % NX + DX_MIN;
            rem /= NX;
            int dz = rem % NZ + DZ_MIN;
            int dy = rem / NZ + DY_MIN;
            long calls = m.offCalls[b];
            long cover = m.offPops[b];
            sb.append(String.format("  %4d %4d %4d %12d %9.3f %9s %9.2f%n", dx, dy, dz, calls,
                    (double) calls / pops, pctBare(cover, pops),
                    cover == 0 ? 0.0 : (double) calls / cover));
        }
    }

    private static Integer[] topOffsets(Census m, int limit) {
        java.util.List<Integer> nz = new java.util.ArrayList<>();
        for (int i = 0; i < NOFF; i++) if (m.offCalls[i] != 0) nz.add(i);
        nz.sort((a, b) -> Long.compare(m.offCalls[b], m.offCalls[a]));
        return nz.subList(0, Math.min(limit, nz.size())).toArray(new Integer[0]);
    }

    private static void appendPercentiles(StringBuilder sb, long[] hist, long total) {
        long[] marks = { total / 2, total * 9 / 10, total * 99 / 100 };
        String[] names = { "p50", "p90", "p99" };
        long seen = 0;
        int mi = 0;
        int max = 0;
        double sum = 0;
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] != 0) max = i;
            sum += (double) hist[i] * i;
        }
        for (int i = 0; i < hist.length && mi < marks.length; i++) {
            seen += hist[i];
            while (mi < marks.length && seen >= marks[mi]) {
                sb.append("  ").append(names[mi]).append(" = ").append(i).append('\n');
                mi++;
            }
        }
        sb.append("  max = ").append(max).append('\n');
        sb.append(String.format("  mean = %.2f%n", total == 0 ? 0 : sum / total));
        // Early-out shape: what fraction of pops are cheap. Directly bounds the waste of an eager prism fill.
        long cheap = 0;
        for (int i = 0; i <= Math.min(9, hist.length - 1); i++) cheap += hist[i];
        sb.append("  pops with <=9 : ").append(cheap).append(pct(cheap, Math.max(total, 1))).append('\n');
    }

    private static String pct(long n, long d) {
        return String.format("  (%.1f%%)", d == 0 ? 0.0 : 100.0 * n / d);
    }

    private static String pctBare(long n, long d) {
        return String.format("%.1f%%", d == 0 ? 0.0 : 100.0 * n / d);
    }
}
