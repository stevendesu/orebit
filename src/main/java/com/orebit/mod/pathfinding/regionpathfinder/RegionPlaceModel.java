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

    /** Region units ({@link RegionPathfinder#WALK_PER_BLOCK}) to pillar up one block with this bot's blocks. */
    private final float pillarPerBlock;

    private RegionPlaceModel(float pillarPerBlock) {
        this.pillarPerBlock = pillarPerBlock;
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
