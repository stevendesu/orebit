package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Validates the rewritten {@link NavBlock} table: per-BlockState fingerprints dedup to a
 * short-sized navtype count, and a handful of well-known blocks pack to the expected fields.
 *
 * <p>Headless caveat: datapack block tags ({@code MINEABLE_WITH_*}) are unbound under
 * {@code Bootstrap.bootStrap()}, so the {@code tool} field collapses to NONE here and the navtype
 * count is the lower (tag-free) bound — at runtime tags are loaded and the count rises modestly
 * (still ≪ 65,536). This test therefore asserts the structural invariants, not the exact count.
 */
public class NavBlockTableTest {

    @Test
    void tableFitsShortAndKnownBlocksClassifyCorrectly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int count = NavBlock.navtypeCount();
        System.out.println("==================== NAVBLOCK TABLE ====================");
        System.out.println("navtypes               : " + count);
        System.out.println("descriptor table       : " + NavBlock.tableBytes() + " bytes");
        System.out.println("fingerprint errors     : " + NavBlock.errorCount());
        System.out.println("========================================================");

        assertTrue(count > 0, "table should not be empty");
        assertTrue(count < 65536, "navtype count must fit a short");
        assertEquals(0, NavBlock.AIR, "air must be navtype 0");

        // Air: passable, replaceable, no collision; precomputed bits: open-for-place, not standable.
        long air = NavBlock.descriptorFor(Blocks.AIR.defaultBlockState());
        assertTrue(NavBlock.isPassable(air), "air passable");
        assertTrue(NavBlock.isReplaceable(air), "air replaceable");
        assertEquals(NavBlock.SHAPE_EMPTY, NavBlock.shape(air));
        assertFalse(NavBlock.isStandable(air), "air not standable");
        assertTrue(NavBlock.isOpenForPlace(air), "air open for placement");

