package com.orebit.mod.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java facts check for {@link BotInventory#weaponScore} — the combat weapon ranking
 * (DESIGN-bot-abilities.md §5): swords beat axes at any tier (DPS + sweep), tiers rank
 * netherite &gt; diamond &gt; iron &gt; copper &gt; stone &gt; golden &gt; wooden, non-weapons
 * score 0, and an unknown (modded) material weapon still beats bare hands.
 */
class WeaponRankTest {

    @Test
    void swordsBeatAxesAtAnyTier() {
        assertTrue(BotInventory.weaponScore("minecraft:wooden_sword")
                > BotInventory.weaponScore("minecraft:netherite_axe"));
    }

    @Test
    void tierOrderWithinAKind() {
        final String[] tiers = {"netherite", "diamond", "iron", "copper", "stone", "golden", "wooden"};
        for (int i = 1; i < tiers.length; i++) {
            assertTrue(BotInventory.weaponScore("minecraft:" + tiers[i - 1] + "_sword")
                            > BotInventory.weaponScore("minecraft:" + tiers[i] + "_sword"),
                    tiers[i - 1] + " must outrank " + tiers[i]);
        }
    }

    @Test
    void nonWeaponsScoreZero() {
        assertEquals(0, BotInventory.weaponScore("minecraft:diamond_pickaxe"));
        assertEquals(0, BotInventory.weaponScore("minecraft:wheat_seeds"));
        assertEquals(0, BotInventory.weaponScore("minecraft:cobblestone"));
        assertEquals(0, BotInventory.weaponScore(""));
    }

    @Test
    void unknownMaterialWeaponStillBeatsBareHands() {
        assertTrue(BotInventory.weaponScore("somemod:obsidianite_sword") > 0);
        assertTrue(BotInventory.weaponScore("somemod:obsidianite_axe") > 0);
    }
}
