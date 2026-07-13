package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Headless REAL-WORLD REPLAY harness — a one-scenario sibling of {@link SwimCourse}, armed by its own
 * {@code -Dorebit.replay} flag. Unlike {@code SwimCourse} (which BUILDS a synthetic grid of water tanks over
 * a flat world), this harness builds NOTHING: it loads the owner's hand-built "Swims" world (already copied
 * into the server's {@code world/} directory by {@code scripts/run-replay.ps1}) and reproduces the exact
 * in-game sequence the owner reports fails:
 *
 * <pre>
 *   /bot stay
 *   /tp Dev_bot 14 -56 1
 *   /bot goto -3 -56 1
 * </pre>
 *
 * <p>The owner reports the bot CONSISTENTLY gets ejected from the water and never reaches the goal in the real
 * world, even though a synthetic reconstruction of the same maze PASSES in {@link SwimCourse}. This harness
 * exists to reproduce that on the REAL blocks and capture the trace (per-tick position/velocity + water state,
 * MOVE/POSE transitions, the computed plan waypoint list) so the divergence can be diagnosed from data.
 *
 * <p>It bakes in NO fix and asserts NO mechanism; it only reproduces and records, exactly like
 * {@link SwimCourse}. The EJECTION guard: once the bot has first entered water, any run of {@code >= 5}
 * consecutive ticks where it is NOT in water while still {@code > 2.5} blocks from the goal is recorded as a
 * FAIL ("ejected") — the reported pathology.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.replay} is set.
 */
public final class WorldReplay {

    private WorldReplay() {}

    private static final String RESULT_FILE = "orebit-replay-result.properties";
    private static final String TRACE_FILE = "orebit-replay-trace.txt";

    /** Mirrors {@code /tp Dev_bot 14 -56 1}: the bot's start feet position. */
    private static final double START_X = 14.0, START_Y = -56.0, START_Z = 1.0;
    /** Mirrors {@code /bot goto -3 -56 1}: the exact target block. */
    private static final BlockPos GOAL = new BlockPos(-3, -56, 1);
    /** goto's exact arrival tolerance (see {@code GotoCommand}). */
    private static final double ARRIVE_DIST = 0.75, ARRIVE_Y = 0.75;
    private static final int GOAL_TOL = 0;

