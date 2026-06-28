package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;

/**
 * The <b>pure connectivity core</b> of the HPA* fragment model (HPA-FRAGMENTS.md §3) — flood-fill the
 * 6-connected components of a region's passable cells, drop the non-occupiable ones (the checkerboard /
 * speckle filter), cap the survivor count, and extract a per-{@code (fragment, face)} 2D footprint bbox into
 * a {@link RegionFragments} record.
 *
 * <h2>No Minecraft</h2>
 * This class takes raw {@code boolean} masks + primitive tallies and contains <b>no MC imports</b>, so it is
 * unit-testable headlessly on synthetic grids (the {@code ConnectivityBenchmark} fixtures). The MC read that
 * fills the masks from a {@link com.orebit.mod.worldmodel.pathing.NavSection NavSection}'s resident navtypes
 * lives in {@link FragmentLeafComputer}.
 *
 * <h2>Indexing</h2>
 * Cells use the project's section-local linear index for a power-of-two grid side {@code G}:
 * {@code i = (y << 2·gbits) | (z << gbits) | x} (the canonical {@code (y<<8)|(z<<4)|x} at {@code G == 16}),
 * so 6-neighbour stepping is {@code ±1} (X), {@code ±G} (Z), {@code ±G²} (Y). {@code G} is always a power of
 * two (16 at the leaf; 4/2 at coarse levels — HPA-FRAGMENTS.md §3.1), so the per-cell coord decode is shifts
 * and masks, never division.
 *
 * <h2>Algorithm (HPA-FRAGMENTS.md §3)</h2>
 * <ol>
 *   <li><b>Uniform fast-paths.</b> {@code passCount == 0} ⇒ {@link RegionFragments#KIND_SOLID};
 *       {@code standCount == 0} (no floor) ⇒ {@link RegionFragments#KIND_WATER} (mostly water) or
 *       {@link RegionFragments#KIND_AIR}. These fold in {@link LeafCostComputer}'s existing all-solid /
 *       all-air / all-water shortcuts and store no fragments.</li>
 *   <li><b>Flood fill</b> (BFS) the passable cells, 6-connected → raw components (beats union-find ~2.2× on
 *       the common large-component case — HPA-FRAGMENTS.md §4).</li>
 *   <li><b>Occupiability filter.</b> Drop any component with no cell that is a real stand position: a passable
 *       cell with a standable floor directly below and ≥2-tall headroom (the cell above also passable). The
 *       checkerboard → 0 fragments → uniform mass; speckle/gravel noise collapses the same way. Principled (a
 *       movement fact), not a tuned size threshold.</li>
 *   <li><b>Cap.</b> {@code > }{@value RegionFragments#MAX_FRAGMENTS} surviving components ⇒ collapse to a
 *       passability-weighted uniform mass ({@link RegionFragments#isCollapsed()}), bounding storage and the
 *       abstract-node count under adversarial terrain. Safe because the block tier is the source of truth.</li>
 *   <li><b>Footprint.</b> For each kept fragment, the 2D bbox of its cells on each touched face (its opening),
 *       used for portal overlap, transit cost, and the window-target portal cell (S2–S4).</li>
 * </ol>
 *
 * <h2>House style</h2>
 * Allocation-free past warmup: the flood label/queue and the per-component face-bbox accumulators are
 * thread-local scratch reused across builds (a leaf compute is single-threaded on the tick/maintenance
 * thread, as {@link LeafCostComputer} requires). The result is written into a caller-owned
 * {@link RegionFragments}.
 */
public final class FragmentBuilder {

    private FragmentBuilder() {}

    /** Largest supported grid side (the {@code G == 16} leaf); scratch is sized to {@code MAX_G³}. */
    public static final int MAX_G = 16;
    private static final int MAX_CELLS = MAX_G * MAX_G * MAX_G; // 4096

    /**
     * Reference nibble for stone in {@link #avgSolidHardnessNibble}: the per-cell NavBlock hardness of stone
     * (≈ {@code round(1.5 × 5) = 8}) maps to this bucket, so the S3 mine-edge cost can recover ticks as
     * {@code MINE_PER_BLOCK × span × (avgSolidHardness / STONE_HARDNESS_NIBBLE)}. Quantization is S3-tunable.
     */
    public static final int STONE_HARDNESS_NIBBLE = 4;

