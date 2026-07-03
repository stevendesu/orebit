package com.orebit.mod.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.platform.ConfigDir;

import net.minecraft.server.MinecraftServer;

/**
 * Loads {@code config/orebit.properties} at server start and holds the active {@link Config} (PRD §10
 * Phase 1a). Zero new dependencies: the file is plain {@link Properties} ({@code key=value}, {@code #}
 * comments), parsed by the JDK. On a missing file it WRITES a fully-commented default (documenting every
 * key), so a server owner gets a self-describing template the first time they run the mod — and because
 * the defaults reproduce today's behaviour, generating it changes nothing.
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

    /** Re-read the file and re-install (the {@code /bot config reload} path). Returns the new config. */
    public static Config reload(MinecraftServer server) {
        Config c = read(server);
        install(c);
        return c;
    }

    private static void install(Config c) {
        config = c;
        botCaps = c.toBotCaps();
        // Re-bake the mining-tick tables if they've already been built (a /bot config reload after server
        // start) so a changed mining-time model (ticksByHardness / ticksToMineFlat) takes effect immediately.
        // On the FIRST load the tables aren't built yet — OrebitCommon's onServerStarted builds them right
        // after this install with the same config — so we skip (ready() is false), avoiding a double bake.
        if (MiningModel.ready()) {
            MiningModel.buildTable(c.ticksByHardness(), c.ticksToMineFlat());
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

        if (!Files.exists(file)) {
            writeDefault(file);
            OrebitCommon.LOGGER.info("[Orebit] wrote default config to {}", file);
            return Config.DEFAULT;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            OrebitCommon.LOGGER.warn("[Orebit] failed to read {} — using defaults", file, e);
            return Config.DEFAULT;
        }

        ConfigValidator validator = new ConfigValidator(
                msg -> OrebitCommon.LOGGER.warn("[Orebit] config: {}", msg));
        Config c = validator.validate(props);
        OrebitCommon.LOGGER.info("[Orebit] loaded config from {}", file);
        return c;
    }

    /**
     * Write the documented default {@code orebit.properties}. Hand-written (not {@link Properties#store})
     * so each key carries its own {@code #} comment explaining what it controls and its range — a
     * self-describing template. The values are {@link Config#DEFAULT}, so the generated file reproduces
     * today's behaviour verbatim.
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
            line(w, "");

            line(w, "# --- pathing: the A* search knobs ---");
            line(w, "# A* node-expansion ceiling per search (> 0). Higher = can route farther, slower worst case.");
            kv(w, ConfigKeys.PATHING_MAX_NODES, d.maxNodes());
            line(w, "# Heuristic greediness weight (>= 1.0). 1.0 = optimal but slow; higher = faster + greedier.");
            kv(w, ConfigKeys.PATHING_GREEDY_WEIGHT, d.greedyWeight());
            line(w, "# Ticks the bot considers 1 HP of damage to be worth (>= 0) -- the ONE damage-pricing");
            line(w, "# knob: walking through fire/bushes/powder snow and falling past the safe distance are all");
            line(w, "# charged this many ticks per HP. 1 HP buys roughly costPerHitpoint / 4.6 blocks of detour");
            line(w, "# (~22 blocks at the default 100). Raise it for a more self-preserving bot; only matters");
            line(w, "# when survival.takesDamage=true.");
            kv(w, ConfigKeys.PATHING_COST_PER_HITPOINT, d.costPerHitpoint());
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
