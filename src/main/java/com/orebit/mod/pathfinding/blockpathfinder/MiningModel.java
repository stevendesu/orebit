package com.orebit.mod.pathfinding.blockpathfinder;

import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * The tool / mining-speed model (PRD §10 Phase 1c, AGENCY-LAYER-PLAN "Tool use") — turns "how fast does
 * <i>this</i> bot mine <i>this</i> block" into a resident table read the A* hot path can do in array
 * indexing, never a per-node vanilla {@code getDestroySpeed} call.
 *
 * <h2>The split: a resident table (init) × a per-pathfind tool snapshot (cold) → a hot lookup</h2>
 * Two pieces, mirroring the favour-cpu-over-ram rule (precompute per-(navtype × tool-tier) at startup, not
 * per A* node):
 * <ul>
 *   <li><b>{@link #TICKS} — the resident table, built ONCE at init</b> ({@link #buildTable()} runs off the
 *       {@link NavBlock} descriptor table). For every navtype × every {@link Tier} it holds the vanilla
 *       mining time in <b>ticks</b> = {@code ceil(destroyTimeSeconds · 20 · harvestMultiplier / toolSpeed)},
 *       where {@code destroyTimeSeconds} comes from the navtype's quantized {@link NavBlock#hardness}, the
 *       tool speed is the canonical per-tier speed when that tier's tool category matches the block's best
 *       tool (else bare-hand 1.0), and the harvest multiplier is 1.5 (correct tool) or 5.0 (wrong/none).
 *       This is the per-(navtype × tool-tier) tick cost stage 1d will read for real break costs. RAM: a few
 *       hundred navtypes × {@link Tier#COUNT} {@code short}s ≈ a few KB, L1-resident.</li>
 *   <li><b>{@link Snapshot} — the per-pathfind tool snapshot, taken ONCE before the search loop</b> (cold,
 *       from {@link com.orebit.mod.platform.BotInventory}). It classifies the bot's best carried tool into a
 *       {@link Tier} <i>rank per tool category</i> (pickaxe/axe/shovel/...), so the hot path then reads
 *       {@code TICKS[navtype][snapshot.tierFor(navtype)]} — a double array index, no allocation, no MC-API
 *       call. It also carries the feasibility gate primitives (mining-hardness cap, can-mine-at-all).</li>
 * </ul>
 *
 * <h2>Why tiers, not raw {@code getDestroySpeed}, in the table</h2>
 * {@link com.orebit.mod.platform.BotInventory#bestDestroySpeed} (vanilla, enchant-aware) is the truth, but
 * it needs a live {@link net.minecraft.world.level.block.state.BlockState} and is far too costly per node.
 * The hot path has only a navtype. So the table quantises tool capability into a handful of canonical tiers
 * (their speed multipliers are stable vanilla <i>data</i> — 2/4/6/8/9/12 — not the churning {@code
 * Tier}/{@code ToolMaterial} <i>API</i>, which is deliberately never imported here). The snapshot maps the
 * bot's actual best tool to the nearest tier by its measured destroy speed, so Efficiency is captured as a
 * faster effective tier. Exact enchant-accurate ticks remain a 1d refinement layered on this; this stage
 * builds the GATES and the table, not the final cost numbers.
 *
 * <p>Statically-sized + allocation-free on the hot path: {@link #TICKS} and a {@link Snapshot}'s small
 * arrays are sized once; {@link Snapshot#ticks}/{@link Snapshot#canMine} are pure array reads.
 */
public final class MiningModel {

    private MiningModel() {}

    /**
     * Canonical mining tiers (a quantisation of real tool capability for the resident table). Each tier has
     * the {@link NavBlock.Tool} category it satisfies and a vanilla destroy-speed multiplier (the stable
     * tool-speed data: bare hand 1, wood 2, stone 4, iron 6, diamond 8, netherite 9, gold 12). A {@link
     * Snapshot} classifies the bot's best carried tool into one of these per category. {@code BARE} is the
     * universal fallback (speed 1, no category) the bot always has.
     */
    public enum Tier {
        BARE(1.0f),     // empty hand — always available
        WOOD(2.0f),
        STONE(4.0f),
        IRON(6.0f),
        DIAMOND(8.0f),
        NETHERITE(9.0f),
        GOLD(12.0f);    // gold is fastest but lowest harvest level (handled by the harvest multiplier)

        /** The vanilla per-tier destroy-speed multiplier (stable data, not the {@code Tier} API). */
        public final float speed;

        Tier(float speed) {
            this.speed = speed;
        }

        /** Number of tiers — the second dimension of {@link #TICKS}. */
        public static final int COUNT = values().length;
    }

    /** Vanilla harvest-time multiplier: 1.5× when the bot has the correct tool, 5× when it doesn't. */
    private static final float HARVEST_OK = 1.5f;
    private static final float HARVEST_NO = 5.0f;

    /** A tick cost meaning "cannot mine within budget" — used for unbreakable navtypes (hardness 255). */
    public static final short UNMINEABLE = Short.MAX_VALUE;

    /**
     * The fixed stand-in mining time (ticks) for a <b>vanilla-unbreakable</b> block (negative destroy
     * time — quantized hardness 255: bedrock, barriers, end portal frames, …) when the owner opted in via
     * {@code mining.allowUnbreakable}. Vanilla defines NO mining time for these (the -1 hardness can't
     * feed the tick formula), so this is a policy constant, not physics: <b>2400 ticks = 2 minutes</b> per
     * block. Rationale: an order of magnitude above the hardest legitimate dig with the best tool
     * (obsidian × diamond pick ≈ 188 ticks) so the planner treats it as an extreme last resort — the
     * break-even detour is {@code 2400 / 4.633 ≈ 518} walk-blocks — while staying well below the
     * bare-hand-obsidian worst case (5000 ticks) and short enough that the executor's grind actually
     * completes in tolerable real time. Used by BOTH sides (parity): the planner's
     * {@link MovementContext#breakCost} charges it, and the executor's {@code BotMining} accumulates
     * progress at {@code 1/this} per tick, so the planned cost is the time actually spent.
     */
    public static final int UNBREAKABLE_STANDIN_TICKS = 2400;

    /**
     * The resident table: {@code TICKS[navtype][tier]} = mining ticks for that block with that tier of tool.
     * Built once at init by {@link #buildTable()}, then frozen (read-only on the hot path). Sized to the
     * frozen {@link NavBlock#navtypeCount()}.
     */
    private static short[][] TICKS;

    // ---- Field-keyed mining table (stage 1d break-cost hot read) ----------------------------------------
    // The A* hot path holds only a packed NAVBLOCK DESCRIPTOR (a long), never the navtype index — and the
    // synthetic path-edit descriptors (a placed cobble / broken air, MovementContext.PLACED_DESC/AIR_DESC)
    // aren't navtype-backed at all. But the mining TIME depends only on the descriptor's three break-relevant
    // fields — hardness (8 bits), best-tool category (3 bits), tool-required (1 bit) — which sit contiguously
    // at bits 24..35, i.e. a compact 12-bit KEY. So we build a SECOND resident table keyed by that field-key
    // (4096 keys × Tier.COUNT shorts ≈ 57 KB, L1/L2-resident — favour-cpu-over-ram), letting MovementContext
    // turn a descriptor straight into mining ticks with ONE array index per (key,tier) and zero arithmetic on
    // the hot path. It is the same vanilla formula as TICKS, just keyed by fields not navtype, so the two
    // agree for every real navtype; it additionally covers the synthetic descriptors for free.

    /** Shift of the descriptor's contiguous (hardness|tool|toolRequired) field block — NavBlock HARD_SHIFT. */
    private static final int FIELD_KEY_SHIFT = 24;
    /** Mask of that field block: 8-bit hardness + 3-bit tool + 1-bit toolRequired = 12 bits. */
    private static final int FIELD_KEY_MASK = 0xFFF;
    /** Number of distinct field-keys (2^12). */
    private static final int FIELD_KEY_COUNT = FIELD_KEY_MASK + 1;

    /**
     * The field-keyed resident table: {@code TICKS_BY_FIELDS[fieldKey(descriptor)][tier]} = mining ticks.
     * Built alongside {@link #TICKS} in {@link #buildTable()}; read by {@link Snapshot#ticksFor(long)} and the
     * bare-hand fallback {@link #bareHandTicks(long)} on the hot path (one index, no compute).
     */
    private static short[][] TICKS_BY_FIELDS;

    /** The 12-bit (hardness|tool|toolRequired) key of a packed descriptor — its mining-time identity. */
    private static int fieldKey(long descriptor) {
        return (int) (descriptor >>> FIELD_KEY_SHIFT) & FIELD_KEY_MASK;
    }

    // ---- Flat-time mining model (config: mining.ticksByHardness / mining.ticksToMineFlat) ----------------
    // The default model is hardness-derived (real vanilla mining time). The owner can instead pick a FLAT
    // per-block mine time (ticksByHardness=false): every mineable block then costs a constant `flatTicks`,
    // regardless of hardness or tool — a simpler, predictable model. These are read ONCE at buildTable()
    // (cold, from the loaded Config) into statics the table bake consults; never on the hot path. Default
    // hardnessModel=true reproduces the physically-derived behaviour.
    private static boolean flatModel;   // true ⇒ ignore hardness/tool, use flatTicks for every mineable block
    private static int flatTicks;       // the constant mine time when flatModel (0 ⇒ insta-mine, 1 tick floor)

    /**
     * Build the resident mining-tick tables from the frozen {@link NavBlock} descriptor table, honouring the
     * loaded mining-time config ({@code mining.ticksByHardness} / {@code mining.ticksToMineFlat}). Idempotent;
     * call once after {@link NavBlock} static-init AND config load. Pure arithmetic over the descriptor fields
     * — no {@link net.minecraft.world.level.block.state.BlockState}, no MC-API call — so it is cheap and
     * version-agnostic. Builds BOTH the navtype-keyed {@link #TICKS} (kept for navtype-holding callers) and
     * the field-keyed {@link #TICKS_BY_FIELDS} (the descriptor-keyed hot read the break cost uses).
     *
     * @param hardnessModel {@code mining.ticksByHardness} — true = real per-hardness/tool ticks; false = flat
     * @param ticksToMineFlat the constant per-block mine ticks when {@code !hardnessModel} (clamped ≥ 0)
     */
    public static void buildTable(boolean hardnessModel, int ticksToMineFlat) {
        flatModel = !hardnessModel;
        flatTicks = Math.max(0, ticksToMineFlat);

        // (1) Navtype-keyed table.
        int n = NavBlock.navtypeCount();
        short[][] t = new short[n][Tier.COUNT];
        for (int navtype = 0; navtype < n; navtype++) {
            long d = NavBlock.descriptor((short) navtype);
            int hardness = NavBlock.hardness(d);
            int bestToolOrdinal = NavBlock.tool(d);
            boolean toolRequired = NavBlock.toolRequired(d);
            for (int ti = 0; ti < Tier.COUNT; ti++) {
                t[navtype][ti] = ticksFor(hardness, bestToolOrdinal, toolRequired, Tier.values()[ti]);
            }
        }
        TICKS = t;

        // (2) Field-keyed table — one row per 12-bit (hardness|tool|toolRequired) key. Decode the key the
        // same way fieldKey() packs it (hardness low 8, tool next 3, toolRequired top bit), so a descriptor's
        // ticks are TICKS_BY_FIELDS[fieldKey(d)][tier] with no per-node arithmetic.
        short[][] f = new short[FIELD_KEY_COUNT][Tier.COUNT];
        for (int key = 0; key < FIELD_KEY_COUNT; key++) {
            int hardness = key & 0xFF;
            int bestToolOrdinal = (key >>> 8) & 0x07;
            boolean toolRequired = (key & 0x800) != 0;
            for (int ti = 0; ti < Tier.COUNT; ti++) {
                f[key][ti] = ticksFor(hardness, bestToolOrdinal, toolRequired, Tier.values()[ti]);
            }
        }
        TICKS_BY_FIELDS = f;
    }

    /** Convenience: build with the hardness-derived model (the default; used where no config is threaded). */
    public static void buildTable() {
        buildTable(true, 0);
    }

    /**
     * One table cell: the vanilla mining time in ticks for a block of {@code hardness} whose best tool
     * category is {@code bestToolOrdinal} (a {@link NavBlock.Tool} ordinal), mined with {@code tier}.
     *
     * <p>{@link NavBlock} stores {@code hardness = round(destroyTime · 5)}, so the block's real
     * {@code destroyTime} seconds is {@code hardness / 5}. Vanilla mines at {@code progressPerTick =
     * speed / destroyTime / divisor} where {@code divisor} is 30 with a correct tool and 100 without; total
     * ticks = {@code ceil(destroyTime · divisor / speed)}. Expressing {@code divisor} as a multiple of the
     * 20-tick second gives the harvest multiplier (30/20 = 1.5×, 100/20 = 5×), so ticks =
     * {@code ceil(destroyTimeSeconds · 20 · harvestMultiplier / speed)} — the form below.
     */
    private static short ticksFor(int hardness, int bestToolOrdinal, boolean toolRequired, Tier tier) {
        if (hardness == 255) return UNMINEABLE; // unbreakable (bedrock/barrier) — flat model too

        // Flat model (mining.ticksByHardness=false): every mineable block costs the same constant time,
        // regardless of hardness or tool. A floor of 1 tick keeps a placed move strictly positive (0 ⇒ the
        // historical insta-mine, so the flat default of 0 reproduces "mining is free").
        if (flatModel) {
            return (short) Math.max(0, Math.min(flatTicks, UNMINEABLE - 1));
        }

        if (hardness == 0) return 1; // insta-mine (the historical behaviour for soft/instant blocks)

        float destroyTimeSeconds = hardness / 5.0f;

        // Does this tier's tool satisfy the block's best-tool category? BARE never does (category NONE).
        boolean tierMatches = tier != Tier.BARE && bestToolOrdinal != NavBlock.Tool.NONE.ordinal();
        float speed = tierMatches ? tier.speed : 1.0f;

        // Correct-tool harvest: a block that requires a correct tool gets the slow 5× unless this tier
        // actually matches its category; a block that doesn't require one always gets the fast 1.5×.
        boolean harvests = !toolRequired || tierMatches;
        float harvestMult = harvests ? HARVEST_OK : HARVEST_NO;

        float ticks = destroyTimeSeconds * 20.0f * harvestMult / speed;
        int t = (int) Math.ceil(ticks);
        if (t < 1) t = 1;
        return (short) Math.min(t, UNMINEABLE - 1);
    }

    /** True once {@link #buildTable()} has run (the resident table is ready). */
    public static boolean ready() {
        return TICKS != null;
    }

    /**
     * Bare-hand mining ticks for a descriptor — the break-cost fallback for a search with NO inventory
     * snapshot (the JMH benchmarks, {@code /bot trace}, unit tests, and any caps-only search): mine time with
     * an empty hand ({@link Tier#BARE}). A pure resident-table read ({@code TICKS_BY_FIELDS[key][BARE]}), so
     * it is hot-path safe, and it gives those searches the SAME real-tick cost model the live bot uses (just
     * with the worst tool) rather than the old magic-number break cost. Returns {@link #UNMINEABLE} for an
     * unbreakable block, or {@code 0} if the table isn't built yet (so a pre-init/headless caller charges no
     * break cost rather than NPEing — break-folding still gates on {@code breakable}).
     */
    public static int bareHandTicks(long descriptor) {
        short[][] f = TICKS_BY_FIELDS;
        if (f == null) return 0;
        return f[fieldKey(descriptor)][Tier.BARE.ordinal()];
    }

    /**
     * The <b>minimum</b> mining ticks for a descriptor across every tier — the time the <i>best possible</i>
     * tool achieves. This is the admissible lower bound the {@link
     * com.orebit.mod.pathfinding.blockpathfinder.cuboid.GoalForcedCost} dig-face premium needs: the probe runs
     * without a bot's inventory, and a forced-cost heuristic must never EXCEED the true cost (the bot might
     * carry a fast pickaxe), so it charges the cheapest dig any tool could manage. A resident-table scan over
     * {@link Tier#COUNT} columns (off the per-node hot path — the probe is once per search). Returns {@link
     * #UNMINEABLE} for an unbreakable block, {@code 0} if the table isn't built.
     */
    public static int fastestTicks(long descriptor) {
        short[][] f = TICKS_BY_FIELDS;
        if (f == null) return 0;
        short[] row = f[fieldKey(descriptor)];
        int min = row[0];
        for (int i = 1; i < row.length; i++) {
            if (row[i] < min) min = row[i];
        }
        return min;
    }

    /** Number of navtypes the table covers (its first dimension). */
    public static int navtypeCount() {
        return TICKS == null ? 0 : TICKS.length;
    }

    /**
     * The per-pathfind tool snapshot — the bot's mining capability captured ONCE (cold) before the search,
     * read on the hot path as pure array indexing. Built by {@link #snapshot(int, int, boolean, boolean,
     * boolean, boolean, boolean, boolean, boolean)} from the {@link com.orebit.mod.platform.BotInventory}
     * scan (which tools the bot carries, classified into a {@link Tier} rank per tool category). A snapshot
     * NEVER holds a reference to the live inventory; it is plain primitives so the A* loop stays
     * alloc-free.
     */
    public static final class Snapshot {
        /** Per {@link NavBlock.Tool} ordinal: the best {@link Tier} ordinal the bot carries for that category. */
        private final int[] tierForCategory;
        /** The mining-hardness cap (from {@link BotCaps#maxBreakHardness()} / config). */
        private final int maxBreakHardness;
        /** Whether the bot may break at all (from {@link BotCaps#canBreak()}). */
        private final boolean canBreak;

        Snapshot(int[] tierForCategory, int maxBreakHardness, boolean canBreak) {
            this.tierForCategory = tierForCategory;
            this.maxBreakHardness = maxBreakHardness;
            this.canBreak = canBreak;
        }

        /**
         * The {@link Tier} ordinal this snapshot uses to mine the given navtype's descriptor — the best tool
         * the bot carries for that block's best-tool category, falling back to {@link Tier#BARE}. Pure array
         * read (descriptor field extract + one index), hot-path safe.
         */
        public int tierFor(long descriptor) {
            int cat = NavBlock.tool(descriptor);
            int tier = tierForCategory[cat];
            return tier;
        }

        /**
         * Mining ticks for the given navtype with this bot's tools — {@code TICKS[navtype][tierFor]}. The
         * value is the resident table cell, no per-node compute. Returns {@link #UNMINEABLE} for an
         * unbreakable block. (Kept for callers that hold a navtype index; the hot break-cost path uses the
         * descriptor-keyed {@link #ticksFor(long)} instead, since the A* carries only the descriptor.)
         */
        public int ticks(int navtype, long descriptor) {
            return TICKS[navtype][tierFor(descriptor)];
        }

        /**
         * Mining ticks for a packed {@link NavBlock} descriptor with this bot's best tool for the block's
         * category — the stage-1d break-cost hot read: {@code TICKS_BY_FIELDS[fieldKey(descriptor)][tierFor]}.
         * Two array indices (the field-key extract is one shift+mask, the tier one more index), NO arithmetic,
         * no navtype needed, no live inventory — fully hot-path safe (HOT-PATH-NO-ALLOC). Works for the
         * synthetic path-edit descriptors too (a placed cobble / broken air), since it keys on fields, not on a
         * registered navtype. Returns {@link #UNMINEABLE} for an unbreakable block.
         */
        public int ticksFor(long descriptor) {
            return TICKS_BY_FIELDS[fieldKey(descriptor)][tierFor(descriptor)];
        }

        /**
         * The feasibility GATE the planner's {@link MovementContext#breakable} consults: can this bot mine a
         * block of this descriptor <i>at all</i>? True when the bot may break, the block is breakable geometry
         * (solid, non-fluid, not the {@link NavBlock#hardness} 255 unbreakable sentinel), and its quantized
         * hardness is within the configured {@code maxBreakHardness} cap.
         *
         * <p><b>Tool is NOT a feasibility gate</b> (the stone-bare-hand fix). A block whose {@code
         * requiresCorrectToolForDrops()} bit is set ({@link NavBlock#toolRequired}) — stone, ore, obsidian —
         * is still <i>breakable</i> bare-handed; that flag governs DROPS, not breakability. The missing tool
         * only inflates the break TIME: {@link #ticksFor} already applies the vanilla 5× ({@link #HARVEST_NO})
         * harvest multiplier to a tool-required block mined without the matching tool, so the planner sees it
         * as expensive (a long, drop-less dig) and routes around it when any cheaper path exists — but it is
         * NOT walled off, so a bot with only its hands can still mine straight up out of a cave when that is
         * the only way through. The {@code maxBreakHardness} config cap remains the lever for forbidding the
         * truly absurd (set it below a block's hardness to make that block hard-unmineable for this bot).
         */
        public boolean canMine(long descriptor) {
            if (!canBreak) return false;
            if (!NavBlock.isBreakable(descriptor)) return false;          // air/plant/fluid/unbreakable
            return NavBlock.hardness(descriptor) <= maxBreakHardness;
        }
    }

    /**
     * Build a {@link Snapshot} from the bot's carried-tool capability, classified per {@link NavBlock.Tool}
     * category into a {@link Tier} rank. The caller (the pathfind setup) supplies, for each tool category,
     * the best {@link Tier} ordinal the bot carries (derived from the {@link
     * com.orebit.mod.platform.BotInventory} scan), plus the break cap + canBreak from {@link BotCaps}. Cold
     * (once per pathfind), so the small array allocation here is off the hot path.
     */
    public static Snapshot snapshot(int[] bestTierPerCategory, int maxBreakHardness, boolean canBreak) {
        return new Snapshot(bestTierPerCategory.clone(), maxBreakHardness, canBreak);
    }

    /**
     * Classify a measured vanilla destroy speed (from {@link com.orebit.mod.platform.BotInventory#bestDestroySpeed},
     * which already folds in the tool's tier <i>and</i> its Efficiency enchantment) into the nearest {@link
     * Tier} <b>ordinal</b> — the column the resident table is keyed on. The bot-side scan measures the best
     * carried tool's speed against a canonical block of each tool category, then calls this to bucket it; a
     * speed at or below bare-hand maps to {@link Tier#BARE}. Pure arithmetic (no MC-API), so the bot-side
     * classification stays cheap and version-agnostic. Picks the highest tier whose canonical speed the
     * measured speed reaches (so an Efficiency-boosted diamond pick reads as netherite/gold-fast).
     */
    public static int classifyTier(float destroySpeed) {
        int best = Tier.BARE.ordinal();
        Tier[] tiers = Tier.values();
        for (int i = 0; i < tiers.length; i++) {
            // skip BARE in the "matched a real tool" sense, but still allow it as the floor
            if (i == Tier.BARE.ordinal()) continue;
            if (destroySpeed >= tiers[i].speed - 1e-3f && tiers[i].ordinal() > best) best = tiers[i].ordinal();
        }
        return best;
    }
}
