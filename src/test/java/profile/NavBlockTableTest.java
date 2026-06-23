package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
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

        // Air: passable, replaceable, no collision.
        long air = NavBlock.descriptorFor(Blocks.AIR.defaultBlockState());
        assertTrue(NavBlock.isPassable(air), "air passable");
        assertTrue(NavBlock.isReplaceable(air), "air replaceable");
        assertEquals(NavBlock.SHAPE_EMPTY, NavBlock.shape(air));

        // Stone: full cube, all faces solid, breakable.
        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());
        assertEquals(NavBlock.SHAPE_FULL, NavBlock.shape(stone), "stone is a full cube");
        assertEquals(16, NavBlock.topY(stone), "stone top at 1.0");
        for (Direction d : Direction.values()) {
            assertTrue(NavBlock.isFaceSolid(stone, d), "stone face solid: " + d);
        }
        assertTrue(NavBlock.hardness(stone) > 0 && NavBlock.hardness(stone) < 255, "stone breakable");

        // Bedrock: unbreakable sentinel.
        long bedrock = NavBlock.descriptorFor(Blocks.BEDROCK.defaultBlockState());
        assertEquals(255, NavBlock.hardness(bedrock), "bedrock unbreakable");

        // Water / lava fluid field.
        assertEquals(1, NavBlock.fluid(NavBlock.descriptorFor(Blocks.WATER.defaultBlockState())), "water");
        assertEquals(2, NavBlock.fluid(NavBlock.descriptorFor(Blocks.LAVA.defaultBlockState())), "lava");

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
