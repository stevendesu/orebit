package com.orebit.mod.worldmodel.hpa;

/**
 * 4-bit log-scale cost quantize/dequantize for the HPA* region tier
 * (PRD §6.5; HPA-IMPLEMENTATION.md §3, "3b/3c storage").
 *
 * <p>A node's six face→center costs (PRD §6.5: we store the half-traversal from a face to the node
 * center, never an edge) are persisted as a <b>nibble</b> each — 15 real buckets ({@code 0..14}) plus an
 * INF sentinel ({@link #BUCKET_INF} = 15) for "effectively impassable" (void / out of world). Six nibbles
 * = 3 bytes per node on disk; in RAM the pyramid keeps one {@code byte} per face (favour-cpu-over-ram —
 * the nibble packing is only the on-disk form, §4/§11).
 *
 * <p>The scale is logarithmic so a single nibble spans a wide tick range: bucket 0 ≈
 * {@link #BASE_TICKS} (1 tick), each bucket multiplies by {@code 2^LOG_STEP}, so buckets {@code 0..14}
 * span ≈ {@code 2^(14·0.7)} ≈ {@code 2^9.8} ≈ 900×. {@link #LOG_STEP} is the <b>fidelity knob</b> — smaller
 * = finer cost resolution but a narrower representable range; larger = wider range, coarser steps.
 *
 * <p>The region A* works in <b>dequantized ticks</b> (real {@code float} cost); the bucket is purely the
 * compact storage form. {@link #COST_INF} is a large finite cap (NOT {@link Float#POSITIVE_INFINITY}) so
 * A* arithmetic — {@code g + edge}, heuristic comparisons — stays finite and well-ordered.
 *
 * <p>Pure static math, no allocation, no MC API.
 */
public final class CostCodec {

    /** The INF bucket — "effectively impassable" (void / out of world); reserved, never emitted by a leaf. */
    public static final int BUCKET_INF = 15;

    /** Bucket 0 ≈ 1 tick. */
    public static final float BASE_TICKS = 1.0f;

    /** ×2^{@code LOG_STEP} per bucket; the fidelity knob (buckets 0..14 span ~2^9.8 ≈ 900×). */
    public static final float LOG_STEP = 0.7f;

    /** Dequantized INF — a large finite cap (NOT {@code Float.INFINITY}) so A* arithmetic stays finite. */
    public static final float COST_INF = 1.0e6f;

    /** {@code log(2)} cached for the per-bucket conversion. */
    private static final double LN2 = Math.log(2);

    private CostCodec() {}

    /**
     * Quantize a tick cost to a bucket {@code 0..14}. {@code ticks <= BASE_TICKS} (incl. {@code <= 0})
     * → 0; otherwise the nearest log-scale bucket, clamped to {@code 14} (never emits {@link #BUCKET_INF};
     * INF is reserved for void/out-of-world nodes the planner shouldn't enter).
     */
    public static int quantize(float ticks) {
        if (ticks <= BASE_TICKS) return 0;
        int b = Math.round((float) (Math.log(ticks / BASE_TICKS) / LN2) / LOG_STEP);
        return Math.max(0, Math.min(14, b));
    }

    /**
     * Dequantize a bucket to its representative tick cost. {@code bucket >= BUCKET_INF} → {@link #COST_INF};
     * otherwise {@code BASE_TICKS · 2^(bucket·LOG_STEP)}.
     */
    public static float dequantize(int bucket) {
        if (bucket >= BUCKET_INF) return COST_INF;
        return (float) (BASE_TICKS * Math.pow(2, bucket * LOG_STEP));
    }
}
