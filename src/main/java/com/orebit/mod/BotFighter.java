package com.orebit.mod;

import java.util.List;

import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.Worlds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * Self-defense — the CROSS-CUTTING combat interrupt (DESIGN-bot-abilities.md §2.3/§5). NOT a
 * {@link AllyBotEntity.Mode}: {@link #defendTick} runs BEFORE the mode dispatch each tick (the
 * {@code BotPortalFollower.followThroughPortal} consumed-tick precedent) and, while a threat is
 * engaged, CONSUMES the tick — the current mode's machine simply isn't stepped, its state frozen
 * in place, and it resumes exactly where it was when combat ends (un-ticked components keep
 * state; a paused GATHER's un-re-requested break even aborts cleanly by the let-go rule). This is
 * the live analogue of the Phase-7 StateStack push/pop contract without building the stack.
 *
 * <p><b>Threat model</b> (verify-don't-assume — the mob's own state, not proximity heuristics):
 * a threat is a live {@link Mob} whose {@code getTarget() == bot} within {@code combat.scanRadius}
 * — the mob has DECLARED the bot its target (the vanilla targeting facts: a survival fake player
 * is targeted exactly like a real player; only {@code abilities.invulnerable} exempts it, so
 * under the invulnerable default this interrupt is naturally quiescent). Nearest threat wins;
 * per-archetype behavior is a {@link MobStrategy} (first-match registration order — creeper,
 * skeleton family, melee fallback).
 */
final class BotFighter {

    private final AllyBotEntity bot;

    /** Strategy registration order = match priority (the melee fallback handles everything). */
    private static final List<MobStrategy> STRATEGIES =
            List.of(new CreeperStrategy(), new SkeletonStrategy(), new MeleeStrategy());

    /** Whether the previous tick was consumed by combat (edge-detects engage/disengage). */
    private boolean engaged;
    /** The threat engaged last tick (for the disengage/retarget chat edge only — never a latch). */
    private Mob lastThreat;

    BotFighter(AllyBotEntity bot) {
        this.bot = bot;
    }

    /** Whether combat consumed the LAST tick (read-only; harness + diagnostics). */
    boolean engaged() {
        return engaged;
    }

    /**
     * One pre-dispatch defense check. Returns {@code true} when combat CONSUMED this tick (a
     * threat is engaged — the caller skips the mode dispatch); {@code false} hands the tick to
     * the normal mode machine. All transitions are state-driven: a threat exists while some mob
     * targets the bot; disengage the tick none does (death, distraction, despawn).
     */
    boolean defendTick() {
        if (!ConfigLoader.config().defend()) {
            if (engaged) disengage();
            return false;
        }
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        final Mob threat = selectThreat(level);
        if (threat == null) {
            if (engaged) disengage();
            return false;
        }
        if (!engaged || threat != lastThreat) {
            // Engage/retarget edge: reset the drive so the strategy plans fresh, and say so once.
            bot.navigator().clearPlan();
            bot.navigator().clearNavGaveUp();
            bot.chat("[bot] defending myself — " + threat.getName().getString() + "!");
        }
        engaged = true;
        lastThreat = threat;
        strategyFor(threat).engage(bot, threat);
        return true;
    }

    /** Leave combat: drop the plan combat was driving and let the paused mode resume cleanly. */
    private void disengage() {
        engaged = false;
        lastThreat = null;
        bot.navigator().clearPlan();
        bot.navigator().clearNavGaveUp();
    }

    /**
     * The nearest live mob that has DECLARED the bot its target, within {@code combat.scanRadius}.
     * One bounded entity query per tick (cold — an AABB entity-section scan; radius default 16).
     */
    private Mob selectThreat(ServerLevel level) {
        final int r = ConfigLoader.config().scanRadius();
        final AABB box = new AABB(bot.getX() - r, bot.getY() - r, bot.getZ() - r,
                bot.getX() + r, bot.getY() + r, bot.getZ() + r);
        Mob nearest = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, box,
                m -> m.isAlive() && m.getTarget() == bot)) {
            final double d = m.distanceToSqr(bot.getX(), bot.getY(), bot.getZ());
            if (d < bestD) { bestD = d; nearest = m; }
        }
        return nearest;
    }

    private MobStrategy strategyFor(Mob threat) {
        for (MobStrategy s : STRATEGIES) {
            if (s.handles(threat)) return s;
        }
        return STRATEGIES.get(STRATEGIES.size() - 1); // unreachable — the fallback handles all
    }
}
