package com.orebit.mod.platform;

import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Version-selected "damage one tool by one use" seam — the single inventory-API drift the live follower
 * needs that can't be expressed through the cross-stable {@link ItemStack} surface {@link BotInventory}
 * uses (count / destroy-speed / harvest-correctness / durability are all stable; only the <i>act</i> of
 * applying durability damage moved). It is split out so the rest of the inventory adapter ({@link
 * BotInventory}, the tool model, the per-pathfind feasibility snapshot) stays in core {@code src/} and only
 * this one method rides an overlay.
 *
 * <h2>The drift</h2>
 * {@link ItemStack#hurtAndBreak} changed signature twice across the supported range:
 * <ul>
 *   <li><b>1.17 → 1.20.4 (this baseline flavor):</b> {@code hurtAndBreak(int, T extends LivingEntity,
 *       Consumer<T>)} — the amount, the holding entity, and an on-break callback. A {@link ServerPlayer}
 *       is a {@code LivingEntity}, so the bot passes itself; the break callback is a no-op (the planner's
 *       feasibility cap, not this, decides whether a worn-out tool can still be used — see {@link
 *       BotInventory}).</li>
 *   <li><b>1.20.5 → 26.x:</b> the {@code Consumer} form is gone; durability damage goes through {@code
 *       hurtAndBreak(int, LivingEntity, EquipmentSlot)} (the equipment-slot the item is held in). That
 *       flavor lives in {@code overlays/1.20.5} and overrides this one for every build ≥ 1.20.5 (the
 *       overlay eras compose, highest ≤ active wins).</li>
 * </ul>
 *
 * <p>Callers see one stable {@code damageMainHand(bot, stack)} either way, so {@code BotInventory} never
 * names a version-specific signature. This only mutates durability; whether the bot <i>should</i> wear a
 * tool at all is the {@code mining.consumesTools} config gate the caller checks first.
 */
public final class ItemDamage {
    private ItemDamage() {}

    /** No-op break callback — the planner's feasibility cap, not this mutation, governs tool exhaustion. */
    private static final Consumer<ServerPlayer> ON_BREAK = p -> { };

    /**
     * Apply one use of durability damage to {@code stack}, held by {@code bot} in its main hand (baseline:
     * the {@code Consumer<T>} {@code hurtAndBreak} form). A no-op for an unbreakable / undamageable stack
     * (vanilla {@code hurtAndBreak} already ignores those).
     */
    public static void damageMainHand(ServerPlayer bot, ItemStack stack) {
        stack.hurtAndBreak(1, bot, ON_BREAK);
    }
}
