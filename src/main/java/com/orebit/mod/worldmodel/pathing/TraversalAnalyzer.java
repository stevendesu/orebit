package com.orebit.mod.worldmodel.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class TraversalAnalyzer {
    private TraversalAnalyzer() {}

    public static TraversalClass classify(Level world, BlockPos pos) {
        // We can't path in the void
        if (pos.getY() <= world.getMinY()) return TraversalClass.BLOCKED;

        BlockState ground = world.getBlockState(pos);
        BlockState air1 = world.getBlockState(pos.above());
        BlockState air2 = world.getBlockState(pos.above(2));

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
        if (isGravityBlock(ground) && isUnsupported(world, pos)) {
            return TraversalClass.BLOCKED;
        }
        if (isGravityBlock(air1) && isUnsupported(world, pos.above())) {
            return TraversalClass.BLOCKED;
        }

        // If breaking a block would cause fluid to flow, then we
        // should avoid operations in the area
        if (risksFluidFlow(world, pos.above()) || risksFluidFlow(world, pos.above(2))) {
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
        if (ground.isAir() && isPlaceable(world, pos, ground)) {
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
        if (isStandable(world, pos, ground)) {
            return TraversalClass.CLEAR;
        }

        // Catch-all fallback
        return TraversalClass.BLOCKED;
    }

    private static boolean isStandable(Level world, BlockPos pos, BlockState state) {
        return state.getCollisionShape(world, pos).max(Direction.Axis.Y) >= 0.5;
    }

    public static boolean isPlaceable(Level world, BlockPos pos, BlockState state) {
        // Step 1: Block must be replaceable (air, tall grass, fluid, etc.)
        if (!state.canBeReplaced()) {
            return false;
        }

        // Step 2: Look for ANY face we could attach to
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighbor = world.getBlockState(neighborPos);

            if (!neighbor.isAir() && neighbor.getFluidState().isEmpty()) {
                // Check if the face has *any* shape
                if (!neighbor.getCollisionShape(world, neighborPos).isEmpty()) {
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

    private static boolean isUnsupported(Level world, BlockPos pos) {
        BlockState belowState = world.getBlockState(pos.below());
        return belowState.isAir();
    }

    private static boolean isGravityBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SAND
            || block == Blocks.RED_SAND
            || block == Blocks.GRAVEL
            || block == Blocks.ANVIL
            || block == Blocks.CHIPPED_ANVIL
            || block == Blocks.DAMAGED_ANVIL
            || block.toString().contains("CONCRETE_POWDER");
    }

    private static boolean risksFluidFlow(Level world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            FluidState fluid = world.getFluidState(neighborPos);
            // Is the adjacent block a fluid?
            if (!fluid.isEmpty()) {
                BlockPos belowNeighbor = neighborPos.below();
                FluidState fluidBelow = world.getFluidState(belowNeighbor);
                // Is that fluid already flowing down?
                return fluidBelow.isEmpty();
            }
        }
        return false;
    }


}