    // Reusable scratch (no per-cell allocation; reset at the top of each build).
    private static final ThreadLocal<int[]> LABEL = ThreadLocal.withInitial(() -> new int[MAX_CELLS]);
    private static final ThreadLocal<int[]> QUEUE = ThreadLocal.withInitial(() -> new int[MAX_CELLS]);
    // Per-component face-bbox accumulators (6 faces × min/max of two in-face axes). Reset per component.
    private static final ThreadLocal<int[]> FACE_MIN_U = ThreadLocal.withInitial(() -> new int[6]);
    private static final ThreadLocal<int[]> FACE_MAX_U = ThreadLocal.withInitial(() -> new int[6]);
    private static final ThreadLocal<int[]> FACE_MIN_V = ThreadLocal.withInitial(() -> new int[6]);
    private static final ThreadLocal<int[]> FACE_MAX_V = ThreadLocal.withInitial(() -> new int[6]);
    private static final ThreadLocal<int[]> PACKED = ThreadLocal.withInitial(() -> new int[6]);

    /**
     * Build a region's fragment record from passability + standability masks and pre-tallied counts.
     *
     * @param passable          per-cell passable mask (no collision: air/plant/fluid), the flood membership
     * @param standable         per-cell standable mask (a solid-topped block you stand ON)
     * @param G                 grid side (a power of two ≤ {@link #MAX_G}); 16 at the leaf
     * @param passCount         number of passable cells (for {@code passFrac} + the all-solid fast-path)
     * @param standCount        number of standable cells (for the floorless air/water fast-path)
     * @param waterCount        number of passable cells holding water (air-vs-water split for a floorless leaf)
     * @param hardnessSumSolid  Σ quantized NavBlock hardness over the region's SOLID (non-passable) cells
     * @param solidCount        number of SOLID cells (the divisor for {@code avgSolidHardness})
     * @param out               the record to reset and fill
     */
    public static void build(boolean[] passable, boolean[] standable, int G,
                             int passCount, int standCount, int waterCount,
                             long hardnessSumSolid, int solidCount,
                             RegionFragments out) {
        out.reset(G);
        final int cells = G * G * G;

        // Region header common to every kind: passFrac (crossing cost) + avgSolidHardness (mine-edge cost).
        out.setPassFrac(nibbleFraction(passCount, cells));
        out.setAvgSolidHardness(avgSolidHardnessNibble(hardnessSumSolid, solidCount));

        // 1) Uniform fast-paths (HPA-FRAGMENTS.md §2.3): store no fragments.
        if (passCount == 0) {                         // fully solid → mine straight through
            out.setKind(RegionFragments.KIND_SOLID);
            return;
        }
        if (standCount == 0) {                        // no floor → floorless air or water column
            boolean water = waterCount * 2 >= passCount;
            out.setKind(water ? RegionFragments.KIND_WATER : RegionFragments.KIND_AIR);
            return;
        }

        // 2) MIXED: flood the passable cells, filter by occupiability, cap, extract footprints.
        out.setKind(RegionFragments.KIND_MIXED);

        final int gbits = Integer.numberOfTrailingZeros(G);
        final int g2bits = gbits * 2;
        final int gmask = G - 1;
        final int G2 = G * G;

        final int[] label = LABEL.get();
        final int[] queue = QUEUE.get();
        Arrays.fill(label, 0, cells, -1);

        final int[] minU = FACE_MIN_U.get();
        final int[] maxU = FACE_MAX_U.get();
        final int[] minV = FACE_MIN_V.get();
        final int[] maxV = FACE_MAX_V.get();
        final int[] packed = PACKED.get();

        int comp = 0;     // raw component id (pre-filter)
        int kept = 0;     // surviving occupiable fragments written
        boolean collapsed = false;

        for (int seed = 0; seed < cells; seed++) {
            if (!passable[seed] || label[seed] != -1) continue;

            // Reset this component's per-face bbox accumulators to "empty" (maxU<0 ⇒ face untouched).
            for (int f = 0; f < 6; f++) {
                minU[f] = Integer.MAX_VALUE; maxU[f] = -1;
                minV[f] = Integer.MAX_VALUE; maxV[f] = -1;
            }

            int head = 0, tail = 0;
            queue[tail++] = seed;
            label[seed] = comp;
            boolean occupiable = false;

            while (head < tail) {
                int c = queue[head++];
                int x = c & gmask;
                int z = (c >> gbits) & gmask;
                int y = c >> g2bits;

                // Occupiability: a passable cell with a standable floor below and ≥2-tall headroom above.
                // Out-of-grid below ⇒ no floor (conservative); out-of-grid above ⇒ open (optimistic).
                if (!occupiable) {
                    boolean floorBelow = (y > 0) && standable[c - G2];
                    boolean headAbove = (y == G - 1) || passable[c + G2];
                    if (floorBelow && headAbove) occupiable = true;
                }

                // Face membership + bbox accumulation (per-face in-face axes per RegionFragments Javadoc).
                if (x == 0)     accFace(0, y, z, minU, maxU, minV, maxV); // -X: u=Y v=Z
                if (x == gmask) accFace(1, y, z, minU, maxU, minV, maxV); // +X
                if (y == 0)     accFace(2, x, z, minU, maxU, minV, maxV); // -Y: u=X v=Z
                if (y == gmask) accFace(3, x, z, minU, maxU, minV, maxV); // +Y
                if (z == 0)     accFace(4, x, y, minU, maxU, minV, maxV); // -Z: u=X v=Y
                if (z == gmask) accFace(5, x, y, minU, maxU, minV, maxV); // +Z

                // 6-connected neighbours.
                if (x > 0     && passable[c - 1]  && label[c - 1]  == -1) { label[c - 1]  = comp; queue[tail++] = c - 1; }
                if (x < gmask && passable[c + 1]  && label[c + 1]  == -1) { label[c + 1]  = comp; queue[tail++] = c + 1; }
                if (z > 0     && passable[c - G]  && label[c - G]  == -1) { label[c - G]  = comp; queue[tail++] = c - G; }
                if (z < gmask && passable[c + G]  && label[c + G]  == -1) { label[c + G]  = comp; queue[tail++] = c + G; }
                if (y > 0     && passable[c - G2] && label[c - G2] == -1) { label[c - G2] = comp; queue[tail++] = c - G2; }
                if (y < gmask && passable[c + G2] && label[c + G2] == -1) { label[c + G2] = comp; queue[tail++] = c + G2; }
            }
            comp++;

            if (!occupiable) continue;                 // filter: a non-occupiable component is not a fragment

            if (kept < RegionFragments.MAX_FRAGMENTS) {
                int faceMaskBits = 0;
                for (int f = 0; f < 6; f++) {
                    if (maxU[f] >= 0) {                 // touched
                        faceMaskBits |= (1 << f);
                        packed[f] = RegionFragments.packFootprint(minU[f], maxU[f], minV[f], maxV[f]);
                    } else {
                        packed[f] = RegionFragments.NO_FACE;
                    }
                }
                out.setFragment(kept, faceMaskBits, packed);
            } else {
                collapsed = true;                      // over cap → collapse to a uniform mass
            }
            kept++;
        }

        if (collapsed) {
            out.setCollapsed(true);
            out.setFragmentCount(0);                    // §5: collapsed ⇒ no fragment records
        } else {
            // kept in 0..MAX_FRAGMENTS. 0 = occupiability stripped every component (uniform mine-through mass).
            out.setFragmentCount(kept);
        }
    }

