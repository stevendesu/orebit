package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;

/**
 * The connectivity record for one HPA* region under the <b>fragment model</b> (HPA-FRAGMENTS.md §2, §5) —
 * the replacement for the lossy single-center-node representation ({@link LeafCostComputer}'s six
 * face→center buckets, {@code CostPyramid}'s 12 face-bytes).
 *
 * <h2>What a fragment is</h2>
 * An abstract node is {@code (region, fragment)}, where a <b>fragment</b> is one 6-connected component of
 * the region's <i>occupiable</i> cells (a passable cell with a standable floor below and ≥2-tall headroom —
 * see {@link FragmentBuilder}). A region contributes one node per fragment: usually 1 (open terrain or solid
 * rock), a handful in caves. Same fragment → cheap-pathable (walk, no mining); different fragments → an
 * expensive intra-region mine edge. The region graph is therefore always fully connected (a sealed region
 * just routes through a dig), so there is no disconnected FAIL.
 *
 * <h2>Region kind (HPA-FRAGMENTS.md §2.3)</h2>
 * A region with no occupiable fragment is one of three uniform kinds (no fragment records):
 * <ul>
 *   <li>{@link #KIND_SOLID} — mine straight through (cost from {@link #avgSolidHardness}).</li>
 *   <li>{@link #KIND_AIR} — floorless: a one-way down chute (fall in/out cheap, pillar up dear).</li>
 *   <li>{@link #KIND_WATER} — symmetric swim.</li>
 * </ul>
 * {@link #KIND_MIXED} is the only kind that carries fragment records. A MIXED region with
 * {@link #fragmentCount()}{@code == 0} is a <b>collapsed / uniform-mass</b> region: either occupiability
 * stripped every component (checkerboard / speckle noise → {@link #isCollapsed()}{@code == false}) or the
 * occupiable component count exceeded the {@value #MAX_FRAGMENTS} cap ({@link #isCollapsed()}{@code == true}).
 * In both cases the crossing cost is derived from {@link #passFrac} at query time (S3) — more air ⇒ cheaper.
 *
 * <h2>Storage (HPA-FRAGMENTS.md §5)</h2>
 * On disk a region is a sub-byte {@code CostCodec} bitstream (S2); in RAM it is the convenient
 * struct-of-arrays below (favour-cpu-over-ram, like {@link CostPyramid}'s not-bit-packed rows). The bit
 * widths the on-disk form targets are: {@code kind} 2, {@code avgSolidHardness} 4, {@code passFrac} 4,
 * {@code fragmentCount} 6 (cap {@value #MAX_FRAGMENTS}); then per fragment a 6-bit {@code faceMask} and, per
 * set face, a 2-byte footprint (a 2D bbox on the face's two in-face axes, 4 bits per min/max). <b>No costs
 * are stored</b> — every edge cost is derived per expansion (HPA-FRAGMENTS.md §2.2).
 *
 * <h2>Faces &amp; in-face axes (canonical {@link RegionAddress} order)</h2>
 * <pre>0 = -X   1 = +X   2 = -Y   3 = +Y   4 = -Z   5 = +Z</pre>
 * A face's footprint is a 2D bbox on its two in-face axes {@code (u, v)}:
 * <pre>
 *   ±X faces (0,1): u = Y, v = Z
 *   ±Y faces (2,3): u = X, v = Z
 *   ±Z faces (4,5): u = X, v = Y
 * </pre>
 *
 * <h2>House style</h2>
 * A reusable mutable container: {@link FragmentBuilder} resets and fills one of these per region with no
 * per-cell allocation; the parallel {@code byte[]}/{@code int[]} arrays are sized once to the cap. Pure data
 * + bit math — <b>no Minecraft imports</b> (the MC read lives in {@link FragmentLeafComputer}).
 */
public final class RegionFragments {

    // ---- Region kind (2 bits) — single source of truth, region-level (HPA-FRAGMENTS.md §2.3, §5) -------
    /** Carries fragment records (the only non-uniform kind). {@code fragmentCount==0} ⇒ collapsed mass. */
    public static final int KIND_MIXED = 0;
    /** Fully solid — mine straight through; no fragments. */
    public static final int KIND_SOLID = 1;
    /** Floorless air column — one-way down chute; no fragments. */
    public static final int KIND_AIR = 2;
    /** Flooded — symmetric swim; no fragments. */
    public static final int KIND_WATER = 3;

    /** Hard cap on fragments per region — a 6-bit id (HPA-FRAGMENTS.md §3, §5). Over-cap ⇒ collapse. */
    public static final int MAX_FRAGMENTS = 63;

    /** Sentinel returned by {@link #footprint(int, int)} for a face the fragment does not touch. */
    public static final int NO_FACE = -1;

    // ---- Region header --------------------------------------------------------------------------------
    private int kind;
    /** Mean quantized hardness over the region's SOLID cells, packed to a nibble (0..15). The mine-edge cost. */
    private int avgSolidHardness;
    /** Passable-cell fraction, packed to a nibble (0..15). The collapsed/uniform crossing cost. */
    private int passFrac;
    /** Real fragment records present (0..{@value #MAX_FRAGMENTS}); 0 with {@link #KIND_MIXED} ⇒ uniform mass. */
    private int fragmentCount;
    /** True iff the occupiable component count exceeded {@value #MAX_FRAGMENTS} (distinguishes over-cap from 0). */
    private boolean collapsed;
    /** The flood grid side this record was built at ({@code G}); 16 at the leaf (HPA-FRAGMENTS.md §3.1). */
    private int gridSize;

