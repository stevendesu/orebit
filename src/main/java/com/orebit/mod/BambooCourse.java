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
import net.minecraft.world.phys.Vec3;

/**
 * Headless OFFSET-COLLIDER MINING harness (a sibling of {@link GateCourse} / {@link SwimMineCourse}, armed
 * by its own {@code -Dorebit.bamboo} flag) — the live physics reproduction of the 2026-08-29 long-flagship
 * wedge at {@code (352,71,512)}, where the bot stood in a bamboo cell it had been told to mine, never mined
 * it, and hung for 37,097 ticks.
 *
 * <h2>The pathology being reproduced</h2>
 * The planner is RIGHT about bamboo: the nav grid treats it as a wall, and the search duly folded
 * {@code BREAK(352,71,512) BREAK(352,72,512)} onto the Traverse that enters the cell. One tick later a
 * region re-derive ({@code REVEALED unbuilt->built ... verdict=SWAPPED}) replaced the block plan, and the
 * bot ended up standing IN the bamboo cell with the stalk intact, and never mined it across 934 further
 * searches: {@code BREAK(352,71,512)} appears exactly ONCE in a 124k-line log. (The obvious explanation —
 * "a start cell is never a break target" — is REFUTED by the {@code trapped} tile below, which passes.
 * See the tile list for what remains.)
 *
 * <h2>Why bamboo, and why THIS column</h2>
 * Bamboo is the cheap reproduction of a general hazard: a block whose COLLISION is narrower than its cell,
 * so the bot can stand in the same cell it must mine. {@code BambooStalkBlock.getCollisionShape} is
 * {@code Block.column(3.0, 0.0, 16.0)} — a full-height 3/16 post — displaced by
 * {@code blockState.getOffset(blockPos)}, and {@code OffsetType.XZ} hashes
 * {@code Mth.getSeed(x, 0, z)}: <b>X and Z only, Y hardcoded to 0</b>.
 *
 * <p>So the sub-cell post position is a pure function of (x,z), and every tile here uses the SAME column
 * {@code x=}{@link #BAMBOO_X}{@code , z=}{@link #BAMBOO_Z} as the flagship wedge — stacked at different Y,
 * which the hash ignores. At (352,512) the hash yields {@code l & 15 == 15}, the MAXIMUM eastward offset
 * {@code dx = +0.25} (the raw term {@code ((l&15)/15 - 0.5) * 0.5} is not clamped, since
 * {@code BlockBehaviour.getMaxHorizontalOffset()} is 0.25 and bamboo does not override it). The post
 * therefore spans {@code x ∈ [352.65625, 352.84375]}, and a 0.6-wide bot pressed against its west face
 * comes to rest at {@code 352.65625 − 0.3 = 352.35625} — matching the wedge's logged {@code x=352.356} to
 * the log's own rounding. The bot is FLUSH AGAINST the post, not inside it (an earlier "embedded" reading
 * came from mis-assuming a 0.125 clamp; owner refuted it live).
 *
 * <p><b>Do not relocate these tiles in X or Z.</b> Any other column draws a different offset and may place
 * the post where a bot can simply walk past it, silently turning every tile into a free walk.
 *
 * <h2>The tiles</h2>
 * <ol>
 *   <li><b>stalk</b> — a 1-wide stone corridor running +X with the bamboo as the only way through. The
 *       straight-line form: Traverse in, Traverse out. PASS requires the stalk BROKEN and the goal
 *       reached.</li>
 *   <li><b>dogleg</b> — the flagship's exact shape: the corridor arrives at the bamboo cell along
 *       {@code z=512} and leaves along {@code z=513}, so the exit step is the {@code Diagonal} that wedged.
 *       {@code Diagonal} deliberately keeps intact-transit pricing and folds no breaks of its own, so if
 *       the entering Traverse's break is lost there is nothing downstream to recover it.</li>
 *   <li><b>trapped</b> — the PERMANENCE test, needing no plan swap: the bot STARTS inside the stalk's own
 *       cell, at the exact rest position it settles into when pressed against the post
 *       ({@code x = 352.35625}), so {@code blockPosition()} IS the cell it must break. <b>It PASSES in 17
 *       ticks</b>, which REFUTES the tempting structural claim that "the bot's own cell is the search
 *       START and a start cell is never a break target". A bot standing in a cell it must break schedules
 *       and executes that break perfectly well. Kept as the control that pins the refutation.</li>
 *   <li><b>trappeddogleg</b> — {@code trapped}'s only live variable changed: the corridor dog-legs, so the
 *       exit move is a {@code Diagonal} rather than a {@code Traverse}. That is the flagship's actual
 *       combination, and the two differ in exactly the way that matters — {@code Traverse} calls
 *       {@code MovementContext.transitOrBreak} and CAN fold a break, while {@code Diagonal} deliberately
 *       keeps intact-transit pricing and folds NONE (CLAUDE.md: "Diagonal/Fall/the airborne family
 *       deliberately keep intact-transit pricing"). So a bot whose only way out of an occupied cell is a
 *       Diagonal has no move in its vocabulary that can schedule the break — which is the candidate
 *       mechanism for the flagship's 934 searches with ZERO break edits.</li>
 * </ol>
 * Every tile FAILS on timeout — a hold is not a failure, and the wedge is precisely a bot holding forever.
 *
 * <p><b>What the passing tiles rule out.</b> {@code stalk}/{@code dogleg}/{@code trapped} all pass, so the
 * break path works, the offset post traps nothing on approach, and mere occupancy of a to-be-broken cell is
 * survivable. Whatever the flagship hit needs more than geometry: either the Diagonal-only exit
 * ({@code trappeddogleg}), or the swapped/seam-truncated plan state that a fully-built synthetic course
 * cannot reproduce (see the config template's KNOWN LIMITATION note).
 *
 * <p><b>Config</b> ({@code scripts/bamboo/orebit.properties}): {@code mining.canMine=true} (the break under
 * test), {@code placement.canPlace=false} (no bridging around the post).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.bamboo} is
 * set.
 */
