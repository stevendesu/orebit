package com.orebit.mod;

import com.orebit.mod.platform.BlockShapes;
import com.orebit.mod.platform.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class BotPositioning {

    public static BlockPos findSafeSpotNear(ServerPlayer player, int radius) {
        ServerLevel world = (ServerLevel) Worlds.of(player);
        BlockPos playerPos = player.blockPosition();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue; // circle shape

                for (int dy = -1; dy <= 1; dy++) { // allow vertical range
                    BlockPos candidate = playerPos.offset(dx, dy, dz);
                    BlockState blockBelow = world.getBlockState(candidate.below());
                    BlockState blockAt = world.getBlockState(candidate);
                    BlockState blockAbove = world.getBlockState(candidate.above());

                    boolean isSafe = blockAt.isAir()
                            && blockAbove.isAir()
                            && BlockShapes.isSolidRender(blockBelow, world, candidate.below())
                            && blockAt.getFluidState().isEmpty();

                    if (isSafe) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    public static void faceEachOther(Entity a, Entity b) {
        Vec3 diff = b.position().subtract(a.position());
        float yaw = (float) (Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        a.setYRot(yaw);
        a.setYHeadRot(yaw);
        a.setYBodyRot(yaw);
    }
}
