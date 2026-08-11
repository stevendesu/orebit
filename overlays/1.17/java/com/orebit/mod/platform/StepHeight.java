package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected "make the bot step like a player" hook — pins the bot's auto-step to vanilla's
 * <b>0.6</b>, undoing a leniency value {@code ServerPlayer} sets on itself that no real player ever
 * consumes.
 *
 * <h2>The bug this closes</h2>
 * {@code LivingEntity}'s constructor sets the step height to {@code 0.6}; {@code ServerPlayer}'s
 * constructor then <b>overwrites it with {@code 1.0}</b> (javap-verified: {@code fconst_1} →
 * {@code setMaxUpStep} / {@code putfield maxUpStep} in {@code ServerPlayer.<init>} on every version
 * 1.17.1 → 1.20.4). A clientless bot is the ONLY entity in the game that is a {@code ServerPlayer} AND
 * runs server-side physics, so it is the only thing that ever spends that 1.0 as a real step:
 * <ul>
 *   <li>A <b>real player's</b> server-side position is written from the move packet
 *       ({@code ServerGamePacketListenerImpl.handleMovePlayer}); the server never runs
 *       {@code move()}/{@code collide()} for them. The physics that decides a human's step-up runs on the
 *       CLIENT, on {@code LocalPlayer} — a {@code Player}, not a {@code ServerPlayer} — which keeps the
 *       0.6. So a player cannot rise more than 0.6, on any version.</li>
 *   <li>The <b>bot</b> runs {@code Entity.move} server-side, and {@code Entity.collide}'s step-up branch
 *       is armed by {@code onGround() || (yClamped && dy < 0.0)} — i.e. it also fires while FALLING into
 *       a floor. With a 1.0 step the bot silently climbs a full block off a descending tick.</li>
 * </ul>
 * Owner-diagnosed 2026-08-10 from a headless staircase repro: the bot gained <b>0.976 blocks in a single
 * tick</b> while falling, landing on the tread ABOVE its planned one, whereupon {@code Ascend}'s validity
 * envelope correctly refused and held. Impossible at 0.6; trivially inside 1.0. Owner ruling: <i>"If a
 * logged-in player can't rise more than 0.6, then a bot shouldn't be able to either."</i>
 *
 * <h2>Why it must be a seam</h2>
 * The accessor drifts three ways across the supported range (javap-swept over the cached Mojang-mapped
 * jars, 2026-08-10):
 * <table border="1">
 *   <tr><th>MC</th><th>API</th><th>flavour</th></tr>
 *   <tr><td>1.17.1 → 1.19.3</td><td>{@code public float maxUpStep} field, NO setter</td>
 *       <td><b>this one</b> — assign the field</td></tr>
 *   <tr><td>1.19.4 → 1.20.4</td><td>field went {@code private}, {@code setMaxUpStep(float)} added</td>
 *       <td>{@code overlays/1.19.4}</td></tr>
 *   <tr><td>1.20.5 → 26.2</td><td>neither — step height became the {@code STEP_HEIGHT} attribute</td>
 *       <td>{@code overlays/1.20.5} (no-op; the attribute already defaults to 0.6, and
 *           {@code ServerPlayer} stopped setting it)</td></tr>
 * </table>
 *
 * <h2>Blast radius (deliberate)</h2>
 * The planner already models vanilla 0.6 — {@code MovementContext.STEP_ASSIST_MAX_RISE = 9} sixteenths =
 * 0.5625 — so on 1.17.1 → 1.20.4 the executor could walk up rises the planner believed needed an
 * {@code Ascend} or {@code Parkour}. This pin closes that ~0.44-block model/reality gap, and also
 * normalises the two other vanilla reads of the same value: {@code Player.maybeBackOffFromEdge} (the
 * sneak edge-guard's downward probe depth, load-bearing for {@code Climb}'s sneak hold) and
 * {@code Player.isAboveGround} (the gate on {@code aiStep}'s push-out-of-blocks). Both now behave as they
 * do for a player. This IS a behaviour change on those 15 versions — that is the point.
 *
 * <p>Called once, from the bot's constructor. Nothing in vanilla re-sets the value for a non-riding
 * entity, so once is enough (the {@code LivingEntity.maxUpStep()} getter's {@code max(f, 1.0F)} branch is
 * gated on {@code getControllingPassenger() instanceof Player} — a bot carries no passengers).
 */
public final class StepHeight {
    private StepHeight() {}

    /** Vanilla's player step height — what {@code LivingEntity} sets before {@code ServerPlayer} clobbers it. */
    public static final float PLAYER_STEP = 0.6f;

    /**
     * Pin {@code bot}'s auto-step to {@link #PLAYER_STEP}. Baseline flavour: the field is {@code public}
     * here and there is no setter until 1.19.4, so it is assigned directly.
     */
    public static void pinToPlayerDefault(ServerPlayer bot) {
        bot.maxUpStep = PLAYER_STEP;
    }
}
