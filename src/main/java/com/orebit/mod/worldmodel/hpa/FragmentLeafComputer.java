package com.orebit.mod.worldmodel.hpa;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavSection;

/**
 * Builds a level-0 leaf's {@link RegionFragments} connectivity record from the resident nav grid — the
 * fragment-model counterpart of {@link LeafCostComputer} (HPA-FRAGMENTS.md §7, the
 * {@code LeafCostComputer → FragmentLeafComputer} row). It is the thin MC read that fills the passability /
 * standability / water masks + occupancy tallies for the pure {@link FragmentBuilder} core, which does the
 * actual flood-fill, keep-all typed-fragment annotation, cap, and footprint extraction
 * (DESIGN-typed-fragments.md §5.1–§5.2).
 *
 * <h2>What it reads</h2>
 * A level-0 region is one 16³ {@link NavSection}. This mirrors {@link LeafCostComputer}'s occupancy scan
 * exactly: for each of the 4096 local cells, look up the cell's resident navtype → {@link NavBlock}
 * descriptor (no live block reads) and tally {@link NavBlock#isStandable standable} /
 * {@link NavBlock#floodPassable flood-passable} (<b>openable-as-air + climbable-as-air</b>,
 * DESIGN-trapdoors.md §8b / DESIGN-trapdoor-ladder-climb.md §6: every door/trapdoor/fence-gate cell —
 * iron included — and every climbable cell (ladder/scaffolding/vines) is region-tier open space;
 * deliberate optimism, unrealizable hops absorbed by the blacklist + capsSig invalidations), fill the water mask (descriptor
 * fluid == WATER on a flood-passable cell — the per-fragment W/S type source) + its count (the
 * truly-uniform all-dry/all-water split), and the Σ-hardness over SOLID (non-flood-passable) cells (the
 * mine-edge cost scale). It then hands the masks + tallies to {@link FragmentBuilder#build} at
 * {@code G = 16}. Only the passable mask uses the flood predicate — {@code standable[]} keeps
 * {@link NavBlock#isStandable} (a closed-bottom trapdoor stays a FLOOR), so fragment typing is
 * undisturbed.
 *
 * <h2>Cost model</h2>
 * No per-cell costs are computed or stored — edge costs are derived at query time from the fragment footprints
 * + universal constants ({@link LeafCostComputer}, HPA-FRAGMENTS.md §2.2). The leaf build is a single flood
 * fill (~13 µs/leaf).
 *
 * <h2>Wiring</h2>
 * The leaf build seam: {@link RegionGrid#rebuildLeaf}/{@link RegionGrid#ensureLeaf} call {@link #computeLeaf}
 * to (re)flood a leaf's {@link RegionFragments} record from its {@link NavSection}, and {@link PyramidMerger}
 * rolls those up to the coarse levels.
 *
 * <h2>House style</h2>
 * Static-only. The per-call mask buffers are flat thread-local scratch reused across leaves (no per-cell
 * allocation); the result is written into a caller-owned {@link RegionFragments}.
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
    /** Water mask (descriptor fluid == WATER on a passable cell) — the typed-fragments type source
     *  (DESIGN-typed-fragments.md §5.1), same pattern as the passable/standable masks. */
    private static final ThreadLocal<boolean[]> WATER = ThreadLocal.withInitial(() -> new boolean[CELLS]);
    /** Climbable mask (ladder / scaffolding / the vine family) — the THIRD footing kind beside water and a
     *  standable floor below (owner ruling 2026-08-21; see {@code FragmentBuilder.build}'s climbable
     *  overload). Without it a floorless climbable column types as pure air, i.e. a one-way DOWN chute, and
     *  the region tier will not route UP it. Consumed only by {@link #computeLeaf} (the typeS source);
     *  {@link #fragmentContaining} needs no climbable mask — it labels components, not types. */
    private static final ThreadLocal<boolean[]> CLIMBABLE = ThreadLocal.withInitial(() -> new boolean[CELLS]);

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
        final boolean[] water = WATER.get();
        final boolean[] climbable = CLIMBABLE.get();

        int standCount = 0;
        int passCount = 0;
        int waterCount = 0;     // passable cells that hold water (the truly-uniform split + the tally)
        long hardnessSumSolid = 0; // Σ quantized hardness over SOLID (non-passable) cells
        int solidCount = 0;

        for (int ly = 0; ly < LEAF; ly++) {
            for (int lz = 0; lz < LEAF; lz++) {
                for (int lx = 0; lx < LEAF; lx++) {
                    long desc = NavBlock.descriptor((short) section.getNavtype(lx, ly, lz));
                    boolean st = NavBlock.isStandable(desc);
                    // Flood membership is openable-as-air + climbable-as-air (NavBlock.floodPassable,
                    // DESIGN-trapdoors.md §8b + DESIGN-trapdoor-ladder-climb.md §6): doors/trapdoors/
                    // fence gates — iron included — and ladders/scaffolding/vines count as open space
                    // here. ONLY this mask changes; standable[] keeps the exact isStandable verdict.
                    boolean pa = NavBlock.floodPassable(desc);
                    int i = idx(lx, ly, lz);
                    standable[i] = st;
                    passable[i] = pa;
                    // Footing source #3 (owner 2026-08-21): a climbable is a place the bot can BE — vanilla
                    // holds it on a rung indefinitely — exactly the claim water makes by treading.
                    climbable[i] = NavBlock.isClimbable(desc);
                    boolean wa = false;
                    if (st) standCount++;
                    if (pa) {
                        passCount++;
                        // Accepted optimism (§8b): a WATERLOGGED openable is flood-passable + water
                        // fluid, so it reads as a WATER cell in the fragment type source even though
                        // the block-tier swim family still treats it as a conservative wall (§10).
                        if (NavBlock.fluid(desc) == FLUID_WATER) {
                            waterCount++;
                            wa = true;
                        }
                    } else {
                        // SOLID (non-flood-passable) cell: contributes to the mine-edge hardness
                        // average. Openable cells now fall on the passable side, so they no longer
                        // dilute the hardness scale — consistent with not being walls to the flood.
                        solidCount++;
                        hardnessSumSolid += NavBlock.hardness(desc);
                    }
                    water[i] = wa;
                }
            }
        }

        FragmentBuilder.build(passable, standable, water, climbable, LEAF,
                passCount, standCount, waterCount,
                hardnessSumSolid, solidCount,
                out);
    }

    /**
     * The kept fragment id that contains local cell {@code (lx,ly,lz)} in this leaf's {@link NavSection},
     * reproduced by re-flooding the section — the MC read behind the flood-from-bot start-fragment resolver
     * (PERF-DESIGN region §4, {@link FragmentBuilder#fragmentContaining}). Returns {@code -1} when the cell isn't
     * passable, the region collapsed or stores a truly-uniform record (no fragments), or the coords are out of
     * range — the caller falls back to nearest-centroid. Fills the same thread-local passable/standable/water
     * masks as {@link #computeLeaf} (a subset of its scan — no hardness work needed here); single-threaded on
     * the tick/planner thread.
     *
     * @param section the 16³ section backing this leaf (resolved by the caller, same as {@link #computeLeaf})
     * @param lx,ly,lz section-local cell coords (0..15)
     */
    public static int fragmentContaining(NavSection section, int lx, int ly, int lz) {
        if (lx < 0 || lx >= LEAF || ly < 0 || ly >= LEAF || lz < 0 || lz >= LEAF) {
            return -1;
        }
        final boolean[] standable = STANDABLE.get();
        final boolean[] passable = PASSABLE.get();
        final boolean[] water = WATER.get();
        fillMasks(section, standable, passable, water);
        return FragmentBuilder.fragmentContaining(passable, standable, water, LEAF, idx(lx, ly, lz));
    }

    /** Fill the three thread-local masks from a section's resident navtypes (the shared wrapper scan). */
    private static void fillMasks(NavSection section, boolean[] standable, boolean[] passable, boolean[] water) {
        for (int y = 0; y < LEAF; y++) {
            for (int z = 0; z < LEAF; z++) {
                for (int x = 0; x < LEAF; x++) {
                    long desc = NavBlock.descriptor((short) section.getNavtype(x, y, z));
                    int i = idx(x, y, z);
                    // Same openable-as-air flood membership as computeLeaf (must stay predicate-
                    // identical: fragmentContaining/labelFragments reproduce build()'s exact flood).
                    boolean pa = NavBlock.floodPassable(desc);
                    standable[i] = NavBlock.isStandable(desc);
                    passable[i] = pa;
                    water[i] = pa && NavBlock.fluid(desc) == FLUID_WATER;
                }
            }
        }
    }

    /**
     * Label every cell of this leaf's {@link NavSection} with its kept fragment id in ONE flood — the
     * whole-region form of {@link #fragmentContaining}, for callers that will query many cells of the same
     * section ({@link RegionGrid#goalDigSeeds}'s per-build label slabs). {@code out[idx(lx,ly,lz)]} equals what
     * {@code fragmentContaining(section, lx, ly, lz)} would return for every local cell (kept id, or {@code -1}
     * — see {@link FragmentBuilder#labelAll} for the exact per-cell contract, including the all-{@code -1}
     * collapsed case). Fills the same thread-local masks as {@link #computeLeaf} (a subset of its scan);
     * single-threaded on the tick/planner thread.
     *
     * @param section the 16³ section backing this leaf (resolved by the caller, same as {@link #computeLeaf})
     * @param out     caller-owned slab of at least {@value #CELLS} cells, fully overwritten
     */
    public static void labelFragments(NavSection section, byte[] out) {
        final boolean[] standable = STANDABLE.get();
        final boolean[] passable = PASSABLE.get();
        final boolean[] water = WATER.get();
        fillMasks(section, standable, passable, water);
        FragmentBuilder.labelAll(passable, standable, water, LEAF, out);
    }
}
