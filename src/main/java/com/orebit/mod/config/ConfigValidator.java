package com.orebit.mod.config;

import java.util.Properties;
import java.util.function.Consumer;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.platform.BlockLookup;

import net.minecraft.world.level.block.Block;

/**
 * Parses + range-checks the raw {@link Properties} loaded from {@code config/orebit.properties} into a
 * validated, immutable {@link Config} (PRD §10 Phase 1a). Every field is clamped or defaulted so the rest
 * of the agency layer never sees an out-of-range value: a typo or hostile edit degrades to a warning + the
 * default for that one key, never a crash. Idempotent — validating the same input twice yields the same
 * {@code Config}.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>booleans: anything other than {@code true}/{@code false} (case-insensitive) → the key's default;</li>
 *   <li>{@code pathing.syncSearchBudgetNodes} must be {@code > 0} (else the sync A* cap is disabled) → clamp to ≥ 1,
 *       defaulting a non-integer;</li>
 *   <li>{@code pathing.greedyWeight} must be {@code >= 1.0} (below 1 is non-greedy / nonsensical for this
 *       weighted-octile search) → clamp up, defaulting a non-number;</li>
 *   <li>{@code pathing.costPerHitpoint} must be {@code >= 0} (0 = damage priced at nothing) → clamp up,
 *       defaulting a non-number;</li>
 *   <li>{@code mining.maxHardness} must be {@code 0..255} (the quantized-hardness range) → clamp;</li>
 *   <li>{@code mining.ticksToMineFlat} must be {@code >= 0} → clamp;</li>
 *   <li>{@code placement.conjuredBlock} must resolve to a real {@link Block} on the running version (via
 *       the {@link BlockLookup} platform seam) → on a malformed id or an unknown block, warn and fall back
 *       to the default ({@code minecraft:cobblestone});</li>
 *   <li>{@code mining.protectedBlocks} is parsed per entry ({@link ProtectedBlocks#parse}) — each
 *       malformed id / tag warns and is skipped individually, the remaining entries still apply.</li>
 * </ul>
 *
 * <p>A smart object, not a static helper bag: one {@code ConfigValidator} carries the warning sink (so the
 * loader routes warnings to its logger) and exposes {@link #validate}. The per-field parse helpers are
 * private instance methods that warn through that sink.
 */
public final class ConfigValidator {

    /** Where a clamp/reject warning goes (the loader wires this to its SLF4J logger). */
    private final Consumer<String> warn;

    public ConfigValidator(Consumer<String> warn) {
        this.warn = warn;
    }

