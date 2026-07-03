package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the salvaged log₂ histogram codec {@link Log2Codec}. These need <b>no Minecraft</b>
 * — pure integer math. They pin the bucketing invariants the resource pyramid roll-up depends on:
 * monotone encode, encode/decode round-trip within a bucket, and an associative + commutative
 * {@link Log2Codec#merge} that approximates {@code encode(a+b)} within one bucket.
 *
 * <p>NOTE on {@code merge(x, 0-encoding) == x}: this is tested only for a <i>non-zero</i> {@code x}
 * (a populated region + an empty child = the region unchanged — the meaningful roll-up case). The
 * original {@code mergeLogCounts} returns {@code 1} for {@code merge(0,0)} (equal buckets bump up),
 * a latent quirk of the exact salvaged formula; that degenerate case is intentionally not asserted.
 */
public class Log2CodecTest {

    @Test
    void encodeZeroIsZero() {
        assertEquals(0, Log2Codec.encode(0), "no blocks encodes to 0");
    }

    @Test
    void encodeKnownBuckets() {
        // From the original RegionMetadata comment: 1=1, 2=2-3, 3=4-7, 4=8-15, ...
        assertEquals(1, Log2Codec.encode(1));
        assertEquals(2, Log2Codec.encode(2));
        assertEquals(2, Log2Codec.encode(3));
        assertEquals(3, Log2Codec.encode(4));
        assertEquals(3, Log2Codec.encode(7));
        assertEquals(4, Log2Codec.encode(8));
        assertEquals(4, Log2Codec.encode(15));
        assertEquals(5, Log2Codec.encode(16));
    }

    @Test
    void encodeIsMonotoneNonDecreasing() {
        int prev = Log2Codec.encode(0);
        for (int n = 1; n <= 4096; n++) {
            int cur = Log2Codec.encode(n) & 0xFF;
            assertTrue(cur >= prev, "encode must be non-decreasing at n=" + n + " (" + cur + " < " + prev + ")");
            prev = cur;
        }
    }

    @Test
    void encodeDecodeRoundTripsWithinBucket() {
        for (int n = 0; n <= 4096; n++) {
            byte e = Log2Codec.encode(n);
            int rep = Log2Codec.decode(e);
            // decode(encode(n)) lands in the same bucket as n (its encode matches).
            assertEquals(e, Log2Codec.encode(rep),
                    "decode(encode(" + n + "))=" + rep + " must re-encode to the same bucket");
            if (n == 0) {
                assertEquals(0, rep, "zero decodes to zero");
            } else {
                assertTrue(rep >= 1 && rep <= n, "representative " + rep + " must be in (0.." + n + "]");
            }
        }
    }

    @Test
    void decodeEncodeRoundTripsExactly() {
        // encode(decode(e)) == e for every reachable bucket (representative is exactly 2^(e-1)).
        for (int e = 0; e <= 20; e++) {
            byte enc = (byte) e;
            int rep = Log2Codec.decode(enc);
            assertEquals(e, Log2Codec.encode(rep) & 0xFF, "encode(decode(" + e + ")) must be " + e);
        }
    }

    @Test
    void mergeIsCommutative() {
        for (int a = 0; a <= 20; a++) {
            for (int b = 0; b <= 20; b++) {
                assertEquals(Log2Codec.merge((byte) a, (byte) b),
                             Log2Codec.merge((byte) b, (byte) a),
                             "merge must be commutative for (" + a + "," + b + ")");
            }
        }
    }

    @Test
    void mergeApproximatesEncodeOfSum() {
        int[] samples = { 1, 2, 3, 4, 7, 8, 15, 16, 31, 100, 500, 1000, 4096 };
        for (int a : samples) {
            for (int b : samples) {
                int merged = Log2Codec.merge(Log2Codec.encode(a), Log2Codec.encode(b)) & 0xFF;
                int direct = Log2Codec.encode(a + b) & 0xFF;
                assertTrue(Math.abs(merged - direct) <= 1,
                        "merge(encode(" + a + "),encode(" + b + "))=" + merged
                        + " must be within one bucket of encode(" + (a + b) + ")=" + direct);
            }
        }
    }

    @Test
    void mergeWithEmptyLeavesNonZeroUnchanged() {
        byte zero = Log2Codec.encode(0);
        for (int a = 1; a <= 20; a++) {
            byte x = (byte) a;
            assertEquals(x, Log2Codec.merge(x, zero), "merge(x, 0-encoding) == x for x=" + a);
            assertEquals(x, Log2Codec.merge(zero, x), "merge(0-encoding, x) == x for x=" + a);
        }
    }
}
