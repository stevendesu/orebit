package com.orebit.mod.worldmodel.resource;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.level.block.Block;

import com.orebit.mod.platform.BlockLookup;

/**
 * Maps tracked blocks to a stable resource-<b>class</b> id (0..63) and, for the subset worth a
 * pyramid column, to an <b>indexed column</b> (0..22).
 *
 * <p>Rehomed from the deleted semantic {@code region.RegionBlockIndex} (find-mine-resources design,
 * phase 2). Two deliberately-split concepts:
 * <ul>
 *   <li><b>Registry (all 64):</b> every tracked block → a stable class id. The registration ORDER is
 *       frozen so ids never shift — future persisted resource data stays compatible. Adding more
 *       block <i>strings</i> to an existing class (e.g. the deepslate ore variants below) does NOT
 *       shift ids.</li>
 *   <li><b>Indexed columns (23):</b> the subset that actually gets a pyramid column. The tally,
 *       storage and query all operate on <i>columns</i>; non-indexed classes map to column −1.</li>
 * </ul>
 *
 * <p>Blocks are referenced by registry id (string) and resolved through the block registry, NOT by
 * {@code Blocks.X} constants. This is deliberate for multi-version support: a constant like
 * {@code Blocks.PALE_OAK_LOG} is a *compile* error on a Minecraft version that predates the block,
 * whereas a registry lookup simply resolves to null and is skipped. The class id for each group is
 * assigned per-registration call regardless of which blocks exist, so the ids are identical across
 * versions and persisted resource data stays compatible.
 */
public final class ResourceClasses {
    public static final int MAX_CLASS = 64;

    /** The number of indexed pyramid columns (0..22). */
    public static final int COLUMN_COUNT = 23;

    private static int nextIndex = 0;
    private static final Map<Block, Integer> BLOCK_TO_CLASS = new HashMap<>();

    // Resources:
    public static final int LOG            = registerAll(
        "oak_log", "spruce_log", "birch_log", "jungle_log",
        "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log",
        "pale_oak_log", "crimson_stem", "warped_stem"
    );
    public static final int STONE          = register("stone");
    // Ores register the stone-tier AND deepslate variant under the SAME class (adding strings does
    // not shift ids). Deepslate ores exist since 1.17 → resolve across the whole 1.17→26.2 range;
    // on any version lacking one it just skips. NOTE: the ore-in-deepslate is indexed under its ORE
    // column here; the plain `deepslate` block (below) is a distinct, non-indexed class.
    public static final int COAL_ORE       = registerAll("coal_ore", "deepslate_coal_ore");
    public static final int IRON_ORE       = registerAll("iron_ore", "deepslate_iron_ore");
    public static final int COPPER_ORE     = registerAll("copper_ore", "deepslate_copper_ore");
    public static final int GOLD_ORE       = registerAll(
        "gold_ore", "nether_gold_ore", "deepslate_gold_ore"
    );
    public static final int REDSTONE_ORE   = registerAll("redstone_ore", "deepslate_redstone_ore");
    public static final int LAPIS_ORE      = registerAll("lapis_ore", "deepslate_lapis_ore");
    public static final int EMERALD_ORE    = registerAll("emerald_ore", "deepslate_emerald_ore");
    public static final int DIAMOND_ORE    = registerAll("diamond_ore", "deepslate_diamond_ore");
    public static final int OBSIDIAN       = register("obsidian");
    // The amethyst_cluster is the harvestable; budding_amethyst is the source block worth mining toward.
    public static final int AMETHYST       = registerAll("amethyst_cluster", "budding_amethyst");
    public static final int NETHER_QUARTZ_ORE = register("nether_quartz_ore");
    public static final int ANCIENT_DEBRIS = register("ancient_debris");

    // Farms:
    public static final int WHEAT          = register("wheat");
    public static final int CARROTS        = register("carrots");
    public static final int POTATOES       = register("potatoes");
    public static final int BEETROOTS      = register("beetroots");
    public static final int BAMBOO         = registerAll(
        "bamboo", "bamboo_sapling"
    );
    public static final int SUGAR_CANE     = register("sugar_cane");
    public static final int CACTUS         = register("cactus");
    public static final int KELP           = register("kelp");
    public static final int MUSHROOMS      = registerAll(
        "red_mushroom", "red_mushroom_block",
        "brown_mushroom", "brown_mushroom_block",
        "mushroom_stem"
    );
    public static final int NETHER_WART    = registerAll(
        "nether_wart_block", "nether_wart"
    );

