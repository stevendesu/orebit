package com.orebit.mod.worldmodel.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.world.level.block.Block;

import com.orebit.mod.platform.BlockLookup;

/**
 * Maps tracked blocks to a stable <b>locatable</b> id and, for the narrow subset worth a pyramid slot, to an
 * <b>indexed column</b> (0..{@link #COLUMN_COUNT}-1). Two deliberately-split tiers:
 * <ul>
 *   <li><b>LOCATABLE (unbounded):</b> every tracked block → a stable locatable id. A locatable may carry a
 *       user-facing NAME (the tab-complete / name-resolution source for {@code /bot gather|find|drop}); a
 *       nameless locatable is tracked-but-inert (it earns a stable id — preserving the class taxonomy for
 *       future use — but is not user-addressable and never appears in {@link #locatableNames()}). The
 *       registration ORDER is frozen so ids never shift; adding more block <i>strings</i> to an existing
 *       locatable (e.g. the deepslate ore variants below) does NOT shift ids.</li>
 *   <li><b>PERSISTED columns ({@link #COLUMN_COUNT}):</b> the subset of NAMED locatables that ALSO earn a
 *       pyramid column (0..COLUMN_COUNT-1). The tally, storage and query all operate on <i>columns</i>;
 *       every locatable that is not column-bound maps to column −1. Every persisted resource is a locatable;
 *       not every locatable is persisted (notably {@code stone}/{@code wood} are locatable-only — named,
 *       gatherable/findable via live scan, but deliberately kept OUT of the pyramid to avoid saturating it).</li>
 * </ul>
 *
 * <p>Blocks are referenced by registry id (string) and resolved through the block registry, NOT by
 * {@code Blocks.X} constants. This is deliberate for multi-version support: a constant like
 * {@code Blocks.PALE_OAK_LOG} is a *compile* error on a Minecraft version that predates the block,
 * whereas a registry lookup simply resolves to null and is skipped. The locatable id for each group is
 * assigned per-registration call regardless of which blocks exist, so the ids are identical across
 * versions and persisted resource data stays compatible.
 */
public final class ResourceClasses {

    /** The number of indexed pyramid columns (0..COLUMN_COUNT-1). */
    public static final int COLUMN_COUNT = 23;

    // ---- Locatable tier (unbounded) ----------------------------------------------------------------
    private static int nextResource = 0;
    private static final Map<Block, Integer> BLOCK_TO_RESOURCE = new HashMap<>();
    /** Locatable id → canonical NAME in id order; {@code null} for a nameless (tracked-but-inert) locatable. */
    private static final List<String> RESOURCE_NAME = new ArrayList<>();
    /** Canonical name (lower-case) → locatable id — the {@code /bot gather|find|drop} name-resolution table. */
    private static final Map<String, Integer> NAME_TO_RESOURCE = new HashMap<>();

    // Resources:
    public static final int LOG            = registerAll("wood",
        "oak_log", "spruce_log", "birch_log", "jungle_log",
        "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log",
        "pale_oak_log", "crimson_stem", "warped_stem"
    );
    public static final int STONE          = registerAll("stone", "stone");
    // Ores register the stone-tier AND deepslate variant under the SAME locatable (adding strings does
    // not shift ids). Deepslate ores exist since 1.17 → resolve across the whole 1.17→26.2 range;
    // on any version lacking one it just skips. NOTE: the ore-in-deepslate is indexed under its ORE
    // column here; the plain `deepslate` block (below) is a distinct, nameless locatable.
    public static final int COAL_ORE       = registerAll("coal", "coal_ore", "deepslate_coal_ore");
    public static final int IRON_ORE       = registerAll("iron", "iron_ore", "deepslate_iron_ore");
    public static final int COPPER_ORE     = registerAll("copper", "copper_ore", "deepslate_copper_ore");
    public static final int GOLD_ORE       = registerAll("gold",
        "gold_ore", "nether_gold_ore", "deepslate_gold_ore"
    );
    public static final int REDSTONE_ORE   = registerAll("redstone", "redstone_ore", "deepslate_redstone_ore");
    public static final int LAPIS_ORE      = registerAll("lapis", "lapis_ore", "deepslate_lapis_ore");
    public static final int EMERALD_ORE    = registerAll("emerald", "emerald_ore", "deepslate_emerald_ore");
    public static final int DIAMOND_ORE    = registerAll("diamond", "diamond_ore", "deepslate_diamond_ore");
    public static final int OBSIDIAN       = registerAll("obsidian", "obsidian");
    // The amethyst_cluster is the harvestable; budding_amethyst is the source block worth mining toward.
    public static final int AMETHYST       = registerAll("amethyst", "amethyst_cluster", "budding_amethyst");
    public static final int NETHER_QUARTZ_ORE = registerAll("quartz", "nether_quartz_ore");
    public static final int ANCIENT_DEBRIS = registerAll("ancient_debris", "ancient_debris");

