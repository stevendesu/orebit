package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;

/**
 * {@link MobKinds} flavor for MC 1.21.11+ (incl. the 26.x era): the skeleton family moved into
 * the {@code monster.skeleton} subpackage (javap-pinned — 1.21.10 still has it under
 * {@code monster}). See the 1.17 baseline for the contract.
 */
public final class MobKinds {

    private MobKinds() {}

    /** Whether {@code mob} is the bow-skeleton family (skeleton/stray/bogged/wither skeleton). */
    public static boolean isSkeletonFamily(Mob mob) {
        return mob instanceof AbstractSkeleton;
    }

    /** Spawn a plain zombie at {@code (x,y,z)} (harness use — the combat autotest's sparring
     *  partner; the Zombie CLASS rode the same 1.21.11 package move as the skeleton family). */
    public static Mob spawnZombieAt(ServerLevel level, double x, double y, double z) {
        final Zombie zombie = new Zombie(level);
        zombie.setPos(x, y, z);
        level.addFreshEntity(zombie);
        return zombie;
    }
}