    // Utility Blocks:
    public static final int CRAFTING_TABLE = register("crafting_table");
    public static final int FURNACE        = register("furnace");
    public static final int CHEST          = register("chest");
    public static final int BED            = registerAll(
        "white_bed", "red_bed", "blue_bed", "black_bed", "yellow_bed",
        "brown_bed", "cyan_bed", "gray_bed", "green_bed", "light_blue_bed",
        "light_gray_bed", "lime_bed", "magenta_bed", "orange_bed",
        "pink_bed", "purple_bed"
    );
    public static final int ENCHANTING_TABLE = register("enchanting_table");

    // Decorative:
    public static final int DIORITE        = register("diorite");
    public static final int GRANITE        = register("granite");
    public static final int ANDESITE       = register("andesite");
    public static final int CALCITE        = register("calcite");
    public static final int TUFF           = register("tuff");
    public static final int DRIPSTONE      = registerAll(
        "dripstone_block", "pointed_dripstone"
    );
    // The plain deepslate block: ubiquitous below y=0 → saturates → NOT indexed (registry-only).
    public static final int DEEPSLATE      = register("deepslate");
    public static final int SANDSTONE      = registerAll(
        "sandstone", "red_sandstone"
    );
    public static final int BASALT         = register("basalt");
    public static final int TERRACOTTA     = registerAll(
        "red_terracotta", "orange_terracotta", "yellow_terracotta",
        "brown_terracotta", "white_terracotta", "light_gray_terracotta",
        "terracotta"
    );
    public static final int GLOWSTONE      = register("glowstone");
    public static final int VINES          = register("vine");

    // Other:
    public static final int SAND           = register("sand");
    public static final int SOUL_SAND      = register("soul_sand");
    public static final int GRAVEL         = register("gravel");
    public static final int CLAY           = register("clay");
    public static final int SNOW           = registerAll(
        "snow_block", "snow", "powder_snow"
    );
    public static final int ICE            = register("ice");
    public static final int BEE_NEST       = registerAll(
        "bee_nest", "beehive"
    );
    public static final int MOSS_BLOCK     = register("moss_block");
    public static final int SKULK          = registerAll(
        "sculk", "sculk_catalyst", "sculk_vein"
    );
    public static final int CORAL          = registerAll(
        "brain_coral", "bubble_coral", "fire_coral",
        "horn_coral", "tube_coral",
        "brain_coral_block", "bubble_coral_block", "fire_coral_block",
        "horn_coral_block", "tube_coral_block",
        "brain_coral_fan", "bubble_coral_fan", "fire_coral_fan",
        "horn_coral_fan", "tube_coral_fan",
        "dead_brain_coral", "dead_bubble_coral", "dead_fire_coral",
        "dead_horn_coral", "dead_tube_coral",
        "dead_brain_coral_block", "dead_bubble_coral_block", "dead_fire_coral_block",
        "dead_horn_coral_block", "dead_tube_coral_block",
        "dead_brain_coral_fan", "dead_bubble_coral_fan", "dead_fire_coral_fan",
        "dead_horn_coral_fan", "dead_tube_coral_fan"
    );
    public static final int SEA_PICKLE     = register("sea_pickle");
    public static final int PODZOL         = register("podzol");
    public static final int MYCELIUM       = register("mycelium");
    public static final int PURPUR_BLOCK   = register("purpur_block");

