package com.orebit.mod.worldmodel.hpa;

/**
 * Shared region-tier <b>cost constants</b> for the HPA* fragment model (HPA-FRAGMENTS.md §2.2). These are the
 * per-leaf-side tick stand-ins the fragment edge-cost derivation in
 * {@link com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder} divides by {@link RegionAddress#LEAF_SIZE}
 * to get per-block walk/pillar/fall/mine/swim rates, plus the uniform-kind transit costs. One source of truth so
 * the leaf builder ({@link FragmentLeafComputer}) and the region A* agree on what a "tick" of each motion costs.
 *
 * <p>(Historically this class also computed the center-model's six face→center buckets via a per-face bounded
 * mini-pathfind; that model was deleted when the fragment model became the only one — only its constants remain.)
 *
 * <p>Not tuned magic numbers for the final inventory subsystem — placeholders until physically-derived costs
 * land (see the {@code physically-derived-costs} note).
 */
public final class LeafCostComputer {

    private LeafCostComputer() {}

    /** Leaf side in blocks (16) — one {@link com.orebit.mod.worldmodel.pathing.NavSection} per side. */
    private static final int LEAF = RegionAddress.LEAF_SIZE; // 16

    /**
     * Tick stand-in for mining one block of solid material — matches the block tier's break-deterrent scale.
     */
    public static final float MINE_PER_BLOCK = 3.0f;

    /** Cheap cost to transit an all-air leaf (a step/fall-through across one leaf side). */
    public static final float AIR_TRANSIT_TICKS = LEAF; // 16

    /**
     * Expensive cost for any direction through an all-air leaf that is NOT a free fall — pillaring up or
     * crossing floorless air horizontally. ~{@code LEAF} blocks of placement at the block tier's place base cost
     * (~6 ticks/block). This is what makes the region A* treat an air column as a one-way DOWN chute (cheap to
     * fall through, dear to climb/bridge) instead of a cheap up-and-over highway. Tunable.
     */
    public static final float AIR_CLIMB_TICKS = LEAF * 6f; // ~PLACE_BASE_COST per placed block

    /**
     * Symmetric cost to swim across a fully-flooded (all-water) leaf side. Swimming is reversible (up and down
     * both cost effort), so unlike air this is the same in both directions. Seeded at ~the sprint-swim rate
     * (5.612 b/s → ~3.56 ticks/block; a full-water column is ≥2-deep, so the bot sprint-swims it). Tunable.
     */
    public static final float WATER_TRANSIT_TICKS = LEAF * 3.6f; // ~sprint-swim ticks/block × LEAF
}
