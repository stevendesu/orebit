package com.orebit.mod.worldmodel.resource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.orebit.mod.platform.ItemLookup;

import net.minecraft.world.item.Item;

/**
 * The item-side companion to {@link ResourceClasses}: classifies a carried {@link Item} into the taxonomy
 * the {@code /bot drop} command sorts by — a <b>resource</b> (and which {@link ResourceClasses} column it
 * belongs to), a <b>tool</b>/weapon, <b>armor</b>, or otherwise <b>trash</b>.
 *
 * <h2>Why a separate item map (not just {@link ResourceClasses})</h2>
 * {@link ResourceClasses} maps <i>blocks</i> to resource columns — but the bot's inventory holds <i>items</i>,
 * and mining a block drops a DIFFERENT item than the block (iron ore → {@code raw_iron}, not the ore block).
 * So the resource↔item relationship needs its own table. It reuses {@link ResourceClasses}'s column names /
 * indices, so {@code /bot drop iron} and {@code /bot gather iron} name the same "iron" and the drop command's
 * tab-completion is just {@link ResourceClasses#columnNames()} plus the category keywords.
 *
 * <h2>Version-agnostic by construction</h2>
 * Classification keys on the item's registry-id string ({@link ItemLookup#idOf} — the one thin overlay seam),
 * never on {@code Items.X} constants (a compile error on a version predating the item) nor on the churning
 * tool/armor class hierarchy (which went data-driven around 1.20.5, so {@code instanceof DiggerItem} is not
 * portable) nor on item tags (the {@code minecraft:pickaxes} family postdates 1.17). An id in the tables that
 * doesn't exist on the running version simply never appears in any inventory — harmless. Tools/armor are
 * detected by stable id suffixes ({@code _pickaxe}, {@code _chestplate}, …), so new tool/armor MATERIALS
 * (including modded ones) are covered without listing every combination.
 *
 * <p>Cold — consulted at command cadence over one bot's inventory, never on a hot path; a plain {@code
 * HashMap}/{@code Set} of a few dozen strings.
 */
public final class ItemClasses {

    private ItemClasses() {}

    /** Bare item path (namespace stripped) → the {@link ResourceClasses} column it drops toward. */
    private static final Map<String, Integer> PATH_TO_COLUMN = new HashMap<>();

    /** Id suffixes that mark a tool or weapon (any material, incl. modded). */
    private static final String[] TOOL_SUFFIXES = { "_pickaxe", "_axe", "_shovel", "_hoe", "_sword" };
    /** Exact tool/weapon ids without a material suffix. */
    private static final Set<String> TOOL_EXACT = Set.of(
            "shears", "bow", "crossbow", "trident", "mace", "fishing_rod", "flint_and_steel", "brush");
    /** Id suffixes that mark a worn armor piece (any material, incl. modded). */
    private static final String[] ARMOR_SUFFIXES = { "_helmet", "_chestplate", "_leggings", "_boots" };
    /** Exact armor ids without a slot suffix. */
    private static final Set<String> ARMOR_EXACT = Set.of("elytra");

