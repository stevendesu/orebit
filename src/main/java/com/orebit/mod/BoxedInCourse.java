package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Headless BOXED-IN diagnostic harness (a sibling of {@link HeadlessAutotest} / {@link ParkourCourse},
 * armed by its own {@code -Dorebit.boxedin} flag). It proves the multi-level PROACTIVE boxed-in scan
 * ({@link com.orebit.mod.pathfinding.PathPlan#maybeProactiveBoxedIn} &rarr;
 * {@code RegionPathfinder.isSealedWithin}) gives up HONESTLY on a goal sealed inside a solid shell, and
 * still reaches an ordinary open goal — driven end-to-end through the exact internal API {@code /bot come}
 * calls ({@link AllyBotEntity#comeTo}), on a superflat world with programmatic block placement.
 *
 * <p><b>Two sequential scenarios on one superflat run.</b>
 * <ul>
 *   <li><b>tomb</b> — a bedrock-lined 3&times;3&times;3 air pocket carved into the centre of a FULLY SOLID far
 *       chunk cube; the goal is the standable interior-centre floor cell. PASS = the bot gives up
 *       ({@link BotNavigator#navGaveUp}) AND the give-up is a proven boxed-in verdict
 *       ({@link BotNavigator#boxedInProven}), i.e. it never wandered.
 *       <p><b>Caps caveat (verified against the region tier, NOT an assumption).</b> The region tier is
 *       unbreakable-BLIND: {@code FragmentBuilder} saturates bedrock's hardness sentinel into the same nibble as
 *       breakable obsidian, and {@code RegionPathfinder.expandNode} emits a finite-cost dig-through edge for a
 *       break-capable bot — so a bot with {@code mining.canMine=true} "digs out" of ANY tomb (bedrock or not)
 *       and NO seal ever closes for it (the residual deferred to #6, {@code DESIGN-boxed-in-reachability §14}).
 *       The proactive seal is therefore only provable for a NO-BREAK bot ({@code mining.canMine=false}), which
 *       has no dig edges so any solid wall is a hard seal. This harness runs no-break for that reason. Also, the
 *       flood's goal fragment is seeded by nearest-centroid to the raw solid {@code goalFloor} cell, so the
 *       pocket must be its region's ONLY non-solid fragment (hence the solid-filled cube) or the seed resolves
 *       to an exterior fragment and never seals.</li>
 *   <li><b>open</b> — an ordinary stone platform a short walk away; the goal is a cell on it, reachable over
 *       open ground. PASS = the bot arrives (mode flips to {@link AllyBotEntity.Mode#STAY} within the driver's
 *       3-D arrival tolerance).</li>
 * </ul>
 * Overall {@code result=PASS} iff both scenarios pass. Both goals sit near spawn so their chunks load and
 * nav-build (Gate&nbsp;1 of the proactive scan: the goal's L0 region must be BUILT), and each leg waits on the
 * same {@code navReadyRadiusChunks} residency gate {@link HeadlessAutotest} uses before issuing its goto.
 *
 * <p><b>Inert in production</b> &mdash; {@link #register} returns immediately unless {@code -Dorebit.boxedin}
 * is set. Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class BoxedInCourse {

    private BoxedInCourse() {}

    private static final String RESULT_FILE = "orebit-boxedin-result.properties";

    /** The superflat surface: the DEFAULT flat preset is bedrock(-64)/dirt(-63,-62)/grass(-61), so the top
     *  solid block is Y {@code = -61} and feet stand at {@code -60}. The course is built directly ON this
     *  effectively-INFINITE walkable plane — NOT floated. A floating platform is itself a finite sealed island
     *  (its whole surface floods CLOSED within the boxed-in box, so even an open goal on it reads "sealed" — a
     *  false positive proven empirically). The infinite ground plane makes the sealed tomb the ONLY finite
     *  caps-legal component, which is exactly what the region tier's seal proof keys on. */
    private static final int GROUND = -61;
    private static final int FEET = GROUND + 1; // -60

    /** Bot spawn column (open ground, far from the sealed pocket's chunk). */
    private static final int START_X = 0;
    private static final int START_Z = 0;

    /** Open-goal column (a short walk from the start over open ground; a stone pad marks it). */
    private static final int OPEN_X = 0;
    private static final int OPEN_Z = 12;

    /** The sealed pocket's interior-centre column + the Y its feet stand at. Placed at the CENTRE of a FAR chunk
     *  (chunk (4,4) = world x/z 64..79) whose entire level-0 region cube is filled SOLID except the pocket, so
     *  the goal's region has EXACTLY ONE non-solid fragment — the pocket. That matters because the region tier
     *  seeds the seal-flood's goal fragment by {@code nearestFragment} (nearest CENTROID to the raw solid
     *  goalFloor cell, no floor→feet conversion — RegionPathfinder.costToGoalField): if the goal region also held
     *  exterior air, the centroid could resolve to the EXTERIOR fragment and the flood would escape (never seal —
     *  proven empirically). Isolating the pocket in an otherwise-solid region cube mirrors
     *  {@code BoxedInReachabilityTest.seedInteriorPocket} (a pocket fully interior to a solid leaf). */
    private static final int TOMB_CX = 72;   // chunk (4,4) centre column
    private static final int TOMB_CZ = 72;
    private static final int TOMB_FLOOR = -57;           // bedrock floor of the pocket; feet stand at TOMB_FLOOR+1
    private static final int TOMB_FEET = TOMB_FLOOR + 1; // -56

    /** The SOLID isolating cube = exactly the pocket's level-0 region cube (chunk (4,4), region-Y 0 = world
     *  y −64..−49), filled solid stone except the carved pocket, so the pocket is its region's only fragment. */
    private static final int SOLID_X0 = 64, SOLID_X1 = 79;
    private static final int SOLID_Z0 = 64, SOLID_Z1 = 79;
    private static final int SOLID_Y0 = -64, SOLID_Y1 = -49;

    /** Ticks to let the whole starting area gen + nav-build before the first goto. */
    private static final int WARMUP_TICKS = 120;
    /** Ticks to let the local nav grid settle after re-seating the bot for a subsequent leg. */
    private static final int SETTLE_TICKS = 40;
    /** Per-leg attempt budget (ticks) — the tomb should give up fast; the open walk is ~12 blocks. */
    private static final int ATTEMPT_BUDGET = 800;
    /** Overall fail-safe: if the whole run has not finished by here, write a FAIL and halt (never hang). */
    private static final int OVERALL_BUDGET = 6000;
    /** If nav gives up within this many attempt ticks on the OPEN leg, treat it as nav-not-yet-built and
     *  re-issue (up to {@link #MAX_NAV_RETRY}) rather than failing — harness robustness, not bot behaviour. */
    private static final int NAV_RETRY_WINDOW = 40;
    private static final int MAX_NAV_RETRY = 5;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.boxedin") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/boxedin] armed: {} scenarios", course.legs.length);
    }

    /** One drive leg: a goal + the pass predicate discriminator (sealed-give-up vs open-arrival). */
    private enum Leg {
        /** Sealed bedrock tomb: PASS = navGaveUp AND boxedInProven. */
        TOMB,
        /** Open stone platform: PASS = arrived. */
        OPEN
    }

    private static final class Course {
        final Leg[] legs = { Leg.TOMB, Leg.OPEN };

        MinecraftServer server;
        ServerLevel level;
        FakePlayerEntity owner;
        AllyBotEntity bot;

        BlockPos goalA;   // tomb interior-centre feet cell (sealed)
        BlockPos goalB;   // open platform feet cell

        int index = -1;
        boolean settling;
        int settleTicks;
        int attemptTicks;
        int navRetries;
        int totalTicks;
        boolean overallDone;

        // Per-scenario recorded outcomes.
        String tombResult = "n/a", tombReason = "not reached";
        boolean tombBoxedIn, tombGaveUp;
        String openResult = "n/a", openReason = "not reached";

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.boxedin.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                this.goalA = new BlockPos(TOMB_CX, TOMB_FEET, TOMB_CZ);
                this.goalB = new BlockPos(OPEN_X, FEET, OPEN_Z);

                buildWorld();

                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitBoxedIn:owner".getBytes(StandardCharsets.UTF_8)),
                        "BoxedIn"));
                owner.setPos(START_X + 0.5, FEET, START_Z + 0.5);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                // A diamond pickaxe in inventory (per the harness spec) — but the bot runs NO-BREAK by config
                // (mining.canMine=false), which is what actually gates the region tier's seal-flood. A
                // break-capable bot can never seal a tomb (region tier is unbreakable-blind; see buildWorld/class
                // javadoc), so the caps, not the tool, decide.
                bot.getInventory().clearContent();
                bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));

                OrebitCommon.LOGGER.info("[Orebit/boxedin] ready; tomb goal={} open goal={}",
                        compact(goalA), compact(goalB));
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/boxedin] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        /** Build the (static) course once: an open stone pad marking the open goal (on the natural superflat
         *  plane) + a FAR solid-filled chunk cube with a bedrock-lined sealed pocket carved in its centre. */
        void buildWorld() {
            // (1) A 3x3 stone pad at the open goal — a definite, nav-built "open stone platform" cell, flush with
            //     the surrounding grass (same GROUND Y), reachable over open ground. Purely a marker; the natural
            //     plane already extends everywhere around it (so its component is NOT a finite sealed island).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    setBlock(OPEN_X + dx, GROUND, OPEN_Z + dz, STONE);
                }
            }
            // (2) Fill the pocket's entire level-0 region cube (chunk (4,4), world y −64..−49) SOLID stone, so the
            //     region has exactly ONE non-solid fragment once the pocket is carved (the nearestFragment goal
            //     seed then resolves to the interior, not an exterior air fragment — see the TOMB_CX javadoc).
            for (int y = SOLID_Y0; y <= SOLID_Y1; y++) {
                for (int x = SOLID_X0; x <= SOLID_X1; x++) {
                    for (int z = SOLID_Z0; z <= SOLID_Z1; z++) {
                        setBlock(x, y, z, STONE);
                    }
                }
            }
            // (3) Carve the sealed pocket in the cube's centre: a 5x5x5 BEDROCK shell (TOMB_FLOOR..TOMB_FLOOR+4,
            //     +/-2 on X and Z) with a 3x3x3 AIR interior (feet stand at TOMB_FEET on the bedrock floor).
            //     Fully interior to the chunk (>= 6 blocks of solid on every side), so it touches no region face.
            //     NOTE: the sealing here comes from the bot being NO-BREAK (mining.canMine=false). The region
            //     tier is unbreakable-BLIND (FragmentBuilder saturates bedrock's hardness sentinel to the same
            //     nibble as breakable obsidian; RegionPathfinder.expandNode emits a finite dig-through edge for a
            //     break-capable bot), so a BREAK-CAPABLE bot's seal-flood always "digs out" and NO tomb — bedrock
            //     or otherwise — ever seals for it (the residual deferred to #6, DESIGN-boxed-in-reachability §14).
            //     A no-break bot has no dig edges, so any solid wall is a hard seal; bedrock is used only so the
            //     lining is self-documenting as a wall.
            for (int y = TOMB_FLOOR; y <= TOMB_FLOOR + 4; y++) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean interior = (y >= TOMB_FLOOR + 1 && y <= TOMB_FLOOR + 3)
                                && dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1;
                        setBlock(TOMB_CX + dx, y, TOMB_CZ + dz, interior ? AIR : BEDROCK);
                    }
                }
            }
        }

        void setBlock(int x, int y, int z, BlockState s) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), s);
        }

        void enter(int i) {
            index = i;
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.setMode(AllyBotEntity.Mode.STAY);
            bot.setPos(START_X + 0.5, FEET, START_Z + 0.5);
            bot.setDeltaMovement(Vec3.ZERO);
            settling = true;
            settleTicks = 0;
            attemptTicks = 0;
            navRetries = 0;
            OrebitCommon.LOGGER.info("[Orebit/boxedin] === scenario {} : {} -> goal {}",
                    i, legs[i], compact(goal(i)));
        }

        BlockPos goal(int i) {
            return legs[i] == Leg.TOMB ? goalA : goalB;
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            // Overall fail-safe: a hang anywhere becomes a FAIL, never an infinite loop.
            if (++totalTicks >= OVERALL_BUDGET) {
                finish("overall tick budget exhausted (" + totalTicks + "t)");
                return;
            }

            Leg leg = legs[index];
            BlockPos g = goal(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                // Gate on nav readiness around the GOAL cell — the proactive boxed-in scan requires the goal's
                // L0 region BUILT (Gate 1), and an open goal likewise needs its landing built. Only issue the
                // goto once both the warmup window elapsed AND the ring around the goal is resident.
                if (++settleTicks < target || !navReadyAround(g)) {
                    if (settleTicks >= OVERALL_BUDGET) {
                        finish("nav never built around goal " + compact(g) + " (waited " + settleTicks + "t)");
                    }
                    return;
                }
                settling = false;
                bot.comeTo(g);
                OrebitCommon.LOGGER.info("[Orebit/boxedin] scenario {} goto issued at settle {}t (nav ready)",
                        index, settleTicks);
                return;
            }

            attemptTicks++;

            boolean gaveUp = bot.navigator().navGaveUp();
            boolean boxedIn = bot.navigator().boxedInProven();
            boolean atStay = bot.mode() == AllyBotEntity.Mode.STAY;
            boolean arrived = atStay && arrivedAt(g);

            if (leg == Leg.TOMB) {
                // A sealed goal must give up HONESTLY (proven boxed-in), never arrive.
                if (arrived) {
                    recordTomb("FAIL", "reached a sealed goal (should be impossible)");
                    return;
                }
                if (gaveUp) {
                    tombGaveUp = true;
                    tombBoxedIn = boxedIn;
                    recordTomb(boxedIn ? "PASS" : "FAIL",
                            boxedIn ? "gave up with boxedInProven=true"
                                    : "gave up but boxedInProven=false (not a proactive seal proof)");
                    return;
                }
                if (!bot.isAlive()) {
                    recordTomb("FAIL", "bot died");
                    return;
                }
                if (attemptTicks >= ATTEMPT_BUDGET) {
                    recordTomb("FAIL", "timeout — never gave up (bot may be wandering a sealed goal)");
                }
                return;
            }

            // OPEN leg.
            if (arrived) {
                recordOpen("PASS", "arrived at the open goal");
                return;
            }
            if (!bot.isAlive()) {
                recordOpen("FAIL", "bot died");
                return;
            }
            if (gaveUp) {
                // An early give-up on an OPEN goal is almost always nav-not-yet-built after re-seating; retry a
                // few times before calling it a real failure.
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(g);
                    return;
                }
                recordOpen("FAIL", "nav gave up on an open goal"
                        + (boxedIn ? " (WRONGLY boxedInProven=true)" : ""));
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                recordOpen("FAIL", "timeout — never arrived at the open goal");
            }
        }

        void recordTomb(String result, String reason) {
            tombResult = result;
            tombReason = reason;
            OrebitCommon.LOGGER.info("[Orebit/boxedin] tomb -> {} ({}) [gaveUp={} boxedIn={}]",
                    result, reason, tombGaveUp, tombBoxedIn);
            advance();
        }

        void recordOpen(String result, String reason) {
            openResult = result;
            openReason = reason;
            OrebitCommon.LOGGER.info("[Orebit/boxedin] open -> {} ({})", result, reason);
            advance();
        }

        void advance() {
            if (index + 1 < legs.length) {
                enter(index + 1);
            } else {
                finish("all scenarios complete");
            }
        }

        /** 3-D arrival test at feet cell {@code g}, mirroring {@link BotNavigator#ARRIVE_DIST}/{@code ARRIVE_Y}. */
        boolean arrivedAt(BlockPos g) {
            double dx = g.getX() + 0.5 - bot.getX();
            double dy = g.getY() - bot.getY();
            double dz = g.getZ() + 0.5 - bot.getZ();
            return Math.sqrt(dx * dx + dz * dz) <= BotNavigator.ARRIVE_DIST
                    && Math.abs(dy) <= BotNavigator.ARRIVE_Y;
        }

        boolean navReadyAround(BlockPos cell) {
            final int radius = ConfigLoader.config().navReadyRadiusChunks();
            return NavStore.ringBuilt(level, cell.getX() >> 4, cell.getZ() >> 4, radius);
        }

        void finish(String reason) {
            overallDone = true;
            boolean overall = "PASS".equals(tombResult) && "PASS".equals(openResult);
            String overallResult = overall ? "PASS" : "FAIL";
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "result", overallResult);              // orchestrator exit-code contract
                kv(w, "reason", reason);
                kv(w, "completed", "true");
                kv(w, "tomb_goal", coord(goalA));
                kv(w, "tomb_result", tombResult);
                kv(w, "tomb_gaveUp", tombGaveUp);
                kv(w, "tomb_boxedIn", tombBoxedIn);
                kv(w, "tomb_reason", tombReason);
                kv(w, "open_goal", coord(goalB));
                kv(w, "open_result", openResult);
                kv(w, "open_reason", openReason);
                if (bot != null) {
                    kv(w, "finalX", fmt(bot.getX()));
                    kv(w, "finalY", fmt(bot.getY()));
                    kv(w, "finalZ", fmt(bot.getZ()));
                    kv(w, "mode", bot.mode());
                    kv(w, "navGaveUp", bot.navigator().navGaveUp());
                    kv(w, "navstat_boxedIn", bot.navigator().boxedInProven());
                    kv(w, "alive", bot.isAlive());
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/boxedin] could not write {}", file, e);
            }
            OrebitCommon.LOGGER.info("[Orebit/boxedin] DONE ({}) — overall {} [tomb={} open={}] — halting",
                    reason, overallResult, tombResult, openResult);
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-boxedin-exit");
            exiter.setDaemon(true);
            exiter.start();
        }

        private static String coord(BlockPos p) {
            return p == null ? "n/a" : p.getX() + "," + p.getY() + "," + p.getZ();
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

    private static String compact(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
