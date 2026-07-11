package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * Stairs classify with a directional standing surface (the fix for "walk-up mispriced as a jump"). A BOTTOM
 * stair carries its FACING/HALF in the {@link NavBlock} fingerprint, and {@link MovementContext#directionalTopY}
 * reads its edge surfaces as HIGH 16/16 on the FACING side / LOW 8/16 on the opposite + perpendicular edges;
 * a TOP stair is flat 16/16 both ways. The empirical voxel mapping (StairVoxelProbe): a BOTTOM stair FACING
 * EAST has its 16/16 half on the +X side, so climbing +X (approaching the -X low front) reads as a +0.5
 * step-assist rather than a +1.0 jump.
 */
class StairNavTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    private static BlockState stair(Direction facing, Half half) {
        return Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);
    }

    // ---- (1) NavBlock classifies the stair facing/half ----------------------------------------

    @Test
    void bottomStairClassifiesFacingAndHalf() {
        long east = NavBlock.descriptorFor(stair(Direction.EAST, Half.BOTTOM));
        assertTrue(NavBlock.isStair(east), "a *_STAIRS state classifies as SHAPE_STAIR");
        assertEquals(1, NavBlock.stairFacing(east), "EAST facing is ordinal 1 (0=N 1=E 2=S 3=W)");
        assertEquals(0, NavBlock.stairHalf(east), "a BOTTOM stair has stairHalf 0");
        assertTrue(NavBlock.isStandable(east), "a stair is a standable floor");

        long northTop = NavBlock.descriptorFor(stair(Direction.NORTH, Half.TOP));
        assertEquals(0, NavBlock.stairFacing(northTop), "NORTH facing is ordinal 0");
        assertEquals(1, NavBlock.stairHalf(northTop), "a TOP stair has stairHalf 1");

        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());
        assertFalse(NavBlock.isStair(stone), "a full block is not a stair");
        assertEquals(0, NavBlock.stairFacing(stone), "non-stairs carry stairFacing 0");
    }

    // ---- (2) directionalTopY reads the BOTTOM stair edges directionally, TOP flat --------------

    @Test
    void directionalTopYReadsBottomStairEdges() {
        // A single stone section with a BOTTOM stair FACING=EAST at (2,5,8). Its HIGH 16/16 half is on +X.
        long eastBottom = NavBlock.descriptorFor(stair(Direction.EAST, Half.BOTTOM));
        MovementContext ctx = new MovementContext(
                new NavGridView(0, new java.util.concurrent.ConcurrentHashMap<>()), BotCaps.DEFAULT);

        assertEquals(16, ctx.directionalTopY(eastBottom, 1, 0), "+X edge (FACING side) is the 16/16 high half");
        assertEquals(8, ctx.directionalTopY(eastBottom, -1, 0), "-X edge (low front) is the 8/16 half");
        assertEquals(8, ctx.directionalTopY(eastBottom, 0, 1), "a perpendicular edge is the low 8/16 (straight approx)");
        assertEquals(8, ctx.directionalTopY(eastBottom, 0, -1), "the other perpendicular edge is the low 8/16 too");

        // The climb rise a +X step-assist onto this stair sees: from a full block (16) one level up onto the
        // stair's -X low front (8) is 16 + 8 - 16 = 8 <= STEP_ASSIST_MAX_RISE (a walk, not a jump).
        assertTrue(MovementContext.rise(1, ctx.directionalTopY(eastBottom, -1, 0), 16)
                <= MovementContext.STEP_ASSIST_MAX_RISE, "a stair step-up is a step-assist rise, not a jump");

        // A TOP stair is flat 16/16 everywhere — no directional handling.
        long eastTop = NavBlock.descriptorFor(stair(Direction.EAST, Half.TOP));
        assertEquals(16, ctx.directionalTopY(eastTop, 1, 0), "a TOP stair top is flat 16/16 on the FACING edge");
        assertEquals(16, ctx.directionalTopY(eastTop, -1, 0), "a TOP stair top is flat 16/16 on the opposite edge");

        // A non-stair full block is always its scalar topY, both edges.
        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());
        assertEquals(16, ctx.directionalTopY(stone, 1, 0), "a full block reads its scalar topY (16), directionless");
    }

    // ---- (3) facing→high-edge mapping holds for all four cardinals -----------------------------

    @Test
    void highHalfIsOnTheFacingSideForEveryCardinal() {
        MovementContext ctx = new MovementContext(
                new NavGridView(0, new java.util.concurrent.ConcurrentHashMap<>()), BotCaps.DEFAULT);
        // N=(0,-1) E=(1,0) S=(0,1) W=(-1,0) — the high 16/16 half is on the FACING side (StairVoxelProbe).
        int[][] facingEdge = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < dirs.length; i++) {
            long d = NavBlock.descriptorFor(stair(dirs[i], Half.BOTTOM));
            int fx = facingEdge[i][0], fz = facingEdge[i][1];
            assertEquals(16, ctx.directionalTopY(d, fx, fz),
                    dirs[i] + " stair: the FACING edge is the 16/16 high half");
            assertEquals(8, ctx.directionalTopY(d, -fx, -fz),
                    dirs[i] + " stair: the opposite edge is the 8/16 low front");
        }
    }
}
