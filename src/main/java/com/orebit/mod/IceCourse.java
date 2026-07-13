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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Headless GROUND-MOVEMENT diagnostic harness (a sibling of {@link ParkourCourse} / {@link SwimCourse}, armed
 * by its own {@code -Dorebit.ice} flag). It builds a grid of BLUE-ICE (lowest-friction) paths — 1 block wide,
 * flanked by lethal LAVA-CURTAIN walls — floating high over a flat world, and drives the bot across each,
 * recording pass/fail plus a per-tick trajectory. It reproduces the near-frictionless-turn OVERSHOOT: the
 * ground moves ({@code Traverse}/{@code Descend}/{@code Diagonal}) drive full-forward via a look-ahead pursuit
 * point aimed PAST the waypoint, so on blue ice the carried-forward momentum coasts the bot off a 1-wide path
 * at a corner — here straight into the lava (clip = die), an unambiguous FAIL.
 *
 * <p><b>Why a bespoke course.</b> The overshoot is an EXECUTOR/steering pathology (the physical slide misses
 * the corner), invisible to a route-level pass/fail. Isolating each shape on a 1-wide ice path with a lethal
 * hazard exactly where momentum overshoots makes each a reproducible experiment; the trajectory dump captures
 * the X/Z sliding past the turn cell into the lava.
 *
 * <p><b>The lava-curtain wall (owner-specified, spread-safe).</b> A bare floating lava source spreads in ALL
 * horizontal directions on its first expansion tick (a cross that would flood the ice) BEFORE the flow blocks
 * start draining straight down. So the ice path is ROOFED with stone and the lava SOURCES sit at ceiling level
 * flanking that roof, with air on both sides of the ice at body level. Cross-section perpendicular to the path
 * (A=air, L=lava source, S=stone, I=ice):
 * <pre>
 *   A L S L A   ceiling row: STONE directly over the ice blocks the sources' first-tick inward spread; L flank it
 *   A A A A A   body level:  lava drains straight DOWN the side columns = the lethal curtain the bot clips
 *   A A A A A   body level:  ~2 blocks headroom over the ice
 *   A A I A A   ICE floor at centre; AIR both sides so lava drains into those side columns (never onto the ice)
 * </pre>
 * With the ice at world-Y {@link #Y0} (feet at {@code Y0+1}), the stone ceiling and the flanking lava sources
 * sit at {@code Y0+3}; the lava drains down the flank columns through the bot's body cells ({@code Y0+1},
 * {@code Y0+2}) and keeps falling into the void below (no floor at ice level in the flank columns → it never
 * pools onto the path). Isolated overshoot cells (the diagonal / descend turns, where a contiguous flank line
 * can't provide the barrier) instead use a source BOXED in stone on all four horizontal sides at {@code Y0+3}
 * — the source can then only drain straight down, a bullet-proof vertical curtain with zero horizontal spread.
 *
 * <p><b>Config (scripts/ice/orebit.properties).</b> {@code survival.takesDamage=true} (lava kills → FAIL),
 * {@code survival.needsBreath=false}, {@code placement.canPlace=false} + {@code mining.canMine=false} (the bot
 * can't bridge/dig around — the ice path is the ONLY route; lava is damaging → the planner avoids every
 * off-path cell), {@code pathing.async=false} (deterministic). The harness bakes in NO fix and asserts NO
 * mechanism; it only reproduces and records, exactly like its siblings.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.ice} is set.
 * Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class IceCourse {

    private IceCourse() {}

    private static final String RESULT_FILE = "orebit-ice-result.properties";
    private static final String TRACE_FILE = "orebit-ice-trace.txt";

    /** Ice-floor / platform Y (feet stand at {@code Y0+1}); high enough that a curtain drains into deep void. */
    private static final int Y0 = 150;
    /** Blocks above the ice floor at which the stone ROOF and the flanking lava SOURCES sit (2 body cells
     *  of headroom: {@code Y0+1}, {@code Y0+2}). */
    private static final int ROOF = 3;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    private static final int COLS = 2;
    private static final int STRIDE = 30; // grid cell size (> the longest tile span so tiles never touch)

    /** Ticks to let the whole starting area gen + nav-build before the first goto. */
    private static final int WARMUP_TICKS = 160;
    /** Ticks after each teleport before the goto — ALSO the window the just-placed lava curtains need to drain
     *  down through the body cells (overworld lava steps ~1 cell / 30 t; 100 t clears the ~3-cell fall). */
    private static final int SETTLE_TICKS = 100;
    private static final int NAV_RETRY_WINDOW = 60;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). An ice crossing is fast; a stuck/looping bot resolves well within it. */
    private static final int ATTEMPT_BUDGET = 400;

    private static final BlockState ICE = Blocks.BLUE_ICE.defaultBlockState(); // lowest friction → most slide
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();     // LEVEL 0 = a source block

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.ice") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/ice] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        STRAIGHT, // long straight blue-ice run, lava walls both sides, NO turn — the CONTROL (should PASS) and
                  //   the sanity check that the walls don't flood the path (a death here = lava on the ice)
        TURN,     // straight blue-ice run then a 90° turn; the straight-ahead overshoot cell is the lava curtain
                  //   → full-forward Traverse momentum should slide the bot past the corner into the lava
        DIAG,     // a diagonal-in / diagonal-out "V" ice path (exercises Diagonal); the apex's straight-ahead
                  //   diagonal-continuation cell is a boxed lava curtain (the overshoot cell)
        DESCEND   // a descending blue-ice staircase (exercises Descend) that turns 90° at the bottom; the
                  //   straight-ahead cell past the bottom corner is a boxed lava curtain (the overshoot cell)
    }

    /** One ice challenge: a kind + its base grid cell, with start/goal geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int baseX, baseZ;
        final int zc;               // centre-line Z of the tile

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;
        int minFloorY;              // lowest ice-floor Y in this trial (a fall this far below = off the path)

        Trial(String name, Kind kind, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 6;
            this.minFloorY = Y0;
            int bx = baseX;
            switch (kind) {
                case STRAIGHT: {
                    this.startX = bx + 0.5; this.startZ = zc + 0.5; this.startY = Y0 + 1;
                    this.startYaw = yaw(1, 0);
                    this.goal = new BlockPos(bx + STRAIGHT_LEN + 1, Y0 + 1, zc);
                    break;
                }
                case TURN: {
                    this.startX = bx + 0.5; this.startZ = zc + 0.5; this.startY = Y0 + 1;
                    this.startYaw = yaw(1, 0);                 // run +X into the corner
                    this.goal = new BlockPos(bx + 8, Y0 + 1, zc + 6);
                    break;
                }
                case DIAG: {
                    this.startX = bx + 0.5; this.startZ = zc + 0.5; this.startY = Y0 + 1;
                    this.startYaw = yaw(1, 1);                 // run +X+Z along the first diagonal leg
                    this.goal = new BlockPos(bx + 8, Y0 + 1, zc);
                    break;
                }
                default: { // DESCEND
                    this.minFloorY = Y0 - 5;
                    this.startX = bx + 0.5; this.startZ = zc + 0.5; this.startY = Y0 + 1;
                    this.startYaw = yaw(1, 0);                 // run +X down the staircase into the bottom corner
                    this.goal = new BlockPos(bx + 6, (Y0 - 5) + 1, zc + 5);
                    break;
                }
            }
        }

        static float yaw(int dx, int dz) { return (float) Math.toDegrees(Math.atan2(-dx, dz)); }
    }

    /** Straight-run ice length (cells) — long enough to build real momentum before the goal / a turn. */
    private static final int STRAIGHT_LEN = 12;

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
        boolean touchedLava;        // the bot entered lava at least once this trial (colours the FAIL reason)
        double prevX, prevZ;
        String prevMove = "";
        int prevSegToX = Integer.MIN_VALUE, prevSegToY, prevSegToZ;
        int passed, failed;

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("icestraight", Kind.STRAIGHT);
            add("iceturn",     Kind.TURN);
            add("icediag",     Kind.DIAG);
            add("icedescend",  Kind.DESCEND);
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
            if (Boolean.getBoolean("orebit.ice.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitIce:owner".getBytes(StandardCharsets.UTF_8)),
                        "Ice"));
                owner.setPos(first.startX, Y0 + 1, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit ice course trace  (T <trial> <tick> x y z | spd vy | onGround inLava | hp | move)\n");
                trace.write("legend: spd = position-delta horizontal speed (b/t); a slide off the ice into the "
                        + "lava wall shows X or Z overshooting the turn cell\n\n");
                OrebitCommon.LOGGER.info("[Orebit/ice] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/ice] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr);
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
            touchedLava = false;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
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
                bot.comeTo(tr.goal);
                return;
            }

            attemptTicks++;
            boolean inLava = bot.isInLava();
            if (inLava) touchedLava = true;
            trace(tr);

            if (!bot.isAlive()) {
                record(tr, "FAIL", touchedLava ? "died (slid into lava)" : "died");
                return;
            }
            // On the ice course every off-path cell is a lava-curtain wall; the ONLY safe route is the 1-wide
            // ice (icestraight proves a centred crossing never touches lava). So ANY lava contact = the bot slid
            // off the path into the hazard wall — the overshoot failure this harness reproduces — even when
            // peaceful fast-regen would then out-heal the glancing burn and let it survive to the goal.
            if (inLava) {
                record(tr, "FAIL", "slid off ice into lava wall");
                return;
            }
            double dx = bot.getX() - (tr.goal.getX() + 0.5);
            double dy = bot.getY() - tr.goal.getY();
            double dz = bot.getZ() - (tr.goal.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            // A slide off the ice into a flank/overshoot curtain drops the bot into the void below the wall — a
            // fast, unambiguous FAIL (it will die anyway; catch it here so the verdict is prompt, and colour it
            // with whether it touched lava on the way down).
            if (bot.getY() < tr.minFloorY - 6) {
                record(tr, "FAIL", touchedLava ? "fell off ice into lava" : "fell off ice");
                return;
            }
            // PASS: the driver reverted to STAY (comeTo drops to STAY only on TRUE arrival) near the goal cell.
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.6) {
                record(tr, "PASS", "reached goal");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(tr.goal);
                    return;
                }
                record(tr, "FAIL", "nav gave up (no route offered)");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", "timeout");
            }
        }

        void trace(Trial tr) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            boolean inLava = bot.isInLava();
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
                        "T %-12s %3d  %.3f %.3f %.3f | %.4f %.4f | %d %d | %.1f | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y,
                        grnd ? 1 : 0, inLava ? 1 : 0, bot.getHealth(), move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.1f,%.1f,%.1f) hp=%.1f touchedLava=%b lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), bot.getHealth(), touchedLava, bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/ice] {} -> {} ({}) closest={} ticks={} finalY={} touchedLava={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, String.format(Locale.ROOT, "%.2f", bot.getY()), touchedLava);
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            switch (tr.kind) {
                case STRAIGHT: buildStraight(tr); break;
                case TURN:     buildTurn(tr);     break;
                case DIAG:     buildDiag(tr);     break;
                default:       buildDescend(tr);  break;
            }
        }

        /** icestraight: a straight +X blue-ice tunnel (ice + stone roof), lava curtains flanking both sides, a
         *  safe 1-wide stone start strip at the west and a stone goal strip at the east. No turn → the bot
         *  should cross it centred and PASS; a death here means the walls flooded the ice. */
        void buildStraight(Trial tr) {
            int bx = tr.baseX, zc = tr.zc, y = Y0;
            stripX(bx - 2, bx, y, zc);                                 // safe start strip (1-wide, z=zc)
            for (int i = 1; i <= STRAIGHT_LEN; i++) {
                int x = bx + i;
                iceRoofed(x, y, zc);
                lavaCurtain(x, y, zc - 1);
                lavaCurtain(x, y, zc + 1);
            }
            stripX(bx + STRAIGHT_LEN + 1, bx + STRAIGHT_LEN + 3, y, zc); // safe goal strip
        }

        /** iceturn: a long +X blue-ice tunnel to a corner, then a 90° turn to a +Z tunnel and a goal strip. The
         *  cell STRAIGHT AHEAD of the corner (+X, one past it) is a lava curtain — the Traverse overshoot cell.
         *  Lava flanks both legs; the flank at the inside corner also blocks the diagonal corner-cut, forcing
         *  the cardinal +X→+Z route. */
        void buildTurn(Trial tr) {
            int bx = tr.baseX, zc = tr.zc, y = Y0;
            stripX(bx - 2, bx, y, zc);                                 // safe start strip
            // +X leg (corner at bx+8).
            for (int x = bx + 1; x <= bx + 8; x++) iceRoofed(x, y, zc);
            // +Z leg.
            for (int z = zc + 1; z <= zc + 5; z++) iceRoofed(bx + 8, y, z);
            // Goal strip along +Z past the leg end (bot arrives moving +Z → decelerates onto stone).
            for (int z = zc + 6; z <= zc + 8; z++) plat(bx + 8, y, z);
            // +X-leg flanks (zc+1 side stops at bx+7 — (bx+8,zc+1) is the +Z-leg's first ice cell).
            for (int x = bx + 1; x <= bx + 8; x++) lavaCurtain(x, y, zc - 1);
            for (int x = bx + 1; x <= bx + 7; x++) lavaCurtain(x, y, zc + 1);
            // +Z-leg flanks; the bx+9 side includes (bx+9,zc) = the straight-ahead OVERSHOOT cell of the corner.
            for (int z = zc + 1; z <= zc + 5; z++) lavaCurtain(bx + 7, y, z);
            for (int z = zc;     z <= zc + 5; z++) lavaCurtain(bx + 9, y, z);
        }

        /** icediag: a "V" of two diagonal blue-ice legs (+X+Z then +X-Z) that exercises the Diagonal move. The
         *  sides are open void (a sideways slide falls), and the apex's straight-ahead diagonal-continuation
         *  cell is a BOXED lava curtain — the Diagonal cruise overshoots the apex into it. (Diagonal moves need
         *  clear corner columns, so a flanked/roofed tunnel is impossible here; void sides keep the ice the only
         *  route and the boxed source can't spread onto the diagonal corners.) */
        void buildDiag(Trial tr) {
            int bx = tr.baseX, zc = tr.zc, y = Y0;
            // 2×2 safe start pad behind the first diagonal step.
            plat(bx - 1, y, zc - 1); plat(bx, y, zc - 1); plat(bx - 1, y, zc); plat(bx, y, zc);
            // Leg A: +X+Z to the apex (bx+4, zc+4).
            for (int i = 1; i <= 4; i++) ice(bx + i, y, zc + i);
            // Leg B: +X-Z down to the goal pad area.
            ice(bx + 5, y, zc + 3); ice(bx + 6, y, zc + 2); ice(bx + 7, y, zc + 1);
            // 2×2 safe goal pad (approached moving +X-Z).
            plat(bx + 8, y, zc); plat(bx + 9, y, zc); plat(bx + 8, y, zc - 1); plat(bx + 9, y, zc - 1);
            // Overshoot: the +X+Z continuation of the apex — a boxed lava curtain (bullet-proof no-spread).
            boxedLava(bx + 5, y, zc + 5);
        }

        /** icedescend: a descending +X blue-ice staircase (exercises Descend) to a flat bottom corner, then a
         *  90° turn to +Z and a goal pad. The cell straight ahead (+X) past the bottom corner is a boxed lava
         *  curtain — the descended momentum overshoots the corner into it. Open void sides keep the ice the only
         *  route. */
        void buildDescend(Trial tr) {
            int bx = tr.baseX, zc = tr.zc;
            stripX(bx - 2, bx, Y0, zc);                                // safe start strip at the top
            // Descend: each step +1 X and -1 Y (5 steps → bottom at Y0-5).
            for (int s = 1; s <= 5; s++) ice(bx + s, Y0 - s, zc);
            int yb = Y0 - 5;
            ice(bx + 6, yb, zc);                                       // flat bottom corner
            for (int z = zc + 1; z <= zc + 4; z++) ice(bx + 6, yb, z); // +Z leg (flat)
            for (int z = zc + 5; z <= zc + 6; z++) plat(bx + 6, yb, z);// safe goal pad
            // Overshoot: straight ahead (+X) past the bottom corner — a boxed lava curtain.
            boxedLava(bx + 7, yb, zc);
        }

        // ---- placement primitives ------------------------------------------------------------------------

        /** A plain (unroofed) blue-ice floor cell — feet at {@code y+1}, open above. Used where the sides are
         *  void (diagonal/descend), so no roof is needed to contain lava. */
        void ice(int x, int y, int z) {
            set(x, y, z, ICE);
        }

        /** A ROOFED blue-ice floor cell: ice at {@code y}, a stone ceiling at {@code y+ROOF} (leaving 2 body
         *  cells of air), so a flanking lava source at {@code y+ROOF} can't spread inward over the path. */
        void iceRoofed(int x, int y, int z) {
            set(x, y, z, ICE);
            set(x, y + ROOF, z, STONE);
        }

        /** A lava-curtain flank: a SOURCE at ceiling level ({@code y+ROOF}) with air below it, so vanilla drains
         *  it straight down through the body cells ({@code y+1}, {@code y+2}) and on into the void — the lethal
         *  wall the bot clips. Relies on the adjacent stone roof (see {@link #iceRoofed}) to block the source's
         *  first-tick inward spread. */
        void lavaCurtain(int x, int y, int z) {
            set(x, y + ROOF, z, LAVA);
        }

        /** An ISOLATED lethal curtain: a lava SOURCE at {@code y+ROOF} boxed by stone on all four horizontal
         *  sides at that level, so it can ONLY drain straight down (zero horizontal spread) — used at the
         *  diagonal/descend overshoot cells where no contiguous roof provides the barrier. The box is only at
         *  {@code y+ROOF}; the body cells below stay open lava so the bot can slide into it. */
        void boxedLava(int x, int y, int z) {
            int yc = y + ROOF;
            set(x - 1, yc, z, STONE);
            set(x + 1, yc, z, STONE);
            set(x, yc, z - 1, STONE);
            set(x, yc, z + 1, STONE);
            set(x, yc, z, LAVA);
        }

        /** A safe stone floor cell (friction — the bot can stop on it). */
        void plat(int x, int y, int z) {
            set(x, y, z, STONE);
        }

        /** A 1-wide (z=zc) stone strip along +X from {@code x0}..{@code x1} — a safe start/goal pad with no
         *  perpendicular cell for a flank curtain to pool on. */
        void stripX(int x0, int x1, int y, int zc) {
            for (int x = x0; x <= x1; x++) plat(x, y, zc);
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
                OrebitCommon.LOGGER.error("[Orebit/ice] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/ice] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-ice-exit");
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
