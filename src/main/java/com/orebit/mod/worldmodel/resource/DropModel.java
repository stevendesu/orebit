package com.orebit.mod.worldmodel.resource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Phase-2 front door that decouples "gather OUTPUT item Y" from "find &amp; mine SOURCE block X". A user
 * types the item they WANT ({@code cobblestone}, {@code iron}, {@code diamond}); this resolves it to the
 * {@link ResourceClasses} source resource to scan, the tool the drop demands (silk / no-silk / either), and the
 * item id(s) that COUNT toward the quota. The flagship case is one source X with two opposite-tool outputs:
 * {@code stone} (X=stone, mined with a SILK pickaxe → the stone item) vs {@code cobblestone} (X=stone, mined
 * with a NON-silk pickaxe → cobblestone).
 *
 * <h2>Why a third table (not {@link ResourceClasses} / {@link ItemClasses})</h2>
 * {@link ResourceClasses} maps blocks → a locatable/persisted resource (the SCAN + pyramid layer); {@link
 * ItemClasses} maps carried items → their resource (the {@code /bot drop} taxonomy — deliberately BROAD, one
 * resource covers ore/ingot/nugget/block). Neither expresses "mining X with tool T yields item Y", nor
 * distinguishes the two outputs of the same source (stone vs cobblestone both classify as the "stone"
 * resource). This table is that missing Y↔X front door, keyed by the friendly OUTPUT name.
 *
 * <h2>Scope + version-agnosticism</h2>
 * Covers exactly the current Phase-1 gatherable set (the ~25 named locatables) — mechanism first, NOT a broad
 * block→drop table. Source resolution reuses {@link ResourceClasses#resourceForName} (the source-resource ids
 * are version-stable), and target item ids are plain {@code "namespace:path"} strings compared against {@link
 * com.orebit.mod.platform.ItemLookup#idOf} at count time — so an id absent on the running MC version simply
 * never matches a live drop (harmless, same graceful-degradation pattern as {@link ResourceClasses}). Cold — a
 * command-cadence lookup over a few dozen strings; a plain {@code HashMap}.
 *
 * <p>Every current output resolves to exactly ONE {@link ResourceClasses} source resource (the ore stone/
 * deepslate variants are already bundled under one resource id there), so an output carries a single {@code
 * sourceResourceId}; a future multi-source output is a trivial extension (make it an id set).
 */
public final class DropModel {

    private DropModel() {}

    /** The tool a requested output demands: it must HAVE Silk Touch, must LACK it, or either works. */
    public enum ToolCondition { SILK_REQUIRED, NO_SILK, EITHER }

    /**
     * A resolved gather/find target: the friendly output {@code name}, the {@link ResourceClasses} {@code
     * sourceResourceId} to scan/find, the {@code condition} the mining tool must satisfy, and the registry
     * ids of the item(s) that COUNT toward the quota when picked up.
     */
    public static final class Output {
        private final String name;
        private final int sourceResourceId;
        private final ToolCondition condition;
        private final Set<String> targetItemIds;

        Output(String name, int sourceResourceId, ToolCondition condition, Set<String> targetItemIds) {
            this.name = name;
            this.sourceResourceId = sourceResourceId;
            this.condition = condition;
            this.targetItemIds = targetItemIds;
        }

        /** The friendly output name (also the chat label). */
        public String name() { return name; }
        /** The {@link ResourceClasses} source resource to SCAN / find (never -1 for a registered output). */
        public int sourceResourceId() { return sourceResourceId; }
        /** The tool condition the mining tool must satisfy to yield this output. */
        public ToolCondition condition() { return condition; }
        /** Whether {@code itemId} ("namespace:path") is one this output COUNTS toward its quota. */
        public boolean counts(String itemId) { return targetItemIds.contains(itemId); }
        /** The registry ids counted toward the quota (immutable) — for tests / diagnostics. */
        public Set<String> targetItemIds() { return targetItemIds; }
    }

    /** Friendly name (lower-case) → resolved output. {@link LinkedHashMap} so {@link #outputNames()} keeps a
     *  stable registration order; aliases share the target output's instance. */
    private static final Map<String, Output> BY_NAME = new LinkedHashMap<>();
    /** Source resource id → {normalDropId, silkDropId} (full ids, "" if none) — the modeled both-drops data
     *  the outputs are curated from; exposed for diagnostics/tests. */
    private static final Map<Integer, String[]> SOURCE_DROPS = new HashMap<>();
    /** All resolvable names (primaries + aliases), registration order — the tab-complete source. */
    private static final List<String> NAMES;

    static {
        // === Flagship: X=stone, two opposite-tool outputs ==========================================
        // Silk → the stone item; plain pickaxe → cobblestone. Both require a pickaxe (stone
        // requiresCorrectToolForDrops), so a bare-hand break yields nothing — the tool policy enforces both
        // the pickaxe AND the silk/no-silk condition.
        sourceDrops("stone", "cobblestone", "stone");
        output("stone",       "stone", ToolCondition.SILK_REQUIRED, "stone");
        output("cobblestone", "stone", ToolCondition.NO_SILK,       "cobblestone");

        // === Ores: normal drop is the mined item (the friendly output); silk drop is the ore BLOCK ===
        // Modeled normal as the friendly output (NO_SILK — silk would give the un-smeltable ore block). The
        // silk ore-block form has no friendly name, so it is recorded in the source table but not a gather
        // output. Ores require a correct pickaxe (requiresCorrectToolForDrops) → the correct-tool gate applies.
        sourceDrops("coal",    "coal",         "coal_ore");
        output("coal",         "coal",         ToolCondition.NO_SILK, "coal");
        sourceDrops("iron",    "raw_iron",     "iron_ore");
        output("iron",         "iron",         ToolCondition.NO_SILK, "raw_iron");
        sourceDrops("copper",  "raw_copper",   "copper_ore");
        output("copper",       "copper",       ToolCondition.NO_SILK, "raw_copper");
        sourceDrops("gold",    "raw_gold",     "gold_ore");
        output("gold",         "gold",         ToolCondition.NO_SILK, "raw_gold");
        sourceDrops("redstone","redstone",     "redstone_ore");
        output("redstone",     "redstone",     ToolCondition.NO_SILK, "redstone");
        sourceDrops("lapis",   "lapis_lazuli", "lapis_ore");
        output("lapis",        "lapis",        ToolCondition.NO_SILK, "lapis_lazuli");
        sourceDrops("emerald", "emerald",      "emerald_ore");
        output("emerald",      "emerald",      ToolCondition.NO_SILK, "emerald");
        sourceDrops("diamond", "diamond",      "diamond_ore");
        output("diamond",      "diamond",      ToolCondition.NO_SILK, "diamond");
        sourceDrops("quartz",  "quartz",       "nether_quartz_ore");
        output("quartz",       "quartz",       ToolCondition.NO_SILK, "quartz");
        // amethyst: source amethyst_cluster → amethyst_shard (normal); silk gives the CLUSTER block. FLAGGED
        // ambiguous (owner confirm): defaulting the friendly "amethyst" to the shards (the useful drop).
        sourceDrops("amethyst", "amethyst_shard", "amethyst_cluster");
        output("amethyst",      "amethyst",       ToolCondition.NO_SILK, "amethyst_shard");

        // === Blocks that drop THEMSELVES with a correct tool (X==Y, EITHER) =========================
        // ancient_debris + obsidian require a correct pickaxe; the decorative/stone-family drop themselves.
        // Silk vs no-silk is irrelevant (same drop either way), so EITHER = today's fastest-correct tool.
        selfDrop("ancient_debris", "ancient_debris");
        selfDrop("obsidian",       "obsidian");
        selfDrop("diorite",        "diorite");
        selfDrop("granite",        "granite");
        selfDrop("andesite",       "andesite");
        selfDrop("calcite",        "calcite");
        selfDrop("tuff",           "tuff");
        selfDrop("basalt",         "basalt");
        selfDrop("vines",          "vine");
        selfDrop("sandstone",      "sandstone", "red_sandstone");
        selfDrop("terracotta",
                "terracotta", "white_terracotta", "orange_terracotta", "magenta_terracotta",
                "light_blue_terracotta", "yellow_terracotta", "lime_terracotta", "pink_terracotta",
                "gray_terracotta", "light_gray_terracotta", "cyan_terracotta", "purple_terracotta",
                "blue_terracotta", "brown_terracotta", "green_terracotta", "red_terracotta", "black_terracotta");

        // wood: X==Y (a log drops itself), EITHER. The source resource "wood" bundles every log/stem, and the
        // count set is every log/stem the bot could mine.
        output("wood", "wood", ToolCondition.EITHER,
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "pale_oak_log", "crimson_stem", "warped_stem");

        // === FLAGGED ambiguous outputs (owner confirm the friendly-name default) ====================
        // glowstone: normal → glowstone_dust (2-4), silk → the glowstone BLOCK. Defaulting the friendly
        // "glowstone" to the DUST (the normal, more-useful drop; glowstone is hand-breakable so no pickaxe is
        // needed — NO_SILK just means "don't silk-touch it into the block").
        sourceDrops("glowstone", "glowstone_dust", "glowstone");
        output("glowstone", "glowstone", ToolCondition.NO_SILK, "glowstone_dust");
        // dripstone: pointed_dripstone → shards; dripstone_block → itself (needs a pickaxe). Silk doesn't
        // change dripstone_block's drop. Defaulting the friendly "dripstone" to EITHER, counting the block AND
        // the pointed form's own item (dripstone_block) + the shard.
        sourceDrops("dripstone", "dripstone_block", "dripstone_block");
        output("dripstone", "dripstone", ToolCondition.EITHER,
                "dripstone_block", "pointed_dripstone");

        // === Aliases (alternate names → the same output) ============================================
        alias("cobble", "cobblestone");
        alias("logs", "wood");
        alias("log", "wood");
        alias("raw_iron", "iron");
        alias("raw_copper", "copper");
        alias("raw_gold", "gold");
        alias("lapis_lazuli", "lapis");
        alias("amethyst_shard", "amethyst");

        NAMES = List.copyOf(BY_NAME.keySet());
    }

    // ---- Registration helpers ----------------------------------------------------------------------

    /** Register an X==Y "drops itself with a correct tool" output (EITHER), counting the block's own item(s). */
    private static void selfDrop(String name, String... itemPaths) {
        // Record both drops as identical (self) for the modeled table, then a single EITHER output.
        sourceDrops(name, itemPaths.length > 0 ? itemPaths[0] : "", itemPaths.length > 0 ? itemPaths[0] : "");
        output(name, name, ToolCondition.EITHER, itemPaths);
    }

    /** Model a source resource's normal-pickaxe + silk-touch drop (full/"minecraft:"-prefixed ids). */
    private static void sourceDrops(String sourceResourceName, String normalDrop, String silkDrop) {
        int rid = ResourceClasses.resourceForName(sourceResourceName);
        if (rid < 0) return; // source not tracked (should not happen — source ids are version-stable)
        SOURCE_DROPS.put(rid, new String[] { id(normalDrop), id(silkDrop) });
    }

    /** Register an output: friendly {@code name} → source resource + tool condition + counted item ids. */
    private static void output(String name, String sourceResourceName, ToolCondition condition,
            String... itemPaths) {
        int rid = ResourceClasses.resourceForName(sourceResourceName);
        if (rid < 0) return; // source resource not registered on this version — skip (keeps the tables in sync)
        java.util.HashSet<String> items = new java.util.HashSet<>();
        for (String p : itemPaths) items.add(id(p));
        BY_NAME.put(name.toLowerCase(Locale.ROOT), new Output(name, rid, condition, Set.copyOf(items)));
    }

    /** Register {@code alias} as another name for an already-registered output. */
    private static void alias(String alias, String targetName) {
        Output o = BY_NAME.get(targetName.toLowerCase(Locale.ROOT));
        if (o != null) BY_NAME.put(alias.toLowerCase(Locale.ROOT), o);
    }

    /** Namespace a bare path: {@code "cobblestone"} → {@code "minecraft:cobblestone"}; leave a qualified id. */
    private static String id(String path) {
        if (path == null || path.isEmpty()) return "";
        return path.indexOf(':') < 0 ? "minecraft:" + path : path;
    }

    // ---- Public API --------------------------------------------------------------------------------

    /** Resolve a typed output name (or alias) to its {@link Output}, or {@code null} if unknown. */
    public static Output resolve(String name) {
        return name == null ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** Every resolvable output name (primaries + aliases), registration order — the tab-complete source. */
    public static List<String> outputNames() {
        return NAMES;
    }

    /** The modeled {normalDropId, silkDropId} for a source resource id, or {@code null} if unmodeled — the
     *  both-drops data the outputs are curated from (diagnostics / tests). */
    public static String[] sourceDrops(int sourceResourceId) {
        return SOURCE_DROPS.get(sourceResourceId);
    }
}
