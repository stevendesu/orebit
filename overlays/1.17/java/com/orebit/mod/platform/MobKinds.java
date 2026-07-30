package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Zombie;

/**
 * Version-selected mob-archetype tests for the combat strategies (DESIGN-bot-abilities.md §5) —
 * confines the one drifting mob CLASS reference to an adapter so the strategy classes stay in
 * version-stable core. (Creeper never moved; the skeleton family did.)
 *
 * <p><b>This 1.17 baseline</b> (MC 1.17.1 → 1.21.10): the skeleton family root is
 * {@code net.minecraft.world.entity.monster.AbstractSkeleton}. Overridden at <b>1.21.11</b>
 * (moved into the {@code monster.skeleton} subpackage — javap-pinned; 1.21.10 still `monster`),
 * which stays valid through the 26.x era.
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