    // Farms (nameless — tracked for future use, not user-addressable today):
    public static final int WHEAT          = register("wheat");
    public static final int CARROTS        = register("carrots");
    public static final int POTATOES       = register("potatoes");
    public static final int BEETROOTS      = register("beetroots");
    public static final int BAMBOO         = register(
        "bamboo", "bamboo_sapling"
    );
    public static final int SUGAR_CANE     = register("sugar_cane");
    public static final int CACTUS         = register("cactus");
    public static final int KELP           = register("kelp");
    public static final int MUSHROOMS      = register(
        "red_mushroom", "red_mushroom_block",
        "brown_mushroom", "brown_mushroom_block",
        "mushroom_stem"
    );
    public static final int NETHER_WART    = register(
        "nether_wart_block", "nether_wart"
    );

    // Utility Blocks (nameless):
    public static final int CRAFTING_TABLE = register("crafting_table");
    public static final int FURNACE        = register("furnace");
    public static final int CHEST          = register("chest");
    public static final int BED            = register(
        "white_bed", "red_bed", "blue_bed", "black_bed", "yellow_bed",
        "brown_bed", "cyan_bed", "gray_bed", "green_bed", "light_blue_bed",
        "light_gray_bed", "lime_bed", "magenta_bed", "orange_bed",
        "pink_bed", "purple_bed"
    );
    public static final int ENCHANTING_TABLE = register("enchanting_table");

    // Decorative:
    public static final int DIORITE        = registerAll("diorite", "diorite");
    public static final int GRANITE        = registerAll("granite", "granite");
    public static final int ANDESITE       = registerAll("andesite", "andesite");
    public static final int CALCITE        = registerAll("calcite", "calcite");
    public static final int TUFF           = registerAll("tuff", "tuff");
    public static final int DRIPSTONE      = registerAll("dripstone",
        "dripstone_block", "pointed_dripstone"
    );
    // The plain deepslate block: ubiquitous below y=0 → saturates → nameless (registry-only).
    public static final int DEEPSLATE      = register("deepslate");
    public static final int SANDSTONE      = registerAll("sandstone",
        "sandstone", "red_sandstone"
    );
    public static final int BASALT         = registerAll("basalt", "basalt");
    public static final int TERRACOTTA     = registerAll("terracotta",
        "red_terracotta", "orange_terracotta", "yellow_terracotta",
        "brown_terracotta", "white_terracotta", "light_gray_terracotta",
        "terracotta"
    );
    public static final int GLOWSTONE      = registerAll("glowstone", "glowstone");
    public static final int VINES          = registerAll("vines", "vine");