    // Hints
    // These aren't blocks you're likely to search for specifically,
    // but their presence can help you find larger structures.
    // For example, redstone circuits indicate player activity
    public static final int BUILDING_BLOCKS = registerAll(
        "oak_slab", "spruce_slab", "birch_slab", "jungle_slab",
        "acacia_slab", "dark_oak_slab", "mangrove_slab", "cherry_slab",
        "pale_oak_slab", "crimson_slab", "warped_slab",
        "cobblestone_slab", "stone_slab",
        "diorite_slab", "granite_slab", "andesite_slab",
        "oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs",
        "acacia_stairs", "dark_oak_stairs", "mangrove_stairs", "cherry_stairs",
        "pale_oak_stairs", "crimson_stairs", "warped_stairs",
        "cobblestone_stairs", "stone_stairs",
        "diorite_stairs", "granite_stairs", "andesite_stairs",
        "cobblestone_wall",
        "diorite_wall", "granite_wall", "andesite_wall",
        "oak_door", "spruce_door", "birch_door", "jungle_door",
        "acacia_door", "dark_oak_door", "mangrove_door", "cherry_door",
        "pale_oak_door", "crimson_door", "warped_door", "iron_door",
        "oak_fence", "spruce_fence", "birch_fence", "jungle_fence",
        "acacia_fence", "dark_oak_fence", "mangrove_fence", "cherry_fence",
        "pale_oak_fence", "crimson_fence", "warped_fence",
        "oak_fence_gate", "spruce_fence_gate", "birch_fence_gate", "jungle_fence_gate",
        "acacia_fence_gate", "dark_oak_fence_gate", "mangrove_fence_gate", "cherry_fence_gate",
        "pale_oak_fence_gate", "crimson_fence_gate", "warped_fence_gate",
        "oak_trapdoor", "spruce_trapdoor", "birch_trapdoor", "jungle_trapdoor",
        "acacia_trapdoor", "dark_oak_trapdoor", "mangrove_trapdoor", "cherry_trapdoor",
        "pale_oak_trapdoor", "crimson_trapdoor", "warped_trapdoor", "iron_trapdoor",
        "oak_sign", "spruce_sign", "birch_sign", "jungle_sign",
        "acacia_sign", "dark_oak_sign", "mangrove_sign", "cherry_sign",
        "pale_oak_sign", "crimson_sign", "warped_sign",
        "oak_hanging_sign", "spruce_hanging_sign", "birch_hanging_sign", "jungle_hanging_sign",
        "acacia_hanging_sign", "dark_oak_hanging_sign", "mangrove_hanging_sign", "cherry_hanging_sign",
        "pale_oak_hanging_sign", "crimson_hanging_sign", "warped_hanging_sign",
        "oak_wall_sign", "spruce_wall_sign", "birch_wall_sign", "jungle_wall_sign",
        "acacia_wall_sign", "dark_oak_wall_sign", "mangrove_wall_sign", "cherry_wall_sign",
        "pale_oak_wall_sign", "crimson_wall_sign", "warped_wall_sign",
        "oak_wall_hanging_sign", "spruce_wall_hanging_sign", "birch_wall_hanging_sign", "jungle_wall_hanging_sign",
        "acacia_wall_hanging_sign", "dark_oak_wall_hanging_sign", "mangrove_wall_hanging_sign", "cherry_wall_hanging_sign",
        "pale_oak_wall_hanging_sign", "crimson_wall_hanging_sign", "warped_wall_hanging_sign"
    );
    public static final int REDSTONE_CIRCUIT = registerAll(
        "redstone_wire", "redstone_torch", "repeater",
        "comparator", "observer", "lever", "piston", "sticky_piston",
        "oak_button", "spruce_button", "birch_button", "jungle_button",
        "acacia_button", "dark_oak_button", "mangrove_button", "cherry_button",
        "pale_oak_button", "crimson_button", "warped_button", "stone_button",
        "oak_pressure_plate", "spruce_pressure_plate", "birch_pressure_plate", "jungle_pressure_plate",
        "acacia_pressure_plate", "dark_oak_pressure_plate", "mangrove_pressure_plate", "cherry_pressure_plate",
        "pale_oak_pressure_plate", "crimson_pressure_plate", "warped_pressure_plate", "stone_pressure_plate",
        "light_weighted_pressure_plate", "heavy_weighted_pressure_plate", "polished_blackstone_pressure_plate"
    );
    public static final int MINECART_RAILS  = registerAll(
        "rail", "activator_rail", "detector_rail", "powered_rail"
    );
    public static final int SPAWNER         = register("spawner");
    public static final int ENDER_CHEST     = register("ender_chest");
    public static final int TRAPPED_CHEST   = register("trapped_chest");
    public static final int PORTAL          = registerAll(
        "nether_portal", "end_portal"
    );

    // User-defined
    public static final int USER_DEFINED_1  = 62;
    public static final int USER_DEFINED_2  = 63;

