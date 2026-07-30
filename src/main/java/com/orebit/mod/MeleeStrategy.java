package com.orebit.mod;

import net.minecraft.world.entity.Mob;

/**
 * The default combat Strategy — plain melee (zombies, spiders, drowned, anything unclassified):
 * face the threat, close to reach, and land only FULL-CHARGE hits (uncharged spam deals ~20%
 * damage and no knockback — the verified {@code 0.2 + charge² × 0.8} scaling). The threat closes
 * the distance itself most of the time (it is targeting the bot by definition), so this stands
 * its ground within reach and chases only when the mob kites away.
 */
class MeleeStrategy extends MobStrategy {

    @Override
    boolean handles(Mob mob) {
        return true; // the fallback — registered LAST
    }

    @Override
    void engage(AllyBotEntity bot, Mob target) {
        if (distanceTo(bot, target) > MELEE_REACH) {
            closeIn(bot, target);
            return;
        }
        bot.setForward(0.0f);
        face(bot, target);
        if (charge(bot) >= 1.0f) {
            strike(bot, target);
        }
    }
}
