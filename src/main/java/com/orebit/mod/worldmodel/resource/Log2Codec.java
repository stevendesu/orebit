package com.orebit.mod.worldmodel.resource;

/**
 * The log₂ histogram codec salvaged from the deleted {@code region.RegionMetadata}
 * (find-mine-resources design, phase 2). A block count is stored as an approximate log₂ so a whole
 * region histogram fits in one byte per class and rolls up associatively up the pyramid.
 *
 * <p>Bucketing (from the original {@code RegionMetadata} comment): an encoded value {@code e ≥ 1}
 * represents the count range {@code [2^(e-1), 2^e − 1]} — {@code 1}=1 block, {@code 2}=2–3,
 * {@code 3}=4–7, {@code 4}=8–15, … — and {@code 0} means "none present".
 *
 * <p>Pure static, no state. The formulas are copied verbatim from {@code RegionMetadata.encodeLog2}
 * and {@code mergeLogCounts}; do not "simplify" them (the exact edge behaviour is relied upon).
 */
public final class Log2Codec {
    private Log2Codec() {}

    /**
     * Encodes a raw block count to its log₂ bucket. Exactly the original {@code encodeLog2}:
     * {@code 32 − numberOfLeadingZeros(count)} — i.e. the number of significant bits, which is
     * {@code floor(log₂(count)) + 1} for {@code count ≥ 1} and {@code 0} for {@code count == 0}.
     */
    public static byte encode(int count) {
        return (byte) (32 - Integer.numberOfLeadingZeros(count));
    }

    /**
     * Merges two encoded counts (the pyramid roll-up). The larger dominates, and two equal <i>non-zero</i>
     * buckets combine to the next bucket up ({@code log₂(x) + log₂(x) = log₂(2x)}). Commutative.
     *
     * <p><b>0 is the additive identity</b> (fixed in phase 3): {@code merge(0,x) == merge(x,0) == x} and
     * {@code merge(0,0) == 0}. The original {@code mergeLogCounts} returned {@code 1} for {@code merge(0,0)}
     * (its equal-bucket bump fired on two zeros), which would inject phantom counts into the pyramid roll-up
     * — a parent whose children are all empty in some column would read as "1 block present". Guarding the
     * zero cases keeps a summed-count roll-up honest: an all-empty column stays empty at every ancestor.
     */
    public static byte merge(byte a, byte b) {
        int ua = a & 0xFF;
        int ub = b & 0xFF;

        if (ua == 0) return b; // 0 is the additive identity (no phantom counts in the roll-up)
        if (ub == 0) return a;
        if (ua > ub) return a;
        if (ub > ua) return b;
        return (byte) (ua + 1); // log₂(x) + log₂(x) = log₂(2x); both > 0 here
    }

    /**
     * Decodes an encoded bucket to a representative count — the low end of the bucket
     * ({@code 2^(e-1)} for {@code e ≥ 1}, {@code 0} for {@code e == 0}). This is the inverse that
     * round-trips within a bucket: {@code encode(decode(e)) == e} and {@code decode(encode(n))} lands
     * in {@code n}'s bucket. Used for the query threshold and quota display.
     */
    public static int decode(byte encoded) {
        int e = encoded & 0xFF;
        if (e <= 0) return 0;
        return 1 << (e - 1);
    }
}
