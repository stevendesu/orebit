package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * The region tier's <b>tool-aware per-block dig cost</b> (PERF-DESIGN region §5) — a precomputed
 * {@code float[16]} of "mine-units per solid block" indexed by a region's quantized {@link
 * com.orebit.mod.worldmodel.hpa.RegionFragments#avgSolidHardness avgSolidHardness} nibble (0..15). Built once
 * per plan from the bot's real {@link MiningModel.Snapshot}, then read on the region-A* cost path with a single
 * array index — no per-edge tool logic, no {@link MiningModel} descriptor probe (the region tier holds only a
 * hardness nibble, never a live block).
 *
 * <h2>Why it exists</h2>
 * The old region dig cost was {@code span × MINE_PER_BLOCK × hardnessFactor} — a flat, tool-blind term that
 * could price a dig <i>below</i> a walk (soft dirt at {@code 3 × 0.5 = 1.5}/block) and ignored the bot's tools.
 * That let a bot dig straight down a cave rather than walk to a natural entrance. This model prices a dig in the
 * SAME currency the block tier uses (real {@link MiningModel} ticks, converted to {@link
 * RegionPathfinder#WALK_PER_BLOCK} walk-units by dividing by {@link #WALK_REAL_TICKS}), so the region and block
 * tiers agree on how expensive digging is, and it is combined with an always-present walk term by {@link
 * RegionPathfinder} so a dig can never undercut a walk.
 *
 * <h2>Tool mapping (deliberately coarse, §5)</h2>
 * One hardness→category branch, no averaging, no axe tier: soft nibbles (dirt / sand / gravel, {@code ≤
 * }{@link #SOFT_MAX_NIBBLE}) map to a SHOVEL and do not require the tool to harvest; harder nibbles (stone /
 * ore / wood-ish) map to a PICKAXE and DO require it (wood is slightly pessimistic — safe). The best tier the
 * bot carries for that category is read from the snapshot ({@link MiningModel.Snapshot#bestTierOrdinal}); a
 * missing tool self-handles — a no-pickaxe bot mining stone gets the bare-hand 5× harvest penalty, so stone
 * digging becomes expensive and the bot prefers walking. The two-term model makes this factor low-stakes: it
 * only sets the dig/walk ratio and can never flip a dig below a walk.
 */
public final class RegionMineModel {

    /** Number of region hardness nibbles (0..15). */
    static final int NIBBLES = 16;

    /**
     * Largest nibble treated as SHOVEL-soft (dirt/sand/gravel ≈ nibble 2; stone is 4). Blocks above this map to
     * a PICKAXE and are treated as tool-required for harvest. Ordinal, tunable.
     */
    static final int SOFT_MAX_NIBBLE = 3;

    /**
     * Real ticks to walk one block (vanilla ≈ 4.317 m/s ⇒ ~4.6 ticks/block) — the divisor that converts a
     * {@link MiningModel} real-tick dig estimate into {@link RegionPathfinder#WALK_PER_BLOCK} walk-units, so the
     * region tier prices digging and walking in ONE currency (the whole-scale ratio pass, §5).
     */
    static final float WALK_REAL_TICKS = 4.6f;

    /** No-inventory baseline tool (tests / headless / JMH): a middling stone-tier tool for each category. */
    private static final MiningModel.Tier DEFAULT_TIER = MiningModel.Tier.STONE;

    /** The no-inventory baseline model — a stone-tier tool for every category (used by tests / headless plans). */
    public static final RegionMineModel DEFAULT = build(null);

    /** Mine-units (in {@link RegionPathfinder#WALK_PER_BLOCK}) to break one solid block of each hardness nibble. */
    private final float[] unitsByNibble;

    private RegionMineModel(float[] unitsByNibble) {
        this.unitsByNibble = unitsByNibble;
    }

    /** Build the model from a bot's mining snapshot ({@code null} ⇒ the stone-tier {@link #DEFAULT} baseline). */
    public static RegionMineModel from(MiningModel.Snapshot snap) {
        return snap == null ? DEFAULT : build(snap);
    }

    private static RegionMineModel build(MiningModel.Snapshot snap) {
        final int pickCat = NavBlock.Tool.PICKAXE.ordinal();
        final int shovelCat = NavBlock.Tool.SHOVEL.ordinal();
        final float[] u = new float[NIBBLES];
        for (int nib = 0; nib < NIBBLES; nib++) {
            final boolean soft = nib <= SOFT_MAX_NIBBLE;
            final int cat = soft ? shovelCat : pickCat;
            final boolean toolRequired = !soft; // pickaxe blocks need the tool to harvest; dirt/sand do not
            final float realTicks = MiningModel.regionMineTicks(nib, cat, toolRequired, tierFor(snap, cat));
            u[nib] = realTicks / WALK_REAL_TICKS;
        }
        return new RegionMineModel(u);
    }

    private static MiningModel.Tier tierFor(MiningModel.Snapshot snap, int categoryOrdinal) {
        if (snap == null) {
            return DEFAULT_TIER;
        }
        final MiningModel.Tier[] tiers = MiningModel.Tier.values();
        final int ord = snap.bestTierOrdinal(categoryOrdinal);
        return (ord < 0 || ord >= tiers.length) ? MiningModel.Tier.BARE : tiers[ord];
    }

    /**
     * Mine-units (in {@link RegionPathfinder#WALK_PER_BLOCK}) to break one solid block of quantized hardness
     * {@code nibble} (0..15) with this bot's best tool for the mapped category. A single array read — hot-path
     * safe.
     */
    public float unitsPerBlock(int nibble) {
        return unitsByNibble[nibble & 15];
    }
}