    // Other (nameless):
    public static final int SAND           = register("sand");
    public static final int SOUL_SAND      = register("soul_sand");
    public static final int GRAVEL         = register("gravel");
    public static final int CLAY           = register("clay");
    public static final int SNOW           = register(
        "snow_block", "snow", "powder_snow"
    );
    public static final int ICE            = register("ice");
    public static final int BEE_NEST       = register(
        "bee_nest", "beehive"
    );
    public static final int MOSS_BLOCK     = register("moss_block");
    public static final int SKULK          = register(
        "sculk", "sculk_catalyst", "sculk_vein"
    );
    public static final int CORAL          = register(
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

    // Hints (nameless)
    // These aren't blocks you're likely to search for specifically,
    // but their presence can help you find larger structures.
    // For example, redstone circuits indicate player activity
    public static final int BUILDING_BLOCKS = register(
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
    public static final int REDSTONE_CIRCUIT = register(
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
    public static final int MINECART_RAILS  = register(
        "rail", "activator_rail", "detector_rail", "powered_rail"
    );
    public static final int SPAWNER         = register("spawner");
    public static final int ENDER_CHEST     = register("ender_chest");
    public static final int TRAPPED_CHEST   = register("trapped_chest");
    public static final int PORTAL          = register(
        "nether_portal", "end_portal"
    );

    // User-defined (reserved locatable ids; no blocks / no name yet)
    public static final int USER_DEFINED_1  = 62;
    public static final int USER_DEFINED_2  = 63;

    // ===================================================================================================
    // Persisted-column layer: the NAMED subset that earns a pyramid column (0..COLUMN_COUNT-1); every other
    // locatable maps to column −1. stone + wood are named locatables but intentionally NOT column-bound.
    // ===================================================================================================
    private static final int[] COLUMN_OF_RESOURCE;   // index = locatable id; −1 = not persisted
    private static final String[] COLUMN_NAME = new String[COLUMN_COUNT];
    private static final List<String> LOCATABLE_NAMES;

    static {
        COLUMN_OF_RESOURCE = new int[nextResource];
        Arrays.fill(COLUMN_OF_RESOURCE, -1);

        // Ores / valuables (columns 0..11).
        bindColumn(0,  COAL_ORE);
        bindColumn(1,  IRON_ORE);
        bindColumn(2,  COPPER_ORE);
        bindColumn(3,  GOLD_ORE);
        bindColumn(4,  REDSTONE_ORE);
        bindColumn(5,  LAPIS_ORE);
        bindColumn(6,  EMERALD_ORE);
        bindColumn(7,  DIAMOND_ORE);
        bindColumn(8,  NETHER_QUARTZ_ORE);
        bindColumn(9,  ANCIENT_DEBRIS);
        bindColumn(10, AMETHYST);
        bindColumn(11, OBSIDIAN);

        // Builder palette (columns 12..22).
        bindColumn(12, DIORITE);
        bindColumn(13, GRANITE);
        bindColumn(14, ANDESITE);
        bindColumn(15, CALCITE);
        bindColumn(16, TUFF);
        bindColumn(17, DRIPSTONE);
        bindColumn(18, SANDSTONE);
        bindColumn(19, BASALT);
        bindColumn(20, TERRACOTTA);
        bindColumn(21, GLOWSTONE);
        bindColumn(22, VINES);

        // stone + wood are LOCATABLE-ONLY (named, live-scannable/gatherable/findable) but NOT column-bound:
        // stone saturates the compass (it's everywhere) and its ubiquity would flip the NavGrid-build resource
        // tally gate to almost-always-on underground; gather never NEEDS the compass for them (the live scan
        // always finds them adjacent), so they earn no pyramid slot.

        final List<String> named = new ArrayList<>();
        for (String n : RESOURCE_NAME) if (n != null) named.add(n);
        LOCATABLE_NAMES = List.copyOf(named);
    }

    private ResourceClasses() {}

    // ---- Registration ------------------------------------------------------------------------------

    /** Reserve the next locatable id, bind every {@code id} present on this version, and give it a NAME. */
    private static int registerAll(String name, String... ids) {
        int index = registerIds(ids);
        RESOURCE_NAME.set(index, name);
        NAME_TO_RESOURCE.put(name.toLowerCase(Locale.ROOT), index);
        return index;
    }

    /** Reserve the next locatable id and bind every {@code id} present on this version — NAMELESS
     *  (tracked-but-inert: it earns a stable id but is not user-addressable and never appears in
     *  {@link #locatableNames()}). */
    private static int register(String... ids) {
        return registerIds(ids);
    }

    private static int registerIds(String... ids) {
        int index = nextResource++;
        RESOURCE_NAME.add(null); // grow the id-indexed name list; registerAll fills it in
        for (String id : ids) {
            // Absent on this Minecraft version -> null -> skipped (graceful degradation).
            // BlockLookup hides the registry-id type (Mojang renamed ResourceLocation ->
            // Identifier in 1.21.11), so this stays version-agnostic.
            Block block = BlockLookup.byId("minecraft:" + id);
            if (block != null) BLOCK_TO_RESOURCE.put(block, index);
        }
        return index;
    }

    /** Attach a persisted pyramid {@code column} to an already-registered NAMED locatable. The column's
     *  display name is the locatable's name. */
    private static void bindColumn(int column, int resourceId) {
        COLUMN_OF_RESOURCE[resourceId] = column;
        COLUMN_NAME[column] = nameOfResource(resourceId);
    }

    // ---- Locatable accessors -----------------------------------------------------------------------

    /** The stable locatable id for {@code block}, or −1 if the block is not tracked. */
    public static int resourceForBlock(Block block) {
        return BLOCK_TO_RESOURCE.getOrDefault(block, -1);
    }

    /** Parse a user token ("diamond", "stone", "wood", "andesite") to its locatable id, or −1 if unknown. */
    public static int resourceForName(String name) {
        if (name == null) return -1;
        Integer id = NAME_TO_RESOURCE.get(name.toLowerCase(Locale.ROOT));
        return id == null ? -1 : id;
    }

    /** The canonical display name of a locatable id, or {@code null} if out of range or nameless. */
    public static String nameOfResource(int resourceId) {
        return (resourceId < 0 || resourceId >= RESOURCE_NAME.size()) ? null : RESOURCE_NAME.get(resourceId);
    }

    /** The persisted pyramid column for a locatable id, or −1 if it is locatable-only (not persisted). This is
     *  the "is it persisted?" bridge {@code /bot find} + the gatherer use to choose the pyramid compass vs the
     *  live-scan fallback. */
    public static int columnForResource(int resourceId) {
        return (resourceId < 0 || resourceId >= COLUMN_OF_RESOURCE.length) ? -1 : COLUMN_OF_RESOURCE[resourceId];
    }

    /** An immutable view of every NAMED locatable's canonical name, in id order — the tab-completion source for
     *  {@code /bot gather|find|drop} (cold, command-suggestion cadence). A superset of {@link #columnNames()}. */
    public static List<String> locatableNames() {
        return LOCATABLE_NAMES;
    }

    // ---- Persisted-column accessors (keyed by column, for the pyramid / tally / /bot report) --------

    /** The indexed pyramid column (0..COLUMN_COUNT-1) for {@code block}, or −1 if not tracked / not persisted. */
    public static int columnForBlock(Block block) {
        return columnForResource(resourceForBlock(block));
    }

    /** The number of indexed pyramid columns ({@link #COLUMN_COUNT}). */
    public static int columnCount() {
        return COLUMN_COUNT;
    }

    /** An immutable view of every persisted column's canonical name, in column order (a subset of
     *  {@link #locatableNames()} — excludes the locatable-only resources like stone/wood). */
    public static List<String> columnNames() {
        return List.of(COLUMN_NAME);
    }

    /** The canonical display name of a column (0..COLUMN_COUNT-1), or null if out of range. */
    public static String nameOfColumn(int column) {
        return (column < 0 || column >= COLUMN_COUNT) ? null : COLUMN_NAME[column];
    }
}