    // ---- Per-fragment records (struct-of-arrays, sized to the cap) -------------------------------------
    /** Which of the 6 faces each fragment reaches (6-bit mask, bit f = face f). */
    private final byte[] faceMask = new byte[MAX_FRAGMENTS];
    /**
     * Packed footprint per {@code (fragment, face)} = {@code [frag*6 + face]}: four nibbles
     * {@code (minU<<12)|(maxU<<8)|(minV<<4)|maxV}, or {@link #NO_FACE} when the face bit is clear. Valid only
     * where {@link #touchesFace}.
     */
    private final int[] footprint = new int[MAX_FRAGMENTS * 6];

    /** Reset to an empty record at flood grid side {@code G} (clears all fragments). */
    void reset(int G) {
        this.kind = KIND_MIXED;
        this.avgSolidHardness = 0;
        this.passFrac = 0;
        this.fragmentCount = 0;
        this.collapsed = false;
        this.gridSize = G;
        Arrays.fill(faceMask, (byte) 0);
        Arrays.fill(footprint, NO_FACE);
    }

    // ---- Package-private setters (FragmentBuilder) -----------------------------------------------------

    void setKind(int kind) { this.kind = kind; }
    void setAvgSolidHardness(int nibble) { this.avgSolidHardness = nibble; }
    void setPassFrac(int nibble) { this.passFrac = nibble; }
    void setFragmentCount(int n) { this.fragmentCount = n; }
    void setCollapsed(boolean c) { this.collapsed = c; }

    /** Record one fragment's faces + packed footprints. {@code packed[face]} = {@link #NO_FACE} when unset. */
    void setFragment(int frag, int faceMaskBits, int[] packedByFace) {
        this.faceMask[frag] = (byte) faceMaskBits;
        int base = frag * 6;
        for (int f = 0; f < 6; f++) {
            this.footprint[base + f] = ((faceMaskBits >> f) & 1) != 0 ? packedByFace[f] : NO_FACE;
        }
    }

    // ---- Public accessors -----------------------------------------------------------------------------

    /** Region kind ({@link #KIND_MIXED}/{@link #KIND_SOLID}/{@link #KIND_AIR}/{@link #KIND_WATER}). */
    public int kind() { return kind; }

    /** Mean solid-cell hardness as a nibble (0..15) — the intra-region mine-edge cost scale. */
    public int avgSolidHardness() { return avgSolidHardness; }

    /** Passable-cell fraction as a nibble (0..15) — the collapsed/uniform crossing cost scale. */
    public int passFrac() { return passFrac; }

    /** Number of real fragment records (0..{@value #MAX_FRAGMENTS}); 0 ⇒ uniform mass for a MIXED region. */
    public int fragmentCount() { return fragmentCount; }

    /** True iff this region collapsed because its occupiable component count exceeded the cap. */
    public boolean isCollapsed() { return collapsed; }

    /** The flood grid side ({@code G}) this record was built at (16 at the leaf). */
    public int gridSize() { return gridSize; }

    /** True iff this is a uniform kind (SOLID/AIR/WATER) — no fragment records by construction. */
    public boolean isUniform() { return kind != KIND_MIXED; }

    /** The 6-bit face mask of fragment {@code frag} (bit f set ⇒ it reaches face f). */
    public int faceMask(int frag) { return faceMask[frag] & 0x3F; }

    /** Whether fragment {@code frag} reaches face {@code f} (0..5). */
    public boolean touchesFace(int frag, int f) { return (faceMask[frag] & (1 << f)) != 0; }

    /**
     * The packed footprint of fragment {@code frag} on face {@code f}, or {@link #NO_FACE} if it does not
     * touch that face. Decode with {@link #footprintMinU}/{@link #footprintMaxU}/{@link #footprintMinV}/
     * {@link #footprintMaxV}.
     */
    public int footprint(int frag, int f) { return footprint[frag * 6 + f]; }

    /** Min of the face's first in-face axis ({@code u}) — see the class Javadoc for the per-face {@code (u,v)}. */
    public static int footprintMinU(int packed) { return (packed >> 12) & 0xF; }
    /** Max of the face's first in-face axis ({@code u}). */
    public static int footprintMaxU(int packed) { return (packed >> 8) & 0xF; }
    /** Min of the face's second in-face axis ({@code v}). */
    public static int footprintMinV(int packed) { return (packed >> 4) & 0xF; }
    /** Max of the face's second in-face axis ({@code v}). */
    public static int footprintMaxV(int packed) { return packed & 0xF; }

    /** Pack a footprint bbox (each coord 0..15) into the {@code int} form stored per {@code (fragment, face)}. */
    public static int packFootprint(int minU, int maxU, int minV, int maxV) {
        return ((minU & 0xF) << 12) | ((maxU & 0xF) << 8) | ((minV & 0xF) << 4) | (maxV & 0xF);
    }

    /**
     * A cheap content hash over everything the record carries (kind, header nibbles, and every active
     * fragment's faceMask + per-face footprint). Used by {@link PyramidMerger#mergeUpFragments} for the
     * design's "stop the moment a level's output is unchanged" damping (HPA-FRAGMENTS.md §6.5): if a parent's
     * recompute leaves its signature identical, nothing above it can change, so the walk stops. Order-stable and
     * allocation-free.
     */
    public long contentSignature() {
        long h = 1125899906842597L; // FNV-ish seed
        h = h * 31 + kind;
        h = h * 31 + avgSolidHardness;
        h = h * 31 + passFrac;
        h = h * 31 + fragmentCount;
        h = h * 31 + (collapsed ? 1 : 0);
        for (int frag = 0; frag < fragmentCount; frag++) {
            h = h * 31 + (faceMask[frag] & 0x3F);
            int base = frag * 6;
            for (int f = 0; f < 6; f++) {
                h = h * 31 + footprint[base + f];
            }
        }
        return h;
    }
}
