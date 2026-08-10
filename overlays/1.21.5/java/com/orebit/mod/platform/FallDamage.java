package com.orebit.mod.platform;

/**
 * 1.21.5+ flavour of the fall-damage rule — see {@code overlays/1.17}'s copy for the full seam rationale
 * and the version sweep. Vanilla rewrote {@code LivingEntity.calculateFallDamage} here:
 *
 * <pre>
 *   calculateFallDamage(double d, float mult)
 *       = Mth.floor(calculateFallPower(d) * mult * getAttributeValue(FALL_DAMAGE_MULTIPLIER))
 *   calculateFallPower(double d)
 *       = (d + 1.0E-6) - getAttributeValue(SAFE_FALL_DISTANCE)
 * </pre>
 *
 * Three changes at once, all javap-verified on 1.21.5 and confirmed unchanged through 26.2:
 * <ul>
 *   <li><b>{@code ceil} → {@code floor}</b>. This is the one that moves behaviour: the damage-free
 *       window widens from {@code d ≤ 3.0} to {@code d < 4.0}. A 3.5-block fall is free here and costs
 *       1 HP on the older flavour. Owner-measured in-game (2026-08-10): walk-off drops of 3.0, 3.5 and
 *       3.9375 all dealt zero damage, while a JUMP from 3.0 — apex ≈1.2522, so {@code d ≈ 4.2522} —
 *       dealt damage. Both match this expression exactly.</li>
 *   <li><b>{@code +1.0E-6} epsilon</b>, applied before the subtraction. It exists so a fall of exactly
 *       4.0 floors to 1 rather than being lost to float representation, which is why the window is
 *       {@code d < 4.0} and not {@code d ≤ 4.0}. Reproduced faithfully below.</li>
 *   <li><b>Safe distance became an ATTRIBUTE</b> ({@code SAFE_FALL_DISTANCE}, default 3.0), joined by
 *       {@code FALL_DAMAGE_MULTIPLIER} (default 1.0). The caps value passed in stands in for the
 *       former; the latter is not modelled — see the multiplier note on the baseline copy.</li>
 * </ul>
 *
 * <p>Whole-cell drops price identically to the baseline flavour ({@code ceil} and {@code floor} agree at
 * integer distances), so composing this in changes nothing for {@code Fall}/{@code WalkOff}; it moves
 * only the sub-block distances, which today means the falling-{@code Parkour} apex.
 */
public final class FallDamage {
    private FallDamage() {}

    /**
     * Hit points a fall of {@code fallDistance} blocks deals to a bot whose damage-free window is
     * {@code safeFallDistance} blocks (the caps value, standing in for vanilla's {@code
     * SAFE_FALL_DISTANCE} attribute — {@code 3} by default, {@code BotCaps.IMMUNE_FALL} for an
     * invulnerable bot, which drives the result to 0).
     *
     * <p>{@code floor(d + 1e-6 − safe)}, clamped at 0 because vanilla gates the {@code hurt} call on a
     * positive result (the raw expression goes negative for any comfortable fall).
     */
    public static int damageFor(float fallDistance, float safeFallDistance) {
        return Math.max(0, (int) Math.floor(fallDistance + 1.0e-6f - safeFallDistance));
    }
}
