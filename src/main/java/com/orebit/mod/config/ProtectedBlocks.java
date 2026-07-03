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
 *   <li><b>Executor (this object, cold):</b> every live break site ({@code AllyBotEntity.applyEdits}/
 *       {@code place}, {@code BotMining}) re-checks the LIVE block state against {@link #matches} via
 *       {@link Config#mayBreak} — the hard backstop that also covers stale nav grids.</li>
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

    /** The empty list (the default): protects nothing, matches nothing. */
    public static final ProtectedBlocks EMPTY = new ProtectedBlocks("", Set.of(), List.of());

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
