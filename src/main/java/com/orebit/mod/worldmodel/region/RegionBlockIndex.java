package com.orebit.mod.worldmodel.region;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class RegionBlockIndex {
    public static final int MAX_INDEX = 64;

    private static int nextIndex = 0;
    private static final Map<Block, Integer> BLOCK_TO_INDEX = new HashMap<>();
    private static final Set<Integer> REGISTERED_INDICES = new HashSet<>();
    private static final int[] REGISTERED_INDEX_ARRAY;

    // Resources:
    public static final int LOG            = registerAll(
        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
        Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
        Blocks.PALE_OAK_LOG, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM
    );
    public static final int STONE          = register(Blocks.STONE);
    public static final int COAL_ORE       = register(Blocks.COAL_ORE);
    public static final int IRON_ORE       = register(Blocks.IRON_ORE);
    public static final int COPPER_ORE     = register(Blocks.COPPER_ORE);
    public static final int GOLD_ORE       = registerAll(
        Blocks.GOLD_ORE, Blocks.NETHER_GOLD_ORE
    );
    public static final int REDSTONE_ORE   = register(Blocks.REDSTONE_ORE);
    public static final int LAPIS_ORE      = register(Blocks.LAPIS_ORE);
    public static final int EMERALD_ORE    = register(Blocks.EMERALD_ORE);
    public static final int DIAMOND_ORE    = register(Blocks.DIAMOND_ORE);
    public static final int OBSIDIAN       = register(Blocks.OBSIDIAN);
    public static final int AMETHYST       = register(Blocks.AMETHYST_CLUSTER);
    public static final int NETHER_QUARTZ_ORE = register(Blocks.NETHER_QUARTZ_ORE);
    public static final int ANCIENT_DEBRIS = register(Blocks.ANCIENT_DEBRIS);

    // Farms:
    public static final int WHEAT          = register(Blocks.WHEAT);
    public static final int CARROTS        = register(Blocks.CARROTS);
    public static final int POTATOES       = register(Blocks.POTATOES);
    public static final int BEETROOTS      = register(Blocks.BEETROOTS);
    public static final int BAMBOO         = registerAll(
        Blocks.BAMBOO, Blocks.BAMBOO_SAPLING
    );
    public static final int SUGAR_CANE     = register(Blocks.SUGAR_CANE);
    public static final int CACTUS         = register(Blocks.CACTUS);
    public static final int KELP           = register(Blocks.KELP);
    public static final int MUSHROOMS      = registerAll(
        Blocks.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK,
        Blocks.BROWN_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK,
        Blocks.MUSHROOM_STEM
    );
    public static final int NETHER_WART    = registerAll(
        Blocks.NETHER_WART_BLOCK, Blocks.NETHER_WART
    );

    // Utility Blocks:
    public static final int CRAFTING_TABLE = register(Blocks.CRAFTING_TABLE);
    public static final int FURNACE        = register(Blocks.FURNACE);
    public static final int CHEST          = register(Blocks.CHEST);
    public static final int BED            = registerAll(
        Blocks.WHITE_BED, Blocks.RED_BED, Blocks.BLUE_BED, Blocks.BLACK_BED, Blocks.YELLOW_BED,
        Blocks.BROWN_BED, Blocks.CYAN_BED, Blocks.GRAY_BED, Blocks.GREEN_BED, Blocks.LIGHT_BLUE_BED,
        Blocks.LIGHT_GRAY_BED, Blocks.LIME_BED, Blocks.MAGENTA_BED, Blocks.ORANGE_BED,
        Blocks.PINK_BED, Blocks.PURPLE_BED
    );
    public static final int ENCHANTING_TABLE = register(Blocks.ENCHANTING_TABLE);

    // Decorative:
    public static final int DIORITE        = register(Blocks.DIORITE);
    public static final int GRANITE        = register(Blocks.GRANITE);
    public static final int ANDESITE       = register(Blocks.ANDESITE);
    public static final int CALCITE        = register(Blocks.CALCITE);
    public static final int TUFF           = register(Blocks.TUFF);
    public static final int DRIPSTONE      = registerAll(
        Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE
    );
    public static final int DEEPSLATE      = register(Blocks.DEEPSLATE);
    public static final int SANDSTONE      = registerAll(
        Blocks.SANDSTONE, Blocks.RED_SANDSTONE
    );
    public static final int BASALT         = register(Blocks.BASALT);
    public static final int TERRACOTTA     = registerAll(
        Blocks.RED_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
        Blocks.BROWN_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
        Blocks.TERRACOTTA
    );
    public static final int GLOWSTONE      = register(Blocks.GLOWSTONE);
    public static final int VINES          = register(Blocks.VINE);

    // Other:
    public static final int SAND           = register(Blocks.SAND);
    public static final int SOUL_SAND      = register(Blocks.SOUL_SAND);
    public static final int GRAVEL         = register(Blocks.GRAVEL);
    public static final int CLAY           = register(Blocks.CLAY);
    public static final int SNOW           = registerAll(
        Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.POWDER_SNOW
    );
    public static final int ICE            = register(Blocks.ICE);
    public static final int BEE_NEST       = registerAll(
        Blocks.BEE_NEST, Blocks.BEEHIVE
    );
    public static final int MOSS_BLOCK     = register(
        Blocks.MOSS_BLOCK
    );
    public static final int SKULK          = registerAll(
        Blocks.SCULK, Blocks.SCULK_CATALYST, Blocks.SCULK_VEIN
    );
    public static final int CORAL          = registerAll(
        Blocks.BRAIN_CORAL, Blocks.BUBBLE_CORAL, Blocks.FIRE_CORAL,
        Blocks.HORN_CORAL, Blocks.TUBE_CORAL,
        Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK,
        Blocks.HORN_CORAL_BLOCK, Blocks.TUBE_CORAL_BLOCK,
        Blocks.BRAIN_CORAL_FAN, Blocks.BUBBLE_CORAL_FAN, Blocks.FIRE_CORAL_FAN,
        Blocks.HORN_CORAL_FAN, Blocks.TUBE_CORAL_FAN,
        Blocks.DEAD_BRAIN_CORAL, Blocks.DEAD_BUBBLE_CORAL, Blocks.DEAD_FIRE_CORAL,
        Blocks.DEAD_HORN_CORAL, Blocks.DEAD_TUBE_CORAL,
        Blocks.DEAD_BRAIN_CORAL_BLOCK, Blocks.DEAD_BUBBLE_CORAL_BLOCK, Blocks.DEAD_FIRE_CORAL_BLOCK,
        Blocks.DEAD_HORN_CORAL_BLOCK, Blocks.DEAD_TUBE_CORAL_BLOCK,
        Blocks.DEAD_BRAIN_CORAL_FAN, Blocks.DEAD_BUBBLE_CORAL_FAN, Blocks.DEAD_FIRE_CORAL_FAN,
        Blocks.DEAD_HORN_CORAL_FAN, Blocks.DEAD_TUBE_CORAL_FAN
    );
    public static final int SEA_PICKLE     = register(Blocks.SEA_PICKLE);
    public static final int PODZOL         = register(Blocks.PODZOL);
    public static final int MYCELIUM       = register(Blocks.MYCELIUM);
    public static final int PURPUR_BLOCK   = register(Blocks.PURPUR_BLOCK);

    // Hints
    // These aren't blocks you're likely to search for specifically,
    // but their presence can help you find larger structures.
    // For example, redstone circuits indicate player activity
    public static final int BUILDING_BLOCKS = registerAll(
        Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB, Blocks.BIRCH_SLAB, Blocks.JUNGLE_SLAB,
        Blocks.ACACIA_SLAB, Blocks.DARK_OAK_SLAB, Blocks.MANGROVE_SLAB, Blocks.CHERRY_SLAB,
        Blocks.PALE_OAK_SLAB, Blocks.CRIMSON_SLAB, Blocks.WARPED_SLAB,
        Blocks.COBBLESTONE_SLAB, Blocks.STONE_SLAB,
        Blocks.DIORITE_SLAB, Blocks.GRANITE_SLAB, Blocks.ANDESITE_SLAB,
        Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS, Blocks.JUNGLE_STAIRS,
        Blocks.ACACIA_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.MANGROVE_STAIRS, Blocks.CHERRY_STAIRS,
        Blocks.PALE_OAK_STAIRS, Blocks.CRIMSON_STAIRS, Blocks.WARPED_STAIRS,
        Blocks.COBBLESTONE_STAIRS, Blocks.STONE_STAIRS,
        Blocks.DIORITE_STAIRS, Blocks.GRANITE_STAIRS, Blocks.ANDESITE_STAIRS,
        Blocks.COBBLESTONE_WALL,
        Blocks.DIORITE_WALL, Blocks.GRANITE_WALL, Blocks.ANDESITE_WALL,
        Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.BIRCH_DOOR, Blocks.JUNGLE_DOOR,
        Blocks.ACACIA_DOOR, Blocks.DARK_OAK_DOOR, Blocks.MANGROVE_DOOR, Blocks.CHERRY_DOOR,
        Blocks.PALE_OAK_DOOR, Blocks.CRIMSON_DOOR, Blocks.WARPED_DOOR, Blocks.IRON_DOOR,
        Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE, Blocks.BIRCH_FENCE, Blocks.JUNGLE_FENCE,
        Blocks.ACACIA_FENCE, Blocks.DARK_OAK_FENCE, Blocks.MANGROVE_FENCE, Blocks.CHERRY_FENCE,
        Blocks.PALE_OAK_FENCE, Blocks.CRIMSON_FENCE, Blocks.WARPED_FENCE,
        Blocks.OAK_FENCE_GATE, Blocks.SPRUCE_FENCE_GATE, Blocks.BIRCH_FENCE_GATE, Blocks.JUNGLE_FENCE_GATE,
        Blocks.ACACIA_FENCE_GATE, Blocks.DARK_OAK_FENCE_GATE, Blocks.MANGROVE_FENCE_GATE, Blocks.CHERRY_FENCE_GATE,
        Blocks.PALE_OAK_FENCE_GATE, Blocks.CRIMSON_FENCE_GATE, Blocks.WARPED_FENCE_GATE,
        Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.BIRCH_TRAPDOOR, Blocks.JUNGLE_TRAPDOOR,
        Blocks.ACACIA_TRAPDOOR, Blocks.DARK_OAK_TRAPDOOR, Blocks.MANGROVE_TRAPDOOR, Blocks.CHERRY_TRAPDOOR,
        Blocks.PALE_OAK_TRAPDOOR, Blocks.CRIMSON_TRAPDOOR, Blocks.WARPED_TRAPDOOR, Blocks.IRON_TRAPDOOR,
        Blocks.OAK_SIGN, Blocks.SPRUCE_SIGN, Blocks.BIRCH_SIGN, Blocks.JUNGLE_SIGN,
        Blocks.ACACIA_SIGN, Blocks.DARK_OAK_SIGN, Blocks.MANGROVE_SIGN, Blocks.CHERRY_SIGN,
        Blocks.PALE_OAK_SIGN, Blocks.CRIMSON_SIGN, Blocks.WARPED_SIGN,
        Blocks.OAK_HANGING_SIGN, Blocks.SPRUCE_HANGING_SIGN, Blocks.BIRCH_HANGING_SIGN, Blocks.JUNGLE_HANGING_SIGN,
        Blocks.ACACIA_HANGING_SIGN, Blocks.DARK_OAK_HANGING_SIGN, Blocks.MANGROVE_HANGING_SIGN, Blocks.CHERRY_HANGING_SIGN,
        Blocks.PALE_OAK_HANGING_SIGN, Blocks.CRIMSON_HANGING_SIGN, Blocks.WARPED_HANGING_SIGN,
        Blocks.OAK_WALL_SIGN, Blocks.SPRUCE_WALL_SIGN, Blocks.BIRCH_WALL_SIGN, Blocks.JUNGLE_WALL_SIGN,
        Blocks.ACACIA_WALL_SIGN, Blocks.DARK_OAK_WALL_SIGN, Blocks.MANGROVE_WALL_SIGN, Blocks.CHERRY_WALL_SIGN,
        Blocks.PALE_OAK_WALL_SIGN, Blocks.CRIMSON_WALL_SIGN, Blocks.WARPED_WALL_SIGN,
        Blocks.OAK_WALL_HANGING_SIGN, Blocks.SPRUCE_WALL_HANGING_SIGN, Blocks.BIRCH_WALL_HANGING_SIGN, Blocks.JUNGLE_WALL_HANGING_SIGN,
        Blocks.ACACIA_WALL_HANGING_SIGN, Blocks.DARK_OAK_WALL_HANGING_SIGN, Blocks.MANGROVE_WALL_HANGING_SIGN, Blocks.CHERRY_WALL_HANGING_SIGN,
        Blocks.PALE_OAK_WALL_HANGING_SIGN, Blocks.CRIMSON_WALL_HANGING_SIGN, Blocks.WARPED_WALL_HANGING_SIGN
    );
    public static final int REDSTONE_CIRCUIT = registerAll(
        Blocks.REDSTONE_WIRE, Blocks.REDSTONE_TORCH, Blocks.REPEATER,
        Blocks.COMPARATOR, Blocks.OBSERVER, Blocks.LEVER, Blocks.PISTON, Blocks.STICKY_PISTON,
        Blocks.OAK_BUTTON, Blocks.SPRUCE_BUTTON, Blocks.BIRCH_BUTTON, Blocks.JUNGLE_BUTTON,
        Blocks.ACACIA_BUTTON, Blocks.DARK_OAK_BUTTON, Blocks.MANGROVE_BUTTON, Blocks.CHERRY_BUTTON,
        Blocks.PALE_OAK_BUTTON, Blocks.CRIMSON_BUTTON, Blocks.WARPED_BUTTON, Blocks.STONE_BUTTON,
        Blocks.OAK_PRESSURE_PLATE, Blocks.SPRUCE_PRESSURE_PLATE, Blocks.BIRCH_PRESSURE_PLATE, Blocks.JUNGLE_PRESSURE_PLATE,
        Blocks.ACACIA_PRESSURE_PLATE, Blocks.DARK_OAK_PRESSURE_PLATE, Blocks.MANGROVE_PRESSURE_PLATE, Blocks.CHERRY_PRESSURE_PLATE,
        Blocks.PALE_OAK_PRESSURE_PLATE, Blocks.CRIMSON_PRESSURE_PLATE, Blocks.WARPED_PRESSURE_PLATE, Blocks.STONE_PRESSURE_PLATE,
        Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE, Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE
    );
    public static final int MINECART_RAILS  = registerAll(
        Blocks.RAIL, Blocks.ACTIVATOR_RAIL, Blocks.DETECTOR_RAIL, Blocks.POWERED_RAIL
    );
    public static final int SPAWNER         = register(Blocks.SPAWNER);
    public static final int ENDER_CHEST     = register(Blocks.ENDER_CHEST);
    public static final int TRAPPED_CHEST   = register(Blocks.TRAPPED_CHEST);
    public static final int PORTAL          = registerAll(
        Blocks.NETHER_PORTAL, Blocks.END_PORTAL
    );

    // User-defined
    public static final int USER_DEFINED_1  = 62;
    public static final int USER_DEFINED_2  = 63;

    static {
        //noinspection
        REGISTERED_INDEX_ARRAY = REGISTERED_INDICES.stream().mapToInt(Integer::intValue).toArray();
    }

    private RegionBlockIndex() {}

    private static int register(Block block) {
        int index = nextIndex++;
        if (index >= MAX_INDEX) throw new IllegalStateException("Too many blocks registered");
        BLOCK_TO_INDEX.put(block, index);
        REGISTERED_INDICES.add(index);
        return index;
    }

    private static int registerAll(Block... blocks) {
        int index = nextIndex++;
        if (index >= MAX_INDEX) throw new IllegalStateException("Too many blocks registered");
        for (Block block : blocks) {
            BLOCK_TO_INDEX.put(block, index);
        }
        REGISTERED_INDICES.add(index);
        return index;
    }

    public static int getIndexForBlock(Block block) {
        return BLOCK_TO_INDEX.getOrDefault(block, -1);
    }

    public static int[] values() {
        return REGISTERED_INDEX_ARRAY;
    }
}