    static {
        // Ores / valuables — the ore block AND deepslate variant, the mined drop (raw_*/gem), the ingot,
        // the nugget, and the storage block, so /bot drop iron sweeps every iron form the bot could carry.
        bind("coal", "coal", "charcoal", "coal_ore", "deepslate_coal_ore", "coal_block");
        bind("iron", "raw_iron", "iron_ingot", "iron_nugget", "iron_ore", "deepslate_iron_ore",
                "iron_block", "raw_iron_block");
        bind("copper", "raw_copper", "copper_ingot", "copper_ore", "deepslate_copper_ore",
                "copper_block", "raw_copper_block");
        bind("gold", "raw_gold", "gold_ingot", "gold_nugget", "gold_ore", "deepslate_gold_ore",
                "nether_gold_ore", "gold_block", "raw_gold_block");
        bind("redstone", "redstone", "redstone_ore", "deepslate_redstone_ore", "redstone_block");
        bind("lapis", "lapis_lazuli", "lapis_ore", "deepslate_lapis_ore", "lapis_block");
        bind("emerald", "emerald", "emerald_ore", "deepslate_emerald_ore", "emerald_block");
        bind("diamond", "diamond", "diamond_ore", "deepslate_diamond_ore", "diamond_block");
        bind("quartz", "quartz", "nether_quartz_ore", "quartz_block");
        bind("ancient_debris", "ancient_debris", "netherite_scrap", "netherite_ingot", "netherite_block");
        bind("amethyst", "amethyst_shard", "amethyst_block", "budding_amethyst", "amethyst_cluster");
        bind("obsidian", "obsidian", "crying_obsidian");

        // Builder palette — the block is its own item, so the base form (plus the common polished/cut/smooth
        // derivatives) is what the bot carries.
        bind("diorite", "diorite", "polished_diorite");
        bind("granite", "granite", "polished_granite");
        bind("andesite", "andesite", "polished_andesite");
        bind("calcite", "calcite");
        bind("tuff", "tuff", "polished_tuff");
        bind("dripstone", "dripstone_block", "pointed_dripstone");
        bind("sandstone", "sandstone", "red_sandstone", "cut_sandstone", "cut_red_sandstone",
                "smooth_sandstone", "chiseled_sandstone");
        bind("basalt", "basalt", "polished_basalt", "smooth_basalt");
        bind("terracotta", "terracotta");
        bind("glowstone", "glowstone", "glowstone_dust");
        bind("vines", "vine");

        // Gatherables — logs / stems and their stripped forms (the "wood" gather column).
        bind("wood",
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "pale_oak_log", "crimson_stem", "warped_stem",
                "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_jungle_log",
                "stripped_acacia_log", "stripped_dark_oak_log", "stripped_mangrove_log",
                "stripped_cherry_log", "stripped_pale_oak_log", "stripped_crimson_stem",
                "stripped_warped_stem");
    }

    /** Map every {@code path} to the {@link ResourceClasses} column named {@code columnName}. */
    private static void bind(String columnName, String... paths) {
        int column = ResourceClasses.columnForName(columnName);
        if (column < 0) return; // column name not bound in ResourceClasses — skip (keeps the two in sync)
        for (String path : paths) PATH_TO_COLUMN.put(path, column);
    }

    /** The bare path (namespace stripped) of {@code item}: {@code "minecraft:iron_ingot"} → {@code "iron_ingot"}. */
    private static String path(Item item) {
        String id = ItemLookup.idOf(item);
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** The {@link ResourceClasses} column {@code item} drops toward, or {@code -1} if it isn't a tracked resource. */
    public static int resourceColumn(Item item) {
        return PATH_TO_COLUMN.getOrDefault(path(item), -1);
    }

    /** Whether {@code item} is a tool or weapon (pickaxe/axe/shovel/hoe/sword, shears, bow, ...). */
    public static boolean isTool(Item item) {
        String path = path(item);
        if (TOOL_EXACT.contains(path)) return true;
        for (String suffix : TOOL_SUFFIXES) if (path.endsWith(suffix)) return true;
        return false;
    }

    /** Whether {@code item} is a worn armor piece (helmet/chestplate/leggings/boots, elytra). */
    public static boolean isArmor(Item item) {
        String path = path(item);
        if (ARMOR_EXACT.contains(path)) return true;
        for (String suffix : ARMOR_SUFFIXES) if (path.endsWith(suffix)) return true;
        return false;
    }

    /** Whether {@code item} is "trash": not a tracked resource, not a tool, not armor — the /bot drop trash set. */
    public static boolean isTrash(Item item) {
        return resourceColumn(item) < 0 && !isTool(item) && !isArmor(item);
    }

    /** Lower-cased trim of a user token — the {@code /bot drop} argument normaliser. */
    public static String normalizeToken(String token) {
        return token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    }
}
