package com.orebit.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;

/**
 * One per-mob-archetype combat Strategy (DESIGN-bot-abilities.md §5; the owner's
 * abstract-classes-over-enums / one-class-per-variant principle — no mob-type switch anywhere).
 * {@link BotFighter} picks the first registered strategy whose {@link #handles} accepts the
 * threat and calls {@link #engage} once per consumed combat tick.
 *
 * <p>Shared combat vocabulary lives here as protected finals so every strategy prices the same
 * verified vanilla facts (mechanics javap/source-verified 1.17.1→26.2):
 * <ul>
 *   <li>{@link #MELEE_REACH} 3.0 — the survival attack range the server enforces on real
 *       clients; {@code Player#attack} itself has NO reach gate, so the bot self-imposes it.</li>
 *   <li>full charge = {@code getAttackStrengthScale(0.5f) >= 1.0} — the REAL vanilla ticker
 *       (advanced by the bot's own {@code Player.tick}), never a derived clock. Uncharged spam
 *       deals ~20% damage; waiting for 1.0 is both strongest and knockback-eligible.</li>
 * </ul>
 */
abstract class MobStrategy {

    /** Survival melee reach (blocks, centre-to-centre) — self-imposed, see the class doc. */
    protected static final double MELEE_REACH = 3.0;

    /** Whether this strategy fights {@code mob}. Checked in {@link BotFighter}'s registration order. */
    abstract boolean handles(Mob mob);

    /** Drive one engagement tick against {@code target} (the tick is already consumed). */
    abstract void engage(AllyBotEntity bot, Mob target);

    // ---- shared verbs -------------------------------------------------------------------------

    /** Face the target (knockback direction in {@code Player#attack} derives from the ATTACKER's
     *  yaw — face before every hit or the knockback goes sideways). */
    protected void face(AllyBotEntity bot, Mob target) {
        final double dx = target.getX() - bot.getX();
        final double dy = target.getEyeY() - bot.getEyeY();
        final double dz = target.getZ() - bot.getZ();
        final double distXZ = Math.sqrt(dx * dx + dz * dz);
        final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        final float pitch = (float) Math.toDegrees(-Math.atan2(dy, distXZ));
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setXRot(pitch);
    }

    /** One full-charge melee hit: equip the best weapon, face, vanilla {@code Player#attack}
     *  (crit/sweep/knockback/enchants all inside), swing. Call only at full charge and in reach. */
    protected void strike(AllyBotEntity bot, Mob target) {
        new com.orebit.mod.platform.BotInventory(bot).equipBestWeapon();
        face(bot, target);
        bot.attack(target);
        bot.swing(InteractionHand.MAIN_HAND);
        bot.combatStrikes++; // observation only (harness verdicts) — never read by behavior
    }

    /** The real attack charge in [0,1] (see the class doc). */
    protected float charge(AllyBotEntity bot) {
        return bot.getAttackStrengthScale(0.5f);
    }

    /** Centre-to-centre distance bot→target. */
    protected double distanceTo(AllyBotEntity bot, Mob target) {
        final double dx = target.getX() - bot.getX();
        final double dy = target.getY() - bot.getY();
        final double dz = target.getZ() - bot.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Chase the target's LIVE position (default planner tolerance; the mob moves every tick —
     *  the exact-goal moved-cell rebuild keeps the plan honest). */
    protected void closeIn(AllyBotEntity bot, Mob target) {
        bot.navigator().driveToward(target.getX(), target.getY(), target.getZ(),
                floorCellOf(bot, target));
    }

    /** Retreat: drive toward a cell {@code away} blocks straight AWAY from the target (planner
     *  tolerance absorbs terrain; an unreachable retreat falls back to standing ground —
     *  {@code navGaveUp} is consumed so the follow-up tick fights from where it is). */
    protected void backOff(AllyBotEntity bot, Mob target, double away) {
        final double dx = bot.getX() - target.getX();
        final double dz = bot.getZ() - target.getZ();
        final double d = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        final double ax = bot.getX() + dx / d * away;
        final double az = bot.getZ() + dz / d * away;
        final BlockPos goal = new BlockPos((int) Math.floor(ax),
                bot.blockPosition().getY() - 1, (int) Math.floor(az));
        bot.navigator().driveToward(ax, bot.getY(), az, goal);
        if (bot.navigator().navGaveUp()) { // cornered — stand and fight from here
            bot.navigator().clearNavGaveUp();
            bot.navigator().clearPlan();
        }
    }

    /** The target's floor cell, partial-floor-aware (the sweep-drop rule: an entity standing IN a
     *  15/16 block's cell floors THERE, not one below). Reads the level through the version-stable
     *  {@code Worlds} seam off the BOT (same level — the fighter only ever engages same-level mobs;
     *  {@code Entity#level()} itself drifted field→method across the range). */
    protected static BlockPos floorCellOf(AllyBotEntity bot, Mob target) {
        final BlockPos feet = target.blockPosition();
        return com.orebit.mod.platform.Worlds.of(bot).getBlockState(feet).isAir()
                ? feet.below() : feet;
    }
}
