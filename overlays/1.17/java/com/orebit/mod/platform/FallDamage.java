package com.orebit.mod.platform;

/**
 * Version-selected vanilla fall-damage arithmetic — the planner's mirror of {@code
 * LivingEntity.calculateFallDamage}, returning <b>hit points</b> for a fall of {@code fallDistance}
 * blocks. Movements that price a drop as cost ask this instead of doing the rounding themselves, so the
 * one place the rule differs between MC versions is this file rather than every damage site.
 *
 * <p><b>Why this is a seam at all.</b> The rounding DIRECTION changed at <b>1.21.5</b>, javap-verified
 * against the cached Mojang-mapped jars across the whole supported matrix:
 * <ul>
 *   <li><b>1.15.2 → 1.21.4</b> (this file): {@code Mth.ceil((d − 3.0F − jumpBoost) × multiplier)} —
 *       byte-identical bytecode over that entire span.</li>
 *   <li><b>1.21.5 → 26.2</b> ({@code overlays/1.21.5}): {@code Mth.floor(calculateFallPower(d) ×
 *       multiplier × FALL_DAMAGE_MULTIPLIER)}, where {@code calculateFallPower(d) = (d + 1.0E-6) −
 *       SAFE_FALL_DISTANCE}. The safe distance became an ATTRIBUTE (default 3.0) and the signature
 *       widened to {@code (double, float)}.</li>
 * </ul>
 * The behavioural consequence is the damage-free window: <b>{@code d ≤ 3.0} here</b>, {@code d < 4.0}
 * from 1.21.5. A 3.5-block fall costs 1 HP on this era and nothing on the next.
 *
 * <p><b>Why the split is invisible to whole-cell drops.</b> {@code ceil(d − 3)} and {@code floor(d + ε −
 * 3)} are EQUAL at every integer {@code d}, so a movement that measures drops in whole cells — {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Fall}, {@code WalkOff} — prices identically on
 * both sides of the boundary and is unaffected by which flavour is composed in. Only SUB-BLOCK fall
 * distances can diverge, and today the only one the planner produces is the jump apex a falling {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour} adds to its drop (≈1.2522 blocks): drop
 * 2 lands at 3.2522 and costs 1 HP here but nothing from 1.21.5.
 *
 * <p><b>Multiplier is deliberately NOT a parameter.</b> Vanilla folds the landing block's softness
 * multiplier INSIDE the rounding; this seam models the {@code multiplier = 1.0} case only. Soft-landing
 * scaling stays where it already lives — {@code Fall}'s {@code fallSoftness} / {@link
 * com.orebit.mod.pathfinding.blockpathfinder.ClutchModel} model, which scales its own term — so this
 * stays the pure rounding rule and gains no argument it has no caller for.
 *
 * <p>Static one-liner, no dispatch: JIT-inlines at every call site (overlay strategy — thin divergent
 * primitive here, pricing logic in core).
 */
public final class FallDamage {
    private FallDamage() {}

    /**
     * Hit points a fall of {@code fallDistance} blocks deals to a bot whose damage-free window is
     * {@code safeFallDistance} blocks (the caps value — {@code 3} by default, mirroring vanilla's 3.0;
     * {@code BotCaps.IMMUNE_FALL} for an invulnerable bot, which drives the result to 0).
     *
     * <p>Pre-1.21.5 form: {@code ceil(d − safe)}, clamped at 0 because vanilla gates the {@code hurt}
     * call on a positive result. Jump boost is not modelled (the bot carries no effects the planner
     * knows about), which is the conservative direction — it can only over-price.
     */
    public static int damageFor(float fallDistance, float safeFallDistance) {
        return Math.max(0, (int) Math.ceil(fallDistance - safeFallDistance));
    }
}
