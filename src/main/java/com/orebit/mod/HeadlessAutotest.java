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
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
                cell("orebit.autotest.start", "-3,125,-28"),
                cell("orebit.autotest.goal", "201,-28,90"),
                Integer.getInteger("orebit.autotest.budgetTicks", 24_000),
                Integer.getInteger("orebit.autotest.startDelayTicks", 0));
        events.onServerStarted(scenario::start);
        events.onWorldTickEnd(scenario::tick);
        OrebitCommon.LOGGER.info("[Orebit/autotest] armed: start={} goal={} budget={}t",
                compact(scenario.start), compact(scenario.goal), scenario.budgetTicks);
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

        Scenario(BlockPos start, BlockPos goal, int budgetTicks, int startDelayTicks) {
            this.start = start;
            this.goal = goal;
            this.budgetTicks = budgetTicks;
            this.startDelayTicks = startDelayTicks;
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
                // anyway so a rerun against a dirty run dir can never smuggle items in.
                bot.getInventory().clearContent();
                bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
                // The same internal call /bot goto makes (GotoCommand -> comeTo): COME once, then STAY. Deferred
                // by startDelayTicks so the nav grid can build first (else the tick-1 plan floods on unbuilt nav).
                if (startDelayTicks <= 0) {
                    bot.comeTo(goal);
                    goalIssued = true;
                    OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {} heading to {}",
                            compact(bot.blockPosition()), compact(goal));
                } else {
                    OrebitCommon.LOGGER.info("[Orebit/autotest] bot spawned at {}; deferring goto {} by {}t (nav settle)",
                            compact(bot.blockPosition()), compact(goal), startDelayTicks);
                }
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] scenario setup threw", t);
                finish("FAIL", "setup threw " + t.getClass().getSimpleName() + " (see log)");
            }
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

            if (!bot.isAlive()) {
                finish("FAIL", "bot died");
                return;
            }

            // Deferred goto (startDelayTicks): sit at spawn until the nav grid around the bot has built, THEN
            // issue the goal — so the traced searches run on real terrain, isolating a heuristic pathology from
            // the tick-1 unbuilt-nav flood. Skip the arrival poll entirely until the goal exists.
            if (!goalIssued) {
                if (ticks < startDelayTicks) {
                    return;
                }
                bot.comeTo(goal);
                goalIssued = true;
                OrebitCommon.LOGGER.info("[Orebit/autotest] goal issued at tick {} (after {}t settle) heading to {}",
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

        /** Write the result file (run dir, same anchor as the config seam), log the verdict, halt. */
        void finish(String result, String reason) {
            done = true;
            if (tracing) {
                BlockPathfinder.TRACE = false;
                BlockPathfinder.TRACE_SEARCH_START = null;
                BlockPathfinder.TRACE_OUT = null;
                closeTrace();
                OrebitCommon.LOGGER.info("[Orebit/autotest] wrote {} A* trace file(s) (orebit-autotest-trace-*.txt)",
                        traceSearches);
            }
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
                    kv(w, "alive", bot.isAlive());
                    kv(w, "waypoint", bot.navigator().waypointIndex());
                    kv(w, "pathSize", bot.navigator().pathSize());
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/autotest] could not write {}", file, e);
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
    }
}
