package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;

/**
 * The region tier's <b>capability-aware per-block pillar/bridge cost</b> (the place-side sibling of {@link
 * RegionMineModel}) — a single precomputed scalar of "region units to gain one block of pillared height",
 * derived from the bot's real placement config exactly as {@link RegionMineModel} derives its dig cost from the
 * bot's tools. Read on the cost-to-goal FIELD's vertical-climb path with one field load — no per-edge placement
 * logic.
 *
 * <h2>Why it exists</h2>
 * The field's upward-climb term was a hardcoded {@link RegionPathfinder#PILLAR_PER_BLOCK_FIELD} = {@code 2.29}
 * — a stand-in for "one pillar block ≈ {@link Pillar#COST 4.633} move ticks + {@code placement.placeBaseCost}
 * ~6 ≈ 10.6 ticks, ÷ a walk tick". That ignored the bot's actual place economy: a high {@code
 * placement.placeBaseCost} (a build-reluctant bot) or an expensive-to-remove placed block (a high {@code
 * placement.removalCostWeight}) makes pillaring dearer and should push the field's cost-to-goal — and hence the
 * block heuristic — to prefer a walk-around ramp over a phantom-cheap vertical climb. This model prices a pillar
 * in the SAME currency the block tier's {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar}
 * charges (real ticks converted to {@link RegionPathfinder#WALK_PER_BLOCK} walk-units by dividing by {@link
 * RegionMineModel#WALK_REAL_TICKS}), so the region field and block tier agree on how expensive building up is.
 *
 * <h2>Cost model (admissible lower bound, §GoalForcedCost parity)</h2>
 * Per-block ticks = {@link Pillar#COST} + {@link MovementContext.InventoryView#placeBaseCost} + {@link
 * MovementContext.InventoryView#placeRemovalPremium} — the same terms as {@link MovementContext#pillarPlaceCost}
 * (the block tier's admissible build-face probe), deliberately WITHOUT the finite-inventory premium so it stays a
 * lower bound on the real per-block place cost (running out of blocks only makes the true cost higher). With no
 * snapshot (headless / tests / {@code /bot trace} on a block-less bot) it falls back to {@link
 * MovementContext#PLACE_BASE_COST} + zero premium — reproducing the legacy {@code 2.29}-ish stand-in.
 */
public final class RegionPlaceModel {

    /** No-inventory baseline (tests / headless / trace): the static default place base, no removal premium. */
    public static final RegionPlaceModel DEFAULT = from(null);

    /**
     * Hardness nibble for <b>cobblestone</b> (2.0) under {@code FragmentBuilder.avgSolidHardnessNibble}'s
     * quantizer, {@code round(round(hardness × 5) / 2)}: {@code round(10/2) = 5}. Cross-check: stone (1.5) gives
     * {@code round(round(7.5)/2) = 4}, which is {@code FragmentBuilder.STONE_HARDNESS_NIBBLE}.
     */
    private static final int COBBLE_HARDNESS_NIBBLE = 5;

    /** Default {@code placement.removalCostWeight} — a literal, NOT {@code Config.DEFAULT} (see {@link #bakeForward}). */
    private static final float DEFAULT_REMOVAL_COST_WEIGHT = 1.0f;

    /**
     * The FORWARD-pass model, <b>baked from config</b> at server start (and on {@code /bot config reload}) —
     * never read from {@code ConfigLoader} here.
     *
     * <p><b>Why baked, not read.</b> {@code Config.DEFAULT} holds a {@code Block}, so merely touching
     * {@code ConfigLoader} initializes {@code Blocks} → {@code SoundType} → {@code SoundEvents} and needs the MC
     * registry bootstrap. Reading the config from inside the pathfinder therefore blew up every headless test
     * with {@code ExceptionInInitializerError} (measured, 2026-08-02: 368 failures). The pathfinder stays
     * registry-free and the config pushes values IN — the same shape as {@code MiningModel.buildTable}, which
     * {@code OrebitCommon.init} bakes at {@code SERVER_STARTED} for exactly this reason. The initial value uses
     * literal defaults so headless/JMH/test callers get a sane model without any bake.
     */
    private static volatile RegionPlaceModel forward =
            buildForward(MovementContext.PLACE_BASE_COST, DEFAULT_REMOVAL_COST_WEIGHT);

