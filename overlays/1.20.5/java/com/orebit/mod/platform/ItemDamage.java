package com.orebit.mod.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * MC <b>1.20.5+</b> flavor of {@link ItemDamage}: the {@code Consumer<T>} {@link ItemStack#hurtAndBreak}
 * form the baseline ({@code overlays/1.17}) uses was removed in the 1.20.5 item-component refactor, and
 * durability damage now goes through {@code hurtAndBreak(int, LivingEntity, EquipmentSlot)} — the slot the
 * item is worn/held in. The bot wears the throwaway/mining tool in its main hand, so we pass {@link
 * EquipmentSlot#MAINHAND}. This flavor overrides the baseline for every build ≥ 1.20.5 (the overlay eras
 * compose, highest ≤ active wins) and the 26.x era inherits it (also ≥ 1.20.5). See the baseline for the
 * rationale (this is only the act of applying durability; the {@code mining.consumesTools} config gate and
 * the planner's feasibility cap decide whether to call it at all).
 */
public final class ItemDamage {
    private ItemDamage() {}

    /** Apply one use of durability to {@code stack} held in {@code bot}'s main hand (1.20.5+ slot form). */
    public static void damageMainHand(ServerPlayer bot, ItemStack stack) {
        stack.hurtAndBreak(1, bot, EquipmentSlot.MAINHAND);
    }
}
