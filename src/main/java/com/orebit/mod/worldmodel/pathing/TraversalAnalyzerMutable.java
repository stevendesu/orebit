package com.orebit.mod.worldmodel.pathing;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class TraversalAnalyzerMutable {
    private static final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private static final int[][] neighborOffsets = {
        {-1, 0},
        {1, 0},
        {0, -1},
        {0, 1},
    };
    private static final int[][] neighborOffsetsAll = {
        {-1, 0, 0},
        {1, 0, 0},
        {0, -1, 0},
        {0, 1, 0},
        {0, 0, -1},
        {0, 0, 1}
    };

    private TraversalAnalyzerMutable() {}

    public static TraversalClass classify(Level world, BlockState[] blocks, int x, int y, int z) {
        BlockState ground = getBlockState(blocks, x, y, z);
        BlockState air1 = getBlockState(blocks, x, y + 1, z);
        BlockState air2 = getBlockState(blocks, x, y + 2, z);

        float air1Hardness = air1.getBlock().defaultDestroyTime();
        float air2Hardness = air2.getBlock().defaultDestroyTime();

        // There is no room to stand, and breaking the blocks
        // in the way is non-trivial (e.g. not leaves)
        if (air1Hardness + air2Hardness >= 1.0f) {
            return TraversalClass.BLOCKED;
        }

        // If air2 is a gravity block, then we risk making it
        // fall if we perform operations here
        if (isGravityBlock(air2)) {
            return TraversalClass.BLOCKED;
        }

        // If air1 or the ground is an UNSUPPORTED gravity block,
        // then we risk making it fall if we perform operations
        if (isGravityBlock(ground) && isUnsupported(blocks, x, y, z)) {
            return TraversalClass.BLOCKED;
        }
        if (isGravityBlock(air1) && isUnsupported(blocks, x, y + 1, z)) {
            return TraversalClass.BLOCKED;
        }

        // If breaking a block would cause fluid to flow, then we
        // should avoid operations in the area
        boolean air1RisksFlow = risksFluidFlow(blocks, x, y + 1, z);
        boolean air2RisksFlow = risksFluidFlow(blocks, x, y + 2, z);
        if (air1RisksFlow || air2RisksFlow) {
            return TraversalClass.BLOCKED;
        }

        // Walking here would hurt the player, and breaking it
        // fundamentally alters the path
        if (isSolidHazard(ground)) {
            return TraversalClass.BLOCKED;
        }

        // Walking here would hurt the player if they didn't
        // first break something. But breaking is possible.
        if (isAirHazard(air1) || isAirHazard(air2)) {
            return TraversalClass.SLOW;
        }

        // There's no block, so ability to walk here depends
        // on our ability to place a block
        if (ground.isAir() && isPlaceable(world, blocks, ground, x, y, z)) {
            return TraversalClass.EASY;
        }

        // Since we can't place a block, this is just a fall hazard
        if (ground.isAir()) {
            return TraversalClass.SLOW;
        }

        // If block surface slows movement (e.g., ice, slime, soul sand)
        if (isSlowingSurface(ground)) {
            return TraversalClass.SLOW;
        }

        // Ideal case: solid base + clear headroom
        if (isStandable(world, ground, x, y, z)) {
            return TraversalClass.CLEAR;
        }

        // Catch-all fallback
        return TraversalClass.BLOCKED;
    }

    private static BlockState getBlockState(BlockState[] blocks, int x, int y, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= 16) return Blocks.AIR.defaultBlockState();

        return blocks[y + z * 16 + x * 16 * 16];
    }

    private static boolean isStandable(Level world, BlockState state, int x, int y, int z) {
        scratchPos.set(x, y, z);
        return state.getCollisionShape(world, scratchPos).max(Direction.Axis.Y) >= 0.5;
    }

    private static boolean isReplaceable(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.AIR
            || block == Blocks.TALL_GRASS
            || block == Blocks.SHORT_GRASS
            || block == Blocks.WATER
            || block == Blocks.LAVA;
    }

    public static boolean isPlaceable(Level world, BlockState[] blocks, BlockState state, int x, int y, int z) {
        // Step 1: Block must be replaceable (air, tall grass, fluid, etc.)
        if (!isReplaceable(state)) {
            return false;
        }

        // Step 2: Look for ANY face we could attach to
        for (int[] offset : neighborOffsetsAll) {
            int nx = x + offset[0];
            int ny = y + offset[1];
            int nz = z + offset[2];

            BlockState neighbor = getBlockState(blocks, nx, ny, nz);

            if (!neighbor.isAir() && !isFluid(neighbor)) {
                scratchPos.set(nx, ny, nz); // Only set if needed
                if (!neighbor.getCollisionShape(world, scratchPos).isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isSlowingSurface(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SOUL_SAND
            || block == Blocks.SOUL_SOIL
            || block == Blocks.SLIME_BLOCK
            || block == Blocks.HONEY_BLOCK
            || block == Blocks.ICE
            || block == Blocks.FROSTED_ICE
            || block == Blocks.PACKED_ICE
            || block == Blocks.BLUE_ICE;
    }

    private static boolean isAirHazard(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.LAVA
            || block == Blocks.FIRE
            || block == Blocks.WITHER_ROSE
            || block == Blocks.COBWEB;
    }

    private static boolean isSolidHazard(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.CACTUS
            || block == Blocks.MAGMA_BLOCK
            || block == Blocks.CAMPFIRE
            || block == Blocks.SOUL_CAMPFIRE;
    }

    private static boolean isUnsupported(BlockState[] blocks, int x, int y, int z) {
        BlockState belowState = getBlockState(blocks, x, y - 1, z);
        return belowState.isAir();
    }

    private static final Set<Block> GRAVITY_BLOCKS = Set.of(
        Blocks.SAND,
        Blocks.RED_SAND,
        Blocks.GRAVEL,
        Blocks.ANVIL,
        Blocks.CHIPPED_ANVIL,
        Blocks.DAMAGED_ANVIL,
        Blocks.BLACK_CONCRETE_POWDER,
        Blocks.BLUE_CONCRETE_POWDER,
        Blocks.BROWN_CONCRETE_POWDER,
        Blocks.CYAN_CONCRETE_POWDER,
        Blocks.GRAY_CONCRETE_POWDER,
        Blocks.GREEN_CONCRETE_POWDER,
        Blocks.LIGHT_BLUE_CONCRETE_POWDER,
        Blocks.LIGHT_GRAY_CONCRETE_POWDER,
        Blocks.LIME_CONCRETE_POWDER,
        Blocks.MAGENTA_CONCRETE_POWDER,
        Blocks.ORANGE_CONCRETE_POWDER,
        Blocks.PINK_CONCRETE_POWDER,
        Blocks.PURPLE_CONCRETE_POWDER,
        Blocks.RED_CONCRETE_POWDER,
        Blocks.WHITE_CONCRETE_POWDER,
        Blocks.YELLOW_CONCRETE_POWDER
    );

    private static boolean isGravityBlock(BlockState state) {
        return GRAVITY_BLOCKS.contains(state.getBlock());
    }

    private static boolean isFluid(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.WATER
            || block == Blocks.LAVA;
    }

    private static boolean risksFluidFlow(BlockState[] blocks, int x, int y, int z) {
        for (int[] offset : neighborOffsets) {
            BlockState neighbor = getBlockState(blocks, x + offset[0], y, z + offset[1]);
            // Is the adjacent block a fluid?
            if (isFluid(neighbor)) {
                BlockState belowNeighbor = getBlockState(blocks, x + offset[0], y - 1, z + offset[1]);
                // Is that fluid already flowing down?
                if (!isFluid(belowNeighbor)) {
                    return true;
                }
            }
        }
        return false;
    }


}
