package com.orebit.mod.pathfinding.blockpathfinder.movements;

/**
 * ParkourEnvelope — the DERIVED gap-jump admission table the {@link Parkour} and {@link DiagonalParkour}
 * moves read to decide, per takeoff condition, the largest gap {@code g} each landing class may offer.
 * It replaces the old hand-tuned per-move constants ({@code Parkour.RISE_MAX}, {@code Parkour.FALL_MAX},
 * {@code DiagonalParkour.MAX_GAP}) with values computed at class-load from closed-form Minecraft
 * ballistics — the exact model validated in {@code internal_docs/parkour_envelope_params.py} (which
 * supersedes the prose envelope table in {@code internal_docs/NOTES-movement-physics.md} §1–§2). No Minecraft
 * imports, so its static init runs in safe order (pure arithmetic) and works headless.
 *
 * <h2>The model (ported verbatim from {@code parkour_envelope_params.py})</h2>
 * A jump is admitted for gap {@code g} iff the horizontal reach the bot can achieve within the airtime
 * that keeps its feet at/above the landing surface covers the required centre-to-centre travel, subject
 * to a policy cap on how much open air a single jump may clear.
 *
 * <ul>
 *   <li><b>Vertical arc.</b> Feet height above the takeoff floor after {@code T} air ticks is the
 *       geometric series {@code y(T) = (vy0+K)·(1−QV^T)/(1−QV) − K·T}, with jump velocity
 *       {@code vy0 = 0.42·occV}, gravity/drag terminal {@code K = G·QV/(1−QV) = 3.92}, {@code QV = 0.98}.
 *       Apex is {@code y(6) ≈ 1.2522} blocks ({@code ×16 ≈ 20} sixteenths — this is where
 *       {@link com.orebit.mod.pathfinding.blockpathfinder.MovementContext#JUMP_RISE} comes from). The
 *       admitted airtime {@code T(Δy)} is the LAST tick whose feet are still {@code ≥ Δy} (the rising arc
 *       dips below +1 then re-crosses, so it is a max over the arc, not a first-failure walk).</li>
 *   <li><b>Horizontal reach.</b> Cumulative centre travel {@code X(T)} accumulates the jump-tick boost
 *       ({@code +0.2}) and sprint input, with ground drag {@code QG = 0.6·0.91} scaled by the floor's
 *       speed factor {@code gsf} on the takeoff tick (soul sand {@code 0.4}) and air drag {@code QH = 0.91}
 *       thereafter; the whole horizontal budget is scaled by a body-cell stuck multiplier {@code occH}
 *       (sweet berry bush {@code 0.8}) for a jump launched from inside a slow block.</li>
 *   <li><b>Geometry.</b> A cardinal {@code g}-gap requires centre travel {@code g + 0.2 − 0.35} (the
 *       {@code +0.35} conservative takeoff trigger); a diagonal {@code (g+1)·√2 − 0.5·√2 − 0.3·√2 − 0.40}.</li>
 *   <li><b>Effective Δy.</b> Feet leave from surface height {@code takeoffSurfaceY} (full block 1.0, slab
 *       0.5, stair edge per {@link com.orebit.mod.pathfinding.blockpathfinder.MovementContext#directionalTopY})
 *       and land on a full-block top (1.0), so {@code effΔy = classΔy + (1.0 − takeoffSurfaceY)} — a lower
 *       takeoff makes even a flat jump behave like a small rise, shrinking its reach.</li>
 *   <li><b>Policy cap {@link #MAX_CLEARED_AIR}.</b> A single jump clears at most {@code 3.0} blocks of
 *       open air for Δy≥0 (plus the drop for a falling jump). This is the design ceiling on top of the
 *       physics — it is why flat caps at 3 even where the raw reach could stretch further.</li>
 * </ul>
 *
 * <h2>Parameterization &amp; the no-help clamp</h2>
 * The baked table {@link #MAX_GAP} is indexed {@code [startTopY 1..16][gsfBucket 0..1][occBucket 0..1]}
 * and holds a six-outcome row {@code {flatMax, riseMax, fall1Max, fall2Max, fall3Max, diagMax}} (see the
 * {@link #FLAT}…{@link #DIAG} indices). {@code startTopY} is the takeoff surface in sixteenths;
 * {@code gsfBucket} 0 = normal floor, 1 = soul-sand-like slow floor ({@code speedFactor 0.4}); {@code
 * occBucket} 0 = no slow body cell, 1 = a LIGHT through-slow body cell (sweet berry bush). Each occupied
 * row is CLAMPED to its own {@code (surface, gsf)} occ=none ceiling — a slow body cell can only ever
 * REDUCE reach, never fabricate it (powder snow's {@code occV 1.5} would otherwise invent airtime a
 * zeroed-velocity block never actually gives; powder therefore folds onto the occ=none row, and any LIGHT
 * body cell the wiring detects is priced with the same-or-tighter berry row — the safe direction). Honey
 * floors (reduced jump) and cobweb body cells (killed takeoff velocity) never reach this table — they are
 * refused earlier by the moves' {@code reducesJump}/{@code noJumpFromBody} gates.
 *
 * <p>Class-load cost is a few hundred formula evaluations (a ≤25-tick integer scan per Δy over 16×2×2
 * cells) — nanoseconds, well before {@code NavWarmup}. The physics constants and closed forms are
 * package-private so {@code ParkourEnvelopeTest} can assert the margins directly; the static init contains
 * NO hard-coded maxima (they are derived — the expected values are pinned in the test).
 */
