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
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/**
 * Headless TRAPDOOR-ARC diagnostic harness (a sibling of {@link SwimCourse} / {@link IceCourse}, armed by
 * its own {@code -Dorebit.trapdoor} flag) — the live follower-tier proof of DESIGN-trapdoors.md §9. Each
 * tile is an isolated station cloning a geometry the planner test suite pins synthetically
 * ({@code TrapdoorToggleTest} / {@code TrapdoorSandwichTest} / {@code TrapdoorParkourTest}), rebuilt from
 * real blocks at {@link #Y0} over a flat world and driven end to end: plan → SET folds → the
 * {@code PhaseRunner} openable pre-pass ({@code WorldEdits.setTrapdoorOpen}) → the crossing itself under
 * full vanilla physics. PASS requires BOTH the exact goal cell reached AND the expected end-of-trial
 * trapdoor world state (a crossing that "arrives" without operating its hatch is a FAIL, not a pass).
 *
 * <p><b>The tiles</b> (corridors run +X, 1-wide at the tile's centre Z, stone-boxed so the hatch is the
 * only route; facings use the vanilla rule that the OPEN panel hugs the wall OPPOSITE {@code facing}):
 * <ol>
 *   <li><b>hallwayopen</b> — 2-tall corridor, closed-TOP oak hatch in the crossing column's FEET cell
 *       (facing NORTH → opens onto the south wall): the §5 TOGGLE-FOR-CLEARANCE fold; expect ONE
 *       SET_OPEN executed and the hatch left OPEN.</li>
 *   <li><b>hallwayclose</b> — 3-tall corridor, OPEN bottom-half panel ACROSS the feet cell (facing EAST
 *       → panel on the west strip, the entry face of eastward travel): the bot must SET_CLOSED and cross
 *       standing the 3/16 plate node; hatch left CLOSED.</li>
 *   <li><b>pocket</b> — a closed-BOTTOM hatch sunk flush in a floor hole: entered by the plain 13/16
 *       step-down, exited ONLY by Ascend's same-level jump arm; the hatch is never toggled (stays
 *       CLOSED) and the trace must show the feet dipping into the pocket.</li>
 *   <li><b>hatchdrop</b> — the bot STARTS standing on a closed-TOP hatch sealed in a 1×1 stone chimney,
 *       goal directly below: with no floor cell adjacent at hatch level, the only candidate is
 *       MineDown's §5 toggle arm — SET_OPEN under its own feet and fall through; hatch OPEN. (Any
 *       adjacent approach cell lets Descend's clearance fold preempt the arm, one move cheaper —
 *       live-verified.)</li>
 *   <li><b>closeparkour</b> — the only takeoff cell for a flat 2-gap sprint jump is an OPEN panel over a
 *       void slot: the arriving move folds SET_CLOSED ({@code requireFloorOrToggle}), the bot stands the
 *       plate and jumps from its 3/16 takeoff row; hatch CLOSED.</li>
 *   <li><b>sandwich</b> — the owner's needle (§9-3b): closed-TOP in the feet cell AND closed-BOTTOM in
 *       the head cell of ONE column, facings opposite + perpendicular (N / S): TWO SET_OPENs, then the
 *       bot threads the live 10/16 slot between opposite-wall panels; both hatches OPEN.</li>
 *   <li><b>exttoggle</b> — the anti-trick property: same geometry as hallwayopen, but once the bot's own
 *       SET_OPEN lands (and while its body is still fully west of the hatch cell) the COURSE flips the
 *       hatch CLOSED from the tick handler, exactly once. The {@code PhaseRunner} pre-pass re-validates
 *       against the live block and must simply re-issue the toggle next tick — no timers, no stuck
 *       state; expect the flip observed, a re-open observed after it, and the crossing completed.</li>
 * </ol>
 *
 * <p><b>Config (scripts/trapdoor/orebit.properties).</b> {@code doors.toggle=true} (the arc under test),
 * {@code mining.canMine=true} (MineDown's toggle arm sits behind the movement's {@code canBreak} top
 * gate; the trapdoors themselves stay unminable via the DEFAULT protected-blocks list, which the toggle
 * path bypasses by design), {@code placement.canPlace=false} (no bridging around), {@code
 * pathing.async=false} (deterministic). Mining stone barehanded (~160 ticks) never outbids a 6-cost
 * toggle, so the corridors stay honest without protecting the course fabric.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.trapdoor}
 * is set. Common, version-portable source (trapdoor state properties are drift-free 1.17.1 → 26.2).
 */
