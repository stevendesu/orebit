package com.orebit.mod.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.server.MinecraftServer;

/**
 * Loads {@code config/orebit.properties} at server start and holds the active {@link Config} (PRD §10
 * Phase 1a). Zero new dependencies: the file is plain {@link Properties} ({@code key=value}, {@code #}
 * comments), parsed by the JDK. On a missing file it WRITES a fully-commented default (documenting every
 * key), so a server owner gets a self-describing template the first time they run the mod. The generated
 * defaults reproduce today's follower behaviour with ONE deliberate exception: {@code mining.protectedBlocks}
 * defaults to a broad "don't wreck the build" set ({@link ProtectedBlocks#DEFAULT_SPEC}) rather than empty,
 * so out of the box the bot routes around player-placed/decorative blocks instead of mining through them.
 *
 * <h2>Where the file lives — the loader-agnostic config dir</h2>
 * The run/config directory is resolved through the {@link ConfigDir} platform seam (which reads it off the
 * {@link MinecraftServer} every loader hands us), so this stays vanilla-API-clean and works on the
 * multi-loader mc-1.21 era and the pure-Fabric 26.x era alike — a dedicated server and an integrated
 * single-player world each resolve to their own run dir. The file is {@code <runDir>/config/orebit.properties}.
 *
 * <h2>Lifecycle</h2>
 * {@link #load(MinecraftServer)} is called once from {@code OrebitCommon.init}'s {@code onServerStarted}
 * hook. It parses + validates into a {@link Config} and derives a {@link BotCaps} once, both cached in
 * statics ({@link #config()} / {@link #botCaps()}) the rest of the mod reads. {@link #reload(MinecraftServer)}
 * re-reads the same file (the {@code /bot config reload} subcommand) — cheap, off any hot path.
 *
 * <h2>Hot-path note</h2>
 * Nothing here runs per tick or per A* node: parse cost is paid once at startup (favour-cpu-over-ram). The
 * cached {@link #botCaps()} is what the pathfinder threads per search, and it reads BotCaps fields into
 * search-start locals — so config being configurable costs the hot loop nothing.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Path under the run dir: {@code config/orebit.properties}. */
    private static final String CONFIG_SUBDIR = "config";
    private static final String CONFIG_FILE = "orebit.properties";
    /** The id written for {@code placement.conjuredBlock} in the generated default file (= {@link Config#DEFAULT}). */
    private static final String DEFAULT_CONJURED_BLOCK_ID = "minecraft:cobblestone";

    /** The active configuration (defaults until {@link #load} runs, so early reads are still safe). */
    private static volatile Config config = Config.DEFAULT;
    /** The capability gate derived from {@link #config}, cached so it isn't rebuilt per pathfind. */
    private static volatile BotCaps botCaps = Config.DEFAULT.toBotCaps();

    /** The active validated configuration. */
    public static Config config() {
        return config;
    }

    /** The capability gate derived from the active config — what the pathfinder + follower read. */
    public static BotCaps botCaps() {
        return botCaps;
    }

    /**
     * Load (or first-time generate) {@code config/orebit.properties} for {@code server} and install it as
     * the active config. Missing file → write the commented default and use {@link Config#DEFAULT}; an I/O
     * or parse error → log and fall back to defaults (never crash the server start).
     */
    public static void load(MinecraftServer server) {
        install(read(server));
    }

    /** Re-read the file and re-install (the {@code /bot config reload} path). Returns the new config.
     *  Also re-bakes the {@code /bot craft} recipe index as a courtesy, so a datapack {@code /reload}'s
     *  recipe changes are picked up without a server restart (DESIGN-bot-abilities.md §3.3). */
    public static Config reload(MinecraftServer server) {
        Config c = read(server);
        install(c);
        com.orebit.mod.crafting.RecipeIndex.bake(server);
        return c;
    }

    private static void install(Config c) {
        // Async pathing (DESIGN-background-pathfinding.md §4.4): before mutating the cold shared tables
        // below (NavBlock.applyProtected splits navtypes; MiningModel.buildTable republishes), wait out any
        // in-flight background search — the drain-instead-of-volatile choice that keeps the hot read paths
        // untouched. No-op when async is off / at first load (no executor yet). Bot ticks don't run during
        // this command's execution (same thread), so nothing new is submitted mid-rebake.
        if (!com.orebit.mod.pathfinding.async.PlanExecutor.drainIdle(2_000)) {
            OrebitCommon.LOGGER.warn("[Orebit] config reload: planner pool didn't drain in 2s — "
                    + "an in-flight search may see mixed old/new tables (one stale plan at worst)");
        }
        config = c;
        botCaps = c.toBotCaps();
        // Planner-side protected-block awareness: fold mining.protectedBlocks into the NavBlock
        // classification fingerprint (the PROTECTED descriptor bit), splitting matching states into
        // protected navtypes. This is the FIRST active use of NavBlock on a live server, so it triggers
        // its static-init here — at server-started the block registry AND the datapack tags are both
        // bound (the same window the MiningModel bake below has always relied on), so #tag entries
        // resolve. It runs BEFORE any nav grid is built (chunk nav builds are world-tick-deferred), so
        // grids always classify with post-policy navtypes. Cold: one full-table pass per load/reload.
        int remapped = NavBlock.applyProtected(state -> c.protectedBlocks().matches(state));
        if (remapped > 0) {
            OrebitCommon.LOGGER.info("[Orebit] protected blocks ({}): {} block states re-fingerprinted",
                    c.protectedBlocks().spec(), remapped);
        }
        // Re-bake the mining-tick tables if they've already been built (a /bot config reload after server
        // start) so a changed mining-time model (ticksByHardness / ticksToMineFlat) takes effect immediately
        // — and so the navtype-keyed table covers any navtypes the protected split above just added (the
        // bake must stay AFTER applyProtected). On the FIRST load the tables aren't built yet —
        // OrebitCommon's onServerStarted builds them right after this install with the same config — so we
        // skip (ready() is false), avoiding a double bake.
        if (MiningModel.ready()) {
            MiningModel.buildTable(c.ticksByHardness(), c.ticksToMineFlat(), c.unbreakableHardness());
            if (remapped > 0) {
                // ready() == true means this is a RELOAD: nav grids already built still hold the old
                // navtypes, so the PLANNER won't fully see the list change until chunks rebuild.
                OrebitCommon.LOGGER.warn("[Orebit] mining.protectedBlocks changed on reload — nav data built "
                        + "before the change is stale; restart the server (or let chunks rebuild) to fully "
                        + "apply. The execution-side break refusal is already active.");
            }
        }
    }

    /**
     * Read + validate the file, generating the commented default if it's absent. Always returns a usable
     * {@link Config} (defaults on any error) — the caller never has to handle failure.
     */
    private static Config read(MinecraftServer server) {
        Path file;
        try {
            Path dir = ConfigDir.serverDir(server).resolve(CONFIG_SUBDIR);
            Files.createDirectories(dir);
            file = dir.resolve(CONFIG_FILE);
        } catch (IOException | RuntimeException e) {
            OrebitCommon.LOGGER.warn("[Orebit] could not resolve config dir — using defaults", e);
            return Config.DEFAULT;
        }

        Consumer<String> sink = msg -> OrebitCommon.LOGGER.warn("[Orebit] config: {}", msg);

        if (!Files.exists(file)) {
            writeDefault(file);
            OrebitCommon.LOGGER.info("[Orebit] wrote default config to {}", file);
            // Validate an EMPTY Properties so first-run gets every key's default THIS session — critically
            // including the built-in mining.protectedBlocks set (an absent key parses ProtectedBlocks.DEFAULT_SPEC;
            // the registry + datapack tags are bound at this SERVER_STARTED point, so it resolves). Without this
            // the freshly-written protection wouldn't take effect until the next start/reload.
            return new ConfigValidator(sink).validate(new Properties());
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            OrebitCommon.LOGGER.warn("[Orebit] failed to read {} — using defaults", file, e);
            return Config.DEFAULT;
        }

        Config c = new ConfigValidator(sink).validate(props);
        OrebitCommon.LOGGER.info("[Orebit] loaded config from {}", file);
        return c;
    }

    /**
     * Write the documented default {@code orebit.properties}. Hand-written (not {@link Properties#store})
     * so each key carries its own {@code #} comment explaining what it controls and its range — a
     * self-describing template. The values are {@link Config#DEFAULT}, except {@code mining.protectedBlocks}
     * is written as {@link ProtectedBlocks#DEFAULT_SPEC} (the record holds {@code EMPTY} — it can't parse at
     * static-init — but the effective default is the broad protection set the validator applies to an absent
     * key, so the generated file must show it).
     */
    private static void writeDefault(Path file) {
        Config d = Config.DEFAULT;
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            line(w, "# Orebit bot configuration.");
            line(w, "# Flat namespaced keys parsed as a java.util.Properties file ('#' starts a comment).");
            line(w, "# These defaults reproduce the stock follower behaviour; edit + '/bot config reload'.");
            line(w, "");

            line(w, "# --- survival: does the bot have a body that can be hurt / starve / drown? ---");
            line(w, "# Bot takes damage at all (fall, mobs, fire, ...).");
            kv(w, ConfigKeys.SURVIVAL_TAKES_DAMAGE, d.takesDamage());
            line(w, "# Bot has a hunger bar that depletes.");
            kv(w, ConfigKeys.SURVIVAL_HUNGER, d.hunger());
            line(w, "# Bot needs air underwater (can drown).");
            kv(w, ConfigKeys.SURVIVAL_NEEDS_BREATH, d.needsBreath());
            line(w, "");

            line(w, "# --- placement: may the bot place blocks, and what does it place? ---");
            line(w, "# Bot may place blocks (bridge a gap, pillar up).");
            kv(w, ConfigKeys.PLACEMENT_CAN_PLACE, d.canPlace());
            line(w, "# Placing consumes a real block from the bot's inventory (false = infinite supply).");
            kv(w, ConfigKeys.PLACEMENT_CONSUMES_BLOCKS, d.consumesBlocks());
            line(w, "# The block the bot conjures/places when not consuming inventory (a real block id).");
            kv(w, ConfigKeys.PLACEMENT_CONJURED_BLOCK, DEFAULT_CONJURED_BLOCK_ID);
            line(w, "# How strongly the bot avoids placing hard-to-remove blocks (>= 0). Each placement is");
            line(w, "# charged extra by the placed block's mine-out time (ticks) * this weight, so with a mixed");
            line(w, "# inventory it favors dirt/cobblestone over obsidian. 1.0 = full mine-out cost, 0 = disabled");
            line(w, "# (placement cost ignores the block).");
            kv(w, ConfigKeys.PLACEMENT_REMOVAL_COST_WEIGHT, d.removalCostWeight());
            line(w, "# Flat cost (in ticks) charged per block placed (>= 0) -- a behavioral penalty that biases");
            line(w, "# the bot toward walking/digging over building scaffolding. NOT a physical place time");
            line(w, "# (placing is ~instant). Lower it for a more build-happy bot; raise it to discourage placing.");
            line(w, "# Default 6.");
            kv(w, ConfigKeys.PLACEMENT_PLACE_BASE_COST, d.placeBaseCost());
            line(w, "");

            line(w, "# --- mining: may the bot mine, what can it mine, how long does it take? ---");
            line(w, "# Bot may mine (break) blocks in its way.");
            kv(w, ConfigKeys.MINING_CAN_MINE, d.canMine());
            line(w, "# Mining wears down / consumes the bot's tools.");
            kv(w, ConfigKeys.MINING_CONSUMES_TOOLS, d.consumesTools());
            line(w, "# Hardest block the bot may mine, 0..255 (255 = mine anything breakable; lower = soft only).");
            kv(w, ConfigKeys.MINING_MAX_HARDNESS, d.maxHardness());
            line(w, "# Mining time scales with hardness (true) vs. a flat per-block time (false).");
            kv(w, ConfigKeys.MINING_TICKS_BY_HARDNESS, d.ticksByHardness());
            line(w, "# Ticks to mine one block when ticksByHardness=false (0 = insta-mine).");
            kv(w, ConfigKeys.MINING_TICKS_TO_MINE_FLAT, d.ticksToMineFlat());
            line(w, "# Flat surcharge (in ticks) added to EVERY block break the bot plans, on top of the real");
            line(w, "# mining time (>= 0) -- the mining-side mirror of placement.placeBaseCost. Raise it to");
            line(w, "# discourage gratuitous world edits (digging shortcuts, punching through bushes/cobwebs);");
            line(w, "# 0 (default) prices breaks at mining time alone.");
            kv(w, ConfigKeys.MINING_BREAK_BASE_COST, d.breakBaseCost());
            line(w, "# Blocks the bot must NEVER break (or clear/replace by placing): a comma-separated list of block ids");
            line(w, "# and #-prefixed block tags, e.g.  minecraft:chest, #minecraft:beds, minecraft:diamond_ore");
            line(w, "# The DEFAULT below is a broad 'don't wreck the build' set -- logs/planks, cobblestone variants,");
            line(w, "# stairs/slabs/walls, fences/gates, doors+trapdoors, glass, carpets, ladders, decorative plants,");
            line(w, "# workbenches/stations/storage (chests, furnaces, ...), torches/lanterns/campfires, glowing blocks,");
            line(w, "# redstone components, and beds/signs/banners. Hand-toggleable doors are still OPENED to pass (only");
            line(w, "# BREAKING is refused); iron doors are routed around. To let the bot break anything, set this empty");
            line(w, "# (mining.protectedBlocks=). Tags that don't exist on your version are ignored silently; ids that don't");
            line(w, "# exist warn once and are skipped. Enforced when planning routes AND when actually breaking. NOTE:");
            line(w, "# changing this list requires a SERVER RESTART (or waiting for chunks to rebuild) to fully apply --");
            line(w, "# protected-ness is baked into the cached nav data; the hard refusal to break applies immediately");
            line(w, "# on /bot config reload.");
            kv(w, ConfigKeys.MINING_PROTECTED_BLOCKS, ProtectedBlocks.DEFAULT_SPEC);
            line(w, "# Bot may mine vanilla-UNBREAKABLE blocks (bedrock, barriers, end portal frames, ...) at the");
            line(w, "# tool-derived cost below. A separate axis from mining.maxHardness (which only ranges over");
            line(w, "# breakable blocks); mining.protectedBlocks always overrides.");
            kv(w, ConfigKeys.MINING_ALLOW_UNBREAKABLE, d.allowUnbreakable());
            line(w, "# Pseudo-hardness for those unbreakable blocks (>= 1) when allowUnbreakable=true -- they have");
            line(w, "# no real destroy time, so this synthetic value feeds the normal mining formula (assuming a");
            line(w, "# pickaxe): a better pickaxe tier digs faster, bare hands far slower. Same quantized scale as");
            line(w, "# real blocks (obsidian, the hardest, is ~250) but may exceed 255 for a stronger deterrent.");
            line(w, "# Default 3200 = ~2 minutes per block with a diamond pickaxe (matching the old fixed cost).");
            kv(w, ConfigKeys.MINING_UNBREAKABLE_HARDNESS, d.unbreakableHardness());
            line(w, "");

            line(w, "# --- pathing: the A* search knobs ---");
            line(w, "# A* node-expansion ceiling per SYNC-mode search (> 0) -- the tick-thread search budget in");
            line(w, "# nodes. Only used when pathing.async=false; async caps by asyncSearchBudgetMs instead.");
            line(w, "# Higher = can route farther per sync search, but a bigger worst-case tick stall.");
            kv(w, ConfigKeys.PATHING_SYNC_SEARCH_BUDGET_NODES, d.maxNodes());
            line(w, "# Heuristic greediness weight (>= 1.0). 1.0 = optimal but slow; higher = faster + greedier.");
            kv(w, ConfigKeys.PATHING_GREEDY_WEIGHT, d.greedyWeight());
            line(w, "# Ticks the bot considers 1 HP of damage to be worth (>= 0) -- the ONE damage-pricing");
            line(w, "# knob: walking through fire/bushes/powder snow and falling past the safe distance are all");
            line(w, "# charged this many ticks per HP. 1 HP buys roughly costPerHitpoint / 4.6 blocks of detour");
            line(w, "# (~22 blocks at the default 100). Raise it for a more self-preserving bot; only matters");
            line(w, "# when survival.takesDamage=true.");
            kv(w, ConfigKeys.PATHING_COST_PER_HITPOINT, d.costPerHitpoint());
            line(w, "# Run a short synthetic pathfinder warm-up at server start (before any player can join)");
            line(w, "# so the bot's FIRST real search doesn't run JIT-cold (a one-time ~16ms tick stall");
            line(w, "# otherwise). Costs ~0.3-1.5s of startup wall-clock only; no effect after boot.");
            kv(w, ConfigKeys.PATHING_WARMUP, d.warmup());
            line(w, "# Hard cap (milliseconds) on that warm-up; it usually stops earlier, once search times");
            line(w, "# plateau. 0 disables the warm-up entirely.");
            kv(w, ConfigKeys.PATHING_WARMUP_BUDGET_MS, d.warmupBudgetMs());
            line(w, "# Run path searches on background planner threads instead of the server tick thread.");
            line(w, "# Searches stop costing tick time; plans arrive 1-3 ticks after they're requested (the");
            line(w, "# bot keeps walking its current plan meanwhile). Requires a server restart to change.");
            kv(w, ConfigKeys.PATHING_ASYNC, d.asyncPathing());
            line(w, "# Background planner thread count when pathing.async=true (clamped to your core count");
            line(w, "# minus 2). All bots share the pool; raise it on a many-bot server to cut search latency,");
            line(w, "# lower to 1 on a constrained host. Requires a server restart to change.");
            kv(w, ConfigKeys.PATHING_MAX_THREADS, d.maxThreads());
            line(w, "# Wall-clock budget (milliseconds) per background path search when pathing.async=true --");
            line(w, "# the time-based cap that replaces syncSearchBudgetNodes as the effective limit (a large");
            line(w, "# node cap remains only as a memory backstop). A search that runs out of budget returns its");
            line(w, "# best partial path; the bot moves that way and replans. Bigger = escapes bigger dead-ends,");
            line(w, "# longer worst-case plan latency (the tick itself is never stalled -- these run off-thread).");
            kv(w, ConfigKeys.PATHING_ASYNC_SEARCH_BUDGET_MS, d.asyncSearchBudgetMs());
            line(w, "# Per-tick COUNT BACKSTOP on NavGrid chunk builds per level (>= 1). No longer the primary");
            line(w, "# gate -- the wall-clock budget below is. This ceiling just keeps a burst of cheap all-air");
            line(w, "# columns from draining unbounded within the time budget. Raise on a strong/multi-core host");
            line(w, "# to let more cheap columns build per tick; default 64 is a backstop, not a per-tick target.");
            kv(w, ConfigKeys.PATHING_CHUNK_BUILDS_PER_TICK, d.chunkBuildsPerTick());
            line(w, "# PRIMARY per-tick wall-clock budget (ms, > 0) for draining the NavGrid chunk-build queue.");
            line(w, "# Per-column cost swings ~15x with terrain (surface ~1ms, cave ~5ms), which a fixed count");
            line(w, "# can't adapt to; a time budget drains as many columns as safely fit. Elapsed is checked");
            line(w, "# after each column (worst-case overshoot one column). Clamped to (0, 1000] ms. Default 2.0.");
            kv(w, ConfigKeys.PATHING_CHUNK_BUILD_BUDGET_MS, d.chunkBuildBudgetMs());
            line(w, "# Radius (in chunks) of the ring around the bot that must be nav-built before it plans a");
            line(w, "# path (N => a (2N+1)^2 chunk ring). The bot HOLDS until this is built, so it never plans");
            line(w, "# over unbuilt terrain (which the planner reads as air -> a truncated-world target). Default");
            line(w, "# 4 covers the block-tier sliding window + a 1-chunk margin. 0 disables the gate.");
            kv(w, ConfigKeys.PATHING_NAV_READY_RADIUS_CHUNKS, d.navReadyRadiusChunks());
            line(w, "# How many ticks to wait for that ring to build before giving up (>= 1). A give-up backstop");
            line(w, "# only -- the gate polls real nav residency and proceeds the instant the ring is built, so on");
            line(w, "# a healthy server the wait is 1-2 ticks and this never fires. Default 150 (~7.5s).");
            kv(w, ConfigKeys.PATHING_NAV_READY_TIMEOUT_TICKS, d.navReadyTimeoutTicks());
            line(w, "# Per-tick wall-clock budget (ms, > 0) for the HPA* region-tier dirty-leaf flush -- the");
            line(w, "# region-tier analog of chunkBuildBudgetMs. A leaf rebuild is ~0.15-1.8ms, so 1.0 usually");
            line(w, "# drains the (small) dirty set fully; a bulk edit amortizes over a few ticks. Elapsed is");
            line(w, "# checked after each leaf. Bounded by a hardcoded leaf-count backstop. Clamped to (0, 1000] ms.");
            kv(w, ConfigKeys.PATHING_HPA_FLUSH_BUDGET_MS, d.hpaFlushBudgetMs());
            line(w, "# Per-tick wall-clock budget (ms, > 0) for the Stage-2 region-shard lazy-load drain (only used");
            line(w, "# when hpa.lazyLoad=true). Each whole-shard load is atomic (~11-34ms); this bounds how many");
            line(w, "# shards a tick pages in, with a >=1-per-tick backstop. Clamped to (0, 1000] ms. Default 2.0.");
            kv(w, ConfigKeys.PATHING_REGION_SHARD_LOAD_BUDGET_MS, d.regionShardLoadBudgetMs());
            line(w, "# Half-extent (in region cells, per pyramid level 0..6) of the PROACTIVE boxed-in seal scan run");
            line(w, "# at plan-entry (1..16): a (2R+1)^3 region box centered on the goal region at each level. R=3");
            line(w, "# (default) catches seals from ~16 blocks (level 0) up to ~7k blocks (level 6). Larger = catches");
            line(w, "# bigger tombs but scans a wider box each time the bot picks a goal (a plan-entry cost, not per-node).");
            kv(w, ConfigKeys.PATHING_BOXED_IN_SCAN_RADIUS, d.boxedInScanRadius());
            line(w, "");

            line(w, "# --- hpa: the persisted region tier (survives a server restart) ---");
            line(w, "# How often (in server ticks) to re-write each dimension's persisted routing/resource data");
            line(w, "# as CRASH INSURANCE, and only when it changed. The authoritative flush happens on a graceful");
            line(w, "# server stop regardless of this. 0 disables the periodic flush (stop flush still runs).");
            line(w, "# Default 6000 (~5 minutes at 20 ticks/second). The pass it triggers is spread across ticks");
            line(w, "# under hpa.persistFlushBudgetMs below (no longer one big write).");
            kv(w, ConfigKeys.HPA_PERSIST_INTERVAL_TICKS, d.persistIntervalTicks());
            line(w, "# Per-tick wall-clock budget (ms, > 0) for that periodic flush drain -- the persistence analog");
            line(w, "# of pathing.hpaFlushBudgetMs. When the interval fires, dirty shards are written under this");
            line(w, "# budget and the pass RESUMES on later ticks until the backlog drains (coarse files last),");
            line(w, "# turning the old one-tick flush spike into a steady trickle. >=1 shard/tick backstop. Clamped");
            line(w, "# to (0, 1000] ms. Default 2.0.");
            kv(w, ConfigKeys.HPA_PERSIST_FLUSH_BUDGET_MS, d.persistFlushBudgetMs());
            line(w, "# Page the persisted region tier in LAZILY: load only the coarse levels at start and stream");
            line(w, "# per-shard leaf files in on demand (bounds RAM), instead of eager-loading every shard up front.");
            line(w, "# Default false (eager). Requires a server restart to change.");
            kv(w, ConfigKeys.HPA_LAZY_LOAD, d.lazyLoad());
            line(w, "# Bounded region RAM: max resident BUILT level-0 cost leaves (~1.6-5.8 KB each) to keep paged in");
            line(w, "# (>= 0). 0 (default) = UNBOUNDED / eviction OFF. A positive value pages the coldest shards whose");
            line(w, "# chunks are all currently unloaded back to disk (keeping the coarse tier resident); they reload");
            line(w, "# on demand when the planner next touches them. Opt-in until in-game-verified.");
            kv(w, ConfigKeys.HPA_RESIDENT_LEAF_CAP, d.residentLeafCap());
            line(w, "");

            line(w, "# --- doors: how the bot deals with doors in its path ---");
            line(w, "# Bot may OPEN/CLOSE hand-toggleable doors (wood/copper) instead of smashing or routing around");
            line(w, "# them. Default false: a planned door-open is only followable once the bot can operate doors (a");
            line(w, "# later feature), so for now an already-open door is walked through and a closed door is mined.");
            line(w, "# Iron doors are never hand-toggleable regardless of this.");
            kv(w, ConfigKeys.DOORS_TOGGLE, d.doorToggle());
            line(w, "");

            line(w, "# --- crafting: how /bot craft handles 3x3 recipes needing a crafting table ---");
            line(w, "# When a recipe needs a table and none is nearby, the bot may place a temporary one from its");
            line(w, "# inventory (crafting the table itself first, from planks, when it only carries the makings).");
            kv(w, ConfigKeys.CRAFTING_PLACE_TABLE, d.placeTable());
            line(w, "# After crafting on a temporary table the bot placed, break it and take it back. This is the one");
            line(w, "# narrow exception to mining.protectedBlocks: it applies only to the exact table the bot placed.");
            kv(w, ConfigKeys.CRAFTING_RECLAIM_TABLE, d.reclaimTable());
            line(w, "# How far (blocks, 0-48) to look for an existing crafting table before placing one. 0 disables");
            line(w, "# the search. Only loaded chunks are seen.");
            kv(w, ConfigKeys.CRAFTING_TABLE_SEARCH_RADIUS, d.tableSearchRadius());
            line(w, "");

            line(w, "# --- farming: how /bot farm tends the fields ---");
            line(w, "# Horizontal half-extent (blocks, 4-48) of the box a farm pass surveys around the command spot.");
            kv(w, ConfigKeys.FARMING_WORK_RADIUS, d.workRadius());
            line(w, "# A pass may TILL new hydrated ground (hoe + seeds required) — i.e. expand the farm, not just");
            line(w, "# tend it. false = harvest/replant/plant existing farmland only.");
            kv(w, ConfigKeys.FARMING_TILL, d.till());
            line(w, "");

            line(w, "# --- combat: self-defense against mobs ---");
            line(w, "# Fight back while any mob is targeting the bot (pauses the current activity, resumes after).");
            line(w, "# Only matters with survival.takesDamage=true — mobs never target an invulnerable bot.");
            kv(w, ConfigKeys.COMBAT_DEFEND, d.defend());
            line(w, "# Half-extent (blocks, 8-32) of the per-tick threat scan box.");
            kv(w, ConfigKeys.COMBAT_SCAN_RADIUS, d.scanRadius());
        } catch (IOException e) {
            OrebitCommon.LOGGER.warn("[Orebit] could not write default config {} — using defaults in memory",
                    file, e);
        }
    }

    private static void line(BufferedWriter w, String s) throws IOException {
        w.write(s);
        w.write('\n');
    }

    private static void kv(BufferedWriter w, String key, Object value) throws IOException {
        w.write(key);
        w.write('=');
        w.write(String.valueOf(value));
        w.write('\n');
    }
}
