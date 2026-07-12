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
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Headless SWIMMING-MOVEMENT diagnostic harness (a sibling of {@link ParkourCourse}, armed by its own
 * {@code -Dorebit.swim} flag). It builds a grid of self-contained water TANKS floating high over a flat
 * world and drives the bot through a SERIES of isolated water challenges — surface crossings, dives, column
 * rises, a prone 1×1 thread, bubble columns (up + down), kelp, and a waterlogged fence — recording pass/fail
 * plus a per-tick trajectory dump with the water-specific state (in-water, submerged, the prone
 * {@code Pose.SWIMMING}, sprint, vertical velocity) so the catatonic/oscillation/exit pathologies can be
 * diagnosed from data.
 *
 * <p><b>Why a bespoke course.</b> The reported swim pathologies — walks into water and freezes, sinks to the
 * bottom and spins on one square, can't climb out, tries to thread a 1×1 gap in the upright pose — are all
 * EXECUTOR/follower problems invisible to a route-level pass/fail. Isolating each shape in its own tank makes
 * it a reproducible experiment; the trajectory dump captures exactly what the bot did (position frozen,
 * position cycling, wrong pose at the gap, the depth-autopilot fighting a bubble column's push).
 *
 * <p><b>Contained-tank verdict model (owner-chosen).</b> Each tile is a fully-walled stone tank (a solid shell
 * with an open top, filled with water sources) with dry approach/exit platforms. Nothing is lethal by design:
 * a stuck/frozen/oscillating bot simply never reaches its goal and FAILS by timeout, with the trace
 * explaining why. {@code needsBreath} is OFF (see {@code scripts/swim/orebit.properties}) so a slow-but-working
 * deep swim is never killed mid-diagnosis — "stuck" and "slow" stay distinguishable in the data. The harness
 * bakes in NO fix and asserts NO mechanism; it only reproduces and records, exactly like {@link ParkourCourse}.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.swim} is set.
 * Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class SwimCourse {

    private SwimCourse() {}

    private static final String RESULT_FILE = "orebit-swim-result.properties";
    private static final String TRACE_FILE = "orebit-swim-trace.txt";

    /** Water-surface / lip / platform level: solid platform & wall tops and the top water layer all sit at S,
     *  so a bot stands at S+1 and steps DOWN one cell into the surface (the realistic contained-pool lip). */
    private static final int S = 160;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    /** Tiles laid in a compact GRID (snake ordering, so consecutive trials are adjacent and teleports stay
     *  inside the loaded+built nav bubble). */
    private static final int COLS = 4;
    private static final int STRIDE = 22; // grid cell size (> the longest tile span so tiles never touch)

    private static final int WARMUP_TICKS = 120;
    private static final int SETTLE_TICKS = 40;
    private static final int NAV_RETRY_WINDOW = 40;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). Swimming is slow (surface paddle 9.09 t/block), and a deep dive +
     *  rise + long crossing is legitimately long — a generous budget so a working-but-slow swim never trips a
     *  false timeout, while a genuine freeze still resolves within it (~40 s). */
    private static final int ATTEMPT_BUDGET = 800;
    /** Consecutive fully-out-of-water ticks (far from goal) that count as an ejection (debounced so a 1-tick
     *  surface bob doesn't false-fire). */
    private static final int EJECT_CONSECUTIVE_TICKS = 5;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** A slippery ice lip: reduced friction settles the bot at a sub-block-center offset at the pool edge. */
    private static final BlockState ICE = Blocks.ICE.defaultBlockState();
    /** Soul sand under a water column makes vanilla form an UPWARD bubble column (pushes entities up). */
    private static final BlockState SOUL_SAND = Blocks.SOUL_SAND.defaultBlockState();
    /** Magma under a water column makes vanilla form a DOWNWARD bubble column (drags entities down); also
     *  damaging on contact (takesDamage bots) — the down-drag can pull a surface swimmer under toward it. */
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();
    /** Kelp: a plant IN water — empty collision shape + water fluid → classified SWIMMABLE (passable), the
     *  "waterlogged but passable" case. (Even if it decays untended, the cell reverts to plain water — still
     *  swimmable — so the classification under test is stable for the run.) */
    private static final BlockState KELP = Blocks.KELP_PLANT.defaultBlockState();
    /** A WATERLOGGED fence: fence collision shape + water fluid → classified NOT swimmable (a wall the bot
     *  must route around), the "waterlogged but NOT passable" case. */
    private static final BlockState WLOG_FENCE = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true);
    /** Honey on the step-off lip: a sticky full block that slows horizontal movement and prevents jumping,
     *  helping settle the bot at ~zero velocity dry on the lip (part of the lip-stuck repro). */
    private static final BlockState HONEY = Blocks.HONEY_BLOCK.defaultBlockState();
    /** Cobweb in the body cells above the honey lip: crushes the bot's speed to ~zero so it perches DRY on
     *  the lip (the arrival-with-no-momentum precondition of the lip-stuck bug). */
    private static final BlockState COBWEB = Blocks.COBWEB.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.swim") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/swim] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        CROSS_SURFACE,  // 1-deep pool: a pure surface Swim across (no 2-deep → no sprint-swim init)
        CROSS_DEEP,     // 4-deep pool: dive → prone SprintSwim just below the surface → surface & exit
        DIVE,           // deep shaft, goal at the BOTTOM: dive down and hold the bottom (the "spins on the
                        //   lowest square" oscillation case)
        SINK,           // deep pool, goal a FLOORLESS mid-water cell: surface-swim then dive & hold mid-depth
        RISE,           // deep shaft, START submerged at the bottom, goal at the TOP: swim up & climb out
        SUBMERGED_GAP,  // 2-deep init, 1-deep run, a 1-tall hole in a wall, a dry bank goal — the in-game
                        //   StatefulSwimTest shape; only a PRONE (sprint-swim) bot threads it (pathology #4)
        GAP_NO_INIT,    // same, but the init cell is only 1-deep → nowhere to go prone → expect nav give-up
        BUBBLE_UP,      // soul-sand floor → UPWARD bubble column in the centre lane; CROSS to the far exit
                        //   platform — the impassable column forces a route AROUND it via a side lane
        BUBBLE_DOWN,    // magma floor → DOWNWARD bubble column in the centre lane; CROSS to the far exit
                        //   platform — likewise route AROUND the impassable column
        KELP,           // kelp-filled pool: swim across, should behave exactly like open water
        FENCE,          // a waterlogged-fence wall blocking the centre lane: route around it (fence = wall)
        GAP_2X1,        // surface crossing with an over-height wall mid-pool; only passage is a 2-tall x 1-wide
                        //   center gap at S-1..S — bot must submerge + go prone to thread (skims & slams wall)
        GAP_1X1_ANGLE,  // 5-lane pool, over-height wall with a single 1x1 hole at (wallX,S-1,zc); start offset
                        //   +2 lanes at the surface — angled + descending approach to the hole
        LIP_ICE,        // surface cross whose step-off lip block is ICE — friction settles the bot at a
                        //   sub-block-center offset at the lip (long-shot dry-perch repro)
        LIP_DOWN,       // bot starts DRY on a honey pool-lip, buried in cobweb (≈zero velocity), goal at the
                        //   pool BOTTOM directly adjacent → the first in-water move is a DOWN dive, not a
                        //   lateral step; reproduces the lip-stuck bug (dry-perch, cursor advances past the
                        //   water-entry waypoint, bot tries to swim down while still dry and stalls)
        SWIM_TURN,      // a SUBMERGED 1-wide sprint-swim tunnel (stone ceiling → prone the whole way) that runs
                        //   a LONG +X approach, then turns 90° to +Z; an UP-bubble-column sits STRAIGHT AHEAD of
                        //   the corner (the +X-overshoot cell). The only route is +X→turn→+Z (the column is
                        //   impassable, off-route), so a clean cruise routes around it — but the current
                        //   SprintSwim drives full setForward(1.0), so carried-forward momentum should COAST the
                        //   bot straight past the corner into the column (drift off the planned path). Repro of
                        //   the cruise-overshoot; walls seal every diagonal so the corner can't be cut.
        SWIM_MAZE       // a SUBMERGED bubble-column SERPENTINE: a 1-wide safe STONE-floored channel winds
                        //   boustrophedon (+X leg, one lane over, -X leg, one lane over, +X leg) through a tank
                        //   whose every OTHER water cell is a SOUL_SAND up-bubble-column (the maze "walls" — the
                        //   inverse of the centre-column trials). At each turn the cell straight ahead along the
                        //   incoming leg is a column, so cruise momentum that fails to brake into the turn drifts
                        //   the bot into an impassable column. Only the FIRST leg is roofed (kept submerged/prone
                        //   for the opening cruise); over every later leg the sky is OPEN, so a column clip EJECTS
                        //   the bot out of the water (loses the prone Pose.SWIMMING) with no ceiling to hold it
                        //   down — the lethality mechanism. Repro of momentum-overshoot-into-a-bubble-column.
    }

    /** One water challenge: a kind + its tank dimensions, with start/goal geometry precomputed from its base. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int depth;            // water layers (surface at S, solid floor at S-depth)
        final int poolLen;          // pool length along +X (interior)
        final int baseX, baseZ;

        final int zc;               // centre-line Z
        final int approachX0;       // first approach-platform cell
        final int poolX0, poolX1;   // interior water span (X)
        final int yFloor;           // solid floor Y (= S - depth)

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;

        Trial(String name, Kind kind, int depth, int poolLen, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.depth = depth;
            this.poolLen = poolLen;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 4;
            this.approachX0 = baseX + 1;
            this.poolX0 = approachX0 + 3;            // 3-wide approach, then the west wall at poolX0-1
            this.poolX1 = poolX0 + poolLen - 1;
            this.yFloor = S - depth;
            int centerX = (poolX0 + poolX1) / 2;
            this.startYaw = yaw(1, 0);               // every trial runs / faces +X

            if (kind == Kind.SUBMERGED_GAP || kind == Kind.GAP_NO_INIT) {
                // Special geometry (see buildGap): a solid block with a carved water channel at feet-level
                // yB = S-1. Start submerged in the init cell; goal on the dry bank two cells past the hole.
                int yB = S - 1;
                this.startX = baseX + 2 + 0.5;
                this.startY = yB;                    // feet in the init water cell (submerged when 2-deep)
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(baseX + 8, yB, zc);
                return;
            }

            if (kind == Kind.GAP_1X1_ANGLE) {
                // 5-lane pool (built by buildAngleGap). Start at the surface, offset +2 lanes; the single 1x1
                // hole is in the CENTER lane one block below the surface — an angled + descending approach.
                this.startX = approachX0 + 1 + 0.5;
                this.startY = S + 1;
                this.startZ = zc + 2 + 0.5;
                this.goal = new BlockPos(poolX1 + 2, S + 1, zc);
                return;
            }

            if (kind == Kind.LIP_DOWN) {
                // Start standing DRY directly on the honey pool-lip (feet at S+1), buried in cobweb so it sits
                // at ~zero velocity. The goal is the pool BOTTOM of the nearest water column (adjacent to the
                // lip), so the planned first in-water move after the water-entry waypoint heads DOWN (a dive),
                // not laterally. This is the lip-stuck repro shape.
                this.startX = poolX0 - 1 + 0.5;      // directly on the lip block (poolX0-1), centre lane
                this.startY = S + 1;                 // feet on top of the honey lip
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(poolX0, yFloor + 1, zc);
                return;
            }

            if (kind == Kind.SWIM_TURN) {
                // Custom submerged L-tunnel (see buildSwimTurn). Start submerged at the tunnel entry, facing +X,
                // so the bot is prone-sprint-swimming BEFORE it reaches the corner (this tests the CRUISE, not
                // the initiation). The +X leg is long (entry baseX+2 .. corner baseX+10 = 9 cells) to build full
                // cruise momentum; then a 90° turn to +Z runs to the goal at the end of the +Z leg. The impassable
                // up-bubble column sits at (baseX+11, zc) — the cell STRAIGHT AHEAD of the corner in +X.
                int xEntry = baseX + 2;
                this.startX = xEntry + 0.5;
                this.startY = yFloor + 1;            // feet in the bottom water layer → fully submerged
                this.startZ = zc + 0.5;
                int xTurn = baseX + 10;
                int zEnd = zc + 8;
                this.goal = new BlockPos(xTurn, yFloor + 1, zEnd);   // end of the +Z leg, submerged
                return;
            }

            if (kind == Kind.SWIM_MAZE) {
                // Submerged bubble-column serpentine (see buildSwimMaze). Start submerged at the WEST end of the
                // first +X leg, facing +X, so the bot is already prone-sprint-swimming before the first turn
                // (this tests the CRUISE, not the initiation). Three ~9-cell legs (boustrophedon: +X at z=zc,
                // -X at z=zc+2, +X at z=zc+4) build real momentum; each turn has an up-bubble-column straight
                // ahead (the overshoot cell). Goal at the EAST end of leg 3, with a column one cell past it.
                int xW = baseX + 2;                  // west channel end
                int xE = baseX + 10;                 // east channel end
                this.startX = xW + 0.5;
                this.startY = yFloor + 1;            // feet in the bottom water layer → fully submerged
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(xE, yFloor + 1, zc + 4);   // end of leg 3 (+X), submerged
                return;
            }

            // Generic tank kinds.
            if (kind == Kind.RISE) {
                // Start submerged at the bottom of the shaft; climb out to the top far platform.
                this.startX = centerX + 0.5;
                this.startY = yFloor + 1;
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(poolX1 + 2, S + 1, zc);
            } else {
                // Everyone else starts on the approach platform at the surface.
                this.startX = approachX0 + 1 + 0.5;
                this.startY = S + 1;
                this.startZ = zc + 0.5;
                if (kind == Kind.DIVE) {
                    // Goal at the bottom centre — dive to it.
                    this.goal = new BlockPos(centerX, yFloor + 1, zc);
                } else if (kind == Kind.SINK) {
                    // Goal at a FLOORLESS mid-water cell (3 below the surface, water still below it) — the bot
                    // must dive and HOLD depth against buoyancy with no floor to rest on.
                    this.goal = new BlockPos(centerX, S - 3, zc);
                } else {
                    // CROSS_SURFACE / CROSS_DEEP / BUBBLE_UP / BUBBLE_DOWN / KELP / FENCE: cross to the far exit
                    // platform. The BUBBLE trials put a full-height bubble column in the CENTRE lane (z=zc) — now
                    // that a bubble column is classified impassable, the only route across is AROUND it through a
                    // side lane (z=zc±1, ordinary water), so both bubble trials test ROUTING, not diving in.
                    this.goal = new BlockPos(poolX1 + 2, S + 1, zc);
                }
            }
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
        double closest;             // closest 3-D approach to the goal seen this trial (diagnostic)
        boolean everInWater;        // the bot has been in water at least once this trial (arms the eject guard)
        int ejectTicks;             // consecutive fully-out-of-water ticks (far from goal) since last in water
        double prevX, prevZ;
        boolean prevSwimming;
        String prevMove = "";
        int prevSegToX = Integer.MIN_VALUE, prevSegToY, prevSegToZ;
        /** Last plan reference already dumped (identity) — a new/replanned plan re-triggers the PLAN block. */
        com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan lastDumpedPlan;
        int passed, failed;

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("cross",       Kind.CROSS_SURFACE, 2, 6);
            add("crossdeep",   Kind.CROSS_DEEP,    4, 6);
            add("dive",        Kind.DIVE,          6, 3);
            add("sink",        Kind.SINK,          6, 3);
            add("rise",        Kind.RISE,          6, 3);
            add("gap",         Kind.SUBMERGED_GAP, 0, 0);
            add("gapnoinit",   Kind.GAP_NO_INIT,   0, 0);
            add("bubbleup",    Kind.BUBBLE_UP,     5, 3);
            add("bubbledown",  Kind.BUBBLE_DOWN,   5, 6);
            add("kelp",        Kind.KELP,          3, 6);
            add("fence",       Kind.FENCE,         3, 5);
            add("gap2x1",      Kind.GAP_2X1,       3, 6);
            add("gap1x1angle", Kind.GAP_1X1_ANGLE, 3, 6);
            add("lipice",      Kind.LIP_ICE,       2, 6);
            add("lipdown",     Kind.LIP_DOWN,      4, 6);
            add("swimturn",    Kind.SWIM_TURN,     4, 8);
            add("swimmaze",    Kind.SWIM_MAZE,     4, 8);
        }

        void add(String name, Kind kind, int depth, int poolLen) {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake
            int bx = BASE_X + col * STRIDE;
            int bz = BASE_Z + row * STRIDE;
            trials.add(new Trial(name, kind, depth, poolLen, bx, bz));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.swim.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitSwim:owner".getBytes(StandardCharsets.UTF_8)),
                        "Swim"));
                owner.setPos(first.startX, S + 1, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit swim course trace  (T <trial> <tick> x y z | vy spd | grnd inW subm swim spr | move)\n");
                trace.write("legend: grnd=onGround inW=inWater subm=isUnderWater swim=prone Pose.SWIMMING spr=sprinting\n\n");
                OrebitCommon.LOGGER.info("[Orebit/swim] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/swim] setup threw", t);
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
            everInWater = false;
            ejectTicks = 0;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevSwimming = false;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            lastDumpedPlan = null;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s depth=%d start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d)\n",
                        tr.name, tr.kind, tr.depth, tr.startX, tr.startY, tr.startZ,
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
                bot.comeTo(tr.goal, 0.75, 0.75, 0);
                return;
            }

            attemptTicks++;
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

            // EJECTION GUARD: the bot loses the prone swim pose by fully leaving the water mid-route (a bubble
            // column shoots it out). Only armed once the bot has actually been in water (so a dry-start trial
            // like lipdown doesn't false-fire), debounced over EJECT_CONSECUTIVE_TICKS (so a 1-tick surface bob
            // doesn't trip it), and gated on dist > ARRIVE_DIST (so exiting onto a bank AT the goal is a PASS).
            if (bot.isInWater()) {
                everInWater = true;
                ejectTicks = 0;
            } else if (everInWater) {
                ejectTicks++;
                if (ejectTicks >= EJECT_CONSECUTIVE_TICKS && dist > BotNavigator.ARRIVE_DIST) {
                    record(tr, "FAIL", "ejected (left water mid-route, lost prone)");
                    return;
                }
            }

            // PASS: the driver reverted to STAY (comeTo drops to STAY only on TRUE arrival — a nav give-up
            // holds in COME), and the bot is genuinely near the goal cell.
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.8) {
                record(tr, "PASS", "reached goal");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                // GAP_NO_INIT is a NEGATIVE control — a clean nav-give-up there is the CORRECT outcome.
                if (tr.kind == Kind.GAP_NO_INIT) {
                    record(tr, "PASS", "nav correctly refused (no 2-deep to initiate)");
                    return;
                }
                // Otherwise: a give-up right after a teleport can just be nav-not-yet-built; retry a few times.
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

        void trace(Trial tr) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            boolean inW = bot.isInWater();
            boolean subm = bot.isUnderWater();
            boolean swim = bot.isSwimming();
            boolean spr = bot.isSprinting();
            String move = bot.lastSteerMove;
            try {
                BotNavigator nav = bot.navigator();
                com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan plan = nav.currentPlan();
                if (plan != null && plan != lastDumpedPlan) {
                    trace.write(String.format(Locale.ROOT, "PLAN %s size=%d\n", tr.name, plan.size()));
                    for (int j = 0; j < plan.size(); j++) {
                        BlockPos wp = plan.waypoint(j);
                        trace.write(String.format(Locale.ROOT, "  P %d %d %d %d %s\n",
                                j, wp.getX(), wp.getY(), wp.getZ(),
                                plan.movement(j).getClass().getSimpleName()));
                    }
                    lastDumpedPlan = plan;
                }
                boolean segChanged = nav.segToX() != prevSegToX || nav.segToY() != prevSegToY
                        || nav.segToZ() != prevSegToZ;
                if (!move.equals(prevMove) || segChanged) {
                    // Log on EVERY waypoint advance (segment change), not just move-name change, and include
                    // the waypoint index + the bot's actual position so overshoot past an in-place pose-flip
                    // waypoint (StartSprintSwim / Surface) is measurable.
                    trace.write(String.format(Locale.ROOT,
                            "  WP i=%d/%d %s seg=(%d,%d,%d)->(%d,%d,%d) bot=(%.2f,%.2f,%.2f)\n",
                            nav.waypointIndex(), nav.pathSize(), move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ(), x, y, z));
                    prevMove = move;
                    prevSegToX = nav.segToX(); prevSegToY = nav.segToY(); prevSegToZ = nav.segToZ();
                }
                if (swim != prevSwimming) {
                    trace.write(String.format(Locale.ROOT, "  POSE %s at y=%.3f vy=%.3f inW=%d subm=%d spr=%d\n",
                            swim ? "STAND->PRONE" : "PRONE->STAND", y, v.y, inW ? 1 : 0, subm ? 1 : 0, spr ? 1 : 0));
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-12s %3d  %.3f %.3f %.3f | %.4f %.4f | %d %d %d %d %d | %s | drive=%s wp=%d/%d fwd=%.2f\n",
                        tr.name, attemptTicks, x, y, z, v.y, spd,
                        grnd ? 1 : 0, inW ? 1 : 0, subm ? 1 : 0, swim ? 1 : 0, spr ? 1 : 0, move,
                        nav.driveState(), nav.waypointIndex(), nav.pathSize(), bot.zza));
                // GEO: vanilla ground-truth Y-indexing probe (additive; DIAGNOSTIC ONLY — no behavior change).
                // Only the three named trials, to keep the trace compact. Logs bot.getY() vs the REAL AABB
                // extent vs the REAL world block states straddling the hitbox, plus footY and the waypoint Y —
                // so a Y off-by-one between our computed model and vanilla ground truth is directly readable.
                if (tr.name.equals("gap1x1angle") || tr.name.equals("crossdeep") || tr.name.equals("dive")) {
                    net.minecraft.world.phys.AABB bb = bot.getBoundingBox();
                    BlockPos foot = bot.blockPosition();
                    int footX = foot.getX(), footZ = foot.getZ();
                    int y0 = (int) Math.floor(bb.minY) - 1;
                    int y1 = (int) Math.floor(bb.maxY) + 1;
                    StringBuilder cells = new StringBuilder();
                    for (int wy = y0; wy <= y1; wy++) {
                        BlockPos cp = new BlockPos(footX, wy, footZ);
                        BlockState cs = level.getBlockState(cp);
                        char c;
                        if (cs.getFluidState().is(FluidTags.WATER)) c = 'W';
                        else if (!cs.getCollisionShape(level, cp).isEmpty()) c = 'S';
                        else c = 'A';
                        cells.append('Y').append(wy).append('=').append(c).append(' ');
                    }
                    trace.write(String.format(Locale.ROOT,
                            "GEO %s %d byY=%.3f aabb=[%.3f,%.3f] footY=%d wpY=%d cells{%s} inW=%d subm=%d swim=%d\n",
                            tr.name, attemptTicks, y, bb.minY, bb.maxY, foot.getY(), nav.segToY(),
                            cells.toString().trim(), inW ? 1 : 0, subm ? 1 : 0, swim ? 1 : 0));
                }
            } catch (IOException ignored) { }
            prevSwimming = swim;
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.1f,%.1f,%.1f) inWater=%b swimming=%b lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), bot.isInWater(), bot.isSwimming(), bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/swim] {} -> {} ({}) closest={} ticks={} finalY={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, String.format(Locale.ROOT, "%.2f", bot.getY()));
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            if (tr.kind == Kind.SUBMERGED_GAP) { buildGap(tr, true); return; }
            if (tr.kind == Kind.GAP_NO_INIT)   { buildGap(tr, false); return; }
            if (tr.kind == Kind.GAP_1X1_ANGLE) { buildAngleGap(tr); return; }
            if (tr.kind == Kind.SWIM_TURN)     { buildSwimTurn(tr); return; }
            if (tr.kind == Kind.SWIM_MAZE)     { buildSwimMaze(tr); return; }
            buildTank(tr);
            switch (tr.kind) {
                case BUBBLE_UP:   floorFeature(tr, SOUL_SAND); break; // upward bubble column in the shaft
                case BUBBLE_DOWN: floorFeature(tr, MAGMA);     break; // downward column mid-crossing
                case KELP:        fillLane(tr, KELP);          break; // swap the centre water lane for kelp
                case FENCE:       fenceWall(tr);               break; // block the centre lane with a fence wall
                case GAP_2X1:     gap2x1Wall(tr);              break; // over-height wall, 2-tall center gap
                case LIP_ICE:     iceLip(tr);                  break; // ice on the step-off lip block
                case LIP_DOWN:    honeyLip(tr);                break; // honey lip + cobweb body cells (≈0 vel)
                default: break;
            }
        }

        /** A fully-walled stone tank (solid shell, open top) filled with water sources, plus dry approach and
         *  exit platforms at the surface level S. Contained on all four horizontal sides + the floor, so the
         *  water is stable (every interior cell is a source with only air ABOVE it). */
        void buildTank(Trial tr) {
            int wx0 = tr.poolX0 - 1, wx1 = tr.poolX1 + 1;   // west/east walls
            int wz0 = tr.zc - 2, wz1 = tr.zc + 2;           // ±z walls
            for (int x = wx0; x <= wx1; x++) {
                for (int z = wz0; z <= wz1; z++) {
                    set(x, tr.yFloor, z, STONE);            // floor slab
                    boolean perimeter = (x == wx0 || x == wx1 || z == wz0 || z == wz1);
                    if (perimeter) {
                        // N/S walls rise to S+2 (an UNWALKABLE rim — no dry bypass around the pool); the E/W center
                        // lips stay at S so the bot can still step in at the west and climb out at the east. This is
                        // what forces the only approach->exit route to go THROUGH the water.
                        int wallTop = (z == wz0 || z == wz1) ? S + 2 : S;
                        for (int y = tr.yFloor + 1; y <= wallTop; y++) set(x, y, z, STONE);
                    }
                }
            }
            for (int x = tr.poolX0; x <= tr.poolX1; x++) {  // interior water
                for (int z = tr.zc - 1; z <= tr.zc + 1; z++) {
                    for (int y = tr.yFloor + 1; y <= S; y++) set(x, y, z, WATER);
                }
            }
            for (int x = tr.approachX0; x <= tr.poolX0 - 1; x++) {   // approach platform (layer at S)
                for (int z = tr.zc - 1; z <= tr.zc + 1; z++) set(x, S, z, STONE);
            }
            for (int x = tr.poolX1 + 1; x <= tr.poolX1 + 4; x++) {   // exit platform (layer at S)
                for (int z = tr.zc - 1; z <= tr.zc + 1; z++) set(x, S, z, STONE);
            }
        }

        /** Replace the centre-column floor block with a bubble-column source (soul sand → up, magma → down),
         *  so vanilla forms the bubble column through the water above it. */
        void floorFeature(Trial tr, BlockState floor) {
            int cx = (tr.poolX0 + tr.poolX1) / 2;
            set(cx, tr.yFloor, tr.zc, floor);
        }

        /** Swap the centre water lane for another swimmable fill (kelp). */
        void fillLane(Trial tr, BlockState fill) {
            for (int x = tr.poolX0; x <= tr.poolX1; x++) {
                for (int y = tr.yFloor + 1; y <= S; y++) set(x, y, tr.zc, fill);
            }
        }

        /** A full-height WATERLOGGED-fence wall across the centre lane mid-pool, leaving the two side lanes
         *  (z = zc±1) open — so the only route across is AROUND the wall, proving the planner treats the
         *  waterlogged fence as impassable (not swimmable). */
        void fenceWall(Trial tr) {
            int cx = (tr.poolX0 + tr.poolX1) / 2;
            for (int y = tr.yFloor + 1; y <= S; y++) set(cx, y, tr.zc, WLOG_FENCE);
        }

        /** A full-width, over-height wall across all 3 lanes mid-pool, sealing both THROUGH and OVER, leaving
         *  only a 2-tall × 1-wide gap (world-Y S-1..S) in the CENTER lane. An upright 1.8-tall bot skimming the
         *  surface can't fit (its head hits the STONE at S+1); it must submerge + go prone to thread. */
        void gap2x1Wall(Trial tr) {
            int wallX = (tr.poolX0 + tr.poolX1) / 2;
            for (int z = tr.zc - 1; z <= tr.zc + 1; z++) {
                for (int y = tr.yFloor + 1; y <= S + 2; y++) set(wallX, y, z, STONE);
            }
            set(wallX, S, tr.zc, WATER);       // top of the 2-tall gap (surface layer)
            set(wallX, S - 1, tr.zc, WATER);   // bottom of the 2-tall gap (one below surface)
            // (wallX, S-2, zc) stays STONE (below the gap); (wallX, S+1..S+2, zc) stays STONE (over the surface).
        }

        /** Replace the step-off lip block (the last approach-platform cell adjacent to the pool, center lane)
         *  with ICE — reduced friction settles the bot at a sub-block-center offset at the lip. */
        void iceLip(Trial tr) {
            set(tr.poolX0 - 1, S, tr.zc, ICE);
        }

        /** The lip-stuck repro: make the step-off lip block HONEY (sticky, no-jump) and bury the two body
         *  cells directly above it (S+1 feet, S+2 head) in COBWEB, so the bot — teleported to start dry ON
         *  the lip — sits at ≈zero velocity. With the goal at the pool bottom directly adjacent, the plan's
         *  first in-water move is a DOWN dive, so a dry-perched bot that advances its cursor past the
         *  water-entry waypoint tries to swim downward while still dry and stalls. */
        void honeyLip(Trial tr) {
            int lx = tr.poolX0 - 1;
            set(lx, S, tr.zc, HONEY);        // the lip block itself → honey
            set(lx, S + 1, tr.zc, COBWEB);   // feet cell → cobweb
            set(lx, S + 2, tr.zc, COBWEB);   // head cell → cobweb
        }

        /** A widened 5-lane tank (interior z = zc-2..zc+2, walls at zc-3/zc+3) with an over-height wall across
         *  all 5 lanes mid-pool, pierced by a SINGLE 1×1 water hole at (wallX, S-1, zc) — one block below the
         *  surface, center lane. Start is surface-level offset +2 lanes → an angled + descending approach. */
        void buildAngleGap(Trial tr) {
            int wx0 = tr.poolX0 - 1, wx1 = tr.poolX1 + 1;   // west/east walls
            int wz0 = tr.zc - 3, wz1 = tr.zc + 3;           // ±z walls (5 interior lanes between them)
            for (int x = wx0; x <= wx1; x++) {
                for (int z = wz0; z <= wz1; z++) {
                    set(x, tr.yFloor, z, STONE);            // floor slab
                    boolean perimeter = (x == wx0 || x == wx1 || z == wz0 || z == wz1);
                    if (perimeter) {
                        int wallTop = (z == wz0 || z == wz1) ? S + 2 : S;
                        for (int y = tr.yFloor + 1; y <= wallTop; y++) set(x, y, z, STONE);
                    }
                }
            }
            for (int x = tr.poolX0; x <= tr.poolX1; x++) {  // interior water (5 lanes)
                for (int z = tr.zc - 2; z <= tr.zc + 2; z++) {
                    for (int y = tr.yFloor + 1; y <= S; y++) set(x, y, z, WATER);
                }
            }
            for (int x = tr.approachX0; x <= tr.poolX0 - 1; x++) {   // approach platform (5 lanes wide)
                for (int z = tr.zc - 2; z <= tr.zc + 2; z++) set(x, S, z, STONE);
            }
            for (int x = tr.poolX1 + 1; x <= tr.poolX1 + 4; x++) {   // exit platform
                for (int z = tr.zc - 2; z <= tr.zc + 2; z++) set(x, S, z, STONE);
            }
            int wallX = (tr.poolX0 + tr.poolX1) / 2;                 // over-height wall across all 5 lanes
            for (int z = tr.zc - 2; z <= tr.zc + 2; z++) {
                for (int y = tr.yFloor + 1; y <= S + 2; y++) set(wallX, y, z, STONE);
            }
            set(wallX, S - 1, tr.zc, WATER);                        // the single 1×1 hole, one below the surface
        }

        /** The prone 1×1-thread maze (the in-game {@code StatefulSwimTest} shape): a solid stone block with a
         *  water channel carved along z=zc at feet level yB = S-1. init cell (2-deep when {@code deepInit},
         *  else 1-deep), a 1-deep run, a 1-tall hole in a wall (water feet, stone above), then a dry bank. */
        void buildGap(Trial tr, boolean deepInit) {
            int yB = S - 1;
            int x0 = tr.baseX + 1, x1 = tr.baseX + 9;
            int z0 = tr.zc - 2, z1 = tr.zc + 2;
            for (int x = x0; x <= x1; x++) {                // the solid enclosing block
                for (int z = z0; z <= z1; z++) {
                    for (int y = yB - 2; y <= yB + 4; y++) set(x, y, z, STONE);
                }
            }
            int z = tr.zc;
            set(tr.baseX + 2, yB, z, WATER);                            // init feet
            set(tr.baseX + 2, yB + 1, z, deepInit ? WATER : AIR);       // init head: water=2-deep / air=1-deep
            for (int x = tr.baseX + 3; x <= tr.baseX + 5; x++) {        // 1-deep run
                set(x, yB, z, WATER);
                set(x, yB + 1, z, AIR);
            }
            set(tr.baseX + 6, yB, z, WATER);                           // the 1-TALL hole: water feet, stone wall above
            for (int x = tr.baseX + 7; x <= tr.baseX + 8; x++) {       // dry bank (floor stays stone at yB-1)
                set(x, yB, z, AIR);
                set(x, yB + 1, z, AIR);
            }
        }

        /** The cruise-overshoot repro: a fully-enclosed, 1-wide SUBMERGED sprint-swim L-tunnel carved out of a
         *  solid stone block. A LONG +X approach leg (z=zc) runs to a corner, where the tunnel turns 90° to +Z.
         *  Stone floor + stone CEILING (y = S+1) over every carved cell keep the bot prone/submerged the whole
         *  way (no surface to breach → it stays in fast SprintSwim). The single passable straight-ahead branch
         *  off the corner (baseX+11, zc) is an UP-bubble-column (soul-sand floor + water) — classified
         *  impassable, so the ONLY route is +X→corner→+Z, and the planner routes AROUND the column. But that
         *  column sits exactly where +X cruise momentum overshoots the corner: a full-forward SprintSwim should
         *  coast the bot straight into it (drift off-path) instead of decelerating into the turn. Every cell that
         *  is not the tunnel or the column is solid stone, so the corner cannot be cut diagonally. */
        void buildSwimTurn(Trial tr) {
            int zc = tr.zc;
            int yFloor = tr.yFloor;              // = S - depth
            int yTop = S;                        // top water layer
            int yCeil = S + 1;                   // stone ceiling (forces submerged prone swimming)
            int xEntry = tr.baseX + 2;
            int xTurn = tr.baseX + 10;           // corner cell (xTurn, zc)
            int xBubble = xTurn + 1;             // (baseX+11, zc): the +X straight-ahead overshoot cell
            int zEnd = zc + 8;                   // end of the +Z leg (the goal cell)

            // (1) Fill the whole tile bounding box (a 1-cell wall margin around every carved cell) with solid
            //     stone. Carving then leaves ONLY the 1-wide tunnel + the bubble-column cell open.
            int bx0 = xEntry - 1, bx1 = xBubble + 1;   // baseX+1 .. baseX+12
            int bz0 = zc - 1,     bz1 = zEnd + 1;      // (zc-1) .. (zEnd+1)
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    for (int y = yFloor - 1; y <= yCeil; y++) set(x, y, z, STONE);
                }
            }

            // (2) Carve the +X approach leg (z=zc), full water from just above the floor to the top layer.
            for (int x = xEntry; x <= xTurn; x++) {
                for (int y = yFloor + 1; y <= yTop; y++) set(x, y, zc, WATER);
            }

            // (3) Carve the +Z leg (x=xTurn), same water column — the corner cell (xTurn,zc) is shared.
            for (int z = zc; z <= zEnd; z++) {
                for (int y = yFloor + 1; y <= yTop; y++) set(xTurn, y, z, WATER);
            }

            // (4) The UP-bubble-column straight ahead of the corner: soul-sand floor + water above (vanilla
            //     forms the column). Reuses the bubbleup trial's construction (floorFeature places SOUL_SAND at
            //     the floor with water above → an impassable upward column). It is cardinal-adjacent to the
            //     corner (so momentum can drift the bot into it) but walled off from the +Z leg (so it is a
            //     dead-end branch the planner routes AROUND, never through).
            set(xBubble, yFloor, zc, SOUL_SAND);
            for (int y = yFloor + 1; y <= yTop; y++) set(xBubble, y, zc, WATER);
        }

        /** The momentum-overshoot-into-a-column repro: a submerged bubble-column SERPENTINE. The tank water is
         *  4-deep (floor at yFloor = S-4, water yFloor+1..S). A 1-wide safe CHANNEL winds boustrophedon through
         *  it — a +X leg at z=zc, one lane over in +Z, a -X leg at z=zc+2, one more lane over, a +X leg at
         *  z=zc+4 — its floor plain STONE (no column). EVERY other water cell has a SOUL_SAND floor → an UP
         *  bubble-column (the maze "walls"; the INVERSE of the usual centre-column trials, where the columns are
         *  the walls and the safe path is the gaps between them). Adjacent lanes on either side of the channel
         *  are columns, so at each turn the cell STRAIGHT AHEAD along the incoming leg is a column: cruise
         *  momentum that fails to brake into the turn drifts the bot into an impassable column. Only the FIRST
         *  leg is roofed (STONE ceiling at S+1) — it pins the bot submerged/prone through the opening +X cruise;
         *  over every LATER leg the sky is OPEN, so a column clip EJECTS the bot fully out of the water
         *  (isInWater false → loses the prone Pose.SWIMMING) with no ceiling to hold it down. Cells outside the
         *  water region are solid stone (walls) so the corners can't be cut diagonally. */
        void buildSwimMaze(Trial tr) {
            int zc = tr.zc;
            int yFloor = tr.yFloor;              // = S - 4
            int yTop = S;                        // top water layer
            int xW = tr.baseX + 2;               // west channel end (leg starts/ends)
            int xE = tr.baseX + 10;              // east channel end (9-cell legs)
            int z0 = zc;                         // leg 1 lane (+X)
            int z1 = zc + 2;                     // leg 2 lane (-X)
            int z2 = zc + 4;                     // leg 3 lane (+X)

            // Water region (holds the channel + every column "wall"), and the surrounding stone margin.
            int rx0 = tr.baseX + 1, rx1 = tr.baseX + 11;   // x span of the water rectangle
            int rz0 = z0,           rz1 = z2;              // z span (zc .. zc+4)

            // (1) Fill the whole tile bounding box (water rectangle + a 1-cell stone margin, from the floor base
            //     up to the surface) with solid stone. Carving then leaves ONLY the water columns open.
            int bx0 = rx0 - 1, bx1 = rx1 + 1;
            int bz0 = rz0 - 1, bz1 = rz1 + 1;
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    for (int y = yFloor - 1; y <= yTop; y++) set(x, y, z, STONE);
                }
            }

            // (2) Carve every water-rectangle cell into a 4-deep water column with a SOUL_SAND floor → vanilla
            //     forms an UP-bubble-column in each (the default; the safe channel overrides its floor below).
            for (int x = rx0; x <= rx1; x++) {
                for (int z = rz0; z <= rz1; z++) {
                    set(x, yFloor, z, SOUL_SAND);
                    for (int y = yFloor + 1; y <= yTop; y++) set(x, y, z, WATER);
                }
            }

            // (3) Stamp the safe serpentine channel: its floor is plain STONE (no bubble). Boustrophedon —
            //     leg 1 (+X) at z0, turn up to z1, leg 2 (-X) at z1, turn up to z2, leg 3 (+X) at z2. The turn
            //     connectors run through the intermediate column lanes (zc+1 / zc+3) at the leg ends, so the
            //     only way across is a genuine 90° corner (the overshoot cell straight ahead stays a column).
            for (int x = xW; x <= xE; x++) set(x, yFloor, z0, STONE);   // leg 1: +X at z0
            for (int z = z0; z <= z1; z++)  set(xE, yFloor, z, STONE);  // turn 1: +Z at the east end (z0 -> z1)
            for (int x = xW; x <= xE; x++) set(x, yFloor, z1, STONE);   // leg 2: -X at z1
            for (int z = z1; z <= z2; z++)  set(xW, yFloor, z, STONE);  // turn 2: +Z at the west end (z1 -> z2)
            for (int x = xW; x <= xE; x++) set(x, yFloor, z2, STONE);   // leg 3: +X at z2

            // (4) Roof ONLY the first leg (STONE ceiling at S+1) so the bot stays pinned submerged/prone through
            //     the opening +X cruise. Over every later leg the sky is left OPEN (a column clip ejects the bot
            //     out of the water) — the lethality mechanism.
            for (int x = xW; x <= xE; x++) set(x, yTop + 1, z0, STONE);
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
                OrebitCommon.LOGGER.error("[Orebit/swim] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/swim] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-swim-exit");
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