    /** Accumulate cell {@code (u,v)} into face {@code f}'s bbox. */
    private static void accFace(int f, int u, int v, int[] minU, int[] maxU, int[] minV, int[] maxV) {
        if (u < minU[f]) minU[f] = u;
        if (u > maxU[f]) maxU[f] = u;
        if (v < minV[f]) minV[f] = v;
        if (v > maxV[f]) maxV[f] = v;
    }

    /** A count as a fraction-of-cells nibble (0..15). */
    public static int nibbleFraction(int count, int cells) {
        if (cells <= 0) return 0;
        int n = Math.round((float) count * 15f / cells);
        return n < 0 ? 0 : Math.min(15, n);
    }

    /**
     * Mean SOLID-cell NavBlock hardness packed to a nibble (0..15) — the mine-edge cost scale. Step 2 so
     * stone (per-cell ≈ 8) maps to {@link #STONE_HARDNESS_NIBBLE} (4), deepslate (~15) to ~8, with very hard
     * blocks (obsidian/bedrock) saturating at 15. S3-tunable; only ordinal correctness is required.
     */
    public static int avgSolidHardnessNibble(long hardnessSumSolid, int solidCount) {
        if (solidCount <= 0) return 0;
        float avg = (float) hardnessSumSolid / solidCount;
        int b = Math.round(avg / 2f);
        return b < 0 ? 0 : Math.min(15, b);
    }
}
