package com.orebit.mod.platform;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * MC <b>1.21+</b> flavor of {@link ToolEnchants}: the enchantment registry went data-driven at 1.21, so {@code
 * Enchantments.SILK_TOUCH} is now a {@code ResourceKey<Enchantment>} (not the baseline's static {@code
 * Enchantment} constant) and {@code EnchantmentHelper.getItemEnchantmentLevel} takes a {@code
 * Holder<Enchantment>}. The holder is resolved from the level's registry access.
 *
 * <p>The resolution chain {@code registryAccess().lookup(Registries.ENCHANTMENT).orElseThrow().getOrThrow(key)}
 * is deliberately the ONE form that compiles across the whole 1.21 → 1.21.11 range (verified by mapped-signature
 * probe): the {@code lookup(ResourceKey)} Optional accessor is present at every 1.21.x (its element type drifts
 * from {@code HolderLookup.RegistryLookup} at 1.21 to {@code Registry} at 1.21.2+, but both expose {@code
 * getOrThrow(ResourceKey) : Holder.Reference}), whereas the non-Optional {@code registryOrThrow} was renamed to
 * {@code lookupOrThrow} mid-range and so is NOT stable. This flavor overrides the baseline ({@code overlays/1.17})
 * for every build ≥ 1.21 (the overlay eras compose, highest ≤ active wins); the 26.x era (a separate build)
 * inherits it — if 26 drifts the registry-access shape, add an {@code overlays/26} override. See the baseline
 * {@link ToolEnchants} for the seam contract.
 */
public final class ToolEnchants {
    private ToolEnchants() {}

    /** Whether {@code stack} is enchanted with Silk Touch (level &gt; 0). Resolves the {@code Silk Touch}
     *  {@link Holder} from {@code level}'s registry access (data-driven enchantments, 1.21+). */
    public static boolean hasSilkTouch(ServerLevel level, ItemStack stack) {
        return EnchantmentHelper.getItemEnchantmentLevel(silkHolder(level), stack) > 0;
    }

    /** Apply Silk Touch level 1 to {@code stack} (test/harness support — the headless gather autotest
     *  pre-equips a silk pickaxe for {@code gather stone}). 1.21+: resolve the {@code Silk Touch}
     *  {@link Holder} from the level's registry access and use the component-era {@code
     *  ItemStack.enchant(Holder, int)} form. */
    public static void applySilkTouch(ServerLevel level, ItemStack stack) {
        stack.enchant(silkHolder(level), 1);
    }

    private static Holder<Enchantment> silkHolder(ServerLevel level) {
        return level.registryAccess()
                .lookup(Registries.ENCHANTMENT).orElseThrow()
                .getOrThrow(Enchantments.SILK_TOUCH);
    }
}
