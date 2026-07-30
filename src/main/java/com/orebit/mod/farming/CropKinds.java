package com.orebit.mod.farming;

import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The registry of farmable {@link CropKind}s (DESIGN-bot-abilities.md §4) — the four farmland
 * crops for v1 (wheat, carrots, potatoes, beetroots; nether wart / stems / cocoa are later
 * kinds with their own soil rules). Registration order is the deterministic replant preference.
 * {@link #bake()} resolves the vanilla blocks/items once at SERVER_STARTED (registries bound);
 * unit tests call it directly under Bootstrap.
 */
public final class CropKinds {

    private CropKinds() {}

    /** Wheat: planted by wheat_seeds, mature at age 7. */
    static class Wheat extends CropKind {
        @Override public String cropBlockId() { return "minecraft:wheat"; }
        @Override public String seedItemId() { return "minecraft:wheat_seeds"; }
        @Override public int maxAge() { return 7; }
    }

    /** Carrots: planted by the carrot item itself, mature at age 7. */
    static class Carrots extends CropKind {
        @Override public String cropBlockId() { return "minecraft:carrots"; }
        @Override public String seedItemId() { return "minecraft:carrot"; }
        @Override public int maxAge() { return 7; }
    }

    /** Potatoes: planted by the potato item itself, mature at age 7. */
    static class Potatoes extends CropKind {
        @Override public String cropBlockId() { return "minecraft:potatoes"; }
        @Override public String seedItemId() { return "minecraft:potato"; }
        @Override public int maxAge() { return 7; }
    }

    /** Beetroots: planted by beetroot_seeds, mature at age 3 (the short age track). */
    static class Beetroots extends CropKind {
        @Override public String cropBlockId() { return "minecraft:beetroots"; }
        @Override public String seedItemId() { return "minecraft:beetroot_seeds"; }
        @Override public int maxAge() { return 3; }
    }

    private static final List<CropKind> ALL =
            List.of(new Wheat(), new Carrots(), new Potatoes(), new Beetroots());

    private static boolean baked;

    /** Resolve every kind's vanilla block/item (idempotent; SERVER_STARTED or test-Bootstrap). */
    public static void bake() {
        for (CropKind kind : ALL) {
            kind.resolve();
        }
        baked = true;
    }

    /** All kinds in deterministic (replant-preference) order. */
    public static List<CropKind> all() {
        return ALL;
    }

    /** Whether {@link #bake} has run (kinds are inert before it). */
    public static boolean ready() {
        return baked;
    }

    /** The kind whose crop block is {@code block}, or {@code null}. */
    public static CropKind byCropBlock(Block block) {
        for (CropKind kind : ALL) {
            if (kind.block() == block) return kind;
        }
        return null;
    }

    /** The kind whose crop block matches {@code state}'s block, or {@code null}. */
    public static CropKind byState(BlockState state) {
        return byCropBlock(state.getBlock());
    }

    /** Whether {@code item} plants a known crop — the bridging-material EXCLUSION test
     *  (softest-first placeable selection must never spend seeds as footing; §4). */
    public static boolean isSeedItem(Item item) {
        if (item == null) return false;
        for (CropKind kind : ALL) {
            if (kind.seedItem() == item) return true;
        }
        return false;
    }
}
