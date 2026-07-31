package com.orebit.mod.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.platform.TagLookup;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The parsed {@code mining.protectedBlocks} list — the blocks the bot must <b>NEVER</b> break, as a
 * membership predicate over {@link BlockState}. The raw config value is a comma-separated mix of exact
 * block ids and block tags: {@code minecraft:chest, #minecraft:beds, minecraft:diamond_ore}. Parsed ONCE
 * at config load ({@link ConfigValidator}); malformed / unresolvable entries warn and are skipped, never
 * fatal (the validator's clamp-and-warn rule).
 *
 * <h2>Where it is consumed (both sides of the planner/executor parity rule)</h2>
 * <ul>
 *   <li><b>Planner (via the classification fingerprint, NOT this object):</b> at config install the list
 *       is folded into the {@link com.orebit.mod.worldmodel.navblock.NavBlock} descriptor table as the
 *       PROTECTED bit ({@code NavBlock.applyProtected}), splitting matching states into protected
 *       navtypes. The A* hot path then sees protected-ness as a single already-loaded descriptor bit —
 *       this object (a set lookup + tag tests) is never touched per node.</li>
 *   <li><b>Executor (this object, cold):</b> every PATHING-MOTIVATED live break site
 *       ({@code AllyBotEntity.applyEdits}/{@code place}/{@code mine}, the gather LOS-occluder dig,
 *       builder clears) re-checks the LIVE block state against {@link #matches} via
 *       {@link Config#mayBreak} — the hard backstop that also covers stale nav grids. The DELIBERATE
 *       task hands ({@code BotMining} — a gather target, a harvest, {@code /bot mine}) are exempt
 *       (owner ruling 2026-07-29: protecting logs must not refuse {@code /bot gather wood}).</li>
 * </ul>
 *
 * <p><b>Restart caveat:</b> because protected-ness lives in the navtype fingerprint, nav-grid data built
 * before a list change still carries the old navtypes — a {@code /bot config reload} re-derives the table
 * and warns, but a server restart (or natural chunk rebuilds) is needed for the planner to fully see the
 * change. The execution-side refusal applies immediately either way.
 *
 * <p>Immutable. Tag membership goes through the {@link TagLookup} platform seam (the tag API drifted at
 * 1.18.2 / 1.19.3 / 1.21.11); exact ids through {@link BlockLookup}. A tag that doesn't exist on the
 * running server parses fine and matches nothing (tags are datapack data — existence isn't knowable at
 * parse time).
 */
public final class ProtectedBlocks {

    /** The empty list: protects nothing, matches nothing. Note this is NOT the effective default any more —
     *  an ABSENT {@code mining.protectedBlocks} key parses {@link #DEFAULT_SPEC} (see {@link ConfigValidator});
     *  {@code EMPTY} is only the fallback the {@code Config.DEFAULT} record holds (it can't parse at static-init,
     *  before the block registry is bound) and the unreadable-file degradation. */
    public static final ProtectedBlocks EMPTY = new ProtectedBlocks("", Set.of(), List.of());

    /**
     * The built-in default protected-block list — a broad "don't wreck the build" set applied when {@code
     * mining.protectedBlocks} is ABSENT (a fresh install, or a config that omits the key). Parsed at config
     * LOAD time ({@link ConfigValidator}), NOT here at class-init, because tag/id resolution needs the block
     * registry + datapack tags bound (only true at server start). A PRESENT key (even empty) is honored
     * verbatim, so an existing config keeps whatever it set — only new/omitting configs get this set.
     *
     * <h2>Portability discipline (MC 1.17.1 → 26.x)</h2>
     * Entries are TAGS wherever a covering vanilla tag exists (version-adaptive + they fail SILENTLY when
     * absent), and exact ids only for the untagged categories. Renamed/late tags are <b>composed</b> — both
     * the old and new id are listed ({@code #carpets}+{@code #wool_carpets} across the 1.19 rename;
     * {@code #wooden_doors}+{@code #doors}+{@code minecraft:iron_door}; {@code #small_flowers}+{@code
     * #tall_flowers}+{@code #flowers}; {@code #signs}+{@code #all_signs}) — so the one that doesn't exist on a
     * given version no-ops and the other matches. A block id absent on an older version (froglights 1.19,
     * sculk sensors 1.19/1.20, crafter/copper bulbs 1.21) is skipped with one load-time warning — harmless.
     *
     * <p><b>Doors are safe to protect:</b> protection blocks BREAKING (and destroy-by-placing-over) only —
     * the non-destructive door OPEN/CLOSE toggle rides a separate edit path ({@code MovementContext}'s
     * {@code doorSetClears}/{@code canToggleExitDoor}, executor {@code AllyBotEntity.setDoorOpen}) that never
     * consults the PROTECTED bit, so hand-toggleable doors are still operated; iron doors are routed around
     * rather than smashed. <b>Wild ground cover is deliberately NOT protected</b> (short grass, ferns,
     * seagrass, dead bush) so the bot can still clear/path through it — only cultivated/decorative plants are.
     */
    public static final String DEFAULT_SPEC = String.join(", ",
            // logs + planks
            "#minecraft:logs", "#minecraft:planks",
            // cobblestone variants (no vanilla tag)
            "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:cobbled_deepslate",
            // stairs / slabs / walls
            "#minecraft:stairs", "#minecraft:slabs", "#minecraft:walls",
            // fences / fence gates
            "#minecraft:fences", "#minecraft:fence_gates",
            // doors + trapdoors (wooden_* everywhere; iron id for <=1.19; broad tag adds iron+copper on 1.20+)
            "#minecraft:wooden_doors", "#minecraft:doors", "minecraft:iron_door",
            "#minecraft:wooden_trapdoors", "#minecraft:trapdoors", "minecraft:iron_trapdoor",
            // carpets (renamed carpets -> wool_carpets at 1.19; list both)
            "#minecraft:carpets", "#minecraft:wool_carpets",
            // glass blocks + panes (no umbrella tag)
            "minecraft:glass", "minecraft:tinted_glass",
            "minecraft:white_stained_glass", "minecraft:orange_stained_glass", "minecraft:magenta_stained_glass",
            "minecraft:light_blue_stained_glass", "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
            "minecraft:pink_stained_glass", "minecraft:gray_stained_glass", "minecraft:light_gray_stained_glass",
            "minecraft:cyan_stained_glass", "minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
            "minecraft:brown_stained_glass", "minecraft:green_stained_glass", "minecraft:red_stained_glass",
            "minecraft:black_stained_glass",
            "minecraft:glass_pane", "minecraft:white_stained_glass_pane", "minecraft:orange_stained_glass_pane",
            "minecraft:magenta_stained_glass_pane", "minecraft:light_blue_stained_glass_pane",
            "minecraft:yellow_stained_glass_pane", "minecraft:lime_stained_glass_pane",
            "minecraft:pink_stained_glass_pane", "minecraft:gray_stained_glass_pane",
            "minecraft:light_gray_stained_glass_pane", "minecraft:cyan_stained_glass_pane",
            "minecraft:purple_stained_glass_pane", "minecraft:blue_stained_glass_pane",
            "minecraft:brown_stained_glass_pane", "minecraft:green_stained_glass_pane",
            "minecraft:red_stained_glass_pane", "minecraft:black_stained_glass_pane",
            // ladders
            "minecraft:ladder",
            // plants — cultivated/decorative only (wild grass/fern/seagrass/dead_bush stay breakable)
            "#minecraft:saplings", "#minecraft:small_flowers", "#minecraft:tall_flowers", "#minecraft:flowers",
            "#minecraft:crops",
            "minecraft:vine", "minecraft:glow_lichen", "minecraft:lily_pad", "minecraft:sugar_cane",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:cocoa", "minecraft:sea_pickle",
            "minecraft:big_dripleaf", "minecraft:small_dripleaf", "minecraft:spore_blossom",
            "minecraft:hanging_roots", "minecraft:flower_pot",
            // workbenches / stations / storage
            "minecraft:crafting_table", "minecraft:cartography_table", "minecraft:fletching_table",
            "minecraft:smithing_table", "minecraft:loom", "minecraft:stonecutter", "minecraft:grindstone",
            "minecraft:enchanting_table", "minecraft:brewing_stand", "minecraft:anvil", "minecraft:chipped_anvil",
            "minecraft:damaged_anvil", "minecraft:lectern", "minecraft:composter", "minecraft:barrel",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:bell",
            "minecraft:beacon", "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest",
            "minecraft:beehive", "minecraft:bee_nest", "minecraft:crafter",
            // torches / campfires / lanterns
            "#minecraft:campfires", "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch",
            "minecraft:soul_wall_torch", "minecraft:lantern", "minecraft:soul_lantern",
            // glowing / light-emitting decorative
            "minecraft:glowstone", "minecraft:sea_lantern", "minecraft:shroomlight", "minecraft:jack_o_lantern",
            "minecraft:end_rod", "minecraft:redstone_lamp", "minecraft:conduit", "#minecraft:candles",
            "minecraft:ochre_froglight", "minecraft:verdant_froglight", "minecraft:pearlescent_froglight",
            // redstone components
            "#minecraft:buttons", "#minecraft:pressure_plates", "#minecraft:rails",
            "minecraft:redstone_wire", "minecraft:repeater", "minecraft:comparator", "minecraft:redstone_torch",
            "minecraft:redstone_wall_torch", "minecraft:redstone_block", "minecraft:lever",
            // pistons incl. the technical blocks (breaking an extended head or the mid-motion block destroys the base)
            "minecraft:piston", "minecraft:sticky_piston", "minecraft:piston_head", "minecraft:moving_piston",
            "minecraft:dispenser", "minecraft:dropper", "minecraft:hopper",
            "minecraft:observer", "minecraft:daylight_detector", "minecraft:note_block", "minecraft:tripwire",
            "minecraft:tripwire_hook", "minecraft:target", "minecraft:lightning_rod", "minecraft:sculk_sensor",
            "minecraft:calibrated_sculk_sensor", "minecraft:copper_bulb", "minecraft:exposed_copper_bulb",
            "minecraft:weathered_copper_bulb", "minecraft:oxidized_copper_bulb", "minecraft:waxed_copper_bulb",
            "minecraft:waxed_exposed_copper_bulb", "minecraft:waxed_weathered_copper_bulb",
            "minecraft:waxed_oxidized_copper_bulb",
            // bonus aesthetic / player-placed
            "#minecraft:beds", "#minecraft:signs", "#minecraft:all_signs", "#minecraft:banners");

    /** The normalized accepted entries, comma-joined — for display and reload change-detection. */
    private final String spec;
    /** Exact-id entries, resolved to registry {@link Block}s (identity comparison is exact here). */
    private final Set<Block> blocks;
    /** {@code #tag} entries, as {@link TagLookup} membership predicates. */
    private final List<Predicate<BlockState>> tags;

    private ProtectedBlocks(String spec, Set<Block> blocks, List<Predicate<BlockState>> tags) {
        this.spec = spec;
        this.blocks = blocks;
        this.tags = tags;
    }

    /**
     * Parse a raw comma-separated config value ({@code minecraft:chest, #minecraft:beds, ...}) into a
     * {@code ProtectedBlocks}. Each entry is trimmed; empty entries are ignored; a {@code #}-prefixed
     * entry is a block tag; anything else must resolve to a registered block id. Malformed or unknown
     * entries warn through {@code warn} and are skipped — the rest of the list still applies.
     */
    public static ProtectedBlocks parse(String raw, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return EMPTY;
        Set<Block> blocks = new HashSet<>();
        List<Predicate<BlockState>> tags = new ArrayList<>();
        StringBuilder spec = new StringBuilder();
        for (String entry : raw.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            if (e.startsWith("#")) {
                Predicate<BlockState> tag = TagLookup.blockTagMatcher(e.substring(1).trim());
                if (tag == null) {
                    warn.accept(ConfigKeys.MINING_PROTECTED_BLOCKS + ": '" + e
                            + "' is not a valid tag id — entry skipped");
                    continue;
                }
                tags.add(tag);
            } else {
                Block b = BlockLookup.byId(e);
                if (b == null) {
                    warn.accept(ConfigKeys.MINING_PROTECTED_BLOCKS + ": '" + e
                            + "' is not a known block id — entry skipped");
                    continue;
                }
                blocks.add(b);
            }
            if (spec.length() > 0) spec.append(',');
            spec.append(e);
        }
        if (blocks.isEmpty() && tags.isEmpty()) return EMPTY;
        return new ProtectedBlocks(spec.toString(), blocks, tags);
    }

    /** Whether {@code state}'s block is protected (exact id or any listed tag). Cold — never per A* node. */
    public boolean matches(BlockState state) {
        if (blocks.contains(state.getBlock())) return true;
        for (int i = 0; i < tags.size(); i++) {
            if (tags.get(i).test(state)) return true;
        }
        return false;
    }

    /** Whether the list protects nothing (the default) — lets consumers skip work entirely. */
    public boolean isEmpty() {
        return blocks.isEmpty() && tags.isEmpty();
    }

    /** The normalized accepted entries (comma-joined) — display + reload change-detection. */
    public String spec() {
        return spec;
    }
}
