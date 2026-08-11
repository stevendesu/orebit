package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * 1.19.4 → 1.20.4 flavour of the player-step pin — see {@code overlays/1.17}'s copy for the full
 * rationale (why {@code ServerPlayer} carries a 1.0 step, why only a clientless bot ever spends it, and
 * what it cost us).
 *
 * <p>The only thing that differs here is the accessor: at <b>1.19.4</b> {@code Entity.maxUpStep} went
 * {@code private} and gained {@code setMaxUpStep(float)} (javap-verified — the field is
 * {@code public float} through 1.19.3 and {@code private float} with a setter from 1.19.4). The value
 * being written, and the reason for writing it, are identical.
 *
 * <p>{@code ServerPlayer.<init>} still overwrites the inherited 0.6 with {@code 1.0} on every version in
 * this span (verified 1.19.4 / 1.20 / 1.20.1 / 1.20.2 / 1.20.3 / 1.20.4), so the pin is still load-bearing
 * — it stops being needed only at 1.20.5, where the constructor drops the assignment entirely and step
 * height becomes the {@code STEP_HEIGHT} attribute.
 */
public final class StepHeight {
    private StepHeight() {}

    /** Vanilla's player step height — what {@code LivingEntity} sets before {@code ServerPlayer} clobbers it. */
    public static final float PLAYER_STEP = 0.6f;

    /** Pin {@code bot}'s auto-step to {@link #PLAYER_STEP} through the setter this era introduced. */
    public static void pinToPlayerDefault(ServerPlayer bot) {
        bot.setMaxUpStep(PLAYER_STEP);
    }
}
