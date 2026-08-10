package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Headless ITERATED-ASCEND diagnostic harness (a sibling of {@link GateCourse} / {@link ShaftCourse}, armed
 * by its own {@code -Dorebit.staircase} flag) — the live follower-tier repro for the 2026-08-10 staircase
 * wedge. Each tile is an isolated free-standing stone staircase rebuilt from real blocks at {@link #Y0} over
 * a flat world and driven end to end under full vanilla physics: plan → consecutive {@code Ascend} steps →
 * arrival on the top landing.
 *
 * <h2>The pathology being reproduced</h2>
 * Owner-observed on MC 1.20.1 (worlds {@code Test Parkour} / {@code - Copy}, twice, on a natural snowy
 * grass hillside that rises exactly one block per block of +Z), then narrowed by the owner to a hand-built
 * staircase in a flat world: after a couple of hops the bot latches. Both field captures share one exact
 * signature — the bot ends up <b>grounded one full block ABOVE the Ascend's intended landing feet</b>, at
 * the destination column, and {@code Ascend}'s {@code failWhen} envelope then correctly refuses (fail→hold):
 * <pre>
 *   run 1  to-floor (-16,66,65) top 67.0  ->  bot grounded at botY 68.000   (+1)
 *   run 2  to-floor (-21,65,63) top 66.0  ->  bot grounded at botY 67.000   (+1)
 * </pre>
 * Both confirmed against saved {@code playerdata} ({@code onGround=1}), so the +1 is physical, not a
 * logging artifact. The jump arc itself is textbook — apex measured at {@code 66.252} from a takeoff of
 * {@code 65.0}, i.e. exactly {@code MovementContext.JUMP_RISE}'s 1.2522 — so ONE jump cannot reach +2 over
 * the takeoff; a second jump is the only arithmetic that gets there. The prime suspect is therefore
 * {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend}'s climb-phase jump gate
 * ({@code b.setJumping(b.footY() < landFootY)}) re-arming on a landing that has not settled — the same
 * "launched from one block too high → permanent fail→HOLD" family that gate was written to close for the
 * 2026-08-01 vine top-out.
 *
 * <h2>The tiles</h2>
 * Staircases run +X, 3 wide in Z (hillside-like: the bot has room, so a fall off the side is a real
 * outcome rather than a fixture artifact), rising one block per column unless stated:
 * <ol>
 *   <li><b>step1</b> — ONE step. The control: a lone Ascend must pass. If this fails the defect is not
 *       about iteration at all and every other tile's reading is worthless.</li>
 *   <li><b>step2</b> — two back-to-back steps: the minimal iteration case, and the cheapest possible
 *       repro if the second Ascend is what breaks.</li>
 *   <li><b>step4</b> — four back-to-back steps: the owner's field description ("after a couple of hops").</li>
 *   <li><b>step8</b> — eight back-to-back steps, for a defect that only shows after a longer run.</li>
 *   <li><b>snow4</b> — step4's geometry with the field's EXACT surface: every tread capped
 *       {@code grass_block[snowy=true]} with {@code snow[layers=1]} in the feet cell above it. That snow
 *       has an EMPTY collision shape (javap-verified on 1.20.1: {@code SnowLayerBlock.getCollisionShape}
 *       returns {@code SHAPE_BY_LAYER[layers-1]}, and {@code SHAPE_BY_LAYER[0] = Shapes.empty()}), so it
 *       cannot lift the bot — this tile exists to PROVE that, by passing or failing identically to
 *       {@code step4}. A divergence here would mean the zero-collision occupant matters after all.</li>
 *   <li><b>spaced4</b> — four steps separated by a 2-block flat run, so no two Ascends are adjacent. The
 *       discriminator: if this PASSES while {@code step4} FAILS, back-to-back adjacency is the trigger and
 *       the bug is in the hand-off between consecutive Ascends, not in Ascend itself.</li>
 * </ol>
 *
 * <h2>Verdict</h2>
 * PASS = the exact goal cell reached. The wedge presents as a permanent hold, so it surfaces as a timeout.
 * Every trial additionally records {@link Course#maxAbove} — the largest {@code botY − expected-feet-Y for
 * the bot's own column} observed while GROUNDED. That number IS the pathology: {@code ~0.0} on a clean
 * climb, {@code ~1.0} when the bot is standing a block above its tread. It is reported on pass and fail
 * alike, so a tile that passes while still floating a block high cannot hide.
 *
 * <p><b>Config (scripts/staircase/orebit.properties).</b> {@code mining.canMine=false} and
 * {@code placement.canPlace=false} — with no dig and no bridge the staircase is the only route, so the bot
 * must actually iterate Ascends rather than pillar or tunnel past the shape under test.
 * {@code pathing.async=false} for determinism.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.staircase}
 * is set. Common, version-portable source (stone/grass/snow states are drift-free 1.17.1 → 26.2).
 */
public final class StaircaseCourse {

    private StaircaseCourse() {}

    private static final String RESULT_FILE = "orebit-staircase-result.properties";
    private static final String TRACE_FILE = "orebit-staircase-trace.txt";

    /** Start-pad feet Y (the pad's top surface); floating high so a fall off a tile is unambiguous. */
    private static final int Y0 = 150;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    private static final int COLS = 2;
    private static final int STRIDE = 48; // > the longest tile span so tiles' nav grids never touch

    private static final int WARMUP_TICKS = 160;
    private static final int SETTLE_TICKS = 60;
    private static final int NAV_RETRY_WINDOW = 60;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial budget (ticks). Even step8 climbs in well under this; the wedge is permanent, so it times out. */
    private static final int ATTEMPT_BUDGET = 600;

    /** Grounded height above the column's own tread that counts as the "+1 block high" pathology. */
    private static final double ABOVE_SURFACE_ALARM = 0.5;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState SNOWY_GRASS =
            Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true);
    /** {@code snow[layers=1]} — the default state; EMPTY collision, so it never lifts the bot. */
    private static final BlockState SNOW_LAYER = Blocks.SNOW.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.staircase") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/staircase] armed: {} trials", course.trials.size());
    }

    /** One staircase challenge. {@code stepRun} &gt; 1 inserts a flat run after each rise (the spaced tile). */
    private static final class Trial {
        final String name;
        final int steps;
        final int stepRun;
        final boolean snowy;
        final int baseX, baseZ;
        final int zc;

        final int span;        // x columns the staircase itself occupies
        final int topFeetY;    // feet Y on the last tread / the landing
        final int minFloorY;   // a fall this far below the pad = off the course

        /** Half-width in Z. 1 = a 3-wide stair; the diagonal tiles widen it into a crossable slope. */
        final int halfW;
        final int zStart, zGoal;   // differ on a DIAGONAL tile, forcing an angled ascent
        /**
         * Flat pad columns before the first tread — the bot's RUN-UP, and the experimental variable the
         * owner's own flat-world repro pointed at (2026-08-10): dead-on approach, but enough room to build
         * momentum before the first jump. A player accelerates over several ticks, so a long pad arrives at
         * step 0 at full speed while a short one arrives still accelerating; the horizontal distance covered
         * during the jump arc scales with it, and on a 1:1 staircase that decides which tread the bot comes
         * down on.
         */
        final int runUp;

        final double startX, startY, startZ;
        final float startYaw;
        final BlockPos goal;

        Trial(String name, int steps, int stepRun, boolean snowy, boolean diagonal, int runUp,
                int baseX, int baseZ) {
            this.name = name;
            this.steps = steps;
            this.stepRun = stepRun;
            this.snowy = snowy;
            this.runUp = runUp;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 6;
            this.span = steps * stepRun;
            this.topFeetY = Y0 + steps;
            this.minFloorY = Y0 - 6;
            // A diagonal tile is a WIDE uniform slope (height depends on X alone) whose start and goal sit
            // on opposite Z edges, so the only route up crosses it at an angle and every Ascend is entered
            // out of a Diagonal, carrying cross-axis momentum. That is the field geometry: the owner's
            // hillside rose one block per column and the bot traversed it obliquely.
            this.halfW = diagonal ? 4 : 1;
            this.zStart = diagonal ? zc - 3 : zc;
            this.zGoal = diagonal ? zc + 3 : zc;
            this.startX = baseX - runUp + 0.5; // stand at the FAR end of the pad — the whole run-up is used
            this.startY = Y0;
            this.startZ = zStart + 0.5;
            this.startYaw = yaw(1, 0); // face +X, up the slope
            this.goal = new BlockPos(baseX + span + 2, topFeetY, zGoal);
        }

        /**
         * Expected FEET Y for the bot's column {@code x} — the pad, the tread it is on, or the landing.
         * {@link Integer#MIN_VALUE} when {@code x} is off the tile entirely (no assertion possible there).
         */
        int surfaceFeetYAt(int x) {
            if (x < baseX - runUp) return Integer.MIN_VALUE;
            if (x < baseX) return Y0;                       // start pad
            if (x < baseX + span) return Y0 + 1 + (x - baseX) / stepRun;
            if (x <= baseX + span + 3) return topFeetY;     // landing
            return Integer.MIN_VALUE;
        }

        static float yaw(int dx, int dz) { return (float) Math.toDegrees(Math.atan2(-dx, dz)); }
    }

    private static final class Course {
        final List<Trial> trials = new ArrayList<>();
        final List<String> results = new ArrayList<>();
        MinecraftServer server;
        ServerLevel level;
        FakePlayerEntity owner;
        AllyBotEntity bot;
        BufferedWriter trace;

        int index = -1;
        boolean settling;
        int settleTicks;
        int attemptTicks;
        int navRetries;
        boolean overallDone;
        double closest;
        double prevX, prevZ;
        String prevMove = "";
        int prevSegToX = Integer.MIN_VALUE, prevSegToY, prevSegToZ;
        int passed, failed;

        // Per-trial pathology watch (reset in enter()).
        /** Largest grounded {@code botY − expected feet Y for the bot's own column} seen this trial. */
        double maxAbove;
        /** Where that worst grounded overshoot happened, and the tread it was measured against. */
        double aboveAtX, aboveAtY;
        int aboveExpectY;
        /** Highest tread index whose feet height the bot actually attained (how far it climbed). */
        int stepsClimbed;

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            //   name          steps stepRun snowy diagonal runUp
            add("step1",       1, 1, false, false, 3);
            add("step2",       2, 1, false, false, 3);
            add("step4",       4, 1, false, false, 3);
            add("step8",       8, 1, false, false, 3);
            add("snow4",       4, 1, true,  false, 3);
            add("spaced4",     4, 3, false, false, 3);
            // RUN-UP tiles — dead-on approach, but a long flat pad so the bot reaches the first tread at
            // full speed instead of still accelerating. Owner's own flat-world repro (2026-08-10) pointed
            // here: same geometry as step4/step8 above, the ONLY variable is how much momentum is carried
            // into the first jump. Horizontal distance covered during the arc scales with entry speed, and
            // on a 1:1 staircase that decides which tread the bot comes down on.
            add("run6step4",   4, 1, false, false, 6);
            add("run12step4",  4, 1, false, false, 12);
            add("run12step8",  8, 1, false, false, 12);
            add("run12snow4",  4, 1, true,  false, 12);
            // DIAGONAL tiles — a wide slope crossed at an angle, so every Ascend is entered out of a
            // Diagonal with cross-axis momentum. The straight-on tiles above all PASSED on the first run
            // (2026-08-10, maxAbove=0.000 across the board), which is exactly what says the missing
            // ingredient is the approach and not the staircase: the field's failing Ascend was preceded by
            // a Diagonal and entered at offCentre=(0.389,-0.320), and Ascend's own climb phase carries an
            // arrestCarryFrom guard written for "an Ascend entered carrying momentum perpendicular to its
            // own step -> launches off-lane -> permanent fail->HOLD".
            add("diag4",       4, 1, false, true,  3);
            add("diag8",       8, 1, false, true,  3);
            add("diagsnow4",   4, 1, true,  true,  3);
        }

        void add(String name, int steps, int stepRun, boolean snowy, boolean diagonal, int runUp) {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake: keep consecutive trials adjacent
            int bx = BASE_X + col * STRIDE;
            int bz = BASE_Z + row * STRIDE;
            trials.add(new Trial(name, steps, stepRun, snowy, diagonal, runUp, bx, bz));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.staircase.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitStair:owner".getBytes(StandardCharsets.UTF_8)),
                        "Stair"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit staircase course trace  (T <trial> <tick> x y z | spd vy | onGround"
                        + " | expectFeetY above | move)\n");
                trace.write("legend: expectFeetY = the feet height the bot's OWN column should stand at;"
                        + " above = botY - expectFeetY while grounded\n");
                trace.write("        (~0.0 = climbing cleanly; ~1.0 = the pathology - grounded a block"
                        + " above its own tread)\n");
                trace.write("        ABOVE lines mark a new worst grounded overshoot.\n\n");
                OrebitCommon.LOGGER.info("[Orebit/staircase] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/staircase] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr);
            probeTile(tr);
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.setMode(AllyBotEntity.Mode.STAY);
            bot.setPos(tr.startX, tr.startY, tr.startZ);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.setYRot(tr.startYaw);
            bot.setYHeadRot(tr.startYaw);
            settling = true;
            settleTicks = 0;
            attemptTicks = 0;
            navRetries = 0;
            closest = Double.MAX_VALUE;
            maxAbove = 0;
            aboveAtX = 0;
            aboveAtY = 0;
            aboveExpectY = 0;
            stepsClimbed = 0;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : steps=%d stepRun=%d snowy=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d)"
                                + " topFeetY=%d\n",
                        tr.name, tr.steps, tr.stepRun, tr.snowy, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ(), tr.topFeetY));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                if (++settleTicks < target || !navReadyAround(tr.goal)) {
                    return;
                }
                settling = false;
                bot.comeTo(tr.goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }

            attemptTicks++;
            watchSurface(tr);
            trace(tr);

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            double dx = bot.getX() - (tr.goal.getX() + 0.5);
            double dy = bot.getY() - tr.goal.getY();
            double dz = bot.getZ() - (tr.goal.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            if (bot.getY() < tr.minFloorY) {
                record(tr, "FAIL", "fell off the staircase");
                return;
            }
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                record(tr, "PASS", "reached the top landing");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(tr.goal, 0.75, 0.75, 0);
                    return;
                }
                record(tr, "FAIL", "nav gave up (no route offered)");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", stalledReason());
            }
        }

        /**
         * The pathology watch: while GROUNDED, compare the bot's Y against the feet height its own column
         * should stand at. A clean climb reads ~0; the wedge reads ~1.0 (grounded a block above its tread).
         * Airborne ticks are exempt — a jump is legitimately above the surface.
         */
        void watchSurface(Trial tr) {
            if (!EntityState.onGround(bot)) {
                return;
            }
            int expect = tr.surfaceFeetYAt((int) Math.floor(bot.getX()));
            if (expect == Integer.MIN_VALUE) {
                return; // off the tile — nothing to measure against
            }
            int reached = (int) Math.floor(bot.getY()) - Y0;
            if (reached > stepsClimbed) {
                stepsClimbed = Math.min(reached, tr.steps);
            }
            double above = bot.getY() - expect;
            if (above > maxAbove) {
                maxAbove = above;
                aboveAtX = bot.getX();
                aboveAtY = bot.getY();
                aboveExpectY = expect;
                if (above > ABOVE_SURFACE_ALARM) {
                    try {
                        trace.write(String.format(Locale.ROOT,
                                "  ABOVE tick=%d bot=(%.3f,%.3f,%.3f) expectFeetY=%d above=%.3f  <-- grounded"
                                        + " above its own tread\n",
                                attemptTicks, bot.getX(), bot.getY(), bot.getZ(), expect, above));
                    } catch (IOException ignored) { }
                }
            }
        }

        /** A timeout reason that says WHICH failure this was — the wedge, or merely slow. */
        String stalledReason() {
            if (maxAbove > ABOVE_SURFACE_ALARM) {
                return String.format(Locale.ROOT,
                        "timeout — WEDGED %.3f above its own tread (grounded at y=%.3f in a column whose"
                                + " feet belong at y=%d)",
                        maxAbove, aboveAtY, aboveExpectY);
            }
            return "timeout";
        }

        boolean navReadyAround(BlockPos cell) {
            final int radius = ConfigLoader.config().navReadyRadiusChunks();
            return NavStore.ringBuilt(level, cell.getX() >> 4, cell.getZ() >> 4, radius);
        }

        void trace(Trial tr) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            int expect = tr.surfaceFeetYAt((int) Math.floor(x));
            try {
                BotNavigator nav = bot.navigator();
                boolean segChanged = nav.segToX() != prevSegToX || nav.segToY() != prevSegToY
                        || nav.segToZ() != prevSegToZ;
                if (!move.equals(prevMove) || segChanged) {
                    trace.write(String.format(Locale.ROOT,
                            "  WP i=%d/%d %s seg=(%d,%d,%d)->(%d,%d,%d) bot=(%.2f,%.2f,%.2f)\n",
                            nav.waypointIndex(), nav.pathSize(), move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ(), x, y, z));
                    prevMove = move;
                    prevSegToX = nav.segToX(); prevSegToY = nav.segToY(); prevSegToZ = nav.segToZ();
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-8s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %s %s | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y, grnd ? 1 : 0,
                        expect == Integer.MIN_VALUE ? "--" : String.valueOf(expect),
                        (grnd && expect != Integer.MIN_VALUE)
                                ? String.format(Locale.ROOT, "%+.3f", y - expect) : "     .",
                        move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) steps=%d climbed=%d closest=%.2f ticks=%d finalPos=(%.2f,%.2f,%.2f)"
                            + " maxAbove=%.3f@(x=%.2f,y=%.2f,expect=%d) lastMove=%s",
                    tr.name, result, reason, tr.steps, stepsClimbed, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(),
                    maxAbove, aboveAtX, aboveAtY, aboveExpectY, bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info(
                    "[Orebit/staircase] {} -> {} ({}) climbed={}/{} closest={} ticks={} maxAbove={}",
                    tr.name, result, reason, stepsClimbed, tr.steps,
                    String.format(Locale.ROOT, "%.2f", closest), attemptTicks,
                    String.format(Locale.ROOT, "%.3f", maxAbove));
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        /**
         * Paint the tile: a 3-wide start pad, the staircase itself (one solid column per x, filled from the
         * pad base up to that column's tread), and a flat top landing. Every column is filled rather than
         * left as a floating slab so the bot can never fall THROUGH the stairs — a fall here always means it
         * left the tile sideways, which is a distinct, meaningful outcome.
         */
        void buildTile(Trial tr) {
            int bx = tr.baseX, zc = tr.zc;
            clear(bx - tr.runUp - 2, bx + tr.span + 6, Y0 - 1, tr.topFeetY + 4,
                    zc - tr.halfW - 2, zc + tr.halfW + 2);
            // Start pad (the run-up): feet stand at Y0, so the pad's top solid cell is Y0-1.
            fill(bx - tr.runUp, bx - 1, Y0 - 1, tr);
            // The staircase: column x carries the tread whose top is surfaceFeetYAt(x).
            for (int x = bx; x < bx + tr.span; x++) {
                fill(x, x, tr.surfaceFeetYAt(x) - 1, tr);
            }
            // Top landing (4 columns) so the goal never sits on a step edge.
            fill(bx + tr.span, bx + tr.span + 3, tr.topFeetY - 1, tr);
        }

        /**
         * Fill columns {@code x0..x1} (3 wide about {@code zc}) solid from the pad base up to and including
         * {@code topSolidY}. On a snowy tile the top cell becomes {@code grass_block[snowy=true]} and the
         * feet cell above it gets a {@code snow[layers=1]} — the field surface exactly.
         */
        void fill(int x0, int x1, int topSolidY, Trial tr) {
            for (int x = x0; x <= x1; x++) {
                for (int z = tr.zc - tr.halfW; z <= tr.zc + tr.halfW; z++) {
                    for (int y = Y0 - 2; y <= topSolidY; y++) {
                        set(x, y, z, tr.snowy && y == topSolidY ? SNOWY_GRASS : STONE);
                    }
                    if (tr.snowy) {
                        set(x, topSolidY + 1, z, SNOW_LAYER);
                    }
                }
            }
        }

        /** Air out the tile's whole envelope first, so a rebuild never inherits a previous shape. */
        void clear(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, AIR);
        }

        void set(int x, int y, int z, BlockState state) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), state);
        }

        /**
         * Dump the ACTUALLY-PLACED tread column heights — the geometry double-check. A staircase that did
         * not come out 1-block-per-column (or a snowy tile whose feet cell is not the zero-collision layer)
         * silently turns the whole tile into a different experiment.
         */
        void probeTile(Trial tr) {
            StringBuilder sb = new StringBuilder();
            for (int x = tr.baseX - 1; x <= tr.baseX + tr.span + 1; x++) {
                BlockPos feet = new BlockPos(x, tr.surfaceFeetYAt(x), tr.zc);
                sb.append(' ').append(x).append(':').append(feet.getY())
                        .append('=').append(level.getBlockState(feet).getBlock().getClass().getSimpleName());
            }
            OrebitCommon.LOGGER.info("[Orebit/staircase] {} probe (x:feetY=blockInFeetCell){}", tr.name, sb);
            try {
                trace.write("  PROBE (x:feetY=blockInFeetCell)" + sb + "\n");
            } catch (IOException ignored) { }
        }

        void finish(String reason) {
            overallDone = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "completed", "true");
                kv(w, "reason", reason);
                kv(w, "trials", trials.size());
                kv(w, "passed", passed);
                kv(w, "failed", failed);
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/staircase] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/staircase] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-staircase-exit");
            exiter.setDaemon(true);
            exiter.start();
        }

        private static void kv(BufferedWriter w, String key, Object value) throws IOException {
            w.write(key);
            w.write('=');
            w.write(String.valueOf(value));
            w.write('\n');
        }
    }
}