public final class TrapdoorCourse {

    private TrapdoorCourse() {}

    private static final String RESULT_FILE = "orebit-trapdoor-result.properties";
    private static final String TRACE_FILE = "orebit-trapdoor-trace.txt";

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
        if (System.getProperty("orebit.trapdoor") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/trapdoor] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        HALLWAY_OPEN,   // closed-TOP plate bisects the feet cell -> SET_OPEN and pass
        HALLWAY_CLOSE,  // open panel across the corridor -> SET_CLOSED and stand the plate node
        POCKET,         // flush sunken hatch -> step down in, same-level jump arm out, NO toggle
        HATCH_DROP,     // stand on a flush hatch, MineDown toggle arm -> fall through to the goal below
        CLOSE_PARKOUR,  // SET_CLOSED the only takeoff cell, then a flat 2-gap sprint jump from the plate
        SANDWICH,       // the §9-3b needle: TWO SET_OPENs, thread the 10/16 slot
        EXT_TOGGLE      // hallwayopen + a one-shot external re-CLOSE after the bot's own toggle lands
    }

    /** One trapdoor challenge: a kind + its base grid cell, with start/goal geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int baseX, baseZ;
        final int zc;               // centre-line Z of the tile
        final int hatchX;           // X of the interesting (hatch) column

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;
        int minFloorY;              // a fall this far below the tile = off the course
        final List<BlockPos> tds = new ArrayList<>();      // the tile's trapdoor cells…
        final List<Boolean> expectOpen = new ArrayList<>(); // …and the OPEN state each must END in

        Trial(String name, Kind kind, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 6;
            this.hatchX = baseX + (kind == Kind.CLOSE_PARKOUR ? 5 : 6);
            this.minFloorY = kind == Kind.CLOSE_PARKOUR ? Y0 - 2 : Y0 - 6;
            this.startX = baseX - 1.5;
            this.startY = Y0 + 1;
            this.startZ = zc + 0.5;
            this.startYaw = yaw(1, 0); // face +X down the corridor
            switch (kind) {
                case HALLWAY_OPEN:
                case EXT_TOGGLE: {
                    this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
                    td(hatchX, Y0 + 1, true);   // the feet-cell plate must END open
                    break;
                }
                case HALLWAY_CLOSE: {
                    this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
                    td(hatchX, Y0 + 1, false);  // the blocking panel must END closed
                    break;
                }
                case POCKET: {
                    this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
                    td(hatchX, Y0, false);      // the sunken hatch is never toggled
                    break;
                }
                case HATCH_DROP: {
                    // The bot STARTS standing on the hatch — no approach corridor, see buildTile.
                    this.startX = hatchX + 0.5;
                    this.startY = Y0 + 2;
                    this.startZ = zc + 0.5;
                    this.goal = new BlockPos(hatchX, Y0 + 1, zc); // feet cell of the shaft, directly below
                    td(hatchX, Y0 + 1, true);   // the floor hatch must END open
                    break;
                }
                case CLOSE_PARKOUR: {
                    this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
                    td(hatchX, Y0, false);      // the takeoff panel must END closed (the plate it jumped from)
                    break;
                }
                default: { // SANDWICH
                    this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
                    td(hatchX, Y0 + 1, true);   // BOTH stacked hatches must END open
                    td(hatchX, Y0 + 2, true);
                    break;
                }
            }
        }

        private void td(int x, int y, boolean endsOpen) {
            tds.add(new BlockPos(x, y, zc));
            expectOpen.add(endsOpen);
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
        String prevTdStates = "";
        int passed, failed;

        // Per-trial tile state (reset in enter()).
        double pocketDip;           // POCKET: lowest feet-Y recorded while inside the pocket column
        boolean flipped;            // EXT_TOGGLE: the one-shot external re-close fired
        boolean reopened;           // EXT_TOGGLE: the hatch read OPEN again after the flip

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("hallwayopen",  Kind.HALLWAY_OPEN);
            add("hallwayclose", Kind.HALLWAY_CLOSE);
            add("pocket",       Kind.POCKET);
            add("hatchdrop",    Kind.HATCH_DROP);
            add("closeparkour", Kind.CLOSE_PARKOUR);
            add("sandwich",     Kind.SANDWICH);
            add("exttoggle",    Kind.EXT_TOGGLE);
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
            if (Boolean.getBoolean("orebit.trapdoor.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitTrapdoor:owner".getBytes(StandardCharsets.UTF_8)),
                        "Trapdoor"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit trapdoor course trace  (T <trial> <tick> x y z | spd vy | onGround | hp | td=<O/C per hatch> | move)\n");
                trace.write("legend: td = live OPEN state of the tile's trapdoor cells in Trial order "
                        + "(O=open C=closed); EXTFLIP marks the exttoggle tile's one-shot external re-close\n\n");
                OrebitCommon.LOGGER.info("[Orebit/trapdoor] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/trapdoor] setup threw", t);
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
            pocketDip = Double.MAX_VALUE;
            flipped = false;
            reopened = false;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            prevTdStates = "";
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d)\n",
                        tr.name, tr.kind, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ()));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                if (++settleTicks < target) return;
                settling = false;
                bot.comeTo(tr.goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }

            attemptTicks++;

            // EXT_TOGGLE: the one-shot external re-close. State-based, no timers — fires the first tick the
            // hatch reads OPEN (i.e. the bot's own SET_OPEN has landed) while the bot's body is still fully
            // WEST of the hatch cell (never closes a plate through the bot itself). The PhaseRunner's per-tick
            // re-validation must then simply re-issue the toggle.
            if (tr.kind == Kind.EXT_TOGGLE) {
                BlockPos hatch = tr.tds.get(0);
                boolean open = openAt(hatch);
                if (!flipped) {
                    if (open && bot.getX() < tr.hatchX - 0.35) {
                        level.setBlockAndUpdate(hatch,
                                level.getBlockState(hatch).setValue(BlockStateProperties.OPEN, false));
                        flipped = true;
                        try {
                            trace.write(String.format(Locale.ROOT,
                                    "  EXTFLIP tick=%d bot=(%.2f,%.2f,%.2f) — hatch externally re-CLOSED\n",
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

            // POCKET / HATCH_DROP: record the lowest feet-Y while horizontally inside the hatch column —
            // the position-based proof the crossing went THROUGH the hatch (the pocket step-down; the
            // drop through the opened floor plate), not around it.
            if ((tr.kind == Kind.POCKET || tr.kind == Kind.HATCH_DROP)
                    && Math.abs(bot.getX() - (tr.hatchX + 0.5)) < 0.6
                    && Math.abs(bot.getZ() - (tr.zc + 0.5)) < 0.6
                    && bot.getY() < pocketDip) {
                pocketDip = bot.getY();
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
                record(tr, "FAIL", tr.kind == Kind.CLOSE_PARKOUR ? "fell through the gap" : "fell off the tile");
                return;
            }
            // Candidate PASS: the driver reverted to STAY (exact-tolerance arrival) near the goal cell —
            // then the tile's WORLD-STATE assertions decide (arriving without operating the hatch is a FAIL).
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

        /** {@code null} when every end-of-trial assertion holds; else a short reason. */
        String worldStateFailure(Trial tr) {
            for (int i = 0; i < tr.tds.size(); i++) {
                boolean open = openAt(tr.tds.get(i));
                if (open != tr.expectOpen.get(i)) {
                    return "trapdoor " + tr.tds.get(i).toShortString() + " is "
                            + (open ? "OPEN" : "CLOSED") + ", expected "
                            + (tr.expectOpen.get(i) ? "OPEN" : "CLOSED");
                }
            }
            if (tr.kind == Kind.POCKET && pocketDip > Y0 + 0.7) {
                return "the feet never dipped into the pocket (min " + fmt(pocketDip)
                        + ") — crossed without the step-down";
            }
            if (tr.kind == Kind.HATCH_DROP && pocketDip > Y0 + 1.1) {
                return "the feet never dropped through the hatch column (min " + fmt(pocketDip) + ")";
            }
            if (tr.kind == Kind.EXT_TOGGLE) {
                if (!flipped) return "the external flip never armed (bot crossed before its own toggle landed?)";
                if (!reopened) return "the hatch was never re-opened after the external flip";
            }
            return null;
        }

        boolean openAt(BlockPos pos) {
            BlockState s = level.getBlockState(pos);
            return s.getBlock() instanceof TrapDoorBlock && s.getValue(BlockStateProperties.OPEN);
        }

        String tdStates(Trial tr) {
            StringBuilder sb = new StringBuilder(tr.tds.size());
            for (BlockPos p : tr.tds) sb.append(openAt(p) ? 'O' : 'C');
            return sb.toString();
        }

        void trace(Trial tr) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            String tds = tdStates(tr);
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
                if (!tds.equals(prevTdStates)) {
                    trace.write(String.format(Locale.ROOT,
                            "  TD tick=%d states=%s bot=(%.2f,%.2f,%.2f)\n", attemptTicks, tds, x, y, z));
                    prevTdStates = tds;
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-12s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %.1f | td=%s | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y,
                        grnd ? 1 : 0, bot.getHealth(), tds, move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            String extra = "";
            if (tr.kind == Kind.POCKET || tr.kind == Kind.HATCH_DROP) {
                extra = " dip=" + (pocketDip == Double.MAX_VALUE ? "none" : fmt(pocketDip));
            } else if (tr.kind == Kind.EXT_TOGGLE) {
                extra = " flipped=" + flipped + " reopened=" + reopened;
            }
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.1f,%.1f,%.1f) td=%s%s lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), tdStates(tr), extra, bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/trapdoor] {} -> {} ({}) closest={} ticks={} td={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, tdStates(tr));
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
            switch (tr.kind) {
                case HALLWAY_OPEN:
                case EXT_TOGGLE: {
                    // 2-tall boxed corridor; a closed-TOP plate bisects the feet cell of the hatch column.
                    // Facing NORTH -> the opened panel hugs the SOUTH wall strip (perpendicular, a guide rail).
                    stripX(bx - 3, bx - 1, Y0, zc);
                    box(bx, bx + 12, Y0, Y0 + 3, zc - 1, zc + 1);
                    carve(bx, bx + 12, Y0 + 1, Y0 + 2, zc);
                    set(tr.hatchX, Y0 + 1, zc, td(Direction.NORTH, false, Half.TOP));
                    break;
                }
                case HALLWAY_CLOSE: {
                    // 3-tall boxed corridor (the plate-node step-off needs the third body row); an OPEN
                    // bottom-half panel facing EAST hugs the WEST strip — squarely across eastward travel.
                    stripX(bx - 3, bx - 1, Y0, zc);
                    box(bx, bx + 12, Y0, Y0 + 4, zc - 1, zc + 1);
                    carve(bx, bx + 12, Y0 + 1, Y0 + 3, zc);
                    set(tr.hatchX, Y0 + 1, zc, td(Direction.EAST, true, Half.BOTTOM));
                    break;
                }
                case POCKET: {
                    // 3-tall boxed corridor (jump headroom for the same-level jump arm); the FLOOR block of
                    // the hatch column swapped for a closed-BOTTOM hatch — a 13/16-deep sunken pocket.
                    stripX(bx - 3, bx - 1, Y0, zc);
                    box(bx, bx + 12, Y0, Y0 + 4, zc - 1, zc + 1);
                    carve(bx, bx + 12, Y0 + 1, Y0 + 3, zc);
                    set(tr.hatchX, Y0, zc, td(Direction.NORTH, false, Half.BOTTOM));
                    break;
                }
                case HATCH_DROP: {
                    // A solid stone pillar with a 1×1 chimney whose floor is a closed-TOP oak hatch over a
                    // stone landing; the bot STARTS standing on the plate and the goal is the shaft feet
                    // cell directly below it. With NO floor cell adjacent at hatch level, the only
                    // candidate is MineDown's §5 toggle arm: SET_OPEN under its own feet and fall the one
                    // block through. (Live-verified twice that any adjacent approach cell lets Descend's
                    // clearance fold preempt the arm — one whole move cheaper and equally §5/§6-legal —
                    // so a corridor-approach variant never exercises MineDown.)
                    box(bx + 5, bx + 7, Y0, Y0 + 4, zc - 1, zc + 1);
                    set(tr.hatchX, Y0 + 1, zc, td(Direction.NORTH, false, Half.TOP));
                    set(tr.hatchX, Y0 + 2, zc, AIR);
                    set(tr.hatchX, Y0 + 3, zc, AIR);
                    break;
                }
                case CLOSE_PARKOUR: {
                    // Boxed runway with THREE body rows (arc prism); floor row Y0: approach stone, the OPEN
                    // takeoff panel at hatchX (facing EAST -> panel on its west strip), a 2-cell void slot,
                    // stone landing + goal strip. The only route is SET_CLOSED + a flat 2-gap sprint jump
                    // from the 3/16 plate row (ParkourEnvelope: flat max 2 from topY=3; 3 refused).
                    stripX(bx - 3, bx - 1, Y0, zc);
                    box(bx, bx + 12, Y0, Y0 + 4, zc - 1, zc + 1);
                    carve(bx, bx + 12, Y0 + 1, Y0 + 3, zc);
                    // Approach ceiling one lower (2-tall): HEADROOM_JUMP refuses every full-block takeoff
                    // WEST of the hatch — without this the planner (correctly) sprint-jumped a flat 3-gap
                    // from the approach cell clean OVER the open panel (arc peak feet ~+1.2, panel tops at
                    // +1), bypassing the toggle entirely (live-verified, run 1). The plate keeps its three
                    // body rows from hatchX east for its own takeoff + arc.
                    for (int x = bx; x < tr.hatchX; x++) set(x, Y0 + 3, zc, STONE);
                    set(tr.hatchX, Y0, zc, td(Direction.EAST, true, Half.BOTTOM));
                    set(tr.hatchX + 1, Y0, zc, AIR);
                    set(tr.hatchX + 2, Y0, zc, AIR);
                    break;
                }
                default: { // SANDWICH
                    // 2-tall boxed corridor; ONE column holds closed-TOP (feet, facing NORTH -> opens south)
                    // AND closed-BOTTOM (head, facing SOUTH -> opens north): opposite + perpendicular, the
                    // live 10/16 needle slot once both are open.
                    stripX(bx - 3, bx - 1, Y0, zc);
                    box(bx, bx + 12, Y0, Y0 + 3, zc - 1, zc + 1);
                    carve(bx, bx + 12, Y0 + 1, Y0 + 2, zc);
                    set(tr.hatchX, Y0 + 1, zc, td(Direction.NORTH, false, Half.TOP));
                    set(tr.hatchX, Y0 + 2, zc, td(Direction.SOUTH, false, Half.BOTTOM));
                    break;
                }
            }
        }

        /** Dump the ACTUALLY-PLACED trapdoor BlockStates (facing/half/open) — the hinge-side double-check
         *  (the open panel hugs the wall OPPOSITE {@code facing}; transposing it silently inverts a tile). */
        void probeTile(Trial tr) {
            for (BlockPos p : tr.tds) {
                BlockState s = level.getBlockState(p);
                OrebitCommon.LOGGER.info("[Orebit/trapdoor] {} probe {} = {}", tr.name, p.toShortString(), s);
                try {
                    trace.write(String.format(Locale.ROOT, "  PROBE %s = %s\n", p.toShortString(), s));
                } catch (IOException ignored) { }
            }
        }

        // ---- placement primitives ------------------------------------------------------------------------

        static BlockState td(Direction facing, boolean open, Half half) {
            return Blocks.OAK_TRAPDOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.OPEN, open)
                    .setValue(BlockStateProperties.HALF, half)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
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
                OrebitCommon.LOGGER.error("[Orebit/trapdoor] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/trapdoor] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-trapdoor-exit");
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
