package com.orebit.mod.config;

/**
 * The single source of truth for the flat, namespaced property keys in {@code config/orebit.properties}
 * (PRD §10 Phase 1a). Every key the owner can set appears here
 * exactly once, so {@link ConfigLoader} (read + default-file generation), {@link ConfigValidator} (range
 * checks), and any future doc/GUI tooling all name the same string — no stringly-typed drift between the
 * file format, the parser, and the validator.
 *
 * <h2>Layout — flat dotted namespaces, NOT nested sections</h2>
 * The on-disk format is a plain {@link java.util.Properties} file (JDK built-in, zero new deps; {@code #}
 * comments). Keys are grouped by a leading namespace that mirrors the four owner-facing capability
 * groups Steve listed (survival / placement / mining / pathing), e.g. {@code mining.consumesTools}. The
 * grouping is purely lexical (a dotted prefix), since {@code Properties} has no real sections.
 *
 * <p>Constants are grouped + ordered the same way the default file is written, so reading this class
 * top-to-bottom reads the generated {@code orebit.properties}. Pure constants — no logic, no state
 * (honouring "no Utils/Helper classes": this is a key vocabulary, not a helper).
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    // ---- survival: does the bot have a body that can be hurt / starve / drown? ---------------------
    /** {@code boolean} — bot takes damage (fall, mob, fire, …) at all. Default {@code false} (invulnerable). */
    public static final String SURVIVAL_TAKES_DAMAGE = "survival.takesDamage";
    /** {@code boolean} — bot has a hunger bar that depletes. Default {@code false}. */
    public static final String SURVIVAL_HUNGER = "survival.hunger";
    /** {@code boolean} — bot needs air underwater (drowns). Default {@code false}. */
    public static final String SURVIVAL_NEEDS_BREATH = "survival.needsBreath";

    // ---- placement: may the bot place blocks, and what does that cost / conjure? --------------------
    /** {@code boolean} — bot may place blocks (bridge a gap, pillar up). Default {@code true}. */
    public static final String PLACEMENT_CAN_PLACE = "placement.canPlace";
    /** {@code boolean} — placing consumes a real block from the bot's inventory. Default {@code false} (infinite). */
    public static final String PLACEMENT_CONSUMES_BLOCKS = "placement.consumesBlocks";
    /**
     * {@code block id} ("namespace:path") — the throwaway block the bot conjures/places when {@link
     * #PLACEMENT_CONSUMES_BLOCKS} is off. Default {@code minecraft:cobblestone} (today's hardcoded
     * {@code PLACE_BLOCK}). Must resolve to a real block on the running version or the validator rejects it.
     */
    public static final String PLACEMENT_CONJURED_BLOCK = "placement.conjuredBlock";
    /**
     * {@code float >= 0} — how strongly the bot avoids placing hard-to-remove blocks. Each placement is
     * charged extra by the placed block's mine-out time (ticks) × this weight, so a mixed inventory favours
     * dirt/cobblestone over obsidian. {@code 1.0} (default) = full mine-out cost; {@code 0} disables the
     * premium (placement cost ignores the block).
     */
    public static final String PLACEMENT_REMOVAL_COST_WEIGHT = "placement.removalCostWeight";
    /**
     * {@code float >= 0} — the flat cost (in ticks) charged per block placed. NOT a physical place time
     * (placing is ~instant in-game); it is a behavioral "reluctance to place" penalty (positioning/facing
     * overhead + a bias against needless scaffolding) that tilts the planner toward walking/digging over
     * building. Lower for a more build-happy bot; raise to discourage placing. Default {@code 6.0}.
     */
    public static final String PLACEMENT_PLACE_BASE_COST = "placement.placeBaseCost";

    // ---- mining: may the bot mine, what can it mine, how long does it take? -------------------------
    /** {@code boolean} — bot may mine (break) blocks in its way. Default {@code true}. */
    public static final String MINING_CAN_MINE = "mining.canMine";
    /** {@code boolean} — mining wears down (and consumes) the bot's tools. Default {@code false}. */
    public static final String MINING_CONSUMES_TOOLS = "mining.consumesTools";
    /**
     * {@code int} 0..255 — the hardest block (quantized {@link
     * com.orebit.mod.worldmodel.navblock.NavBlock} hardness) the bot may mine. {@code 255} = mine
     * anything breakable (today's insta-mine cap); lower values let a weak bot mine only soft blocks.
     */
    public static final String MINING_MAX_HARDNESS = "mining.maxHardness";
    /**
     * {@code boolean} — mining time scales with block hardness ({@code true}, the physically-derived
     * model) vs. a flat per-block time ({@code false}, {@link #MINING_TICKS_TO_MINE_FLAT}). Default
     * {@code true}.
     */
    public static final String MINING_TICKS_BY_HARDNESS = "mining.ticksByHardness";
    /**
     * {@code int} — ticks to mine one block when {@link #MINING_TICKS_BY_HARDNESS} is off (the flat
     * model). Ignored when ticks-by-hardness is on. Default {@code 0} (insta-mine, matching today).
     */
    public static final String MINING_TICKS_TO_MINE_FLAT = "mining.ticksToMineFlat";
    /**
     * {@code float >= 0} — a flat surcharge (in ticks) added to <b>every block break the planner folds</b>,
     * on top of the real mining time — the mining-side mirror of {@link #PLACEMENT_PLACE_BASE_COST}: a
     * behavioral "reluctance to edit the world" penalty that biases the bot toward walking/detouring over
     * digging (and over punching through berry bushes / cobwebs) when comparable routes exist. Raise it to
     * discourage gratuitous world edits. Default {@code 0.0} (breaks priced at mining time alone).
     */
    public static final String MINING_BREAK_BASE_COST = "mining.breakBaseCost";
    /**
     * Comma-separated list of block ids and {@code #}-prefixed block tags the bot must <b>NEVER</b> break
     * — nor clear/replace with a placement — (e.g. {@code minecraft:chest, #minecraft:beds,
     * minecraft:diamond_ore}). Default empty (nothing protected). Enforced BOTH planner-side (folded into
     * the NavBlock classification fingerprint as the
     * PROTECTED descriptor bit, so routes are planned around protected blocks) and execution-side (every
     * live break re-checks the list — the stale-grid backstop). Malformed entries warn and are skipped.
     * <b>Changing this list requires a server restart</b> (or waiting for chunks to rebuild) to fully
     * apply: nav-grid data classified before the change still carries the old fingerprints; the
     * execution-side refusal applies immediately.
     */
    public static final String MINING_PROTECTED_BLOCKS = "mining.protectedBlocks";
    /**
     * {@code boolean} — bot may mine <b>vanilla-unbreakable</b> blocks (negative destroy time: bedrock,
     * barriers, end portal frames, …) at a fixed, very large stand-in time cost. Its own gate (a separate
     * axis from {@link #MINING_MAX_HARDNESS}, which only ranges over breakable blocks — the unbreakable
     * sentinel doesn't order against real hardness). {@link #MINING_PROTECTED_BLOCKS} always overrides.
     * Default {@code false}.
     */
    public static final String MINING_ALLOW_UNBREAKABLE = "mining.allowUnbreakable";
    /**
     * {@code int >= 1} — the synthetic pseudo-hardness assigned to vanilla-unbreakable blocks (bedrock,
     * barriers, portal frames — no real destroy time) when {@link #MINING_ALLOW_UNBREAKABLE} is on. It feeds
     * the SAME mining-time formula real blocks use, assuming a pickaxe (correct-tool-required), so a better
     * pickaxe tier digs faster and bare hands are drastically slower; both the planner's cost and the
     * executor's grind rate derive from it (parity). On the same quantized scale as real blocks (obsidian, the
     * hardest, is ~250) but may exceed 255 to make unbreakable mining a stronger deterrent. Default {@code
     * 3200} — a diamond pickaxe grinds one block in ~2400 ticks (2 minutes), matching the old fixed cost.
     */
    public static final String MINING_UNBREAKABLE_HARDNESS = "mining.unbreakableHardness";

    // ---- pathing: the A* search knobs (carried on BotCaps into BlockPathfinder) ---------------------
    /**
     * {@code int > 0} — A* node-expansion ceiling per SYNC-mode search (the tick-thread search budget, in
     * nodes). Only consulted when {@link #PATHING_ASYNC} is off; async mode caps by wall-clock
     * ({@link #PATHING_ASYNC_SEARCH_BUDGET_MS}) with a large memory-only node backstop instead. Sync keeps a
     * NODE cap (not a time cap) so the tick stall stays bounded + deterministic — a wall-clock budget on the
     * tick thread could freeze the server for its whole duration. Default {@code 10000}.
     */
    public static final String PATHING_SYNC_SEARCH_BUDGET_NODES = "pathing.syncSearchBudgetNodes";
    /**
     * {@code float >= 1.0} — heuristic greediness weight. {@code 1.0} = admissible/optimal/slow; higher
     * beelines (far fewer nodes, paths no longer guaranteed optimal). Default {@code 2.0}.
     */
    public static final String PATHING_GREEDY_WEIGHT = "pathing.greedyWeight";
    /**
     * {@code float >= 0} — ticks the bot considers <b>1 HP of damage</b> to be worth: the ONE knob every
     * damage-as-cost planner term is priced in (hazard-cell transit — fire / berry bush / powder snow —
     * and fall damage past the safe window). One HP buys {@code costPerHitpoint / 4.633} walk-blocks of
     * detour (≈ 21.6 at the default {@code 100.0}); raise it for a more self-preserving bot. Only
     * meaningful when {@code survival.takesDamage} is on (an immune bot's damage terms are zero).
     */
    public static final String PATHING_COST_PER_HITPOINT = "pathing.costPerHitpoint";
    /**
     * {@code boolean} — run a short synthetic pathfinder warm-up at server start (~0.3–1.5 s, before any
     * player can join) so the first REAL search doesn't run JIT-cold (a one-time ~16 ms tick stall on the
     * first search after boot otherwise). Costs startup wall-clock only — zero effect on any search after
     * boot. Default {@code true}.
     */
    public static final String PATHING_WARMUP = "pathing.warmup";
    /**
     * {@code int >= 0} — hard wall-clock cap (milliseconds) on that warm-up pass; it usually finishes
     * earlier (it stops once search times plateau). {@code 0} disables the warm-up entirely (same as
     * {@code pathing.warmup=false}). Default {@code 1500}.
     */
    public static final String PATHING_WARMUP_BUDGET_MS = "pathing.warmupBudgetMs";
    /**
     * {@code boolean} — run path searches on background planner threads instead of the server tick thread
     * (DESIGN-background-pathfinding.md). The tick thread submits a search and adopts the result at the
     * same settled boundary plans already swap at; searches stop costing tick time entirely, and the
     * wall-clock budget below replaces the node cap as the effective search limit. Plans arrive 1–3 ticks
     * after they're requested (the bot keeps walking its current plan meanwhile). Default {@code true};
     * set {@code false} for the old synchronous tick-thread behaviour. Changing it requires a server restart.
     */
    public static final String PATHING_ASYNC = "pathing.async";
    /**
     * {@code int >= 1} — background planner thread count when {@link #PATHING_ASYNC} is on (clamped to
     * {@code [1, cores − 2]} at start). Bots share the pool; raise it on a multi-tenant server (many bots)
     * to cut search tail latency, lower it to 1 on a constrained host. Like view-distance, this trades bot
     * responsiveness against server CPU headroom. Default {@code 2}. Requires a server restart to change.
     */
    public static final String PATHING_MAX_THREADS = "pathing.maxThreads";
    /**
     * {@code int >= 1} — wall-clock budget (milliseconds) per background path search when
     * {@link #PATHING_ASYNC} is on: the time-based cap that replaces {@link #PATHING_SYNC_SEARCH_BUDGET_NODES}
     * as the effective limit (a large node cap remains only as a memory backstop). A search that exhausts the
     * budget returns its best partial path — the bot moves that way and replans, converging on far goals.
     * Bigger budgets escape bigger dead-ends at the cost of longer worst-case plan latency; the tick itself
     * is never stalled either way (these searches run off the tick thread). Default {@code 250}.
     */
    public static final String PATHING_ASYNC_SEARCH_BUDGET_MS = "pathing.asyncSearchBudgetMs";

    /**
     * {@code int >= 1} — per-level per-tick <b>COUNT BACKSTOP</b> on NavGrid chunk builds (drained at
     * tick-end from the chunk-load queue). <b>No longer the primary gate</b> — that is now the wall-clock
     * {@link #PATHING_CHUNK_BUILD_BUDGET_MS}. This is the safety ceiling that keeps a burst of cheap all-air
     * columns from draining unbounded and spiking other tick work even inside the time budget; the time
     * budget is what actually stops a run of expensive (cave) columns. A teleport / world-open can queue
     * hundreds of chunks at once; the two together bound how fast they fill in so the build cost stays off
     * the frame budget. Raise it on a strong / multi-core machine to let more cheap columns build per tick;
     * lower it on a constrained host. Default {@code 64} (a backstop value, not a per-tick target).
     */
    public static final String PATHING_CHUNK_BUILDS_PER_TICK = "pathing.chunkBuildsPerTick";
    /**
     * {@code float > 0} — the <b>primary</b> per-level per-tick wall-clock budget (milliseconds) for
     * draining the NavGrid chunk-build queue. Elapsed time is checked after each column, so the worst-case
     * overshoot is one column. Per-column build cost swings ~15× with terrain (a surface column ~1 ms, a
     * cave column ~5 ms — {@code ChunkBuildBenchmark}), which a fixed COUNT can't adapt to (8 cave columns =
     * ~41 ms of a 50 ms tick); a wall-clock budget drains as many columns as safely fit. Bounded above by
     * the {@link #PATHING_CHUNK_BUILDS_PER_TICK} count backstop. Clamped to {@code (0, 1000]} ms — the ceiling
     * is generous (1 s) so a determinism-pinned harness (the headless autotest) can set a HIGH budget that
     * never binds, letting the deterministic count backstop govern instead; a &gt; 1 s per-tick drain budget is
     * the real typo. Default {@code 2.0}.
     */
    public static final String PATHING_CHUNK_BUILD_BUDGET_MS = "pathing.chunkBuildBudgetMs";
    /**
     * {@code float > 0} — the per-level per-tick wall-clock budget (milliseconds) for the HPA* dirty-leaf
     * flush ({@link com.orebit.mod.worldmodel.hpa.HpaMaintenance#flush}), the region-tier analog of
     * {@link #PATHING_CHUNK_BUILD_BUDGET_MS}. Elapsed time is checked after each leaf (worst-case overshoot
     * one leaf). A leaf rebuild is cheap-ish (~0.15–1.8 ms), so at the default {@code 1.0} ms this usually
     * drains the (typically small) dirty set fully; a bulk edit amortizes over a few ticks instead of
     * stalling one. Bounded above by a hardcoded leaf-count backstop
     * ({@code HpaMaintenance.MAX_LEAVES_PER_TICK}). Clamped to {@code (0, 1000]} ms (generous ceiling — the
     * autotest pins a high, never-binding budget for determinism). Default {@code 1.0}.
     */
    public static final String PATHING_HPA_FLUSH_BUDGET_MS = "pathing.hpaFlushBudgetMs";
    /**
     * {@code float > 0} — the per-level per-tick wall-clock budget (milliseconds) for the Stage-2 <b>region-shard
     * lazy-load</b> drain ({@code RegionShardLoader}), the region-persistence analog of
     * {@link #PATHING_CHUNK_BUILD_BUDGET_MS}. When {@link com.orebit.mod.config.ConfigKeys#HPA_LAZY_LOAD hpa.lazyLoad}
     * is on, a coarse-only startup pages shard leaves in on demand; each whole-shard load is atomic (measured
     * ~11–34 ms, tick-safe now that the on-disk format is un-gzipped) and this budget bounds how many shards a
     * single tick pages in, with a hardcoded ≥1-per-tick count backstop so progress is always guaranteed. Elapsed
     * is checked after each shard (worst-case overshoot one shard). Clamped to {@code (0, 1000]} ms. Default
     * {@code 2.0}. Ignored under eager-load-all (no shards are ever requested).
     */
    public static final String PATHING_REGION_SHARD_LOAD_BUDGET_MS = "pathing.regionShardLoadBudgetMs";
    /**
     * {@code int >= 0} — the radius (in chunks) of the ring around the bot whose NavGrid must be built before
     * the bot plans a path. {@code N} ⇒ a {@code (2N+1)²} chunk ring must all be nav-built. NavGrids build
     * asynchronously over a few ticks after chunks load, and the planner reads an unbuilt cell as AIR, so
     * planning too early picks a truncated-world target (the cold-start canopy bug). The bot HOLDS (does not
     * plan) until this ring is built. Default {@code 4}: the block-tier sliding window spans
     * {@link com.orebit.mod.pathfinding.PathPlan#WINDOW} (=4) level-0 regions — each a 16-block chunk — so the
     * far window target sits up to 3 chunks ahead; 4 covers that full window plus a 1-chunk margin (and also
     * covers the gather SCAN volume, radius 3). {@code 0} disables the gate (plan immediately, old behaviour).
     */
    public static final String PATHING_NAV_READY_RADIUS_CHUNKS = "pathing.navReadyRadiusChunks";
    /**
     * {@code int >= 1} — how many consecutive ticks the bot waits for its {@link #PATHING_NAV_READY_RADIUS_CHUNKS
     * readiness ring} to build before giving up and telling the owner it can't get terrain data. A give-up
     * BACKSTOP only: the gate itself is state-based (it polls real {@link
     * com.orebit.mod.worldmodel.pathing.NavStore} residency and proceeds the instant the ring is built), so on a
     * healthy server the wait is 1-2 ticks and this timeout never fires. It exists so a genuinely un-loadable
     * area (world border, permanently missing chunk) fails cleanly instead of hanging. Default {@code 150}
     * (~7.5 s at 20 t/s).
     */
    public static final String PATHING_NAV_READY_TIMEOUT_TICKS = "pathing.navReadyTimeoutTicks";
    /**
     * {@code int} 1..16 — the half-extent (in region cells, applied at every pyramid level 0..{@link
     * com.orebit.mod.worldmodel.hpa.RegionAddress#MAX_COARSE_LEVEL}) of the PROACTIVE boxed-in seal scan
     * ({@code PathPlan.maybeProactiveBoxedIn}): at each level the goal-rooted sealed-probe floods a {@code
     * (2·R+1)³} region box centred on the goal region. {@code R=3} (default) ⇒ a 7-region box per axis,
     * catching seals from ~16 blocks (L0) up to ~7k blocks (L6, 1024-block regions). A larger radius catches
     * bigger tombs but scans a wider box at plan-entry (each per-level field is {@code (2R+1)³ · MAX_FRAGMENTS}
     * floats and the flood is bounded by the box), so it is a plan-entry cost knob, not a per-node one. Default
     * {@code 3}.
     */
    public static final String PATHING_BOXED_IN_SCAN_RADIUS = "pathing.boxedInScanRadius";

    // ---- hpa: the persisted region tier (routing fragments + resource tallies) ----------------------
    /**
     * {@code int} — how often (in server ticks) the background crash-insurance flush re-writes each dimension's
     * persisted HPA region tier ({@code <world>/orebit/<dim>/hpa.bin} + {@code res.bin}), and only when that
     * dimension changed since its last flush. This is a SAFETY NET only: the authoritative flush happens on a
     * graceful server stop regardless. {@code 0} (or negative) disables the periodic flush (stop flush still
     * runs). Default {@code 6000} (≈ 5 minutes at 20 t/s), matching vanilla autosave feel. The pass this
     * interval TRIGGERS is itself spread across ticks under {@link #HPA_PERSIST_FLUSH_BUDGET_MS} (it no longer
     * writes every dirty shard in one tick — it drains a budgeted slice each tick until the backlog clears).
     */
    public static final String HPA_PERSIST_INTERVAL_TICKS = "hpa.persistIntervalTicks";
    /**
     * {@code float > 0} — the per-level per-tick wall-clock budget (milliseconds) for the periodic
     * crash-insurance flush drain ({@link com.orebit.mod.worldmodel.persistence.RegionPersistence#tick}), the
     * persistence analog of {@link #PATHING_HPA_FLUSH_BUDGET_MS}. When the {@link #HPA_PERSIST_INTERVAL_TICKS}
     * interval fires, the dimension's dirty shards are written under this budget and the pass RESUMES on
     * subsequent ticks until the backlog drains (interval-triggered, then a budgeted resuming drain) — turning
     * the old one-tick flush spike (a whole dimension's dirty shards + both coarse files written at once,
     * measured ~1.9 s) into a steady trickle. Elapsed is checked after each shard (worst-case overshoot one
     * shard), with a hardcoded ≥1-shard-per-tick backstop so progress is guaranteed even if a single shard
     * exceeds the budget; the two coarse files are written LAST (deferred to a later tick if the budget is spent).
     * Atomicity is not required (each shard file is an independent atomic replace, and the {@code SERVER_STOPPING}
     * flush is authoritative), so spreading the writes across ticks is safe. Clamped to {@code (0, 1000]} ms.
     * Default {@code 2.0}.
     */
    public static final String HPA_PERSIST_FLUSH_BUDGET_MS = "hpa.persistFlushBudgetMs";
    /**
     * {@code boolean} — page the persisted region tier in <b>lazily</b> (Stage-2 bounded region RAM): load ONLY
     * the per-dimension coarse levels at server start ({@code RegionPersistence.loadCoarseOnly}) and stream each
     * per-shard leaf file in on demand as the planner touches it (the atomic {@code RegionShardLoader}), instead
     * of eager-loading every shard up front ({@code loadAll}). Bounds RAM to the coarse tier + the shards actually
     * visited. <b>Default {@code false}</b> for now — eager-load-all stays the shipped behaviour until the whole
     * bounded-RAM stage is in-game-verified; flip to {@code true} to opt into lazy loading. Requires a server
     * restart to change (it governs the startup load path).
     */
    public static final String HPA_LAZY_LOAD = "hpa.lazyLoad";
    /**
     * {@code int >= 0} — the Stage-2 <b>bounded region RAM</b> target: the maximum number of resident BUILT
     * level-0 cost leaves (the dominant region-tier RAM cost, ~1.6–5.8 KB each) the cold-shard evictor
     * ({@code RegionEvictor}) keeps paged in. <b>Default {@code 0} = UNBOUNDED / eviction OFF</b> — the evictor is
     * a no-op, so bounded RAM stays opt-in until the whole stage is in-game-verified. A positive value bounds RAM:
     * when the resident built-leaf count exceeds it, the evictor pages the coldest shards whose backing chunk
     * columns are <b>all currently unloaded</b> (LRU by last-touch, flushing any dirty shard first) back to disk —
     * keeping the coarse (level-6) tier resident — and the clobber-guard + {@code RegionShardLoader} page them back
     * in on demand when the planner next touches them (byte-identical round-trip). Pairs naturally with
     * {@link #HPA_LAZY_LOAD} (eviction converts an eager-loaded shard into a persisted-non-resident one either way).
     * Read once per eviction sweep off {@code onWorldTickEnd}, never on the search hot path.
     */
    public static final String HPA_RESIDENT_LEAF_CAP = "hpa.residentLeafCap";

    // ---- doors: how the bot deals with doors in its path --------------------------------------------
    /**
     * {@code boolean} — the bot may OPEN/CLOSE hand-toggleable doors (wood/copper) by right-clicking, preferring
     * that over smashing them or routing around. Rides into {@link
     * com.orebit.mod.pathfinding.blockpathfinder.BotCaps#mayToggleDoors}. <b>Default {@code true}</b> (DOORS P3):
     * the follower now operates doors for real — it opens a closed door before crossing and closes it again on a
     * hallway-corner exit — so the feature is complete and on by default, like the other movements. Set it
     * {@code false} as a kill-switch to fall back to P1 behaviour (an already-open door is walked through, a
     * closed door is mined). Iron doors are never hand-toggleable regardless of this flag.
     */
    public static final String DOORS_TOGGLE = "doors.toggle";
}
