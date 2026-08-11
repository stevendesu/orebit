package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * 1.20.5 → 26.2 flavour of the player-step pin: <b>deliberately a no-op</b>. See {@code overlays/1.17}'s
 * copy for the full rationale.
 *
 * <p>Mojang fixed this upstream at <b>1.20.5</b>, in two parts (javap-verified over the cached
 * Mojang-mapped jars):
 * <ul>
 *   <li>{@code Entity.maxUpStep} — the mutable field, its setter, and the whole field mechanism — is
 *       <b>gone</b>. {@code Entity.maxUpStep()} returns a constant {@code 0.0} and
 *       {@code LivingEntity.maxUpStep()} reads {@code Attributes.STEP_HEIGHT} via
 *       {@code getAttributeValue}. {@code Attributes} registers {@code step_height} with a default of
 *       {@code 0.6}, and {@code Player.createAttributes()} adds it without an override.</li>
 *   <li>{@code ServerPlayer.<init>} <b>no longer touches step height at all</b> — the {@code fconst_1}
 *       assignment that made a server-side player able to climb a full block is simply absent from
 *       1.20.5 onward.</li>
 * </ul>
 * So the bot already inherits the player's 0.6 here and there is nothing to correct. That is exactly why
 * the staircase repro passes on 1.21.11 and fails on 1.20.1 with byte-identical mod code.
 *
 * <p>The method is kept (rather than the call being version-gated at the call site) so the common caller
 * stays a single unconditional line and the divergence lives entirely in {@code platform/} — the overlay
 * strategy's rule: thin divergent primitive here, logic in core. A future MC that reintroduces a
 * non-player step default gets a new flavour, not a new branch in {@code AllyBotEntity}.
 */
public final class StepHeight {
    private StepHeight() {}

    /** Vanilla's player step height. Informational on this era — the attribute already defaults to it. */
    public static final float PLAYER_STEP = 0.6f;

    /**
     * No-op: from 1.20.5 the {@code STEP_HEIGHT} attribute already defaults to {@link #PLAYER_STEP} and
     * {@code ServerPlayer} no longer overrides it. The parameter is accepted for a stable seam signature
     * across every era and is deliberately unused.
     */
    public static void pinToPlayerDefault(ServerPlayer bot) {
        // Intentionally empty — see the class Javadoc.
    }
}
