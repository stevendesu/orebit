package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Version-selected "does this tool carry Silk Touch?" seam — the one enchantment fact the Phase-2 drop model
 * needs and the single enchantment-API drift {@link BotInventory} can't express through its version-stable
 * item verbs. Silk changes WHICH item a block drops (stone → stone rather than cobblestone; an ore block
 * rather than the mined resource), so the drop-goal-aware tool policy ({@link BotInventory#equipForCondition})
 * must be able to tell a silk pickaxe from a plain one. Fixed signature both flavors implement:
 * {@code hasSilkTouch(ServerLevel, ItemStack)}.
 *
 * <h2>The drift</h2>
 * The enchantment registry went data-driven at <b>MC 1.21</b> (verified by mapped-signature probe, NOT the
 * 1.20.5 item-component split — {@code getItemEnchantmentLevel} + {@code Enchantments.SILK_TOUCH} keep the old
 * shapes through 1.20.6):
 * <ul>
 *   <li><b>1.17 → 1.20.6 (this baseline flavor):</b> {@code Enchantments.SILK_TOUCH} is a static {@code
 *       Enchantment} constant and {@code EnchantmentHelper.getItemEnchantmentLevel(Enchantment, ItemStack)}
 *       takes it directly. {@code level} is unused here (kept for the stable signature — the 1.21+ flavor
 *       needs it to resolve the enchantment holder from the level's registry access).</li>
 *   <li><b>1.21 → 26.x:</b> {@code Enchantments.SILK_TOUCH} is a {@code ResourceKey<Enchantment>} and the
 *       helper takes a {@code Holder<Enchantment>} resolved from the level's registry. That flavor lives in
 *       {@code overlays/1.21} and overrides this one for every build ≥ 1.21 (the overlay eras compose,
 *       highest ≤ active wins).</li>
 * </ul>
 *
 * <p>Callers see one stable {@code hasSilkTouch(level, stack)} either way, so {@code BotInventory} never names
 * a version-specific enchantment type. Cold — consulted at gather/mining tick cadence for one bot, never on
 * the A* hot path.
 */
public final class ToolEnchants {
    private ToolEnchants() {}

    /** Whether {@code stack} is enchanted with Silk Touch (level &gt; 0). Baseline: the static {@code
     *  Enchantment} constant + the pre-data-driven {@code getItemEnchantmentLevel} form. */
    public static boolean hasSilkTouch(ServerLevel level, ItemStack stack) {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
    }

    /** Apply Silk Touch level 1 to {@code stack} (test/harness support — the headless gather autotest
     *  pre-equips a silk pickaxe for {@code gather stone}). Baseline: {@code ItemStack.enchant(Enchantment,
     *  int)} with the static enchantment constant. {@code level} is unused here (kept for the stable
     *  signature — the 1.21+ flavor resolves the enchantment holder from it). */
    public static void applySilkTouch(ServerLevel level, ItemStack stack) {
        stack.enchant(Enchantments.SILK_TOUCH, 1);
    }
}
