package com.orebit.mod;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;

/**
 * The creeper Strategy — hit-and-retreat knockback discipline built on the source-verified swell
 * mechanics (DESIGN-bot-abilities.md §5): a creeper starts swelling within 3.0 blocks
 * (distSq&lt;9), KEEPS swelling while ≤7 with line of sight, explodes at 30 swell ticks
 * (power 3 — zero damage beyond 6 blocks), and the fuse COUNTS DOWN (never resets) when broken.
 * So the bot:
 * <ul>
 *   <li>never loiters inside the {@link #STANDOFF} band while its attack charge refills —
 *       it backs off toward {@link #DISENGAGE} (past the 7-block keep-swelling bound, letting
 *       the fuse drain);</li>
 *   <li>at FULL charge darts in and lands a SPRINT hit ({@code sprint + charge>0.9} adds the
 *       +0.5 knockback bonus — verified — punting the creeper 3–5 blocks), then the next tick's
 *       sub-1.0 charge naturally re-enters the back-off branch.</li>
 * </ul>
 * Numbers: STANDOFF 4.0 &gt; the 3.0 swell trigger; STRIKE 2.9 ≤ melee reach; DISENGAGE 8.0 &gt;
 * the 7.0 keep-swelling bound — all derived from the verified constants, none tuned.
 */
class CreeperStrategy extends MobStrategy {

    private static final double STANDOFF = 4.0;
    private static final double STRIKE = 2.9;
    private static final double DISENGAGE = 8.0;

    @Override
    boolean handles(Mob mob) {
        return mob instanceof Creeper;
    }

    @Override
    void engage(AllyBotEntity bot, Mob target) {
        final double dist = distanceTo(bot, target);
        if (charge(bot) < 1.0f) {
            // Recharging: stay out of the swell band; inside it, open the distance past the
            // keep-swelling bound so the fuse drains instead of accumulating.
            if (dist < STANDOFF) {
                backOff(bot, target, DISENGAGE);
            } else {
                bot.setForward(0.0f);
                face(bot, target);
            }
            return;
        }
        // Fully charged: dart in and punt it. Sprint arms the +0.5 knockback bonus (and a sprint
        // hit is never a sweep, so nearby pets are safe); vanilla cancels sprint after the hit.
        if (dist > STRIKE) {
            bot.setSprinting(true);
            closeIn(bot, target);
            return;
        }
        bot.setSprinting(true);
        strike(bot, target);
    }
}