    /** Region units ({@link RegionPathfinder#WALK_PER_BLOCK}) to pillar up one block with this bot's blocks. */
    private final float pillarPerBlock;

    private RegionPlaceModel(float pillarPerBlock) {
        this.pillarPerBlock = pillarPerBlock;
    }

    /**
     * The <b>FORWARD-pass</b> place model (owner ruling, 2026-08-02) — the place-side sibling of {@link
     * RegionMineModel#WOODEN}. Like the forward dig model it is deliberately <b>inventory-blind</b>, so the
     * region skeleton's shape is stable as the bot's gear changes and the planner does not spend a diamond
     * pickaxe's durability advantage on a route a walk-around would serve; unlike it, this one IS
     * <b>config-aware</b>, reading the server's placement knobs from the {@link ConfigLoader#config() global
     * config} (which defaults to {@link com.orebit.mod.config.Config#DEFAULT} before load, so headless/JMH/test
     * callers are safe).
     *
     * <p>Per-block ticks = {@link Pillar#COST} + {@code placement.placeBaseCost} + the <b>mine-out premium for
     * COBBLESTONE</b> ({@code placement.removalCostWeight} × cobble's dig cost under the same fixed wooden
     * economy the forward dig model assumes). Cobble is the right fixed block for the same reason the wooden
     * pickaxe is the right fixed tool: it is what the bot actually has in bulk (every stone it mines becomes
     * cobble, and it is already {@code ConfigLoader}'s default conjured block), and picking it rather than dirt
     * or scaffolding biases the tier mildly AGAINST placing. That bias is the point — the block tier already
     * prices a placement as "something I may have to mine out later", which is what keeps bots walking around
     * ravines and over ground instead of pillaring above every obstacle and flying straight at the goal. A
     * region tier that under-priced it would hand back routes needing a bridge or a pillar when a walkable one
     * sits a region over.
     */
    public static RegionPlaceModel forward() {
        return forward;
    }

    /**
     * Re-bake {@link #forward()} from the server's placement config — called once at {@code SERVER_STARTED} and
     * again on {@code /bot config reload}, alongside {@code MiningModel.buildTable}. Off any hot path; the
     * search reads the baked model's single scalar.
     */
    public static void bakeForward(float placeBaseCost, float removalCostWeight) {
        forward = buildForward(placeBaseCost, removalCostWeight);
    }

    private static RegionPlaceModel buildForward(float placeBaseCost, float removalCostWeight) {
        final float baseUnits = (Pillar.COST + placeBaseCost) / RegionMineModel.WALK_REAL_TICKS;
        final float removalUnits =
                RegionMineModel.WOODEN.unitsPerBlock(COBBLE_HARDNESS_NIBBLE) * removalCostWeight;
        return new RegionPlaceModel(baseUnits + removalUnits);
    }

    /** Build the model from a bot's inventory feasibility view ({@code null} ⇒ the {@link #DEFAULT} baseline). */
    public static RegionPlaceModel from(MovementContext.InventoryView inv) {
        final float placeBase = inv != null ? inv.placeBaseCost() : MovementContext.PLACE_BASE_COST;
        final float removalPremium = inv != null ? inv.placeRemovalPremium() : 0f;
        // Real per-block pillar ticks = the upward move + the folded placement (base + removal premium), the same
        // admissible terms as MovementContext.pillarPlaceCost; divide by a walk tick to reach region walk-units.
        final float ticks = Pillar.COST + placeBase + removalPremium;
        return new RegionPlaceModel(ticks / RegionMineModel.WALK_REAL_TICKS);
    }

    /**
     * Region units ({@link RegionPathfinder#WALK_PER_BLOCK}) to pillar/bridge up one block with this bot's blocks
     * — a single field read, hot-path safe. Fed into the cost-to-goal field's upward-climb term in place of the
     * hardcoded {@link RegionPathfinder#PILLAR_PER_BLOCK_FIELD} stand-in.
     */
    public float pillarPerBlock() {
        return pillarPerBlock;
    }
}