public final class ParkourEnvelope {

    private ParkourEnvelope() { }

    // ---- Row indices into a MAX_GAP entry --------------------------------------------------------
    /** Flat (same-level) landing max gap. */
    public static final int FLAT = 0;
    /** Rising (+1) landing max gap. */
    public static final int RISE = 1;
    /** Falling (−1) landing max gap. */
    public static final int FALL1 = 2;
    /** Falling (−2) landing max gap. */
    public static final int FALL2 = 3;
    /** Falling (−3) landing max gap. */
    public static final int FALL3 = 4;
    /** Diagonal (same-level) landing max gap. */
    public static final int DIAG = 5;

    // ---- Verified MC constants (1.21.11 mojang-mapped; parkour_envelope_params.py header) --------
    private static final double G = 0.08;                 // gravity per tick
    private static final double QV = 0.98;                // vertical drag
    private static final double QH = 0.91;                // horizontal air drag
    private static final double QG = 0.6 * 0.91;          // 0.546 ground drag
    private static final double V0Y = 0.42;               // JUMP_STRENGTH (blockJumpFactor 1.0)
    private static final double BOOST = 0.2;              // sprint jump-tick horizontal boost
    private static final double SPEED = 0.1 * 1.3;        // 0.13 sprint move speed
    private static final double INPUT = 0.98;
    /** Ground input accel/tick = 0.127400. */
    private static final double A_G = SPEED * (0.21600002 / (0.6 * 0.6 * 0.6)) * INPUT;
    /** Air input accel/tick = 0.025480. */
    private static final double A_A = 0.025999999 * INPUT;
    /** Cardinal takeoff trigger, blocks past cell centre. */
    private static final double TAKEOFF_EDGE = 0.35;
    /** Diagonal takeoff trigger, along-line. */
    private static final double TAKEOFF_EDGE_ALONG = 0.40;
    private static final double S2 = Math.sqrt(2.0);
    /** Terminal-velocity constant {@code G·QV/(1−QV) = 3.92}. */
    private static final double K = G * QV / (1.0 - QV);

    /** Policy cap: a single jump clears at most this many blocks of open air (Δy≥0; +drop for a fall). */
    public static final double MAX_CLEARED_AIR = 3.0;

    /**
     * The baked admission table: {@code MAX_GAP[startTopY][gsfBucket][occBucket]} is a six-outcome row
     * {@code {flatMax, riseMax, fall1Max, fall2Max, fall3Max, diagMax}}. {@code startTopY} indexes 1..16
     * (entry 0 is a copy of 1, so a clamped-to-1 index is safe). Derived, never hand-written.
     */
    static final int[][][][] MAX_GAP;

