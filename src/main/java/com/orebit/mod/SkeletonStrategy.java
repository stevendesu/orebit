package com.orebit.mod;

import com.orebit.mod.platform.MobKinds;

import net.minecraft.world.entity.Mob;

/**
 * The skeleton-family Strategy (skeleton/stray/bogged — anything extending
 * {@code AbstractSkeleton}): CLOSE FAST. A bow skeleton has no melee goal and keeps shooting at
 * point-blank (source-verified), its arrows are aimed at the bot's CURRENT position with no
 * velocity lead, and its inter-shot gap is 40 ticks (20 on hard) — so sprinting straight in
 * during reload windows loses less health than any standoff, and once in reach the fight is a
 * plain full-charge melee. (The perpendicular-strafe/LOS-break refinement is a documented later
 * pass — the driver has no strafe primitive yet; DESIGN-bot-abilities.md §5.)
 */
class SkeletonStrategy extends MobStrategy {

    @Override
    boolean handles(Mob mob) {
        return MobKinds.isSkeletonFamily(mob); // the family root CLASS moved packages at 1.21.11
    }

    @Override
    void engage(AllyBotEntity bot, Mob target) {
        if (distanceTo(bot, target) > MELEE_REACH) {
            bot.setSprinting(true); // close the range fast — every tick outside reach is an arrow window
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