    /** Ticks to let the world settle (nav grid build around start/goal) before issuing the goto. */
    private static final int WARMUP_TICKS = 120;
    /** Attempt budget after the goto is issued (~40 s — a working-but-slow swim must not false-timeout). */
    private static final int ATTEMPT_BUDGET = 800;
    /** Ejection guard: >= this many consecutive out-of-water ticks (post first-entry, while far) = FAIL. */
    private static final int EJECT_TICKS = 5;
    private static final double EJECT_DIST = 2.5;
    /** Retry a fresh goto a few times if nav gives up right after the teleport (grid not yet built). */
    private static final int NAV_RETRY_WINDOW = 40, MAX_NAV_RETRY = 5;

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.replay") == null) {
            return;
        }
        Replay replay = new Replay();
        events.onServerStarted(replay::start);
        events.onWorldTickEnd(replay::tick);
        OrebitCommon.LOGGER.info("[Orebit/replay] armed: start=({},{},{}) goal=({},{},{})",
                START_X, START_Y, START_Z, GOAL.getX(), GOAL.getY(), GOAL.getZ());
    }

    private static final class Replay {
        MinecraftServer server;
        ServerLevel level;
        FakePlayerEntity owner;
        AllyBotEntity bot;
        BufferedWriter trace;

        boolean done;
        boolean settling = true;
        int settleTicks;
        int attemptTicks;
        int navRetries;

        // trace / guard state
        double closest = Double.MAX_VALUE;
        double prevX, prevZ;
        boolean prevSwimming;
        String prevMove = "";
        BlockPathPlan prevPlan;
        boolean enteredWater;
        int outOfWaterRun;
        boolean dumpedLayout;

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.replay.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitReplay:owner".getBytes(StandardCharsets.UTF_8)),
                        "Replay"));
                owner.setPos(START_X, START_Y, START_Z);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit world-replay trace  (T <tick> x y z | vy spd | grnd inW subm swim spr | move)\n");
                trace.write("legend: grnd=onGround inW=inWater subm=isUnderWater swim=prone Pose.SWIMMING spr=sprinting\n");
                trace.write(String.format(Locale.ROOT,
                        "== replay : start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d)\n",
                        START_X, START_Y, START_Z, GOAL.getX(), GOAL.getY(), GOAL.getZ()));

                // /bot stay + /tp Dev_bot 14 -56 1
                bot.reviveIfDead();
                bot.setHealth(bot.getMaxHealth());
                bot.setMode(AllyBotEntity.Mode.STAY);
                bot.setPos(START_X, START_Y, START_Z);
                bot.setDeltaMovement(Vec3.ZERO);
                float yaw = yaw(GOAL.getX() - (int) START_X, GOAL.getZ() - (int) START_Z);
                bot.setYRot(yaw);
                bot.setYHeadRot(yaw);
                prevX = START_X;
                prevZ = START_Z;
                OrebitCommon.LOGGER.info("[Orebit/replay] ready; settling {} ticks then goto", WARMUP_TICKS);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/replay] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void tick(ServerLevel lvl) {
            if (done || bot == null || server == null || lvl != level) {
                return;
            }

            if (settling) {
                // Keep the bot pinned at the start while the nav grid builds (mirror /bot stay before goto).
                bot.setMode(AllyBotEntity.Mode.STAY);
                if (++settleTicks < WARMUP_TICKS) return;
                settling = false;
                dumpFailureLayout();   // one-time ground-truth block + bubble + nav dump around the eject spot
                bot.comeTo(GOAL, ARRIVE_DIST, ARRIVE_Y, GOAL_TOL);   // /bot goto -3 -56 1
                try { trace.write("  GOTO issued at tick 0\n"); } catch (IOException ignored) { }
                return;
            }

            attemptTicks++;
            traceLine();

            if (!bot.isAlive()) {
                finishResult("FAIL", "died", "the bot died mid-run");
                return;
            }

            double dx = bot.getX() - (GOAL.getX() + 0.5);
            double dy = bot.getY() - GOAL.getY();
            double dz = bot.getZ() - (GOAL.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            // --- EJECTION guard: after the first water entry, a sustained out-of-water run while far = FAIL.
            boolean inW = bot.isInWater();
            if (inW) enteredWater = true;
            if (enteredWater && !inW && dist > EJECT_DIST) {
                if (++outOfWaterRun >= EJECT_TICKS) {
                    finishResult("FAIL", "ejected",
                            String.format(Locale.ROOT,
                                    "out of water %d consecutive ticks at (%.2f,%.2f,%.2f), dist=%.2f",
                                    outOfWaterRun, bot.getX(), bot.getY(), bot.getZ(), dist));
                    return;
                }
            } else {
                outOfWaterRun = 0;
            }

            // --- PASS: driver reverted to STAY (comeTo drops to STAY only on TRUE arrival) and we're near.
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.8) {
                finishResult("PASS", "reached goal", "arrived");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(GOAL, ARRIVE_DIST, ARRIVE_Y, GOAL_TOL);
                    return;
                }
                finishResult("FAIL", "nav gave up", "no route offered");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                finishResult("FAIL", "timeout", "budget exhausted without arrival");
            }
        }

        void traceLine() {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            boolean inW = bot.isInWater();
            boolean subm = bot.isUnderWater();
            boolean swim = bot.isSwimming();
            boolean spr = bot.isSprinting();
            String move = bot.lastSteerMove;
            BotNavigator nav = bot.navigator();
            try {
                // Dump the full plan waypoint list whenever the plan identity changes (window-swap / replan).
                BlockPathPlan plan = nav.currentPlan();
                if (plan != prevPlan) {
                    if (plan != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("  PLAN ").append(plan.size()).append(" wp cost=")
                          .append(String.format(Locale.ROOT, "%.1f", plan.cost())).append(" [");
                        for (int i = 0; i < plan.size(); i++) {
                            if (i > 0) sb.append(' ');
                            BlockPos wp = plan.waypoint(i);
                            sb.append(plan.movement(i).getClass().getSimpleName())
                              .append('@').append(wp.getX()).append(',').append(wp.getY())
                              .append(',').append(wp.getZ());
                        }
                        sb.append("]\n");
                        trace.write(sb.toString());
                        checkPlanBubble(plan);
                    } else {
                        trace.write("  PLAN (null)\n");
                    }
                    prevPlan = plan;
                }
                if (!move.equals(prevMove)) {
                    trace.write(String.format(Locale.ROOT, "  MOVE %s seg=(%d,%d,%d)->(%d,%d,%d) drive=%s wp=%d/%d\n",
                            move, nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ(),
                            nav.driveState(), nav.waypointIndex(), nav.pathSize()));
                    prevMove = move;
                }
                if (swim != prevSwimming) {
                    trace.write(String.format(Locale.ROOT, "  POSE %s at y=%.3f vy=%.3f inW=%d subm=%d spr=%d\n",
                            swim ? "STAND->PRONE" : "PRONE->STAND", y, v.y, inW ? 1 : 0, subm ? 1 : 0, spr ? 1 : 0));
                }
                trace.write(String.format(Locale.ROOT,
                        "T %3d  %.3f %.3f %.3f | %.4f %.4f | %d %d %d %d %d | %s\n",
                        attemptTicks, x, y, z, v.y, spd,
                        grnd ? 1 : 0, inW ? 1 : 0, subm ? 1 : 0, swim ? 1 : 0, spr ? 1 : 0, move));
            } catch (IOException ignored) { }
            prevSwimming = swim;
            prevX = x;
            prevZ = z;
        }

        // ---- DIAGNOSTIC (no fix) : ground-truth block/bubble/nav dump around the reported eject spot -----
        // Bounding box (world cells) around the failure: x in [7,13], z in [-8,-1], y in [-61,-53].
        private static final int BX0 = 7, BX1 = 13, BZ0 = -8, BZ1 = -1, BY0 = -61, BY1 = -53;

        void dumpFailureLayout() {
            if (dumpedLayout) return;
            dumpedLayout = true;
            NavGridView view = new NavGridView(level);   // live nav store + live-getBlockState fallback (tick thread)
            StringBuilder sb = new StringBuilder();
            sb.append("\n==== FAILURE-LAYOUT DUMP  x[").append(BX0).append("..").append(BX1)
              .append("] z[").append(BZ0).append("..").append(BZ1)
              .append("] y[").append(BY0).append("..").append(BY1).append("]  (live blocks + nav classification)\n");
            sb.append("legend: U=bubble-UP(soul-sand src)  D=bubble-DOWN(magma src)  ~=swimmable-water  w=waterlogged/water(no-swim)\n");
            sb.append("        S=soul_sand  M=magma_block  #=solid  .=air  ?=other   (lowercase built-grid UNBUILT for that cell)\n");
            sb.append("        each Y slice: rows are z (top=z").append(BZ0).append("), cols are x (left=x").append(BX0)
              .append(", right=x").append(BX1).append(")\n");

            // Per-Y top-down slices, y from TOP down so up-columns read naturally.
            for (int y = BY1; y >= BY0; y--) {
                sb.append(String.format(Locale.ROOT, "\n-- y=%d --      x:", y));
                for (int x = BX0; x <= BX1; x++) sb.append(String.format(Locale.ROOT, "%3d", x));
                sb.append('\n');
                for (int z = BZ0; z <= BZ1; z++) {
                    sb.append(String.format(Locale.ROOT, "   z=%-4d      ", z));
                    for (int x = BX0; x <= BX1; x++) {
                        sb.append("  ").append(cellChar(view, x, y, z));
                    }
                    sb.append('\n');
                }
            }

            // Explicit per-cell detail for every non-trivial (non air/solid/plain-water) cell.
            sb.append("\n-- detail (bubble columns, soul-sand, magma, and any nav/live mismatch) --\n");
            for (int y = BY1; y >= BY0; y--) {
                for (int z = BZ0; z <= BZ1; z++) {
                    for (int x = BX0; x <= BX1; x++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState st = level.getBlockState(p);
                        long d = view.descriptorAt(x, y, z);
                        boolean liveBubble = st.getBlock() == Blocks.BUBBLE_COLUMN;
                        boolean navBubble = NavBlock.isBubble(d);
                        boolean interesting = liveBubble || navBubble
                                || st.getBlock() == Blocks.SOUL_SAND
                                || st.getBlock() == Blocks.MAGMA_BLOCK;
                        if (!interesting) continue;
                        String dragTxt = liveBubble
                                ? (st.getValue(BubbleColumnBlock.DRAG_DOWN) ? "DRAG_DOWN(down)" : "DRAG_UP(up)")
                                : "-";
                        int navDir = NavBlock.bubbleDir(d);
                        sb.append(String.format(Locale.ROOT,
                                "  (%2d,%3d,%2d) %-24s live.bubble=%b %s | nav.bubbleDir=%d nav.swimmable=%b nav.passable=%b built=%b\n",
                                x, y, z, name(st), liveBubble, dragTxt,
                                navDir, NavBlock.isSwimmableWater(d), NavBlock.isPassable(d),
                                view.built(x, y, z)));
                    }
                }
            }
            sb.append("==== END FAILURE-LAYOUT DUMP ====\n\n");
            try { trace.write(sb.toString()); trace.flush(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/replay] failure-layout dump written to trace ({} chars)", sb.length());
        }

        /** One char per cell: bubble/soul-sand/magma/water/solid/air. Lowercase when the built nav grid has
         *  no section for the cell (classification came from the live-getBlockState fallback). */
        String cellChar(NavGridView view, int x, int y, int z) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            long d = view.descriptorAt(x, y, z);
            boolean built = view.built(x, y, z);
            char c;
            int dir = NavBlock.bubbleDir(d);
            if (dir == NavBlock.BUBBLE_UP)               c = 'U';
            else if (dir == NavBlock.BUBBLE_DOWN)        c = 'D';
            else if (st.getBlock() == Blocks.SOUL_SAND)  c = 'S';
            else if (st.getBlock() == Blocks.MAGMA_BLOCK) c = 'M';
            else if (NavBlock.isSwimmableWater(d))       c = '~';
            else if (NavBlock.isWaterloggedNow(d))       c = 'w';
            else if (st.isAir())                         c = '.';
            else if (NavBlock.hasCollision(d))           c = '#';
            else                                         c = '?';
            return String.valueOf(built ? c : Character.toLowerCase(c));
        }

        /** Flag whether ANY plan waypoint cell (or the cell below/above it) is a bubble column / impassable —
         *  a planner routing-through-a-column bug — vs the plan staying on safe channel water. */
        void checkPlanBubble(BlockPathPlan plan) throws IOException {
            NavGridView view = new NavGridView(level);
            StringBuilder sb = new StringBuilder("  PLAN-BUBBLE-CHECK:\n");
            boolean anyHit = false;
            for (int i = 0; i < plan.size(); i++) {
                BlockPos wp = plan.waypoint(i);
                long dHere  = view.descriptorAt(wp.getX(), wp.getY(),     wp.getZ());
                long dAbove = view.descriptorAt(wp.getX(), wp.getY() + 1, wp.getZ());
                long dBelow = view.descriptorAt(wp.getX(), wp.getY() - 1, wp.getZ());
                boolean bHere  = NavBlock.isBubble(dHere);
                boolean bAbove = NavBlock.isBubble(dAbove);
                boolean bBelow = NavBlock.isBubble(dBelow);
                boolean impassableHere = !NavBlock.isSwimmableWater(dHere) && !NavBlock.isPassable(dHere);
                if (bHere || bAbove || bBelow || impassableHere) {
                    anyHit = true;
                    sb.append(String.format(Locale.ROOT,
                            "    wp[%d] %s@%d,%d,%d  bubbleHERE=%b bubbleABOVE=%b bubbleBELOW=%b impassableHERE=%b\n",
                            i, plan.movement(i).getClass().getSimpleName(),
                            wp.getX(), wp.getY(), wp.getZ(), bHere, bAbove, bBelow, impassableHere));
                }
            }
            if (!anyHit) {
                sb.append("    NONE — no plan waypoint (or its cell above/below) is a bubble column or impassable"
                        + " (plan stays on safe channel water).\n");
            } else {
                sb.append("    ^^ AT LEAST ONE plan waypoint touches a bubble column / impassable cell.\n");
            }
            trace.write(sb.toString());
            trace.flush();
        }

        static String name(BlockState st) {
            String id = st.getBlock().getDescriptionId();   // e.g. block.minecraft.soul_sand — range-stable
            int dot = id.lastIndexOf('.');
            return dot >= 0 ? id.substring(dot + 1) : id;
        }

        void finishResult(String result, String reason, String detail) {
            String line = String.format(Locale.ROOT,
                    "result=%s reason=%s ticks=%d closest=%.2f finalPos=(%.2f,%.2f,%.2f) inWater=%b swimming=%b lastMove=%s detail=%s",
                    result, reason, attemptTicks, closest,
                    bot.getX(), bot.getY(), bot.getZ(), bot.isInWater(), bot.isSwimming(), bot.lastSteerMove, detail);
            OrebitCommon.LOGGER.info("[Orebit/replay] {} ({}) after {} ticks; closest={} finalPos=({},{},{})",
                    result, reason, attemptTicks, String.format(Locale.ROOT, "%.2f", closest),
                    String.format(Locale.ROOT, "%.2f", bot.getX()),
                    String.format(Locale.ROOT, "%.2f", bot.getY()),
                    String.format(Locale.ROOT, "%.2f", bot.getZ()));
            try {
                trace.write("  RESULT " + result + " (" + reason + "): " + detail + "\n");
            } catch (IOException ignored) { }
            finishFile(result, reason, line);
        }

        void finishFile(String result, String reason, String resultLine) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("completed=true\n");
                w.write("result=" + result + "\n");
                w.write("reason=" + reason + "\n");
                w.write(resultLine + "\n");
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/replay] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/replay] DONE ({}) — halting", reason);
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-replay-exit");
            exiter.setDaemon(true);
            exiter.start();
        }

        void finish(String reason) {
            finishFile("FAIL", reason, "result=FAIL reason=" + reason);
        }

        static float yaw(int dx, int dz) { return (float) Math.toDegrees(Math.atan2(-dx, dz)); }
    }
}