    /**
     * Clamp a takeoff surface height (sixteenths) into the {@link #MAX_GAP} row index range 1..16 — a
     * standable floor's collision top is 1..16, but guard defensively so an odd descriptor can never
     * AIOOBE the table lookup on the hot path.
     */
    public static int index(int topY) {
        return topY < 1 ? 1 : (topY > 16 ? 16 : topY);
    }

    // ---- Closed forms (package-private so the test asserts margins) ------------------------------

    /** Feet height (blocks) above the takeoff floor after {@code T} air ticks; {@code vy(1) = 0.42·occV}. */
    static double y(int T, double occV) {
        double v0 = V0Y * occV;
        return (v0 + K) * (1.0 - Math.pow(QV, T)) / (1.0 - QV) - K * T;
    }

    /** The LAST air tick whose feet are still {@code ≥ dy} (max over the whole arc), or 0 if never. */
    static int tForDy(double dy, double occV) {
        int best = 0;
        for (int t = 1; t < 80; t++) {
            if (y(t, occV) >= dy) best = t;
        }
        return best;
    }

    /** TERMINAL sprint speed ({@code A_G/(1−QG·gsf)}) — what this table USED to assume, kept for
     *  {@link #vRunup} to be measured against and for the reach report. */
    static double vInf(double gsf) {
        return A_G / (1.0 - QG * gsf);
    }

    /** One tick of grounded sprint from {@code v}: the stored velocity drags on the floor, then the tick's
     *  input accelerates. {@link #vInf} is this recurrence's fixed point. Exposed so a SHORT run-up can be
     *  integrated against the same physics the table is baked from, rather than a hand-copy of it. */
    static double vGroundStep(double v, double gsf) {
        return v * QG * gsf + A_G;
    }

    /**
     * The run-up the follower can actually GUARANTEE, in blocks (owner ruling 2026-08-14).
     *
     * <p>The table used to bake in {@link #vInf} — terminal sprint speed, i.e. an unbounded run-up — and
     * nothing ever delivered that. The planner's own contract is centre-to-centre: a move puts the bot in
     * the MIDDLE of its cell, so a jump that follows a Fall/Descend/Traverse starts its run-up from there,
     * not from some prior straightaway. On a BOTTOM stair crossed toward its low half the bound is exactly
     * this too: the bot may sit at {@code 0.30} (the furthest back a 0.6-wide box fits without overhanging
     * a neighbour that may be fire, lava or solid) and must launch by {@code 0.80} (the last tick the box
     * still overlaps the tall half), which is {@code 0.50} of run-up.
     *
     * <p><b>It costs almost nothing</b>, because MC ground acceleration is steep and the jump tick is
     * dominated by {@code BOOST + A_G}: 0.5 blocks from a standstill reaches 83.7% of terminal ground speed
     * but <b>94.8%</b> of the terminal jump-tick speed. The only admission that changes is {@code fall-1},
     * 4 → 3 (measured in {@code StairRunupReachTest}). What it buys is that the emitted envelope is one the
     * follower can actually realise from a standing start, instead of one that assumed a run-up the geometry
     * never provides.
     */
    static final double RUNUP_BLOCKS = 0.5;

    /** Ground speed after accelerating from REST across {@link #RUNUP_BLOCKS}, integrated with the same
     *  {@link #vGroundStep} recurrence {@link #vInf} is the fixed point of. Cold — static-init only. */
    static double vRunup(double gsf) {
        double v = 0.0;
        double travelled = 0.0;
        for (int t = 0; t < 200 && travelled < RUNUP_BLOCKS; t++) {
            v = vGroundStep(v, gsf);
            travelled += v;
        }
        return v;
    }

    private static double vJump(double gsf) {
        return vJumpFrom(vRunup(gsf), gsf);          // a run-up the follower can actually deliver
    }

    /** Jump-tick speed reached from an arbitrary incoming ground speed (incoming velocity drags on the
     *  floor, then the sprint boost and this tick's input are added). */
    static double vJumpFrom(double vIn, double gsf) {
        return vIn * QG * gsf + BOOST + A_G;
    }

    /** Cumulative centre travel after {@code T} ticks (tick 1 = jump); {@code occH} scales the whole budget. */
    static double X(int T, double gsf, double occH) {
        return xFrom(T, vJump(gsf), gsf, occH);
    }

