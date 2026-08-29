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

    // ===================================================================================================
    // Fragment-model region record (un)packing (HPA-FRAGMENTS.md §5; DESIGN-typed-fragments.md §5.3–§5.4)
    // ===================================================================================================
    //
    // A region record is variable-length and NOT byte-aligned — a sub-byte bitstream, exactly as the
    // center model's six nibble buckets were. The layout (information content, MSB-first per field):
    //
    //   kind             : 2 bits   (MIXED=0 | SOLID=1 | AIR=2 | WATER=3)
    //   avgSolidHardness : 4 bits
    //   --- uniform kinds (SOLID/AIR/WATER) STOP HERE: 6 bits total. A uniform record is TRULY uniform
    //       (typed-fragments §5.5 amended), so kind alone is exact — AIR implies dry, WATER implies
    //       all-water; no extra water bit. A uniform region carries no passFrac (its crossing cost is
    //       implied by kind — SOLID mines, AIR chutes, WATER swims), no count, no fragments; the in-RAM
    //       RegionFragments still exposes a passFrac field, but it is meaningful only for MIXED. ---
    //   passFrac         : 4 bits   (MIXED only — the collapsed/uniform-mass crossing cost)
    //   fragmentCount    : 6 bits   (MIXED only — 0 = honestly-ZERO kept fragments (coarse no-passable-item
    //                                mass, collapsed=false); 1..61 = exact count (was 1..62 before the
    //                                2026-08-28 MAX_FRAGMENTS 62→61 corner-crossing reduction — a LEGACY
    //                                over-cap count decodes as collapsed, see unpackRegion);
    //                                63 = RegionFragments.FRAGMENT_COUNT_COLLAPSED, the cap-collapsed sentinel
    //                                (collapsed=true). 0, 62-legacy and 63 carry no decoded fragment records;
    //                                cross-cost comes from passFrac. Depth-nibble precedent: 0..N exact, top
    //                                code = flag.)
    //   fragment[count] {
    //     faceMask       : 6 bits
    //     typeS          : 1 bit    (surfaceable — DESIGN-typed-fragments.md §2; the type bits sit after
    //     typeW          : 1 bit     faceMask and before the footprints; typeW = the fragment holds water)
    //     per set face f : 16 bits  (the packed footprint = minU<<12|maxU<<8|minV<<4|maxV, 4 bits each)
    //   }
    //
    // The on-disk form does NOT persist gridSize (it is a build-time/level attribute; footprints are stored
    // in face-relative 16-bucket units regardless of G — §5 roll-up note). The build-time collapsed-vs-
    // stripped distinction DOES survive the trip (the count-field sentinel above) — the old schema conflated
    // the two into count==0-reloads-as-collapsed, which broke round-trip identity (contentSignature drift on
    // reload of a stripped record). The packer is a cold disk path (persistence), not the A* read hot path,
    // so it may allocate.

    /**
     * Pack one region's fragment record into {@code buf} starting at bit offset {@code bitPos}, returning the
     * end bit offset (the next record's start). {@code buf} must be zero-initialized over the written span
     * (the writer only sets 1-bits). Sized via {@link #regionBitLength}.
     */
    public static int packRegion(RegionFragments rf, byte[] buf, int bitPos) {
        int p = bitPos;
        p = writeBits(buf, p, rf.kind(), 2);
        p = writeBits(buf, p, rf.avgSolidHardness(), 4);
        if (rf.kind() != RegionFragments.KIND_MIXED) {
            return p; // uniform — 6 bits, no passFrac / count / fragments (kind is exact, §5.5 amended)
        }
        p = writeBits(buf, p, rf.passFrac(), 4);
        // A cap-collapsed record (fragmentCount 0 in RAM, collapsed flag set) writes the count-field
        // sentinel; an honestly-zero record writes 0. Neither carries fragment records.
        int count = rf.isCollapsed() ? RegionFragments.FRAGMENT_COUNT_COLLAPSED : rf.fragmentCount();
        p = writeBits(buf, p, count, 6);
        if (count == RegionFragments.FRAGMENT_COUNT_COLLAPSED) {
            return p; // collapsed sentinel — no fragment records follow
        }
        for (int f = 0; f < count; f++) {
            int mask = rf.faceMask(f);
            p = writeBits(buf, p, mask, 6);
            p = writeBits(buf, p, rf.typeS(f) ? 1 : 0, 1); // type bits after faceMask, before footprints
            p = writeBits(buf, p, rf.typeW(f) ? 1 : 0, 1);
            for (int face = 0; face < 6; face++) {
                if ((mask & (1 << face)) != 0) {
                    p = writeBits(buf, p, rf.footprint(f, face) & 0xFFFF, 16);
                }
            }
        }
        return p;
    }

    /**
     * Unpack a region's fragment record from {@code buf} at bit offset {@code bitPos} into {@code out}
     * (reset at flood grid side {@code gridSize}), returning the end bit offset. The inverse of
     * {@link #packRegion}; a packed-then-unpacked record is field-identical for everything the schema
     * persists (kind, hardness, passFrac, count, per-fragment faceMask + type bits + footprints, and the
     * collapsed flag). A MIXED record whose on-wire count is {@link RegionFragments#FRAGMENT_COUNT_COLLAPSED} decodes
     * as {@code fragmentCount == 0} with {@link RegionFragments#isCollapsed() collapsed}{@code == true};
     * a count of {@code 0} decodes as honestly-zero ({@code collapsed == false}).
     */
    public static int unpackRegion(byte[] buf, int bitPos, int gridSize, RegionFragments out) {
        out.reset(gridSize);
        int p = bitPos;
        int kind = readBits(buf, p, 2); p += 2;
        int hardness = readBits(buf, p, 4); p += 4;
        out.setKind(kind);
        out.setAvgSolidHardness(hardness);
        if (kind != RegionFragments.KIND_MIXED) {
            return p; // uniform — no passFrac / count / fragments
        }
        int passFrac = readBits(buf, p, 4); p += 4;
        out.setPassFrac(passFrac);
        int count = readBits(buf, p, 6); p += 6;
        if (count == RegionFragments.FRAGMENT_COUNT_COLLAPSED) {
            out.setFragmentCount(0);   // the sentinel is a flag, not a count — no fragment records on the wire
            out.setCollapsed(true);    // cap-collapsed spongey mass; cross-cost from passFrac
            return p;
        }
        if (count > RegionFragments.MAX_FRAGMENTS) {
            // LEGACY over-cap count (2026-08-28, corner-crossing R21): a file written before the
            // MAX_FRAGMENTS 62 → 61 reduction can legitimately carry count == 62 (a then-at-cap region).
            // Decoding its records would index past the 61-sized arrays (AIOOBE), and the persistence
            // version is PINNED at 1 pre-release (owner ruling) so no version fence exists. Treat it as
            // cap-collapsed: same shape the region would have been built with under today's cap, priced
            // from passFrac. The returned bit offset is NOT advanced over the fragment records, which is
            // safe because the production caller (CostPyramidCodec.decode) frames records at the BYTE
            // level (one length-prefixed run buffer per record) and never uses the returned offset.
            out.setFragmentCount(0);
            out.setCollapsed(true);
            return p;
        }
        out.setFragmentCount(count);
        if (count == 0) {
            // Honestly zero kept fragments (stripped / no-passable-item mass) — collapsed stays FALSE
            // (reset() cleared it). The old re-flag-as-collapsed conflation is gone: collapse now rides
            // the FRAGMENT_COUNT_COLLAPSED sentinel above.
            return p;
        }
        int[] packed = new int[6]; // cold disk path; alloc fine
        for (int f = 0; f < count; f++) {
            int mask = readBits(buf, p, 6); p += 6;
            int typeBits = (readBits(buf, p, 1) != 0 ? RegionFragments.TYPE_S : 0); p += 1;
            typeBits |= (readBits(buf, p, 1) != 0 ? RegionFragments.TYPE_W : 0); p += 1;
            for (int face = 0; face < 6; face++) {
                if ((mask & (1 << face)) != 0) {
                    packed[face] = readBits(buf, p, 16); p += 16;
                } else {
                    packed[face] = RegionFragments.NO_FACE;
                }
            }
            out.setFragment(f, mask, packed);
            out.setFragmentTypes(f, typeBits);
        }
        return p;
    }

    /** The exact packed bit length of {@code rf} (for buffer sizing); matches what {@link #packRegion} writes. */
    public static int regionBitLength(RegionFragments rf) {
        if (rf.kind() != RegionFragments.KIND_MIXED) {
            return 6; // kind(2) + hardness(4)
        }
        int bits = 2 + 4 + 4 + 6; // kind + hardness + passFrac + count
        int count = rf.fragmentCount();
        for (int f = 0; f < count; f++) {
            bits += 6 + 2; // faceMask + type bits (S, W)
            bits += Integer.bitCount(rf.faceMask(f)) * 16; // one 16-bit footprint per set face
        }
        return bits;
    }

    /**
     * Write the low {@code nbits} of {@code value} into {@code buf} at bit offset {@code bitPos}, MSB-first,
     * returning the new bit offset. Assumes the target bits are 0 (only sets 1-bits — the buffer must be
     * zero-initialized over the written span).
     */
    static int writeBits(byte[] buf, int bitPos, int value, int nbits) {
        for (int i = nbits - 1; i >= 0; i--) {
            if (((value >> i) & 1) != 0) {
                buf[bitPos >> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            }
            bitPos++;
        }
        return bitPos;
    }

    /** Read {@code nbits} from {@code buf} at bit offset {@code bitPos}, MSB-first, as an unsigned int. */
    static int readBits(byte[] buf, int bitPos, int nbits) {
        int v = 0;
        for (int i = 0; i < nbits; i++) {
            int bit = (buf[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
            v = (v << 1) | bit;
            bitPos++;
        }
        return v;
    }
}