    /**
     * Parse + clamp {@code props} into a validated {@link Config}. Any key absent from {@code props} takes
     * its {@link Config#DEFAULT} value (so a partial file written by an older mod version still loads); any
     * present-but-invalid value is clamped/defaulted with a warning.
     */
    public Config validate(Properties props) {
        Config d = Config.DEFAULT;
        return new Config(
                // survival
                bool(props, ConfigKeys.SURVIVAL_TAKES_DAMAGE, d.takesDamage()),
                bool(props, ConfigKeys.SURVIVAL_HUNGER, d.hunger()),
                bool(props, ConfigKeys.SURVIVAL_NEEDS_BREATH, d.needsBreath()),
                // placement
                bool(props, ConfigKeys.PLACEMENT_CAN_PLACE, d.canPlace()),
                bool(props, ConfigKeys.PLACEMENT_CONSUMES_BLOCKS, d.consumesBlocks()),
                block(props, ConfigKeys.PLACEMENT_CONJURED_BLOCK, d.conjuredBlock()),
                weightNonNeg(props, ConfigKeys.PLACEMENT_REMOVAL_COST_WEIGHT, d.removalCostWeight()),
                weightNonNeg(props, ConfigKeys.PLACEMENT_PLACE_BASE_COST, d.placeBaseCost()),
                // mining
                bool(props, ConfigKeys.MINING_CAN_MINE, d.canMine()),
                bool(props, ConfigKeys.MINING_CONSUMES_TOOLS, d.consumesTools()),
                intClamped(props, ConfigKeys.MINING_MAX_HARDNESS, d.maxHardness(), 0, BotCaps.UNBREAKABLE),
                bool(props, ConfigKeys.MINING_TICKS_BY_HARDNESS, d.ticksByHardness()),
                intClamped(props, ConfigKeys.MINING_TICKS_TO_MINE_FLAT, d.ticksToMineFlat(), 0, Integer.MAX_VALUE),
                weightNonNeg(props, ConfigKeys.MINING_BREAK_BASE_COST, d.breakBaseCost()),
                protectedBlocks(props, ConfigKeys.MINING_PROTECTED_BLOCKS, d.protectedBlocks()),
                bool(props, ConfigKeys.MINING_ALLOW_UNBREAKABLE, d.allowUnbreakable()),
                // pathing
                intClamped(props, ConfigKeys.PATHING_SYNC_SEARCH_BUDGET_NODES, d.maxNodes(), 1, Integer.MAX_VALUE),
                weight(props, ConfigKeys.PATHING_GREEDY_WEIGHT, d.greedyWeight()),
                weightNonNeg(props, ConfigKeys.PATHING_COST_PER_HITPOINT, d.costPerHitpoint()),
                bool(props, ConfigKeys.PATHING_WARMUP, d.warmup()),
                intClamped(props, ConfigKeys.PATHING_WARMUP_BUDGET_MS, d.warmupBudgetMs(), 0, 60_000),
                bool(props, ConfigKeys.PATHING_ASYNC, d.asyncPathing()),
                // Upper clamps are sanity rails only: maxThreads is re-clamped to [1, cores-2] at pool
                // start (the host's core count isn't known here), and a >10s search budget is a config typo.
                intClamped(props, ConfigKeys.PATHING_MAX_THREADS, d.maxThreads(), 1, 64),
                intClamped(props, ConfigKeys.PATHING_ASYNC_SEARCH_BUDGET_MS, d.asyncSearchBudgetMs(), 1, 10_000));
    }

    // ---- per-type parse + clamp (each warns through the sink and never throws) ----------------------

    private boolean bool(Properties props, String key, boolean def) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        raw = raw.trim();
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        warn.accept(key + "='" + raw + "' is not true/false — using default " + def);
        return def;
    }

    private int intClamped(Properties props, String key, int def, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            warn.accept(key + "='" + raw.trim() + "' is not an integer — using default " + def);
            return def;
        }
        if (v < min) { warn.accept(key + "=" + v + " below minimum " + min + " — clamped"); return min; }
        if (v > max) { warn.accept(key + "=" + v + " above maximum " + max + " — clamped"); return max; }
        return v;
    }

    private float weight(Properties props, String key, float def) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        float v;
        try {
            v = Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            warn.accept(key + "='" + raw.trim() + "' is not a number — using default " + def);
            return def;
        }
        if (v < 1.0f) { warn.accept(key + "=" + v + " below minimum 1.0 — clamped"); return 1.0f; }
        return v;
    }

    /**
     * Like {@link #weight} but clamps to {@code >= 0.0} (0 = disabled / no cost), for the non-negative float
     * knobs {@code placement.removalCostWeight}, {@code placement.placeBaseCost},
     * {@code mining.breakBaseCost}, and {@code pathing.costPerHitpoint}.
     */
    private float weightNonNeg(Properties props, String key, float def) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        float v;
        try {
            v = Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            warn.accept(key + "='" + raw.trim() + "' is not a number — using default " + def);
            return def;
        }
        if (v < 0.0f) { warn.accept(key + "=" + v + " below minimum 0.0 — clamped"); return 0.0f; }
        return v;
    }

    /**
     * Parse the {@code mining.protectedBlocks} comma list (block ids + {@code #}-prefixed tags) into a
     * {@link ProtectedBlocks}. Per the clamp-and-warn rule each malformed / unknown entry warns and is
     * skipped individually — the rest of the list still applies; an absent key keeps the default.
     */
    private ProtectedBlocks protectedBlocks(Properties props, String key, ProtectedBlocks def) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        return ProtectedBlocks.parse(raw, warn);
    }

    private Block block(Properties props, String key, Block def) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        Block b = BlockLookup.byId(raw.trim());
        if (b == null) {
            warn.accept(key + "='" + raw.trim() + "' is not a known block id — using default");
            return def;
        }
        return b;
    }
}