    /** {@link #X} parameterised on the jump-tick speed, so a run-up shorter than the assumed one is priced
     *  through the IDENTICAL arc integrator. {@code X} delegates here, so production stays byte-identical. */
    static double xFrom(int T, double vJump0, double gsf, double occH) {
        if (T < 1) return 0.0;
        double m = A_A / (1.0 - QH);
        double vPost0 = vJump0 * QG * gsf + A_A;      // jump-tick trailing drag (still on the slow floor)
        double base = vJump0 + (T - 1) * m
                + (vPost0 - m) * (1.0 - Math.pow(QH, T - 1)) / (1.0 - QH);
        return occH * base;
    }

    /** Required centre travel for a cardinal {@code g}-gap ({@code = g − 0.15}). */
    static double dReqCard(int g) {
        return g + 0.2 - TAKEOFF_EDGE;
    }

    /** Required centre travel for a diagonal {@code g}-gap ({@code = (g+0.2)·√2 − 0.40}). */
    static double dReqDiag(int g) {
        return (g + 1) * S2 - 0.5 * S2 - 0.3 * S2 - TAKEOFF_EDGE_ALONG;
    }

    /** Effective Δy: node class offset folded with the takeoff surface deficit (landing assumed full block). */
    static double effDy(int classDy, double takeoffSurfaceY) {
        return classDy + (1.0 - takeoffSurfaceY);
    }

    /**
     * The admitted maximum gap for one landing class: the largest {@code g} whose required travel fits the
     * reach budget AND whose cleared air stays within {@link #MAX_CLEARED_AIR} (+ the drop for a fall).
     */
    static int maxGap(int classDy, boolean diagonal, double takeoffSurfaceY,
            double gsf, double occH, double occV) {
        double edy = effDy(classDy, takeoffSurfaceY);
        int t = tForDy(edy, occV);
        double budget = X(t, gsf, occH);
        int drop = classDy < 0 ? -classDy : 0;
        double capAir = MAX_CLEARED_AIR + drop;
        int gmax = 0;
        for (int g = 1; g <= 8; g++) {
            double cleared = diagonal ? g * S2 : g;
            if (cleared > capAir + 1e-9) break;
            double dreq = diagonal ? dReqDiag(g) : dReqCard(g);
            if (dreq <= budget + 1e-9) gmax = g;
            else break;
        }
        return gmax;
    }

    /** One six-outcome row {@code {flat, rise, fall1, fall2, fall3, diag}} for a full condition tuple. */
    private static int[] row(double surfY, double gsf, double occH, double occV) {
        return new int[] {
                maxGap(0, false, surfY, gsf, occH, occV),
                maxGap(1, false, surfY, gsf, occH, occV),
                maxGap(-1, false, surfY, gsf, occH, occV),
                maxGap(-2, false, surfY, gsf, occH, occV),
                maxGap(-3, false, surfY, gsf, occH, occV),
                maxGap(0, true, surfY, gsf, occH, occV),
        };
    }

    static {
        final double[] gsfs = {1.0, 0.4};                 // bucket 0 normal, 1 soul-sand-like
        final double[] occHs = {1.0, 0.8};                // bucket 0 none,   1 berry-light
        final double[] occVs = {1.0, 0.75};
        int[][][][] tbl = new int[17][2][2][];
        for (int topY = 1; topY <= 16; topY++) {
            double surfY = topY / 16.0;
            for (int gb = 0; gb < 2; gb++) {
                int[] ceil = row(surfY, gsfs[gb], 1.0, 1.0); // the occ=none ceiling for this surface+gsf
                for (int ob = 0; ob < 2; ob++) {
                    int[] raw = row(surfY, gsfs[gb], occHs[ob], occVs[ob]);
                    int[] clamped = new int[6];
                    for (int k = 0; k < 6; k++) {
                        clamped[k] = Math.min(raw[k], ceil[k]); // no-help clamp: slow body only REDUCES reach
                    }
                    tbl[topY][gb][ob] = clamped;
                }
            }
        }
        tbl[0] = tbl[1];                                    // clamped-to-1 index safety (never queried)
        MAX_GAP = tbl;
    }
}
