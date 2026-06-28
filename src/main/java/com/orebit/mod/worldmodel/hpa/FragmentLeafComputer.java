package com.orebit.mod.worldmodel.hpa;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavSection;

/**
 * Builds a level-0 leaf's {@link RegionFragments} connectivity record from the resident nav grid — the
 * fragment-model counterpart of {@link LeafCostComputer} (HPA-FRAGMENTS.md §7, the
 * {@code LeafCostComputer → FragmentLeafComputer} row). It is the thin MC read that fills the passability /
 * standability masks + occupancy tallies for the pure {@link FragmentBuilder} core, which does the actual
 * flood-fill, occupiability filter, cap, and footprint extraction.
 *
 * <h2>What it reads</h2>
 * A level-0 region is one 16³ {@link NavSection}. This mirrors {@link LeafCostComputer}'s occupancy scan
 * exactly: for each of the 4096 local cells, look up the cell's resident navtype → {@link NavBlock}
 * descriptor (no live block reads) and tally {@link NavBlock#isStandable standable} /
 * {@link NavBlock#isPassable passable}, the water count (to tell an all-water column from an all-air one),
 * and the Σ-hardness over SOLID cells (the mine-edge cost scale). It then hands the masks + tallies to
 * {@link FragmentBuilder#build} at {@code G = 16}.
 *
 * <h2>Drops vs {@link LeafCostComputer}</h2>
 * The bounded per-face mini-pathfind <b>and</b> all stored face costs are gone (HPA-FRAGMENTS.md §2.2: costs
 * are derived at query time, not stored) — replaced by a single flood fill (~13 µs/leaf, net faster than the
 * ~60 µs/leaf bounded-A* baseline).
 *
 * <h2>Wiring</h2>
 * <b>Not yet wired</b> into {@link CostPyramid}/{@link RegionGrid} — that is S2, gated by
 * {@link RegionGrid#HPA_FRAGMENTS}. This class is purely additive at S1: it builds a {@link RegionFragments}
 * from a {@link NavSection} and touches no working code path.
 *
 * <h2>House style</h2>
 * Static-only, mirroring {@link LeafCostComputer}. The per-call mask buffers are flat thread-local scratch
 * reused across leaves (no per-cell allocation); the result is written into a caller-owned
 * {@link RegionFragments}.
 */
public final class FragmentLeafComputer {

    private FragmentLeafComputer() {}

    /** Leaf side in blocks (16) — one {@link NavSection} per side. */
    private static final int LEAF = RegionAddress.LEAF_SIZE; // 16
    /** Cells per leaf (16³ = 4096). */
    private static final int CELLS = LEAF * LEAF * LEAF;

    /** FLUID_WATER encoding in {@link NavBlock#fluid} (geometrically passable water cells). */
    private static final int FLUID_WATER = 1;

    // Reusable per-call scratch (single-threaded leaf compute, as LeafCostComputer requires). Flat 1-D
    // buffers in the canonical (ly<<8)|(lz<<4)|lx order so the occupancy scan allocates nothing per cell.
    private static final ThreadLocal<boolean[]> STANDABLE = ThreadLocal.withInitial(() -> new boolean[CELLS]);
    private static final ThreadLocal<boolean[]> PASSABLE = ThreadLocal.withInitial(() -> new boolean[CELLS]);

    /** Flat cell index for local coords (0..15 each) — the {@code G == 16} form of {@link FragmentBuilder}'s. */
    private static int idx(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    /**
     * Compute the level-0 leaf's fragment record from a resident {@link NavSection}, writing into {@code out}.
     *
     * @param section the 16³ section backing this leaf (resolved by the caller / S2 wiring)
     * @param out     the record to reset and fill
     */
    public static void computeLeaf(NavSection section, RegionFragments out) {
        final boolean[] standable = STANDABLE.get();
        final boolean[] passable = PASSABLE.get();

        int standCount = 0;
        int passCount = 0;
        int waterCount = 0;     // passable cells that hold water (all-water column vs all-air)
        long hardnessSumSolid = 0; // Σ quantized hardness over SOLID (non-passable) cells
        int solidCount = 0;

        for (int ly = 0; ly < LEAF; ly++) {
            for (int lz = 0; lz < LEAF; lz++) {
                for (int lx = 0; lx < LEAF; lx++) {
                    long desc = NavBlock.descriptor((short) section.getNavtype(lx, ly, lz));
                    boolean st = NavBlock.isStandable(desc);
                    boolean pa = NavBlock.isPassable(desc);
                    int i = idx(lx, ly, lz);
                    standable[i] = st;
                    passable[i] = pa;
                    if (st) standCount++;
                    if (pa) {
                        passCount++;
                        if (NavBlock.fluid(desc) == FLUID_WATER) waterCount++;
                    } else {
                        // SOLID (non-passable) cell: contributes to the mine-edge hardness average.
                        solidCount++;
                        hardnessSumSolid += NavBlock.hardness(desc);
                    }
                }
            }
        }

        FragmentBuilder.build(passable, standable, LEAF,
                passCount, standCount, waterCount,
                hardnessSumSolid, solidCount,
                out);
    }
}
