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
 * Headless PARKOUR-MOVEMENT diagnostic harness (a sibling of {@link HeadlessAutotest}, armed by its own
 * {@code -Dorebit.parkour} flag). It builds a synthetic obstacle course floating high over a flat world and
 * drives the bot through a SERIES of isolated single-jump challenges — each a specific parkour SHAPE under a
 * specific APPROACH condition — recording pass/fail plus a per-tick trajectory, so the over/undershoot
 * pathologies can be diagnosed from data.
 *
 * <p><b>Why a bespoke course.</b> Over/undershoot is an EXECUTOR pathology (the physical jump misses its
 * landing), invisible to a route-level pass/fail. Isolating one jump on a known platform with a lethal drop
 * on a miss makes each shape a reproducible experiment; the trajectory dump captures the arc and the takeoff
 * speed (position-delta on the ground&rarr;air tick), the quantity the physics envelope assumes.
 *
 * <p><b>Generalised tile model.</b> A trial's APPROACH direction is independent of its JUMP vector, so the
 * same builder stages a head-on cardinal jump, a diagonal jump, a {@code (c,±1)} OFFSET jump (the gated
 * "2-forward-1-lateral" tier), and a 90°-TURN approach (run one way, jump another — the misaligned-approach
 * case the owner suspects behind the real-play undershoot). Each shape runs under both precursor conditions:
 * <ul>
 *   <li><b>walkin</b> — a short runway of non-sprinting {@code Traverse}/{@code Diagonal} steps, so the bot
 *       enters the takeoff block carrying walk-{@code v∞} momentum (the Traverse-preceded case);</li>
 *   <li><b>rest</b> — teleported onto the takeoff block AT REST, so the RUNUP must sprint up from a
 *       standstill within that one block (the pillar/fall-preceded worst case).</li>
 * </ul>
 * The harness bakes in NO fix and asserts NO expected outcome. Placement and mining are OFF in the course
 * config so the ONLY way across a gap is a jump; a miss falls ~200 blocks and dies (an unambiguous FAIL).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.parkour} is
 * set. Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class ParkourCourse {

    private ParkourCourse() {}

    private static final String RESULT_FILE = "orebit-parkour-result.properties";
    private static final String TRACE_FILE = "orebit-parkour-trace.txt";

    /** Floor-cell Y of the takeoff platform (feet stand at {@code Y0+1}); high enough that a miss is fatal. */
    private static final int Y0 = 150;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    /** Tiles are laid in a compact GRID (snake ordering, so consecutive trials are always adjacent and
     *  teleports stay inside the loaded+built nav bubble — the long linear course left far tiles unbuilt). */
    private static final int COLS = 6;
    private static final int STRIDE = 26; // grid cell size (> the longest tile span so tiles never touch)
    /** Runway length in cells (the takeoff cell is the last). */
    private static final int RUN = 7;
    /** REACH landing platform length beyond the landing cell (generous). */
    private static final int GOAL_LEN = 6;
    /** Where on the landing platform the goal sits (cells past the landing, along the continuation axis). */
    private static final int GOAL_REACH = 4;
    /** PRECISION walkway length (perpendicular to the jump line) from the 1-wide landing to the goal. */
    private static final int WALK = 5;

    /** Ticks to let the WHOLE starting area's nav grid build before the first goto (chunk gen + nav build). */
    private static final int WARMUP_TICKS = 120;
    /** Ticks to let the local nav grid build after each subsequent teleport before issuing the goto. */
    private static final int SETTLE_TICKS = 40;
    /** If nav gives up within this many attempt ticks, treat it as nav-not-yet-built and re-issue (up to
     *  {@link #MAX_NAV_RETRY}) rather than failing — a harness robustness measure, not a bot behaviour. */
    private static final int NAV_RETRY_WINDOW = 40;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). */
    private static final int ATTEMPT_BUDGET = 400;

    private static final BlockState FLOOR = Blocks.STONE.defaultBlockState();
    private static final BlockState SLAB = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.parkour") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/parkour] armed: {} trials", course.trials.size());
    }

    private enum Template { REACH, PRECISION, OFFSET }
    private enum Approach { WALKIN, REST }

    /** One jump challenge: an approach direction + a jump vector + a landing template + a precursor condition,
     *  with all world geometry precomputed from its base X band. */
    private static final class Trial {
        final String name;
        boolean slabRunway;
        final Approach approach;
        final int rdx, rdz;             // approach (runway) direction
        final int jdx, jdy, jdz;        // jump vector: takeoff cell -> landing cell
        final Template template;
        final boolean walled;           // block diagonal corner-cuts (force the cardinal jump after a turn)
        final int baseX, baseZ;
        final int takeoffX, takeoffZ;
        final int landX, landZ, landY;
        final int landedFeetY;          // expected feet Y on a clean landing (= landY + 1)
        final int cdx, cdz;             // continuation axis (dominant horizontal of the jump vector)
        final boolean wideRunway;       // 3-wide straight runway (only when approach == continuation, cardinal)
        final boolean diagRunway;       // 1-wide diagonal runway strip
        final BlockPos goal;
        final double startX, startZ;
        final float startYaw;
        final double ujx, ujz;          // normalized horizontal jump direction (for along-line projection)

        Trial(String name, Approach approach, int rdx, int rdz, int jdx, int jdy, int jdz,
                Template template, boolean walled, int baseX, int baseZ) {
            this.name = name;
            this.approach = approach;
            this.rdx = rdx;
            this.rdz = rdz;
            this.jdx = jdx;
            this.jdy = jdy;
            this.jdz = jdz;
            this.template = template;
            this.walled = walled;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.takeoffX = baseX + (RUN - 1) * rdx;
            this.takeoffZ = baseZ + (RUN - 1) * rdz;
            this.landX = takeoffX + jdx;
            this.landZ = takeoffZ + jdz;
            this.landY = Y0 + jdy;
            this.landedFeetY = landY + 1;
            // Continuation axis: the dominant horizontal component of the jump (where the landing platform and
            // goal extend). Diagonal (|jdx|==|jdz|) and offset (|jdx|>|jdz|) both continue along X here.
            if (Math.abs(jdx) >= Math.abs(jdz)) { this.cdx = Integer.signum(jdx); this.cdz = 0; }
            else { this.cdx = 0; this.cdz = Integer.signum(jdz); }
            this.diagRunway = rdx != 0 && rdz != 0;
            this.wideRunway = template == Template.REACH && !diagRunway && rdx == cdx && rdz == cdz;
            if (template == Template.PRECISION) {
                int px = -cdz, pz = cdx; // perpendicular walkway (overshoot along the jump falls off the ledge)
                this.goal = new BlockPos(landX + WALK * px, landY + 1, landZ + WALK * pz);
            } else { // REACH / OFFSET continue along the continuation axis
                this.goal = new BlockPos(landX + GOAL_REACH * cdx, landY + 1, landZ + GOAL_REACH * cdz);
            }
            if (approach == Approach.WALKIN) {
                this.startX = baseX + 0.5;
                this.startZ = baseZ + 0.5;
                this.startYaw = (float) Math.toDegrees(Math.atan2(-rdx, rdz)); // face the approach
            } else {
                this.startX = takeoffX + 0.5;
                this.startZ = takeoffZ + 0.5;
                this.startYaw = (float) Math.toDegrees(Math.atan2(-jdx, jdz)); // face the jump
            }
            double len = Math.sqrt((double) (jdx * jdx + jdz * jdz));
            this.ujx = jdx / len;
            this.ujz = jdz / len;
        }

        double proj(double x, double z) {
            return (x - (takeoffX + 0.5)) * ujx + (z - (takeoffZ + 0.5)) * ujz;
        }
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
        boolean leftTakeoff;
        double takeoffSpeed = -1;   // position-delta horizontal speed the tick the bot left the ground
        boolean wasGrounded = true;
        double prevX, prevZ;
        String prevMove = "";
        int passed, failed;

        Course() {
            buildTrialList();
        }

        /** The catalogue. Cardinal head-on shapes test the envelope; PRECISION (1-wide ledge) tests overshoot;
         *  OFFSET (c,±1) and 90°-TURN approaches hunt the real-play undershoot; the WALLED turn isolates whether
         *  that undershoot is the planner's diagonal corner-cut or the executor's misaligned-momentum takeoff. */
        void buildTrialList() {
            // Cardinal head-on (approach == jump == +X). name, jump(dx,dy,dz), template.
            card("flat1", 2, 0, 0, Template.REACH);
            card("flat2", 3, 0, 0, Template.REACH);
            card("flat3", 4, 0, 0, Template.REACH);
            card("rise1", 2, 1, 0, Template.REACH);
            card("rise2", 3, 1, 0, Template.REACH);
            card("rise3", 4, 1, 0, Template.REACH); // ratified-OUT (rising-3 unmakeable) — confirm it misses
            card("fall1", 2, -1, 0, Template.PRECISION); // overshoot (shallow ledge)
            card("fall2", 3, -1, 0, Template.PRECISION); // overshoot (shallow ledge)
            card("fall3", 4, -1, 0, Template.REACH);
            card("fall4", 5, -1, 0, Template.REACH);
            card("falld2g4", 5, -2, 0, Template.REACH);
            card("falld3g4", 5, -3, 0, Template.REACH);
            // Diagonal (approach == jump == +X+Z). diag3 (a 3-gap, 4-step diagonal) is the ratified-OUT row
            // the design doc derives as unmakeable (DIAG_MAX 3→2) — tested HEAD-ON here to confirm it misses
            // even with a clean aligned approach (it's what the turnflat corner-cut routes).
            diag("diag1", 2, 0, 2);
            diag("diag2", 3, 0, 3);
            diag("diag3", 4, 0, 4);
            // OFFSET (c,±1): approach +X, jump lands 1 cell OFF the cardinal line — the gated tier. The cardinal
            // line is kept pure gap (1-wide runway + 1-wide off-axis landing) so the aligned scan finds no
            // landing and ARMS the offset probe; nothing else can reach the landing. Both lateral signs.
            offset("offset2p", 2, 1);
            offset("offset3p", 3, 1);
            offset("offset2n", 2, -1);
            // 90°-TURN approach: run +Z, jump +X (walkin only — the turn IS the condition). Tight shapes.
            turn("turnflat2", 3, 0, 0, false);
            turn("turnflat3", 4, 0, 0, false);
            turn("turnrise2", 3, 1, 0, false);
            // WALLED turn: same turn, but a 2-high wall along the +X side of the runway blocks the diagonal
            // corner-cut, forcing the CARDINAL +X jump. If it still undershoots -> executor (misaligned
            // momentum) is the root; if it passes -> the planner's corner-cut choice was.
            turn("turnflat2w", 3, 0, 0, true);
            turn("turnflat3w", 4, 0, 0, true);
            // ---- Slab-takeoff reach trials ----
            slabCard("slabflat2", 3, 0, 0);           // slab takeoff, node-flat 2-gap (physically +0.5 rise)
            slabCard("slabflat3", 4, 0, 0);           // slab takeoff, node-flat 3-gap (the reach-reduction case)
            slabCard("slabrise1", 2, 1, 0);           // slab takeoff, rise+1 — expect the rise() gate to refuse
        }

        /** The grid base (snake-ordered) for the trial at position {@code trials.size()}. */
        int[] nextBase() {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake: keep consecutive trials adjacent
            return new int[]{ BASE_X + col * STRIDE, BASE_Z + row * STRIDE };
        }

        void addTrial(String name, Approach a, int rdx, int rdz, int jdx, int jdy, int jdz,
                Template t, boolean walled) {
            int[] b = nextBase();
            trials.add(new Trial(name, a, rdx, rdz, jdx, jdy, jdz, t, walled, b[0], b[1]));
        }

        /** Cardinal-approach shape (approach dir = dominant jump axis) under both precursor conditions. */
        void card(String name, int jdx, int jdy, int jdz, Template t) {
            int rdx = jdx >= 0 ? 1 : -1;
            addTrial(name + ".walkin", Approach.WALKIN, rdx, 0, jdx, jdy, jdz, t, false);
            addTrial(name + ".rest", Approach.REST, rdx, 0, jdx, jdy, jdz, t, false);
        }

        /** Slab-takeoff variant of {@link #card}: the whole runway is bottom-slabs (surface +0.5). */
        void slabCard(String name, int jdx, int jdy, int jdz) {
            int rdx = jdx >= 0 ? 1 : -1;
            addSlabTrial(name + ".walkin", Approach.WALKIN, rdx, 0, jdx, jdy, jdz);
            addSlabTrial(name + ".rest", Approach.REST, rdx, 0, jdx, jdy, jdz);
        }

        void addSlabTrial(String name, Approach a, int rdx, int rdz, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            Trial t = new Trial(name, a, rdx, rdz, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]);
            t.slabRunway = true;
            trials.add(t);
        }

        void diag(String name, int jdx, int jdy, int jdz) {
            addTrial(name + ".walkin", Approach.WALKIN, 1, 1, jdx, jdy, jdz, Template.REACH, false);
            addTrial(name + ".rest", Approach.REST, 1, 1, jdx, jdy, jdz, Template.REACH, false);
        }

        /** OFFSET (c,±lat) knight's-move jump, both precursor conditions. */
        void offset(String name, int c, int lat) {
            addTrial(name + ".walkin", Approach.WALKIN, 1, 0, c, 0, lat, Template.OFFSET, false);
            addTrial(name + ".rest", Approach.REST, 1, 0, c, 0, lat, Template.OFFSET, false);
        }

        /** 90°-turn: approach along +Z, jump along +X — walkin only. */
        void turn(String name, int jdx, int jdy, int jdz, boolean walled) {
            addTrial(name + ".turn", Approach.WALKIN, 0, 1, jdx, jdy, jdz, Template.REACH, walled);
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.parkour.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitParkour:owner".getBytes(StandardCharsets.UTF_8)),
                        "Parkour"));
                owner.setPos(first.startX, Y0 + 1, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit parkour course trace  (T <trial> <tick> x y z | spd vy | onGround | move)\n");
                trace.write("legend: spd = position-delta horizontal speed (b/t); TAKEOFF marks the onGround->air flip\n\n");
                OrebitCommon.LOGGER.info("[Orebit/parkour] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/parkour] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr); // each trial owns a distinct grid cell — build it once on entry
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.setMode(AllyBotEntity.Mode.STAY);
            bot.setPos(tr.startX, Y0 + 1, tr.startZ);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.setYRot(tr.startYaw);
            bot.setYHeadRot(tr.startYaw);
            settling = true;
            settleTicks = 0;
            attemptTicks = 0;
            navRetries = 0;
            leftTakeoff = false;
            takeoffSpeed = -1;
            wasGrounded = true;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : approach(%d,%d) jump(%d,%d,%d) %s %s takeoff=(%d,%d,%d) land=(%d,%d,%d) goal=(%d,%d,%d)\n",
                        tr.name, tr.rdx, tr.rdz, tr.jdx, tr.jdy, tr.jdz, tr.template, tr.approach,
                        tr.takeoffX, Y0, tr.takeoffZ, tr.landX, tr.landY, tr.landZ,
                        tr.goal.getX(), tr.goal.getY() - 1, tr.goal.getZ()));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                // The first trial waits for the whole starting area to gen + nav-build; later trials only need
                // the short local settle (snake ordering keeps each teleport inside the already-built bubble).
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                if (++settleTicks < target) return;
                settling = false;
                bot.comeTo(tr.goal);
                return;
            }

            attemptTicks++;
            trace(tr);

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            double proj = tr.proj(bot.getX(), bot.getZ());
            if (proj > 0.6) leftTakeoff = true;
            if (bot.mode() == AllyBotEntity.Mode.STAY && bot.getY() > tr.landedFeetY - 1.5) {
                record(tr, "PASS", "reached goal");
                return;
            }
            if (leftTakeoff && bot.getY() < tr.landedFeetY - 1.6) {
                record(tr, "FAIL", "fell");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                // Nav-not-yet-built after a teleport looks like a give-up; retry the goto a few times before
                // calling it a real failure (the identical jump can pass once its grid finishes building).
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
            double x = bot.getX(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean onGround = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            try {
                // On a move change, dump the ACTUAL segment the planner routed (from/to cells) — so a greedy
                // diagonal corner-cut off a turn shows its real takeoff/landing cells, not the intended jump.
                if (!move.equals(prevMove)) {
                    BotNavigator nav = bot.navigator();
                    trace.write(String.format(Locale.ROOT, "  MOVE %s seg=(%d,%d,%d)->(%d,%d,%d)\n", move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ()));
                    prevMove = move;
                }
                if (wasGrounded && !onGround) {
                    takeoffSpeed = spd;
                    trace.write(String.format(Locale.ROOT, "  TAKEOFF spd=%.4f at x=%.3f z=%.3f (proj=%.3f)\n",
                            spd, x, z, tr.proj(x, z)));
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-16s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %s\n",
                        tr.name, attemptTicks, x, bot.getY(), z, spd, v.y, onGround ? 1 : 0, move));
            } catch (IOException ignored) { }
            wasGrounded = onGround;
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT, "%s = %s (%s) takeoffSpd=%s finalY=%.2f",
                    tr.name, result, reason,
                    takeoffSpeed < 0 ? "n/a" : String.format(Locale.ROOT, "%.4f", takeoffSpeed),
                    bot.getY()));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/parkour] {} -> {} ({}) takeoffSpd={} finalY={}",
                    tr.name, result, reason,
                    takeoffSpeed < 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", takeoffSpeed),
                    String.format(Locale.ROOT, "%.2f", bot.getY()));
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        /** Place a trial's blocks: runway + landing/goal geometry, one solid layer. Chunks sync-load on write. */
        void buildTile(Trial tr) {
            // Runway along the approach direction, ending at the takeoff cell.
            for (int k = 0; k < RUN; k++) {
                int cx = tr.baseX + k * tr.rdx;
                int cz = tr.baseZ + k * tr.rdz;
                if (tr.slabRunway) placeState(cx, Y0, cz, SLAB);
                else if (tr.wideRunway) placeWide(cx, Y0, cz, tr.rdx, tr.rdz);
                else place(cx, Y0, cz);
            }
            if (tr.walled) {
                // A 2-high wall along the +continuation side of the runway (every runway cell BEFORE the takeoff
                // row) blocks any diagonal corner-cut out of the runway — its corner column is walled, so the
                // DiagonalParkour candidate is rejected and only the cardinal jump from the takeoff cell remains.
                for (int k = 0; k < RUN - 1; k++) {
                    int cx = tr.baseX + k * tr.rdx + tr.cdx;
                    int cz = tr.baseZ + k * tr.rdz + tr.cdz;
                    place(cx, Y0 + 1, cz);
                    place(cx, Y0 + 2, cz);
                }
            }
            if (tr.template == Template.REACH) {
                for (int k = 0; k <= GOAL_LEN; k++) {
                    int cx = tr.landX + k * tr.cdx;
                    int cz = tr.landZ + k * tr.cdz;
                    placeWide(cx, tr.landY, cz, tr.cdx, tr.cdz); // 3-wide landing platform
                }
            } else if (tr.template == Template.OFFSET) {
                // 1-wide landing strip along the continuation axis, OFF the cardinal line — so the aligned scan
                // finds no landing (arming the offset probe) and the only reachable floor is the offset cell.
                for (int k = 0; k <= GOAL_LEN; k++) {
                    place(tr.landX + k * tr.cdx, tr.landY, tr.landZ + k * tr.cdz);
                }
            } else { // PRECISION: 1-wide landing cell + a perpendicular walkway to the goal (drop beyond it)
                place(tr.landX, tr.landY, tr.landZ);
                int px = -tr.cdz, pz = tr.cdx;
                for (int k = 1; k <= WALK; k++) place(tr.landX + k * px, tr.landY, tr.landZ + k * pz);
            }
        }

        void place(int x, int y, int z) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), FLOOR);
        }

        void placeState(int x, int y, int z, BlockState s) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), s);
        }

        /** Place a cell plus its two perpendicular neighbours (a 3-wide platform along {@code (ux,uz)}). */
        void placeWide(int x, int y, int z, int ux, int uz) {
            int px = -uz, pz = ux;
            place(x, y, z);
            place(x + px, y, z + pz);
            place(x - px, y, z - pz);
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
                OrebitCommon.LOGGER.error("[Orebit/parkour] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/parkour] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-parkour-exit");
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