        // Stone: full cube, breakable; precomputed bits: standable + breakable + collision, not open.
        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());
        assertEquals(NavBlock.SHAPE_FULL, NavBlock.shape(stone), "stone is a full cube");
        assertEquals(16, NavBlock.topY(stone), "stone top at 1.0");
        assertTrue(NavBlock.hardness(stone) > 0 && NavBlock.hardness(stone) < 255, "stone breakable");
        assertTrue(NavBlock.isStandable(stone), "stone standable");
        assertTrue(NavBlock.isBreakable(stone), "stone breakable geometry");
        assertTrue(NavBlock.hasCollision(stone), "stone has a buildable face");
        assertFalse(NavBlock.isOpenForPlace(stone), "stone not open for placement");

        // Bedrock: unbreakable sentinel — and the breakable bit reflects it.
        long bedrock = NavBlock.descriptorFor(Blocks.BEDROCK.defaultBlockState());
        assertEquals(255, NavBlock.hardness(bedrock), "bedrock unbreakable");
        assertFalse(NavBlock.isBreakable(bedrock), "bedrock not breakable");

        // Water / lava fluid field (lava re-encoded to 0b11 so low bit = is-fluid, high = is-lava).
        long water = NavBlock.descriptorFor(Blocks.WATER.defaultBlockState());
        long lava = NavBlock.descriptorFor(Blocks.LAVA.defaultBlockState());
        assertEquals(1, NavBlock.fluid(water), "water");
        assertEquals(3, NavBlock.fluid(lava), "lava");
        assertTrue(NavBlock.isFluid(water) && NavBlock.isFluid(lava), "both report is-fluid");
        assertFalse(NavBlock.isLava(water), "water is not lava");
        assertTrue(NavBlock.isLava(lava), "lava is lava");

        // FLUID_SOURCE / FLUID_LEVEL (DESIGN-fluid-flow-prediction.md §3; the level field superseded the
        // single FLUID_MIN_LEVEL bit on 2026-08-20) — the base fields the flood funnel's tier 1 and the
        // swim moves' eye test read. A default fluid state IS the source; blockstate LEVEL 1..7 is flowing
        // at amount 8-level (so LEVEL 7 = amount 1 = the minimum); LEVEL >= 8 is the FALLING (mid-fall
        // column) state — amount 8 but NOT a source, so the source bit stays clear at a full level.
        // isFluidMinLevel is now DERIVED (fluidLevel == 1); the assertions below are unchanged from when it
        // was a stored bit, which is exactly what makes them a regression pin on the widening.
        assertTrue(NavBlock.isFluidSource(water), "default water state is the source (amount 8)");
        assertFalse(NavBlock.isFluidMinLevel(water), "a source is not min-level");
        assertEquals(8, NavBlock.fluidLevel(water), "a water source is amount 8");
        long waterFlow = NavBlock.descriptorFor(
                Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 1));
        assertFalse(NavBlock.isFluidSource(waterFlow), "flowing water (amount 7) is not a source");
        assertFalse(NavBlock.isFluidMinLevel(waterFlow), "…and not yet minimum");
        assertEquals(7, NavBlock.fluidLevel(waterFlow), "blockstate LEVEL 1 = amount 7");
        long waterMin = NavBlock.descriptorFor(
                Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 7));
        assertFalse(NavBlock.isFluidSource(waterMin), "min-level water is not a source");
        assertTrue(NavBlock.isFluidMinLevel(waterMin), "LEVEL 7 = amount 1 = min level");
        assertEquals(1, NavBlock.fluidLevel(waterMin), "blockstate LEVEL 7 = amount 1");
        long waterFalling = NavBlock.descriptorFor(
                Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8));
        assertFalse(NavBlock.isFluidSource(waterFalling), "falling water is amount 8 but NOT a source");
        assertFalse(NavBlock.isFluidMinLevel(waterFalling), "…and not min-level");
        assertEquals(8, NavBlock.fluidLevel(waterFalling),
                "falling water is amount 8 — full height but not a source; !isFluidSource && level==8 is"
                        + " the waterfall-middle fact the old two-bit encoding could not express");
        // Lava variants: same encoding, and the deliberately fluid-agnostic minimum — amount==1 exactly,
        // so overworld lava at amount 2 (dropOff 2, cannot actually spread) reads as spread-capable:
        // conservative, errs wet, the safe direction for lava (§3 — do not make it dimension-aware).
        assertTrue(NavBlock.isFluidSource(lava), "default lava state is the source");
        assertFalse(NavBlock.isFluidMinLevel(lava), "a lava source is not min-level");
        assertEquals(8, NavBlock.fluidLevel(lava), "a lava source is amount 8");
        long lavaAmount2 = NavBlock.descriptorFor(
                Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, 6));
        assertFalse(NavBlock.isFluidSource(lavaAmount2), "flowing lava (amount 2) is not a source");
        assertFalse(NavBlock.isFluidMinLevel(lavaAmount2),
                "amount 2 is NOT min-level even though overworld lava can't spread from it (§3 conservatism)");
        long lavaMin = NavBlock.descriptorFor(
                Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, 7));
        assertTrue(NavBlock.isFluidMinLevel(lavaMin), "lava at amount 1 is min-level");
        assertEquals(1, NavBlock.fluidLevel(lavaMin), "lava blockstate LEVEL 7 = amount 1");
        assertEquals(2, NavBlock.fluidLevel(lavaAmount2), "lava blockstate LEVEL 6 = amount 2");
        // Non-fluids carry neither bit.
        assertFalse(NavBlock.isFluidSource(stone) || NavBlock.isFluidMinLevel(stone), "stone: no fluid bits");
        assertFalse(NavBlock.isFluidSource(air) || NavBlock.isFluidMinLevel(air), "air: no fluid bits");
        assertEquals(0, NavBlock.fluidLevel(stone), "a non-fluid cell has NO level, not level 1");
        assertEquals(0, NavBlock.fluidLevel(air), "a non-fluid cell has NO level, not level 1");

        // The eye-submersion threshold this field exists to serve (MovementContext.EYE_SUBMERGE_MIN_AMOUNT
        // = 6): a standing bot's eyes sit at feetY+1.62, so the head cell's fluid must clear 0.62 of the
        // cell, and getOwnHeight is amount/9F. Pin both sides of the boundary against the raw arithmetic
        // so a future change to either the constant or the encoding has to face the physics.
        assertTrue(6 / 9.0f > 0.62f, "amount 6 clears the standing eye");
        assertFalse(5 / 9.0f > 0.62f, "amount 5 does not");

        // Bottom slab: solid lower half, top at 0.5; top slab: solid upper half.
        BlockState slabBottom = Blocks.OAK_SLAB.defaultBlockState(); // default type = BOTTOM
        long sb = NavBlock.descriptorFor(slabBottom);
        assertEquals(NavBlock.SHAPE_SLAB_BOTTOM, NavBlock.shape(sb), "bottom slab shape");
        assertEquals(8, NavBlock.topY(sb), "bottom slab top at 0.5");
        BlockState slabTop = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        assertEquals(NavBlock.SHAPE_SLAB_TOP, NavBlock.shape(NavBlock.descriptorFor(slabTop)), "top slab shape");

        // Stairs: STAIR shape (facing carried by solid faces, not a separate field).
        assertEquals(NavBlock.SHAPE_STAIR,
                NavBlock.shape(NavBlock.descriptorFor(Blocks.OAK_STAIRS.defaultBlockState())), "stairs shape");

        // Ladder: climbable.
        assertTrue(NavBlock.isClimbable(NavBlock.descriptorFor(Blocks.LADDER.defaultBlockState())), "ladder climbable");

        // Sand: gravity.
        assertTrue(NavBlock.hasGravity(NavBlock.descriptorFor(Blocks.SAND.defaultBlockState())), "sand gravity");

        // Waterlogged fence: waterloggable AND reports water (the derived "waterlogged now") — and its
        // fluid state IS a water source, so FLUID_SOURCE rides along (the bit does NOT imply an open
        // water cell; the funnel's genuine-open test is what keeps waterlogged partials out of tier 1 —
        // DESIGN-fluid-flow-prediction.md §8.2, NavBlock.isFluidSource's Javadoc).
        BlockState wlFence = Blocks.OAK_FENCE.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        long wf = NavBlock.descriptorFor(wlFence);
        assertTrue(NavBlock.isWaterloggable(wf), "fence waterloggable");
        assertTrue(NavBlock.isWaterloggedNow(wf), "waterlogged fence reports water");
        assertTrue(NavBlock.isFluidSource(wf), "a waterlogged state's fluid IS a water source");
        assertFalse(NavBlock.isFluidMinLevel(wf), "…at amount 8, so never min-level");
        assertEquals(8, NavBlock.fluidLevel(wf), "…and carries the source's amount");

        // Cap headroom: the FLUID_SOURCE/FLUID_LEVEL split fans water and lava per source and per AMOUNT
        // (base fields — they split navtypes), so the 2026-08-20 widening from one min-level bit to a
        // 3-bit level field fans each fluid further than the fluid-flow arc did. The table must stay
        // inside TraversalGrid's 10-bit navtype capacity — asserted as a BOUND, never an exact count (the
        // count moves with MC version, loaded tags, and mining.protectedBlocks at runtime:
        // no-hardcoded-navtype-counts).
        assertTrue(NavBlock.navtypeCount() <= TraversalGrid.NAVTYPE_CAPACITY,
                "navtype count " + NavBlock.navtypeCount() + " must fit the "
                        + TraversalGrid.NAVTYPE_CAPACITY + "-entry grid capacity");
    }
}
