package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.ItemLookup;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.platform.ToolEnchants;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.worldmodel.pathing.NavStore;
import com.orebit.mod.worldmodel.resource.DropModel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Headless end-to-end scenario harness: on a dedicated-server run launched with
 * {@code -Dorebit.autotest=true} (the {@code :fabric:<ver>:runAutotest} run config), spawn one ally bot at a
 * fixed start cell, issue one {@code goto} to a fixed goal cell — through exactly the internal API the
 * {@code /bot goto} command calls ({@link AllyBotEntity#comeTo}) — then poll the bot's own completion
 * signals once per world tick, write a machine-readable result file, and halt the server. The scenario
 * (known worldgen seed, capability config) is supplied by the run dir the orchestration script prepares
 * ({@code server.properties} pins the seed; {@code config/orebit.properties} the caps); this class adds NO
 * behavior of its own to the bot.
 *
 * <p><b>GATHER mode</b> ({@code -Dorebit.autotest.gather=<resource>}): instead of the goto, pre-equip a tool
 * ({@code -Dorebit.autotest.tool}, default {@code diamond_pickaxe}; {@code -Dorebit.autotest.silk} adds Silk
 * Touch) into the bot's real inventory and drive {@code /bot gather <resource> [count]} through the same
 * internal API the command calls ({@link AllyBotEntity#startGather}). The poll ({@code gatherTick}) reads the
 * bot's own signals — the read-only {@link AllyBotEntity#gatherPhaseName}/{@link AllyBotEntity#gatheredCount}
 * observation seams and the terminal STAY flip — and writes a granular result (phase reached / collected /
 * quota / returned / trajectory / outcome PASS·FAIL·TIMEOUT) so the four known gather failure modes
 * (collected-but-not-returned, never-collected/wander, unreachable, timeout) are distinguishable. PASS =
 * collected≥quota AND the bot returned to its start cell within budget.
 *
 * <p><b>Inert in production.</b> {@link #register} returns immediately unless the system property is set,
 * so shipping this class in the jar costs a single property read at init. It is version-portable common
 * source (compiled by {@code chiseledCompileCommon} across the whole range): every MC surface it touches is
 * range-stable — the 3-arg {@link FakePlayerEntity} ctor (identical in all overlay flavors),
 * {@code MinecraftServer.overworld()/halt(boolean)}, {@code Inventory.clearContent()/setItem},
 * {@code Items.STONE_PICKAXE}, {@code ItemStack(ItemLike)} — plus the {@link ConfigDir} platform seam and
 * this package's own bot API.
 *
 * <p><b>Success/failure are the BOT'S own signals, not harness heuristics</b> (verify-don't-assume):
 * <ul>
 *   <li><b>PASS</b> — {@code COME} completed: {@code driveToward}'s 3-D arrival test
 *       ({@code BotNavigator.ARRIVE_DIST}/{@code ARRIVE_Y}) flipped the bot to {@link AllyBotEntity.Mode#STAY};
 *       the harness re-verifies the distance as a cross-check.</li>
 *   <li><b>FAIL fast</b> — {@link BotNavigator#navGaveUp()} (the region tier's terminal give-up),
 *       or the bot died ({@code survival.takesDamage=true} makes that a real outcome).</li>
 *   <li><b>FAIL slow</b> — an overall tick budget. This is TEST INFRASTRUCTURE bounding the harness run,
 *       not bot behavior (the bot keeps its no-arbitrary-timers discipline; a budget failure is triaged
 *       from the periodic progress lines, which show whether distance was still shrinking).</li>
 * </ul>
 *
 * <p>The synthetic OWNER is a bare {@link FakePlayerEntity} positioned at the start cell but never placed
 * into the world: {@code BotManager.spawnBotFor} only reads its level/name/UUID/position, per-tick owner
 * uses are reads, and owner chat is already throw-safe ({@code AllyBotEntity.chat} catches Throwable). The
 * BOT, by contrast, goes through the full production spawn path (placeNewPlayer join, client-loaded mark,
 * forced SURVIVAL) — the test exercises the real thing.
 */
public final class HeadlessAutotest {

    private HeadlessAutotest() {}

    /** Result file written into the server run dir (next to {@code orebit-trace.txt}'s home). */
    private static final String RESULT_FILE = "orebit-autotest-result.properties";
    /** Progress-line cadence (ticks) — 10 s at 20 TPS; cold, log-only. */
    private static final int PROGRESS_LOG_TICKS = 200;

    /**
     * Arm the harness if {@code -Dorebit.autotest} is set; otherwise do nothing (production path).
     * Called LAST in {@code OrebitCommon.init} so the {@code SERVER_STARTED} hook here runs after the
     * config load, mining-table bake, warm-up, and planner-pool installs (loader events fire in
     * registration order).
     */
    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.autotest") == null) {
            return;
        }
        Scenario scenario = new Scenario(
                // Canopy top of a 25-block-tall jungle tree in the FROZEN master world (scripts/autotest-
                // world-master/world; run via run-autotest.ps1 -MasterWorld). Feet at y=180 stand on the
                // jungle-leaf floor at y=179; the trunk (jungle_log) runs y=177..153 to dirt/stone below —
                // a real "get down from the tree" descent (fall / parkour to a lower tree / dig the trunk).
                // NOTE: seed-regen worldgen is non-deterministic for VEGETATION (see
                // DIAGNOSIS-worldgen-nondeterminism.md) — this cell is only valid against the FROZEN world,
                // never a fresh seed-regen (where the tree may not exist and the cell is mid-air).
                cell("orebit.autotest.start", "60,180,253"),
                // Inside a trial chamber ~240 blocks off (owner-confirmed in the master world).
                cell("orebit.autotest.goal", "201,-28,90"),
                Integer.getInteger("orebit.autotest.budgetTicks", 24_000),
                Integer.getInteger("orebit.autotest.startDelayTicks", 0),
                // GATHER mode (-Dorebit.autotest.gather=<resource>): drive /bot gather instead of /bot goto.
                // Null (unset) = the historical goto scenario above, byte-for-byte unchanged. When set, the
                // goal cell is ignored; the bot gathers <resource> ×count, pre-equipped with <tool> (+silk),
                // and must return to its start cell (PASS = collected>=quota AND returned within budget).
                System.getProperty("orebit.autotest.gather"),
                Integer.getInteger("orebit.autotest.count", 1),
                System.getProperty("orebit.autotest.tool", "diamond_pickaxe"),
                Boolean.getBoolean("orebit.autotest.silk"),
                // CRAFT mode (-Dorebit.autotest.craft=<result name>): drive /bot craft instead of /bot
                // goto — <result> ×count from an injected inventory (-Dorebit.autotest.give=
                // "item:count,item:count", e.g. "oak_planks:7,stick:2"). Null = not a craft run.
                System.getProperty("orebit.autotest.craft"),
                System.getProperty("orebit.autotest.give", ""),
                // FARM mode (-Dorebit.autotest.farm=true): build a deterministic wheat plot next to
                // the start (24 farmland around a water hole, 8 MATURE wheat, 2 hydrated grass till
                // candidates), give a hoe, and drive /bot farm. Deterministic: crops are placed
                // pre-grown (growth is random-tick and never waited on).
                Boolean.getBoolean("orebit.autotest.farm"),
                // FIGHT mode (-Dorebit.autotest.fight=true): raise difficulty to NORMAL, give an
                // iron sword, spawn one zombie 6 blocks off with the bot as its declared target,
                // and let the self-defense interrupt do the rest (no command issued — the bot sits
                // in STAY and the ZOMBIE closes the distance, so the scenario is independent of
                // the committed follower increment's partial-floor walking gap).
                Boolean.getBoolean("orebit.autotest.fight"));
        events.onServerStarted(scenario::start);
        events.onWorldTickEnd(scenario::tick);
        if (scenario.fightMode) {
            OrebitCommon.LOGGER.info("[Orebit/autotest] armed FIGHT: start={} budget={}t",
                    compact(scenario.start), scenario.budgetTicks);
        } else if (scenario.farmMode) {
            OrebitCommon.LOGGER.info("[Orebit/autotest] armed FARM: start={} budget={}t",
                    compact(scenario.start), scenario.budgetTicks);
        } else if (scenario.craftItem != null) {
            OrebitCommon.LOGGER.info("[Orebit/autotest] armed CRAFT: item={} count={} give='{}' start={} budget={}t",
                    scenario.craftItem, scenario.gatherQuota, scenario.giveSpec,
                    compact(scenario.start), scenario.budgetTicks);
        } else if (scenario.gatherResource != null) {
            OrebitCommon.LOGGER.info("[Orebit/autotest] armed GATHER: resource={} count={} tool={}{} start={} budget={}t",
                    scenario.gatherResource, scenario.gatherQuota, scenario.toolId,
                    scenario.silk ? "+silk" : "", compact(scenario.start), scenario.budgetTicks);
        } else {
            OrebitCommon.LOGGER.info("[Orebit/autotest] armed GOTO: start={} goal={} budget={}t",
                    compact(scenario.start), compact(scenario.goal), scenario.budgetTicks);
        }
    }

    /** Parse {@code "x,y,z"} from a system property (default {@code def}). Fail fast on garbage — a typo'd
     *  coordinate must kill the run at arm time, not send the bot somewhere silently wrong. */
    private static BlockPos cell(String property, String def) {
        String raw = System.getProperty(property, def);
        String[] parts = raw.trim().split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("[Orebit/autotest] " + property + " must be 'x,y,z', got: " + raw);
        }
        return new BlockPos(Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
    }

    private static String compact(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    /** One armed scenario: owns the synthetic owner, the bot handle, the poll state, and the result write. */
    private static final class Scenario {

        final BlockPos start;
        final BlockPos goal;
        final int budgetTicks;
        /** Ticks to let the world/nav grid settle AFTER spawn before issuing the goto (diagnostic seam): the
         *  first plan otherwise fires at tick 1 on an UNBUILT nav grid, where both tiers read the optimistic-AIR
         *  default and the block search floods through phantom air. 0 = the historical spawn-tick goto. */
        final int startDelayTicks;
        boolean goalIssued;

        // ---- GATHER mode (null gatherResource = GOTO mode; see class javadoc) ----
        /** Resource name for {@code /bot gather} (e.g. "cobblestone", "stone", "iron"); null = GOTO mode. */
        final String gatherResource;
        /** Target picked-up count (quota) for the gather run. */
        final int gatherQuota;
        /** Item id of the tool to pre-equip before gathering (e.g. "diamond_pickaxe"). */
        final String toolId;
        /** Whether to add Silk Touch to the pre-equipped tool (needed for {@code gather stone}). */
        final boolean silk;
        /** True once gathering is under way. */
        boolean gatherIssued;
        /** The resolved gather output, prepared at spawn but issued only once the vicinity is nav-built (STEP 4). */
        DropModel.Output pendingOutput;
        /** Last non-terminal gather phase observed (for the TIMEOUT/FAIL result). */
        String lastGatherPhase = "IDLE";
        /** Max distance the bot ever reached from its gather-start cell (exposes a wander). */
        double maxDistFromStart;

        // ---- CRAFT mode (null craftItem = not a craft run; mirrors GATHER's deferred-issue shape) ----
        /** Result name for {@code /bot craft} (a RecipeIndex name, e.g. "crafting_table"); null = off. */
        final String craftItem;
        /** Inventory injection spec {@code "item:count,item:count"} applied at spawn (craft mode only). */
        final String giveSpec;
        /** True once the craft has been issued (after the nav-readiness gate). */
        boolean craftIssued;
        /** Last non-terminal craft phase observed (for the TIMEOUT/FAIL result). */
        String lastCraftPhase = "IDLE";

        // ---- FARM mode (false = not a farm run; the plot is built at spawn, pre-grown) ----
        final boolean farmMode;
        /** True once the farm pass has been issued (after the nav-readiness gate). */
        boolean farmIssued;
        /** Last non-terminal farm phase observed (for the TIMEOUT/FAIL result). */
        String lastFarmPhase = "IDLE";

        // ---- FIGHT mode (false = not a fight run) ----
        final boolean fightMode;
        /** The sparring zombie (spawned targeting the bot at scenario start). */
        net.minecraft.world.entity.Mob sparringMob;
        /** Whether the self-defense interrupt was ever observed engaged. */
        boolean fighterEverEngaged;

        // ---- TRACE mode (-Dorebit.autotest.traceGoal=x,y,z): mirror /bot trace, headlessly ----
        /**
         * The {@code goalFloor} cell for a one-shot {@link AllyBotEntity#traceTo} — the EXACT entry point
         * {@code /bot trace} (TraceCommand) calls, so the dump is comparable byte-for-byte with a live
         * {@code /bot trace}. Null = not a trace run. NOTE the convention: {@code TraceCommand} passes
         * {@code player.blockPosition().below()}, i.e. the block the caller STANDS ON — so this property is
         * the FLOOR cell, not the owner's feet cell (mirroring the {@code rtrace} seam's {@code goal.below()}).
         * Distinct from {@code -Dorebit.autotest.trace}, which rolls a file per search of a full goto run.
         */
        final BlockPos traceGoal = System.getProperty("orebit.autotest.traceGoal") == null
                ? null
                : cell("orebit.autotest.traceGoal", "0,0,0");
        /** True once the one-shot trace has been issued (it is terminal — the run halts right after). */
        boolean traceIssued;

        MinecraftServer server;
        FakePlayerEntity owner;   // synthetic, never world-placed (see class javadoc)
        AllyBotEntity bot;
        int ticks;
        boolean done;
        double bestDistXZ = Double.MAX_VALUE;
        // -Dorebit.autotest.trace=true: per-search A* trace files (see rollTrace). SLOW + HUGE — diagnostic runs only.
        boolean tracing;
        BufferedWriter traceOut;  // the CURRENT per-search file (BlockPathfinder.TRACE_OUT aliases it)
        int traceSearches;

        Scenario(BlockPos start, BlockPos goal, int budgetTicks, int startDelayTicks,
                 String gatherResource, int gatherQuota, String toolId, boolean silk,
                 String craftItem, String giveSpec, boolean farmMode, boolean fightMode) {
            this.start = start;
            this.goal = goal;
            this.budgetTicks = budgetTicks;
            this.startDelayTicks = startDelayTicks;
            this.gatherResource = gatherResource;
            this.gatherQuota = Math.max(1, gatherQuota); // shared count knob (gather quota / craft target)
            this.toolId = toolId;
            this.silk = silk;
            this.craftItem = craftItem;
            this.giveSpec = giveSpec;
            this.farmMode = farmMode;
            this.fightMode = fightMode;
        }

        /**
         * SERVER_STARTED: build the synthetic owner at the start cell, run the PRODUCTION bot spawn
         * ({@code BotManager.spawnBotFor} — placeNewPlayer join, client-loaded mark, forced SURVIVAL),
         * pin the exact start position + the one-stone-pickaxe inventory, and issue the goto. Runs on the
         * server thread before the tick loop serves anything else; block reads around the start cell
         * sync-load their chunks, and the placed bot then holds player chunk tickets like any survival
         * player (ChunkMap.skipPlayer skips spectators only).
         */
        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.autotest.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            if (Boolean.getBoolean("orebit.autotest.trace")) {
                // Full A* expansion traces, one numbered file per search (rollTrace), each directly
                // analyzable offline with internal_docs/trace_analysis.py. Armed HERE (this scenario's
                // SERVER_STARTED hook runs after NavWarmup's — registration order) so the warm-up's
                // synthetic searches produce no files. Trace I/O is per-node file writes on the tick
                // thread: slow + huge, diagnostic runs only. Assumes sync pathing (the harness config
                // pins pathing.async=false) — TRACE_OUT is one shared rotating sink.
                tracing = true;
                BlockPathfinder.TRACE_SEARCH_START = this::rollTrace;
                BlockPathfinder.TRACE = true;
                OrebitCommon.LOGGER.info("[Orebit/autotest] A* tracing ON — orebit-autotest-trace-<n>.txt per search");
            }
            try {
                ServerLevel level = server.overworld();
                // Read-only worldgen-determinism probe (-Dorebit.autotest.probeOnly=true): dump the start
                // cell + a 5x5 neighborhood and HALT — no bot, no goto. Lets N fresh regenerations be diffed
                // for tree placement/height stability (the treetop-vs-mid-air question). See probeStartCell.
                if (Boolean.getBoolean("orebit.autotest.probeOnly")) {
                    probeStartCell(level);
                    finish("PROBE", "start-cell worldgen probe only (no bot spawned)");
                    return;
                }
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitAutotest:owner".getBytes(StandardCharsets.UTF_8)),
                        "Autotest"));
                owner.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);

                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("FAIL", "bot never spawned (spawnBotFor produced no bot)");
                    return;
                }
                // The exact owner-specified start cell (spawnBotFor may have snapped to a nearby safe spot).
                bot.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
                // Exactly one stone pickaxe. A fresh dedicated-server bot has empty saved data, but clear
                // anyway so a rerun against a dirty run dir can never smuggle items in. With
                // -Dorebit.autotest.barehanded=true the bot carries NOTHING — bare-handed mining is far slower,
                // which raises the region-tier mine-through cost of a ground descent (repro of the owner's
                // bare-handed pillar-to-the-sky: the empty-air highway out-prices a dig-down descent).
                bot.getInventory().clearContent();
                final boolean barehanded = Boolean.getBoolean("orebit.autotest.barehanded");

                // ---- FIGHT mode (-Dorebit.autotest.fight=true): spawn a sparring zombie ----
                // Difficulty NORMAL (the template pins peaceful, which would insta-despawn it);
                // iron sword given; the zombie spawns 6 blocks east with the bot DECLARED its
                // target (the exact signal BotFighter's threat scan reads). No command issued —
                // the interrupt preempts STAY the moment the zombie targets the bot.
                if (fightMode) {
                    server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                    bot.getInventory().setItem(0, new ItemStack(ItemLookup.byId("minecraft:iron_sword")));
                    bot.setMode(AllyBotEntity.Mode.STAY);
                    // A PROVEN-CLEAR spawn cell (air feet+head, solid floor) via the existing
                    // safe-spot helper — a fixed offset buried the zombie in the cliff face and it
                    // suffocated in ~200t before the bot could land a hit (diagnosed live).
                    BlockPos spawn = BotPositioning.findSafeSpotNear(bot, 6);
                    if (spawn == null) spawn = bot.blockPosition().offset(1, 0, 0);
                    sparringMob = com.orebit.mod.platform.MobKinds.spawnZombieAt(level,
                            spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
                    sparringMob.setTarget(bot);
                    OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {}; sparring zombie at {} targeting the bot",
                            compact(bot.blockPosition()), compact(sparringMob.blockPosition()));
                    return;
                }

                // ---- FARM mode (-Dorebit.autotest.farm=true): build the plot, drive /bot farm ----
                // Deterministic wheat plot EAST of the start on the ground plane (start.y-1):
                // a 5x5 of farmland around a central water hole, MATURE wheat on the 8 ring cells
                // around the water (pre-grown — growth is random-tick, never waited on), 2 hydrated
                // grass_block till candidates nearer the start. Two clear air rows above every cell.
                // Give: one wooden hoe (seeds come from the harvest itself — exercises SWEEPUP).
                if (farmMode) {
                    // The whole plot sits within Chebyshev 5 of the start; the harness config pins
                    // farming.workRadius=5 so the survey NEVER leaves the plot — the master world's
                    // wild hydrated grass (e.g. (68,62,-74), Chebyshev 6) would otherwise queue a
                    // till whose walk starts FROM farmland and trips the committed envelope
                    // increment's full-block footY test (fixed by the in-flight follower arc's
                    // topY-aware fromFootY/toFootY; re-widen the radius once that lands).
                    final int gx = start.getX(), gy = start.getY() - 1, gz = start.getZ();
                    for (int dx = 1; dx <= 5; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            final BlockPos base = new BlockPos(gx + dx, gy, gz + dz);
                            final boolean water = (dx == 3 && dz == 0);
                            level.setBlockAndUpdate(base, (water ? Blocks.WATER : Blocks.FARMLAND)
                                    .defaultBlockState());
                            level.setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
                            level.setBlockAndUpdate(base.above(2), Blocks.AIR.defaultBlockState());
                        }
                    }
                    // Deterministic LIGHT regardless of terrain (the plot may sit against a cliff —
                    // sky access is not assumed): glowstone on the four crop-layer corners keeps
                    // rawBrightness >= 8 at every crop (CropBlock.canSurvive pops crops below 8
                    // without sky). Placed BEFORE the wheat so no crop ever sees the dark.
                    for (int[] c : new int[][]{{1, -2}, {1, 2}, {5, -2}, {5, 2}}) {
                        level.setBlockAndUpdate(new BlockPos(gx + c[0], gy + 1, gz + c[1]),
                                Blocks.GLOWSTONE.defaultBlockState());
                    }
                    // Flag 18 = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE (the litematica-paste precedent):
                    // NO neighbor-shape updates during the build — a plain setBlockAndUpdate here
                    // cascade-POPS the already-placed wheat (each placement re-runs its neighbors'
                    // canSurvive while the glowstone light hasn't propagated yet; diagnosed live).
                    final var wheat = com.orebit.mod.farming.CropKinds.all().get(0).matureState();
                    for (int dx = 2; dx <= 4; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 3 && dz == 0) continue; // the water hole
                            level.setBlock(new BlockPos(gx + dx, gy + 1, gz + dz), wheat, 18);
                        }
                    }
                    for (int dz : new int[]{-3, 3}) { // till candidates, Chebyshev ≤4 from the water
                        final BlockPos g = new BlockPos(gx + 2, gy, gz + dz);
                        level.setBlockAndUpdate(g, Blocks.GRASS_BLOCK.defaultBlockState());
                        level.setBlockAndUpdate(g.above(), Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(g.above(2), Blocks.AIR.defaultBlockState());
                    }
                    bot.getInventory().setItem(0, new ItemStack(ItemLookup.byId("minecraft:wooden_hoe")));
                    bot.setMode(AllyBotEntity.Mode.STAY);
                    OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {}; farm plot built east; "
                            + "pass pending (startDelay {}t + nav readiness)",
                            compact(bot.blockPosition()), startDelayTicks);
                    return;
                }

                // ---- CRAFT mode (-Dorebit.autotest.craft=<result name>): drive /bot craft ----
                // Inject the -give inventory into the bot's REAL ServerPlayer inventory, then defer the
                // startCraft to the poll behind the same nav-readiness gate the goto/gather use (SEEK_TABLE
                // may need to path). The craft poll (craftTick) takes over from here.
                if (craftItem != null) {
                    int slot = 0;
                    for (String entry : giveSpec.split(",")) {
                        final String e = entry.trim();
                        if (e.isEmpty()) continue;
                        final int sep = e.lastIndexOf(':');
                        final String name = sep < 0 ? e : e.substring(0, sep);
                        final int count = sep < 0 ? 1 : Integer.parseInt(e.substring(sep + 1).trim());
                        final String id = name.indexOf(':') >= 0 ? name : "minecraft:" + name;
                        final var item = ItemLookup.byId(id);
                        if (item == null) {
                            finishCraft("FAIL", "unknown give item '" + name + "'", "FAIL");
                            return;
                        }
                        bot.getInventory().setItem(slot++, new ItemStack(item, count));
                    }
                    bot.setMode(AllyBotEntity.Mode.STAY);
                    OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {}; craft {} x{} (give '{}') "
                                    + "pending (startDelay {}t + nav readiness)",
                            compact(bot.blockPosition()), craftItem, gatherQuota, giveSpec, startDelayTicks);
                    return;
                }

                // ---- GATHER mode (-Dorebit.autotest.gather=<resource>): drive /bot gather ----
                // Pre-equip the correct/silk tool into the bot's REAL ServerPlayer inventory BEFORE the
                // command, so the gather machine's drop-goal tool gate (BotGatherer.equipForCondition) does
                // not refuse for lack of a qualifying pickaxe. Then hand off to the exact internal API the
                // /bot gather command calls (AllyBotEntity.startGather) — no goto, no rtrace (those are
                // GOTO-only). The gather poll (gatherTick) takes over from here.
                if (gatherResource != null) {
                    DropModel.Output output = DropModel.resolve(gatherResource);
                    if (output == null) {
                        finishGather("FAIL", "unknown gather resource '" + gatherResource + "'", "FAIL");
                        return;
                    }
                    if (!barehanded) {
                        ItemStack tool = new ItemStack(toolItem(toolId));
                        if (silk) {
                            ToolEnchants.applySilkTouch(level, tool);
                        }
                        bot.getInventory().setItem(0, tool);
                    }
                    // Prepare, but DON'T issue yet (STEP 4): hold the bot until its vicinity has nav-built (and
                    // any -StartDelay elapsed), then issue /bot gather from the poll — deterministic regardless
                    // of machine speed, matching warm play (a player issues gather standing in loaded terrain,
                    // not into a still-building world). Issuing at tick 1 on an unbuilt grid is exactly the
                    // cold-start truncated-scan the readiness gate exists to prevent. STAY meanwhile so the bot
                    // doesn't FOLLOW-drive during the wait. gatherTick() does the deferred issue.
                    pendingOutput = output;
                    bot.setMode(AllyBotEntity.Mode.STAY);
                    OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {}; gather {} x{} (tool={}{}) "
                                    + "pending (startDelay {}t + nav readiness)",
                            compact(bot.blockPosition()), output.name(), gatherQuota,
                            barehanded ? "none" : toolId, silk ? "+silk" : "", startDelayTicks);
                    return;
                }

                // ---- GOTO mode (unchanged): one stone pickaxe by default ----
                if (!barehanded) {
                    bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
                }
                // Region-cascade trace seam (-Dorebit.autotest.rtrace=true): run the FULL-cascade rtrace toward
                // the goal (what /bot goto's region tier evaluates for THIS scenario — e.g. the bare-handed +
                // persisted-HPA pillar) into orebit-region-trace.txt, level-tagged, then halt. No goto. Uses the
                // goal FLOOR cell (goal.below()) to match the /bot come goalFloor convention.
                if (Boolean.getBoolean("orebit.autotest.rtrace")) {
                    String path = bot.regionTraceTo(new BlockPos(goal.getX(), goal.getY() - 1, goal.getZ()));
                    OrebitCommon.LOGGER.info("[Orebit/autotest] region cascade trace written to {}", path);
                    finish("RTRACE", "region cascade trace only (no goto)");
                    return;
                }
                // The same internal call /bot goto makes (GotoCommand -> comeTo): COME once, then STAY. Always
                // issued from the poll (tick()), once startDelayTicks have elapsed AND the nav grid around the
                // start cell has built (STEP 4 residency wait) — deterministic regardless of machine speed, and
                // it never fires the tick-1 plan on an unbuilt (AIR-reading) grid.
                OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {}; goto {} pending (startDelay {}t + nav readiness)",
                        compact(bot.blockPosition()), compact(goal), startDelayTicks);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] scenario setup threw", t);
                finish("FAIL", "setup threw " + t.getClass().getSimpleName() + " (see log)");
            }
        }

        /**
         * Read-only worldgen-determinism dump (diagnostic seam, {@code -Dorebit.autotest.probeOnly=true}):
         * force-generate the start chunks via {@code getBlockState} and record what worldgen produced at the
         * start cell and a 5x5 XZ neighborhood, into {@code orebit-autotest-startprobe.txt}. NO bot spawn, NO
         * goto — so each run is just fresh worldgen + a handful of block reads. Because the harness deletes
         * the world every run and the seed is pinned, running this N times exercises N INDEPENDENT
         * regenerations under an IDENTICAL load pattern: identical {@code signature=} lines ⇒ worldgen is
         * deterministic here (any live-client variation came from a different chunk load ORDER, not RNG);
         * differing signatures ⇒ genuine same-seed same-version worldgen non-determinism. The
         * {@code topSolidY} grid shows the canopy silhouette / tree height; the exact start column shows
         * whether the start cell sits on a trunk (robust) or an overhanging neighbor-chunk leaf (fragile).
         */
        void probeStartCell(ServerLevel level) {
            int sx = start.getX();
            int sz = start.getZ();
            // Start-relative window: a few cells above the start (to see clearance) down well below it (to see
            // the trunk/canopy the bot descends through and the ground under it). Covers a treetop at ANY Y.
            final int yTop = start.getY() + 8;
            final int yBot = start.getY() - 72;
            StringBuilder sb = new StringBuilder(4096);
            sb.append("# Orebit START-CELL worldgen probe (read-only; no bot)\n");
            sb.append("start=").append(sx).append(',').append(start.getY()).append(',').append(sz).append('\n');
            sb.append("column@start (y ").append(yTop).append("..").append(yBot).append("):\n");
            for (int y = yTop; y >= yBot; y--) {
                var st = level.getBlockState(new BlockPos(sx, y, sz));
                sb.append("  y=").append(y).append(' ').append(st.isAir() ? "air" : st.toString()).append('\n');
            }
            // 5x5 topmost-solid-Y silhouette + a determinism signature folded over the WHOLE neighborhood cube.
            sb.append("topSolidY grid (rows dz=+2..-2, cols dx=-2..+2):\n");
            long sig = 1125899906842597L;
            for (int dz = 2; dz >= -2; dz--) {
                sb.append(" ");
                for (int dx = -2; dx <= 2; dx++) {
                    int topY = Integer.MIN_VALUE;
                    for (int y = yTop; y >= yBot; y--) {
                        var st = level.getBlockState(new BlockPos(sx + dx, y, sz + dz));
                        String tok = st.isAir() ? "" : st.toString();
                        sig = sig * 1099511628211L + tok.hashCode() + y * 31L + dx * 7L + dz;
                        if (topY == Integer.MIN_VALUE && !st.isAir()) {
                            topY = y;
                        }
                    }
                    sb.append(String.format(Locale.ROOT, "%6d", topY));
                }
                sb.append('\n');
            }
            sb.append("signature=").append(Long.toHexString(sig)).append('\n');
            // Full-geometry box dump (movement-pathology forensics): every NON-AIR block in a radius-3 XZ /
            // -3..+4 Y box around the start cell, one line per block — enough to reconstruct any single-move
            // takeoff/landing/corner geometry (diagonals, parkour arcs, head clearance) at a stuck spot.
            sb.append("box (dx,dy,dz -> block; r=3, dy -3..+4, non-air only):\n");
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        var st = level.getBlockState(new BlockPos(sx + dx, start.getY() + dy, sz + dz));
                        if (!st.isAir()) {
                            sb.append("  (").append(dx).append(',').append(dy).append(',').append(dz)
                                    .append(") ").append(st).append('\n');
                        }
                    }
                }
            }
            Path file = ConfigDir.serverDir(server).resolve("orebit-autotest-startprobe.txt");
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write start-cell probe {}", file, e);
            }
            OrebitCommon.LOGGER.info("[Orebit/autotest] START-CELL PROBE signature={} (orebit-autotest-startprobe.txt)",
                    Long.toHexString(sig));
        }

        /**
         * Per-world-tick poll (overworld only; cold). Reads only the bot's own state machine — mode flip
         * to STAY (= driveToward's arrival test passed), navGaveUp, liveness — plus the harness budget.
         */
        void tick(ServerLevel level) {
            if (done || bot == null || server == null || level != server.overworld()) {
                return;
            }
            ticks++;

            // TRACE mode (-Dorebit.autotest.traceGoal): one-shot mirror of /bot trace. Deferred to the poll on
            // the SAME readiness gate the goto/gather use — a trace fired at SERVER_STARTED would search an
            // UNBUILT nav grid (optimistic-AIR reads) and could never match a live /bot trace, which is always
            // issued in warm, resident terrain. Terminal: dump, then halt (no goto, no gather).
            if (traceGoal != null) {
                if (traceIssued) {
                    return;
                }
                if (ticks < startDelayTicks) {
                    return;
                }
                if (!navReadyAround(level, start)) {
                    if (ticks >= budgetTicks) {
                        finish("FAIL", "nav grid never built around start (waited " + ticks + "t)");
                    } else if (ticks % PROGRESS_LOG_TICKS == 0) {
                        OrebitCommon.LOGGER.info("[Orebit/autotest] t={} waiting for nav readiness around start {}",
                                ticks, compact(start));
                    }
                    return;
                }
                traceIssued = true;
                // The exact internal call TraceCommand makes (goalFloor = the cell the caller stands on).
                String msg = bot.traceTo(traceGoal);
                OrebitCommon.LOGGER.info("[Orebit/autotest] /bot trace mirror at tick {} (startDelay {}t + nav ready)"
                        + " startFloor={} goalFloor={}: {}", ticks, startDelayTicks,
                        compact(bot.blockPosition().below()), compact(traceGoal), msg);
                finish("TRACE", "block A* trace only (no goto): " + msg);
                return;
            }

            // FIGHT / FARM / CRAFT / GATHER modes have their own polls; GOTO's poll is below.
            if (fightMode) {
                fightTick(level);
                return;
            }
            if (farmMode) {
                farmTick(level);
                return;
            }
            if (craftItem != null) {
                craftTick(level);
                return;
            }
            if (gatherResource != null) {
                gatherTick(level);
                return;
            }

            if (!bot.isAlive()) {
                finish("FAIL", "bot died");
                return;
            }

            // Deferred goto: sit at spawn until -StartDelay has elapsed AND the nav grid around the start cell
            // has built (STEP 4), THEN issue the goal — so the plan/traced searches run on real terrain, never
            // the tick-1 unbuilt-nav flood. Skip the arrival poll entirely until the goal exists.
            if (!goalIssued) {
                if (ticks < startDelayTicks) {
                    return;
                }
                if (!navReadyAround(level, start)) {
                    if (ticks >= budgetTicks) {
                        finish("FAIL", "nav grid never built around start (waited " + ticks + "t)");
                    } else if (ticks % PROGRESS_LOG_TICKS == 0) {
                        OrebitCommon.LOGGER.info("[Orebit/autotest] t={} waiting for nav readiness around start {}",
                                ticks, compact(start));
                    }
                    return;
                }
                bot.comeTo(goal);
                goalIssued = true;
                OrebitCommon.LOGGER.info("[Orebit/autotest] goal issued at tick {} (startDelay {}t + nav ready) heading to {}",
                        ticks, startDelayTicks, compact(goal));
                return;
            }

            double dx = goal.getX() + 0.5 - bot.getX();
            double dy = goal.getY() - bot.getY();
            double dz = goal.getZ() + 0.5 - bot.getZ();
            double distXZ = Math.sqrt(dx * dx + dz * dz);
            if (distXZ < bestDistXZ) {
                bestDistXZ = distXZ;
            }

            // COME completes by flipping the bot to STAY (AllyBotEntity.tick's COME arm). Cross-check the
            // driver's own 3-D arrival tolerance so a spurious STAY can never masquerade as a pass.
            if (bot.mode() == AllyBotEntity.Mode.STAY) {
                boolean arrived = distXZ <= BotNavigator.ARRIVE_DIST && Math.abs(dy) <= BotNavigator.ARRIVE_Y;
                finish(arrived ? "PASS" : "FAIL", arrived ? "arrived" : "settled short of goal");
                return;
            }
            // The region tier's terminal verdict: no route after exhausting its blacklist repairs.
            if (bot.navigator().navGaveUp()) {
                finish("FAIL", "navigation gave up (region tier exhausted its repairs)");
                return;
            }
            if (ticks >= budgetTicks) {
                finish("FAIL", "tick budget exhausted");
                return;
            }

            if (ticks % PROGRESS_LOG_TICKS == 0) {
                OrebitCommon.LOGGER.info(
                        "[Orebit/autotest] t={} pos={} mode={} wp={}/{} distXZ={} dy={} best={}",
                        ticks, compact(bot.blockPosition()), bot.mode(),
                        bot.navigator().waypointIndex(), bot.navigator().pathSize(),
                        fmt(distXZ), fmt(dy), fmt(bestDistXZ));
            }
        }

        /**
         * {@link BlockPathfinder#TRACE_SEARCH_START} hook (tick thread, sync pathing): close the previous
         * per-search file and point {@code TRACE_OUT} at the next numbered one, with a header line in the
         * same {@code start=BlockPos{...}  goal=BlockPos{...}} shape {@code /bot trace} writes — so
         * {@code trace_analysis.py} parses each file stand-alone. An open failure logs and leaves
         * {@code TRACE_OUT} null (that search simply goes untraced; {@code trace()} null-checks).
         */
        void rollTrace(BlockPos searchStart, BlockPos searchGoal) {
            closeTrace();
            traceSearches++;
            Path file = ConfigDir.serverDir(server).resolve(
                    String.format(Locale.ROOT, "orebit-autotest-trace-%03d.txt", traceSearches));
            try {
                BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                w.write("Orebit A* trace  start=" + searchStart + "  goal=" + searchGoal
                        + "  (autotest search " + traceSearches + " at tick " + ticks + ")\n\n");
                traceOut = w;
                BlockPathfinder.TRACE_OUT = w;
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not open trace file {}", file, e);
                BlockPathfinder.TRACE_OUT = null;
            }
        }

        /** Flush + close the current per-search trace file, if any. */
        void closeTrace() {
            if (traceOut != null) {
                try { traceOut.close(); } catch (IOException ignored) { }
                traceOut = null;
            }
        }

        /** Write the GOTO result file (run dir, same anchor as the config seam), log the verdict, halt. */
        void finish(String result, String reason) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "result", result);
                kv(w, "reason", reason);
                kv(w, "ticks", ticks);
                kv(w, "budgetTicks", budgetTicks);
                kv(w, "start", start.getX() + "," + start.getY() + "," + start.getZ());
                kv(w, "goal", goal.getX() + "," + goal.getY() + "," + goal.getZ());
                if (tracing) {
                    kv(w, "traceSearches", traceSearches);
                }
                if (bot != null) {
                    double dx = goal.getX() + 0.5 - bot.getX();
                    double dy = goal.getY() - bot.getY();
                    double dz = goal.getZ() + 0.5 - bot.getZ();
                    kv(w, "x", fmt(bot.getX()));
                    kv(w, "y", fmt(bot.getY()));
                    kv(w, "z", fmt(bot.getZ()));
                    kv(w, "mode", bot.mode());
                    kv(w, "distXZ", fmt(Math.sqrt(dx * dx + dz * dz)));
                    kv(w, "distY", fmt(Math.abs(dy)));
                    kv(w, "bestDistXZ", fmt(bestDistXZ));
                    kv(w, "navGaveUp", bot.navigator().navGaveUp());
                    kv(w, "navstat_boxedIn", bot.navigator().boxedInProven());
                    kv(w, "alive", bot.isAlive());
                    kv(w, "waypoint", bot.navigator().waypointIndex());
                    kv(w, "pathSize", bot.navigator().pathSize());
                    writeNavStats(w, bot.navigator().journeyStats()); // NAVSTATS search-health aggregates
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write {}", file, e);
            }
            endRun(result, reason);
        }

        /**
         * GATHER-mode poll (find→mine→return). Reads only the bot's own signals — liveness, {@link
         * AllyBotEntity#gatherPhaseName} / {@link AllyBotEntity#gatheredCount} (read-only observation seams),
         * mode flip to STAY (the machine's terminal signal), and position — plus the harness budget. The
         * granular result distinguishes the four known gather failures (collected-but-not-returned;
         * never-collected/wander; unreachable; timeout) via {@code collected}/{@code returned}/{@code
         * distanceFromStart}/{@code phaseReached}.
         */
        void gatherTick(ServerLevel level) {
            if (!bot.isAlive()) {
                finishGather("FAIL", "bot died", "FAIL");
                return;
            }
            // Deferred gather issue (STEP 4): honor -StartDelay (previously a NO-OP in gather mode — only goto
            // read startDelayTicks) AND wait for the nav grid around the start cell to build before issuing, so
            // the SCAN commits over real terrain instead of the bot's lone already-built chunk (the cold-start
            // Y=80 canopy target). Deterministic regardless of machine speed. Runs BEFORE the terminal-STAY
            // check below (the bot sits in STAY while it waits, which must not be read as "finished").
            if (!gatherIssued) {
                if (ticks < startDelayTicks) {
                    return;
                }
                if (!navReadyAround(level, start)) {
                    if (ticks >= budgetTicks) {
                        finishGather("FAIL", "nav grid never built around start (waited " + ticks + "t)", "TIMEOUT");
                    } else if (ticks % PROGRESS_LOG_TICKS == 0) {
                        OrebitCommon.LOGGER.info("[Orebit/autotest] t={} waiting for nav readiness around start {}",
                                ticks, compact(start));
                    }
                    return;
                }
                bot.startGather(pendingOutput, gatherQuota);
                gatherIssued = true;
                OrebitCommon.LOGGER.info("[Orebit/autotest] gather issued at tick {} (startDelay {}t + nav ready): {} x{}",
                        ticks, startDelayTicks, pendingOutput.name(), gatherQuota);
                return;
            }
            final double dStart = dist3(start, bot.getX(), bot.getY(), bot.getZ());
            if (dStart > maxDistFromStart) {
                maxDistFromStart = dStart;
            }
            final String phase = bot.gatherPhaseName();
            if (!"IDLE".equals(phase)) {
                lastGatherPhase = phase;
            }
            final int collected = bot.gatheredCount();

            // The gather machine ends by flipping to STAY (RETURN arrived, RETURN unreachable, nothing found,
            // or a tool refusal). Diagnose which of the four failure modes (or PASS) it is.
            if (bot.mode() == AllyBotEntity.Mode.STAY) {
                final boolean quotaMet = collected >= gatherQuota;
                final boolean returned = dStart <= RETURN_TOL;
                if (quotaMet && returned) {
                    finishGather("PASS", "gathered " + collected + "/" + gatherQuota + " and returned", "PASS");
                } else if (quotaMet) {
                    finishGather("FAIL", "collected " + collected + "/" + gatherQuota
                            + " but did not return (dist=" + fmt(dStart) + ")", "FAIL");
                } else if (collected == 0) {
                    finishGather("FAIL", "never collected any " + gatherResource
                            + " (ended in phase " + lastGatherPhase + ", maxDist=" + fmt(maxDistFromStart) + ")", "FAIL");
                } else {
                    finishGather("FAIL", "collected only " + collected + "/" + gatherQuota
                            + " (ended in phase " + lastGatherPhase + ")", "FAIL");
                }
                return;
            }
            if (ticks >= budgetTicks) {
                finishGather("FAIL", "tick budget exhausted (phase " + lastGatherPhase
                        + ", collected " + collected + "/" + gatherQuota + ", maxDist=" + fmt(maxDistFromStart) + ")",
                        "TIMEOUT");
                return;
            }
            if (ticks % PROGRESS_LOG_TICKS == 0) {
                OrebitCommon.LOGGER.info(
                        "[Orebit/autotest] t={} pos={} phase={} collected={}/{} distFromStart={} maxDist={}",
                        ticks, compact(bot.blockPosition()), phase, collected, gatherQuota,
                        fmt(dStart), fmt(maxDistFromStart));
            }
        }

        /**
         * CRAFT-mode poll: defer {@code startCraft} behind the shared nav-readiness gate, then read only
         * the bot's own signals — {@link AllyBotEntity#craftPhaseName}/{@link AllyBotEntity#craftedCount}
         * (read-only observation seams) and the terminal mode flip to STAY — plus the harness budget.
         * PASS = crafted ≥ count, cross-checked against the physical inventory ({@code countCraftItems}).
         */
        void craftTick(ServerLevel level) {
            if (!bot.isAlive()) {
                finishCraft("FAIL", "bot died", "FAIL");
                return;
            }
            if (!craftIssued) {
                if (ticks < startDelayTicks) {
                    return;
                }
                if (!navReadyAround(level, start)) {
                    if (ticks >= budgetTicks) {
                        finishCraft("FAIL", "nav grid never built around start (waited " + ticks + "t)", "TIMEOUT");
                    } else if (ticks % PROGRESS_LOG_TICKS == 0) {
                        OrebitCommon.LOGGER.info("[Orebit/autotest] t={} waiting for nav readiness around start {}",
                                ticks, compact(start));
                    }
                    return;
                }
                bot.startCraft(craftItem, gatherQuota);
                craftIssued = true;
                OrebitCommon.LOGGER.info("[Orebit/autotest] craft issued at tick {} (startDelay {}t + nav ready): {} x{}",
                        ticks, startDelayTicks, craftItem, gatherQuota);
                return;
            }
            final String phase = bot.craftPhaseName();
            if (!"IDLE".equals(phase)) {
                lastCraftPhase = phase;
            }
            final int crafted = bot.craftedCount();
            // The craft machine ends by flipping to STAY (quota met, missing ingredients, table trouble).
            if (bot.mode() == AllyBotEntity.Mode.STAY) {
                final int inInv = countCraftItems();
                if (crafted >= gatherQuota && inInv >= gatherQuota) {
                    finishCraft("PASS", "crafted " + crafted + "/" + gatherQuota + " (inventory holds " + inInv + ")", "PASS");
                } else if (crafted >= gatherQuota) {
                    finishCraft("FAIL", "machine reports " + crafted + "/" + gatherQuota
                            + " but inventory holds only " + inInv, "FAIL");
                } else {
                    finishCraft("FAIL", "crafted only " + crafted + "/" + gatherQuota
                            + " (ended in phase " + lastCraftPhase + ")", "FAIL");
                }
                return;
            }
            if (ticks >= budgetTicks) {
                finishCraft("FAIL", "tick budget exhausted (phase " + lastCraftPhase
                        + ", crafted " + crafted + "/" + gatherQuota + ")", "TIMEOUT");
                return;
            }
            if (ticks % PROGRESS_LOG_TICKS == 0) {
                OrebitCommon.LOGGER.info("[Orebit/autotest] t={} pos={} phase={} crafted={}/{}",
                        ticks, compact(bot.blockPosition()), phase, crafted, gatherQuota);
            }
        }

        /**
         * FIGHT-mode poll: pure observation — the self-defense interrupt engages on its own the
         * moment the zombie's declared target is the bot. PASS = the zombie is gone (killed),
         * the bot is alive, and the interrupt actually engaged with ≥1 landed strike (so a
         * sunburnt zombie can't fake a pass); FAIL = bot died; TIMEOUT = budget.
         */
        void fightTick(ServerLevel level) {
            if (!bot.isAlive()) {
                finishFight("FAIL", "bot died", "FAIL");
                return;
            }
            if (bot.fighterEngaged()) {
                fighterEverEngaged = true;
            }
            if (sparringMob == null || sparringMob.isRemoved() || !sparringMob.isAlive()) {
                final int strikes = bot.combatStrikes();
                if (fighterEverEngaged && strikes >= 1) {
                    finishFight("PASS", "zombie down after " + strikes + " strikes", "PASS");
                } else {
                    finishFight("FAIL", "zombie gone but the bot never fought (engaged="
                            + fighterEverEngaged + ", strikes=" + strikes + ")", "FAIL");
                }
                return;
            }
            if (ticks >= budgetTicks) {
                finishFight("FAIL", "tick budget exhausted (engaged=" + fighterEverEngaged
                        + ", strikes=" + bot.combatStrikes() + ")", "TIMEOUT");
                return;
            }
            if (ticks % PROGRESS_LOG_TICKS == 0) {
                OrebitCommon.LOGGER.info(
                        "[Orebit/autotest] t={} pos={} engaged={} strikes={} zombieHp={}",
                        ticks, compact(bot.blockPosition()), bot.fighterEngaged(),
                        bot.combatStrikes(), fmt(sparringMob.getHealth()));
            }
        }

        /** Write the FIGHT result file, halt. */
        void finishFight(String result, String reason, String outcome) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "result", result);
                kv(w, "outcome", outcome);
                kv(w, "reason", reason);
                kv(w, "mode", "fight");
                kv(w, "ticks", ticks);
                kv(w, "budgetTicks", budgetTicks);
                kv(w, "start", start.getX() + "," + start.getY() + "," + start.getZ());
                if (bot != null) {
                    kv(w, "engaged", fighterEverEngaged);
                    kv(w, "strikes", bot.combatStrikes());
                    kv(w, "botHp", fmt(bot.getHealth()));
                    kv(w, "zombieAlive", sparringMob != null && sparringMob.isAlive());
                    kv(w, "alive", bot.isAlive());
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write {}", file, e);
            }
            endRun(result, reason);
        }

        /**
         * FARM-mode poll: defer {@code startFarm} behind the shared nav-readiness gate, then read
         * only the bot's own signals — the {@link AllyBotEntity#farmPhaseName}/harvest/plant/till
         * observation seams and the terminal STAY flip. The plot is deterministic (8 mature wheat,
         * 2 hydrated grass), so PASS = harvested 8, tilled 2, planted ≥ 1 (replant is funded by
         * the harvest's own seed drops — binomial, ≥1 across 8 crops with p≈1).
         */
        void farmTick(ServerLevel level) {
            if (!bot.isAlive()) {
                finishFarm("FAIL", "bot died", "FAIL");
                return;
            }
            if (!farmIssued) {
                if (ticks < startDelayTicks) {
                    return;
                }
                if (!navReadyAround(level, start)) {
                    if (ticks >= budgetTicks) {
                        finishFarm("FAIL", "nav grid never built around start (waited " + ticks + "t)", "TIMEOUT");
                    } else if (ticks % PROGRESS_LOG_TICKS == 0) {
                        OrebitCommon.LOGGER.info("[Orebit/autotest] t={} waiting for nav readiness around start {}",
                                ticks, compact(start));
                    }
                    return;
                }
                bot.startFarm();
                farmIssued = true;
                OrebitCommon.LOGGER.info("[Orebit/autotest] farm pass issued at tick {} (startDelay {}t + nav ready)",
                        ticks, startDelayTicks);
                return;
            }
            final String phase = bot.farmPhaseName();
            if (!"IDLE".equals(phase)) {
                lastFarmPhase = phase;
            }
            if (bot.mode() == AllyBotEntity.Mode.STAY) {
                final int h = bot.farmHarvestedCount();
                final int p = bot.farmPlantedCount();
                final int t = bot.farmTilledCount();
                if (h == 8 && t == 2 && p >= 1) {
                    finishFarm("PASS", "harvested " + h + ", planted " + p + ", tilled " + t, "PASS");
                } else {
                    finishFarm("FAIL", "harvested " + h + "/8, planted " + p + " (want >=1), tilled "
                            + t + "/2 (ended in phase " + lastFarmPhase + ")", "FAIL");
                }
                return;
            }
            if (ticks >= budgetTicks) {
                finishFarm("FAIL", "tick budget exhausted (phase " + lastFarmPhase
                        + ", harvested " + bot.farmHarvestedCount() + ")", "TIMEOUT");
                return;
            }
            if (ticks % PROGRESS_LOG_TICKS == 0) {
                OrebitCommon.LOGGER.info(
                        "[Orebit/autotest] t={} pos={} phase={} harvested={} planted={} tilled={}",
                        ticks, compact(bot.blockPosition()), phase, bot.farmHarvestedCount(),
                        bot.farmPlantedCount(), bot.farmTilledCount());
            }
        }

        /** Write the FARM result file (granular: phase / harvested / planted / tilled / yield), halt. */
        void finishFarm(String result, String reason, String outcome) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "result", result);
                kv(w, "outcome", outcome);
                kv(w, "reason", reason);
                kv(w, "mode", "farm");
                kv(w, "ticks", ticks);
                kv(w, "budgetTicks", budgetTicks);
                kv(w, "start", start.getX() + "," + start.getY() + "," + start.getZ());
                if (bot != null) {
                    kv(w, "phaseReached", "PASS".equals(result) ? "DONE" : lastFarmPhase);
                    kv(w, "harvested", bot.farmHarvestedCount());
                    kv(w, "planted", bot.farmPlantedCount());
                    kv(w, "tilled", bot.farmTilledCount());
                    kv(w, "wheatInInventory", countItemsById("minecraft:wheat"));
                    kv(w, "seedsInInventory", countItemsById("minecraft:wheat_seeds"));
                    kv(w, "mode_bot", bot.mode());
                    kv(w, "navGaveUp", bot.navigator().navGaveUp());
                    kv(w, "alive", bot.isAlive());
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write {}", file, e);
            }
            endRun(result, reason);
        }

        /** Physical count of item {@code id} in the bot's real inventory (result-file forensics). */
        int countItemsById(String id) {
            if (bot == null) return 0;
            var inv = bot.getInventory();
            int total = 0;
            for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && id.equals(ItemLookup.idOf(s.getItem()))) {
                    total += s.getCount();
                }
            }
            return total;
        }

        /** Write the CRAFT result file (granular: phase / crafted / target / physical inventory), halt. */
        void finishCraft(String result, String reason, String outcome) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "result", result);
                kv(w, "outcome", outcome);
                kv(w, "reason", reason);
                kv(w, "mode", "craft");
                kv(w, "item", craftItem);
                kv(w, "target", gatherQuota);
                kv(w, "give", giveSpec);
                kv(w, "ticks", ticks);
                kv(w, "budgetTicks", budgetTicks);
                kv(w, "start", start.getX() + "," + start.getY() + "," + start.getZ());
                if (bot != null) {
                    kv(w, "phaseReached", "PASS".equals(result) ? "DONE" : lastCraftPhase);
                    kv(w, "crafted", bot.craftedCount());
                    kv(w, "inventoryCount", countCraftItems());
                    kv(w, "finalX", fmt(bot.getX()));
                    kv(w, "finalY", fmt(bot.getY()));
                    kv(w, "finalZ", fmt(bot.getZ()));
                    kv(w, "mode_bot", bot.mode());
                    kv(w, "navGaveUp", bot.navigator().navGaveUp());
                    kv(w, "alive", bot.isAlive());
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write {}", file, e);
            }
            endRun(result, reason);
        }

        /** Physical count of the craft-target item in the bot's real inventory (cross-checks craftedCount). */
        int countCraftItems() {
            if (bot == null || craftItem == null) return 0;
            final String wantedId = craftItem.indexOf(':') >= 0 ? craftItem : "minecraft:" + craftItem;
            var inv = bot.getInventory();
            int total = 0;
            for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && wantedId.equals(ItemLookup.idOf(s.getItem()))) {
                    total += s.getCount();
                }
            }
            return total;
        }

        /** Write the GATHER result file (granular: phase / collected / quota / returned / trajectory), halt. */
        void finishGather(String result, String reason, String outcome) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "result", result);       // PASS/FAIL — the orchestrator's exit-code contract
                kv(w, "outcome", outcome);     // PASS/FAIL/TIMEOUT — granular
                kv(w, "reason", reason);
                kv(w, "mode", "gather");
                kv(w, "resource", gatherResource);
                kv(w, "quota", gatherQuota);
                kv(w, "ticks", ticks);
                kv(w, "ticksUsed", ticks);
                kv(w, "budgetTicks", budgetTicks);
                kv(w, "start", start.getX() + "," + start.getY() + "," + start.getZ());
                if (bot != null) {
                    final double dStart = dist3(start, bot.getX(), bot.getY(), bot.getZ());
                    final int collected = bot.gatheredCount();
                    kv(w, "phaseReached", "PASS".equals(result) ? "DONE" : lastGatherPhase);
                    kv(w, "collected", collected);
                    kv(w, "inventoryCount", countInInventory());
                    kv(w, "returned", dStart <= RETURN_TOL);
                    kv(w, "finalX", fmt(bot.getX()));
                    kv(w, "finalY", fmt(bot.getY()));
                    kv(w, "finalZ", fmt(bot.getZ()));
                    kv(w, "distanceFromStart", fmt(dStart));
                    kv(w, "maxDistFromStart", fmt(maxDistFromStart));
                    kv(w, "mode_bot", bot.mode());
                    kv(w, "navGaveUp", bot.navigator().navGaveUp());
                    kv(w, "navstat_boxedIn", bot.navigator().boxedInProven());
                    kv(w, "alive", bot.isAlive());
                    writeNavStats(w, bot.navigator().journeyStats()); // NAVSTATS search-health aggregates
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write {}", file, e);
            }
            endRun(result, reason);
        }

        /**
         * Write the per-journey NAVSTATS search-health aggregates as {@code navstat_*} result keys — the
         * search-health oracle: floods, boundary thrash, and roundabout routes are visible independent of the
         * PASS/FAIL verdict. Reads the live journey accumulator (which retains the last journey's totals until
         * a new goal is set; the run halts before that), so it captures the run's actual search behaviour.
         */
        private static void writeNavStats(BufferedWriter w, NavJourneyStats s) throws IOException {
            kv(w, "navstat_outcome", s.outcome());
            kv(w, "navstat_searches", s.searchCount());
            kv(w, "navstat_expansionsTotal", s.totalExpansions());
            kv(w, "navstat_expansionsMax", s.maxExpansions());
            kv(w, "navstat_partial", s.partialCount());
            kv(w, "navstat_capHit", s.capHitCount());
            kv(w, "navstat_flood", s.floodCount());
            kv(w, "navstat_replans", s.replanCount());
            kv(w, "navstat_repairs", s.repairCount());
            kv(w, "navstat_crossingsInvalidated", s.crossingsInvalidated());
            kv(w, "navstat_boundaryRecrossings", s.boundaryRecrossings());
            kv(w, "navstat_distanceTraveled", fmt(s.distanceTraveled()));
            kv(w, "navstat_straightLineDistance", fmt(s.straightLineDistance()));
            kv(w, "navstat_routeEfficiency", fmt(s.routeEfficiency()));
        }

        /**
         * Count items already IN the bot's real inventory that the requested output would count toward its
         * quota (a physical cross-check of {@link AllyBotEntity#gatheredCount}, which is the machine's
         * accrual). Version-portable: the {@code getContainerSize()/getItem()} container verbs + the {@link
         * ItemLookup#idOf} id seam + {@link DropModel.Output#counts}.
         */
        int countInInventory() {
            if (bot == null || gatherResource == null) return 0;
            DropModel.Output output = DropModel.resolve(gatherResource);
            if (output == null) return 0;
            var inv = bot.getInventory();
            int total = 0;
            for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && output.counts(ItemLookup.idOf(s.getItem()))) {
                    total += s.getCount();
                }
            }
            return total;
        }

        /** Shared teardown: close any A* trace, log the verdict, and halt+exit (see the long note below). */
        void endRun(String result, String reason) {
            if (tracing) {
                BlockPathfinder.TRACE = false;
                BlockPathfinder.TRACE_SEARCH_START = null;
                BlockPathfinder.TRACE_OUT = null;
                closeTrace();
                OrebitCommon.LOGGER.info("[Orebit/autotest] wrote {} A* trace file(s) (orebit-autotest-trace-*.txt)",
                        traceSearches);
            }
            OrebitCommon.LOGGER.info("[Orebit/autotest] {} ({}) after {} ticks — halting server",
                    result, reason, ticks);
            // halt(false) stops the tick loop; the server thread then runs the full vanilla shutdown
            // (world + player save). But the PROCESS won't exit on its own in a dev run: vanilla
            // DedicatedServer.onServerExit has no System.exit (javap-verified on 1.21.11) — a production
            // server dies because every remaining thread is daemon, while the dev classpath carries
            // non-daemon executor pools (two 24-thread `pool-N` executors in the 1.21.11 Fabric dev run;
            // jstack-verified) that park forever and pin the JVM, hanging the gradle task. So: join the
            // completed shutdown from a DAEMON thread (halt(true) re-flags running=false — idempotent —
            // and joins the server thread, i.e. AFTER the save finished), then System.exit(0). Exit code
            // is 0 for any COMPLETED run, pass or fail — the orchestration script asserts on the result
            // file, and a missing file (crash before finish) is its own distinct outcome.
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true); // join the server thread: vanilla shutdown (incl. world save) is done
                System.exit(0);
            }, "orebit-autotest-exit");
            exiter.setDaemon(true);
            exiter.start();
        }

        private static void kv(BufferedWriter w, String key, Object value) throws IOException {
            w.write(key);
            w.write('=');
            w.write(String.valueOf(value));
            w.write('\n');
        }

        private static String fmt(double v) {
            return String.format(Locale.ROOT, "%.2f", v);
        }

        /** 3-D distance from block cell {@code p}'s centre to the point {@code (x,y,z)}. */
        private static double dist3(BlockPos p, double x, double y, double z) {
            double dx = p.getX() + 0.5 - x, dy = p.getY() - y, dz = p.getZ() + 0.5 - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /**
         * Map a tool id to a range-stable {@link Items} constant (this class is common source compiled across
         * the whole 1.17→26.x range, so it must NOT reach into a version-fragile registry lookup by
         * {@code ResourceLocation}). Covers the pickaxe tiers plus the common wood tools; an unknown id logs a
         * warning and falls back to the diamond pickaxe so a typo never silently disarms the bot.
         */
        private static Item toolItem(String id) {
            String key = id == null ? "" : id.toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
            return switch (key) {
                case "wooden_pickaxe", "wood_pickaxe" -> Items.WOODEN_PICKAXE;
                case "stone_pickaxe" -> Items.STONE_PICKAXE;
                case "iron_pickaxe" -> Items.IRON_PICKAXE;
                case "golden_pickaxe", "gold_pickaxe" -> Items.GOLDEN_PICKAXE;
                case "diamond_pickaxe" -> Items.DIAMOND_PICKAXE;
                case "netherite_pickaxe" -> Items.NETHERITE_PICKAXE;
                case "iron_axe" -> Items.IRON_AXE;
                case "diamond_axe" -> Items.DIAMOND_AXE;
                case "netherite_axe" -> Items.NETHERITE_AXE;
                case "iron_shovel" -> Items.IRON_SHOVEL;
                case "diamond_shovel" -> Items.DIAMOND_SHOVEL;
                case "shears" -> Items.SHEARS;
                default -> {
                    OrebitCommon.LOGGER.warn("[Orebit/autotest] unknown -Tool '{}' — defaulting to diamond_pickaxe", id);
                    yield Items.DIAMOND_PICKAXE;
                }
            };
        }

        /**
         * Whether the NavGrid ring around {@code cell} (radius {@code pathing.navReadyRadiusChunks}) has fully
         * built (STEP 4) — the same residency signal the bot's own readiness gate polls, so the harness issues
         * its command exactly when a warm player standing in loaded terrain could. Cold — one ring scan per
         * waiting tick.
         */
        boolean navReadyAround(ServerLevel level, BlockPos cell) {
            final int radius = ConfigLoader.config().navReadyRadiusChunks();
            return NavStore.ringBuilt(level, cell.getX() >> 4, cell.getZ() >> 4, radius);
        }

        /** Return tolerance (blocks) — the bot is "back" when within this of its gather-start cell. */
        private static final double RETURN_TOL = 2.5;
    }
}