public final class BambooCourse {

    private BambooCourse() {}

    private static final String RESULT_FILE = "orebit-bamboo-result.properties";
    private static final String TRACE_FILE = "orebit-bamboo-trace.txt";

    /** The flagship wedge's own column. The offset hash reads X and Z only — see the class doc. */
    private static final int BAMBOO_X = 352;
    private static final int BAMBOO_Z = 512;

    /** Base Y of the first tile's FLOOR. Free to choose: {@code Mth.getSeed(x, 0, z)} ignores Y. */
    private static final int Y0 = 150;
    /** Vertical spacing between tiles (they share one X/Z column, so they stack). Three sections clear. */
    private static final int Y_STRIDE = 24;

    private static final int WARMUP_TICKS = 160;
    private static final int SETTLE_TICKS = 60;
    private static final int ATTEMPT_BUDGET = 900;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BAMBOO = Blocks.BAMBOO.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.bamboo") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/bamboo] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        STALK,   // straight 1-wide corridor through the stalk
        DOGLEG,  // arrive along z=512, leave along z=513 — the flagship's Traverse-in / Diagonal-out shape
        TRAPPED,        // START inside the stalk cell, straight corridor -> the exit move is a Traverse
        TRAPPED_DOGLEG, // START inside the stalk cell, dog-legged -> the exit move is a DIAGONAL
        TURNHOLD        // approach along +Z and TURN east into the stalk — cross-axis momentum at the hold
    }

    /** One bamboo challenge: a kind + its tile floor Y, with start/goal geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int y;                 // tile FLOOR y (corridor body is y+1 / y+2)
        final int exitZ;             // Z of the corridor east of the stalk

        final double startX, startY, startZ;
        final float startYaw;
        final BlockPos goal;
        final BlockPos stalkLo;      // the feet-cell bamboo — the cell the bot must break
        final BlockPos stalkHi;      // the head-cell bamboo (cascades when the lower one goes)
        final int minFloorY;

        Trial(String name, Kind kind, int y) {
            this.name = name;
            this.kind = kind;
            this.y = y;
            this.exitZ = (kind == Kind.DOGLEG || kind == Kind.TRAPPED_DOGLEG) ? BAMBOO_Z + 1 : BAMBOO_Z;
            // The exit MOVE is what the dog-leg selects, and it is the live variable: a straight corridor
            // leaves by Traverse, which calls MovementContext.transitOrBreak and CAN fold a break; a
            // dog-leg leaves by Diagonal, which deliberately keeps intact-transit pricing and folds NONE.
            // TRAPPED (straight) passes in 17 ticks; TRAPPED_DOGLEG is the flagship's actual combination.
            this.minFloorY = y - 6;
            // TRAPPED starts INSIDE the stalk's own cell, at the exact rest position a bot pressed against
            // the post settles into: the post spans [352.65625, 352.84375], so a 0.6-wide body rests at
            // 352.65625 - 0.3 = 352.35625 — the flagship's logged x=352.356. blockPosition() is therefore
            // (352, y+1, 512): the bot's foot cell IS the cell it must break, which is the whole point.
            // TURNHOLD approaches along +Z one cell WEST of the stalk, so the bot reaches the hold cell
            // carrying CROSS-AXIS momentum and must then turn east into the stalk — the flagship's actual
            // arrival (it entered (351,71,512) from (351,71,511) at vel z=+0.113, then the next step was
            // +X). That perpendicular momentum is what a position-only P-law cannot arrest, and the four
            // straight-approach tiles all pass precisely because their momentum is ALIGNED with the anchor.
            this.startX = (kind == Kind.TRAPPED || kind == Kind.TRAPPED_DOGLEG)
                    ? BAMBOO_X + 6.5 / 16.0 + 0.25 - 0.3
                    : (kind == Kind.TURNHOLD ? BAMBOO_X - 1 + 0.5 : BAMBOO_X - 4.5);
            this.startY = y + 1;
            this.startZ = kind == Kind.TURNHOLD ? BAMBOO_Z - 7 + 0.5 : BAMBOO_Z + 0.5;
            this.startYaw = kind == Kind.TURNHOLD
                    ? (float) Math.toDegrees(Math.atan2(0, 1))    // face +Z
                    : (float) Math.toDegrees(Math.atan2(-1, 0));  // face +X
            this.goal = new BlockPos(BAMBOO_X + 4, y + 1, exitZ);
            this.stalkLo = new BlockPos(BAMBOO_X, y + 1, BAMBOO_Z);
            this.stalkHi = new BlockPos(BAMBOO_X, y + 2, BAMBOO_Z);
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
        boolean overallDone;
        double closest;
        double prevX, prevZ;
        String prevMove = "";
        int passed, failed;

        // per-trial observation
        boolean stalkBroken;         // the feet-cell bamboo went away at some point
        boolean everEnteredCell;     // the bot's foot cell was ever the stalk cell
        double maxX;

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("stalk", Kind.STALK);
            add("dogleg", Kind.DOGLEG);
            add("trapped", Kind.TRAPPED);
            add("trappeddogleg", Kind.TRAPPED_DOGLEG);
            add("turnhold", Kind.TURNHOLD);
        }

        void add(String name, Kind kind) {
            trials.add(new Trial(name, kind, Y0 + trials.size() * Y_STRIDE));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.bamboo.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitBamboo:owner".getBytes(StandardCharsets.UTF_8)),
                        "Bamboo"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit bamboo course trace"
                        + "  (T <trial> <tick> x y z | spd vy | onGround | hp | stalk=<B/.> | move)\n");
                trace.write("stalk = live state of the FEET-cell bamboo (B=present .=broken); the whole point\n");
                trace.write("is whether it is ever broken. BREAK lines mark the transition.\n\n");
                OrebitCommon.LOGGER.info("[Orebit/bamboo] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/bamboo] setup threw", t);
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
            closest = Double.MAX_VALUE;
            stalkBroken = false;
            everEnteredCell = false;
            maxX = tr.startX;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d) stalk=(%d,%d,%d)\n",
                        tr.name, tr.kind, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ(),
                        tr.stalkLo.getX(), tr.stalkLo.getY(), tr.stalkLo.getZ()));
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
                // EXACT arrival, like GateCourse. The default comeTo tolerance is the FOLLOW distance and
                // would call a short tile "arrived" from the start pad without the bot ever moving.
                bot.comeTo(tr.goal, 0.75, 0.75, 0);
                return;
            }

            attemptTicks++;

            boolean present = bambooAt(tr.stalkLo);
            if (!present && !stalkBroken) {
                stalkBroken = true;
                try {
                    trace.write(String.format(Locale.ROOT,
                            "  BREAK tick=%d bot=(%.2f,%.2f,%.2f) — feet-cell stalk gone (hi=%s)\n",
                            attemptTicks, bot.getX(), bot.getY(), bot.getZ(),
                            bambooAt(tr.stalkHi) ? "still there" : "cascaded"));
                } catch (IOException ignored) { }
            }
            if (bot.blockPosition().getX() == tr.stalkLo.getX()
                    && bot.blockPosition().getZ() == tr.stalkLo.getZ()) {
                everEnteredCell = true;
            }
            if (bot.getX() > maxX) {
                maxX = bot.getX();
            }

            trace(tr);

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            if (bot.getY() < tr.minFloorY) {
                record(tr, "FAIL", "fell off the tile");
                return;
            }
            double dx = bot.getX() - (tr.goal.getX() + 0.5);
            double dy = bot.getY() - tr.goal.getY();
            double dz = bot.getZ() - (tr.goal.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            if (dist < 0.75) {
                if (bambooAt(tr.stalkLo)) {
                    // Reaching the goal with the post still standing means the tile did not test what it
                    // claims to (a route around the stalk exists) — a harness fault, not a bot pass.
                    record(tr, "FAIL", "reached goal with the stalk INTACT — tile does not force the break");
                } else {
                    record(tr, "PASS", "broke the stalk and crossed");
                }
                return;
            }

            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", everEnteredCell
                        ? "WEDGED in the stalk cell — never broke it (the flagship wedge)"
                        : "timeout short of the stalk cell");
            }
        }

        boolean bambooAt(BlockPos pos) {
            return level.getBlockState(pos).is(Blocks.BAMBOO);
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
                if (!move.equals(prevMove)) {
                    trace.write(String.format(Locale.ROOT, "  MOVE %s at bot=(%.2f,%.2f,%.2f)\n", move, x, y, z));
                    prevMove = move;
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-8s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %.1f | stalk=%s | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y,
                        grnd ? 1 : 0, bot.getHealth(), bambooAt(tr.stalkLo) ? "B" : ".", move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.3f,%.1f,%.3f) stalk=%s"
                            + " enteredCell=%s maxX=%.3f lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), bambooAt(tr.stalkLo) ? "INTACT" : "broken",
                    everEnteredCell, maxX, bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/bamboo] {} -> {} ({}) closest={} ticks={} stalk={} enteredCell={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, bambooAt(tr.stalkLo) ? "INTACT" : "broken", everEnteredCell);
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        /**
         * A 2-tall stone-boxed corridor whose ONLY through-route is the bamboo cell. The stalk is placed
         * LAST so its {@code canSurvive} support (the floor below) is already in place.
         */
        void buildTile(Trial tr) {
            int y = tr.y;
            int x0 = BAMBOO_X - 6, x1 = BAMBOO_X + 6;
            int z0 = Math.min(BAMBOO_Z, tr.exitZ) - 1, z1 = Math.max(BAMBOO_Z, tr.exitZ) + 1;
            if (tr.kind == Kind.TURNHOLD) {
                z0 = BAMBOO_Z - 8; // room for the +Z approach leg
            }

            // Solid block, then carve the corridor out of it — walls/roof/floor come free.
            box(x0, x1, y, y + 3, z0, z1);
            if (tr.kind == Kind.TURNHOLD) {
                // The approach runs +Z up the column ONE WEST of the stalk, then turns east into it. The
                // bot therefore arrives at the hold cell (BAMBOO_X-1, BAMBOO_Z) carrying +Z momentum that
                // is PERPENDICULAR to the recenter anchor it is about to hold on — the flagship's state.
                for (int z = BAMBOO_Z - 7; z <= BAMBOO_Z; z++) {
                    carve(BAMBOO_X - 1, BAMBOO_X - 1, y + 1, y + 2, z);
                }
            }
            // West approach + the stalk cell itself, along the arrival line. TURNHOLD gets only the last
            // cell of it — its approach is the +Z leg above, and a carved west corridor would hand the bot
            // an ALIGNED entry, which is exactly the condition the other tiles already prove is survivable.
            carve(tr.kind == Kind.TURNHOLD ? BAMBOO_X - 1 : x0, BAMBOO_X, y + 1, y + 2, BAMBOO_Z);
            // East exit, along the (possibly dog-legged) departure line.
            carve(BAMBOO_X, x1, y + 1, y + 2, tr.exitZ);
            if (tr.kind == Kind.DOGLEG || tr.kind == Kind.TRAPPED_DOGLEG) {
                // The Diagonal's two corner cells, exactly as the flagship had them: both AIR, so the
                // corner is geometrically clear and the ONLY obstruction is the offset post.
                carve(BAMBOO_X + 1, BAMBOO_X + 1, y + 1, y + 2, BAMBOO_Z);
                carve(BAMBOO_X, BAMBOO_X, y + 1, y + 2, BAMBOO_Z + 1);
            }
            // The stalk: feet cell + head cell. Breaking the lower one cascades the upper via
            // canSurvive -> scheduleTick -> destroyBlock, so a folded BREAK on the upper may find it gone.
            set(BAMBOO_X, y + 1, BAMBOO_Z, BAMBOO);
            set(BAMBOO_X, y + 2, BAMBOO_Z, BAMBOO);
        }

        /** Dump the placed stalk + the computed post span — the "is this tile actually a wall?" check. */
        void probeTile(Trial tr) {
            BlockState lo = level.getBlockState(tr.stalkLo);
            OrebitCommon.LOGGER.info("[Orebit/bamboo] {} probe {} = {} (post x-span [{}, {}])",
                    tr.name, tr.stalkLo.toShortString(), lo,
                    String.format(Locale.ROOT, "%.5f", BAMBOO_X + 6.5 / 16.0 + 0.25),
                    String.format(Locale.ROOT, "%.5f", BAMBOO_X + 9.5 / 16.0 + 0.25));
            try {
                trace.write(String.format(Locale.ROOT, "  PROBE %s = %s\n", tr.stalkLo.toShortString(), lo));
            } catch (IOException ignored) { }
        }

        void box(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, STONE);
        }

        void carve(int x0, int x1, int y0, int y1, int z) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    set(x, y, z, AIR);
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
                OrebitCommon.LOGGER.error("[Orebit/bamboo] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/bamboo] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-bamboo-exit");
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