    // ===================================================================================================
    // Indexed-column layer: 23 of the 64 classes get a pyramid column (0..22); the rest map to -1.
    // ===================================================================================================
    private static final int[] COLUMN_OF = new int[MAX_CLASS];
    private static final String[] COLUMN_NAME = new String[COLUMN_COUNT];
    private static final Map<String, Integer> NAME_TO_COLUMN = new HashMap<>();

    static {
        java.util.Arrays.fill(COLUMN_OF, -1);

        // Ores / valuables (columns 0..11).
        bindColumn(0,  COAL_ORE,          "coal");
        bindColumn(1,  IRON_ORE,          "iron");
        bindColumn(2,  COPPER_ORE,        "copper");
        bindColumn(3,  GOLD_ORE,          "gold");
        bindColumn(4,  REDSTONE_ORE,      "redstone");
        bindColumn(5,  LAPIS_ORE,         "lapis");
        bindColumn(6,  EMERALD_ORE,       "emerald");
        bindColumn(7,  DIAMOND_ORE,       "diamond");
        bindColumn(8,  NETHER_QUARTZ_ORE, "quartz");
        bindColumn(9,  ANCIENT_DEBRIS,    "ancient_debris");
        bindColumn(10, AMETHYST,          "amethyst");
        bindColumn(11, OBSIDIAN,          "obsidian");

        // Builder palette (columns 12..22).
        bindColumn(12, DIORITE,           "diorite");
        bindColumn(13, GRANITE,           "granite");
        bindColumn(14, ANDESITE,          "andesite");
        bindColumn(15, CALCITE,           "calcite");
        bindColumn(16, TUFF,              "tuff");
        bindColumn(17, DRIPSTONE,         "dripstone");
        bindColumn(18, SANDSTONE,         "sandstone");
        bindColumn(19, BASALT,            "basalt");
        bindColumn(20, TERRACOTTA,        "terracotta");
        bindColumn(21, GLOWSTONE,         "glowstone");
        bindColumn(22, VINES,             "vines");
    }

    private ResourceClasses() {}

    private static void bindColumn(int column, int classId, String name) {
        COLUMN_OF[classId] = column;
        COLUMN_NAME[column] = name;
        NAME_TO_COLUMN.put(name, column);
    }

    /** Reserves the next resource-class index and binds it to {@code id} if that block exists in this version. */
    private static int register(String id) {
        return registerAll(id);
    }

    /** Reserves the next resource-class index and binds it to every {@code id} that exists in this version. */
    private static int registerAll(String... ids) {
        int index = nextIndex++;
        if (index >= MAX_CLASS) throw new IllegalStateException("Too many blocks registered");
        for (String id : ids) {
            // Absent on this Minecraft version -> null -> skipped (graceful degradation).
            // BlockLookup hides the registry-id type (Mojang renamed ResourceLocation ->
            // Identifier in 1.21.11), so this stays version-agnostic.
            Block block = BlockLookup.byId("minecraft:" + id);
            if (block != null) BLOCK_TO_CLASS.put(block, index);
        }
        return index;
    }

    /** The stable class id (0..63) for {@code block}, or −1 if the block is not tracked. */
    public static int classIdForBlock(Block block) {
        return BLOCK_TO_CLASS.getOrDefault(block, -1);
    }

    /** The indexed pyramid column (0..22) for {@code block}, or −1 if not tracked / not indexed. */
    public static int columnForBlock(Block block) {
        int classId = BLOCK_TO_CLASS.getOrDefault(block, -1);
        return classId < 0 ? -1 : COLUMN_OF[classId];
    }

    /** The indexed pyramid column (0..22) for a class id, or −1 if that class is not indexed. */
    public static int columnFor(int classId) {
        return (classId < 0 || classId >= MAX_CLASS) ? -1 : COLUMN_OF[classId];
    }

    /** The number of indexed pyramid columns (23). */
    public static int columnCount() {
        return COLUMN_COUNT;
    }

    /** Parse a user token ("diamond", "iron", "andesite") to its column, or −1 if unknown. */
    public static int columnForName(String name) {
        if (name == null) return -1;
        Integer col = NAME_TO_COLUMN.get(name.toLowerCase(java.util.Locale.ROOT));
        return col == null ? -1 : col;
    }

    /** The canonical display name of a column (0..22), or null if out of range. */
    public static String nameOfColumn(int column) {
        return (column < 0 || column >= COLUMN_COUNT) ? null : COLUMN_NAME[column];
    }
}
