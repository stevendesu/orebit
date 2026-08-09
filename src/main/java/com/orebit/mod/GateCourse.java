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
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Headless FENCE-GATE-ARC diagnostic harness (a sibling of {@link TrapdoorCourse}, armed by its own
 * {@code -Dorebit.gate} flag) — the live follower-tier proof of DESIGN-fence-gates.md §7. Each tile is an
 * isolated station cloning the geometry the planner test suite pins synthetically ({@code GateToggleTest} /
 * {@code PhaseRunnerGateTest}), rebuilt from real blocks at {@link #Y0} over a flat world and driven end to
 * end: plan → the face-agnostic SET_OPEN fold → the {@code PhaseRunner} openable pre-pass
 * ({@code WorldEdits.setGateOpen}) → the crossing itself under full vanilla physics. PASS requires BOTH the
 * exact goal cell reached (where crossing is expected) AND the expected end-of-trial gate world state,
 * INCLUDING the observed toggle count — every gate state change on a tile is a bot toggle by construction
 * (peaceful, no redstone, no wind charges; only the exttoggle tile's one-shot course flip is external), so
 * the per-tick state watch is an own-toggle counter without instrumenting the bot.
 *
 * <p><b>The tiles</b> (corridors run +X, 1-wide at the tile's centre Z, stone-boxed 2-tall so the gate is
 * the only route; gates face EAST so the closed 4/16 plate spans the corridor's Z width and bars +X travel
 * — facing is behaviorally void for the executor, §1, but the collision axis must cross the corridor):
 * <ol>
 *   <li><b>opencross</b> — CLOSED oak gate in the crossing column's feet cell: the §3 toggle-for-passage
 *       fold; expect EXACTLY ONE own SET_OPEN observed (one closed→open transition, none back) and the
 *       gate left OPEN.</li>
 *   <li><b>alreadyopen</b> — same geometry, gate pre-OPENED (zero collision, §1): the zero-SET control;
 *       expect the crossing with ZERO gate state changes observed.</li>
 *   <li><b>exttoggle</b> — the anti-trick property, cloned from {@link TrapdoorCourse}'s exttoggle tile:
 *       once the bot's own SET_OPEN lands (and while its body is still fully west of the gate cell) the
 *       COURSE flips the gate CLOSED from the tick handler, exactly once. The {@code PhaseRunner} pre-pass
 *       re-validates against the live block ({@code AllyBotEntity.doorOpenAt} reads gates, §4) and must
 *       simply re-issue the toggle next tick — no timers, no stuck state; expect the flip observed, a
 *       re-open observed after it, and the crossing completed. Timeout = FAIL (a livelock shows here).</li>
 *   <li><b>toggleoff</b> — the control: {@code doors.toggle=false} installed through the REAL owner
 *       mechanism (rewrite the live config file + {@code ConfigLoader.reload}, the {@code /bot config
 *       reload} path — {@code AllyBotEntity.caps()} picks the flip up on the next plan). The gate is
 *       default-PROTECTED ({@code #minecraft:fence_gates} is in the built-in protected list) and this
 *       course runs no-break/no-place, so the closed gate is a hard wall with no route around: PASS = the
 *       planner gives up honestly ({@link BotNavigator#navGaveUp}, the {@link BoxedInCourse} refusal
 *       convention) with ZERO gate state changes and the bot never past the gate plane. Crossing, arrival,
 *       or timeout-without-give-up = FAIL. Runs LAST so the config flip never needs reverting.</li>
 * </ol>
 *
 * <p><b>Config (scripts/gate/orebit.properties).</b> {@code doors.toggle=true} (the arc under test; the
 * toggleoff tile flips it at runtime as above), {@code mining.canMine=false} (unlike the trapdoor course —
 * no gate tile needs a MineDown arm, and no-break makes the toggleoff refusal exact: no dig-around route
 * exists, so the fold is the ONLY way through, §3), {@code placement.canPlace=false} (no bridging around),
 * {@code pathing.async=false} (deterministic).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.gate} is
 * set. Common, version-portable source (fence-gate state properties are drift-free 1.17.1 → 26.2, §1).
 */
public final class GateCourse {

    private GateCourse() {}

    private static final String RESULT_FILE = "orebit-gate-result.properties";
    private static final String TRACE_FILE = "orebit-gate-trace.txt";

    /** Corridor-floor Y (feet stand at {@code Y0+1}); floating high so a fall off a tile is unambiguous. */
    private static final int Y0 = 150;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    private static final int COLS = 2;
    private static final int STRIDE = 40; // grid cell size (> the longest tile span so nav grids never touch)

    /** Ticks to let the whole starting area gen + nav-build before the first goto. */
    private static final int WARMUP_TICKS = 160;
    /** Ticks after each teleport before the goto (the just-painted tile's nav patches settle). */
    private static final int SETTLE_TICKS = 60;
    private static final int NAV_RETRY_WINDOW = 60;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). Every crossing is short; a stuck/looping bot resolves well within it. */
    private static final int ATTEMPT_BUDGET = 600;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.gate") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/gate] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        OPEN_CROSS,   // closed gate bars the corridor -> exactly one own SET_OPEN and pass
        ALREADY_OPEN, // gate pre-opened -> cross with ZERO gate state changes
        EXT_TOGGLE,   // opencross + a one-shot external re-CLOSE after the bot's own toggle lands
        TOGGLE_OFF    // doors.toggle=false -> the protected closed gate is a hard wall; honest give-up
    }

    /** One gate challenge: a kind + its base grid cell, with start/goal geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int baseX, baseZ;
        final int zc;               // centre-line Z of the tile
        final int gateX;            // X of the gate column

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;
        int minFloorY;              // a fall this far below the tile = off the course
        BlockPos gate;              // the tile's ONE gate cell…
        boolean expectOpen;         // …and the OPEN state it must END in

        Trial(String name, Kind kind, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 6;
            this.gateX = baseX + 6;
            this.minFloorY = Y0 - 6;
            this.startX = baseX - 1.5;
            this.startY = Y0 + 1;
            this.startZ = zc + 0.5;
            this.startYaw = yaw(1, 0); // face +X down the corridor
            this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
            this.gate = new BlockPos(gateX, Y0 + 1, zc);
            // opencross/exttoggle end OPEN (the bot's toggle sticks); alreadyopen stays OPEN (untouched);
            // toggleoff must still be CLOSED (never operated).
            this.expectOpen = kind != Kind.TOGGLE_OFF;
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

        // Per-trial tile state (reset in enter()).
        boolean prevOpen;           // last observed gate OPEN state (the transition-counter baseline)
        int opensSeen;              // closed->open transitions observed this trial
        int closesSeen;             // open->closed transitions observed this trial
        double maxBotX;             // easternmost bot X this trial (TOGGLE_OFF: the never-crossed proof)
        boolean flipped;            // EXT_TOGGLE: the one-shot external re-close fired
        boolean reopened;           // EXT_TOGGLE: the gate read OPEN again after the flip

        // Course-level one-shot: doors.toggle=false installed for the (last) TOGGLE_OFF tile, and whether
        // the reload verifiably took (a flip that silently failed must fail the tile, not pass it).
        boolean toggleOffInstalled;
        boolean toggleOffTook;

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            // toggleoff MUST be last: its config flip (doors.toggle=false via the live reload path) is
            // never reverted, so no later tile may depend on toggling.
            add("opencross",   Kind.OPEN_CROSS);
            add("alreadyopen", Kind.ALREADY_OPEN);
            add("exttoggle",   Kind.EXT_TOGGLE);
            add("toggleoff",   Kind.TOGGLE_OFF);
        }

        void add(String name, Kind kind) {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake: keep consecutive trials adjacent
            int bx = BASE_X + col * STRIDE;
            int bz = BASE_Z + row * STRIDE;
            trials.add(new Trial(name, kind, bx, bz));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.gate.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitGate:owner".getBytes(StandardCharsets.UTF_8)),
                        "Gate"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit gate course trace  (T <trial> <tick> x y z | spd vy | onGround | hp | gate=<O/C> | move)\n");
                trace.write("legend: gate = live OPEN state of the tile's fence gate (O=open C=closed); GATE lines mark\n");
                trace.write("state transitions (every one is a bot toggle except the exttoggle tile's one-shot EXTFLIP);\n");
                trace.write("CONFIG marks the toggleoff tile's doors.toggle=false install via the live reload path\n\n");
                OrebitCommon.LOGGER.info("[Orebit/gate] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/gate] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            if (tr.kind == Kind.TOGGLE_OFF && !toggleOffInstalled) {
                installToggleOff();
            }
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
            prevOpen = openAt(tr.gate);
            opensSeen = 0;
            closesSeen = 0;
            maxBotX = tr.startX;
            flipped = false;
            reopened = false;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d) gate=(%d,%d,%d)\n",
                        tr.name, tr.kind, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ(),
                        tr.gate.getX(), tr.gate.getY(), tr.gate.getZ()));
            } catch (IOException ignored) { }
        }

        /**
         * Install {@code doors.toggle=false} through the SAME machinery an owner uses ({@code /bot config
         * reload}): rewrite the live {@code config/orebit.properties}, then {@link ConfigLoader#reload} —
         * so the flip exercises the real caps-rebake path (planner-pool drain, {@code BotCaps}
         * re-derivation picked up by {@code AllyBotEntity.caps()} on the next plan), not a test backdoor.
         * Verifies the reload actually took; a silent failure must FAIL the tile, never fake a refusal.
         */
        void installToggleOff() {
            toggleOffInstalled = true;
            try {
                Path file = ConfigDir.serverDir(server).resolve("config").resolve("orebit.properties");
                List<String> lines = new ArrayList<>();
                if (Files.exists(file)) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (!line.trim().startsWith("doors.toggle")) {
                            lines.add(line);
                        }
                    }
                }
                lines.add("doors.toggle=false");
                Files.write(file, lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/gate] toggleoff config rewrite failed", e);
            }
            ConfigLoader.reload(server);
            toggleOffTook = !ConfigLoader.config().doorToggle();
            OrebitCommon.LOGGER.info("[Orebit/gate] toggleoff: doors.toggle=false installed via reload (took={})",
                    toggleOffTook);
            try {
                trace.write(String.format(Locale.ROOT,
                        "  CONFIG doors.toggle=false installed via ConfigLoader.reload (took=%s)\n", toggleOffTook));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                // Also gate the goto on nav residency around the goal (the BoxedInCourse readiness gate):
                // the toggleoff tile PASSES on a give-up, so its goto must never be issued over unbuilt nav
                // or a premature navGaveUp would fake the refusal. Applied uniformly — for the crossing
                // tiles it only ever delays the goto, never changes the outcome.
                if (++settleTicks < target || !navReadyAround(tr.goal)) {
                    return;
                }
                settling = false;
                bot.comeTo(tr.goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }

            attemptTicks++;

            // The per-tick gate state watch: count transitions in both directions. Nothing else on a tile
            // can change the gate (peaceful, no redstone, no wind charges), so every transition is a bot
            // toggle — except the exttoggle tile's one-shot course flip, counted knowingly (that tile
            // asserts flags, not counts).
            boolean open = openAt(tr.gate);
            if (open != prevOpen) {
                if (open) opensSeen++; else closesSeen++;
                try {
                    trace.write(String.format(Locale.ROOT,
                            "  GATE tick=%d %s bot=(%.2f,%.2f,%.2f) opens=%d closes=%d\n",
                            attemptTicks, open ? "C->O" : "O->C",
                            bot.getX(), bot.getY(), bot.getZ(), opensSeen, closesSeen));
                } catch (IOException ignored) { }
                prevOpen = open;
            }
            if (bot.getX() > maxBotX) {
                maxBotX = bot.getX();
            }

            // EXT_TOGGLE: the one-shot external re-close, cloned from TrapdoorCourse. State-based, no
            // timers — fires the first tick the gate reads OPEN (i.e. the bot's own SET_OPEN has landed)
            // while the bot's body is still fully WEST of the gate cell (never closes a gate through the
            // bot itself). The PhaseRunner's per-tick re-validation must then simply re-issue the toggle.
            if (tr.kind == Kind.EXT_TOGGLE) {
                if (!flipped) {
                    if (open && bot.getX() < tr.gateX - 0.35) {
                        level.setBlockAndUpdate(tr.gate,
                                level.getBlockState(tr.gate).setValue(BlockStateProperties.OPEN, false));
                        flipped = true;
                        try {
                            trace.write(String.format(Locale.ROOT,
                                    "  EXTFLIP tick=%d bot=(%.2f,%.2f,%.2f) — gate externally re-CLOSED\n",
                                    attemptTicks, bot.getX(), bot.getY(), bot.getZ()));
                        } catch (IOException ignored) { }
                    }
                } else if (!reopened && open) {
                    reopened = true;
                    try {
                        trace.write(String.format(Locale.ROOT,
                                "  REOPEN tick=%d bot=(%.2f,%.2f,%.2f) — bot re-issued its toggle\n",
                                attemptTicks, bot.getX(), bot.getY(), bot.getZ()));
                    } catch (IOException ignored) { }
                }
            }

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
                record(tr, "FAIL", "fell off the tile");
                return;
            }

            if (tr.kind == Kind.TOGGLE_OFF) {
                tickToggleOff(tr, dist);
                return;
            }

            // Candidate PASS: the driver reverted to STAY (exact-tolerance arrival) near the goal cell —
            // then the tile's WORLD-STATE assertions decide (arriving without operating the gate as
            // expected is a FAIL).
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                String stateFail = worldStateFailure(tr);
                if (stateFail == null) {
                    record(tr, "PASS", "reached goal, world state as expected");
                } else {
                    record(tr, "FAIL", "arrived but " + stateFail);
                }
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
                record(tr, "FAIL", "timeout");
            }
        }

        /**
         * The TOGGLE_OFF verdict: refusing is the correct outcome. The protected closed gate is a hard
         * wall (no toggle, no break, no place), so PASS = an honest give-up with the gate untouched and
         * the bot never past the gate plane. Crossing/arrival = the caps gate leaked; a timeout without
         * the give-up = no honest refusal (a park/livelock — the BoxedInCourse tomb convention).
         */
        void tickToggleOff(Trial tr, double dist) {
            if (!toggleOffTook) {
                record(tr, "FAIL", "doors.toggle=false did not install (reload did not take)");
                return;
            }
            if (bot.getX() > tr.gateX + 1.0) {
                record(tr, "FAIL", "crossed the gate plane with doors.toggle off");
                return;
            }
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                record(tr, "FAIL", "arrived at the goal with doors.toggle off");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                String stateFail = worldStateFailure(tr);
                if (stateFail == null) {
                    record(tr, "PASS", "gave up honestly; gate untouched");
                } else {
                    record(tr, "FAIL", "gave up but " + stateFail);
                }
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", "timeout — never gave up (parked without an honest refusal)");
            }
        }

        /** {@code null} when every end-of-trial assertion holds; else a short reason. */
        String worldStateFailure(Trial tr) {
            boolean open = openAt(tr.gate);
            if (open != tr.expectOpen) {
                return "gate " + tr.gate.toShortString() + " is " + (open ? "OPEN" : "CLOSED")
                        + ", expected " + (tr.expectOpen ? "OPEN" : "CLOSED");
            }
            switch (tr.kind) {
                case OPEN_CROSS: {
                    // Exactly one own toggle: one closed->open transition and none back.
                    if (opensSeen != 1 || closesSeen != 0) {
                        return "expected exactly one own SET_OPEN (opens=" + opensSeen
                                + " closes=" + closesSeen + ")";
                    }
                    break;
                }
                case ALREADY_OPEN:
                case TOGGLE_OFF: {
                    // Zero toggles: the gate state must never have changed.
                    if (opensSeen != 0 || closesSeen != 0) {
                        return "expected zero gate toggles (opens=" + opensSeen
                                + " closes=" + closesSeen + ")";
                    }
                    if (tr.kind == Kind.TOGGLE_OFF && maxBotX > tr.gateX + 1.0) {
                        return "bot crossed the gate plane (maxX=" + fmt(maxBotX) + ")";
                    }
                    break;
                }
                default: { // EXT_TOGGLE — flags, not counts (the course's own flip perturbs the counters)
                    if (!flipped) return "the external flip never armed (bot crossed before its own toggle landed?)";
                    if (!reopened) return "the gate was never re-opened after the external flip";
                    break;
                }
            }
            return null;
        }

        boolean openAt(BlockPos pos) {
            BlockState s = level.getBlockState(pos);
            return s.getBlock() instanceof FenceGateBlock && s.getValue(BlockStateProperties.OPEN);
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
                        "T %-12s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %.1f | gate=%s | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y,
                        grnd ? 1 : 0, bot.getHealth(), openAt(tr.gate) ? "O" : "C", move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            String extra = " opens=" + opensSeen + " closes=" + closesSeen;
            if (tr.kind == Kind.EXT_TOGGLE) {
                extra += " flipped=" + flipped + " reopened=" + reopened;
            } else if (tr.kind == Kind.TOGGLE_OFF) {
                extra += " maxX=" + fmt(maxBotX) + " took=" + toggleOffTook;
            }
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.1f,%.1f,%.1f) gate=%s%s lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), openAt(tr.gate) ? "O" : "C", extra,
                    bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/gate] {} -> {} ({}) closest={} ticks={} gate={} opens={} closes={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, openAt(tr.gate) ? "O" : "C", opensSeen, closesSeen);
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        private static String fmt(double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            int bx = tr.baseX, zc = tr.zc;
            // 2-tall boxed corridor, identical for every tile (the closed gate's 24/16 collision caps the
            // feet cell outright and protrudes into the head row like a fence — §3: a whole-cell blocker,
            // no over-the-gate route at 2-tall headroom). Facing EAST puts the closed plate's collision
            // across +X travel (the plate spans the corridor's Z width); open = zero hitbox either way.
            stripX(bx - 3, bx - 1, Y0, zc);
            box(bx, bx + 12, Y0, Y0 + 3, zc - 1, zc + 1);
            carve(bx, bx + 12, Y0 + 1, Y0 + 2, zc);
            set(tr.gateX, Y0 + 1, zc, gate(Direction.EAST, tr.kind == Kind.ALREADY_OPEN));
        }

        /** Dump the ACTUALLY-PLACED gate BlockState (facing/open/in_wall) — the collision-axis double-check
         *  (a NORTH/SOUTH facing's closed plate runs ALONG the corridor and bars nothing; transposing the
         *  axis silently turns every tile into a free walk). */
        void probeTile(Trial tr) {
            BlockState s = level.getBlockState(tr.gate);
            OrebitCommon.LOGGER.info("[Orebit/gate] {} probe {} = {}", tr.name, tr.gate.toShortString(), s);
            try {
                trace.write(String.format(Locale.ROOT, "  PROBE %s = %s\n", tr.gate.toShortString(), s));
            } catch (IOException ignored) { }
        }

        // ---- placement primitives ------------------------------------------------------------------------

        static BlockState gate(Direction facing, boolean open) {
            return Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.OPEN, open);
        }

        /** Solid stone fill over the inclusive box. */
        void box(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, STONE);
        }

        /** Carve the 1-wide corridor body (air) at {@code z} over the inclusive spans. */
        void carve(int x0, int x1, int y0, int y1, int z) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    set(x, y, z, AIR);
        }

        /** A 1-wide (z=zc) stone strip along +X from {@code x0}..{@code x1} — the safe start pad. */
        void stripX(int x0, int x1, int y, int zc) {
            for (int x = x0; x <= x1; x++) set(x, y, zc, STONE);
        }

        void set(int x, int y, int z, BlockState state) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), state);
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
                OrebitCommon.LOGGER.error("[Orebit/gate] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/gate] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-gate-exit");
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
