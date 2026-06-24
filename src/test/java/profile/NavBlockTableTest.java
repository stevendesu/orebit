package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

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

        // Waterlogged fence: waterloggable AND reports water (the derived "waterlogged now").
        BlockState wlFence = Blocks.OAK_FENCE.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        long wf = NavBlock.descriptorFor(wlFence);
        assertTrue(NavBlock.isWaterloggable(wf), "fence waterloggable");
        assertTrue(NavBlock.isWaterloggedNow(wf), "waterlogged fence reports water");
    }
}
